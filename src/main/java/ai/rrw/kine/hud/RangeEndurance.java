package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.ElytraGuard;
import ai.rrw.kine.autoflight.FlightDirector;
import ai.rrw.kine.util.ElytraLife;
import ai.rrw.kine.util.KineTime;
import ai.rrw.kine.util.Motion;
import ai.rrw.kine.util.Readouts;
import ai.rrw.kine.util.Smoothing;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Estimates how long (endurance) and how far (range) you can glide on every elytra you carry: the worn
 * one plus spares in the inventory and offhand (not in shulker boxes, which can't be opened in flight),
 * each scaled by Unbreaking (about (L+1)x flight time per point, so treat the numbers as estimates).
 * Reserves are held back as in aviation: a 5% contingency plus a final reserve to glide down from the
 * current height (the durability failsafe's reserve), so the readout reaches zero when that failsafe
 * would fire. Range is endurance times your recent average ground speed while gliding.
 */
public class RangeEndurance {

    private static final double CONTINGENCY = 0.05;
    private static final String UNLIMITED = "\u221E";   // the infinity sign, in the game's default font (nonlatin_european.png)


    // Ground speed (m/s) sampled each tick while gliding. The mean is taken over whole porpoise cycles so
    // the fast dive and slow climb cancel; the cycle length is FlightDirector.periodTicks(), measured live
    // (about 245 ticks climbing, 325 holding). Until a cycle of samples exists, range and ETA use
    // FlightDirector.expectedGroundSpeed(), the active profile's modelled speed.
    private static final int SPEED_WINDOW = 1400;       // ring buffer: four of the longest (hold) cycles, 70 s
    private static final double[] speedBuf = new double[SPEED_WINDOW];
    private static int speedIdx = 0, speedCount = 0;

    private static double enduranceSec = 0;
    private static boolean show = false;                // an estimate exists (an elytra is worn), whether or not it is displayed
    private static final double AGL_TC = 12.0;          // s; smooths the porpoise out of the reserve, so range and END don't pulse
    private static double smoothedAgl = Double.NaN;     // low-passed height-above-ground feeding the readout reserve
    private static final double RANGE_TC = 12.0;        // s; time constant of the displayed range's complementary filter
    private static double rangeShown = Double.NaN;      // smoothed range (m) actually displayed

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(RangeEndurance::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "range_endurance"),
            RangeEndurance::render);
    }

    // Runs whether or not the readout is displayed: the nav range warning and the ETA use the estimate too.
    private static void tick(Minecraft mc) {
        // A paused game (singleplayer, Kine's own screens included) ticks no entities: the speed would be one
        // frozen sample over and over, and no flight is used up. Keep the estimate as it was.
        if (mc.isPaused()) return;
        show = false;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;

        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA)) { smoothedAgl = Double.NaN; rangeShown = Double.NaN; resetSpeed(); return; }   // only while wearing an elytra

        if (p.isFallFlying()) {
            speedBuf[speedIdx] = Motion.groundSpeed(p);
            speedIdx = (speedIdx + 1) % SPEED_WINDOW;
            if (speedCount < SPEED_WINDOW) speedCount++;
        } else {
            // not gliding: drop the last flight's samples and the smoothed range, so the readout shows the
            // current estimate on the ground and the next flight starts from it
            resetSpeed();
            rangeShown = Double.NaN;
        }

        double flightSeconds = ElytraLife.flightSeconds(chest);   // infinite for a worn wing that can't take damage
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) flightSeconds += spareSeconds(s);
        flightSeconds += spareSeconds(p.getItemBySlot(EquipmentSlot.OFFHAND));

        // The reserve plans a glide down from your cruising height, and the porpoise isn't a change in that,
        // so it uses a smoothed height. (The durability failsafe uses the instantaneous height.)
        double aglNow = reserveHeight(RadioAltimeter.agl(), p.getY(), mc.level.getMinY());
        smoothedAgl = Double.isNaN(smoothedAgl) ? aglNow : Smoothing.toward(smoothedAgl, aglNow, 0.05, AGL_TC);
        double reserve = ElytraGuard.landingReserveSeconds(smoothedAgl);
        enduranceSec = Math.max(0, flightSeconds * (1.0 - CONTINGENCY) - reserve);
        show = true;

        rangeShown = nextRange(rangeShown, enduranceSec, speedEstimate(), p.isFallFlying());
    }

    // Complementary filter, like the ETA's: count the range down at the rate flying uses it up, then
    // drift toward endurance x speed. The countdown carries the steady decline without lag, so unlike
    // a plain low-pass it doesn't overstate the range; the drift irons out the durability steps.
    // Unlimited endurance (a worn wing that can't take damage) is unlimited range.
    static double nextRange(double rangeShown, double enduranceSec, double v, boolean gliding) {
        if (Double.isInfinite(enduranceSec)) return Double.POSITIVE_INFINITY;
        double rangeRaw = enduranceSec * v;
        if (Double.isNaN(rangeShown) || Double.isInfinite(rangeShown)
                || Math.abs(rangeRaw - rangeShown) > Math.max(600.0, rangeShown * 0.4)) {
            return rangeRaw;                                                    // first sample, or a big jump (an elytra swap)
        }
        if (gliding) rangeShown -= v * (1.0 - CONTINGENCY) * 0.05;             // range used up this 0.05 s tick
        return Math.max(0, Smoothing.toward(rangeShown, rangeRaw, 0.05, RANGE_TC));
    }

    // A spare counts only if the failsafe's swap can take it, and ElytraGuard.tryAutoSwap skips wings that can't
    // take damage: an Unbreakable spare adds nothing (as before), so the readout still reaches zero when the
    // failsafe would fire.
    private static double spareSeconds(ItemStack s) {
        return s.isDamageableItem() ? ElytraLife.flightSeconds(s) : 0.0;
    }



    /** The height the reserve plans a glide down from: the radio altimeter's, or over the void (where it reads
     *  -1) the height above the world floor, as the durability failsafe takes it (ElytraGuard). */
    static double reserveHeight(int agl, double y, int minY) {
        return agl >= 0 ? agl : Math.max(0, y - minY);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!show || !Settings.shows(Settings.displayRangeEndurance)) return;
        Minecraft mc = Minecraft.getInstance();

        String end = "END " + (Double.isInfinite(enduranceSec) ? UNLIMITED : KineTime.format(enduranceSec));
        String rng = "RNG " + (Double.isInfinite(rangeShown) ? UNLIMITED : Readouts.range(rangeShown));

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int cx = W / 2;
        int y = HudLayout.rangeEnduranceRow(H);
        int half = HudLayout.HOTBAR_HALF;

        g.text(mc.font, end, cx - half - mc.font.width(end) / 2, y, HudColors.AMBER, true);
        g.text(mc.font, rng, cx + half - mc.font.width(rng) / 2, y, HudColors.AMBER, true);
    }

    private static double cruiseSpeed() {
        if (speedCount <= 0) return 0;
        // Average over whole porpoise cycles only, so the fast dive and slow climb cancel; a partial cycle
        // would bias the mean toward whichever half it ends on.
        int period = Math.max(1, FlightDirector.periodTicks());
        int avail  = Math.min(speedCount, SPEED_WINDOW);
        int n = (Math.min(4 * period, avail) / period) * period;   // up to 4 whole cycles
        if (n <= 0) n = avail;                                      // not one full cycle yet: use what there is
        double sum = 0;
        for (int k = 1; k <= n; k++) {
            int i = ((speedIdx - k) % SPEED_WINDOW + SPEED_WINDOW) % SPEED_WINDOW;
            sum += speedBuf[i];
        }
        return sum / n;
    }

    /**
     * Cruise ground speed (m/s), the one estimate behind both the range readout and the nav ETA: the
     * mean over whole porpoise cycles once a cycle of samples exists, the modelled speed before that.
     */
    public static double speedEstimate() { return speedReady() ? cruiseSpeed() : FlightDirector.expectedGroundSpeed(); }
    public static boolean speedReady() { return speedCount >= FlightDirector.periodTicks(); }

    /** Forget the speed samples, so the estimate falls back to the modelled speed until a new mean exists. */
    private static void resetSpeed() { speedCount = 0; speedIdx = 0; }

    /** Estimated reachable distance (m) right now, infinite on a wing that can't take damage, or -1 if no
     *  elytra is worn. */
    public static double rangeMeters() { return show ? rangeShown : -1.0; }
}
