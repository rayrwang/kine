package ai.rrw.kine.walk;

import ai.rrw.kine.hud.HudColors;

import java.util.Arrays;

/**
 * What the search display draws of one plan, prepared on the search's worker from the search's record (so the game's
 * thread only uploads it). Two stages (RW, 2026-10-13, agreed: "one color ... for the search, then magnetia for the
 * tighneing stage until it setltes into the final magneta"):
 * <ol>
 *   <li>the search, in orange: the feeler, the best way so far (in each step of the replay, the floor expanded then
 *       that the search rates nearest the goal, traced back to the player), which grows toward the goal, probes and
 *       pulls back in a maze; and the area searched so far, outlined faintly on the ground (the cells, kept here,
 *       give the outline at any point of the replay);</li>
 *   <li>the tightening, in magenta: the grid path as a staircase pulled taut a leg at a time from the player outward,
 *       into the route (keyframes of the polyline).</li>
 * </ol>
 * Then the route stays (magenta, the line along the rails' centreline) with the line on past the loaded area. The
 * old display (every floor seen, one segment from its parent, coloured by cost so far) is kept for the detailed
 * setting: built only when it is on.
 */
final class Replay {
    /** At most this many tree segments are drawn in the detailed display (the first seen). */
    static final int MAX_SEGMENTS = 60_000;
    static final float TREE_W = 2.5F, GRID_W = 2.0F, ROUTE_W = 7.0F, BEYOND_W = 1.5F, FEEL_W = 4.0F, TAUT_W = 7.0F;
    /** The feeler's steps over the replay, and the tightening's keyframes. */
    static final int FEEL_STEPS = 72, TAUT_STEPS = 24;
    /** The search's colour (red is kine's warning colour, gold layer 2's chosen run, magenta the route, cyan the walk's
     *  indicators, blue the rails, green the pillars). */
    static final int ORANGE = 0xFFFF8C1A;
    /** Areas larger than this many columns (cells) get no outline. */
    static final int MAX_CELLS = 4_000_000;

    // the detailed display (empty unless built)
    final LineSet.Builder bright, dim;
    /** For each segment in bright's order, when it was seen; in dim's order, when it was expanded (both sorted). */
    final int[] seenAt, closedAt;

    final LineSet.Builder grid, route, beyond;
    final int expansions;
    final double ox, oy, oz;

    /** The feeler: step k (0 to steps) is feel's segments feelFrom[k] .. + feelCount[k]; the last is the grid path. */
    final LineSet.Builder feel;
    final int[] feelFrom, feelCount;
    final int feelSteps;

    /** The searched area's columns: a grid from (cellX0, cellZ0), cellW by cellH, each the expansion at which its first
     *  floor was expanded (Integer.MAX_VALUE if never) and that floor's height; the expanded ones in that order. Null
     *  when there is no record or the area is too large. */
    final int cellX0, cellZ0, cellW, cellH;
    final int[] cellAt, cellOrder, cellOrderAt;
    final float[] cellY;

    /** The tightening: keyframe k (0 to TAUT_STEPS) is taut's segments tautFrom[k] .. + tautCount[k]; 0 is the grid
     *  path's staircase, the last the route's straight legs. */
    final LineSet.Builder taut;
    final int[] tautFrom, tautCount;

    Replay(PathSearch.Result r, Route rt, RailPath rails) {
        this(r, rt, rails, true);
    }

