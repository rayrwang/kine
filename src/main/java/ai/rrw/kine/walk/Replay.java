package ai.rrw.kine.walk;

import ai.rrw.kine.hud.HudColors;

import java.util.Arrays;

/**
 * What the search display draws of one plan, prepared on the search's worker from the search's record (so the game's
 * thread only uploads it): the search tree as one segment per floor seen, from its parent, in two orders (the order
 * floors were first seen, drawn bright: the frontier; and the order they were expanded, drawn dim over them: the
 * closed set), coloured by cost so far; the grid path; the tightened route; and the straight line on past the loaded
 * area. A replay of n expansions shows, at expansion e, the segments seen by then, the expanded ones dim.
 */
final class Replay {
    /** At most this many tree segments are drawn (the first seen; a bigger search shows its first part). */
    static final int MAX_SEGMENTS = 60_000;
    static final float TREE_W = 2.5F, GRID_W = 2.0F, ROUTE_W = 3.5F, BEYOND_W = 1.5F;

    final LineSet.Builder bright, dim, grid, route, beyond;
    /** For each segment in bright's order, when it was seen; in dim's order, when it was expanded (both sorted). */
    final int[] seenAt, closedAt;
    final int expansions;
    final double ox, oy, oz;

    Replay(PathSearch.Result r, Route rt, RailPath rails) {
        ox = rt.x[0]; oy = rt.y[0]; oz = rt.z[0];
        bright = new LineSet.Builder(ox, oy, oz);
        dim = new LineSet.Builder(ox, oy, oz);
        grid = new LineSet.Builder(ox, oy, oz);
        route = new LineSet.Builder(ox, oy, oz);
        beyond = new LineSet.Builder(ox, oy, oz);
        PathSearch.Tree t = r.tree;
        int n = 0;
        int[] seg = new int[t == null ? 0 : t.n];
        if (t != null)
            for (int i = 0; i < t.n; i++)
                if (t.parent[i] >= 0 && !Float.isNaN(t.x[i]) && !Float.isNaN(t.x[t.parent[i]])) seg[n++] = i;
        // the first MAX_SEGMENTS seen (ids are in the order first seen)
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
        // the dim copy in the order expanded (never expanded last)
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
        expansions = t == null ? 0 : t.expansions;

        // the grid path, thin white; the route, magenta (layer 2's chosen run is gold); past the loaded area, a thin
        // line to the goal
        for (int k = 1; k < r.path.length; k++) {
            long a = r.path[k - 1], b = r.path[k];
            grid.add(View.nodeX(a) + 0.5, height(r, k - 1, rt) + 0.06, View.nodeZ(a) + 0.5,
                     View.nodeX(b) + 0.5, height(r, k, rt) + 0.06, View.nodeZ(b) + 0.5, 0xDCF0F0F0, GRID_W);
        }
        // the route line along the rails' smooth centreline, at its height, so it runs between them
        for (int k = 1; k < rails.n; k++) {
            if (Math.abs(rails.y[k] - rails.y[k - 1]) > 1.2) continue;
            route.add(rails.x[k - 1], rails.y[k - 1] + 0.04, rails.z[k - 1], rails.x[k], rails.y[k] + 0.04, rails.z[k], HudColors.MAGENTA, ROUTE_W);
        }
        if (rt.viaExit) {
            int e = rt.n - 1;
            beyond.add(rt.x[e], rt.y[e] + 0.08, rt.z[e], rt.goalX + 0.5, rt.y[e] + 0.08, rt.goalZ + 0.5, 0xB4A0C8FF, BEYOND_W);
        }
    }

    private float[] gridY;

    /** The grid node's floor height (looked up from the tree when recorded, else the route's start). */
    private double height(PathSearch.Result r, int k, Route rt) {
        if (gridY == null) gridY = r.pathY;
        return gridY != null && k < gridY.length ? gridY[k] : rt.y[0];
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
            int m = (lo + hi) >>> 1;
            if (a[m] <= v) lo = m + 1; else hi = m;
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
