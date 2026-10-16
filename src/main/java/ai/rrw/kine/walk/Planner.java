package ai.rrw.kine.walk;

import ai.rrw.kine.Kine;
import net.minecraft.util.Mth;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs layer 1's searches on a worker thread, over a snapshot of the table, within a wall-clock budget, so the game's
 * thread never waits for one. Anytime: a quick weighted pass first (its path is published at once and the player can
 * start walking), then passes with smaller weights while the budget lasts, each published if it is cheaper. How much
 * effort that is follows this machine's measured speed (RW: adjusted "base don how powerful the paleyrs cpu is"): the
 * planner keeps the rate at which its searches expand floors and how much bigger each pass's search is than the one
 * before, and starts a pass only if it is predicted to finish in the time left; the first pass gets a cap on floors
 * from the same rate. Measured, not guessed from core counts. A search that runs out leaves a partial path toward the
 * goal, and the walk replans as it goes.
 */
public final class Planner {
    private Planner() {}

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kine-walk-search");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** The loosened layer 1's searches and their checks, on their own thread (Loose, LooseCheck): started by a strict
     *  job whose first pass shows no way, so the walk doesn't wait for both. */
    private static final ExecutorService LOOSE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kine-walk-loose");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** The loose search's time a search; the loose job's whole time (its searches and checks); its searches at most. */
    static final long LOOSE_SEARCH_NANOS = 1_000_000_000L, LOOSE_JOB_NANOS = 90_000_000_000L;
    static final int LOOSE_SEARCHES = 400;   // with the quick checks most searches end in a fraction of a second

    /** The weights of the passes, quick to cheapest. With the estimate at the sprint-jump speed, w = 1.27 makes it
     *  about the sprint cost on open ground. */
    static final double[] WEIGHTS = {2.0, 1.27, 1.0};

    // measured on this machine
    private static double rate = -1;              // floors expanded per millisecond, smoothed
    private static final double[] growth = {1, 2.0, 2.0};   // expansions of pass k against pass k - 1, smoothed
    public static volatile long lastNanos = 0, lastFirstNanos = 0;
    public static volatile int lastPasses = 0;

    /** A published plan: the route and the search it came from. */
    public static final class Plan {
        public final Route route;
        public final PathSearch.Result result;
        public final int pass;
        public final long id;
        /** The columns the route crosses and beside it, and their stamps when planned: {x, z, stamp} each. */
        public final long[] stamps;
        /** What the search display draws, prepared here on the worker. */
        final Replay replay;
        /** The rails' shape along the route, prepared here on the worker. */
        final RailPath rails;
        /** The planned time left from each place along the route, for the ETA; null if it couldn't be made. */
        public final TimeProfile time;
        /** The route reaches the goal, but the straight line from its start to the goal crosses ground not loaded when
         *  it was planned: a straighter way may open as that ground loads. */
        public final boolean throughUnknown;
        /** No route: layer 1 found none and the goal is near, so layer 2 walks with its own search (Field.direct); the
         *  route is the straight line, for the progress check and the display. */
        public final boolean direct;
        /** A loosened layer 1 route (its search offered Loose moves): the path index up to which its loose moves have
         *  been checked in the movement copy and found a way (every loose move at or before it has one); MAX_VALUE: all
         *  of them, or a route of proven moves. Written by the loose job's checks, read by the walk. */
        public volatile int checkedTo = Integer.MAX_VALUE;
        /** The loose moves on the route (0 for a route of proven moves). */
        public volatile int looseMoves = 0;
        Plan(Route route, PathSearch.Result result, int pass, long id, long[] stamps, Replay replay, RailPath rails,
             TimeProfile time, boolean throughUnknown) {
            this(route, result, pass, id, stamps, replay, rails, time, throughUnknown, false);
        }
        Plan(Route route, PathSearch.Result result, int pass, long id, long[] stamps, Replay replay, RailPath rails,
             TimeProfile time, boolean throughUnknown, boolean direct) {
            this.route = route; this.result = result; this.pass = pass; this.id = id; this.stamps = stamps; this.replay = replay;
            this.rails = rails; this.time = time; this.throughUnknown = throughUnknown; this.direct = direct;
        }
    }

