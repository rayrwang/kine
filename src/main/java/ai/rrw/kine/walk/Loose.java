package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;

/**
 * Layer 1's loosened moves: nearly possible jumps, offered only when the proven moves find no way (RW 2026-10-15
 * 13:43:39 on Hielke's Parkour Spiral, "coudl make a "losedened l1 palner" where its allwoed to make almost possible
 * moves and hva ehte hoe the l2 figure them out", and 13:48:45, "so it shoudl try purely legal layer 1, then fallbakc
 * ot het right"). A loose move is a jump from a ground floor at an edge to any floor within what some known trick can
 * reach for its height (reach): onto floors the graph's proven moves never land on (slabs, fences, carpets on cacti,
 * azalea, a block a jump up and three over), round walls, over hazards. Whether it can really be made is not decided
 * here: each loose move on the route the loose search chooses is checked in layer 2's movement copy before the walk
 * commits to it (LooseCheck: the jump search, its standing spots, run-ups and templates), in route order with the state
 * the previous landing leaves; a move that fails is marked (Walk's blocked moves) and the search runs again without it
 * (lazy checking: LazySP, Dellin and Srinivasa, ICAPS 2016, which evaluates only the costly edges of the current best
 * path and replans when one fails).
 *
 * <p>Calibration. reach(dh) is the farthest centre-to-centre distance at which the movement copy found any way at all
 * for the landing's height above the take-off (GapReachTest, ~/_/kine_path_2026-10-12/parkour_measure/gapreach.csv:
 * full blocks, run-ups 0 to 8: 6.40 at 1 down, 5.66 level, 4.47 one up), plus half a block for the tricks the copy's
 * plain jumps don't cover (the catalogue's longest single jumps: gap_5_375b_max_tdhh, 6.375 level, possible_hard;
 * up_4p0p5, 5.0 at half a block up; neos round a wall within these); 7.71 at 3 down (7.21 measured) and 6.90 at 2
 * down. Where a jump's fall (its peak 1.25 over the take-off, plus the drop) is more than the walk's safe fall
 * (Costs.maxFall: 3.9, or more by the Fall damage allowed setting), the jump would hurt, which the walk refuses
 * (Recipe.hurts): there only stepping off the edge at a sprint, which carries about 2.5 blocks past it falling 2 and 3.1
 * falling 3 (the copy's air drag and sprint), so about 4.1 and 4.7 centre to centre, plus the half block, and 0.6 more
 * a block deeper. Nothing past what any known trick can do is offered. The price is the run's time (the sprint over the distance) plus a jump's stop and search (BASE) and
 * the risk: the measured share of tries that land at that distance and height (ease, from the same table, the easiest
 * about 0.75), priced as RISK times the log of how much rarer than the easiest it is.
 */
final class Loose {
    private Loose() {}

    /** The farthest a loose jump is offered, centre to centre (blocks), at any height and safe fall: a jump 3 down. */
    static final double MAX_REACH = 7.71;
    /** The highest a loose jump lands above its take-off (a jump's peak, 1.2522), and onto a climbable (its cell's
     *  bottom at most this above the feet: the feet must get into the ladder's cell). */
    static final double MAX_UP = 1.25, CLIMB_UP = 1.25;

    // a jump's reach: the measured farthest way plus half a block, at the heights measured; between, linear
    private static final double[] REACH_DH = {-3.0, -2.0, -1.0, 0.0, 0.5, 1.0, 1.25};
    private static final double[] REACH_D = {7.71, 6.90, 6.90, 6.16, 5.60, 4.97, 4.20};
    /** A jump's peak over its take-off (0.42 up, then gravity and drag): what its fall adds to the drop. */
    static final double JUMP_PEAK = 1.2522;

    /** The farthest loose jump offered (centre to centre, blocks) to a landing dh above the take-off, the walk's safe fall
     *  being maxFall; 0 past what a jump reaches. */
    static double reach(double dh, double maxFall) {
        if (dh > MAX_UP + 1e-4) return 0.0;
        if (dh < -(maxFall - JUMP_PEAK)) {
            // a jump would hurt: stepping off the edge at a sprint
            return Math.max(4.0, Math.min(6.5, 4.6 + (-2.0 - dh) * 0.6));
        }
        if (dh <= REACH_DH[0]) return REACH_D[0];
        for (int i = 1; i < REACH_DH.length; i++)
            if (dh <= REACH_DH[i]) {
                double f = (dh - REACH_DH[i - 1]) / (REACH_DH[i] - REACH_DH[i - 1]);
                return REACH_D[i - 1] + f * (REACH_D[i] - REACH_D[i - 1]);
            }
        return REACH_D[REACH_D.length - 1];
    }

