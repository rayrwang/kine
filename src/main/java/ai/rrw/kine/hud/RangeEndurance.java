package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.autoflight.Durability;
import ai.rrw.kine.autoflight.FlightDirector;
import ai.rrw.kine.autoflight.LandingTime;
import ai.rrw.kine.autoflight.Nav;
import ai.rrw.kine.autoflight.Takeoff;
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
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Endurance (END) and range (RNG) on every elytra you carry: the worn one plus spares in the inventory and offhand
 * (not in shulker boxes, which can't be opened in flight). END is the expected gliding time on every usable point
 * (above the 3-point floor), (Unbreaking + 1) seconds a point on average ({@link Durability}), flown in the
 * failsafe's order, less the landing reserve held on the last wing: one point for each second the autopilot's landing
 * takes from your height down to the world's bottom ({@link LandingTime}), the height being the top of the last 20 s
 * of the flight, so END doesn't cycle with the porpoise ({@link #reserveY}). So END reaching 0 means the landing must
 * start now; the durability failsafe acts on it (ElytraGuard, Nav). RNG is END times your recent average ground speed,
 * less 10%. Both count down with the clock and drift toward the estimate, so they don't jump with each durability
 * point; END turns amber in its last minute and red in its last 20 s.
 */
public class RangeEndurance {

    /** RNG keeps back 10% (RW: "ok 10"). */
    static final double RANGE_FACTOR = 0.9;
    /** END turns amber in its last 60 s and red in its last 20 (RW: amber at 60; red at 15, 20 or 30, his "you
     *  decide"; Claude chose 20). */
    static final double AMBER_SECONDS = 60.0, RED_SECONDS = 20.0;
    /** END's colour above AMBER_SECONDS (both readouts were amber throughout before; white here so the amber means
     *  something). */
    static final int END_NORMAL = HudColors.WHITE;
    private static final String UNLIMITED = "\u221E";   // the infinity sign, in the game's default font (nonlatin_european.png)


    // Ground speed (m/s) sampled each tick while gliding. The mean is taken over whole porpoise cycles so
    // the fast dive and slow climb cancel; the cycle length is FlightDirector.periodTicks(), measured live
    // (about 245 ticks climbing, 325 holding). Until a cycle of samples exists, range and ETA use
    // FlightDirector.expectedGroundSpeed(), the active profile's modelled speed.
    private static final int SPEED_WINDOW = 1400;       // ring buffer: four of the longest (hold) cycles, 70 s
    private static final double[] speedBuf = new double[SPEED_WINDOW];
    private static int speedIdx = 0, speedCount = 0;
    // The mean speed of the last flight long enough to have one (a whole porpoise cycle of samples), kept through
    // landings, short glides and hops until a new flight has its own (RW, 2026-10-11); NaN before the first.
    private static double lockedSpeed = Double.NaN;

    // The landing reserve's height: the highest point of the last RESERVE_WINDOW ticks of this flight (a porpoise
    // cycle and more), so the reserve doesn't swing with the porpoise (about 3 points a cycle at cruise); locked at
    // the end of a flight that long and kept through short glides and hops, as the speed is (RW, 2026-10-11).
    static final int RESERVE_WINDOW = 400;               // ticks, 20 s
    private static final double[] topBuf = new double[RESERVE_WINDOW];
    private static int topIdx = 0, topCount = 0;
    private static int flightTicks = 0;                  // ticks of the current glide
    private static double lockedTop = Double.NaN;        // y; NaN before the first flight that long, or in a new world
    private static Level lastLevel = null;               // a new world or dimension has another bottom: drop the lock

    private static double endurance = Double.NaN;      // END now (s), unsmoothed: the failsafe's trigger; NaN with no elytra worn
    private static double enduranceShown = Double.NaN; // END as displayed (s)
    private static double glideLeftShown = Double.NaN; // the gliding time on every usable point, the reserve included (s), as displayed
    private static boolean show = false;                // an estimate exists (an elytra is worn), whether or not it is displayed
    private static final double END_TC = 12.0;          // s; time constant of the displayed END's complementary filter
    private static final double RANGE_TC = 12.0;        // s; time constant of the displayed range's complementary filter
    private static double rangeShown = Double.NaN;      // smoothed range (m) actually displayed

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(RangeEndurance::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "range_endurance"),
            RangeEndurance::render);
    }

    // Runs whether or not the readout is displayed: the failsafe, the nav range warning and the ETA use the estimate too.
    private static void tick(Minecraft mc) {
        // A paused game (singleplayer, Kine's own screens included) ticks no entities: the speed would be one
        // frozen sample over and over, and no flight is used up. Keep the estimate as it was.
        if (mc.isPaused()) return;
        show = false;
        endurance = Double.NaN;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;

        Durability.Wing worn = ElytraLife.wing(p.getItemBySlot(EquipmentSlot.CHEST));
        if (mc.level != lastLevel) { lastLevel = mc.level; lockedTop = Double.NaN; }
        if (worn == null) { enduranceShown = Double.NaN; rangeShown = Double.NaN; glideLeftShown = Double.NaN; endFlight(); return; }   // only while wearing an elytra

        boolean gliding = p.isFallFlying();
        if (gliding) {
            flightTicks++;
            // Not during an autopilot landing, so what a landing locks is the flight before it, not its spiral,
            // brake and slow finish (about a quarter off the mean speed from cruise); nor in water, where the glide
            // goes on after a landing there (LivingEntity.canGlide doesn't check for water).
            if (!(Autopilot.isEngaged() && Nav.landing()) && !p.isInWater()) {
                if (!Takeoff.active()) {   // nor the rocket climb, for the speed (RW): the cruise's speed is what counts
                    speedBuf[speedIdx] = Motion.groundSpeed(p);
                    speedIdx = (speedIdx + 1) % SPEED_WINDOW;
                    if (speedCount < SPEED_WINDOW) speedCount++;
                }
                topBuf[topIdx] = p.getY();
                topIdx = (topIdx + 1) % RESERVE_WINDOW;
                if (topCount < RESERVE_WINDOW) topCount++;
            }
        } else {
            endFlight();
        }

        List<Durability.Wing> order = Durability.order(worn, ElytraLife.spares(p.inventoryMenu, null));
        double ownTop = gliding && topCount > 0 ? windowTop() : Double.NaN;
        double y = reserveY(ownTop, flightTicks, lockedTop, FlightDirector.targetAltitude());
        int reserve = Durability.reservePoints(LandingTime.seconds(y - mc.level.getMinY()));
        endurance = Durability.endurance(order, reserve);
        show = true;

        enduranceShown = nextEndurance(enduranceShown, endurance, gliding);
        glideLeftShown = nextEndurance(glideLeftShown, Durability.endurance(order, 0), gliding);
        rangeShown = nextRange(rangeShown, endurance, speedEstimate(), gliding);
    }

    /**
     * The displayed END: a complementary filter, as the range's. It counts down with the clock while gliding and
     * drifts toward the estimate, which with Unbreaking otherwise jumps in random steps. On the ground, at the first
     * sample, or after a big jump (a wing picked up or dropped), the estimate itself. It reads 0 exactly when the
     * estimate does, the moment the landing must start, and never sooner (1 s at the least until then).
     */
    static double nextEndurance(double shown, double estimate, boolean gliding) {
        if (Double.isInfinite(estimate)) return Double.POSITIVE_INFINITY;
        if (!(estimate > 0)) return 0.0;
        if (!gliding || Double.isNaN(shown) || Double.isInfinite(shown)
                || Math.abs(estimate - shown) > Math.max(30.0, shown * 0.4)) {
            return estimate;
        }
        shown -= 0.05;                                                          // this 0.05 s tick
        return Math.max(1.0, Smoothing.toward(shown, estimate, 0.05, END_TC));
    }

    // Complementary filter, like the ETA's: count the range down at the rate flying uses it up, then
    // drift toward endurance x speed. The countdown carries the steady decline without lag, so unlike
    // a plain low-pass it doesn't overstate the range; the drift irons out the durability steps.
    // Unlimited endurance (a worn wing that can't take damage) is unlimited range; END 0 is range 0.
    static double nextRange(double rangeShown, double endurance, double v, boolean gliding) {
        if (Double.isInfinite(endurance)) return Double.POSITIVE_INFINITY;
        if (!(endurance > 0)) return 0.0;
        double rangeRaw = endurance * v * RANGE_FACTOR;
        if (!gliding || Double.isNaN(rangeShown) || Double.isInfinite(rangeShown)
                || Math.abs(rangeRaw - rangeShown) > Math.max(600.0, rangeShown * 0.4)) {
            return rangeRaw;                                                    // on the ground, first sample, or a big jump
        }
        rangeShown -= v * RANGE_FACTOR * 0.05;                                  // range used up this 0.05 s tick
        return Math.max(0, Smoothing.toward(rangeShown, rangeRaw, 0.05, RANGE_TC));
    }

    /** END's colour: amber in its last minute, red in its last 20 s (RW), judged on the whole seconds shown. */
    static int enduranceColor(double seconds) {
        long shown = Math.round(seconds);   // as KineTime prints it; infinity rounds to Long.MAX_VALUE
        if (shown <= RED_SECONDS) return HudColors.RED;
        if (shown <= AMBER_SECONDS) return HudColors.AMBER;
        return END_NORMAL;
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!show || !Settings.shows(Settings.displayRangeEndurance)) return;
        Minecraft mc = Minecraft.getInstance();

        String end = "END " + (Double.isInfinite(enduranceShown) ? UNLIMITED : KineTime.format(enduranceShown));
        String rng = "RNG " + (Double.isInfinite(rangeShown) ? UNLIMITED : Readouts.range(rangeShown));

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int cx = W / 2;
        int y = HudLayout.rangeEnduranceRow(H);
        int half = HudLayout.HOTBAR_HALF;

        g.text(mc.font, end, cx - half - mc.font.width(end) / 2, y, enduranceColor(enduranceShown), true);
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
     * mean over whole porpoise cycles once this flight has a cycle of samples; before that the last such flight's
     * mean, locked when it ended; before any, the modelled speed.
     */
    public static double speedEstimate() {
        if (speedReady()) return cruiseSpeed();
        return Double.isNaN(lockedSpeed) ? FlightDirector.expectedGroundSpeed() : lockedSpeed;
    }
    public static boolean speedReady() { return speedCount >= FlightDirector.periodTicks(); }

    /** A glide has ended (or the elytra came off): a flight long enough to have its own speed mean and reserve
     *  height locks them for the next; a short glide or a hop changes neither. Then the samples start afresh. */
    private static void endFlight() {
        if (speedReady()) lockedSpeed = cruiseSpeed();
        if (flightTicks >= RESERVE_WINDOW && topCount > 0) lockedTop = windowTop();
        speedCount = 0; speedIdx = 0;
        topCount = 0; topIdx = 0;
        flightTicks = 0;
    }

    // The highest y among the window's samples.
    private static double windowTop() {
        double top = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < topCount; k++) top = Math.max(top, topBuf[k]);
        return top;
    }

    /**
     * The y the landing reserve is sized from. Gliding for RESERVE_WINDOW ticks or more: `ownTop`, the top of this
     * flight's last 20 s. Earlier in a flight: the higher of that and the locked top (a new flight below the last
     * one's height keeps its reserve until it has its own 20 s; one above it is covered at once). On the ground
     * (`ownTop` NaN): the locked top. With nothing locked yet, `cruiseTarget`, the autopilot's cruise height,
     * stands in for it.
     */
    static double reserveY(double ownTop, int flightTicks, double lockedTop, double cruiseTarget) {
        double before = Double.isNaN(lockedTop) ? cruiseTarget : lockedTop;
        if (Double.isNaN(ownTop)) return before;
        return flightTicks >= RESERVE_WINDOW ? ownTop : Math.max(ownTop, before);
    }

    /** Estimated reachable distance (m) right now, infinite on a wing that can't take damage, or -1 if no
     *  elytra is worn. */
    public static double rangeMeters() { return show ? rangeShown : -1.0; }

    /** END now (s), unsmoothed: 0 once only the landing reserve is left, infinite on a worn wing that can't take
     *  damage, NaN with no elytra worn (or not in a world). Computed each tick, before Nav and the failsafe tick. */
    public static double endurance() { return endurance; }

    /** The expected gliding time left on every usable point, the landing reserve included (s), filtered as END is:
     *  what is left once END has reached 0, for the land-now warning by hand (RW, 2026-10-11). NaN with no elytra worn. */
    public static double glideLeft() { return glideLeftShown; }
}
