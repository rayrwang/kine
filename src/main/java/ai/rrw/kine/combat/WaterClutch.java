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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

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
 *   <li><b>As a vanilla client does it.</b> The place and the scoop go through the game's own pick and
 *       startUseItem just before its keybind step, after a turn by whole mouse counts, so the use packets
 *       and that tick's move packet carry the same rotation; the view turns back a tick later.</li>
 *   <li><b>Bucket staging.</b> When a dangerous fall starts and the only water bucket is in the main
 *       inventory, it is swapped into the hotbar (an empty slot if there is one, else the largest stack)
 *       between ticks, followed by the inventory close packet.</li>
 * </ul>
 * It does nothing where water evaporates (the Nether).
 */
public final class WaterClutch {
    private WaterClutch() {}

    private static final double HURT_FALL     = 3.5;   // only bother once the fall would actually do damage
    private static final double PLACE_REACH   = 4.4;   // place once the landing point is this close to the eye (just inside the 4.5 reach)
    private static final int    SIM_TICKS     = 12;    // how far ahead to simulate the fall (ticks)
    private static final int    GIVE_UP_TICKS = 20;    // stop holding the pose if the clutch never resolves
    private static final double H_DRAG        = 0.91;  // per-tick horizontal air drag
    private static final double GRAVITY       = 0.08;  // per-tick gravity
    private static final double V_DRAG        = 0.98;  // per-tick vertical drag

