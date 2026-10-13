package ai.rrw.kine.walk;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;

/**
 * Layer 1's search: A* over the walkability table, forward from the player, with the moves and costs of {@link Graph}
 * and the estimate of {@link Costs#estimate} (horizontal distance at the fastest foot speed, or the climb to the goal
 * column's lowest floor at the fastest climb, whichever is larger). The goal is a column (X and Z); the first floor
 * reached in it ends the search. A goal beyond the loaded area is reached through an EXIT move from the loaded edge.
 *
 * <p>Weighted: f = g + w * h. With w = 1 the path is the cheapest on this graph; a larger w finds one sooner, costing
 * at most w times the cheapest. The planner runs passes with falling w while its time lasts (anytime). Floors are not
 * reopened once expanded. Every expansion is recorded (which node, from which parent, when it was first seen and when
 * expanded) so the search can be replayed in the world afterwards.
 */
public final class PathSearch {
    /** What a search found. */
    public static final class Result {
        public boolean found;        // reached the goal column (or the loaded edge toward it: viaExit)
        public boolean viaExit;      // the path ends at the loaded area's edge; the rest is a straight line
        public boolean partial;      // out of time or nodes: the path goes to the expanded floor nearest the goal
        public boolean exhausted;    // every reachable floor expanded without reaching the goal
        public long[] path = new long[0];
        public byte[] moves = new byte[0];
        /** The path's floor heights. */
        public float[] pathY = new float[0];
        public double cost;          // of the path, ticks (to the edge for viaExit; plus the straight line in total)
        public double totalCost;
        public int expanded, generated;
        public long nanos;
        public double weight;
        public String reason = "";
        public Tree tree;
        public long sweeps;
    }

    /** The search as it ran, for the replay: per seen floor its position, its parent's index, the cost so far, and the
     *  expansion counts at which it was first seen and at which it was expanded (Integer.MAX_VALUE if never). */
    public static final class Tree {
        public int n;
        public float[] x, y, z, g;
        public int[] parent, born, closed;
        public int expansions;
    }

    private final View v;
    private final Costs c;
    private final Graph graph;

    // per seen floor
    private final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
    private long[] keys = new long[1024];
    private double[] g = new double[1024];
    private double[] hEst = new double[1024];
    private int[] parent = new int[1024];
    private byte[] moveIn = new byte[1024];
    private int[] born = new int[1024], closedAt = new int[1024];
    private float[] fy = new float[1024];
    private int count = 0;

    // the open list: a binary heap of (f, id, g at push); stale entries are skipped
    private double[] hf = new double[1024], hg = new double[1024];
    private int[] hid = new int[1024];
    private int hn = 0;

    private double goalMinH = Double.NaN;

    public PathSearch(View v, Costs c) {
        this.v = v;
        this.c = c;
        this.graph = new Graph(v, c);
        index.defaultReturnValue(-1);
    }

    /** Offer glides off drops (layer 2 with elytra hops on and a glider worn). */
    public PathSearch glides(boolean b) {
        graph.glides = b;
        return this;
    }

    /** Offer jumps over gaps (layer 2 only). */
    public PathSearch gaps(boolean b) {
        graph.gaps = b;
        return this;
    }

    public PathSearch canSprint(boolean b) {
        graph.canSprint = b;
        return this;
    }

