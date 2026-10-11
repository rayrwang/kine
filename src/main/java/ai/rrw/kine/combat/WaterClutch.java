package ai.rrw.kine.combat;

import ai.rrw.kine.Settings;
import ai.rrw.kine.util.Hotbar;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
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
 *       waterlogs it and leaves your feet dry. While sneaking, the game puts the source in the block
 *       above instead. Sneak is sent in the input packet, which both the client prediction
 *       ({@code isShiftKeyDown()}) and the server read, so the two agree.</li>
 *   <li><b>Bucket staging.</b> When a dangerous fall starts and the only water bucket is in the main
 *       inventory, it is swapped into the hotbar (an empty slot if there is one, else the largest stack).</li>
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

    // state from placing the water to picking it up
    private static boolean active = false;
    private static InteractionHand clutchHand = InteractionHand.MAIN_HAND;
    private static Vec3    sourceSpot = null;          // where the water went, so the pickup can aim back at it
    private static int     restoreSlot = -1;          // hotbar slot to switch back to (-1: no switch, or the offhand)
    private static float   restorePitch = 0f;
    private static float   restoreYaw = 0f;
    private static int     ticks = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(WaterClutch::tick);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) { if (active) finish(mc, p); return; }
        if (!Settings.waterBucketClutch) { if (active) finish(mc, p); return; }

        if (active) { resolve(mc, p); return; }

        // arm only in a dangerous free fall (gliding has its own safety features)
        if (p.isFallFlying() || p.onGround() || p.isInWater() || p.isInLava()) return;
        if (p.fallDistance < HURT_FALL) return;
        if (p.getDeltaMovement().y() >= 0) return;            // must be descending

        // move a water bucket into the hotbar early, while there is still time
        stageBucketToHotbar(mc, p);

        // predict the landing point by sweeping the footprint against the blocks' collision shapes
        Vec3 land = predictImpact(mc, p);
        if (land == null) return;                             // no landing in range yet
        Vec3 eye = p.getEyePosition();
        double dx = land.x() - eye.x(), dy = land.y() - eye.y(), dz = land.z() - eye.z();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > PLACE_REACH) return;                       // not close enough yet

        // water evaporates in hot dimensions (the Nether)
        BlockPos placeCell = new BlockPos(Mth.floor(land.x()), Mth.floor(land.y() + 0.1), Mth.floor(land.z()));
        Boolean evaporates = (Boolean) mc.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, placeCell);
        if (evaporates != null && evaporates) return;

        // need a water bucket in a hand
        if (!selectWaterBucket(mc, p)) return;

        // aim at the landing point
        double dh = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float pitch = (float) (-Math.atan2(dy, dh) * 180.0 / Math.PI);
        restoreYaw = p.getYRot();
        restorePitch = p.getXRot();
        p.setYRot(yaw);
        p.setXRot(pitch);

        // place while sneaking, which puts the source above the surface instead of waterlogging it
        ClientPacketListener conn = mc.getConnection();
        Input cur = p.input.keyPresses;
        Input sneaking = new Input(cur.forward(), cur.backward(), cur.left(), cur.right(), cur.jump(), true, cur.sprint());
        p.input.keyPresses = sneaking;                                          // client predicts place-above (isShiftKeyDown reads this)
        if (conn != null) conn.send(new ServerboundPlayerInputPacket(sneaking)); // server sneaks before it sees the use packet
        mc.gameMode.useItem(p, clutchHand);   // bucket raycasts toward the impact (packet carries the rotation) -> source at the feet block
        p.input.keyPresses = cur;                                               // restore real input
        if (conn != null) conn.send(new ServerboundPlayerInputPacket(cur));     // and tell the server we stopped sneaking

        sourceSpot = new Vec3(land.x(), land.y() + 0.5, land.z());   // the source sits in the block just above the surface
        active = true;
        ticks = 0;
    }

    /** Keep aiming at the placed water until you are in it, then scoop it back up and restore the view. */
    private static void resolve(Minecraft mc, LocalPlayer p) {
        if (p == null) { finish(mc, null); return; }
        ticks++;

        // keep looking at the water (it can be off-centre under an edge), so the pickup ray hits it
        if (sourceSpot != null) {
            Vec3 eye = p.getEyePosition();
            double dx = sourceSpot.x() - eye.x(), dy = sourceSpot.y() - eye.y(), dz = sourceSpot.z() - eye.z();
            double dh = Math.sqrt(dx * dx + dz * dz);
            p.setYRot((float) (Math.atan2(-dx, dz) * 180.0 / Math.PI));
            p.setXRot((float) (-Math.atan2(dy, dh) * 180.0 / Math.PI));
        } else {
            p.setXRot(90f);
        }

        if (p.isInWater()) {
            mc.gameMode.useItem(p, clutchHand);   // the hand holds an empty bucket now, so this scoops the source back up
            finish(mc, p);
        } else if (p.onGround() || p.isFallFlying() || ticks > GIVE_UP_TICKS) {
            finish(mc, p);   // dry landing (missed), started gliding, or timed out: let go
        }
    }

    private static void finish(Minecraft mc, LocalPlayer p) {
        if (p != null) {
            p.setXRot(restorePitch);
            p.setYRot(restoreYaw);
            if (restoreSlot >= 0 && mc.getConnection() != null) {
                p.getInventory().setSelectedSlot(restoreSlot);
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(restoreSlot));
            }
        }
        active = false;
        sourceSpot = null;
        restoreSlot = -1;
        ticks = 0;
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
        AbstractContainerMenu menu = p.containerMenu;
        if (gm == null || menu != p.inventoryMenu || !menu.getCarried().isEmpty()) return;   // a container is open or the cursor is busy

        int src = -1;                                            // inventory menu slot holding the bucket (9 to 35: main inventory)
        for (int i = 9; i <= 35; i++) {
            if (menu.slots.get(i).getItem().is(Items.WATER_BUCKET)) { src = i; break; }
        }
        if (src < 0) return;                                     // no bucket in the main inventory

        // hotbar slot: empty first, else the largest stack (the hotbar is inventory menu slots 36 to 44)
        int target = Hotbar.slotToDisplace(hb -> menu.slots.get(36 + hb).getItem());
        gm.handleContainerInput(menu.containerId, src, target, ContainerInput.SWAP, p);   // number-key swap of the bucket and the hotbar slot
    }

    /** Put a water bucket in a hand: the offhand if it holds one, else switch the hotbar to one. */
    private static boolean selectWaterBucket(Minecraft mc, LocalPlayer p) {
        restoreSlot = -1;
        if (p.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.WATER_BUCKET)) {
            clutchHand = InteractionHand.OFF_HAND;
            return true;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (p.getInventory().getNonEquipmentItems().get(slot).is(Items.WATER_BUCKET)) {
                clutchHand = InteractionHand.MAIN_HAND;
                if (p.getInventory().getSelectedSlot() != slot) {
                    restoreSlot = p.getInventory().getSelectedSlot();
                    p.getInventory().setSelectedSlot(slot);
                    if (mc.getConnection() != null) mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
                }
                return true;
            }
        }
        return false;   // no water bucket in the hotbar or offhand
    }
}