    Replay(PathSearch.Result r, Route rt, RailPath rails, boolean detailed) {
        ox = rt.x[0]; oy = rt.y[0]; oz = rt.z[0];
        bright = new LineSet.Builder(ox, oy, oz);
        dim = new LineSet.Builder(ox, oy, oz);
        grid = new LineSet.Builder(ox, oy, oz);
        route = new LineSet.Builder(ox, oy, oz);
        beyond = new LineSet.Builder(ox, oy, oz);
        feel = new LineSet.Builder(ox, oy, oz);
        taut = new LineSet.Builder(ox, oy, oz);
        PathSearch.Tree t = r.tree;
        expansions = t == null ? 0 : t.expansions;

        // the detailed display: every floor seen, from its parent
        int n = 0;
        int[] seg = new int[t == null || !detailed ? 0 : t.n];
        if (t != null && detailed)
            for (int i = 0; i < t.n; i++)
                if (t.parent[i] >= 0 && !Float.isNaN(t.x[i]) && !Float.isNaN(t.x[t.parent[i]])) seg[n++] = i;
        n = Math.min(n, MAX_SEGMENTS);
        float gMax = 1e-3F;
        for (int k = 0; k < n; k++) gMax = Math.max(gMax, t.g[seg[k]]);
        seenAt = new int[n];
        for (int k = 0; k < n; k++) {
            int i = seg[k], p = t.parent[i];
            float c = t.g[i] / gMax;
            bright.add(t.x[p], t.y[p] + 0.04, t.z[p], t.x[i], t.y[i] + 0.04, t.z[i], ramp(c, 1.0F, 255), TREE_W);
            seenAt[k] = t.born[i];
        }
        Integer[] order = new Integer[n];
        for (int k = 0; k < n; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> Integer.compare(t.closed[seg[a]], t.closed[seg[b]]));
        closedAt = new int[n];
        for (int j = 0; j < n; j++) {
            int k = order[j], i = seg[k], p = t.parent[i];
            float c = t.g[i] / gMax;
            dim.add(t.x[p], t.y[p] + 0.04, t.z[p], t.x[i], t.y[i] + 0.04, t.z[i], ramp(c, 0.55F, 170), TREE_W);
            closedAt[j] = t.closed[i];
        }

        // the grid path, thin white (the detailed display); the route, magenta (layer 2's chosen run is gold); past the
        // loaded area, a thin line to the goal
        int m = r.path.length;
        double[] px = new double[m], py = new double[m], pz = new double[m];
        for (int k = 0; k < m; k++) {
            px[k] = View.nodeX(r.path[k]) + 0.5;
            pz[k] = View.nodeZ(r.path[k]) + 0.5;
            py[k] = r.pathY != null && k < r.pathY.length ? r.pathY[k] : rt.y[0];
        }
        for (int k = 1; k < m; k++)
            grid.add(px[k - 1], py[k - 1] + 0.06, pz[k - 1], px[k], py[k] + 0.06, pz[k], 0xDCF0F0F0, GRID_W);
        for (int k = 1; k < rails.n; k++) {
            if (Math.abs(rails.y[k] - rails.y[k - 1]) > 1.2) continue;
            route.add(rails.x[k - 1], rails.y[k - 1] + 0.04, rails.z[k - 1], rails.x[k], rails.y[k] + 0.04, rails.z[k], HudColors.MAGENTA, ROUTE_W);
        }
        if (rt.viaExit) {
            int e = rt.n - 1;
            beyond.add(rt.x[e], rt.y[e] + 0.08, rt.z[e], rt.goalX + 0.5, rt.y[e] + 0.08, rt.goalZ + 0.5, 0xB4A0C8FF, BEYOND_W);
        }

        // the feeler
        feelSteps = t == null ? 0 : Math.min(FEEL_STEPS, Math.max(0, expansions));
        feelFrom = new int[feelSteps + 1];
        feelCount = new int[feelSteps + 1];
        if (feelSteps > 0) {
            int[] best = new int[feelSteps];
            Arrays.fill(best, -1);
            for (int i = 0; i < t.n; i++) {
                int c = t.closed[i];
                if (c == Integer.MAX_VALUE || c >= expansions || Float.isNaN(t.x[i])) continue;
                int w = (int) ((long) c * feelSteps / expansions);
                if (best[w] < 0 || t.h[i] < t.h[best[w]]) best[w] = i;
            }
            int last = 0;   // the start
            double[] xyz = new double[3 * 64];
            for (int w = 0; w < feelSteps; w++) {
                if (best[w] >= 0) last = best[w];
                int len = 0;
                for (int i = last; i >= 0; i = t.parent[i]) {
                    if (Float.isNaN(t.x[i])) continue;
                    if (3 * len + 3 > xyz.length) xyz = Arrays.copyOf(xyz, xyz.length * 2);
                    xyz[3 * len] = t.x[i]; xyz[3 * len + 1] = t.y[i] + 0.12; xyz[3 * len + 2] = t.z[i];
                    len++;
                }
                feelFrom[w] = feel.n;
                feel.polyline(xyz, len, ORANGE, FEEL_W);
                feelCount[w] = feel.n - feelFrom[w];
            }
        }
        feelFrom[feelSteps] = feel.n;
        for (int k = 1; k < m; k++) feel.add(px[k - 1], py[k - 1] + 0.12, pz[k - 1], px[k], py[k] + 0.12, pz[k], ORANGE, FEEL_W);
        feelCount[feelSteps] = feel.n - feelFrom[feelSteps];

        // the searched area's columns
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE, cells = 0;
        if (t != null)
            for (int i = 0; i < t.n; i++) {
                if (t.closed[i] == Integer.MAX_VALUE || Float.isNaN(t.x[i])) continue;
                int x = (int) Math.floor(t.x[i]), z = (int) Math.floor(t.z[i]);
                x0 = Math.min(x0, x); z0 = Math.min(z0, z); x1 = Math.max(x1, x); z1 = Math.max(z1, z);
                cells++;
            }
        long area = cells == 0 ? 0 : (long) (x1 - x0 + 1) * (z1 - z0 + 1);
        if (cells > 0 && area <= MAX_CELLS) {
            cellX0 = x0; cellZ0 = z0; cellW = x1 - x0 + 1; cellH = z1 - z0 + 1;
            cellAt = new int[cellW * cellH];
            cellY = new float[cellW * cellH];
            Arrays.fill(cellAt, Integer.MAX_VALUE);
            int count = 0;
            for (int i = 0; i < t.n; i++) {
                int c = t.closed[i];
                if (c == Integer.MAX_VALUE || Float.isNaN(t.x[i])) continue;
                int idx = ((int) Math.floor(t.z[i]) - z0) * cellW + ((int) Math.floor(t.x[i]) - x0);
                if (cellAt[idx] == Integer.MAX_VALUE) count++;
                if (c < cellAt[idx]) { cellAt[idx] = c; cellY[idx] = t.y[i]; }
            }
            long[] packed = new long[count];
            int k = 0;
            for (int idx = 0; idx < cellAt.length; idx++)
                if (cellAt[idx] != Integer.MAX_VALUE) packed[k++] = ((long) cellAt[idx] << 32) | idx;
            Arrays.sort(packed);
            cellOrder = new int[count];
            cellOrderAt = new int[count];
            for (int j = 0; j < count; j++) { cellOrder[j] = (int) packed[j]; cellOrderAt[j] = (int) (packed[j] >>> 32); }
        } else {
            cellX0 = cellZ0 = cellW = cellH = 0;
            cellAt = cellOrder = cellOrderAt = null;
            cellY = null;
        }

        // the tightening: the route's waypoints are floors of the grid path; each grid floor between two of them moves
        // onto the straight leg between them, the legs one after another from the player outward
        tautFrom = new int[TAUT_STEPS + 1];
        tautCount = new int[TAUT_STEPS + 1];
        int[] at = waypointsOnPath(rt, px, pz, m);
        double[] xyz = new double[3 * Math.max(2, m)];
        for (int f = 0; f <= TAUT_STEPS; f++) {
            tautFrom[f] = taut.n;
            double u = (double) f / TAUT_STEPS;
            if (at == null) {
                // not a subsequence (no tightening to show leg by leg): the staircase, then the route
                if (f < TAUT_STEPS / 2) for (int k = 1; k < m; k++) taut.add(px[k - 1], py[k - 1] + 0.1, pz[k - 1], px[k], py[k] + 0.1, pz[k], HudColors.MAGENTA, TAUT_W);
                else for (int k = 1; k < rt.n; k++) taut.add(rt.x[k - 1], rt.y[k - 1] + 0.1, rt.z[k - 1], rt.x[k], rt.y[k] + 0.1, rt.z[k], HudColors.MAGENTA, TAUT_W);
            } else {
                int legs = rt.n - 1;
                double overlap = Math.min(3.0, Math.max(1.0, legs));
                for (int i = 0; i < m; i++) {
                    double qx = px[i], qy = py[i], qz = pz[i];
                    int leg = legOf(at, i);
                    if (leg > 0) {
                        double lam = smooth((u * (legs + overlap) - (leg - 1)) / overlap);
                        double ax = rt.x[leg - 1], ay = rt.y[leg - 1], az = rt.z[leg - 1];
                        double bx = rt.x[leg], by = rt.y[leg], bz = rt.z[leg];
                        double lx = bx - ax, lz = bz - az, len2 = lx * lx + lz * lz;
                        double s = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((qx - ax) * lx + (qz - az) * lz) / len2));
                        double tx = ax + lx * s, ty = ay + (by - ay) * s, tz = az + lz * s;
                        qx += (tx - qx) * lam; qy += (ty - qy) * lam; qz += (tz - qz) * lam;
                    }
                    xyz[3 * i] = qx; xyz[3 * i + 1] = qy + 0.1; xyz[3 * i + 2] = qz;
                }
                taut.polyline(xyz, m, HudColors.MAGENTA, TAUT_W);
            }
            tautCount[f] = taut.n - tautFrom[f];
        }
    }

    /** For each of the route's waypoints, its index along the grid path (in order), or null if they aren't floors of
     *  it in order. */
    static int[] waypointsOnPath(Route rt, double[] px, double[] pz, int m) {
        if (m == 0 || rt.n == 0) return null;
        int[] at = new int[rt.n];
        int j = 0;
        for (int k = 0; k < rt.n; k++) {
            while (j < m && (Math.abs(px[j] - rt.x[k]) > 1e-3 || Math.abs(pz[j] - rt.z[k]) > 1e-3)) j++;
            if (j >= m) return null;
            at[k] = j;
        }
        return at;
    }

    /** The route leg (1 to n - 1) a grid floor lies on, by the waypoints' indices; 0 at or before the first. */
    private static int legOf(int[] at, int i) {
        for (int k = 1; k < at.length; k++) if (i <= at[k]) return i <= at[k - 1] ? 0 : k;
        return 0;
    }

    private static double smooth(double v) {
        v = Math.max(0, Math.min(1, v));
        return v * v * (3 - 2 * v);
    }

    /** The feeler's step at a share of the replay (0 to 1); the last step (the grid path) at 1. */
    int feelStep(double share) {
        if (share >= 1) return feelSteps;
        return Math.min(feelSteps, (int) Math.floor(share * feelSteps));
    }

    /** The tightening's keyframe at a share of its time (0 to 1). */
    int tautStep(double share) {
        return Math.max(0, Math.min(TAUT_STEPS, (int) Math.floor(share * TAUT_STEPS + 1e-9)));
    }

    /** Expanded columns by expansion e (a prefix of cellOrder). */
    int cellsBy(int e) {
        return cellOrderAt == null ? 0 : upper(cellOrderAt, e - 1);
    }

    /** Segments seen by expansion e (bright), and expanded by then (dim). */
    int seenBy(int e) {
        return upper(seenAt, e);
    }

    int closedBy(int e) {
        return upper(closedAt, e - 1);
    }

    /** How many sorted values are at most v. */
    private static int upper(int[] a, int v) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] <= v) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Cost so far, 0 to 1, as a colour: cyan near the start, violet, then rose far out; scaled in brightness. */
    static int ramp(float t, float bright, int alpha) {
        t = Math.max(0, Math.min(1, t));
        float r, g, b;
        if (t < 0.5F) {
            float u = t / 0.5F;
            r = 60 + (150 - 60) * u; g = 230 + (110 - 230) * u; b = 255;
        } else {
            float u = (t - 0.5F) / 0.5F;
            r = 150 + (255 - 150) * u; g = 110 + (90 - 110) * u; b = 255 + (170 - 255) * u;
        }
        int ri = (int) (r * bright), gi = (int) (g * bright), bi = (int) (b * bright);
        return (alpha << 24) | (ri << 16) | (gi << 8) | bi;
    }
}
