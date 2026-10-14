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
        public boolean nearest;      // exhausted: the path goes to the reachable floor nearest the goal
        public long[] path = new long[0];
        public byte[] moves = new byte[0];
        /** The path's floor heights. */
        public float[] pathY = new float[0];
        public double cost;          // of the path, ticks (to the edge for viaExit; plus the straight line in total)
        public double totalCost;
        /** The mean rate (ticks a block) of the floors on the straight line from the start toward the goal, as far as
         *  it is loaded: what the way past the loaded edge is charged at, and the ETA's rate for it. */
        public double lineRate = Double.NaN;
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
        public float[] x, y, z, g, h;
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
    /** The destination's Y (feet): only that floor of the goal column counts (within 0.6); NaN: any floor of it. */
    private double goalY = Double.NaN;

    public PathSearch goalY(double y) {
        this.goalY = y;
        return this;
    }

    private boolean goalFloor(double h) {
        return Double.isNaN(goalY) || Math.abs(h - goalY) < 0.6;
    }
    // a quick pass's aim: where the straight line to the goal leaves the loaded area, and the straight line on from it
    private boolean aiming = false;
    private double aimX, aimZ, aimOn;

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

    /** Treat the goal as past the loaded area (ways out at the loaded edge toward it) even where its own chunk is
     *  loaded: for a goal whose loaded chunks aren't joined to the start's (see goalCutOff). */
    public PathSearch exitAlways(boolean b) {
        exitAlways = b;
        return this;
    }

    private boolean exitAlways = false;

    /**
     * Whether the goal's chunk is loaded but not joined to the start's by loaded chunks: a chunk left loaded (or
     * still in the table) away from the rest, after a teleport or a long walk out and back. A search over what's
     * loaded can't reach it there and found "every reachable place searched"; it should head out past the loaded
     * edge toward it instead.
     */
    public boolean goalCutOff(long start, int gx, int gz) {
        if (!v.known(gx, gz)) return false;
        int scx = View.nodeX(start) >> 4, scz = View.nodeZ(start) >> 4, gcx = gx >> 4, gcz = gz >> 4;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>();
        queue.add(new long[]{scx, scz});
        seen.add(View.key(scx, scz));
        while (!queue.isEmpty()) {
            long[] c = queue.poll();
            if (c[0] == gcx && c[1] == gcz) return false;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = (int) c[0] + dx, nz = (int) c[1] + dz;
                    long k = View.key(nx, nz);
                    if (seen.contains(k) || v.chunkAt(nx, nz) == null) continue;
                    seen.add(k);
                    queue.add(new long[]{nx, nz});
                }
        }
        return true;
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
        graph.goalUnknown = exitAlways || !v.known(gx, gz);
        r.lineRate = beyondRate(start, gx, gz);
        if (graph.goalUnknown) graph.beyondRate = r.lineRate;
        // a quick (weighted) pass toward a goal past the loaded area aims through the point where the straight line
        // leaves it: greedy toward the goal itself, it left at the grid's headings (knight's and diagonal lines: goals
        // at 5 to 20 degrees left at 26, up to 18.8 blocks off the line), and that route was walked and drawn until
        // the finer pass came in, or kept when that didn't fit its time (RW, 2026-10-13: a stray route "at an angle"
        // flashing every few seconds)
        aiming = graph.goalUnknown && w > 1.0;
        if (aiming) {
            double[] c0 = lineLeaves(start, gx, gz);
            aimX = c0[0]; aimZ = c0[1];
            aimOn = Math.hypot(gx + 0.5 - aimX, gz + 0.5 - aimZ);
        }
        goalMinH = Double.NaN;
        if (v.known(gx, gz)) {
            ChunkFloors gc = v.chunk(gx, gz);
            int n = gc.count(gx & 15, gz & 15);
            for (int i = 0; i < n; i++) {
                double h = gc.h(gc.index(gx & 15, gz & 15, i));
                if (!goalFloor(h)) continue;
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
        double bestH = Double.MAX_VALUE, tieH = c.estimate(Hop.TIE, 0);
        int bestId = s;
        boolean exitReached = false;
        // toward a goal past the loaded area: the floor to leave from, chosen as if the way to it were the straight line
        // the walk will take (see exitScore), and how far past the first exit found the search goes on looking
        int sx = View.nodeX(start), sz = View.nodeZ(start);
        int bestExit = -1;
        double bestExitScore = Double.MAX_VALUE, bandLimit = Double.MAX_VALUE;
        boolean bestExitWater = false;
        it.unimi.dsi.fastutil.ints.IntArrayList exitIds = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.doubles.DoubleArrayList exitScores = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
        while (hn > 0) {
            int id = hid[0];
            double gg = hg[0], ff = hf[0];
            pop();
            if (exitReached && ff > bandLimit) break;
            if (closedAt[id] != Integer.MAX_VALUE || gg != g[id]) continue;
            if (keys[id] == Long.MIN_VALUE) {
                if (!exitReached) {
                    goalId = id;
                    exitReached = true;
                    bandLimit = ff + (bestExitWater ? EXIT_BAND_WATER : EXIT_BAND) * g[parent[id]] + 1.0;
                }
                continue;
            }
            closedAt[id] = expansions++;
            long key = keys[id];
            if (View.nodeX(key) == gx && View.nodeZ(key) == gz && goalFloor(fy[id])) { goalId = id; break; }
            // the floor nearest the goal (a partial or a shut-in goal's way ends there): by the estimate to the goal itself,
            // not the weighted pass's aimed one (aimed through where the line leaves the loaded area, a floor a block to
            // the side of the start counted farther than the start: the course's next take-off beside its pad was never
            // the nearest, and the walk gave up on the pad; RW 2026-10-14)
            double near = aiming ? nearness(key, fy[id]) : hEst[id];
            // as near (within Hop.TIE blocks: a block aside of a far goal), the floor nearer the destination's Y by 0.4
            // or more is the nearer (the course's start beside its pad, a block lower: RW 2026-10-14, flower_pot_jump)
            boolean better;
            if (bestId < 0 || near < bestH - tieH) better = true;
            else if (near >= bestH + tieH) better = false;
            else if (nearerY(fy[id], fy[bestId])) better = true;
            else if (nearerY(fy[bestId], fy[id])) better = false;
            else better = near < bestH;
            if (better) { bestH = near; bestId = id; }
            if ((expansions & 255) == 0 && System.nanoTime() > deadline) { r.reason = "out of time"; break; }
            if (count >= maxNodes) { r.reason = "out of nodes"; break; }
            graph.expand(key);
            for (int k = 0; k < graph.n; k++) {
                long to = graph.to[k];
                if (graph.move[k] == Graph.EXIT) {
                    boolean water = graph.expandedType == ChunkFloors.SWIM;
                    double sc = exitScore(g[id], graph.cost[k], water, sx, sz, View.nodeX(key), View.nodeZ(key));
                    if (sc < bestExitScore) { bestExitScore = sc; bestExit = id; bestExitWater = water; }
                    exitIds.add(id);
                    exitScores.add(sc);
                }
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
        if (exitReached && bestExit >= 0) {
            // among the ways out as good as the best (within EXIT_TIE of its cost inside), the one nearest the straight
            // line: what is left of the grid's bias is far smaller than that, and a way out off the line by more is
            // taken only when it is better by more
            double tol = EXIT_TIE * g[bestExit] + 1e-6, bestLat = Double.MAX_VALUE;
            double lx = gx - sx, lz = gz - sz, ll = Math.max(1e-9, Math.hypot(lx, lz));
            for (int e = 0; e < exitIds.size(); e++) {
                if (exitScores.getDouble(e) > bestExitScore + tol) continue;
                long key = keys[exitIds.getInt(e)];
                double lat = Math.abs(((View.nodeX(key) - sx) * lz - (View.nodeZ(key) - sz) * lx) / ll);
                if (lat < bestLat - 1e-9) { bestLat = lat; bestExit = exitIds.getInt(e); }
            }
        }
        if (goalId >= 0) {
            r.found = true;
            r.viaExit = exitReached;
            int end = exitReached ? (bestExit >= 0 ? bestExit : parent[goalId]) : goalId;
            fill(r, end);
            // past the loaded edge: the way out's score as the walk will take it (so a later, finer pass that finds a way
            // out nearer the straight line counts as cheaper, which the grid's own costs alone would deny)
            r.totalCost = exitReached ? (bestExit >= 0 ? bestExitScore : g[goalId]) : g[goalId];
            r.cost = g[end];
        } else if (hn == 0 && r.reason.isEmpty()) {
            r.exhausted = true;
            r.reason = "no route: every reachable place searched (" + expansions + ")";
            // the goal can't be reached from here: the way to the floor nearest it (the walk goes as near as it gets)
            if (bestId != s) {
                r.nearest = true;
                fill(r, bestId);
                r.cost = g[bestId];
                r.totalCost = Double.NaN;
            }
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
     * The rate charged past the loaded edge, the same for every way out: the mean rate of the floors on the straight
     * line from the start toward the goal, as far as it is loaded (a sea's way on charged as a swim, the land's as a
     * sprint, a coast's as the mix). One rate for all ways out, so no way out is favoured for the kind of floor it
     * leaves from: charged at each floor's own rate, a coast's land made walking round a sea look cheaper than
     * crossing it (measured 2026-10-12); charged as a sprint everywhere, the shortest swim to the edge, along an axis,
     * made the cheapest way out of a sea (RW's ocean, 2026-10-12).
     */
    double beyondRate(long start, int gx, int gz) {
        int sx = View.nodeX(start), sz = View.nodeZ(start);
        ChunkFloors sc = v.chunk(sx, sz);
        if (sc == null) return c.sprint;
        int si = sc.index(sx & 15, sz & 15, View.nodeI(start));
        double h = sc.h(si);
        double startRate = floorRate(sc, si), sum = 0;
        int n = 0;
        double dx = gx - sx, dz = gz - sz, len = Math.hypot(dx, dz);
        if (len < 1) return startRate;
        for (int k = 1; k <= len; k++) {
            int x = (int) Math.floor(sx + 0.5 + dx * k / len), z = (int) Math.floor(sz + 0.5 + dz * k / len);
            ChunkFloors cf = v.chunk(x, z);
            if (cf == null) break;
            int cnt = cf.count(x & 15, z & 15), best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < cnt; i++) {
                int idx = cf.index(x & 15, z & 15, i);
                double d = Math.abs(cf.h(idx) - h);
                if (d < bestD) { bestD = d; best = idx; }
            }
            if (best < 0) continue;
            h = cf.h(best);
            sum += floorRate(cf, best);
            n++;
        }
        return n == 0 ? startRate : sum / n;
    }

    /** Where the straight line from the start toward the goal leaves what the view knows: its last known point, every
     *  half block. */
    private double[] lineLeaves(long start, int gx, int gz) {
        double ax = View.nodeX(start) + 0.5, az = View.nodeZ(start) + 0.5;
        double lx = gx + 0.5 - ax, lz = gz + 0.5 - az, len = Math.hypot(lx, lz);
        double cx = ax, cz = az;
        for (double t = 0; t <= len; t += 0.5) {
            double x = ax + lx / Math.max(1e-9, len) * t, z = az + lz / Math.max(1e-9, len) * t;
            if (!v.known((int) Math.floor(x), (int) Math.floor(z))) break;
            cx = x; cz = z;
        }
        return new double[]{cx, cz};
    }

    /** A floor's rate a block: swimming, wading, slow ground or a sprint (a walk without sprinting). */
    private double floorRate(ChunkFloors cf, int idx) {
        if (cf.type(idx) == ChunkFloors.SWIM) return c.water;
        int f = cf.flags(idx);
        return c.rate(graph.canSprint, (f & ChunkFloors.SLOW) != 0, (f & ChunkFloors.WADE) != 0);
    }

    /** How far past the first way out found (a share of its cost inside) the search goes on looking for a better floor
     *  to leave from: the grid's anisotropy, at most 2.7% on foot (knight's moves) and 8.3% swimming (eight
     *  neighbours only). */
    static final double EXIT_BAND = 0.03, EXIT_BAND_WATER = 0.09;
    /** Ways out within this share of the best's cost inside count as as good, and the one nearest the line is taken. */
    static final double EXIT_TIE = 0.01;

    /**
     * A way out past the loaded area, scored as the walk will take it: the cost to the floor, scaled from the grid's
     * way there to the straight line, and the way on past the edge (a straight line at the floor's rate). Inside, the
     * grid's moves (eight neighbours, and knight's moves on foot) make a way up to 2.7% longer than the straight line
     * on foot and 8.3% swimming, while the way on past the edge is charged as a straight line; so the cheapest way out
     * had been one along an axis or a diagonal and then the straight line, not the straight line itself (RW,
     * 2026-10-12: "when the target is off teh rendred chunks, the pathfingin goes in liek one axis direction, rather
     * than a straight line"). The tightened route (and layer 2's field) take the straight line, so the cost inside is
     * scaled to it.
     */
    private double exitScore(double inside, double onward, boolean water, int sx, int sz, int x, int z) {
        double grid = water ? octileLength(x - sx, z - sz) : gridLength(x - sx, z - sz);
        double straight = Math.hypot(x - sx, z - sz);
        return (grid < 1e-9 ? inside : inside * straight / grid) + onward;
    }

    /** The length of the shortest way across open water by eight-neighbour moves. */
    static double octileLength(int dx, int dz) {
        int u = Math.max(Math.abs(dx), Math.abs(dz)), v = Math.min(Math.abs(dx), Math.abs(dz));
        return u - v + v * Math.sqrt(2);
    }

    /** The length of the shortest way across open ground by the graph's moves (straight, diagonal, knight's). */
    static double gridLength(int dx, int dz) {
        int u = Math.max(Math.abs(dx), Math.abs(dz)), v = Math.min(Math.abs(dx), Math.abs(dz));
        if (2 * v <= u) return (u - 2 * v) + v * Math.sqrt(5);
        return (u - v) * Math.sqrt(5) + (2 * v - u) * Math.sqrt(2);
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
        t.x = new float[count]; t.y = new float[count]; t.z = new float[count]; t.g = new float[count]; t.h = new float[count];
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
            t.h[i] = (float) hEst[i];
        }
        return t;
    }

    /** Whether height a is nearer the destination's Y than height b by 0.4 or more (false with no Y). */
    private boolean nearerY(double a, double b) {
        return !Double.isNaN(goalY) && !Double.isNaN(a) && !Double.isNaN(b) && Math.abs(a - goalY) < Math.abs(b - goalY) - 0.4;
    }

    /** A newly seen floor. */
    /** The estimate from floor (key, at height h) to the goal itself (as hEst when not aiming). */
    private double nearness(long key, double h) {
        int x = View.nodeX(key), z = View.nodeZ(key);
        double dx = x - graph.goalX, dz = z - graph.goalZ;
        double climb = Double.isNaN(goalMinH) || Double.isNaN(h) ? 0 : Math.max(0, goalMinH - h);
        return c.estimate(Math.sqrt(dx * dx + dz * dz), climb);
    }

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
            double horizontal = aiming ? Math.hypot(x + 0.5 - aimX, z + 0.5 - aimZ) + aimOn : Math.sqrt(dx * dx + dz * dz);
            hEst[id] = c.estimate(horizontal, climb);
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
