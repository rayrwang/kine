package ai.rrw.kine.walk;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;

/**
 * Layer 2's map of the time left to the goal, in ticks, over the floors of the walkability table near layer 1's route:
 * a corridor of columns within {@link #RADIUS} of the route's grid path, the part of it from a little behind the player
 * to {@link #AHEAD} blocks on. Worked out backward from the corridor's end with layer 1's moves and costs (Dijkstra over
 * the reversed moves), so it holds the cheapest time from every floor in the corridor, not only along the route: off
 * the route, past a corner, across the corridor. The corridor's end gets the route's own cost from there to the goal.
 *
 * <p>Layer 2 reads it at any point (not only floor centres): {@link #at} interpolates between the four nearest columns'
 * floors at the player's height, leaving out a neighbour whose time is lower than one move from here could make it
 * (behind a wall or up a one-way drop, where the time comes from a way round), so the times slope smoothly toward the
 * goal along ways the player can really go. It also gives the way the cheapest moves go from a floor ({@link #aim}) and
 * the best floor in a straight line within reach ({@link #shortcut}), which layer 2's actions are headed by.
 *
 * <p>Built on layer 2's worker over its own snapshot of the table; read on the same thread only (the View is not thread
 * safe).
 */
public final class Field {
    /** Columns within this many blocks (square) of the route's grid path are in the corridor. */
    public static final int RADIUS = 6;
    /** The corridor runs this far along the route past the player (blocks, horizontal), then the route's cost on. */
    public static final double AHEAD = 56.0, BEHIND = 8.0;

    final View v;
    final Costs c;
    /** The plan it was made from, its grid path, and the path indices it covers. */
    public final long planId;
    final long[] path;
    public final int from, to, pathLength;
    /** The goal column, and whether the corridor reaches it (its end is the goal, not a point on the way). */
    final int goalX, goalZ;
    final boolean endsAtGoal;

    private final Long2IntOpenHashMap id = new Long2IntOpenHashMap();
    private final LongOpenHashSet columns = new LongOpenHashSet();
    long[] nodes = new long[0];
    double[] value = new double[0];
    int[] next = new int[0];
    float[] px = new float[0], py = new float[0], pz = new float[0];
    byte[] type = new byte[0];
    int n = 0;
    /** Ticks a block at the fastest speed on foot: the bound used for the interpolation's check of neighbours. */
    final double rate;
    public long buildNanos;
    public int edges;

    private Field(View v, Costs c, long planId, long[] path, int from, int to, int goalX, int goalZ, boolean endsAtGoal) {
        this.v = v; this.c = c; this.planId = planId; this.path = path; this.from = from; this.to = to; this.pathLength = path.length;
        this.goalX = goalX; this.goalZ = goalZ; this.endsAtGoal = endsAtGoal;
        this.rate = c.fastest;
        id.defaultReturnValue(-1);
    }

