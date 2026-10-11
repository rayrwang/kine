package ai.rrw.kine.autoflight;

/**
 * The porpoise law: the pitch the autopilot commands each tick. It dives at a fixed pitch to build
 * speed, pulls up and holds the pull-up pitch for a few ticks, then sweeps the nose back down to the
 * dive pitch. Below the target altitude it flies the CLIMB profile, which pulls up at a trigger speed;
 * at or above it, the ALT_HOLD profile, which dives until DESC_MARGIN above the target, so every trough
 * lands on the target. The climb or hold choice is made at each pull-up, from the lowest altitude
 * since the previous pull-up (the trough), as the live law does.
 *
 * <p>An instance is the law's state; the flight models extend it, so a rollout carries the same state
 * the live law has.
 */
public class PorpoiseLaw {
    /** Phases: holding the dive pitch, holding the pull-up pitch, sweeping back down to the dive pitch. */
    public static final int DIVE = 0, PULL_UP = 1, SWEEP = 2;

    /** Dive and pull-up pitch (degrees, nose down positive), pull-up trigger speed (m/s), ticks held at
     *  the pull-up pitch, and sweep rate back down (degrees per tick). */
    public record Profile(double dive, double pullUp, double triggerSpeed, int pullUpHold, double sweep) {}

    /** Climb: about +0.9 m/s at 22 m/s ground speed. The pull-up pitch is over-commanded; the pitch lag
     *  means about -52 is reached. */
    public static final Profile CLIMB = new Profile(40.0, -62.0, 44.0, 11, 18.0 / 20.0);
    /** Altitude hold at full speed (about 30.1 m/s). It pulls up on altitude, so it has no trigger speed. */
    public static final Profile ALT_HOLD = new Profile(38.0, -66.0, Double.NaN, 13, 24.0 / 20.0);
    /** Holding: pull up when the altitude is within this of the target. The pitch lag carries the true
     *  trough about 3 blocks lower. */
    public static final double DESC_MARGIN = 6.0;

    public int phase = DIVE;
    public double cmd;             // commanded pitch (degrees)
    public int pullUpTicks;        // ticks spent at the pull-up pitch so far
    public boolean climbing;
    public double cycleMin = Double.MAX_VALUE;   // lowest altitude since the last pull-up
    public double trough = -1.0e9;               // the cycle's lowest altitude at the last pull-up

    public Profile profile() { return climbing ? CLIMB : ALT_HOLD; }

    /** Copy another law's state into this one. */
    public void copyLaw(PorpoiseLaw o) {
        phase = o.phase; cmd = o.cmd; pullUpTicks = o.pullUpTicks; climbing = o.climbing;
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
                phase = PULL_UP; pullUpTicks = 0; cmd = p.pullUp();
                return true;
            }
        } else if (phase == PULL_UP) {
            cmd = p.pullUp(); pullUpTicks++;
            if (pullUpTicks >= p.pullUpHold()) phase = SWEEP;
        } else {
            cmd += p.sweep();
            if (cmd >= p.dive()) { cmd = p.dive(); phase = DIVE; }
        }
        return false;
    }

    /** Restart at the beginning of a climb: diving at the climb profile's dive pitch. */
    public void restartClimb() {
        climbing = true; phase = DIVE; cmd = CLIMB.dive(); pullUpTicks = 0; cycleMin = Double.MAX_VALUE;
    }

    /** Restart in altitude hold: diving at the hold profile's pitch, to pull up near the target. For a hand-over
     *  above the target with speed, as at the end of a rocket takeoff, where the climb restart would first dive
     *  to its trigger speed and give up height. */
    public void restartHold() {
        climbing = false; phase = DIVE; cmd = ALT_HOLD.dive(); pullUpTicks = 0; cycleMin = Double.MAX_VALUE;
    }
}
