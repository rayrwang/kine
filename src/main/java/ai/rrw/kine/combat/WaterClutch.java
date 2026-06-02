package ai.rrw.kine.combat;

import ai.rrw.kine.Settings;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Automatic water-bucket clutch (MLG). While free-falling hard enough to get hurt, it predicts where
 * it will land, and once that spot is within arm's reach it places a water source in the block it's
 * about to land in, then scoops the water straight back up once it's safely down.
 *
 * <p>It only runs in free fall — never while the elytra is gliding. A broken elytra simply stops
 * fall-flying, at which point this is plain falling and the clutch takes over.
 *
 * <p>Robustness details:
 * <ul>
 *   <li><b>Trajectory aim.</b> Rather than dropping the water straight down, it simulates the fall
 *       (gravity + air drag) to find the actual impact point, accounting for horizontal velocity, and
 *       aims the place there — so it works on diagonal falls, not just vertical drops.</li>
 *   <li><b>Sneak placement.</b> A water bucket used on a waterloggable block (slab, stairs, fence…)
 *       normally waterlogs it, leaving the feet dry. With sneak held, vanilla puts the source in the
 *       air block <i>above</i> instead, so the water always ends up at the feet. Sneak is driven
 *       through the input packet — both what {@code isShiftKeyDown()} reads for the client prediction
 *       and what the server reads for its own placement — so the two agree.</li>
 *   <li><b>Bucket staging.</b> As soon as a dangerous fall starts, if the only water bucket is down in
 *       the main inventory it is swapped up into the hotbar (an empty slot if there is one, otherwise
 *       the largest stack as the least-valuable thing to displace), so it's ready by the time the
 *       ground arrives.</li>
 * </ul>
 *
 * <p>Water evaporates in the Nether and other hot dimensions, so the clutch checks for that and does
 * not try there.
 */
public final class WaterClutch {
    private WaterClutch() {}

    private static final double HURT_FALL     = 3.5;   // only bother once the fall would actually do damage
    private static final double PLACE_REACH   = 4.4;   // place once the impact point is within this of the eye (just inside the ~4.5 reach)
    private static final int    SIM_TICKS     = 12;    // how far ahead to simulate the trajectory to find the impact
    private static final int    GIVE_UP_TICKS = 20;    // stop holding the pose if the clutch never resolves
    private static final double H_DRAG        = 0.91;  // per-tick horizontal air drag
    private static final double GRAVITY       = 0.08;  // per-tick gravity
    private static final double V_DRAG        = 0.98;  // per-tick vertical drag

    // --- state carried across the clutch (placement -> pickup) ---
    private static boolean active = false;
    private static InteractionHand clutchHand = InteractionHand.MAIN_HAND;
    private static Vec3    sourceSpot = null;          // where we placed the water, so the pickup can aim back at it
    private static int     restoreSlot = -1;          // hotbar slot to switch back to (-1 = didn't switch / used offhand)
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

        // --- arm only in a genuinely dangerous free fall (gliding has its own systems; a broken elytra lands here) ---
        if (p.isFallFlying() || p.onGround() || p.isInWater() || p.isInLava()) return;
        if (p.fallDistance < HURT_FALL) return;
        if (p.getDeltaMovement().y() >= 0) return;            // must be descending

        // --- stage a water bucket into the hotbar early, while there's still time before impact ---
        stageBucketToHotbar(mc, p);

