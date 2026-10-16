package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * The loose moves' check in layer 2's movement copy (Loose; RW 2026-10-15: the loosened layer 1's "almost possible
 * moves", with "the l2 figure them out"): a route's loose moves are checked in route order before the walk commits to
 * them, each from the state the walk will be in at its take-off, by the jump search the walk itself uses before a jump
 * (Layer2.searchFrom: from there, the standing spots and run-ups, the templates). The state at a take-off: standing at
 * its column's centre when the walk comes to it on proven moves (it stops before a jump, as layer 2 does before a
 * gap); after a loose move straight before it, where the copy says the previous jump's inputs leave the player: come to
 * rest there (keys up) if the take-off holds it, else moving (a chained jump, the next one's inputs played straight on
 * from the landing). So momentum is carried from jump to jump without a velocity in the graph.
 *
 * <p>Checks run on their own threads (THREADS), at a lower priority than the walk's own jump searches, so a check ahead
 * never holds up a jump the walk stands before. What a check found is remembered for the walk (Memo: by the move, the
 * kind of start, the terrain), as the failed searches are, so a route searched again doesn't check its moves again.
 */
final class LooseCheck {
    private LooseCheck() {}

    /** The check's threads (the jump search's split by heading, and its spots). */
    static final int THREADS = 3;