    /**
     * Search from `start` to column (gx, gz) with weight w, stopping at the deadline (System.nanoTime) or after
     * maxNodes floors seen.
     */
    public Result run(long start, int gx, int gz, double w, long deadline, int maxNodes, boolean recordTree) {
        long t0 = System.nanoTime();
        reset();
        Result r = new Result();
        r.weight = w;
        graph.goalX = gx; graph.goalZ = gz;
        graph.goalUnknown = !v.known(gx, gz);
        goalMinH = Double.NaN;
        if (!graph.goalUnknown) {
            ChunkFloors gc = v.chunk(gx, gz);
            int n = gc.count(gx & 15, gz & 15);
            for (int i = 0; i < n; i++) {
                double h = gc.h(gc.index(gx & 15, gz & 15, i));
                goalMinH = Double.isNaN(goalMinH) ? h : Math.min(goalMinH, h);
            }
            if (n == 0) {
                r.reason = "nowhere to stand at the destination";
                r.exhausted = true;
                r.nanos = System.nanoTime() - t0;
                return r;
            }
        }
        int s = see(start, -1, 0, Graph.START, 0);
        push(s, w * hEst[s], 0);
        int expansions = 0;
        int goalId = -1;
        double bestH = Double.MAX_VALUE;
        int bestId = s;
        boolean exitReached = false;
        while (hn > 0) {
            int id = hid[0];
            double gg = hg[0];
            pop();
            if (closedAt[id] != Integer.MAX_VALUE || gg != g[id]) continue;
            if (keys[id] == Long.MIN_VALUE) { goalId = id; exitReached = true; break; }
            closedAt[id] = expansions++;
            long key = keys[id];
            if (View.nodeX(key) == gx && View.nodeZ(key) == gz) { goalId = id; break; }
            if (hEst[id] < bestH) { bestH = hEst[id]; bestId = id; }
            if ((expansions & 255) == 0 && System.nanoTime() > deadline) { r.reason = "out of time"; break; }
            if (count >= maxNodes) { r.reason = "out of nodes"; break; }
            graph.expand(key);
            for (int k = 0; k < graph.n; k++) {
                long to = graph.to[k];
                double ng = g[id] + graph.cost[k];
                int tid = index.get(to);
                if (tid >= 0) {
                    if (closedAt[tid] != Integer.MAX_VALUE || ng >= g[tid] - 1e-9) continue;
                    g[tid] = ng; parent[tid] = id; moveIn[tid] = graph.move[k];
                } else {
                    tid = see(to, id, ng, graph.move[k], expansions);
                }
                push(tid, ng + w * hEst[tid], ng);
            }
        }
        r.expanded = expansions;
        r.generated = count;
        r.sweeps = graph.sweeps;
        if (goalId >= 0) {
            r.found = true;
            r.viaExit = exitReached;
            int end = exitReached ? parent[goalId] : goalId;
            fill(r, end);
            r.totalCost = g[goalId];
            r.cost = g[end];
        } else if (hn == 0 && r.reason.isEmpty()) {
            r.exhausted = true;
            r.reason = "no route: every reachable place searched (" + expansions + ")";
        } else {
            r.partial = true;
            fill(r, bestId);
            r.cost = g[bestId];
            r.totalCost = Double.NaN;
        }
        if (recordTree) r.tree = tree(expansions);
        r.nanos = System.nanoTime() - t0;
        return r;
    }

    /**
     * The floors from which the goal column can be reached, found backward from it (a floor counts when one of its own
     * moves leads to one already found), if there are at most `limit` of them; else -1. A small finite set that the
     * forward search didn't reach means the destination is closed off. Each candidate's moves are worked out once.
     */
    public int pocketSize(int gx, int gz, int limit) {
        ChunkFloors gc = v.chunk(gx, gz);
        if (gc == null) return -1;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet found = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<long[]> moves = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        int n = gc.count(gx & 15, gz & 15);
        for (int i = 0; i < n; i++) { long k = View.node(gx, gz, i); found.add(k); queue.add(k); }
        graph.goalUnknown = false;
        while (!queue.isEmpty()) {
            long m = queue.poll();
            int mx = View.nodeX(m), mz = View.nodeZ(m);
            for (int d = -1; d < 16; d++) {
                int cx = d < 0 ? mx : mx - Graph.DX[d], cz = d < 0 ? mz : mz - Graph.DZ[d];
                ChunkFloors c = v.chunk(cx, cz);
                if (c == null) return -1;   // reaches the unknown: not closed off as far as anyone knows
                int cn = c.count(cx & 15, cz & 15);
                for (int j = 0; j < cn; j++) {
                    long cand = View.node(cx, cz, j);
                    if (found.contains(cand)) continue;
                    long[] out = moves.get(cand);
                    if (out == null) {
                        graph.expand(cand);
                        out = java.util.Arrays.copyOf(graph.to, graph.n);
                        moves.put(cand, out);
                    }
                    for (long t : out)
                        if (t == m) {
                            found.add(cand);
                            queue.add(cand);
                            if (found.size() > limit) return -1;
                            break;
                        }
                }
            }
        }
        return found.size();
    }

