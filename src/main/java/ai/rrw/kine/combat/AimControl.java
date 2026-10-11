package ai.rrw.kine.combat;

/**
 * How far auto aim turns the view each tick toward the solved aim, one axis at a time (degrees). Pure, so it
 * can be tested on its own.
 *
 * <p>The turn follows the solution's own motion since the last tick (a feed-forward) and eases a fraction of the
 * rest of the way. Easing alone trails a solution that moves by w each tick by (1 - SMOOTH) / SMOOTH * w (about
 * 1.9 w), which on a strafing player at 20 blocks is more than half its width; with the feed-forward, the view
 * after each turn sits on the solution of that tick once the first ease has closed the gap. When what is left
 * after the feed-forward is within DIRECT_COUNTS mouse counts, the turn goes the whole way: easing a fraction of
 * a small gap rounds to zero whole counts, so the view would stop up to 1.4 counts short (0.9 degrees at the
 * highest sensitivity).
 */
public final class AimControl {
    private AimControl() {}

    /** Fraction of the gap closed each tick. */
    public static final float SMOOTH = 0.35f;
    /** Largest turn in one tick, degrees (200 a second, within what a person can do). */
    public static final float MAX_STEP = 10f;
    /** Within this many mouse counts of the solution, turn the whole way. */
    public static final double DIRECT_COUNTS = 2.0;
    /** Largest feed-forward taken from the solution's motion, degrees a tick (a jump is not motion). */
    public static final float MAX_FEED = 5f;

    /**
     * The turn this tick on one axis. gap: the solution minus the view now. motion: the solution now minus the
     * solution last tick (0 on the first tick of a target). count: degrees per mouse count.
     */
    public static float step(float gap, float motion, double count) {
        float ff = Math.max(-MAX_FEED, Math.min(MAX_FEED, motion));
        // what's left once the solution's own motion is followed: eased, or closed whole when it is small (testing
        // the gap itself would keep a moving target's gap above the cut, and the eased leftover would round away)
        float rest = gap - ff;
        float s = Math.abs(rest) <= DIRECT_COUNTS * count ? gap : ff + SMOOTH * rest;
        return Math.max(-MAX_STEP, Math.min(MAX_STEP, s));
    }
}