    private static ExecutorService pool(String name) {
        return Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 3);
            return t;
        });
    }

    private static final ExecutorService SEARCH = pool("kine-walk-check"), SPOTS = pool("kine-walk-check-spots");

    /** The blocks a check reads: a box of the movement copy's blocks round a take-off (made on the game's thread from the
     *  live world, or from a map offline). */
    interface Grids {
        /** The blocks within r horizontally of (x, z) and from `down` below to `up` above y; null if they can't be had
         *  (the walk ended, the chunks aren't loaded). */
        BlockGrid around(int x, int y, int z, int r, int down, int up) throws InterruptedException;
    }

    /** One loose move's verdict: whether a way was found, the inputs, where they leave the player (the copy's state
     *  after them) and how long the check took. */
    record Verdict(boolean ok, Recipe recipe, MoveState end, long nanos, boolean moving) {}

    /** What the walk's checks found, kept for the walk (cleared with it): by the move (Loose.edge), how it was started
     *  (standing, or chained on from the move before it) and the terrain's version. */
    static final class Memo {
        final java.util.Map<Long, Verdict> found = new ConcurrentHashMap<>();
        final Set<Long> failed = ConcurrentHashMap.newKeySet();
        /** Moves whose quick check (the jump search from the take-off alone) found no way: a full check (spots, run-ups,
         *  templates) only if the planner still has no way round them (see checkRoute). */
        final Set<Long> doubtful = ConcurrentHashMap.newKeySet();
        /** The moves (Loose.edge) the planner has priced up as doubtful, with their families: kept for the walk, so a
         *  search after a replan starts where the last left off (the Parkour Spiral's leg 8 live: each replan's loose
         *  job went through the same fifteen doubts again). */
        final Set<Long> priced = ConcurrentHashMap.newKeySet();

        static long key(long edge, long chainedFrom, long terrain) {
            return edge * 31 + chainedFrom * 0x9E3779B97F4A7C15L + terrain * 0xC2B2AE3D27D4EB4FL;
        }

        void clear() {
            found.clear();
            failed.clear();
            doubtful.clear();
            priced.clear();
        }
    }

    /** The blocks round a take-off a check reads: as layer 2's copy round the player (Layer2.GRID_R, GRID_DOWN), up to
     *  the landing's height and reach. */
    static final int GRID_R = 14, GRID_DOWN = 10, GRID_UP = 8;

    /** The grid's reach round a take-off: GRID_R, or the landing's distance and 3 more (a bounce off two slime pads lands
     *  up to three bounce reaches away). */
    static int gridR(int tx, int tz, int lx, int lz) {
        return Math.max(GRID_R, (int) Math.ceil(Math.hypot(lx - tx, lz - tz)) + 3);
    }

    /** The quick check's tries run this many ticks at most (a try ends sooner on landing, or 6 under its start): a bounce
     *  off two slime pads takes about 60. */
    static final int TICKS = 90;

    /**
     * Check one loose move from take-off floor (tx, th, tz) onto landing floor (lx, lh, lz): from `start` (null: standing
     * at the take-off's centre), the view at `view`. stop: give up (the walk ended or moved on).
     */
    static Verdict check(Grids grids, Body body, MoveState start, float view, int tx, double th, int tz, int lx, double lh, int lz,
                         boolean sprint, BooleanSupplier stop) throws InterruptedException {
        return check(grids, body, start, view, tx, th, tz, lx, lh, lz, sprint, stop, false, true);
    }

    /** As above; quick: only the jump search from the start itself (no spots, run-ups or templates: a fraction of the
     *  time; most of the moves the loose search offers that have no way fail it as surely). */
    static Verdict check(Grids grids, Body body, MoveState start, float view, int tx, double th, int tz, int lx, double lh, int lz,
                         boolean sprint, BooleanSupplier stop, boolean quick, boolean parallel) throws InterruptedException {
        long t0 = System.nanoTime();
        BlockGrid g = grids.around(tx, (int) Math.floor(th), tz, gridR(tx, tz, lx, lz), GRID_DOWN, GRID_UP);
        if (g == null) return new Verdict(false, null, null, System.nanoTime() - t0, false);
        MoveState s = start != null ? start.copy() : Loose.standing(tx + 0.5, th, tz + 0.5);
        float v = Float.isNaN(view) ? Recipe.yawTo(s.x, s.z, lx + 0.5, lz + 0.5) : view;
        boolean moving = start != null && !(start.onGround && start.vx * start.vx + start.vz * start.vz <= RunModel.STILL2);
        int[] land = {lx, (int) Math.round(lh) - 1, lz};
        int bx = s.hasSupport ? s.supX : tx, by = s.hasSupport ? s.supY : (int) Math.floor(th - 1e-3), bz = s.hasSupport ? s.supZ : tz;
        Recipe r;
        if (moving || quick) {
            // chained on from the jump before: the jump search from the moving landing (as Layer2.maybeChain); or the quick
            // check, from the take-off as it stands
            float b = Recipe.yawTo(s.x, s.z, lx + 0.5, lz + 0.5);
            r = parallel
                ? Recipe.searchParallel(SEARCH, THREADS, new GroundPhysics(g, body), s, v, land[0], land[1], land[2], b - 90f, b + 90f,
                    6f, 8, 8, TICKS, sprint)
                : Recipe.search(new GroundPhysics(g, body), s, v, land[0], land[1], land[2], b - 90f, b + 90f, 6f, 8, 8, TICKS, sprint);
            if (!r.found()) r = null;
        } else {
            r = Layer2.searchFrom(s, v, 0f, g, body, null, null, new int[][]{land}, bx, by, bz, stop, SEARCH, THREADS, SPOTS, null, sprint);
        }
        if (r == null || stop.getAsBoolean()) return new Verdict(false, null, null, System.nanoTime() - t0, moving);
        MoveState end = Recipe.after(new GroundPhysics(g, body), s, r);
        return new Verdict(true, r, end, System.nanoTime() - t0, moving);
    }

    /** Where the player comes to rest from state s, keys up, in grid g (at most 40 ticks): the state, or null if it doesn't
     *  come to rest on the ground within that or ends off floor (lx, lh, lz)'s column and height. */
    static MoveState settle(BlockGrid g, Body body, MoveState s, int lx, double lh, int lz) {
        GroundPhysics ph = new GroundPhysics(g, body);
        MoveState m = s.copy();
        for (int i = 0; i < 40; i++) {
            if (m.onGround && m.vx * m.vx + m.vz * m.vz <= Layer2.REST2) break;
            ph.step(m, 0, 0f, 0f);
        }
        if (!m.onGround || m.vx * m.vx + m.vz * m.vz > Layer2.REST2) return null;
        // at rest on the landing's own block (supported by it, at its height)
        if (Math.abs(m.y - lh) > 0.1 || !m.hasSupport || m.supX != lx || m.supZ != lz) return null;
        return m;
    }

    /** A route's check: the path index of the first loose move without a way (-1: every one has one; -2: let go); or,
     *  when a move of the route failed its quick check and the planner hasn't priced it up yet, its path index with its
     *  edge (doubtful, Loose.edge) for the planner to price it and its family up and look for a way round. */
    record RouteVerdict(int index, long[] doubtful) {
        static final RouteVerdict ALL = new RouteVerdict(-1, null), LET_GO = new RouteVerdict(-2, null);

        boolean isDoubtful() {
            return doubtful != null && doubtful.length > 0;
        }
    }

    /**
     * A route's loose moves checked, lazily, in route order, each from the state the walk will be in at its take-off (see
     * the class note). A move not seen before gets the quick check (the jump search from standing at the take-off, or
     * from where the loose jump before leaves the player); failing it, it is doubtful, and unless the planner has already
     * priced it up (priced) it is returned so, for the planner to price up and look for a way round (most of the loose
     * search's candidates that have no way fail the quick check, at a fraction of the full one's time). A doubtful move
     * the planner's route still takes gets the full check (Layer2.searchFrom: spots, run-ups, templates), and failing that
     * it is failed: the first failed one is returned (the planner blocks it). Verdicts come from the memo when it has
     * them, and go into it. onChecked: told each loose move's path index once it has a way. (Quick-checking all of a
     * route's moves side by side, one thread each, was slower offline on the Parkour Spiral: 21 legs checked in 40 s
     * against 23, most of the checked ones taking twice as long, since most routes are dropped at their first doubt.)
     */
    static RouteVerdict checkRoute(PathSearch.Result r, Grids grids, Body body, Memo memo, long terrain, boolean sprint,
                                   BooleanSupplier stop, java.util.function.IntConsumer onChecked, java.util.Set<Long> priced)
        throws InterruptedException {
        Verdict prev = null;
        long prevEdge = 0;
        for (int i = 1; i < r.path.length; i++) {
            if (r.moves[i] != Graph.LOOSE) { prev = null; continue; }
            if (stop.getAsBoolean()) return RouteVerdict.LET_GO;
            long a = r.path[i - 1], b = r.path[i];
            int tx = View.nodeX(a), tz = View.nodeZ(a), lx = View.nodeX(b), lz = View.nodeZ(b);
            double th = r.pathY[i - 1], lh = r.pathY[i];
            long edge = Loose.edge(a, b);
            boolean chained = prev != null && prev.ok();
            long key = Memo.key(edge, chained ? prevEdge : 0, terrain);
            if (memo.failed.contains(key)) return new RouteVerdict(i, null);
            Verdict v = memo.found.get(key);
            if (v == null) {
                MoveState start = null;
                float view = Float.NaN;
                if (chained && prev.end() != null) {
                    // straight on from the loose jump before: come to rest on this take-off if it holds the player, else on
                    // the move (keys up from the landing is what the walk does: a hop's floor arrives standing)
                    BlockGrid g = grids.around(tx, (int) Math.floor(th), tz, gridR(tx, tz, lx, lz), GRID_DOWN, GRID_UP);
                    MoveState rest = g == null ? null : settle(g, body, prev.end(), tx, th, tz);
                    start = rest != null ? rest : prev.end();
                    view = prev.recipe().yaw[prev.recipe().ticks - 1];
                }
                boolean doubtful = memo.doubtful.contains(key);
                v = check(grids, body, start, view, tx, th, tz, lx, lh, lz, sprint, stop, !doubtful, true);
                if (stop.getAsBoolean()) return RouteVerdict.LET_GO;
                if (v.ok()) memo.found.put(key, v);
                else if (!doubtful && !v.moving()) {
                    // the quick check failed: doubtful (from a moving start the quick and the full check are the same
                    // jump search: failed)
                    memo.doubtful.add(key);
                    if (priced == null || !priced.contains(edge)) return new RouteVerdict(i, new long[]{edge});
                    v = check(grids, body, start, view, tx, th, tz, lx, lh, lz, sprint, stop, false, true);
                    if (stop.getAsBoolean()) return RouteVerdict.LET_GO;
                    if (v.ok()) memo.found.put(key, v);
                    else memo.failed.add(key);
                }
                else memo.failed.add(key);
            }
            if (!v.ok()) return new RouteVerdict(i, null);
            if (onChecked != null) onChecked.accept(i);
            prev = v;
            prevEdge = edge;
        }
        return RouteVerdict.ALL;
    }
}
