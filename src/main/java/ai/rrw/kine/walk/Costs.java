package ai.rrw.kine.walk;

/**
 * What each move costs the search, in ticks: one table, so layer 2 can refit the numbers from what it measures. The
 * starting values are Baritone's (ActionCosts and Settings in github.com/cabaletta/baritone, read 2026-10-12; RW: use
 * them "until get more acurate ones from teh feedbck from level 2"): walking 20 / 4.317 ticks a block, sprinting
 * 20 / 5.612, in water 20 / 2.2, a ladder 20 / 2.35 up and 20 / 3.0 down; falls and jumps from gravity (its
 * distanceToTicks: velocity 3.92 * (1 - 0.98^t) blocks a tick), a jump up one block costing the time to rise 1.25
 * less the time to fall the last 0.25; and its penalties, 2 for a jump and 3 for a move onto water. Diagonal and
 * knight's moves cost their length times the rate.
 *
 * <p>The estimate (A*'s heuristic) must never exceed the real cost, now or after a refit lowers these numbers, so it
 * charges the horizontal distance at the fastest speed a player reaches on foot (sprint-jumping, measured on the
 * movement copy) and a climb at the fastest a jump rises.
 */
public final class Costs {
    public double walk = 20 / 4.317;            // 4.633
    public double sprint = 20 / 5.612;          // 3.564
    public double water = 20 / 2.2;             // 9.091
    public double ladderUp = 20 / 2.35;         // 8.511
    public double ladderDown = 20 / 3.0;        // 6.667
    /** Baritone's soul sand cost is twice walking ("0.4 in BlockSoulSand but effectively about half"). */
    public double slowFactor = 2.0;
    public double jumpPenalty = 2.0;
    public double waterPenalty = 3.0;
    /** Walking off an edge: 0.8 of a block's walk before the fall starts (Baritone's WALK_OFF_BLOCK_COST). */
    public double walkOffFraction = 0.8;
    /** A jump up one block (Baritone's JUMP_ONE_BLOCK_COST). */
    public double jumpOne = fallTicks(1.25) - fallTicks(0.25);

    /** Ticks a block at the fastest speed on foot: sprint-jumping, 7.127 m/s on flat ground as measured on the movement
     *  copy (GroundPhysics, CostsTest.sprintJumpTopSpeed), so 20 / 7.127 = 2.806. */
    public double fastest = 20 / 7.127;
    /** Ticks a block of climb at the fastest: a jump reaches one block up in 3 ticks (the copy: 0.42, then
     *  (v - 0.08) * 0.98 a tick, 1.0016 after three). */
    public double fastestClimb = 3.0;
    /** Drops of more than this hurt (the game's safe fall distance; Baritone's maxFallHeightNoWater). */
    public double maxFall = 3.0;
    /** Added for each hazard (lava, fire, cactus and the like) in the eight columns around a floor at body height. */
    public double hazardNear = 2.0;

    public static final Costs BARITONE = new Costs();

    /** Gravity's ticks to fall this far from rest (Baritone's distanceToTicks). */
    public static double fallTicks(double distance) {
        if (distance <= 0) return 0;
        double left = distance;
        int t = 0;
        while (true) {
            double v = (Math.pow(0.98, t) - 1) * -3.92;
            if (left <= v) return t + left / v;
            left -= v;
            t++;
        }
    }

    /** How far gravity drops a body in t ticks from rest (the inverse of fallTicks). */
    public static double fallDistance(double t) {
        double d = 0;
        int i = 0;
        while (i + 1 <= t) { d += (Math.pow(0.98, i) - 1) * -3.92; i++; }
        return d + (t - i) * (Math.pow(0.98, i) - 1) * -3.92;
    }

    /** The rate a block for walking on this floor: sprint where allowed, twice for slow blocks, water when wading. */
    double rate(boolean canSprint, boolean slow, boolean wade) {
        if (wade) return water;
        double r = canSprint ? sprint : walk;
        return slow ? r * slowFactor : r;
    }

    /** Walking off an edge and falling `drop` blocks, `dist` blocks across (Baritone's descend and fall: walk off, then
     *  the larger of the fall and centring on the new block). */
    double dropCost(double dist, double drop) {
        double offWalk = dist * walk * walkOffFraction;
        double centre = dist * walk * (1 - walkOffFraction);
        return offWalk + Math.max(fallTicks(drop), centre);
    }

    /** Stepping or jumping up `rise` (more than a step), `dist` across (Baritone's ascend: the larger of the jump and the
     *  walk, plus the jump penalty). */
    double jumpCost(double dist, double rise) {
        return Math.max(jumpOne * Math.max(1.0, rise), dist * walk) + jumpPenalty;
    }

    /** The estimate from a horizontal distance and a climb (both non-negative). */
    public double estimate(double horizontal, double climb) {
        return Math.max(horizontal * fastest, climb * fastestClimb);
    }
}
