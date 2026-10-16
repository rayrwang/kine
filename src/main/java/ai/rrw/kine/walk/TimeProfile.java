package ai.rrw.kine.walk;

/**
 * The planned time left along a route, for the arrival time (ETA): for each floor of the route's grid path, the ticks
 * from there to the route's end by layer 1's own moves costed as time alone (Costs.timeOnly: the costs fitted from
 * layer 2's simulated runs when it walks, Baritone's for the layer 1 follower; no penalty for hazards or the elytra's
 * wear), and the blocks of it on foot (where sprinting costs food). Past a route that stops at the loaded area's edge,
 * the straight line on to the goal, its time charged at the line's own rate (lineRate). Made once per
 * plan on the planner's worker; read on the game's thread.
 */
public final class TimeProfile {
    /** Floor centres along the grid path. */
    final double[] x, z;
    /** Ticks and blocks on foot from each floor to the route's end. */
    final double[] ticks, footBlocks;
    final int n;
    /** The straight line from the route's end to the goal when the route stops short of it (else 0). */
    public final double beyondBlocks;
    /** Ticks a block of open ground at the planned rate. */
    public final double flatRate;
    /** Ticks a block on the straight line toward the goal: the mean of the floors on it as far as it is loaded (the
     *  search's own rate past the loaded edge; a sea's swim, the land's sprint); the part past the route's end is
     *  charged at it. */
    public final double lineRate;
    /** The route's own straight distance (start to end) and its planned time. */
    public final double straight, total;
    /** How much slower without sprinting. */
    public final double noSprintFactor;
    /** Planned with sprinting (food above 6 then). */
    public final boolean sprinted;

    private TimeProfile(double[] x, double[] z, double[] ticks, double[] footBlocks, double beyondBlocks, double flatRate,
                        double lineRate, double noSprintFactor, boolean sprinted) {
        this.lineRate = lineRate;
        this.sprinted = sprinted;
        this.x = x; this.z = z; this.ticks = ticks; this.footBlocks = footBlocks; this.n = x.length;
        this.beyondBlocks = beyondBlocks; this.flatRate = flatRate; this.noSprintFactor = noSprintFactor;
        this.straight = n == 0 ? 0 : Math.hypot(x[n - 1] - x[0], z[n - 1] - z[0]);
        this.total = n == 0 ? 0 : ticks[0];
    }