    /** One planning job: its latest plan (null until the first pass ends), and whether it has finished. */
    public static final class Job {
        public final AtomicReference<Plan> latest = new AtomicReference<>();
        public volatile boolean done = false;
        public volatile boolean cancelled = false;
        public volatile String failure = null;     // no path at all: why
        public volatile boolean exhausted = false; // that, with every reachable place searched (the goal shut in)
        public volatile long nanos = 0;
        public volatile int passes = 0;
        /** The loosened layer 1's job, started when this one's first pass found no way (or only a short way toward a far
         *  goal): its plans replace this one's. Null when none. */
        public volatile Job loose = null;
        /** Why the loose job gave up without a route (for the walk's message and the report). */
        public volatile String looseWhy = "";
        public final long submitted = System.nanoTime();
        final long id;
        Job(long id) { this.id = id; }
    }

    private static long ids = 0;

    /** A plan without a route, from floor start to the goal column's floor nearest its height (Plan.direct); null when
     *  the goal column has no floor in the view. Made on the game's thread (two points: quick). */
    static Plan direct(View v, long start, int gx, int gz, double goalY, Costs costs, double margin, boolean canSprint) {
        ChunkFloors gc = v.chunk(gx, gz);
        if (gc == null || gc.count(gx & 15, gz & 15) == 0) return null;
        ChunkFloors sc = v.chunk(View.nodeX(start), View.nodeZ(start));
        double sh = sc.h(v.index(start));
        int best = 0;
        double bestD = Double.MAX_VALUE;
        double want = Double.isNaN(goalY) ? sh : goalY;   // the destination's Y names the goal's floor
        for (int i = 0; i < gc.count(gx & 15, gz & 15); i++) {
            double d = Math.abs(gc.h(gc.index(gx & 15, gz & 15, i)) - want);
            if (d < bestD) { bestD = d; best = i; }
        }
        long goal = View.node(gx, gz, best);
        PathSearch.Result r = new PathSearch.Result();
        r.path = new long[]{start, goal};
        r.moves = new byte[]{(byte) Graph.START, (byte) Graph.WALK};
        r.pathY = new float[]{(float) sh, gc.h(gc.index(gx & 15, gz & 15, best))};
        r.reason = "no route: walking with the movement search";
        Route route = new Tighten(v, costs, margin, canSprint).straight(start, goal, gx, gz);
        RailPath rails = new RailPath(v, route);
        Replay replay = new Replay(r, route, rails, false);
        return new Plan(route, r, 0, ++ids, null, replay, rails, null, false, true);
    }

    /** Start a job: from floor `start` to column (gx, gz) (any floor of it); with gap jumps when layer 2 will walk it. */
    public static Job plan(View view, long start, int gx, int gz, Costs costs, double margin, boolean canSprint, boolean gaps,
                           long budgetNanos) {
        return plan(view, start, gx, gz, Double.NaN, costs, margin, canSprint, gaps, false, budgetNanos);
    }

    /** goalY: the destination's Y (feet), only that floor of the column is the goal; NaN: any floor of it. */
    public static Job plan(View view, long start, int gx, int gz, double goalY, Costs costs, double margin, boolean canSprint,
                           boolean gaps, boolean glides, long budgetNanos) {
        return plan(view, start, gx, gz, goalY, costs, margin, canSprint, gaps, glides, budgetNanos, null);
    }

    /**
     * The loosened layer 1's context for a walk (Walk keeps it): the moves found not to work (by Loose.edge; the strict
     * search leaves them out too), the checks' memory, the blocks the checks read, the player's body, the terrain's
     * version when asked, whether the walk sprints.
     */
    public record LooseContext(java.util.Set<Long> blocked, LooseCheck.Memo memo, LooseCheck.Grids grids,
                               ai.rrw.kine.move.Body body, long terrain, boolean sprint) {}

    /** What a doubtful loose move (its quick check found no way) costs the search more (ticks): enough that a way round
     *  it with a few more jumps or a longer walk is tried first (LooseCheck.checkRoute). */
    static final double DOUBT = 40.0;