        // --- predict where we land by sweeping the footprint against real collision shapes ---
        Vec3 land = predictImpact(mc, p);
        if (land == null) return;                             // no landing in range yet — wait
        Vec3 eye = p.getEyePosition();
        double dx = land.x() - eye.x(), dy = land.y() - eye.y(), dz = land.z() - eye.z();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > PLACE_REACH) return;                       // not close enough yet

        // --- water can't exist in hot dimensions (Nether) — don't waste the bucket ---
        BlockPos placeCell = new BlockPos(Mth.floor(land.x()), Mth.floor(land.y() + 0.1), Mth.floor(land.z()));
        Boolean evaporates = (Boolean) mc.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, placeCell);
        if (evaporates != null && evaporates) return;

        // --- need a water bucket reachable in an active hand ---
        if (!selectWaterBucket(mc, p)) return;

        // --- aim at the impact point (handles both straight-down and diagonal) ---
        double dh = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float pitch = (float) (-Math.atan2(dy, dh) * 180.0 / Math.PI);
        restoreYaw = p.getYRot();
        restorePitch = p.getXRot();
        p.setYRot(yaw);
        p.setXRot(pitch);

        // --- place: sneak (puts the source ABOVE the surface, never waterlogging) ---
        ClientPacketListener conn = mc.getConnection();
        Input cur = p.input.keyPresses;
        Input sneaking = new Input(cur.forward(), cur.backward(), cur.left(), cur.right(), cur.jump(), true, cur.sprint());
        p.input.keyPresses = sneaking;                                          // client predicts place-above (isShiftKeyDown reads this)
        if (conn != null) conn.send(new ServerboundPlayerInputPacket(sneaking)); // server sneaks before it sees the use packet
        mc.gameMode.useItem(p, clutchHand);   // bucket raycasts toward the impact (packet carries the rotation) -> source at the feet block
        p.input.keyPresses = cur;                                               // restore real input
        if (conn != null) conn.send(new ServerboundPlayerInputPacket(cur));     // and tell the server we stopped sneaking

        sourceSpot = new Vec3(land.x(), land.y() + 0.5, land.z());   // the source sits in the cell just above the surface we aimed at
        active = true;
        ticks = 0;
    }

    /** Hold the aim on the placed water until we're in it, then scoop it back up and restore. */
    private static void resolve(Minecraft mc, LocalPlayer p) {
        if (p == null) { finish(mc, null); return; }
        ticks++;

        // keep looking at the spot we placed the water (it can be off-center under an edge), so the pickup ray hits it
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
            mc.gameMode.useItem(p, clutchHand);   // hand now holds an empty bucket -> scoops the source straight back up
            finish(mc, p);
        } else if (p.onGround() || p.isFallFlying() || ticks > GIVE_UP_TICKS) {
            finish(mc, p);   // dry landing (missed), started gliding, or timed out — let go
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
     * Simulate the fall (gravity + drag) and return a point on top of the surface we'll actually rest on,
     * or null if no landing is near. Instead of casting rays, each tick it tests the box's footprint against
     * the real collision shapes of every block under it, exactly as the engine's own collision does. That
     * matters for thin shapes — a fence post or end rod tucked under an <i>edge</i> of the hitbox (between
     * the corners, or under the center) still stops the fall, so it has to be the thing we aim the water at;
     * a corner-only test would miss it and place the water a block too low. Among everything the footprint
     * touches we take the highest top, and aim at the part of that block's top that lies under the footprint.
     */
    private static Vec3 predictImpact(Minecraft mc, LocalPlayer p) {
        AABB box = p.getBoundingBox();
        Vec3 vel = p.getDeltaMovement();
        double offX = 0, offY = 0, offZ = 0;
        double vx = vel.x(), vy = vel.y(), vz = vel.z();
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int i = 0; i < SIM_TICKS; i++) {
            double botNow = box.minY + offY;            // box underside now ...
            double botNext = botNow + vy;               // ... and after this tick of falling
            // footprint, swept to cover this tick's horizontal motion
            double fpMinX = Math.min(box.minX + offX, box.minX + offX + vx);
            double fpMaxX = Math.max(box.maxX + offX, box.maxX + offX + vx);
            double fpMinZ = Math.min(box.minZ + offZ, box.minZ + offZ + vz);
            double fpMaxZ = Math.max(box.maxZ + offZ, box.maxZ + offZ + vz);
            double cx = (fpMinX + fpMaxX) / 2, cz = (fpMinZ + fpMaxZ) / 2;

            double bestTop = Double.NEGATIVE_INFINITY, aimX = 0, aimZ = 0;
            int x0 = Mth.floor(fpMinX), x1 = Mth.floor(fpMaxX);
            int z0 = Mth.floor(fpMinZ), z1 = Mth.floor(fpMaxZ);
            int yHi = Mth.floor(botNow + 0.001), yLo = Mth.floor(botNext) - 2;   // -2 covers tall shapes (fences/walls reach 1.5)
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int y = yHi; y >= yLo; y--) {
                        cur.set(x, y, z);
                        VoxelShape shape = mc.level.getBlockState(cur).getCollisionShape(mc.level, cur);
                        if (shape.isEmpty()) continue;
                        AABB b = shape.bounds();
                        double sxMin = x + b.minX, sxMax = x + b.maxX, szMin = z + b.minZ, szMax = z + b.maxZ;
                        if (sxMax <= fpMinX || sxMin >= fpMaxX || szMax <= fpMinZ || szMin >= fpMaxZ) continue;  // no xz overlap with the footprint
                        double top = y + b.maxY;
                        if (top > botNow + 0.001 || top < botNext - 0.001) continue;   // outside this tick's descent window
                        if (top > bestTop) {
                            bestTop = top;
                            aimX = Mth.clamp(cx, Math.max(fpMinX, sxMin), Math.min(fpMaxX, sxMax));  // aim where the footprint actually sits on this shape
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

    /** If the only water bucket is in the main inventory, swap it up into the hotbar so it's ready to use. */
    private static void stageBucketToHotbar(Minecraft mc, LocalPlayer p) {
        if (p.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.WATER_BUCKET)) return;
        for (int i = 0; i < 9; i++) if (p.getInventory().getNonEquipmentItems().get(i).is(Items.WATER_BUCKET)) return;  // already in hotbar

        MultiPlayerGameMode gm = mc.gameMode;
        AbstractContainerMenu menu = p.containerMenu;
        if (gm == null || menu != p.inventoryMenu || !menu.getCarried().isEmpty()) return;   // a screen is open or the cursor is busy

        int src = -1;                                            // inventory-menu slot holding the bucket (9..35 = main inventory)
        for (int i = 9; i <= 35; i++) {
            if (menu.slots.get(i).getItem().is(Items.WATER_BUCKET)) { src = i; break; }
        }
        if (src < 0) return;                                     // no bucket anywhere we can reach

        int target = -1, biggest = -1;                          // pick a hotbar slot: empty first, else the largest stack
        for (int hb = 0; hb < 9; hb++) {
            ItemStack st = menu.slots.get(36 + hb).getItem();    // hotbar = inventory-menu slots 36..44
            if (st.isEmpty()) { target = hb; break; }
            if (st.getCount() > biggest) { biggest = st.getCount(); target = hb; }
        }
        gm.handleContainerInput(menu.containerId, src, target, ContainerInput.SWAP, p);   // number-key swap: bucket <-> hotbar slot
    }

    /** Put a water bucket into an active hand. Prefers the offhand, otherwise switches the hotbar to it. */
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
        return false;   // no water bucket reachable — nothing we can do
    }
}
