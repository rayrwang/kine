package ai.rrw.kine.autoflight;

/**
 * The porpoise law: the pitch the autopilot commands each tick. It dives at a fixed pitch to build speed; at the
 * bottom it levels off for a few ticks, which turns the dive's sink into forward speed (the game's descent redirect,
 * LivingEntity.updateFallFlyingMovement); then it pulls up hard and holds the pull-up pitch, eases the nose back down
 * toward a shallow climb, glides at a shallow dive pitch and returns to the dive. Below the target altitude it flies
 * the CLIMB profile, which pulls up at a trigger speed; at or above it, the ALT_HOLD profile, which dives until
 * DESC_MARGIN above the target, so every trough lands on the target. The climb or hold choice is made at each pull-up,
 * from the lowest altitude since the previous pull-up (the trough), as the live law does.
 *
 * <p>Everything after the pull-up trigger is a program of ticks since the trigger (programCmd), so a rollout that
 * copies the law continues it exactly.
 *
 * <p>An instance is the law's state; the flight models extend it, so a rollout carries the same state the live law has.
 */
public class PorpoiseLaw {
    /** Phases: diving, levelling off, holding the pull-up pitch, easing the nose back down, gliding before the dive. */
    public static final int DIVE = 0, PULL_UP = 1, SWEEP = 2, LEVEL = 3, GLIDE = 4;

    /**
     * One profile (pitch in degrees, nose down positive). After the trigger: `level` for levelTicks; `pullUp` for
     * pullUpHold; then the command eases from pullUp toward sweepTo by sweepGain of the gap each tick for sweepTicks;
     * then `glide` for glideTicks; then the dive pitch until the next trigger. triggerSpeed is the climb's pull-up speed
     * (m/s); the hold pulls up on altitude and has none.
     */
    public record Profile(double dive, double level, int levelTicks, double pullUp, int pullUpHold,
                          double sweepGain, double sweepTo, int sweepTicks, double glide, int glideTicks,
                          double triggerSpeed) {
        /** The command `tau` ticks after the trigger, or NaN once the program is over (back to the dive). */
        public double programCmd(int tau) {
            if (tau < levelTicks) return level;
            tau -= levelTicks;
            if (tau < pullUpHold) return pullUp;
            tau -= pullUpHold;
            if (tau < sweepTicks) return sweepTo + (pullUp - sweepTo) * Math.pow(1.0 - sweepGain, tau + 1);
            tau -= sweepTicks;
            if (tau < glideTicks) return glide;
            return Double.NaN;
        }

        /** The phase `tau` ticks after the trigger. */
        public int phaseAt(int tau) {
            if (tau < levelTicks) return LEVEL;
            tau -= levelTicks;
            if (tau < pullUpHold) return PULL_UP;
            tau -= pullUpHold;
            if (tau < sweepTicks) return SWEEP;
            tau -= sweepTicks;
            return tau < glideTicks ? GLIDE : DIVE;
        }
    }

    /** Climb: about +1.49 m/s at 22.5 m/s ground speed, a 254-tick cycle, on the autopilot's pitch response
     *  (FlightModel3D.ease, 15 deg a tick); the shipped law of a1b6901 climbed +0.92 at 21.9 (~/_/kine_fastpitch_2026-10-11/,
     *  the rate-15 search). */
    public static final Profile CLIMB = new Profile(44.3, 0.0, 13, -78.5, 3, 0.0336, -6.9, 86, 32.9, 81, 44.1);
    /** Altitude hold at full speed, about 32.4 m/s (a1b6901: 30.1), a 360-tick cycle. It pulls up on altitude, so it
     *  has no trigger speed. */
    public static final Profile ALT_HOLD = new Profile(44.5, 0.1, 13, -82.7, 1, 0.0219, 12.6, 84, 37.6, 173, Double.NaN);
    /** Holding: pull up when the altitude is within this of the target. The level-off carries the true trough about
     *  6 blocks lower, to about 2.8 above the target. */
    public static final double DESC_MARGIN = 8.7;

    public int phase = DIVE;
    public double cmd;             // commanded pitch (degrees)
    public int programTicks;       // ticks since the pull-up trigger
    public boolean climbing;
    public double cycleMin = Double.MAX_VALUE;   // lowest altitude since the last pull-up
    public double trough = -1.0e9;               // the cycle's lowest altitude at the last pull-up

    public Profile profile() { return climbing ? CLIMB : ALT_HOLD; }

    /** Copy another law's state into this one. */
    public void copyLaw(PorpoiseLaw o) {
        phase = o.phase; cmd = o.cmd; programTicks = o.programTicks; climbing = o.climbing;
        cycleMin = o.cycleMin; trough = o.trough;
    }

    /** One tick of the law, from this tick's altitude and horizontal speed (m/s) and the trough floor
     *  to fly to. Returns true on the tick it pulls up. */
    public boolean update(double y, double hSpeed, double target) {
        if (y < cycleMin) cycleMin = y;
        Profile p = profile();
        if (phase == DIVE) {
            cmd = p.dive();
            boolean pull = climbing ? (hSpeed >= CLIMB.triggerSpeed()) : (y <= target + DESC_MARGIN);
            if (pull) {                                   // trough: choose the next cycle's profile
                trough = cycleMin;
                climbing = trough < target;
                cycleMin = Double.MAX_VALUE;
                p = profile();
                programTicks = 0;
                program(p);
                return true;
            }
        } else {
            programTicks++;
            program(p);
        }
        return false;
    }

    private void program(Profile p) {
        double c = p.programCmd(programTicks);
        if (Double.isNaN(c)) { cmd = p.dive(); phase = DIVE; }
        else { cmd = c; phase = p.phaseAt(programTicks); }
    }

    /** Restart at the beginning of a climb: diving at the climb profile's dive pitch. */
    public void restartClimb() {
        climbing = true; phase = DIVE; cmd = CLIMB.dive(); programTicks = 0; cycleMin = Double.MAX_VALUE;
    }

    /** Restart in altitude hold: diving at the hold profile's pitch, to pull up near the target. For a hand-over
     *  above the target with speed, as at the end of a rocket takeoff, where the climb restart would first dive
     *  to its trigger speed and give up height. */
    public void restartHold() {
        climbing = false; phase = DIVE; cmd = ALT_HOLD.dive(); programTicks = 0; cycleMin = Double.MAX_VALUE;
    }
}
