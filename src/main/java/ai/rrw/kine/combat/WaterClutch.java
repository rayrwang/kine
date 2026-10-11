package ai.rrw.kine.combat;

import ai.rrw.kine.Settings;
import ai.rrw.kine.mixin.MinecraftInvoker;
import ai.rrw.kine.util.Hotbar;
import ai.rrw.kine.util.InventoryClicks;
import ai.rrw.kine.util.MouseTurn;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Random;

/**
 * Automatic water-bucket clutch. While falling far enough to get hurt, it predicts where you will land;
 * once that spot is within reach it places water there, then scoops it back up once you are down.
 * It runs only in free fall, never while gliding (a broken elytra stops gliding, and then it runs).
 * <ul>
 *   <li><b>Trajectory aim.</b> It simulates the fall (gravity and drag) to find the real landing point,
 *       so diagonal falls work too.</li>
 *   <li><b>Sneak placement.</b> Water used on a waterloggable block (slab, stairs, fence) normally
 *       waterlogs it and leaves your feet dry, and using it on a chest or door opens it. While sneaking,
 *       the game skips the block and puts the source in the block above. Sneak is held as a key from the
 *       start of the dangerous fall to the end of the clutch, so the server has it before the use.</li>
 *   <li><b>As a skilled player does it.</b> From the moment a fall would hurt, the view turns toward where you
 *       will land (straight down until a landing is in sight) at auto aim's eased rate, so the use needs only a
 *       small correction rather than a flick. The water goes in at a varied tick of the window in reach, keeping a
 *       tick in hand before impact where the window allows. After the scoop the view eases back to where it was,
 *       unless you move it first. The place and the scoop go through the game's own pick and startUseItem just
 *       before its keybind step, after a turn by whole mouse counts, so the use packets and that tick's move
 *       packet carry the same rotation.</li>
 *   <li><b>Bucket staging.</b> When a dangerous fall starts and the only water bucket is in the main
 *       inventory, it is swapped into the hotbar (an empty slot if there is one, else the largest stack) as a
 *       player would: one tick with the inventory "opened" (every key up, InventoryClicks.requestOpen), the swap
 *       at that tick's end, then the close.</li>
 * </ul>
 * It does nothing where water evaporates (the Nether).
 */
public final class WaterClutch {
    private WaterClutch() {}

    private static final double HURT_MARGIN   = 0.5;   // a fall this far past the safe fall distance is worth clutching
    private static final double PLACE_REACH   = 4.4;   // place once the landing point is this close to the eye (just inside the 4.5 reach)
    // how far ahead to simulate the fall (ticks): 3 s, about 150 blocks at full falling speed, so a safe landing
    // (water, slime) is seen from the start of most falls; at 12 a 20-block fall into water first looked like a long
    // drop, armed, and turned the view until the water came in sight (client test, 2026-10-11)
    private static final int    SIM_TICKS     = 60;
    private static final int    GIVE_UP_TICKS = 20;    // stop holding the pose if the clutch never resolves
    private static final double H_DRAG        = 0.91;  // per-tick horizontal air drag
    private static final double GRAVITY       = 0.08;  // per-tick gravity
    private static final double V_DRAG        = 0.98;  // per-tick vertical drag
    private static final float  PILOT_EPS     = 0.4f;  // a view change (deg) we didn't make: the pilot is looking around
    private static final double EDGE_IN       = 0.1;   // aim at least this far inside the edge of the surface under the hitbox
    private static final Random RNG = new Random();

    /** Where the fall lands: a point on the surface, and in how many ticks (0: during this tick's move). With safe set,
     *  something that takes no fall damage catches the fall first (catchesFall), and no clutch is wanted. */
    private record Impact(Vec3 point, int ticks, boolean safe) {}

