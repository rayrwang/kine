package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.util.KineTime;
import ai.rrw.kine.autoflight.ElytraGuard;
import ai.rrw.kine.autoflight.FlightDirector;
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

    // rolling average of actual horizontal flight speed (m/s), sampled each tick while gliding. The mean is
    // taken over a whole number of porpoise cycles so the fast (dive) and slow (climb) halves cancel; the
    // cycle length is read live from the flight director (FlightDirector.periodTicks()), which differs by
    // mode (~245 ticks climbing, ~325 holding) and is re-measured every cycle, rather than a fixed guess.
    private static final int SPEED_WINDOW = 1400;       // ring buffer: >= 4 of the longest (hold) cycle, ~70 s
    // Initial cruise speed (m/s) used by BOTH range and ETA until a real mean exists now comes from the
    // flight model itself: FlightDirector.expectedGroundSpeed() returns the active mode's optimized ground
    // speed (~21.9 climbing, ~30.1 hold), so the readouts open on the right figure and the live mean only
    // has to refine it rather than walk in from a generic guess.
    private static final double[] speedBuf = new double[SPEED_WINDOW];
    private static int speedIdx = 0, speedCount = 0;

    private static double enduranceSec = 0;
    private static boolean show = false;
    private static final double AGL_TC = 12.0;          // s; smooths the porpoise bob out of the reserve so range/END don't pulse
    private static double smoothedAgl = Double.NaN;     // low-passed height-above-ground feeding the readout reserve
    private static final double RANGE_TC = 12.0;        // s; complementary-filter time constant for the displayed range (twin of the ETA filter)
    private static double rangeShown = Double.NaN;      // smoothed range (m) actually displayed

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
        if (!chest.is(Items.ELYTRA)) { smoothedAgl = Double.NaN; rangeShown = Double.NaN; resetSpeed(); return; }   // only while actually wearing an elytra

        if (p.isFallFlying()) {
            double dx = p.getX() - p.xOld, dz = p.getZ() - p.zOld;
            speedBuf[speedIdx] = Math.sqrt(dx * dx + dz * dz) * 20.0;
            speedIdx = (speedIdx + 1) % SPEED_WINDOW;
            if (speedCount < SPEED_WINDOW) speedCount++;
        } else {
            resetSpeed();   // on the ground / not gliding: drop the last flight's samples, so the next flight
                            // starts fresh and range/ETA fall back to the model anchor until a new mean exists
        }

        Acc acc = new Acc();
        process(chest, acc);
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) process(s, acc);
        process(p.getItemBySlot(EquipmentSlot.OFFHAND), acc);

        // Reserve off a smoothed altitude: the reserve plans a glide-down from your operating height,
        // and the porpoise bob isn't a real change in that — chasing it just made range and END pulse.
        // (The durability failsafe still reads the instantaneous AGL, where the conservative value is wanted.)
        int aglNow = Math.max(0, RadioAltimeter.agl());
        smoothedAgl = Double.isNaN(smoothedAgl) ? aglNow
            : smoothedAgl + (aglNow - smoothedAgl) * (1.0 - Math.exp(-0.05 / AGL_TC));
        double reserve = ElytraGuard.landingReserveSeconds(smoothedAgl);
        enduranceSec = Math.max(0, acc.flightSeconds * (1.0 - CONTINGENCY) - reserve);
        show = true;

        // Smooth the displayed range with a complementary filter — the twin of the ETA one. The clock
        // term counts it down at the rate reach is actually consumed (~speed × contingency while flying),
        // so the steady decline carries no lag; the drift term eases toward the true endurance × speed,
        // ironing the durability staircase, the speed ripple, and the reserve's slow terrain drift into a
        // steady glide. Because the decline rides the clock, not the lag, there's no upward "you can reach
        // further than you can" bias a plain low-pass would introduce.
        double v = speedEstimate();
        double rangeRaw = enduranceSec * v;
        if (Double.isNaN(rangeShown) || Math.abs(rangeRaw - rangeShown) > Math.max(600.0, rangeShown * 0.4)) {
            rangeShown = rangeRaw;                                              // first sample, or a big jump (elytra swap)
        } else {
            if (p.isFallFlying()) rangeShown -= v * (1.0 - CONTINGENCY) * 0.05; // reach consumed this 0.05s tick
            rangeShown = Math.max(0, rangeShown + (rangeRaw - rangeShown) * (1.0 - Math.exp(-0.05 / RANGE_TC)));
        }
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
        String rng = "RNG " + fmtDist(rangeShown);

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int cx = W / 2;
        int y = H - 60;                       // between the radio-altimeter and speed lines
        int half = 91;                        // ~half the hotbar width, out toward its edges

        g.text(mc.font, end, cx - half - mc.font.width(end) / 2, y, AMBER, true);
        g.text(mc.font, rng, cx + half - mc.font.width(rng) / 2, y, AMBER, true);
    }

    private static double cruiseSpeed() {
        if (speedCount <= 0) return 0;
        // Average over a whole number of porpoise cycles only, so the dive (fast) and climb (slow) halves
        // cancel exactly; a partial cycle would bias the mean toward whichever half it ends on and make the
        // figure ripple at the porpoise frequency. The cycle length is the flight director's live measured
        // period, so the truncation tracks the current mode (climb vs hold) instead of a fixed guess.
        int period = Math.max(1, FlightDirector.periodTicks());
        int avail  = Math.min(speedCount, SPEED_WINDOW);
        int n = (Math.min(4 * period, avail) / period) * period;   // up to ~4 whole cycles
        if (n <= 0) n = avail;                                      // not even one full cycle yet: use what we have
        double sum = 0;
        for (int k = 1; k <= n; k++) {
            int i = ((speedIdx - k) % SPEED_WINDOW + SPEED_WINDOW) % SPEED_WINDOW;
            sum += speedBuf[i];
        }
        return sum / n;
    }

    /**
     * The one estimated quantity in the whole range/ETA picture: cruise ground speed (m/s). Both the
     * range readout and the nav ETA derive from THIS, so they share one smoothing and recalibrate
     * together. It's the phase-balanced multi-cycle mean once we have a cycle of samples, and the
     * nominal anchor before that, so both readouts work from the first second instead of one of them
     * sitting blank.
     */
    public static double speedEstimate() { return speedReady() ? cruiseSpeed() : FlightDirector.expectedGroundSpeed(); }
    public static boolean speedReady() { return speedCount >= FlightDirector.periodTicks(); }

    /** Forget the rolling speed samples so a new flight starts fresh and, until then, the estimate falls
     *  back to the model anchor (the on-ground / between-flights state). */
    private static void resetSpeed() { speedCount = 0; speedIdx = 0; }

    /** Estimated reachable distance (m) right now, or -1 if no elytra is worn. */
    public static double rangeMeters() { return show ? rangeShown : -1.0; }

    private static String fmtDist(double blocks) {
        // Round DOWN to two significant figures: never overstate reach, and two digits is all that's
        // meaningful in a smoothed estimate. Scales with magnitude (840 m, 6.4 km, 880 km) instead of
        // a fixed decimal precision, so big numbers don't sprout spurious digits.
        double v = Math.max(0, blocks);
        if (v < 10) return (long) v + " m";
        double mag = Math.pow(10, Math.floor(Math.log10(v) + 1e-9) - 1);
        v = Math.floor(v / mag) * mag;
        if (v < 1000) return (long) v + " m";
        double km = v / 1000.0;
        return km < 10 ? String.format("%.1f km", km) : String.format("%.0f km", km);
    }
}