    /** As above at the safe fall of Costs' default (3.9). */
    static double reach(double dh) {
        return reach(dh, 3.9);
    }

    // ease(d, dh): the share of the copy's tries that land (gapreach.csv, the best run-up), by distance, at the heights
    // measured (3 down to 1 up); between heights and distances, linear; past the last distance measured with any way, 0.01
    private static final double[] EASE_D = {2.00, 2.24, 2.83, 3.00, 3.16, 3.61, 4.00, 4.12, 4.24, 4.47, 5.00, 5.10, 5.39, 5.66,
        5.83, 6.00, 6.08, 6.32, 6.40, 6.71, 7.00, 7.07, 7.21};
    private static final double[][] EASE = {
        // dh -3
        {0.55, 0.66, 0.65, 0.48, 0.56, 0.73, 0.72, 0.75, 0.75, 0.77, 0.69, 0.63, 0.57, 0.51, 0.40, 0.26, 0.24, 0.17, 0.16, 0.06, 0.01, 0.03, 0.01},
        // dh -2
        {0.42, 0.61, 0.68, 0.55, 0.66, 0.77, 0.73, 0.73, 0.74, 0.67, 0.51, 0.46, 0.34, 0.23, 0.14, 0.04, 0.04, 0.03, 0.03, 0.01, 0.01, 0.01, 0.01},
        // dh -1
        {0.40, 0.57, 0.73, 0.65, 0.71, 0.78, 0.73, 0.70, 0.70, 0.58, 0.39, 0.31, 0.20, 0.12, 0.06, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01},
        // dh 0
        {0.42, 0.49, 0.69, 0.69, 0.71, 0.66, 0.58, 0.53, 0.51, 0.38, 0.14, 0.07, 0.03, 0.03, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01},
        // dh +1
        {0.85, 0.80, 0.68, 0.63, 0.58, 0.42, 0.15, 0.11, 0.13, 0.05, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01},
    };
    static final double EASIEST = 0.75;

    /** The measured share of tries that land, for a jump d blocks (centre to centre) to a landing dh above. A neighbour
     *  (under 2) counts as at 2. More than 1.75 down (stepping off the edge, Loose.reach): as 1 down, its distance scaled
     *  by the two reaches. */
    static double ease(double d, double dh) {
        return ease(d, dh, 3.9);
    }

    /** As above, with the walk's safe fall: a step off the edge (where a jump would hurt) as a jump 1 down, its distance
     *  scaled by the two reaches. */
    static double ease(double d, double dh, double maxFall) {
        if (dh < -(maxFall - JUMP_PEAK)) return ease(d * reach(-1.0, maxFall) / reach(dh, maxFall), -1.0, maxFall);
        double hh = Math.max(-3.0, Math.min(1.0, dh));
        int lo = (int) Math.floor(hh + 3.0);
        if (lo >= 4) lo = 3;
        double fh = hh + 3.0 - lo;
        return (1 - fh) * easeAt(lo, d) + fh * easeAt(lo + 1, d);
    }

    private static double easeAt(int row, double d) {
        double[] e = EASE[row];
        if (d <= EASE_D[0]) return e[0];
        for (int i = 1; i < EASE_D.length; i++)
            if (d <= EASE_D[i]) {
                double f = (d - EASE_D[i - 1]) / (EASE_D[i] - EASE_D[i - 1]);
                return e[i - 1] + f * (e[i] - e[i - 1]);
            }
        return 0.01;
    }

    /** A loose jump's base price beyond its run (ticks): stopping at the take-off and the jump search, about half a
     *  second; and the price of its risk, per natural log of how much rarer its landing ways are than the easiest. */
    static final double BASE = 10.0, RISK = 12.0;

    /** A loose jump's cost (ticks): the run over the distance at the sprint's rate, the base, the risk. */
    static double cost(Costs c, double d, double dh) {
        double e = Math.max(0.01, ease(d, dh, c.safeFall));
        return d * c.sprint + BASE + RISK * Math.log(Math.max(1.0, EASIEST / e));
    }

    /**
     * The most a loose route may cost (ticks): four times the estimate from the start to the goal, and at least 600 more
     * than it (400 refused the Parkour Spiral's leg 8, a level that winds round to a checkpoint nine blocks over its
     * start, at 441). A loose route past that is a detour round most of what's loaded (the Parkour Spiral offline: legs whose
     * own way needs something the loose moves don't offer, a ladder's trick or a water lift, went down to the sea and up
     * another level, 250 steps), more likely a way the checks will strike move by move than the walk's: no loose route
     * then, and the walk falls back as before (the nearest floor, walking without a route, the hops).
     */
    static double costBound(Costs c, long start, int gx, int gz, double goalY, View v) {
        float h = height(v, start);
        double hor = Math.hypot(gx - View.nodeX(start), gz - View.nodeZ(start));
        double climb = Double.isNaN(goalY) || Float.isNaN(h) ? 0.0 : Math.max(0.0, goalY - h);
        double est = c.estimate(hor, climb);
        return Math.max(4.0 * est, est + 600.0);
    }