    private static boolean armed = false;              // a dangerous fall with a bucket to hand: sneak, look down, watch
    private static int     sneakTicks = 0;             // ticks the sneak key has been sent for the clutch
    private static boolean fetching = false;           // the bucket is being brought into the hotbar (the open tick)
    private static int     placeAt = -1;               // ticks-before-impact to place at, picked when in reach
    // state from placing the water to picking it up
    private static boolean active = false;
    private static InteractionHand clutchHand = InteractionHand.MAIN_HAND;
    private static Vec3    sourceSpot = null;          // where the water went, so the pickup can aim back at it
    private static int     restoreSlot = -1;           // hotbar slot to switch back to (-1: no switch)
    private static int     ticks = 0;
    // the view: where it was when the fall began, and easing back there afterwards
    private static boolean tracking = false;           // the view has been turned for this fall
    private static float   restoreYaw = 0f, restorePitch = 0f;
    private static boolean returning = false;
    private static float   lastSetYaw, lastSetPitch;   // the view after our last turn

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(WaterClutch::tick);
    }

    /** Hold sneak while armed or mid-clutch (not in the tick the inventory "opens", when every key is up). */
    public static Input adjustInput(LocalPlayer p, Input keys) {
        if (!Settings.waterBucketClutch || !(armed || active) || InventoryClicks.isOpenTick()) { sneakTicks = 0; return keys; }
        sneakTicks++;
        if (keys.shift()) return keys;
        return new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), true, keys.sprint());
    }

    /** A tick its sneak key wasn't pressed (a screen is open): the server must have the sneak again before a use. */
    public static void sneakNotSent() {
        sneakTicks = 0;
    }

    // End of tick: arm or disarm, bring the bucket to the hotbar, turn the view toward the landing, and ease it
    // back after a clutch.
    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null || !Settings.waterBucketClutch) {
            if (active) finish(mc, p);
            armed = false; fetching = false; tracking = false; returning = false;
            return;
        }
        if (fetching && InventoryClicks.isOpenTick()) { stageBucketToHotbar(mc, p); fetching = false; }   // the open tick's end
        if (active) {
            // a dry landing, gliding, or timed out (standing in the water also counts as on the ground: the
            // scoop happens before the next keybind step)
            if (p.onGround() && !p.isInWater() || p.isFallFlying() || ++ticks > GIVE_UP_TICKS) finish(mc, p);
        } else {
            boolean was = armed;
            armed = dangerousFall(mc, p) && haveBucket(p) && !evaporates(mc, p);
            if (armed && !was) {   // a new dangerous fall: remember the view, and bring the bucket up if it's in the pack
                if (!tracking && !returning) { restoreYaw = p.getYRot(); restorePitch = p.getXRot(); }
                tracking = true; returning = false; placeAt = -1;
                if (needsFetch(p) && InventoryClicks.available(mc, p)) { InventoryClicks.requestOpen(); fetching = true; }
            }
            if (armed) track(mc, p);
            else if (tracking) startReturn(p);   // the fall ended without a clutch (water, a ladder): look back
        }
        if (returning && !armed && !active) stepReturn(mc, p);
    }

    // A fall that will hurt: falling (not gliding, swimming or in lava), and either already past the safe fall
    // distance or about to land where the total fall would pass it, or with no ground in sight (a long drop).
    // Judged ahead of time, so the view has the whole fall to turn. A player who may fly (creative) takes no fall
    // damage (Player.causeFallDamage), and there the bucket stays full, so the water would be left behind.
    private static boolean dangerousFall(Minecraft mc, LocalPlayer p) {
        // survival only (RW): creative and spectator take no fall damage, and in adventure a bucket places only
        // on blocks its can_place_on lists (Player.mayUseItemAt)
        if (mc.gameMode == null || mc.gameMode.getPlayerMode() != GameType.SURVIVAL) return false;
        if (p.getAbilities().mayfly) return false;
        if (p.isFallFlying() || p.onGround() || p.isInWater() || p.isInLava() || p.getDeltaMovement().y() >= 0) return false;
        double hurt = p.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) + HURT_MARGIN;
        Impact imp = predictImpact(mc, p);
        // a fall into water or onto something else that takes no fall damage needs no clutch, however long (RW: a
        // player bobbing at the surface pops out of the water for a moment, and the fall then seemed to reach the bottom)
        if (imp != null && imp.safe()) return false;
        if (p.fallDistance >= hurt) return true;
        return imp == null || p.fallDistance + (p.getY() - imp.point().y()) >= hurt;
    }

    // Turn toward where the fall lands, or straight down until a landing is in sight, at auto aim's eased rate.
    private static void track(Minecraft mc, LocalPlayer p) {
        Impact imp = predictImpact(mc, p);
        float[] look = imp != null && !imp.safe() ? lookFor(mc, p, imp.point(), null, ClipContext.Fluid.NONE) : new float[]{ p.getYRot(), 90f };
        turnToward(mc, p, look[0], look[1]);
    }

    // Where to look to use the bucket on a point below. Straight down, keeping the yaw, when a ray straight down
    // meets the same surface (or, for the scoop, the same water block): from nearly right above it, the yaw toward
    // a point a few hundredths of a block to one side swings all over as you drift, which spins the view about the
    // vertical. Both the exact vertical and the steepest pitch whole mouse counts reach are tested, so a ray just
    // off vertical by a block edge can't meet the next column. Otherwise at the point (under the edge of a ledge).
    private static float[] lookFor(Minecraft mc, LocalPlayer p, Vec3 target, BlockPos block, ClipContext.Fluid fluid) {
        Vec3 eye = p.getEyePosition();
        double reachDown = eye.y - target.y + 2.0;
        Vec3 steepest = Ballistics.dirOf(p.getYRot(), 90.0 - MouseTurn.sens(mc, p) * 0.15);
        if (meets(p, eye, new Vec3(0, -1, 0).scale(reachDown), target, block, fluid)
            && meets(p, eye, steepest.scale(reachDown / Math.max(0.5, -steepest.y)), target, block, fluid))
            return new float[]{ p.getYRot(), 90f };
        Vec3 d = target.subtract(eye);
        return new float[]{ (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))) };
    }

    // The block a full bucket fills from the current rotation, as BucketItem.use picks it: its ray (Item.getPlayerPOVHitResult:
    // block outlines, fluids ignored, out to the interaction range) hits a block, and the water goes into that block
    // if it can hold water, else into the one in front of the face hit. Null if the ray hits nothing.
    private static BlockPos fillTarget(LocalPlayer p) {
        Vec3 eye = p.getEyePosition();
        BlockHitResult h = p.level().clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(p.blockInteractionRange())),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (h.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = h.getBlockPos();
        return p.level().getBlockState(pos).getBlock() instanceof LiquidBlockContainer ? pos : pos.relative(h.getDirection());
    }

    // Whether a ray from the eye meets the target: the given block, or else a top face at the target's height.
    private static boolean meets(LocalPlayer p, Vec3 eye, Vec3 ray, Vec3 target, BlockPos block, ClipContext.Fluid fluid) {
        BlockHitResult h = p.level().clip(new ClipContext(eye, eye.add(ray), ClipContext.Block.OUTLINE, fluid, p));
        if (h.getType() != HitResult.Type.BLOCK) return false;
        return block != null ? h.getBlockPos().equals(block)
                             : h.getDirection() == Direction.UP && Math.abs(h.getLocation().y - target.y) < 0.01;
    }

    private static void turnToward(Minecraft mc, LocalPlayer p, float yaw, float pitch) {
        double count = MouseTurn.sens(mc, p) * 0.15;
        float dYaw = AimControl.step(Mth.wrapDegrees(yaw - p.getYRot()), 0f, count);
        float dPitch = AimControl.step(pitch - p.getXRot(), 0f, count);
        MouseTurn.toward(p, p.getYRot() + dYaw, Mth.clamp(p.getXRot() + dPitch, -90f, 90f));
        lastSetYaw = p.getYRot(); lastSetPitch = p.getXRot();
    }

    private static void startReturn(LocalPlayer p) {
        tracking = false; returning = true;
        lastSetYaw = p.getYRot(); lastSetPitch = p.getXRot();
    }

    // One step of easing the view back to where it was before the fall; the pilot moving it ends the return.
    private static void stepReturn(Minecraft mc, LocalPlayer p) {
        if (Math.abs(Mth.wrapDegrees(p.getYRot() - lastSetYaw)) > PILOT_EPS || Math.abs(p.getXRot() - lastSetPitch) > PILOT_EPS) {
            returning = false;
            return;
        }
        turnToward(mc, p, restoreYaw, restorePitch);
        double count = MouseTurn.sens(mc, p) * 0.15;
        if (Math.abs(Mth.wrapDegrees(restoreYaw - p.getYRot())) <= count && Math.abs(restorePitch - p.getXRot()) <= count) returning = false;
    }

    /** Place or scoop, just before the game's keybind step (VanillaActions). True if it acted or holds the view. */
    public static boolean beforeKeybinds(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!Settings.waterBucketClutch || p == null) return false;
        if (active) { resolve(mc, p); return true; }
        if (!armed || sneakTicks < 1 || fetching) return false;   // the server must already have the sneak

        Impact imp = predictImpact(mc, p);
        if (imp == null || imp.safe()) return false;         // no landing in range yet, or none that needs water
        if (imp.point().distanceTo(p.getEyePosition()) > PLACE_REACH) return false;   // not close enough yet
        // a varied tick in the window, keeping one tick in hand before impact where the window allows
        if (placeAt < 0) placeAt = imp.ticks() <= 1 ? imp.ticks() : 1 + RNG.nextInt(imp.ticks());
        if (imp.ticks() > placeAt) return false;
        if (!selectWaterBucket(p)) return false;

        float[] look = lookFor(mc, p, imp.point(), null, ClipContext.Fluid.NONE);   // the bucket's own ray ignores fluids when full
        MouseTurn.toward(p, look[0], look[1]);   // the last small correction: the view has been turning this way all fall
        BlockPos placed = fillTarget(p);   // read before the water is there
        MinecraftInvoker game = (MinecraftInvoker) mc;
        game.invokePick(1.0F);
        game.invokeStartUseItem();   // the bucket raycasts along this rotation (the packet carries it) -> source at the feet block
        if (p.getItemInHand(clutchHand).is(Items.BUCKET)) {
            // the scoop goes for the block the bucket filled, not the block under the aim point: looking straight
            // down from beside a block edge, the two differ
            sourceSpot = placed != null ? Vec3.atCenterOf(placed)
                       : new Vec3(imp.point().x(), imp.point().y() + 0.5, imp.point().z());   // the source sits in the block just above the surface
            active = true;
            ticks = 0;
        } else {
            finish(mc, p);
        }
        return true;
    }

    /** Keep aiming at the placed water until you are in it, then scoop it back up. */
    private static void resolve(Minecraft mc, LocalPlayer p) {
        // keep looking at the water (it can be off-centre under an edge), so the pickup ray hits it
        if (sourceSpot != null) {
            float[] look = lookFor(mc, p, sourceSpot, BlockPos.containing(sourceSpot), ClipContext.Fluid.SOURCE_ONLY);   // an empty bucket's ray
            MouseTurn.toward(p, look[0], look[1]);
        } else MouseTurn.toward(p, p.getYRot(), 90f);
        if (p.isInWater()) {
            MinecraftInvoker game = (MinecraftInvoker) mc;
            game.invokePick(1.0F);
            game.invokeStartUseItem();   // the hand holds an empty bucket now, so this scoops the source back up
            finish(mc, p);
        }
    }

    private static void finish(Minecraft mc, LocalPlayer p) {
        if (p != null) {
            startReturn(p);   // ease back from the end of this tick, after the use's rotation has gone out
            if (restoreSlot >= 0) p.getInventory().setSelectedSlot(restoreSlot);   // the game sends the change, as for a number key
        }
        active = false;
        armed = false;
        sourceSpot = null;
        restoreSlot = -1;
        ticks = 0;
        placeAt = -1;
    }

    private static boolean evaporates(Minecraft mc, LocalPlayer p) {
        Boolean e = (Boolean) mc.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, p.blockPosition());
        return e != null && e;
    }

    private static boolean haveBucket(LocalPlayer p) {
        if (p.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.WATER_BUCKET)) return true;
        for (int i = 0; i < 36; i++) if (p.getInventory().getNonEquipmentItems().get(i).is(Items.WATER_BUCKET)) return true;
        return false;
    }

    // The only water bucket is in the main inventory, not the hotbar or the offhand.
    private static boolean needsFetch(LocalPlayer p) {
        if (p.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.WATER_BUCKET)) return false;
        for (int i = 0; i < 9; i++) if (p.getInventory().getNonEquipmentItems().get(i).is(Items.WATER_BUCKET)) return false;
        return true;
    }

    /**
     * Simulate the fall (gravity and drag) and return a point on top of the surface you will land on and in how
     * many ticks, or null if no landing is near. Each tick it tests the hitbox's footprint against the collision
     * shapes of every block under it, as the game's own collision does, so a thin shape under an edge of the
     * hitbox (a fence post, an end rod) is found; a test at the corners would miss it and place the water
     * a block too low. It takes the highest top the footprint touches and aims at the part of it under the
     * footprint. A fall caught before that top by anything that takes no fall damage (catchesFall) is safe.
     */
    private static Impact predictImpact(Minecraft mc, LocalPlayer p) {
        AABB box = p.getBoundingBox();
        Vec3 vel = p.getDeltaMovement();
        double offX = 0, offY = 0, offZ = 0;
        double vx = vel.x(), vy = vel.y(), vz = vel.z();
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int i = 0; i < SIM_TICKS; i++) {
            double botNow = box.minY + offY;            // underside of the box now
            double botNext = botNow + vy;               // and after this tick of falling
            // footprint, swept to cover this tick's horizontal motion
            double fpMinX = Math.min(box.minX + offX, box.minX + offX + vx);
            double fpMaxX = Math.max(box.maxX + offX, box.maxX + offX + vx);
            double fpMinZ = Math.min(box.minZ + offZ, box.minZ + offZ + vz);
            double fpMaxZ = Math.max(box.maxZ + offZ, box.maxZ + offZ + vz);
            double cx = (fpMinX + fpMaxX) / 2, cz = (fpMinZ + fpMaxZ) / 2;

            double bestTop = Double.NEGATIVE_INFINITY, aimX = 0, aimZ = 0;
            BlockState bestState = null;
            int x0 = Mth.floor(fpMinX), x1 = Mth.floor(fpMaxX);
            int z0 = Mth.floor(fpMinZ), z1 = Mth.floor(fpMaxZ);
            int yHi = Mth.floor(botNow + 0.001), yLo = Mth.floor(botNext) - 2;   // -2 covers tall shapes (fences and walls reach 1.5)
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int y = yHi; y >= yLo; y--) {
                        cur.set(x, y, z);
                        VoxelShape shape = mc.level.getBlockState(cur).getCollisionShape(mc.level, cur);
                        if (shape.isEmpty()) continue;
                        AABB b = shape.bounds();
                        double sxMin = x + b.minX, sxMax = x + b.maxX, szMin = z + b.minZ, szMax = z + b.maxZ;
                        if (sxMax <= fpMinX || sxMin >= fpMaxX || szMax <= fpMinZ || szMin >= fpMaxZ) continue;  // no overlap with the footprint
                        double top = y + b.maxY;
                        if (top > botNow + 0.001 || top < botNext - 0.001) continue;   // outside this tick's descent window
                        if (top > bestTop) {
                            bestTop = top; bestState = mc.level.getBlockState(cur);
                            aimX = aimInside(cx, Math.max(fpMinX, sxMin), Math.min(fpMaxX, sxMax));  // aim where the footprint sits on this shape
                            aimZ = aimInside(cz, Math.max(fpMinZ, szMin), Math.min(fpMaxZ, szMax));
                        }
                    }
                }
            }
            double landBottom = bestTop > Double.NEGATIVE_INFINITY ? bestTop : botNext;   // where the box's bottom gets this tick
            if (catchesFall(mc, fpMinX, fpMaxX, fpMinZ, fpMaxZ, cx, cz, botNow, landBottom)
                || bestState != null && bestState.is(Blocks.SLIME_BLOCK) && !armed && !p.isShiftKeyDown())   // slime: no damage unless sneaking
                return new Impact(new Vec3(cx, landBottom, cz), i, true);
            if (bestTop > Double.NEGATIVE_INFINITY) return new Impact(new Vec3(aimX, bestTop, aimZ), i, false);

            offX += vx; offY += vy; offZ += vz;
            vx *= H_DRAG; vz *= H_DRAG; vy = (vy - GRAVITY) * V_DRAG;
        }
        return null;
    }

    /**
     * Whether the box, its bottom going down from botNow to landBottom over the footprint, meets something that takes
     * the fall's damage away first, tested over the whole footprint as the game tests the whole box (26.1.2):
     * water anywhere under it, its bottom going below a water surface, waterlogged blocks included (the fluid box is
     * the hitbox shrunk by 0.001, and water resets the fall, Entity.updateFluidInteraction); a cobweb, powder snow or a
     * sweet berry bush it enters (makeStuckInBlock resets the fall; powder snow's landing does no damage either);
     * and a climbable block at the feet, where only the block the feet's centre is in counts (LivingEntity.onClimbable).
     */
    private static boolean catchesFall(Minecraft mc, double fpMinX, double fpMaxX, double fpMinZ, double fpMaxZ,
                                       double cx, double cz, double botNow, double landBottom) {
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        int x0 = Mth.floor(fpMinX), x1 = Mth.floor(fpMaxX), z0 = Mth.floor(fpMinZ), z1 = Mth.floor(fpMaxZ);
        int yHi = Mth.floor(botNow + 0.001), yLo = Mth.floor(landBottom - 0.001);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = yHi; y >= yLo; y--) {
                    cur.set(x, y, z);
                    BlockState s = mc.level.getBlockState(cur);
                    FluidState f = s.getFluidState();
                    if (f.is(FluidTags.WATER) && y + f.getHeight(mc.level, cur) - 0.001 > landBottom) return true;
                    if ((s.is(Blocks.COBWEB) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.SWEET_BERRY_BUSH)) && y + 1 > landBottom + 0.001) return true;
                }
            }
        }
        int fx = Mth.floor(cx), fz = Mth.floor(cz);
        for (int y = yHi; y >= yLo; y--) {
            cur.set(fx, y, fz);
            if (y + 1 > landBottom + 0.001 && mc.level.getBlockState(cur).is(BlockTags.CLIMBABLE)) return true;
        }
        return false;
    }

    /** Where to aim on one axis: the point of [lo, hi] (the part of the surface under the hitbox) nearest c, kept
     *  EDGE_IN inside its ends (or at its middle if it is narrower). Aimed at the very edge, the turn's rounding to
     *  whole counts puts the bucket's ray just past it about half the time, onto the side face, and the water goes
     *  in beside and below the landing. */
    static double aimInside(double c, double lo, double hi) {
        double in = Math.min(EDGE_IN, (hi - lo) / 2);
        return Mth.clamp(c, lo + in, hi - in);
    }

    /** At the end of the tick the inventory "opened" in: swap the main-inventory water bucket into the hotbar,
     *  then close as a vanilla client does. */
    private static void stageBucketToHotbar(Minecraft mc, LocalPlayer p) {
        MultiPlayerGameMode gm = mc.gameMode;
        AbstractContainerMenu menu = p.inventoryMenu;
        if (gm == null || !needsFetch(p) || !InventoryClicks.available(mc, p)) return;   // a screen or container is open, or the cursor is busy

        int src = -1;                                            // inventory menu slot holding the bucket (9 to 35: main inventory)
        for (int i = 9; i <= 35; i++) {
            if (menu.slots.get(i).getItem().is(Items.WATER_BUCKET)) { src = i; break; }
        }
        if (src < 0) return;                                     // no bucket in the main inventory
        if (!InventoryClicks.claimBatch()) return;               // another feature clicked in this opening

        // hotbar slot: empty first, else the largest stack (the hotbar is inventory menu slots 36 to 44)
        int target = Hotbar.slotToDisplace(hb -> menu.slots.get(36 + hb).getItem());
        gm.handleContainerInput(menu.containerId, src, target, ContainerInput.SWAP, p);   // number-key swap of the bucket and the hotbar slot
        InventoryClicks.closeAsVanilla(mc, p);
    }

    /** Put a water bucket in a hand for startUseItem, which tries the main hand first: a hotbar bucket is
     *  selected; an offhand bucket is used with an empty main hand, selecting an empty hotbar slot if need be.
     *  Selecting a slot is a number-key press: the game sends the change before the use. */
    private static boolean selectWaterBucket(LocalPlayer p) {
        restoreSlot = -1;
        var inv = p.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inv.getNonEquipmentItems().get(slot).is(Items.WATER_BUCKET)) {
                clutchHand = InteractionHand.MAIN_HAND;
                if (inv.getSelectedSlot() != slot) { restoreSlot = inv.getSelectedSlot(); inv.setSelectedSlot(slot); }
                return true;
            }
        }
        if (!p.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.WATER_BUCKET)) return false;
        clutchHand = InteractionHand.OFF_HAND;
        if (p.getMainHandItem().isEmpty()) return true;
        for (int slot = 0; slot < 9; slot++) {
            if (inv.getNonEquipmentItems().get(slot).isEmpty()) { restoreSlot = inv.getSelectedSlot(); inv.setSelectedSlot(slot); return true; }
        }
        return false;   // the main hand would be used first and there is no empty slot to switch to
    }
}