    /** The path index nearest (x, z) horizontally, searched from `hint` within the path (the player's place on it). */
    public static int nearestIndex(long[] path, double x, double z) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < path.length; i++) {
            double dx = View.nodeX(path[i]) + 0.5 - x, dz = View.nodeZ(path[i]) + 0.5 - z;
            double d = dx * dx + dz * dz;
            if (d < bestD - 1e-9) { bestD = d; best = i; }
        }
        return best;
    }

    /**
     * The field around the grid path of a plan, from the path index nearest (x, z) less BEHIND to AHEAD blocks on.
     * @param reachedGoal whether the path's last floor is in the goal column (not partial, not at the loaded edge)
     */
    public static Field build(View v, Costs c, boolean canSprint, boolean gaps, long planId, long[] path, double x, double z,
                              int goalX, int goalZ, boolean reachedGoal) {
        return build(v, c, canSprint, gaps, false, planId, path, x, z, goalX, goalZ, reachedGoal);
    }

    public static Field build(View v, Costs c, boolean canSprint, boolean gaps, boolean glides, long planId, long[] path,
                              double x, double z, int goalX, int goalZ, boolean reachedGoal) {
        long t0 = System.nanoTime();
        int at = nearestIndex(path, x, z);
        int from = at;
        double back = 0;
        while (from > 0 && back < BEHIND) { back += hdist(path[from - 1], path[from]); from--; }
        int to = at;
        double ahead = 0;
        while (to + 1 < path.length && ahead < AHEAD) { ahead += hdist(path[to], path[to + 1]); to++; }
        boolean endsAtGoal = reachedGoal && to == path.length - 1;
        Field f = new Field(v, c, planId, path, from, to, goalX, goalZ, endsAtGoal);
        Graph g = new Graph(v, c);
        g.canSprint = canSprint;
        g.gaps = gaps;
        g.glides = glides;
        g.goalX = goalX; g.goalZ = goalZ;
        f.make(g, path, from, to);
        f.buildNanos = System.nanoTime() - t0;
        return f;
    }

    private static double hdist(long a, long b) {
        return Math.hypot(View.nodeX(a) - View.nodeX(b), View.nodeZ(a) - View.nodeZ(b));
    }

    private void make(Graph g, long[] path, int from, int to) {
        // the corridor's columns and their floors
        for (int i = from; i <= to; i++) {
            int x = View.nodeX(path[i]), z = View.nodeZ(path[i]);
            for (int dx = -RADIUS; dx <= RADIUS; dx++)
                for (int dz = -RADIUS; dz <= RADIUS; dz++) columns.add(View.key(x + dx, z + dz));
        }
        if (endsAtGoal) columns.add(View.key(goalX, goalZ));
        long[] list = new long[256];
        int cnt = 0;
        for (long key : columns) {
            int x = (int) (key >> 32), z = (int) key;
            ChunkFloors ch = v.chunk(x, z);
            if (ch == null) continue;
            int k = ch.count(x & 15, z & 15);
            for (int i = 0; i < k; i++) {
                if (cnt == list.length) list = Arrays.copyOf(list, cnt * 2);
                list[cnt++] = View.node(x, z, i);
            }
        }
        n = cnt;
        nodes = Arrays.copyOf(list, cnt);
        value = new double[n];
        Arrays.fill(value, Double.POSITIVE_INFINITY);
        next = new int[n];
        Arrays.fill(next, -1);
        px = new float[n]; py = new float[n]; pz = new float[n];
        type = new byte[n];
        for (int i = 0; i < n; i++) {
            long nd = nodes[i];
            id.put(nd, i);
            int x = View.nodeX(nd), z = View.nodeZ(nd);
            ChunkFloors ch = v.chunk(x, z);
            int idx = ch.index(x & 15, z & 15, View.nodeI(nd));
            px[i] = x + 0.5F; pz[i] = z + 0.5F; py[i] = ch.h(idx);
            type[i] = (byte) ch.type(idx);
        }

        // the moves out of every floor, kept where both ends are in the corridor; reversed for the backward search
        int[] fFrom = new int[1024], fTo = new int[1024];
        double[] fCost = new double[1024];
        int m = 0;
        for (int i = 0; i < n; i++) {
            g.expand(nodes[i]);
            for (int e = 0; e < g.n; e++) {
                int j = id.get(g.to[e]);
                if (j < 0) continue;
                if (m == fFrom.length) {
                    fFrom = Arrays.copyOf(fFrom, m * 2); fTo = Arrays.copyOf(fTo, m * 2); fCost = Arrays.copyOf(fCost, m * 2);
                }
                fFrom[m] = i; fTo[m] = j; fCost[m] = g.cost[e]; m++;
            }
        }
        edges = m;
        // reversed adjacency (CSR by target)
        int[] rStart = new int[n + 1];
        for (int e = 0; e < m; e++) rStart[fTo[e] + 1]++;
        for (int i = 0; i < n; i++) rStart[i + 1] += rStart[i];
        int[] rFrom = new int[m];
        double[] rCost = new double[m];
        int[] fill = Arrays.copyOf(rStart, n);
        for (int e = 0; e < m; e++) { int k = fill[fTo[e]]++; rFrom[k] = fFrom[e]; rCost[k] = fCost[e]; }

        // the sources: the corridor's end at the route's cost from there on (or the goal column's floors at 0)
        double tail = endsAtGoal ? 0.0 : routeCostFrom(g, path, to);
        Heap h = new Heap(n);
        int end = id.get(path[to]);
        if (end >= 0) { value[end] = tail; h.push(end, tail); }
        if (endsAtGoal) {
            ChunkFloors ch = v.chunk(goalX, goalZ);
            if (ch != null)
                for (int i = 0; i < ch.count(goalX & 15, goalZ & 15); i++) {
                    int j = id.get(View.node(goalX, goalZ, i));
                    if (j >= 0 && value[j] > 0) { value[j] = 0; h.push(j, 0); }
                }
        }
        while (h.size > 0) {
            int i = h.popId();
            double vi = h.lastKey;
            if (vi > value[i]) continue;
            for (int k = rStart[i]; k < rStart[i + 1]; k++) {
                int j = rFrom[k];
                double nv = vi + rCost[k];
                if (nv < value[j] - 1e-9) { value[j] = nv; next[j] = i; h.push(j, nv); }
            }
        }
    }

    /** The route's cost from path index k to its end, from the moves between its floors. */
    private static double routeCostFrom(Graph g, long[] path, int k) {
        double sum = 0;
        for (int i = k; i + 1 < path.length; i++) {
            g.expand(path[i]);
            double best = Double.NaN;
            for (int e = 0; e < g.n; e++) if (g.to[e] == path[i + 1] && (Double.isNaN(best) || g.cost[e] < best)) best = g.cost[e];
            sum += Double.isNaN(best) ? hdist(path[i], path[i + 1]) * g.c.sprint : best;
        }
        return sum;
    }

    // ---------------------------------------------------------------- reading it

    /** The number of floors in it. */
    public int size() { return n; }

    /** Whether column (x, z) is in the corridor. */
    public boolean covers(int x, int z) {
        return columns.contains(View.key(x, z));
    }

    /** The floor of column (bx, bz) the feet at height y belong to: standing on it (from a block below to a jump up),
     *  or in its water (a swim floor's height is the water's top, the feet up to 3 below it); -1 if none. */
    int floorAt(int bx, int bz, double y, double above) {
        ChunkFloors ch = v.chunk(bx, bz);
        if (ch == null) return -1;
        int lx = bx & 15, lz = bz & 15, k = ch.count(lx, lz);
        int best = -1;
        double bestS = Double.MAX_VALUE;
        for (int i = 0; i < k; i++) {
            int idx = ch.index(lx, lz, i);
            float h = ch.h(idx);
            double s;
            if (ch.type(idx) == ChunkFloors.SWIM) {
                if (h < y - 1.0 || h > y + 3.0) continue;
                s = Math.max(0, y - h) + Math.max(0, (h - 1.0) - y) * 0.5;
            } else {
                if (h < y - 1.0 || h > y + above) continue;
                s = Math.abs(h - y);
            }
            if (s < bestS) { bestS = s; best = id.get(View.node(bx, bz, i)); if (best < 0) best = -2; }
        }
        return best < 0 ? -1 : best;
    }

    /** The floor the player at (x, y, z) is on: its own column's, else the nearest of the columns its box overlaps
     *  (standing on an edge); -1 if none in the corridor. */
    public int nodeAt(double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int own = floorAt(bx, bz, y, 0.6);
        if (own >= 0 && value[own] < Double.POSITIVE_INFINITY) return own;
        int best = -1;
        double bestV = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int cx = bx + dx, cz = bz + dz;
                // the box (0.3 either side) overlaps this column
                double ox = Math.max(cx - x, x - (cx + 1)), oz = Math.max(cz - z, z - (cz + 1));
                if (ox > 0.3 || oz > 0.3) continue;
                int j = floorAt(cx, cz, y, 0.6);
                if (j < 0 || value[j] == Double.POSITIVE_INFINITY) continue;
                if (value[j] < bestV) { bestV = value[j]; best = j; }
            }
        return best >= 0 ? best : own;
    }

    /**
     * The time left from (x, y, z), interpolated between the four nearest columns' floors at this height; NaN where the
     * corridor has no floor for the player. A neighbour more than one move's time below this floor's (it isn't reached
     * from here directly) counts as this floor's own time; a neighbour without a floor at this height (a wall, a pit, a
     * block up) as this floor's time plus a block's walk, so the times rise toward walls and edges.
     */
    public double at(double x, double y, double z) {
        int own = nodeAt(x, y, z);
        if (own < 0) return Double.NaN;
        double f0 = value[own];
        if (f0 == Double.POSITIVE_INFINITY) return Double.NaN;
        if (type[own] == ChunkFloors.CLIMB) {
            // on a ladder the floors are a block apart: slope the time between this rung and the next one up, so a
            // climb shows progress within the block
            int up = floorAt((int) Math.floor(px[own]), (int) Math.floor(pz[own]), py[own] + 1.0, 0.05);
            if (up >= 0 && up != own && value[up] < f0 && Math.abs(py[up] - py[own] - 1.0) < 0.05)
                f0 = f0 + (value[up] - f0) * Math.max(0.0, Math.min(1.0, y - py[own]));
        }
        double gx = x - 0.5, gz = z - 0.5;
        int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
        double tx = gx - ix, tz = gz - iz;
        double ox = px[own] - 0.5, oz = pz[own] - 0.5;
        double sum = 0;
        for (int a = 0; a < 2; a++)
            for (int b = 0; b < 2; b++) {
                int cx = ix + a, cz = iz + b;
                double w = (a == 0 ? 1 - tx : tx) * (b == 0 ? 1 - tz : tz);
                if (w <= 0) continue;
                double fv;
                if (cx == (int) ox && cz == (int) oz) fv = f0;
                else {
                    int j = floorAt(cx, cz, y, 1.3);
                    if (j < 0 || value[j] == Double.POSITIVE_INFINITY) fv = f0 + c.sprint;
                    else {
                        fv = value[j];
                        if (py[j] > y + 0.6) fv += c.jumpPenalty;   // a block up: a jump
                        double reach = Math.hypot(cx - ox, cz - oz) * rate + 1.0;
                        if (fv < f0 - reach) fv = f0;
                    }
                }
                sum += w * fv;
            }
        return sum;
    }

    /** The point the cheapest moves from floor i lead to, at least `dist` blocks on (at most 6 columns on, a climb in
     *  one column counting as none): {x, y, z}, or null at the end. */
    public double[] aim(int i, double x, double z, double dist) {
        int j = i, steps = 0, guard = 0;
        while (steps < 6 && guard++ < 64) {
            int k = next[j];
            if (k < 0) break;
            // a move within the column (up or down a ladder) doesn't count: aim at where the climb leads off it
            if (px[k] != px[j] || pz[k] != pz[j]) steps++;
            j = k;
            if (Math.hypot(px[j] - x, pz[j] - z) >= dist) break;
        }
        if (j == i && next[i] < 0) {
            if (endsAtGoal) return new double[]{goalX + 0.5, py[i], goalZ + 0.5};
            return null;
        }
        return new double[]{px[j], py[j], pz[j]};
    }

    /** The floor within `radius` (horizontal) of (x, z), not below `y` by more than 3 nor above by more than 1.3, whose
     *  time plus a straight run to it at the fastest speed is least: where a straight line (a jump over a gap, a run
     *  across a corner) would do best; -1 if none. */
    public int shortcut(double x, double y, double z, double radius) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), r = (int) Math.ceil(radius);
        int best = -1;
        double bestV = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int cx = bx + dx, cz = bz + dz;
                double d = Math.hypot(cx + 0.5 - x, cz + 0.5 - z);
                if (d > radius) continue;
                ChunkFloors ch = v.chunk(cx, cz);
                if (ch == null) continue;
                int k = ch.count(cx & 15, cz & 15);
                for (int i = 0; i < k; i++) {
                    int j = id.get(View.node(cx, cz, i));
                    if (j < 0 || value[j] == Double.POSITIVE_INFINITY) continue;
                    if (py[j] < y - 3.0 || py[j] > y + 1.3) continue;
                    double s = value[j] + d * rate;
                    if (s < bestV) { bestV = s; best = j; }
                }
            }
        return best;
    }

    /** The highest floor in the corridor under (x, y, z), below the feet: where a glide over this column would come
     *  down; -1 if none. */
    public int below(double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        ChunkFloors ch = v.chunk(bx, bz);
        if (ch == null) return -1;
        int lx = bx & 15, lz = bz & 15, k = ch.count(lx, lz), best = -1;
        float bestH = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < k; i++) {
            float h = ch.h(ch.index(lx, lz, i));
            if (h > y + 1e-4 || h <= bestH) continue;
            int j = id.get(View.node(bx, bz, i));
            if (j < 0 || value[j] == Double.POSITIVE_INFINITY) continue;
            bestH = h;
            best = j;
        }
        return best;
    }

    public double value(int i) { return value[i]; }
    public double x(int i) { return px[i]; }
    public double y(int i) { return py[i]; }
    public double z(int i) { return pz[i]; }
    public boolean swim(int i) { return type[i] == ChunkFloors.SWIM; }

    /** A binary heap of (key, id). */
    private static final class Heap {
        double[] key;
        int[] ids;
        int size;
        double lastKey;

        Heap(int cap) { key = new double[Math.max(16, cap)]; ids = new int[Math.max(16, cap)]; }

        void push(int i, double k) {
            if (size == key.length) { key = Arrays.copyOf(key, size * 2); ids = Arrays.copyOf(ids, size * 2); }
            int at = size++;
            while (at > 0) {
                int p = (at - 1) >> 1;
                if (key[p] <= k) break;
                key[at] = key[p]; ids[at] = ids[p]; at = p;
            }
            key[at] = k; ids[at] = i;
        }

        int popId() {
            int top = ids[0];
            lastKey = key[0];
            size--;
            if (size > 0) {
                double k = key[size];
                int i = ids[size], at = 0;
                while (true) {
                    int l = 2 * at + 1;
                    if (l >= size) break;
                    int r = l + 1, m = r < size && key[r] < key[l] ? r : l;
                    if (key[m] >= k) break;
                    key[at] = key[m]; ids[at] = ids[m]; at = m;
                }
                key[at] = k; ids[at] = i;
            }
            return top;
        }
    }
}
