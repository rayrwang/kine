package ai.rrw.kine.combat;

import ai.rrw.kine.Settings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Automatic water-bucket clutch (MLG). While free-falling hard enough to get hurt, once the ground
 * comes within arm's reach it places a water source in the block it's about to land in, then scoops
 * the water straight back up once it's safely down.
 *
 * <p>It only runs in free fall — never while the elytra is gliding. A broken elytra simply stops
 * fall-flying, at which point this is plain falling and the clutch takes over, which is exactly what
 * we want.
 *
 * <p>Two mechanics make it robust:
 * <ul>
 *   <li><b>Sneak placement.</b> A water bucket used on a waterloggable block (slab, stairs, fence…)
 *       normally waterlogs it, leaving the feet dry on top — the classic clutch failure. With sneak
 *       held, vanilla puts the source in the air block <i>above</i> instead, so the water always ends
 *       up at the feet regardless of the surface (leaves aren't waterloggable and have full collision,
 *       so they already behave like ordinary ground). Sneak is driven through the input packet, which
 *       is both what {@code isShiftKeyDown()} reads for the client-side prediction and what the server
 *       reads when it runs the same placement — so client and server agree.</li>
 *   <li><b>Look-down placement.</b> The bucket re-raycasts from the player's own view, and the
 *       use-item packet carries that pitch, so the view is snapped straight down for the place and the
 *       pickup, then restored.</li>
 * </ul>
 *
 * <p>Water evaporates in the Nether and other hot dimensions, so the clutch checks for that and does
 * not try there.
 */
public final class WaterClutch {
    private WaterClutch() {}

    private static final double HURT_FALL    = 3.5;   // only bother once the fall would actually do damage
    private static final double REACH_AGL    = 2.8;   // place once the surface is this close to the feet — must stay inside the
                                                      // ~4.5-block reach from the *standing* eye, since the sneak pose (lower eye)
                                                      // doesn't take effect until the tick after we set it
    private static final int    SCAN_DOWN    = 8;     // look this far below for the impact surface
    private static final int    GIVE_UP_TICKS = 20;   // stop holding the pose if the clutch never resolves

    // --- state carried across the clutch (placement -> pickup) ---
    private static boolean active = false;
    private static InteractionHand clutchHand = InteractionHand.MAIN_HAND;
    private static int     restoreSlot = -1;          // hotbar slot to switch back to (-1 = didn't switch / used offhand)
    private static float   restorePitch = 0f;
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

        // --- find the impact surface straight below ---
        Vec3 feet = p.position();
        Vec3 to = new Vec3(feet.x(), feet.y() - (SCAN_DOWN + 1), feet.z());
        BlockHitResult hit = mc.level.clip(new ClipContext(feet, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() != HitResult.Type.BLOCK) return;    // nothing solid below within scan (the void) — can't clutch
        if (feet.y() - hit.getLocation().y > REACH_AGL) return;   // not close enough yet — wait for the next tick

        // --- water can't exist in hot dimensions (Nether) — don't waste the bucket ---
        Boolean evaporates = (Boolean) mc.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, hit.getBlockPos());
        if (evaporates != null && evaporates) return;

        // --- need a water bucket reachable in an active hand ---
        if (!selectWaterBucket(mc, p)) return;

        // --- place: sneak (puts the source ABOVE the surface, never waterlogging) + look straight down ---
        ClientPacketListener conn = mc.getConnection();
        Input cur = p.input.keyPresses;
        Input sneaking = new Input(cur.forward(), cur.backward(), cur.left(), cur.right(), cur.jump(), true, cur.sprint());
        restorePitch = p.getXRot();
        p.setXRot(90f);
        p.input.keyPresses = sneaking;                                          // client predicts place-above (isShiftKeyDown reads this)
        if (conn != null) conn.send(new ServerboundPlayerInputPacket(sneaking)); // server sneaks before it sees the use packet
        mc.gameMode.useItem(p, clutchHand);   // bucket raycasts down (packet carries the pitch) -> source lands in the feet block
        p.input.keyPresses = cur;                                               // restore real input
        if (conn != null) conn.send(new ServerboundPlayerInputPacket(cur));     // and tell the server we stopped sneaking

        active = true;
        ticks = 0;
    }

    /** Hold the look down until we're safely in the placed water, then scoop it back up and restore. */
    private static void resolve(Minecraft mc, LocalPlayer p) {
        if (p == null) { finish(mc, null); return; }
        ticks++;
        p.setXRot(90f);   // keep the pickup ray pointed at the source below

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
            if (restoreSlot >= 0 && mc.getConnection() != null) {
                p.getInventory().setSelectedSlot(restoreSlot);
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(restoreSlot));
            }
        }
        active = false;
        restoreSlot = -1;
        ticks = 0;
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