    /** The profile of a search result's grid path with these costs (as time) and the graph's options. */
    public static TimeProfile of(View v, Costs costs, boolean canSprint, boolean gaps, boolean glides, PathSearch.Result r,
                                 Route route, int goalX, int goalZ) {
        Costs t = costs.timeOnly();
        Graph g = new Graph(v, t);
        g.canSprint = canSprint;
        g.gaps = gaps;
        g.glides = glides;
        g.looseOf(r);   // a loose route's own loose moves, priced as the search priced them
        g.doors = Graph.doorsOn;
        int n = r.path.length;
        double[] x = new double[n], z = new double[n], ticks = new double[n], foot = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = View.nodeX(r.path[i]) + 0.5;
            z[i] = View.nodeZ(r.path[i]) + 0.5;
        }
        // the walk follows the tightened route, shorter than the grid path's steps: its walking legs scaled by the two
        // lengths' ratio
        double straighten = route != null && route.gridLength > 1e-6 ? Math.min(1.0, route.length / route.gridLength) : 1.0;
        double[] swimStraighten = swimStraighten(r, x, z);
        for (int i = n - 2; i >= 0; i--) {
            g.expand(r.path[i]);
            double best = Double.NaN;
            for (int e = 0; e < g.n; e++) if (g.to[e] == r.path[i + 1] && (Double.isNaN(best) || g.cost[e] < best)) best = g.cost[e];
            double len = Math.hypot(x[i + 1] - x[i], z[i + 1] - z[i]);
            if (Double.isNaN(best)) best = len * (canSprint ? t.sprint : t.noSprint);
            int mv = r.moves[i + 1];
            if (mv == Graph.WALK || mv == Graph.WADE) best *= straighten;
            else if (mv == Graph.SWIM) best *= swimStraighten[i];
            ticks[i] = ticks[i + 1] + best;
            boolean onFoot = mv == Graph.WALK || mv == Graph.JUMP || mv == Graph.DROP || mv == Graph.GAP
                || mv == Graph.LOOSE || mv == Graph.OPEN || mv == Graph.CLIMB_OFF;
            foot[i] = foot[i + 1] + (onFoot ? len : 0.0);
        }
        // the corners of the tightened route (Costs.turn a 90 degree turn; the search's floors carry no heading), each
        // charged before the grid floor nearest it
        if (t.turn > 0 && route != null && n > 1) {
            double[] add = new double[n];
            int hint = 0;
            for (int k = 1; k + 1 < route.n; k++) {
                double ax = route.x[k] - route.x[k - 1], az = route.z[k] - route.z[k - 1];
                double bx = route.x[k + 1] - route.x[k], bz = route.z[k + 1] - route.z[k];
                double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
                if (la < 1e-6 || lb < 1e-6) continue;
                double cos = Math.max(-1, Math.min(1, (ax * bx + az * bz) / (la * lb)));
                double deg = Math.toDegrees(Math.acos(cos));
                int best = hint;
                double bestD = Double.MAX_VALUE;
                for (int i = hint; i < n; i++) {
                    double d = Math.hypot(x[i] - route.x[k], z[i] - route.z[k]);
                    if (d < bestD) { bestD = d; best = i; }
                }
                hint = best;
                add[best] += t.turn * deg / 90.0;
            }
            double run = 0;
            for (int i = n - 1; i >= 0; i--) { run += add[i]; ticks[i] += run; }   // the corners at or past each floor
        }
        // where the walk is along the path, measured on the way it walks: the grid floors between two of the route's
        // waypoints, and those of a swim run timed on its straight line, are placed along the straight line between its
        // ends at their share of the grid's length, so progress on the straight line counts in full (measured
        // 2026-10-13: projected on the grid's way, a sea's progress counted 6 to 9% short and the learned ratio rose
        // to 1.08, 6 to 7 s high on a two-minute swim)
        int[] at = route == null ? null : Replay.waypointsOnPath(route, x, z, n);
        int i0 = 0;
        while (i0 < n - 1) {
            if (r.moves[i0 + 1] != Graph.SWIM || swimStraighten[i0] >= 1.0) { i0++; continue; }
            int j0 = i0;
            while (j0 < n - 1 && r.moves[j0 + 1] == Graph.SWIM && swimStraighten[j0] < 1.0) j0++;
            alongChord(x, z, i0, j0);
            i0 = j0;
        }
        if (at != null) for (int k = 1; k < at.length; k++) if (at[k] - at[k - 1] > 1) alongChord(x, z, at[k - 1], at[k]);
        double beyond = 0;
        // a route that stops short (at the loaded edge, or a search out of time): the straight line on from its end
        if ((r.viaExit || r.partial) && n > 0) beyond = Math.hypot(goalX + 0.5 - x[n - 1], goalZ + 0.5 - z[n - 1]);
        double flat = canSprint ? t.sprint : t.noSprint;
        double line = Double.isFinite(r.lineRate) && r.lineRate > 0 ? r.lineRate : flat;
        return new TimeProfile(x, z, ticks, foot, beyond, flat, line, t.noSprintFactor, canSprint);
    }

    /** Place the points strictly between a and b on the straight line from a to b, each at its share of the way's
     *  length. */
    static void alongChord(double[] x, double[] z, int a, int b) {
        if (b - a < 2) return;
        double[] cum = new double[b - a + 1];
        double total = 0;
        for (int k = a + 1; k <= b; k++) { total += Math.hypot(x[k] - x[k - 1], z[k] - z[k - 1]); cum[k - a] = total; }
        if (total < 1e-9) return;
        double ax = x[a], az = z[a], bx = x[b], bz = z[b];
        for (int k = a + 1; k < b; k++) {
            double f = cum[k - a] / total;
            x[k] = ax + (bx - ax) * f;
            z[k] = az + (bz - az) * f;
        }
    }

    /**
     * For each step of the grid path that swims, how much shorter the straight line is than the grid's way across
     * open water: the grid's way is up to 2.7% longer than the straight line with knight's moves (8.3% on the eight
     * directions alone, where knight's moves don't fit), which the walk swims (layer 2, and since 2026-10-14 the
     * tightened route too). A run of swimming steps that is a shortest way on the grid between its ends (open water),
     * with knight's moves or without, is scaled by the straight line over that length; a run that bends (a channel) is
     * left as it is. Measured 2026-10-13: the sea's planned time ran 5 to 8% high.
     */
    static double[] swimStraighten(PathSearch.Result r, double[] x, double[] z) {
        int n = r.path.length;
        double[] f = new double[Math.max(0, n - 1)];
        java.util.Arrays.fill(f, 1.0);
        int i = 0;
        while (i < n - 1) {
            if (r.moves[i + 1] != Graph.SWIM) { i++; continue; }
            int j = i;
            double len = 0;
            while (j < n - 1 && r.moves[j + 1] == Graph.SWIM) { len += Math.hypot(x[j + 1] - x[j], z[j + 1] - z[j]); j++; }
            double dx = x[j] - x[i], dz = z[j] - z[i], straight = Math.hypot(dx, dz);
            int ix = (int) Math.round(dx), iz = (int) Math.round(dz);
            for (double grid : new double[]{PathSearch.gridLength(ix, iz), PathSearch.octileLength(ix, iz)})
                if (grid > 1e-6 && Math.abs(len - grid) <= 0.01 * grid + 1e-6) {
                    for (int k = i; k < j; k++) f[k] = straight / grid;
                    break;
                }
            i = j;
        }
        return f;
    }

    /** Where the player at (px, pz) is along the path: the segment nearest it (searched round `hint`) and the share of
     *  it done, packed as k + t; -1 if the path is empty. */
    public double locate(double px, double pz, double hint) {
        if (n == 0) return -1;
        if (n == 1) return 0;
        int h = hint < 0 ? 0 : (int) hint;
        int from = Math.max(0, h - 4), to = Math.min(n - 2, h + 48);
        if (hint < 0) { from = 0; to = n - 2; }
        double bestD = Double.MAX_VALUE, best = 0;
        for (int k = from; k <= to; k++) {
            double sx = x[k], sz = z[k], lx = x[k + 1] - sx, lz = z[k + 1] - sz, len2 = lx * lx + lz * lz;
            double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - sx) * lx + (pz - sz) * lz) / len2));
            double d = Math.hypot(px - (sx + lx * t), pz - (sz + lz * t));
            if (d < bestD - 1e-9) { bestD = d; best = k + Math.min(t, 0.999999); }
        }
        return best;
    }

    /** The planned ticks left from a place along the path (as from locate). */
    public double ticksLeft(double at) {
        if (n == 0) return 0;
        int k = (int) Math.floor(at);
        if (k >= n - 1) return 0;
        double t = at - k;
        return ticks[k] - t * (ticks[k] - ticks[k + 1]);
    }

    /** The blocks on foot left from a place along the path. */
    public double footLeft(double at) {
        if (n == 0) return 0;
        int k = (int) Math.floor(at);
        if (k >= n - 1) return 0;
        double t = at - k;
        return footBlocks[k] - t * (footBlocks[k] - footBlocks[k + 1]);
    }

    public int size() {
        return n;
    }
}