    /** The doubtful moves' extra costs now (from the memo's doubtful keys: their moves, started standing). */
    private static it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap penaltiesNow(java.util.Set<Long> doubtfulEdges) {
        it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap m = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
        for (Long e : doubtfulEdges) m.put(e.longValue(), DOUBT);
        return m;
    }

    /** As above; loose: the loosened layer 1 as the fallback when this search finds no way (null: none). */
    public static Job plan(View view, long start, int gx, int gz, double goalY, Costs costs, double margin, boolean canSprint,
                           boolean gaps, boolean glides, long budgetNanos, LooseContext loose) {
        return plan(view, start, gx, gz, goalY, costs, margin, canSprint, gaps, glides, budgetNanos, loose, Double.NaN);
    }

    /** As above; keepAbove: the lowest floor the way may end at when the goal can't be reached (PathSearch.keepAbove). */
    public static Job plan(View view, long start, int gx, int gz, double goalY, Costs costs, double margin, boolean canSprint,
                           boolean gaps, boolean glides, long budgetNanos, LooseContext loose, double keepAbove) {
        Job job = new Job(++ids);
        WORKER.submit(() -> {
            try {
                run(job, view, start, gx, gz, costs, margin, canSprint, gaps, glides, budgetNanos, goalY, loose, keepAbove);
            } catch (Throwable t) {
                Kine.LOGGER.warn("Kine walk: search failed", t);
                job.failure = "search error";
            } finally {
                job.nanos = System.nanoTime() - job.submitted;
                job.done = true;
            }
        });
        return job;
    }

    private static void run(Job job, View view, long start, int gx, int gz, Costs costs, double margin, boolean canSprint,
                            boolean gaps, boolean glides, long budget, double goalY, LooseContext loose, double keepAbove) {
        run(job, view, start, gx, gz, costs, margin, canSprint, gaps, glides, budget, new PathSearch(view, costs)
            .canSprint(canSprint).gaps(gaps).glides(glides).goalY(goalY).blocked(loose == null ? null : blockedNow(loose))
            .doors(Graph.doorsOn).keepAbove(keepAbove),
            goalY, loose);
    }

