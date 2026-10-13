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
    /** A block on foot without sprinting (food at 6 or less): Baritone's walk; layer 2 measured 4.63 on the copy. */
    public double noSprint = 20 / 4.317;
    /** How much slower everything is without sprinting, for the ETA's look at hunger ahead: Baritone's walk against
     *  its sprint; layer 2 on the copy: open ground 4.63 against 2.80 a block, a swim 8.5 against 5.1. */
    public double noSprintFactor = 5.612 / 4.317;
    public double sprint = 20 / 5.612;          // 3.564
    public double water = 20 / 2.2;             // 9.091
    /** Wading (feet in water one deep, standing on the bottom): Baritone has one water cost for both. */
    public double wade = 20 / 2.2;
    public double ladderUp = 20 / 2.35;         // 8.511
    public double ladderDown = 20 / 3.0;        // 6.667
    /** Baritone's soul sand cost is twice walking ("0.4 in BlockSoulSand but effectively about half"). */
    public double slowFactor = 2.0;
    public double jumpPenalty = 2.0;
    /** A jump over a gap (layer 2 only), on top of the sprint over its length. */
    public double gapPenalty = 2.0;
    /** How much of a fall's time a running drop hides, ticks; below zero, Baritone's walk-off formula instead. */
    public double fallHidden = -1;
    /** A turn of 90 degrees on the route, ticks: only in the route's time (the search's floors carry no heading). */
    public double turn = 0;
    /** A glide (elytra hops): its start (the jump, opening the glider) and a block of it, ticks, and its wear. */
    public double glideStart = 12.0, glidePerBlock = 1.5, glideWear = 0.5;

    /** A glide of `dist` blocks: its time and the elytra's wear by the tick (a point of durability a second). */
    double glideCost(double dist) {
        double ticks = glideStart + dist * glidePerBlock;
        return ticks * (1.0 + glideWear);
    }
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

    /**
     * The costs fitted from layer 2's own simulated runs (RW: layer 2 "can basiclay generate training data which can
     * feed back into the cost esitmes for layer one"): CostFitTest walks layer 2 closed loop on the movement copy over
     * test courses and checks these numbers against what it measures. Measured 2026-10-12 (with this table planning
     * the courses' routes, since the route changes what is measured; the second fit, with the sneaking gaits and the
     * longer horizon in water): open ground 2.800 ticks a block once running (it sprint-jumps; 7.14 m/s), a sprint swim
     * 5.10 a block, wading one deep 8.3, soul sand 6.25, a ladder 8.9 up and 6.7 down, a step up one block 0.6 to 2.4
     * more than the flat (by the route and the search), a drop of 3 about 6.3 to 7.5 more (the run goes on while
     * falling), into and out of water within a few ticks of nothing, a 90 degree corner about 9. The flat rate
     * is kept at the estimate's top speed, so the estimate never exceeds a cost.
     */
    public static Costs layer2() {
        Costs c = new Costs();
        c.sprint = c.fastest;           // 2.806
        c.walk = c.fastest;             // layer 2 runs wherever it can; walk-offs and climbs off at the same rate
        c.noSprint = 4.63;              // without sprinting (food at 6 or less): measured on the copy
        c.noSprintFactor = 4.63 / 2.80;
        c.water = 5.10;
        c.wade = 8.2;
        c.slowFactor = 6.13 / 2.806;
        c.ladderUp = 9.0;
        c.ladderDown = 7.0;
        c.jumpOne = 0.0;
        c.jumpPenalty = 1.0;
        c.waterPenalty = 0.0;           // per block of swimming too in the graph: the crossing's 5 is left out
        c.gapPenalty = 0.5;
        c.fallHidden = 2.6;
        c.turn = 9.0;
        return c;
    }

    public static final Costs LAYER2 = layer2();

    /** The same costs as time alone (ticks), for the arrival time: no penalty for a hazard next to the way, for
     *  getting into water or for the elytra's wear. */
    public Costs timeOnly() {
        Costs t = new Costs();
        t.walk = walk; t.noSprint = noSprint; t.noSprintFactor = noSprintFactor; t.sprint = sprint; t.water = water;
        t.wade = wade; t.ladderUp = ladderUp; t.ladderDown = ladderDown; t.slowFactor = slowFactor;
        t.jumpPenalty = jumpPenalty; t.gapPenalty = gapPenalty; t.fallHidden = fallHidden; t.turn = turn;
        t.glideStart = glideStart; t.glidePerBlock = glidePerBlock; t.glideWear = 0.0;
        t.walkOffFraction = walkOffFraction; t.jumpOne = jumpOne; t.fastest = fastest; t.fastestClimb = fastestClimb;
        t.maxFall = maxFall;
        t.waterPenalty = 0.0;
        t.hazardNear = 0.0;
        return t;
    }

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
        if (wade) return this.wade;
        double r = canSprint ? sprint : noSprint;
        return slow ? r * slowFactor : r;
    }

    /** Walking off an edge and falling `drop` blocks, `dist` blocks across (Baritone's descend and fall: walk off, then
     *  the larger of the fall and centring on the new block). */
    double dropCost(double dist, double drop) {
        if (fallHidden >= 0) return dist * walk + Math.max(0.0, fallTicks(drop) - fallHidden);
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
