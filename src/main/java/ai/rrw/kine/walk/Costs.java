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
    /** The proven moves' deepest drop (the graph's DROP, the sweep): Baritone's maxFallHeightNoWater, a block in hand under
     *  the game's own limit (safeFall). */
    public double maxFall = 3.0;
    /** The most a fall may be without hurting, blocks: LivingEntity.calculateFallDamage is floor(fall + 1e-6 - 3) (26.1.2,
     *  the SAFE_FALL_DISTANCE attribute 3), so a fall under 4 costs nothing; 3.9 keeps a tenth in hand. What layer 2's
     *  runs, the jump search and the loose moves allow (the Parkour Spiral's second level is a walk off a slab 3.5 over
     *  the next floor). The strict graph keeps maxFall: with this, its sweep tightened routes down slopes of drops that
     *  layer 2 with Parkour off then wouldn't take (Layer2Test.parkourOffGoesDownASteepSlopeOfDrops). */
    public double safeFall = 3.9;

    /**
     * The deepest fall onto a block whose landing takes `factor` of the damage (BlockInfo.fallFactor: hay bales and honey
     * 0.2, a bed 0.5; Block.fallOn's overrides) that hurts no more than a fall of `limit` onto stone, with the same
     * margin in hand: the damage is floor((fall + 1e-6 - 3) * factor). At the safe fall of 3.9 a hay bale takes a fall
     * of 7.9 (the Parkour Spiral drops into a cave onto hay). A factor of 0 (slime, which bounces) takes any fall: 30
     * more.
     */
    public static double fallLimit(double limit, float factor) {
        if (!(factor < 1f)) return limit;
        if (factor <= 0f) return limit + 30.0;
        double allowed = Math.floor(limit - 3.0 + 1e-6);
        double margin = allowed + 1.0 - (limit - 3.0);
        return 3.0 + (allowed + 1.0) / factor - margin;
    }
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

    /** The gait setting's top speeds on foot, on the movement copy (GaitSpeedTest, 2026-10-14): walking 4.376 m/s at
     *  the most (on blue ice; 4.317 on stone, and no faster with jumps: a jump adds its push only while sprinting),
     *  sprinting 5.689 (blue ice; 5.612 on stone). The estimate's rate at each, a little under: 20 / 4.40 and 20 / 5.72
     *  ticks a block. (Sprint-jumping keeps fastest's 7.127, measured on stone: on ice it reaches 9.3.) */
    public static final double WALK_FASTEST = 20 / 4.40, SPRINT_FASTEST = 20 / 5.72;
    /** A sprint, and a swim, a block at Walk and Sprint (layer 2 on the copy: a swim without sprinting 8.5). */
    public static final double SPRINT_RATE = 20 / 5.612, SWIM_WALKING = 8.5;

    private static final Costs[] LAYER2_BY_GAIT = byGait(LAYER2), BARITONE_BY_GAIT = byGait(BARITONE);

    /** These costs at a gait (Settings.GAIT_*): the rates and the estimate's top speed at the fastest it allows, so
     *  the estimate never exceeds a cost; Sprint-jump and Elytra hops as they are. */
    public static Costs forGait(Costs base, int gait) {
        Costs[] t = base == LAYER2 ? LAYER2_BY_GAIT : base == BARITONE ? BARITONE_BY_GAIT : null;
        if (t != null) return t[Math.max(0, Math.min(t.length - 1, gait))];
        return atGait(base, gait);
    }

    private static Costs[] byGait(Costs base) {
        Costs[] t = new Costs[4];
        for (int g = 0; g < 4; g++) t[g] = atGait(base, g);
        return t;
    }

    static Costs atGait(Costs base, int gait) {
        if (gait >= ai.rrw.kine.Settings.GAIT_SPRINT_JUMP) return base;
        Costs c = base.copy();
        if (gait == ai.rrw.kine.Settings.GAIT_WALK) {
            // walking everywhere: the walk's rate (as without sprinting), a swim without sprint
            c.sprint = c.noSprint;
            c.walk = c.noSprint;
            c.noSprintFactor = 1.0;
            c.water = Math.max(c.water, SWIM_WALKING);
            c.fastest = WALK_FASTEST;
        } else {
            // sprinting, no pace jumps: a sprint's rate where layer 2 would sprint-jump
            c.sprint = Math.max(base.sprint, SPRINT_RATE);
            c.walk = base.walk == base.sprint ? c.sprint : base.walk;   // layer 2's table runs everything at its sprint
            c.noSprintFactor = c.noSprint / c.sprint;
            c.fastest = SPRINT_FASTEST;
        }
        return c;
    }

    private static final java.util.Map<Costs, java.util.Map<Double, Costs>> BY_FALL = new java.util.concurrent.ConcurrentHashMap<>();

    /** These costs with falls hurting up to `hp` allowed (the walk's Fall damage allowed: a fall of hp more hurts that
     *  much more): one copy per base and allowance. */
    public static Costs withFallDamage(Costs base, int hp) {
        return BY_FALL.computeIfAbsent(base, b -> new java.util.concurrent.ConcurrentHashMap<>()).computeIfAbsent((double) hp, m -> {
            Costs c = base.copy();
            c.maxFall = base.maxFall + hp;
            c.safeFall = base.safeFall + hp;
            return c;
        });
    }

    /** A copy of every number. */
    Costs copy() {
        Costs t = timeOnly();
        t.hazardNear = hazardNear;
        t.waterPenalty = waterPenalty;
        t.glideWear = glideWear;
        return t;
    }

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
        t.safeFall = safeFall;
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
