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
    /** A swim floor serves the feet up to this far under the water's top (deeper than 3 at DEEP_TICKS a block more,
     *  the time to come up). */
    static final double DEEP = 12.0, DEEP_TICKS = 2.0;
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
    /** The destination's Y (feet): only that floor of the goal column is a source (within 0.6); NaN: any floor. */
    final double goalY;

    private final Long2IntOpenHashMap id = new Long2IntOpenHashMap();
    private final LongOpenHashSet columns = new LongOpenHashSet();
    long[] nodes = new long[0];
    double[] value = new double[0];
    int[] next = new int[0];
    /** The move each floor's next[] step is (Graph's kinds), and the floor a straight line from it leads to along its
     *  chain (the any-angle pass), -1 where none. */
    byte[] nextMove = new byte[0];
    int[] lineTo = new int[0];
    /** Turn the any-angle pass off (only to measure what it changes). */
    public static boolean ANY_ANGLE = true;
    float[] px = new float[0], py = new float[0], pz = new float[0];
    byte[] type = new byte[0];
    int n = 0;
    /** Ticks a block at the fastest speed on foot: the bound used for the interpolation's check of neighbours. */
    final double rate;
    public long buildNanos;
    public int edges;
    /** Walking without a route: the columns are the box round the start and the goal, and floors the graph doesn't
     *  join to the goal get the straight line's time (direct). */
    boolean box = false;
    /** What a floor not joined to the goal pays over the straight line's time: being joined is worth more than this. */
    static final double DIRECT_PENALTY = 40.0;
    /** Walking without a route: how far a joined floor is offered to the free actions as a landing, blocks: a jump
     *  carries some 4.3 to 5.4 past its take-off's edge, and the free plans start up to 2 blocks before it (with the run
     *  up's speed; from a standing start at the edge the landing is out of reach). */
    static final double DIRECT_REACH = 8.0;
    /** Walking without a route: whether the graph's moves join each floor to the goal (null otherwise). */
    boolean[] joined;

    /** Walking without a route: the floors the graph joins to the goal within `radius` (horizontal) of (x, z), from
     *  3.2 below to 1.3 above y, best (lowest time plus the straight line to them) first; at most `max`. */
    int[] joinedNear(double x, double y, double z, double radius, int max) {
        return joinedNear(x, y, z, radius, max, false);
    }

    /** As above; climb: a climbable's cells joined to the goal count too (the jump search's landings: ladder_5b's
     *  ladder, trapdoor_ladder's, whose templates land held on them; detect_c14 searched only the floors). */
    int[] joinedNear(double x, double y, double z, double radius, int max, boolean climb) {
        if (joined == null) return new int[0];
        java.util.List<double[]> c = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!joined[i] || !(type[i] == ChunkFloors.GROUND || climb && type[i] == ChunkFloors.CLIMB)) continue;
            double d = Math.hypot(px[i] - x, pz[i] - z);
            if (d > radius || py[i] < y - 3.2 || py[i] > y + 1.3 && !scaffoldTop(i, y)) continue;
            // the floors first, then the climbables (a jump onto a floor before one onto a ladder: detect_c15's first
            // walk took ladder_diagonal by its ladder, and the walk on from it fell)
            c.add(new double[]{value[i] + d * rate + (type[i] == ChunkFloors.CLIMB ? 1e9 : 0), i});
        }
        c.sort((a, b) -> Double.compare(a[0], b[0]));
        int[] out = new int[Math.min(max, c.size())];
        for (int k = 0; k < out.length; k++) out[k] = (int) c.get(k)[1];
        return out;
    }

    /** How far above the feet a scaffolding column's top may be for joinedNear (blocks): a jump into its side and the
     *  climb with the jump key held (some 0.12 a tick) within the jump search's 60 ticks. */
    static final double SCAFFOLD_CLIMB = 5.0;

    /** Whether floor i, higher than a jump reaches from y, is the top of a scaffolding piece no more than SCAFFOLD_CLIMB
     *  above (2026-10-15, the course's scaffolding_jump): reached by jumping into its column and climbing it (the jump
     *  search climbs one; whether the column below reaches down to where a jump gets in is the copy's to find). */
    private boolean scaffoldTop(int i, double y) {
        if (py[i] > y + SCAFFOLD_CLIMB) return false;
        int bx = (int) Math.floor(px[i]), by = (int) Math.floor(py[i] - 1e-3), bz = (int) Math.floor(pz[i]);
        // the floor's own block, from the floors' windows as View.block reads it (by chunkAt: View.chunk's cache is not
        // for a second thread, and this runs on the client's)
        ChunkFloors ch = v.chunkAt(bx >> 4, bz >> 4);
        if (ch == null) return false;
        int col = (bx & 15) + 16 * (bz & 15);
        for (int j = ch.start[col]; j < ch.start[col + 1]; j++) {
            int k = by - ch.fb[j];
            if (k >= 0 && k < ChunkFloors.WINDOW)
                return Palette.get(ch.window[j * ChunkFloors.WINDOW + k]).info.is(ai.rrw.kine.move.BlockInfo.SCAFFOLD);
        }
        return false;
    }

    /**
     * Floors at the level of y (within 0.05) within radius horizontally, joined to the goal or not, the nearest first, at
     * most max; each {x, y, z} as a block (the one under the feet). The templates' key blocks besides the one the walk
     * stands on: on RW's triple neo the walk came to rest 3 blocks short of the take-off, on the walkway.
     */
    int[][] levelNear(double x, double y, double z, double radius, int max) {
        java.util.List<double[]> c = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (type[i] != ChunkFloors.GROUND || Math.abs(py[i] - y) > 0.05) continue;
            double d = Math.hypot(px[i] - x, pz[i] - z);
            if (d > radius) continue;
            c.add(new double[]{d, i});
        }
        c.sort((a, b) -> Double.compare(a[0], b[0]));
        int[][] out = new int[Math.min(max, c.size())][];
        for (int k = 0; k < out.length; k++) {
            int i = (int) c.get(k)[1];
            out[k] = new int[]{(int) Math.floor(px[i]), (int) Math.round(py[i]) - 1, (int) Math.floor(pz[i])};
        }
        return out;
    }

    /**
     * Floors joined to the goal within radius horizontally and 3.2 below to 1.3 above y that bring the walk nearer it than
     * the floor at (x, y, z) (their value less than its, by at least 0.5; every joined one when the walk's floor is not
     * joined), most progress first, at most max; each {x, y, z} as the searches name a landing. The templates' landings:
     * on RW's triple neo the floor right past the wall was not among joinedNear's four (the walkway past it and the
     * platform after it came first), so the triple template's landing, 4 ahead, was never tried.
     */
    int[][] progressNear(double x, double y, double z, double radius, int max) {
        if (joined == null) return new int[0][];
        int here = nodeAt(x, y, z);
        double hv = here >= 0 && joined[here] ? value[here] : Double.POSITIVE_INFINITY;
        java.util.List<double[]> c = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!joined[i] || type[i] != ChunkFloors.GROUND || i == here) continue;
            double d = Math.hypot(px[i] - x, pz[i] - z);
            if (d > radius || py[i] < y - 3.2 || py[i] > y + 1.3) continue;
            if (!(value[i] < hv - 0.5)) continue;
            c.add(new double[]{value[i], i});
        }
        c.sort((a, b) -> Double.compare(a[0], b[0]));
        int[][] out = new int[Math.min(max, c.size())][];
        for (int k = 0; k < out.length; k++) {
            int i = (int) c.get(k)[1];
            out[k] = new int[]{(int) Math.floor(px[i]), (int) Math.round(py[i]) - 1, (int) Math.floor(pz[i])};
        }
        return out;
    }

    /**
     * Climbable cells joined to the goal within radius horizontally and from 1 below to up above y (a ladder chain's
     * far ladder, beyond joinedNear's 1.3: the climb templates' landings, ladder_90_spiral's fourth ladder), the
     * cheapest first, at most max. Each {x, y, z} as the searches name a landing (the block under its feet rounded).
     */
    int[][] climbsNear(double x, double y, double z, double radius, double up, int max) {
        if (joined == null) return new int[0][];
        java.util.List<double[]> c = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!joined[i] || type[i] != ChunkFloors.CLIMB) continue;
            double d = Math.hypot(px[i] - x, pz[i] - z);
            if (d > radius || py[i] < y - 1 || py[i] > y + up) continue;
            c.add(new double[]{value[i] + d * rate, i});
        }
        c.sort((a, b) -> Double.compare(a[0], b[0]));
        int[][] out = new int[Math.min(max, c.size())][];
        for (int k = 0; k < out.length; k++) {
            int i = (int) c.get(k)[1];
            out[k] = new int[]{(int) Math.floor(px[i]), (int) Math.round(py[i]) - 1, (int) Math.floor(pz[i])};
        }
        return out;
    }

    /**
     * The landing of a jump ahead, for layer 2's free actions: along the field's cheapest moves from the floor at (x, y,
     * z), within `reach` blocks, the first GAP move's landing floor {x, y, z} (its centre and height); walking without a
     * route, standing on a floor not joined to the goal, the best joined floor within reach. Null when none.
     */
    double[] jumpAhead(double x, double y, double z, double reach) {
        int i = nodeAt(x, y, z);
        if (i < 0) return null;
        if (joined != null && !joined[i]) {
            int[] c = joinedNear(x, y, z, Math.max(reach, DIRECT_REACH), 1);
            return c.length == 0 ? null : new double[]{px[c[0]], py[c[0]], pz[c[0]]};
        }
        int j = i;
        for (int k = 0; k < 16 && j >= 0; k++) {
            int nx = next[j];
            if (nx < 0) return null;
            if (Math.hypot(px[nx] - x, pz[nx] - z) > reach + 6) return null;
            if (nextMove[j] == Graph.GAP) return Math.hypot(px[j] - x, pz[j] - z) <= reach ? new double[]{px[nx], py[nx], pz[nx]} : null;
            j = nx;
        }
        return null;
    }

    /** Whether the floor the player is on is joined to the goal (walking without a route); true otherwise. */
    boolean joinedAt(double x, double y, double z) {
        if (joined == null) return true;
        int i = nodeAt(x, y, z);
        return i < 0 || joined[i];
    }

    private Field(View v, Costs c, long planId, long[] path, int from, int to, int goalX, int goalZ, double goalY, boolean endsAtGoal) {
        this.v = v; this.c = c; this.planId = planId; this.path = path; this.from = from; this.to = to; this.pathLength = path.length;
        this.goalX = goalX; this.goalZ = goalZ; this.goalY = goalY; this.endsAtGoal = endsAtGoal;
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
        return build(v, c, canSprint, gaps, false, planId, path, null, x, z, goalX, goalZ, Double.NaN, reachedGoal);
    }

    /** route: the tightened route the walk follows (or null): the corridor takes it in too, and the window's end is
     *  sourced along it. */
    public static Field build(View v, Costs c, boolean canSprint, boolean gaps, boolean glides, long planId, long[] path,
                              Route route, double x, double z, int goalX, int goalZ, double goalY, boolean reachedGoal) {
        long t0 = System.nanoTime();
        int at = nearestIndex(path, x, z);
        int from = at;
        double back = 0;
        while (from > 0 && back < BEHIND) { back += hdist(path[from - 1], path[from]); from--; }
        int to = at;
        double ahead = 0;
        while (to + 1 < path.length && ahead < AHEAD) { ahead += hdist(path[to], path[to + 1]); to++; }
        boolean endsAtGoal = reachedGoal && to == path.length - 1;
        Field f = new Field(v, c, planId, path, from, to, goalX, goalZ, goalY, endsAtGoal);
        Graph g = new Graph(v, c);
        g.canSprint = canSprint;
        g.gaps = gaps;
        g.glides = glides;
        g.goalX = goalX; g.goalZ = goalZ;
        f.make(g, path, route, from, to);
        f.buildNanos = System.nanoTime() - t0;
        return f;
    }

    /**
     * The field for walking without a route (layer 1 found none and the goal is near; RW, 2026-10-13: a short-range
     * layer 2 only mode): the floors in the box round the start (path[0]) and the goal column, with RADIUS to spare;
     * the graph's times to the goal where its moves join a floor to it, and elsewhere the straight line's time at the
     * fastest rate plus DIRECT_PENALTY, so layer 2's own search, in the movement copy, finds what the graph doesn't have
     * (a jump round a corner, a ladder's trick) by heading for the goal and for ground the graph joins to it.
     */
    public static Field direct(View v, Costs c, boolean canSprint, boolean gaps, boolean glides, long planId, long[] path,
                               int goalX, int goalZ, double goalY) {
        long t0 = System.nanoTime();
        Field f = new Field(v, c, planId, path, 0, path.length - 1, goalX, goalZ, goalY, true);
        f.box = true;
        Graph g = new Graph(v, c);
        g.canSprint = canSprint;
        g.gaps = gaps;
        g.glides = glides;
        g.goalX = goalX; g.goalZ = goalZ;
        f.make(g, path, null, 0, path.length - 1);
        f.joined = new boolean[f.n];
        for (int i = 0; i < f.n; i++) {
            f.joined[i] = f.value[i] != Double.POSITIVE_INFINITY;
            if (!f.joined[i]) f.value[i] = Math.hypot(goalX + 0.5 - f.px[i], goalZ + 0.5 - f.pz[i]) * f.rate + DIRECT_PENALTY;
        }
        f.buildNanos = System.nanoTime() - t0;
        return f;
    }

    private static double hdist(long a, long b) {
        return Math.hypot(View.nodeX(a) - View.nodeX(b), View.nodeZ(a) - View.nodeZ(b));
    }

    private void make(Graph g, long[] path, Route route, int from, int to) {
        // the corridor's columns and their floors: round the grid path and round the tightened route, which on open
        // ground can lie several blocks off the grid path's staircase (the walk follows the route: a corridor round the
        // grid path alone pulled it back toward the staircase's side)
        for (int i = from; i <= to; i++) {
            int x = View.nodeX(path[i]), z = View.nodeZ(path[i]);
            for (int dx = -RADIUS; dx <= RADIUS; dx++)
                for (int dz = -RADIUS; dz <= RADIUS; dz++) columns.add(View.key(x + dx, z + dz));
        }
        double[] routeArc = null;
        double sFrom = 0, sTo = 0;
        if (route != null && route.gn > 1) {
            routeArc = new double[route.gn];
            for (int k = 1; k < route.gn; k++)
                routeArc[k] = routeArc[k - 1] + Math.hypot(route.gx[k] - route.gx[k - 1], route.gz[k] - route.gz[k - 1]);
            sFrom = routeArc[nearestSample(route, View.nodeX(path[from]) + 0.5, View.nodeZ(path[from]) + 0.5)];
            sTo = routeArc[nearestSample(route, View.nodeX(path[to]) + 0.5, View.nodeZ(path[to]) + 0.5)];
            int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
            for (int k = 0; k < route.gn; k++) {
                if (routeArc[k] < sFrom - 1 || routeArc[k] > sTo + 1) continue;
                int x = (int) Math.floor(route.gx[k]), z = (int) Math.floor(route.gz[k]);
                if (x == lastX && z == lastZ) continue;
                lastX = x; lastZ = z;
                for (int dx = -RADIUS; dx <= RADIUS; dx++)
                    for (int dz = -RADIUS; dz <= RADIUS; dz++) columns.add(View.key(x + dx, z + dz));
            }
        }
        if (endsAtGoal) columns.add(View.key(goalX, goalZ));
        if (box) {
            int sx = View.nodeX(path[0]), sz = View.nodeZ(path[0]);
            for (int x = Math.min(sx, goalX) - RADIUS; x <= Math.max(sx, goalX) + RADIUS; x++)
                for (int z = Math.min(sz, goalZ) - RADIUS; z <= Math.max(sz, goalZ) + RADIUS; z++) columns.add(View.key(x, z));
        }
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
        byte[] fMove = new byte[1024];
        int m = 0;
        for (int i = 0; i < n; i++) {
            g.expand(nodes[i]);
            for (int e = 0; e < g.n; e++) {
                int j = id.get(g.to[e]);
                if (j < 0) continue;
                if (m == fFrom.length) {
                    fFrom = Arrays.copyOf(fFrom, m * 2); fTo = Arrays.copyOf(fTo, m * 2); fCost = Arrays.copyOf(fCost, m * 2);
                    fMove = Arrays.copyOf(fMove, m * 2);
                }
                fFrom[m] = i; fTo[m] = j; fCost[m] = g.cost[e]; fMove[m] = g.move[e]; m++;
            }
        }
        edges = m;
        // reversed adjacency (CSR by target)
        int[] rStart = new int[n + 1];
        for (int e = 0; e < m; e++) rStart[fTo[e] + 1]++;
        for (int i = 0; i < n; i++) rStart[i + 1] += rStart[i];
        int[] rFrom = new int[m];
        double[] rCost = new double[m];
        byte[] rMove = new byte[m];
        int[] fill = Arrays.copyOf(rStart, n);
        for (int e = 0; e < m; e++) { int k = fill[fTo[e]]++; rFrom[k] = fFrom[e]; rCost[k] = fCost[e]; rMove[k] = fMove[e]; }
        nextMove = new byte[n];
        lineTo = new int[n];
        Arrays.fill(lineTo, -1);

        // the sources: the corridor's end at the route's cost from there on (or the goal column's floors at 0)
        double tail = endsAtGoal ? 0.0 : routeCostFrom(g, path, to);
        Heap h = new Heap(n);
        int end = id.get(path[to]);
        if (end >= 0 && (endsAtGoal || routeArc == null)) { value[end] = tail; h.push(end, tail); }
        if (!endsAtGoal && routeArc != null) {
            // the tightened route's last stretch in the window, each floor under it at the route's cost on (the rate of
            // the grid path's last move): the times then lead along the route the walk follows at the window's end
            double rate = to > from ? edgeCost(g, path[to - 1], path[to]) / Math.max(1e-6, hdist(path[to - 1], path[to])) : c.sprint;
            for (int k = 0; k < route.gn; k++) {
                double sArc = routeArc[k];
                if (sArc < sTo - TAIL_LENGTH || sArc > sTo) continue;
                int j = floorAt((int) Math.floor(route.gx[k]), (int) Math.floor(route.gz[k]), route.gy[k], 0.6);
                if (j < 0) continue;
                double t = tail + (sTo - sArc) * rate;
                if (t < value[j]) { value[j] = t; h.push(j, t); }
            }
        } else if (!endsAtGoal) {
            double t = tail;
            for (int k = to - 1; k >= Math.max(from, to - TAIL_SOURCES); k--) {
                t += edgeCost(g, path[k], path[k + 1]);
                int j = id.get(path[k]);
                if (j >= 0 && t < value[j]) { value[j] = t; h.push(j, t); }
            }
        }
        if (endsAtGoal) {
            ChunkFloors ch = v.chunk(goalX, goalZ);
            if (ch != null)
                for (int i = 0; i < ch.count(goalX & 15, goalZ & 15); i++) {
                    int j = id.get(View.node(goalX, goalZ, i));
                    if (j >= 0 && !Double.isNaN(goalY) && Math.abs(py[j] - goalY) >= 0.6) continue;   // a Y given: one floor
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
                if (nv < value[j] - 1e-9) { value[j] = nv; next[j] = i; nextMove[j] = rMove[k]; h.push(j, nv); }
            }
        }
        if (ANY_ANGLE) anyAngle();
    }

    /** The longest straight line the any-angle pass draws, blocks. */
    static final double LINE_REACH = 64.0;

    /**
     * The grid's moves (eight neighbours and knight's moves) make the times anisotropic: on open ground the time to the
     * goal grows up to 2.7% faster across the grid's directions than along them, and within each sector between two of
     * them it slopes toward one fixed direction (13.3 degrees off an axis for a goal anywhere between 0 and 26.6), so
     * the search, going down the times, veered off the straight line (RW, 2026-10-12: "the layer 2 keeps giving a path
     * that veers liek 10 degrees to the letr"). This pass works as Theta* does, backward and over the finished times:
     * in order of time from the goal, a floor whose next floor's straight line ends at a floor it can itself see in a
     * straight line (every column under the line, and a third of a block either side, a floor of its kind and speed
     * within a step of the line's height) takes that line instead, and the time of the line at its own move's rate. On open
     * ground every floor's line runs to the goal or to a corner of the way round, and the times are the straight-line
     * ones. lineTo is the line's far end, for the aim.
     */
    private void anyAngle() {
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(value[a], value[b]));
        double[] corrected = value.clone();
        for (int oi = 0; oi < n; oi++) {
            int i = order[oi];
            int q = next[i];
            if (value[i] == Double.POSITIVE_INFINITY || q < 0) continue;
            int mv = nextMove[i];
            double step = Math.hypot(px[q] - px[i], pz[q] - pz[i]);
            // its own move as the next floor's corrected time and the move
            corrected[i] = corrected[q] + (value[i] - value[q]);
            if (mv != Graph.WALK && mv != Graph.WADE && mv != Graph.SWIM || step < 1e-6) continue;
            double rate = (value[i] - value[q]) / step;
            int p = lineTo[q];
            if (p < 0 || type[p] != type[i]) { lineTo[i] = q; continue; }
            double d = Math.hypot(px[p] - px[i], pz[p] - pz[i]);
            if (d <= LINE_REACH && straight(i, p)) {
                double v = corrected[p] + d * rate;
                if (v <= corrected[i] + 1e-9) { corrected[i] = v; lineTo[i] = p; continue; }   // ties: the line, so lines grow
            }
            lineTo[i] = q;
        }
        // a line that ends at the goal side's first floor: those floors are their own ends
        value = corrected;
    }

    /** Whether a straight walk from floor i to floor a stays on floors of i's kind: under the line and a third of a
     *  block either side of it, every block, a floor within a step of the line's height. */
    private boolean straight(int i, int a) {
        double x0 = px[i], z0 = pz[i], y0 = py[i], x1 = px[a], z1 = pz[a], y1 = py[a];
        double len = Math.hypot(x1 - x0, z1 - z0);
        if (len < 1e-6) return true;
        double nx = -(z1 - z0) / len * 0.3, nz = (x1 - x0) / len * 0.3;
        int steps = (int) Math.ceil(len);
        int kind = type[i], speed = speedClass(i);
        for (int s = 1; s < steps; s++) {
            double t = s / (double) steps, x = x0 + (x1 - x0) * t, z = z0 + (z1 - z0) * t, y = y0 + (y1 - y0) * t;
            for (int side = -1; side <= 1; side += 2)
                if (!floorNear((int) Math.floor(x + nx * side), (int) Math.floor(z + nz * side), y, kind, speed)) return false;
        }
        return true;
    }

    /** What sets a floor's speed: slow blocks (soul sand), water at the feet. A line keeps to one, so its rate holds
     *  all along it. */
    private int speedClass(int i) {
        long nd = nodes[i];
        int x = View.nodeX(nd), z = View.nodeZ(nd);
        ChunkFloors ch = v.chunk(x, z);
        return ch.flags(ch.index(x & 15, z & 15, View.nodeI(nd))) & (ChunkFloors.SLOW | ChunkFloors.WADE);
    }

    /** A floor of this kind and speed in column (x, z) within a step of height y, in the corridor. */
    private boolean floorNear(int x, int z, double y, int kind, int speed) {
        if (!columns.contains(View.key(x, z))) return false;
        ChunkFloors ch = v.chunk(x, z);
        if (ch == null) return false;
        int lx = x & 15, lz = z & 15, k = ch.count(lx, lz);
        for (int i = 0; i < k; i++) {
            int idx = ch.index(lx, lz, i);
            if (ch.type(idx) == kind && Math.abs(ch.h(idx) - y) <= 0.6
                && (ch.flags(idx) & (ChunkFloors.SLOW | ChunkFloors.WADE)) == speed) return true;
        }
        return false;
    }

    /** How many floors before a window's end take the route's own cost as sources (along the grid path, with no
     *  tightened route), and how far back along the tightened route. */
    static final int TAIL_SOURCES = 8;
    static final double TAIL_LENGTH = 8.0;

    /** The route's ground sample nearest (x, z). */
    private static int nearestSample(Route r, double x, double z) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int k = 0; k < r.gn; k++) {
            double d = (r.gx[k] - x) * (r.gx[k] - x) + (r.gz[k] - z) * (r.gz[k] - z);
            if (d < bestD) { bestD = d; best = k; }
        }
        return best;
    }

    /** The cheapest move's cost from floor a to floor b (a straight walk's if there is no move). */
    private static double edgeCost(Graph g, long a, long b) {
        g.expand(a);
        double best = Double.NaN;
        for (int e = 0; e < g.n; e++) if (g.to[e] == b && (Double.isNaN(best) || g.cost[e] < best)) best = g.cost[e];
        return Double.isNaN(best) ? hdist(a, b) * g.c.sprint : best;
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
     *  or in its water (a swim floor's height is the water's top, the feet up to DEEP below it); -1 if none. */
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
                if (h < y - 1.0 || h > y + DEEP) continue;
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
        if (type[own] == ChunkFloors.SWIM && py[own] - y > 3.0) f0 += (py[own] - y - 3.0) * DEEP_TICKS;
        if (type[own] == ChunkFloors.CLIMB) {
            // on a ladder the floors are a block apart: slope the time between this rung and the next one up, so a
            // climb shows progress within the block; on the top rung, toward the floor it climbs off onto (a
            // neighbour's, a block up). Without that the last rung showed none: climbed out over the top into the air
            // it scored worse than hanging below it, and layer 2 hung on the vine short of the top (the Kine Parkour
            // course's vine_jump, RW 2026-10-14)
            int bx = (int) Math.floor(px[own]), bz = (int) Math.floor(pz[own]);
            int up = floorAt(bx, bz, py[own] + 1.0, 0.05), next = -1;
            if (up >= 0 && up != own && value[up] < f0 && Math.abs(py[up] - py[own] - 1.0) < 0.05) next = up;
            else
                for (int d = 0; d < 4; d++) {
                    int j = floorAt(bx + (d == 0 ? 1 : d == 1 ? -1 : 0), bz + (d == 2 ? 1 : d == 3 ? -1 : 0), py[own] + 1.0, 0.6);
                    if (j >= 0 && j != own && value[j] < f0 && py[j] > py[own] + 0.4 && py[j] <= py[own] + 1.6
                        && (next < 0 || value[j] < value[next])) next = j;
                }
            if (next >= 0) f0 = f0 + (value[next] - f0) * Math.max(0.0, Math.min(1.0, y - py[own]));
            // on a climbable the column is the place: no blend with the columns beside it. Blended, the wall a vine
            // hangs on (no floor at the climber's height) counted a sprint's time more, so pressing into the wall to
            // climb scored worse than hanging still at the vine's foot (the course's vine_jump, RW 2026-10-14)
            return f0;
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
            int k = lineTo[j] >= 0 ? lineTo[j] : next[j];   // along the straight lines of the any-angle pass
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
