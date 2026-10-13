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
        Plan(Route route, PathSearch.Result result, int pass, long id, long[] stamps, Replay replay, RailPath rails) {
            this.route = route; this.result = result; this.pass = pass; this.id = id; this.stamps = stamps; this.replay = replay;
            this.rails = rails;
        }
    }

    /** One planning job: its latest plan (null until the first pass ends), and whether it has finished. */
    public static final class Job {
        public final AtomicReference<Plan> latest = new AtomicReference<>();
        public volatile boolean done = false;
        public volatile boolean cancelled = false;
        public volatile String failure = null;     // no path at all: why
        public volatile long nanos = 0;
        public volatile int passes = 0;
        public final long submitted = System.nanoTime();
        final long id;
        Job(long id) { this.id = id; }
    }

    private static long ids = 0;

    /** Start a job: from floor `start` to column (gx, gz); with gap jumps when layer 2 will walk it. */
    public static Job plan(View view, long start, int gx, int gz, Costs costs, double margin, boolean canSprint, boolean gaps,
                           long budgetNanos) {
        return plan(view, start, gx, gz, costs, margin, canSprint, gaps, false, budgetNanos);
    }

    public static Job plan(View view, long start, int gx, int gz, Costs costs, double margin, boolean canSprint, boolean gaps,
                           boolean glides, long budgetNanos) {
        Job job = new Job(++ids);
        WORKER.submit(() -> {
            try {
                run(job, view, start, gx, gz, costs, margin, canSprint, gaps, glides, budgetNanos);
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
                            boolean gaps, boolean glides, long budget) {
        long t0 = System.nanoTime(), deadline = t0 + budget;
        PathSearch search = new PathSearch(view, costs).canSprint(canSprint).gaps(gaps).glides(glides);
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
            if (r.exhausted) { job.failure = r.reason; return; }
            if (!r.found && !r.partial) { if (pass == 0) job.failure = r.reason.isEmpty() ? "no route" : r.reason; return; }
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
                Route route = new Tighten(view, costs, margin, canSprint).route(r, gx, gz);
                RailPath rails = new RailPath(view, route);
                Replay replay = new Replay(r, route, rails, ai.rrw.kine.Settings.walkSearchDetailed);
                r.tree = null;   // drawn from the replay now; let the record go
                job.latest.set(new Plan(route, r, pass, job.id, stamps(view, route), replay, rails));
                if (r.found) bestCost = c;
            }
            if (r.partial) break;   // out of time or floors: a better pass won't fit
        }
        lastPasses = job.passes;
        lastNanos = System.nanoTime() - t0;
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
