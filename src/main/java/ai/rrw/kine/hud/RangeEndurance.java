package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.util.KineTime;
import ai.rrw.kine.autoflight.ElytraGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Estimates how long (endurance) and how far (range) you can elytra-fly on everything you're
 * carrying. Counts the worn elytra plus every spare in the inventory and offhand (not shulker
 * boxes — you can't reach those airborne); applies each elytra's Unbreaking (which multiplies
 * flight time per durability point).
 * Reserves are then held back aviation-style: a 5% contingency plus a final reserve sized to glide
 * down safely from the current altitude (the durability failsafe's reserve), so the readout reaches
 * zero just as that failsafe would trigger. Range = endurance x your own recent average flight
 * speed (a rolling mean of actual ground speed while gliding), so it self-calibrates to how you
 * really fly rather than to any model.
 *
 * Estimate: Unbreaking is treated as ~(L+1)x flight time, so treat the numbers as ballpark.
 */
public class RangeEndurance {

    // Reserves, modelled on aviation practice: a 5% contingency on the trip, plus a "final reserve"
    // sized to glide down safely from the current altitude (the same reserve the durability failsafe
    // uses, so the readout reaches zero just as that failsafe would fire).
    private static final double CONTINGENCY = 0.05;

    private static final int AMBER = 0xFFFFC400;       // matches the radio altimeter

    // rolling average of actual horizontal flight speed (m/s), sampled each tick while gliding
    // one cycle is approx 14.2s (284 ticks)
    private static final int SPEED_WINDOW = 4*284; // 4 cycles, ~56.8s
    private static final int MIN_SAMPLES  = 284; // wait one cycle (~14.2s) for a stable mean
    private static final double[] speedBuf = new double[SPEED_WINDOW];
    private static int speedIdx = 0, speedCount = 0;

    private static double enduranceSec = 0;
    private static boolean show = false;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(RangeEndurance::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "range_endurance"),
            RangeEndurance::render);
    }

    private static final class Acc {
        double flightSeconds = 0;
    }

    private static void tick(Minecraft mc) {
        show = false;
        if (!Settings.displayRangeEndurance) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;

        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA)) return;   // only while actually wearing an elytra

        if (p.isFallFlying()) {
            double dx = p.getX() - p.xOld, dz = p.getZ() - p.zOld;
            speedBuf[speedIdx] = Math.sqrt(dx * dx + dz * dz) * 20.0;
            speedIdx = (speedIdx + 1) % SPEED_WINDOW;
            if (speedCount < SPEED_WINDOW) speedCount++;
        }

        Acc acc = new Acc();
        process(chest, acc);
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) process(s, acc);
        process(p.getItemBySlot(EquipmentSlot.OFFHAND), acc);

        double reserve = ElytraGuard.landingReserveSeconds(Math.max(0, RadioAltimeter.agl()));
        enduranceSec = Math.max(0, acc.flightSeconds * (1.0 - CONTINGENCY) - reserve);
        show = true;
    }

    private static void process(ItemStack s, Acc acc) {
        if (s.isEmpty()) return;
        if (s.is(Items.ELYTRA) && s.isDamageableItem()) {
            int usable = Math.max(0, s.getMaxDamage() - 1 - s.getDamageValue());
            int unb = level(s, Enchantments.UNBREAKING);
            acc.flightSeconds += (double) usable * (unb + 1);   // Unbreaking ~ (L+1)x flight time
        }
    }

    private static int level(ItemStack s, ResourceKey<Enchantment> key) {
        ItemEnchantments ench = s.getEnchantments();
        for (Holder<Enchantment> h : ench.keySet()) {
            if (h.is(key)) return ench.getLevel(h);
        }
        return 0;
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!show) return;
        Minecraft mc = Minecraft.getInstance();

        String end = "END " + KineTime.format(enduranceSec);
        String rng = speedCount >= MIN_SAMPLES
            ? "RNG " + fmtDist(enduranceSec * cruiseSpeed())
            : "RNG --";

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int cx = W / 2;
        int y = H - 60;                       // between the radio-altimeter and speed lines
        int half = 91;                        // ~half the hotbar width, out toward its edges

        g.text(mc.font, end, cx - half - mc.font.width(end) / 2, y, AMBER, true);
        g.text(mc.font, rng, cx + half - mc.font.width(rng) / 2, y, AMBER, true);
    }

    private static double cruiseSpeed() {
        double sum = 0;
        for (int i = 0; i < speedCount; i++) sum += speedBuf[i];
        return speedCount > 0 ? sum / speedCount : 0;
    }

    /** Multi-cycle mean ground speed (m/s) — stable across the porpoise. 0 until {@link #speedReady}. */
    public static double meanGroundSpeed() { return speedReady() ? cruiseSpeed() : 0; }
    public static boolean speedReady() { return speedCount >= MIN_SAMPLES; }

    /** Estimated reachable distance (m) right now, or -1 if unknown (no elytra worn, or speed not ready yet). */
    public static double rangeMeters() { return (show && speedReady()) ? enduranceSec * cruiseSpeed() : -1.0; }

    private static String fmtDist(double blocks) {
        return blocks >= 1000 ? String.format("%.1f km", blocks / 1000.0)
                              : String.format("%.0f m", blocks);
    }
}