    /** The context's blocked moves now, as a set the search can read on its own thread. */
    private static it.unimi.dsi.fastutil.longs.LongOpenHashSet blockedNow(LooseContext c) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet s = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (Long e : c.blocked()) s.add(e.longValue());
        return s;
    }

    /**
     * Whether the strict search's first pass shows no way on: every reachable floor searched (shut in, or only the floor
     * nearest the goal), or cut short with its best floor hardly nearer the goal than the start (on a big map the search
     * may run out of time before it runs out of floors: the Parkour Spiral's sea and desert round the tower). Nearer by
     * a quarter of the start's estimate or 8 blocks at the fastest, it is a way on, as toward any far goal.
     */
    static boolean noWayOn(PathSearch.Result r, View view, long start, int gx, int gz, double goalY, Costs costs) {
        if (r.exhausted) return true;
        if (!r.partial || r.path.length == 0) return false;
        double s0 = nearness(view, start, gx, gz, goalY, costs), s1 = nearness(view, r.path[r.path.length - 1], gx, gz, goalY, costs);
        return s0 - s1 < Math.max(0.25 * s0, costs.estimate(8.0, 0.0));
    }

    private static double nearness(View v, long n, int gx, int gz, double goalY, Costs c) {
        ChunkFloors ch = v.chunk(View.nodeX(n), View.nodeZ(n));
        int i = v.index(n);
        double h = ch == null || i < 0 ? Double.NaN : ch.h(i);
        double hor = Math.hypot(gx - View.nodeX(n), gz - View.nodeZ(n));
        return c.estimate(hor, Double.isNaN(goalY) || Double.isNaN(h) ? 0.0 : Math.max(0.0, goalY - h));
    }

    private static void run(Job job, View view, long start, int gx, int gz, Costs costs, double margin, boolean canSprint,
                            boolean gaps, boolean glides, long budget, PathSearch search, double goalY, LooseContext loose) {
        long t0 = System.nanoTime(), deadline = t0 + budget;
        double bestCost = Double.MAX_VALUE;
        int prevExpanded = 0;
        boolean cutOff = false;
        for (int pass = 0; pass < WEIGHTS.length && !job.cancelled; pass++) {
            long now = System.nanoTime();
            if (pass > 0 && rate > 0) {
                double predicted = prevExpanded * growth[pass] / rate * 1e6;   // nanoseconds
                if (now + predicted > deadline) break;
            }
            int cap = rate > 0 && pass == 0 ? (int) Math.min(2_000_000, Math.max(50_000, rate * budget / 1e6)) : 2_000_000;
            PathSearch.Result r = search.run(start, gx, gz, WEIGHTS[pass], deadline, cap, true);
            double ms = r.nanos / 1e6;
            if (r.expanded > 500 && ms > 0.2) {
                double rt = r.expanded / ms;
                rate = rate < 0 ? rt : 0.7 * rate + 0.3 * rt;
            }
            if (pass > 0 && prevExpanded > 0 && !r.partial)
                growth[pass] = 0.7 * growth[pass] + 0.3 * Math.max(0.2, (double) r.expanded / prevExpanded);
            prevExpanded = r.expanded;
            job.passes = pass + 1;
            if (pass == 0) lastFirstNanos = r.nanos;
            if (r.exhausted && pass == 0 && !cutOff && search.goalCutOff(start, gx, gz)) {
                // the goal's chunk is loaded but cut off from ours: head out past the loaded edge toward it
                cutOff = true;
                search.exitAlways(true);
                pass--;
                continue;
            }
            // no way on by the proven moves: the loosened layer 1 on its own thread at once (RW 2026-10-15: "so it shoudl
            // try purely legal layer 1, then fallbakc ot het right"); its plans replace this job's when they come
            // only toward a goal in the loaded area: toward one past it (the Kine Parkour course's target, 900 blocks on)
            // the walk crosses what layer 1 can't with hops, as before; with the loose search there, the walk waited
            // on checks of the course's jumps and stood (stuck at #30 in cS1, at #18 in RW's playtest, 2026-10-15)
            if (pass == 0 && loose != null && job.loose == null && !job.cancelled && view.known(gx, gz)
                && noWayOn(r, view, start, gx, gz, goalY, costs))
                job.loose = loose(job, view.copy(), start, gx, gz, goalY, costs, margin, canSprint, glides, loose);
            if (r.exhausted && !r.nearest) { job.failure = r.reason; job.exhausted = true; return; }
            if (!r.found && !r.partial && !r.nearest) { if (pass == 0) job.failure = r.reason.isEmpty() ? "no route" : r.reason; return; }
            if (r.partial && pass == 0) {
                // out of time or floors before the goal: is the destination closed off? (a small pocket found backward)
                int pocket = search.pocketSize(gx, gz, 4096);
                if (pocket >= 0) {
                    job.failure = "the destination is closed off (" + pocket + " places around it, no way in)";
                    return;
                }
            }
            double c = r.found ? (Double.isNaN(r.totalCost) ? r.cost : r.totalCost) : Double.MAX_VALUE;
            // published: the first result, then any pass that finds a path at least as cheap (a smaller weight's path
            // of the same cost is proven cheaper or as cheap, and its search is the one the replay shows)
            if (job.latest.get() == null || r.found && c <= bestCost + 1e-6) {
                job.latest.set(makePlan(view, r, pass, job.id, gx, gz, costs, margin, canSprint, gaps, glides));
                if (r.found) bestCost = c;
            }
            if (r.partial || r.exhausted) break;   // out of time or floors, or no way to the goal: a better pass won't help
        }
        lastPasses = job.passes;
        lastNanos = System.nanoTime() - t0;
    }

    /** A search's result made into a plan: the tightened route, the rails, the search display's replay, the time
     *  profile for the ETA, the columns' stamps. */
    private static Plan makePlan(View view, PathSearch.Result r, int pass, long id, int gx, int gz, Costs costs, double margin,
                                 boolean canSprint, boolean gaps, boolean glides) {
        Route route = new Tighten(view, costs, margin, canSprint).route(r, gx, gz);
        RailPath rails = new RailPath(view, route);
        Replay replay = new Replay(r, route, rails, ai.rrw.kine.Settings.walkSearchDetailed);
        r.tree = null;   // drawn from the replay now; let the record go
        TimeProfile time = null;
        try {
            time = TimeProfile.of(view, costs, canSprint, gaps, glides, r, route, gx, gz);
        } catch (RuntimeException e) {
            Kine.LOGGER.warn("Kine walk: no time profile for the ETA", e);
        }
        boolean blind = !route.viaExit && r.found && crossesUnknown(view, route.x[0], route.z[0], gx + 0.5, gz + 0.5);
        return new Plan(route, r, pass, id, stamps(view, route), replay, rails, time, blind);
    }

    /**
     * The loosened layer 1 (Loose), as a job of its own: the search with loose moves, its route published at once (the
     * walk can go along its proven moves), its loose moves checked in the movement copy in route order (LooseCheck), each
     * checked one marked on the plan (checkedTo); a loose move that fails its check is blocked with its family
     * (Loose.family, in the walk's context, so the walk's later searches leave it out too) and the search runs again
     * without it (LazySP), until a route's loose moves all have a way, none is left, a route costs more than
     * Loose.costBound, or the job's time runs out.
     */
    private static Job loose(Job parent, View view, long start, int gx, int gz, double goalY, Costs costs, double margin,
                             boolean canSprint, boolean glides, LooseContext ctx) {
        Job job = new Job(++ids);
        LOOSE.submit(() -> {
            try {
                runLoose(job, parent, view, start, gx, gz, goalY, costs, margin, canSprint, glides, ctx);
            } catch (Throwable t) {
                Kine.LOGGER.warn("Kine walk: loose search failed", t);
                job.failure = "loose search error";
            } finally {
                job.nanos = System.nanoTime() - job.submitted;
                job.done = true;
            }
        });
        return job;
    }

    /** The loose job's searches and checks (see loose). */
    public static volatile int looseSearches = 0, looseChecksFailed = 0, looseRoutesChecked = 0;

    private static void runLoose(Job job, Job parent, View view, long start, int gx, int gz, double goalY, Costs costs,
                                 double margin, boolean canSprint, boolean glides, LooseContext ctx) throws InterruptedException {
        long t0 = System.nanoTime();
        java.util.function.BooleanSupplier stop = () -> job.cancelled || parent.cancelled || System.nanoTime() - t0 > LOOSE_JOB_NANOS;
        double bound = Loose.costBound(costs, start, gx, gz, goalY, view);
        java.util.Set<Long> doubtfulEdges = ctx.memo().priced;
        for (int it = 0; it < LOOSE_SEARCHES; it++) {
            if (stop.getAsBoolean()) { job.failure = "loose search let go"; return; }
            PathSearch search = new PathSearch(view, costs).canSprint(canSprint).gaps(true).glides(glides).loose(true).goalY(goalY)
                .blocked(blockedNow(ctx)).penalties(penaltiesNow(doubtfulEdges)).doors(Graph.doorsOn);
            PathSearch.Result r = search.run(start, gx, gz, 2.0, System.nanoTime() + LOOSE_SEARCH_NANOS, 2_000_000, true);
            job.passes = it + 1;
            looseSearches++;
            if (!r.found) {
                job.failure = r.exhausted ? "no way even with nearly possible jumps" : "the loose search ran out of time";
                parent.looseWhy = job.failure;
                looseLog(job, it, r, "no route: " + job.failure);
                return;
            }
            if (r.cost > bound) {
                job.failure = String.format(java.util.Locale.ROOT, "only a loose way round far (%.0f ticks against at most %.0f)", r.cost, bound);
                parent.looseWhy = job.failure;
                looseLog(job, it, r, job.failure);
                return;
            }
            int n = 0;
            for (int i = 1; i < r.moves.length; i++) if (r.moves[i] == Graph.LOOSE) n++;
            Plan p = makePlan(view, r, it, job.id, gx, gz, costs, margin, canSprint, true, glides);
            p.looseMoves = n;
            p.checkedTo = n == 0 ? Integer.MAX_VALUE : 0;
            // published only once every loose move of it has a way: a route published before its checks was walked
            // while the search went on, and each next candidate's took the walk somewhere else (the Parkour Spiral's
            // leg 1: led off past the end of a fence, it stood there at a pool's edge, d9a; RW's playtest of
            // 05524e3 stood near that fence too). The walk waits for it ("searching nearly possible jumps").
            if (n == 0) { job.latest.set(p); return; }
            LooseCheck.RouteVerdict v = LooseCheck.checkRoute(r, ctx.grids(), ctx.body(), ctx.memo(), ctx.terrain(), ctx.sprint(), stop,
                i -> p.checkedTo = i, doubtfulEdges);
            int fail = v.index();
            looseLog(job, it, r, v.isDoubtful() ? v.doubtful().length + " doubtful" : fail == -1 ? "every loose move has a way"
                : fail < 0 ? "let go" : "no way " + View.nodeX(r.path[fail - 1]) + "," + r.pathY[fail - 1] + ","
                + View.nodeZ(r.path[fail - 1]) + " -> " + View.nodeX(r.path[fail]) + "," + r.pathY[fail] + "," + View.nodeZ(r.path[fail]));
            if (v.isDoubtful()) {
                // priced up with its family (Loose.family): the neighbouring columns' copies of a jump that failed its
                // quick check would come back one by one otherwise; one the route still takes gets the full check
                looseChecksFailed++;
                for (long e : v.doubtful()) doubtfulEdges.add(e);
                for (long e : Loose.family(view, r.path[fail - 1], r.path[fail])) doubtfulEdges.add(e);
                continue;
            }
            if (fail == -1) { p.checkedTo = Integer.MAX_VALUE; looseRoutesChecked++; job.latest.set(p); return; }
            if (fail < 0) { job.failure = "loose search let go"; return; }
            looseChecksFailed++;
            for (long e : Loose.family(view, r.path[fail - 1], r.path[fail])) ctx.blocked().add(e);
        }
        job.failure = "the loose search's tries ran out";
        parent.looseWhy = job.failure;
        looseLog(job, LOOSE_SEARCHES, null, job.failure);
    }

    /** One loose search's outcome in the log (KINE_SEARCH_LOG set, as layer 2's jump searches: lockstep tests). */
    private static void looseLog(Job job, int it, PathSearch.Result r, String what) {
        if (!Layer2.SEARCH_LOG) return;
        int n = 0;
        if (r != null) for (int i = 1; i < r.moves.length; i++) if (r.moves[i] == Graph.LOOSE) n++;
        Kine.LOGGER.info(String.format(java.util.Locale.ROOT, "Kine walk loose: job %d search %d%s: %s", job.id, it,
            r == null ? "" : String.format(java.util.Locale.ROOT, " (%s, cost %.0f, %d loose, %d expanded, %.0f ms)",
                r.found ? "found" : r.nearest ? "nearest" : r.partial ? "partial" : "none", r.cost, n, r.expanded, r.nanos / 1e6), what));
    }

    /** Whether the straight line from (ax, az) to (bx, bz) crosses a column the view doesn't know (every half block). */
    static boolean crossesUnknown(View v, double ax, double az, double bx, double bz) {
        double lx = bx - ax, lz = bz - az, len = Math.hypot(lx, lz);
        for (double t = 0; t <= len; t += 0.5) {
            if (!v.known(Mth.floor(ax + lx / Math.max(1e-9, len) * t), Mth.floor(az + lz / Math.max(1e-9, len) * t))) return true;
        }
        return false;
    }

    /** The columns under and beside the route's ground samples (the 3 by 3 around each), with their stamps in the view:
     *  {x, z, stamp} each. */
    private static long[] stamps(View view, Route r) {
        it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
        for (int i = 0; i < r.gn; i++) {
            int x = Mth.floor(r.gx[i]), z = Mth.floor(r.gz[i]);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) seen.add(View.key(x + dx, z + dz));
        }
        long[] out = new long[seen.size() * 3];
        int k = 0;
        for (long key : seen) {
            int x = (int) (key >> 32), z = (int) key;
            ChunkFloors c = view.chunk(x, z);
            out[k++] = x; out[k++] = z; out[k++] = c == null ? -1 : c.columnStamp(x & 15, z & 15);
        }
        return out;
    }
}