    private static boolean armed = false;              // a dangerous fall with a bucket to hand: sneak and watch
    private static int     sneakTicks = 0;             // ticks the sneak key has been held for the clutch
    // state from placing the water to picking it up
    private static boolean active = false;
    private static InteractionHand clutchHand = InteractionHand.MAIN_HAND;
    private static Vec3    sourceSpot = null;          // where the water went, so the pickup can aim back at it
    private static int     restoreSlot = -1;          // hotbar slot to switch back to (-1: no switch)
    private static boolean turned = false;            // the view was turned for the clutch
    private static float   restorePitch = 0f;
    private static float   restoreYaw = 0f;
    private static boolean restorePending = false;    // turn the view back at the end of this tick
    private static int     ticks = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(WaterClutch::tick);
    }

    /** Hold sneak while armed or mid-clutch. */
    public static Input adjustInput(LocalPlayer p, Input keys) {
        if (!Settings.waterBucketClutch || !(armed || active)) { sneakTicks = 0; return keys; }
        sneakTicks++;
        if (keys.shift()) return keys;
        return new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), true, keys.sprint());
    }

    // End of tick: arm or disarm, stage the bucket, and turn the view back after a finished clutch.
    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (restorePending && p != null) {
            MouseTurn.toward(p, restoreYaw, restorePitch);
            restorePending = false;
        }
        if (p == null || mc.level == null || mc.gameMode == null || !Settings.waterBucketClutch) {
            if (active) finish(mc, p);
            armed = false;
            return;
        }
        if (active) {
            // a dry landing, gliding, or timed out (standing in the water also counts as on the ground: the
            // scoop happens before the next keybind step)
            if (p.onGround() && !p.isInWater() || p.isFallFlying() || ++ticks > GIVE_UP_TICKS) finish(mc, p);
            return;
        }
        armed = dangerousFall(p) && haveBucket(p) && !evaporates(mc, p);
        if (armed) stageBucketToHotbar(mc, p);   // move a water bucket into the hotbar early, while there is still time
    }

    private static boolean dangerousFall(LocalPlayer p) {
        return !p.isFallFlying() && !p.onGround() && !p.isInWater() && !p.isInLava()
            && p.fallDistance >= HURT_FALL && p.getDeltaMovement().y() < 0;
    }

    /** Place or scoop, just before the game's keybind step (VanillaActions). True if it acted or holds the view. */
    public static boolean beforeKeybinds(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!Settings.waterBucketClutch || p == null) return false;
        if (active) { resolve(mc, p); return true; }
        if (!armed || sneakTicks < 1) return false;           // the server must already have the sneak

        Vec3 land = predictImpact(mc, p);
        if (land == null) return false;                       // no landing in range yet
        Vec3 eye = p.getEyePosition();
        if (land.distanceTo(eye) > PLACE_REACH) return false; // not close enough yet
        if (!selectWaterBucket(p)) return false;

        restoreYaw = p.getYRot();
        restorePitch = p.getXRot();
        turned = true;
        aimAt(p, land);
        MinecraftInvoker game = (MinecraftInvoker) mc;
        game.invokePick(1.0F);
        game.invokeStartUseItem();   // the bucket raycasts along this rotation (the packet carries it) -> source at the feet block
        if (p.getItemInHand(clutchHand).is(Items.BUCKET)) {
            sourceSpot = new Vec3(land.x(), land.y() + 0.5, land.z());   // the source sits in the block just above the surface
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
        if (sourceSpot != null) aimAt(p, sourceSpot);
        else MouseTurn.toward(p, p.getYRot(), 90f);
        if (p.isInWater()) {
            MinecraftInvoker game = (MinecraftInvoker) mc;
            game.invokePick(1.0F);
            game.invokeStartUseItem();   // the hand holds an empty bucket now, so this scoops the source back up
            finish(mc, p);
        }
    }

    private static void aimAt(LocalPlayer p, Vec3 target) {
        Vec3 d = target.subtract(p.getEyePosition());
        double dh = Math.sqrt(d.x * d.x + d.z * d.z);
        MouseTurn.toward(p, (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) Math.toDegrees(-Math.atan2(d.y, dh)));
    }

    private static void finish(Minecraft mc, LocalPlayer p) {
        if (p != null) {
            if (turned) restorePending = true;   // turn back at the end of this tick, after the use's rotation has gone out
            if (restoreSlot >= 0) p.getInventory().setSelectedSlot(restoreSlot);   // the game sends the change, as for a number key
        }
        active = false;
        armed = false;
        turned = false;
        sourceSpot = null;
        restoreSlot = -1;
        ticks = 0;
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

    /**
     * Simulate the fall (gravity and drag) and return a point on top of the surface you will land on, or
     * null if no landing is near. Each tick it tests the hitbox's footprint against the collision shapes
     * of every block under it, as the game's own collision does, so a thin shape under an edge of the
     * hitbox (a fence post, an end rod) is found; a test at the corners would miss it and place the water
     * a block too low. It takes the highest top the footprint touches and aims at the part of it under the
     * footprint.
     */
    private static Vec3 predictImpact(Minecraft mc, LocalPlayer p) {
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
                            bestTop = top;
                            aimX = Mth.clamp(cx, Math.max(fpMinX, sxMin), Math.min(fpMaxX, sxMax));  // aim where the footprint sits on this shape
                            aimZ = Mth.clamp(cz, Math.max(fpMinZ, szMin), Math.min(fpMaxZ, szMax));
                        }
                    }
                }
            }
            if (bestTop > Double.NEGATIVE_INFINITY) return new Vec3(aimX, bestTop, aimZ);

            offX += vx; offY += vy; offZ += vz;
            vx *= H_DRAG; vz *= H_DRAG; vy = (vy - GRAVITY) * V_DRAG;
        }
        return null;
    }

    /** If the only water bucket is in the main inventory, swap it into the hotbar so it is ready. */
    private static void stageBucketToHotbar(Minecraft mc, LocalPlayer p) {
        if (p.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.WATER_BUCKET)) return;
        for (int i = 0; i < 9; i++) if (p.getInventory().getNonEquipmentItems().get(i).is(Items.WATER_BUCKET)) return;  // already in hotbar

        MultiPlayerGameMode gm = mc.gameMode;
        AbstractContainerMenu menu = p.inventoryMenu;
        if (gm == null || !InventoryClicks.available(mc, p)) return;   // a screen or container is open, or the cursor is busy

        int src = -1;                                            // inventory menu slot holding the bucket (9 to 35: main inventory)
        for (int i = 9; i <= 35; i++) {
            if (menu.slots.get(i).getItem().is(Items.WATER_BUCKET)) { src = i; break; }
        }
        if (src < 0) return;                                     // no bucket in the main inventory

        // hotbar slot: empty first, else the largest stack (the hotbar is inventory menu slots 36 to 44)
        int target = Hotbar.slotToDisplace(hb -> menu.slots.get(36 + hb).getItem());
        gm.handleContainerInput(menu.containerId, src, target, ContainerInput.SWAP, p);   // number-key swap of the bucket and the hotbar slot
        InventoryClicks.close(mc, p);
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