    private void fill(Result r, int end) {
        int len = 0;
        for (int i = end; i >= 0; i = parent[i]) len++;
        r.path = new long[len];
        r.moves = new byte[len];
        r.pathY = new float[len];
        int k = len - 1;
        for (int i = end; i >= 0; i = parent[i]) { r.path[k] = keys[i]; r.moves[k] = moveIn[i]; r.pathY[k] = fy[i]; k--; }
    }

    private Tree tree(int expansions) {
        Tree t = new Tree();
        t.n = count;
        t.expansions = expansions;
        t.x = new float[count]; t.y = new float[count]; t.z = new float[count]; t.g = new float[count];
        t.parent = Arrays.copyOf(parent, count);
        t.born = Arrays.copyOf(born, count);
        t.closed = Arrays.copyOf(closedAt, count);
        for (int i = 0; i < count; i++) {
            long k = keys[i];
            if (k == Long.MIN_VALUE) { t.x[i] = Float.NaN; continue; }
            t.x[i] = View.nodeX(k) + 0.5F;
            t.z[i] = View.nodeZ(k) + 0.5F;
            t.y[i] = fy[i];
            t.g[i] = (float) g[i];
        }
        return t;
    }

    /** A newly seen floor. */
    private int see(long key, int par, double gg, int mv, int expansions) {
        if (count == keys.length) grow();
        int id = count++;
        keys[id] = key;
        g[id] = gg;
        parent[id] = par;
        moveIn[id] = (byte) mv;
        born[id] = expansions;
        closedAt[id] = Integer.MAX_VALUE;
        if (key == Long.MIN_VALUE) {
            hEst[id] = 0;
            fy[id] = Float.NaN;
        } else {
            int x = View.nodeX(key), z = View.nodeZ(key);
            int idx = v.index(key);
            ChunkFloors cf = v.chunk(x, z);
            double h = cf.h(idx);
            fy[id] = (float) h;
            double dx = x - graph.goalX, dz = z - graph.goalZ;
            double climb = Double.isNaN(goalMinH) ? 0 : Math.max(0, goalMinH - h);
            hEst[id] = c.estimate(Math.sqrt(dx * dx + dz * dz), climb);
        }
        index.put(key, id);
        return id;
    }

    private void grow() {
        int n = keys.length * 2;
        keys = Arrays.copyOf(keys, n);
        g = Arrays.copyOf(g, n);
        hEst = Arrays.copyOf(hEst, n);
        parent = Arrays.copyOf(parent, n);
        moveIn = Arrays.copyOf(moveIn, n);
        born = Arrays.copyOf(born, n);
        closedAt = Arrays.copyOf(closedAt, n);
        fy = Arrays.copyOf(fy, n);
    }

    private void reset() {
        index.clear();
        count = 0;
        hn = 0;
        graph.sweeps = 0;
    }

    // ---------------------------------------------------------------- heap

    private void push(int id, double f, double gg) {
        if (hn == hf.length) {
            hf = Arrays.copyOf(hf, hn * 2);
            hg = Arrays.copyOf(hg, hn * 2);
            hid = Arrays.copyOf(hid, hn * 2);
        }
        int i = hn++;
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (!less(f, gg, hf[p], hg[p])) break;
            hf[i] = hf[p]; hg[i] = hg[p]; hid[i] = hid[p];
            i = p;
        }
        hf[i] = f; hg[i] = gg; hid[i] = id;
    }

    // ties on f go to the larger g (deeper first)
    private static boolean less(double f1, double g1, double f2, double g2) {
        return f1 < f2 - 1e-9 || Math.abs(f1 - f2) <= 1e-9 && g1 > g2;
    }

    private void pop() {
        hn--;
        if (hn == 0) return;
        double f = hf[hn], gg = hg[hn];
        int id = hid[hn];
        int i = 0;
        while (true) {
            int l = 2 * i + 1;
            if (l >= hn) break;
            int r = l + 1;
            int m = r < hn && less(hf[r], hg[r], hf[l], hg[l]) ? r : l;
            if (!less(hf[m], hg[m], f, gg)) break;
            hf[i] = hf[m]; hg[i] = hg[m]; hid[i] = hid[m];
            i = m;
        }
        hf[i] = f; hg[i] = gg; hid[i] = id;
    }
}