    /** How far a bounce off slime is offered: from the take-off to the slime, and from the slime to the landing (blocks,
     *  centre to centre): about a level jump's reach each way. */
    static final double BOUNCE_REACH = 6.2;

    /** A bounce's cost (ticks): the run over both stretches, the base, and the risk at an ease of 0.05 (rarely more than a
     *  narrow way lands: a bounce's height and heading are set by the fall onto the slime). */
    static double bounceCost(Costs c, double d) {
        return d * c.sprint + BASE + RISK * Math.log(EASIEST / 0.05);
    }

    /** A move's key, from floor `from` to floor `to` (View nodes): for the blocked moves and the checks' memory. */
    static long edge(long from, long to) {
        return from * 0x9E3779B97F4A7C15L ^ Long.rotateLeft(to, 29) * 0xC2B2AE3D27D4EB4FL ^ 0x165667B19E3779F9L;
    }

    /**
     * The moves a failed check stands for, blocked with it: from the take-off's floor or a floor at its height in the
     * eight columns round it, to the landing's floor or one at its height in the eight round it, no shorter than the one
     * checked. A longer jump between the same two places is no easier, and the check from the take-off already tried its
     * standing spots (its block's middle, sides and corners) and run-ups. Without this a failed jump between two
     * platforms came back as its neighbour, one column over, check after check (the Parkour Spiral's leg 2, offline: 31
     * checks of one gap).
     */
    static long[] family(View v, long from, long to) {
        float fh = height(v, from), th = height(v, to);
        if (Float.isNaN(fh) || Float.isNaN(th)) return new long[]{edge(from, to)};
        double d = Math.hypot(View.nodeX(to) - View.nodeX(from), View.nodeZ(to) - View.nodeZ(from));
        // a bounce off slime (longer than a jump reaches): two columns round each end (the graph offers one from every
        // take-off near a pad to every floor near it; the Parkour Spiral's leg 18, offline: dozens of failed bounces from
        // one ledge to one ledge)
        int r = d > MAX_REACH + 1e-9 ? 2 : 1;
        long[] fs = sameHeightRound(v, from, fh, r), ts = sameHeightRound(v, to, th, r);
        it.unimi.dsi.fastutil.longs.LongArrayList out = new it.unimi.dsi.fastutil.longs.LongArrayList();
        out.add(edge(from, to));
        for (long f : fs)
            for (long t : ts) {
                if (f == from && t == to) continue;
                double e = Math.hypot(View.nodeX(t) - View.nodeX(f), View.nodeZ(t) - View.nodeZ(f));
                if (e >= d - 0.25) out.add(edge(f, t));
            }
        return out.toLongArray();
    }

    private static float height(View v, long n) {
        ChunkFloors c = v.chunk(View.nodeX(n), View.nodeZ(n));
        int i = v.index(n);
        return c == null || i < 0 ? Float.NaN : c.h(i);
    }

    /** Node n and the ground floors within 0.1 of height h in the columns round it, r columns out. */
    private static long[] sameHeightRound(View v, long n, float h, int r) {
        it.unimi.dsi.fastutil.longs.LongArrayList out = new it.unimi.dsi.fastutil.longs.LongArrayList();
        out.add(n);
        int x = View.nodeX(n), z = View.nodeZ(n);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (dx == 0 && dz == 0) continue;
                ChunkFloors c = v.chunk(x + dx, z + dz);
                if (c == null) continue;
                int cnt = c.count((x + dx) & 15, (z + dz) & 15);
                for (int j = 0; j < cnt; j++) {
                    int idx = c.index((x + dx) & 15, (z + dz) & 15, j);
                    if (c.type(idx) == ChunkFloors.GROUND && Math.abs(c.h(idx) - h) < 0.1) out.add(View.node(x + dx, z + dz, j));
                }
            }
        return out.toLongArray();
    }

    /** Standing still on the ground at (x, y, z), y the floor's top (the jump search's standing start for a check). */
    static MoveState standing(double x, double y, double z) {
        MoveState s = new MoveState();
        s.x = x; s.y = y; s.z = z;
        s.vy = -0.0784000015258789;
        s.onGround = true;
        s.hasSupport = true;
        s.supX = (int) Math.floor(x); s.supY = (int) Math.floor(y - 1e-3); s.supZ = (int) Math.floor(z);
        return s;
    }
}
