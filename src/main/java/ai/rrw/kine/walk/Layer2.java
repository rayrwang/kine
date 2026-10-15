package ai.rrw.kine.walk;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.combat.KillAura;
import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.WorldSnapshot;
import ai.rrw.kine.search.EffortTuner;
import ai.rrw.kine.search.RecedingSearch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The walking pathfinder's layer 2: instead of layer 1's follower, a search over the game's own movement keys, run in
 * the exact movement copy ({@link RunModel}), chooses what to press each tick along layer 1's route. Layer 1 still finds
 * the way (which side of the hill, where to swim); layer 2 finds how to move along it, and anything the copy says is
 * faster: sprint-jumping, cutting a corner at speed, swimming under the top, climbing out of water, jumping a gap.
 *
 * <p>Each tick, at its end (the move packet sent): a snapshot of the player's movement state, the blocks within 16
 * (rebuilt when the player has moved 4 blocks or every second) and the rotation last sent go to a worker, which runs the
 * receding-horizon search (RecedingSearch over RunModel, effort tuned to this machine's measured speed by EffortTuner)
 * against layer 1's map of the time left ({@link Field}, rebuilt on the same worker for each new route and as the player
 * goes along a long one). At the next tick's start the plan's first tick becomes the heading the rotation sent turns
 * to (through the kill aura's turn, at most 30 degrees a tick, the view untouched) and the keys pressed at that rotation.
 * When it has no plan (the field not built yet, every run unsafe, the worker late) the layer 1 follower steers that
 * tick.
 */
public final class Layer2 {
    private Layer2() {}

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kine-walk-layer2");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** The copy-found jumps' searches (some 250 ms each) on their own thread: on the worker they held the replans up,
     *  and a free plan, which changes its action every tick, can't be carried on while a replan is late (the walk fell
     *  off the take-off: pkTrace2, station 104). */
    private static final ExecutorService RECIPES = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kine-walk-recipes");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 2);
        return t;
    });

    /** The jump searches from standing spots (Recipe.spots), one a thread: when the search from where the walk stands
     *  finds nothing, the spots are searched at once rather than one after another (some 250 ms each). */
    static final int SPOT_THREADS = 4;
    private static final ExecutorService SPOTS = Executors.newFixedThreadPool(SPOT_THREADS, r -> {
        Thread t = new Thread(r, "kine-walk-spots");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 2);
        return t;
    });

    /** The jump search from where the walk stands, split by its first heading over this many threads (Recipe
     *  .searchParallel): the walk stands while it runs, some 250 ms on one thread. */
    static final int SEARCH_THREADS = 4;
    private static final ExecutorService SEARCH = Executors.newFixedThreadPool(SEARCH_THREADS, r -> {
        Thread t = new Thread(r, "kine-walk-jumpsearch");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 2);
        return t;
    });

    static RecedingSearch.Effort startEffort() {
        RecedingSearch.Effort e = new RecedingSearch.Effort();
        e.beam = 4; e.depth = 5; e.hold = 4; e.draws = 1;
        e.budgetNanos = 20_000_000L;   // a worker between ticks: under half the 50 ms
        return e;
    }

    private static final RecedingSearch.Effort START = startEffort();
    private static final EffortTuner TUNER = new EffortTuner(START, 2, 8, 1, 1);

    /** The blocks copied round the player: 16 across and 8 down at least; deeper where the route drops further near
     *  the player (into deep water, a glide's landing), wider toward a glide's landing; rebuilt after moving a quarter
     *  of the width. */
    static final int GRID_R = 16, GRID_DOWN = 8, GRID_UP = 6, GRID_MAX_DOWN = 48, GRID_MAX_R = 40;
    static final int GRID_AGE = 20;
    /** In water the blocks reach up past the water's top over the player, at most this far up. */
    static final int GRID_MAX_UP = 32;

    /**
     * How far up from the feet the copy's blocks reach: GRID_UP, or in water to two above the water's top over the
     * player (at most GRID_MAX_UP). With GRID_UP alone the blocks ended inside water deeper than 6, and past their
     * edge the copy reads a solid block: a roof where the water's surface is. The air's way up was not seen (200 ticks
     * to air from every column) and a rise of more than 5 blocks hit that roof; with the field ending 12 under the top,
     * a walk started on the bottom of water 20 deep swam along the bottom and drowned (PondDeepExploreTest, in the
     * copy).
     */
    static int gridUp(net.minecraft.world.level.Level level, LocalPlayer p) {
        if (!p.isInWater()) return GRID_UP;
        net.minecraft.core.BlockPos.MutableBlockPos pos = p.blockPosition().mutable();
        int y0 = pos.getY();
        for (int k = 1; k <= GRID_MAX_UP; k++) {
            pos.setY(y0 + k);
            if (!level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) return Math.max(GRID_UP, Math.min(GRID_MAX_UP, k + 2));
        }
        return GRID_MAX_UP;
    }

    /** One replan: the search's result, the first tick's heading and the action, the field it ran on, and whether the
     *  plan carries an unsafe mark (its score above the time left from the start by half the mark: the score itself
     *  grows with the walk's length, so it can't be compared with the mark directly). */
    record Out(RecedingSearch.Result result, int action, float yaw, Field field, int tick, long nanos, Layer2View.Frame frame,
               boolean unsafe, float heading) {}

    private static Field field = null;
    private static Future<Field> fieldJob = null;
    private static BlockGrid grid = null;
    private static double gridX, gridY, gridZ;
    private static int gridTick = 0, gridR = 0, gridDown = 0;
    private static int gridUp = 0;
    private static Future<Out> job = null;
    private static Out last = null;
    private static int tick = 0;

    // this tick
    private static boolean controlling = false;
    private static float yaw = 0f, heading = Float.NaN;
    private static int action = RunModel.STOP;
    private static boolean safe = false;

    // measured, for tests and the report
    public static long replans = 0, replanNanos = 0, maxReplanNanos = 0, fieldBuilds = 0, fieldNanos = 0, maxFieldNanos = 0;
    public static long gridBuilds = 0, gridNanos = 0, maxGridNanos = 0, controlledTicks = 0, fallbackTicks = 0;
    public static long waitedNanos = 0, maxWaitNanos = 0;
    public static String lastWhy = "";

    public static void resetStats() {
        replans = replanNanos = maxReplanNanos = fieldBuilds = fieldNanos = maxFieldNanos = 0;
        gridBuilds = gridNanos = maxGridNanos = controlledTicks = fallbackTicks = waitedNanos = maxWaitNanos = 0;
    }

    /** A new walk, or the walk over: drop the field, the plan and the snapshot. */
    public static void reset() {
        recipe = null;
        if (recipeJob != null) recipeJob.cancel(false);
        recipeJob = null;
        recipeTryKey = Long.MIN_VALUE;
        groundY = Double.NaN;
        lastRecipeTry = tick - 1000;   // a try in the last walk doesn't hold this one's first back
        spotsEmpty.clear();
        failedSearches.clear();
        NeoTemplates.dropped.clear();
        hopJumpNone = false;
        dropChain();
        stillTicks = 0;
        recipeRouted = false;
        routeJumpNone = false;
        freeMemory = null;
        field = null;
        if (fieldJob != null) fieldJob.cancel(false);
        fieldJob = null;
        grid = null;
        if (job != null) job.cancel(false);
        job = null;
        last = null;
        controlling = false;
    }

    /** How far the floors a jump search aims at may be (straight line, blocks): as far as a hop's (Walk.HOP_REACH). It was
     *  6, and slime_bounce's landing stood 6.01 from where a walk through the course came to rest on its start (6.0 from
     *  the block's centre, where a walk started there stands): no search, and layer 2 stood until the hop gave up. */
    static final double RECIPE_REACH = 8.0;

    /** A new try at a crossing (a hop of the far-target fallback): the jump search forgets the floor it last searched
     *  from (it searches once per floor, and a try that came onto it moving, or with another field, searched for
     *  nothing), and its wait between tries. */
    public static void newTry() {
        recipeTryKey = Long.MIN_VALUE;
        lastRecipeTry = tick - 1000;
        recipe = null;
        if (recipeJob != null) recipeJob.cancel(false);
        recipeJob = null;
        dropChain();
    }

    private static void dropChain() {
        chainStart = 0;
        if (chainJob != null) chainJob.cancel(false);
        chainJob = null;
        chainBase = null;
        chainNext = null;
        chainedFor = Long.MIN_VALUE;
    }

    /** Whether layer 2 steers this tick. */
    public static boolean controlling() {
        return controlling;
    }

    /** Whether the player's fall now, keys up, ends without hurting, by the copy (layer 2's blocks): onto slime (the
     *  bounce, no damage), into water, or from no more than maxFall. False with no blocks yet. */
    static boolean fallEndsSafely(LocalPlayer p, double maxFall) {
        BlockGrid g = grid;
        if (g == null) return false;
        return Recipe.fallEndsSafely(new GroundPhysics(g, WorldSnapshot.body(p)), WorldSnapshot.state(p), maxFall);
    }

    /** Whether a copy-found jump is being played (it still lands, by the copy from the state each tick). */
    public static boolean playingRecipe() {
        return recipe != null;
    }

    /** Whether this tick's plan was checked safe in the copy (no hurting fall, nothing that hurts). */
    public static boolean safe() {
        return controlling && safe;
    }

    /** The heading this tick (world yaw). */
    public static float yaw() {
        return yaw;
    }

    /** The pitch to send this tick: in water level, or up when swimming up; a glide's own; on land the view's. */
    public static float pitch(LocalPlayer p, float viewPitch) {
        return RunModel.pitch(action, p.isInWater(), viewPitch);
    }

    private static int lastPressed = 0;
    private static int lastPressedTick = -1;

    // a jump the graph has no move for, found in the copy (walking without a route): its inputs, played tick by tick
    private static Recipe recipe = null;
    private static int recipeT = 0, recipeKeys = 0, recipeKeysTick = -1, lastRecipeTry = -1000;
    private static Future<Recipe> recipeJob = null;
    /** The height the player last stood at (on the ground, in water or on a ladder): the gap check's take-off. */
    private static double groundY = Double.NaN;
    /** The free search's memory toward the jump ahead (RunModel.FreeMemory): a new one for each landing. */
    private static volatile RunModel.FreeMemory freeMemory = null;
    private static long recipeTryKey = Long.MIN_VALUE;
    /** Whether the field being built or in use counts sprinting (the gait and food when it was asked for). */
    private static boolean fieldSprint = true;
    public static int recipesFound = 0, recipesPlayed = 0, recipesDropped = 0, recipesFromSpots = 0;
    /** Standing spots, with the landing searched for, whose search found nothing (the copy is deterministic: standing
     *  there again finds nothing again). Cleared with the walk. */
    private static final java.util.Set<Long> spotsEmpty = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Chained jumps (RW 2026-10-14 00:57:04, the jump search from a moving start, "yea hfiel as todo": the walk chains
     * jumps without stopping on each landing, as a player does). While a copy-found jump to a hop's floor is played and
     * has CHAIN_LEAD ticks or more left, the next hop's floor (Walk.nextHopFrom, from where the copy says it lands) and a
     * jump to it from the landing as it comes down, moving, are searched; found in time, the second jump's inputs follow
     * the first's at once, and the walk takes the next hop without stopping (Walk.chainHop). The search's job, the jump
     * it was started from, the next hop, the landing searched from (once per landing).
     */
    private static Future<Recipe> chainJob = null;
    private static Recipe chainBase = null;
    private static double[] chainNext = null;
    private static long chainedFor = Long.MIN_VALUE;
    static final int CHAIN_LEAD = 4;
    /** Where in the recipe being played its last chained part starts (0: none): a chain is searched only once that part
     *  plays, one jump ahead (in detect_c7's first walk the chains ran 7 hops ahead in 20 ticks, the walk's hop taken
     *  for crossed long before the player came near it). */
    private static int chainStart = 0;
    public static int chainsFound = 0, chainsSearched = 0;

    /**
     * Jump searches that found nothing, by the state they ran from (position, speed, the rotation sent), the landings
     * and the terrain then (RW 2026-10-15, "ok": the search is deterministic, so a retry from the same state finds the
     * same nothing and only adds its standing): not run again this walk; a retry from the same state reports nothing at
     * once. The ones skipped are counted.
     */
    private static final java.util.Set<Long> failedSearches = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** KINE_SEARCH_LOG set: each search's start, landings, result and time logged (for the lockstep tests). */
    static final boolean SEARCH_LOG = System.getenv("KINE_SEARCH_LOG") != null;
    public static int searchesSkipped = 0;
    /** The jump search for a hop (walking without a route) found no way, layer 2's own moves aside: Walk gives the hop
     *  up once the player stands still, not after HOP_STUCK no-progress replans. Cleared by the next search. */
    private static volatile boolean hopJumpNone = false;

    /** Whether a jump search is running or a found jump is being played: the walk waits for it before it gives a way up
     *  (RW's triple neo, 2026-10-15: the walk ended 116 ticks after coming to rest at the wall while the search, 6.7 s
     *  with the standing spots, was still running; it found the triple's template after). */
    public static boolean busy() {
        Future<Recipe> j = recipeJob;
        return (j != null && !j.isDone()) || recipe != null;
    }

    private static final java.util.regex.Pattern TEMPLATE_HOW = java.util.regex.Pattern.compile("template (\\S+) (r\\dm?)");

    /** Ticks in a row the player has stood still on the ground (the walk's end of tick). */
    public static int stillTicks() {
        return stillTicks;
    }

    /** Whether the last jump search for a hop found no way (kept until the next search starts). */
    public static boolean hopJumpNone() {
        return hopJumpNone;
    }

    /** The terrain's version: the walk table's builds and block changes (a search on the same terrain finds the same). */
    static long terrain() {
        return WalkTable.builds * 1_000_003L + WalkTable.blockChanges;
    }

    private static long searchKey(MoveState s, float sent, int[][] land) {
        long k = Math.round(s.x * 1000) * 0x9E3779B97F4A7C15L ^ Math.round(s.y * 1000) * 0xC2B2AE3D27D4EB4FL
            ^ Math.round(s.z * 1000) * 0x165667B19E3779F9L ^ Math.round(s.vx * 1000) * 0x27D4EB2F165667C5L
            ^ Math.round(s.vz * 1000) * 0x94D049BB133111EBL ^ Math.round(sent * 10) * 0xBF58476D1CE4E5B9L ^ terrain() * 31;
        for (int[] l : land) k = k * 1_000_003L ^ (((long) l[0] << 40) ^ ((long) l[2] << 16) ^ l[1]);
        return k;
    }

    /** The goal this tick's replans aim at, for layer 2's own run simulated from the standing spots (RunSim). */
    private static RunSim.Goal searchGoal = null;
    /** Ticks in a row the player has stood still on the ground (the walk's end of tick). */
    private static int stillTicks = 0;
    /** The jump search running (or last run) is for a gap of layer 1's route (not walking without a route). */
    private static boolean recipeRouted = false;
    /** A route's gap the walk stood before had no way in the jump search (Walk: on to the hops without waiting). */
    private static boolean routeJumpNone = false;
    /** Standing still this many ticks before a gap of the route within reach: layer 2 has no way over it of its own,
     *  so the jump search looks for one (layer 1 plans gaps and neos layer 2's actions don't make; it stood there for
     *  4 no-progress replans, 245 ticks, before the walk hopped on: 12 times in walk 2 of final3d_pk39). */
    static final int STALL_TICKS = 10;

    /** Whether a route's gap the walk stood before had no way in the jump search; cleared by asking. */
    public static boolean routeJumpNone() {
        boolean b = routeJumpNone;
        routeJumpNone = false;
        return b;
    }
    public static String lastRecipe = "";

    /** The keys this tick at the rotation sent now. */
    public static int keys(LocalPlayer p) {
        if (recipeKeysTick == tick) return recipeKeys;
        int last = lastPressedTick == tick - 1 || lastPressedTick == tick ? lastPressed : 0;
        // a free action's keys come from its movement heading (its view may be 45 degrees off it: a strafe)
        float h = RunModel.isFree(action) && !Float.isNaN(heading) ? heading : yaw;
        int k = RunModel.keys(action, h, p.getYRot(), p.isInWater(), p.onGround(), p.isFallFlying(), last);
        lastPressed = k;
        lastPressedTick = tick;
        return k;
    }

    public static RecedingSearch.Effort effort() {
        return TUNER.effort();
    }

    public static Field field() {
        return field;
    }

    /** The latest replan's display frame, or null. */
    static Layer2View.Frame frame() {
        Out o = last;
        return o == null ? null : o.frame();
    }

    /** The start of a tick (Walk.steer): take the plan made from the end of the last tick. Whether it steers. */
    static boolean steer(LocalPlayer p) {
        controlling = false;
        if (!Settings.walkLayer2) return false;
        if (playRecipe(p)) return true;
        if (recipeJob != null && !recipeJob.isDone() && p.onGround()) {
            // a jump search runs from the state it started in (standing): stand until it is back
            action = RunModel.STOP;
            yaw = KillAura.lastSentYaw(p);
            heading = Float.NaN;
            safe = false;
            controlling = true;
            controlledTicks++;
            lastWhy = "jump search";
            return true;
        }
        if (job != null && !job.isDone()) {
            // in real play the worker has had the whole time between ticks; in a lockstep test this keeps runs the same
            long w0 = System.nanoTime();
            try { job.get(START.budgetNanos + 10_000_000L, TimeUnit.NANOSECONDS); } catch (Exception ignored) { }
            long waited = System.nanoTime() - w0;
            waitedNanos += waited;
            maxWaitNanos = Math.max(maxWaitNanos, waited);
        }
        harvest();
        Out o = last;
        if (o == null || o.result().plan() == null) return fallback("no plan yet");
        if (o.unsafe()) return fallback("every run unsafe");
        int at = tick - o.tick();
        int[] plan = o.result().plan();
        // the first segment's heading was fixed at the plan's first tick; later ticks of the same segment keep it
        if (at < 0 || at >= START.hold || plan[at] != plan[0]) return fallback("plan too old");
        action = plan[at];
        yaw = o.yaw();
        heading = o.heading();
        safe = !o.unsafe();
        controlling = true;
        controlledTicks++;
        lastWhy = "";
        return true;
    }

    /**
     * Walking without a route: a jump found in the copy to a floor the graph joins to the goal (Recipe), played tick by
     * tick while the copy, from the state now, still lands it; dropped otherwise. Whether it steers this tick.
     */
    private static boolean playRecipe(LocalPlayer p) {
        if (recipeJob != null && recipeJob.isDone()) {
            try {
                Recipe r = recipeJob.get();
                if (r != null && r.found()) { recipe = r; recipeT = 0; chainStart = 0; recipesFound++; lastRecipe = r.how; if (r.approachTicks > 0) recipesFromSpots++; }
                else if (recipeRouted) routeJumpNone = true;
                else hopJumpNone = true;
            } catch (Exception ex) {
                Kine.LOGGER.warn("Kine walk layer 2: recipe search failed", ex);
            }
            recipeJob = null;
        }
        if (chainJob != null && chainJob.isDone()) {
            Recipe c = null;
            try { c = chainJob.get(); } catch (Exception ignored) { }
            chainJob = null;
            // in time: the jump being played is still the one the search started from, and it hasn't landed yet
            if (c != null && recipe != null && recipe == chainBase && recipeT < recipe.ticks && Walk.chainHop(chainNext)) {
                chainStart = recipe.ticks;
                recipe = Recipe.then(recipe, c);
                chainsFound++;
                lastRecipe = recipe.how;
            }
            chainBase = null;
        }
        Recipe r = recipe;
        if (r == null || grid == null) return false;
        if (recipeT >= r.ticks) { recipe = null; recipesPlayed++; return false; }
        GroundPhysics phys = new GroundPhysics(grid, WorldSnapshot.body(p));
        if (!r.stillLands(phys, WorldSnapshot.state(p), recipeT, r.lx, r.ly, r.lz)) {
            recipe = null;
            recipesDropped++;
            lastRecipeTry = tick;
            // a template that dropped in play is not fitted again this walk, and the floor may be searched again (RW's
            // triple neo: a thin neo_chained template fitted first, dropped, and no search followed on that block)
            java.util.regex.Matcher m = TEMPLATE_HOW.matcher(r.how == null ? "" : r.how);
            if (m.find()) { NeoTemplates.dropped.add(m.group(1) + " " + m.group(2)); recipeTryKey = Long.MIN_VALUE; }
            return false;
        }
        action = RunModel.STOP;
        yaw = r.yaw[recipeT];
        recipeKeys = r.keys[recipeT];
        recipeKeysTick = tick;
        recipeT++;
        safe = true;
        controlling = true;
        controlledTicks++;
        lastWhy = "recipe";
        return true;
    }

    /** Walking without a route, standing on a floor the graph doesn't join to the goal: search the copy for a jump to
     *  one it does join, nearby (at most once per floor stood on, and a second after a dropped one). */
    private static void maybeSearchRecipe(LocalPlayer p, Field f, BlockGrid g, Body body) {
        // a jump the graph has no move for is parkour: only with Parkour on
        if (!Settings.walkParkour || recipe != null || recipeJob != null || f.joined == null || !p.onGround()) return;
        if (f.joinedAt(p.getX(), p.getY(), p.getZ())) return;
        if (!readyToSearch(p)) return;
        int[] cand = f.joinedNear(p.getX(), p.getY(), p.getZ(), RECIPE_REACH, 4, true);
        if (cand.length == 0) return;
        int[][] land = new int[cand.length][];
        for (int k = 0; k < cand.length; k++)
            land[k] = new int[]{(int) Math.floor(f.x(cand[k])), (int) Math.round(f.y(cand[k])) - 1, (int) Math.floor(f.z(cand[k]))};
        submitSearch(p, f, g, body, land, false);
    }

    /**
     * Walking a route, standing still STALL_TICKS before a gap of it within reach (layer 1's GAP move, a jump or a neo
     * layer 2's own actions found no way over): the jump search toward that gap's landing, as walking without a route
     * does (once per floor stood on). Walking without a route on a floor the graph joins to the goal, the same with the
     * field's jump ahead (its GAP, or the joined floor in reach); a search that finds nothing there counts for the hop.
     */
    private static void maybeSearchRouteGap(LocalPlayer p, Planner.Plan plan, Field f, BlockGrid g, Body body) {
        if (!Settings.walkParkour || recipe != null || recipeJob != null || !p.onGround() || stillTicks < STALL_TICKS) return;
        double[] gap = jumpAhead(plan, f, p.getX(), p.getY(), p.getZ(), RECIPE_REACH);
        if (gap == null) return;
        if (!readyToSearch(p)) return;
        int[][] land = {{(int) Math.floor(gap[0]), (int) Math.round(gap[1]) - 1, (int) Math.floor(gap[2])}};
        submitSearch(p, f, g, body, land, !plan.direct);
    }

    /** A chained jump's search, while a jump to a hop's floor plays (see chainJob). */
    private static void maybeChain(LocalPlayer p, BlockGrid g, Body body) {
        Recipe r = recipe;
        if (r == null || g == null || chainJob != null || !Settings.walkParkour || r.ticks - recipeT < CHAIN_LEAD
            || recipeT < chainStart) return;
        long key = ((long) r.lx << 40) ^ ((long) r.lz << 16) ^ r.ly;
        if (key == chainedFor) return;
        chainedFor = key;
        // where the jump being played comes down, by the copy from the state now
        GroundPhysics phys = new GroundPhysics(g, body);
        MoveState s = WorldSnapshot.state(p);
        for (int t = recipeT; t < r.ticks; t++) phys.step(s, r.keys[t], r.yaw[t], 0f);
        if (!s.onGround) return;   // onto a climbable: it holds on there
        double[] next = Walk.nextHopFrom(r.lx, r.lz, s.x, s.y, s.z);
        if (next == null) return;
        int[] l = {(int) next[0], (int) Math.round(next[2]) - 1, (int) next[1]};
        float yaw0 = r.yaw[r.ticks - 1];
        chainBase = r;
        chainNext = next;
        chainsSearched++;
        // for layer 2's own run from the moving landing when the jump search's form has none (a neo layer 2 makes
        // arriving at speed: the course's neo_winged, neo_headhitter, neo_nix were crossed so before the walk came to
        // rest on each hop's floor): the next hop's plan and field, made on the job's thread from this snapshot
        View v = WalkTable.snapshot();
        Costs costs = Walk.costs();
        double margin = Walk.margin();
        boolean canSprint = p.getFoodData().getFoodLevel() > 6;
        long fromNode = (long) next[4];
        int gx = (int) next[0], gz = (int) next[1];
        double gy = next[2];
        RunSim.Goal goal2 = new RunSim.Goal(gx, gz, gy, true, p.getXRot(), costs.maxFall, Settings.walkParkour, Settings.walkFreeJumps);
        float pitch0 = KillAura.lastSentPitch(p);
        chainJob = RECIPES.submit(() -> {
            GroundPhysics ph = new GroundPhysics(g, body);
            float b = Recipe.yawTo(s.x, s.z, l[0] + 0.5, l[2] + 0.5);
            Recipe c = Recipe.searchParallel(SEARCH, SEARCH_THREADS, ph, s, yaw0, l[0], l[1], l[2], b - 90f, b + 90f, 6f, 8, 8, 60);
            if (c.found()) return c;
            Planner.Plan dp = Planner.direct(v, fromNode, gx, gz, gy, costs, margin, canSprint);
            if (dp == null) return null;
            Field f2 = Field.direct(v, costs, canSprint, true, false, dp.id, dp.result.path, gx, gz, gy);
            return RunSim.run(f2, g, body, goal2, s, yaw0, pitch0, new int[][]{l});
        });
    }

    /**
     * The landing of a jump ahead within reach: the field's (Field.jumpAhead: along its cheapest moves, the first GAP), or,
     * when the field has none, the route's own next GAP move whose take-off is the floor stood on (within 1.5). On a
     * short route to the floor nearest an unreachable goal the floor stood on is one of the field's sources (the route's
     * last stretch is), so it has no move on to follow: layer 2 had no jump to aim its free actions at and stood before
     * the gap (the course's ladder_4b, 215 ticks in full3_c1's walk 2).
     */
    static double[] jumpAhead(Planner.Plan plan, Field f, double x, double y, double z, double reach) {
        double[] a = f.jumpAhead(x, y, z, reach);
        if (a != null || plan == null || plan.direct) return a;
        PathSearch.Result r = plan.result;
        for (int i = 1; i < r.path.length; i++) {
            if (r.moves[i] != Graph.GAP) continue;
            double tx = View.nodeX(r.path[i - 1]) + 0.5, tz = View.nodeZ(r.path[i - 1]) + 0.5;
            double lx = View.nodeX(r.path[i]) + 0.5, lz = View.nodeZ(r.path[i]) + 0.5;
            if (Math.hypot(tx - x, tz - z) > 1.5 || Math.abs(r.pathY[i - 1] - y) > 0.6) continue;
            if (Math.hypot(lx - x, lz - z) > reach + 1.0) return null;
            return new double[]{lx, r.pathY[i], lz};
        }
        return null;
    }

    /** From a standing start only (the walk stands while it searches: steer), a second after a dropped jump, once per
     *  floor stood on; marks the floor tried. */
    private static boolean readyToSearch(LocalPlayer p) {
        var dv = p.getDeltaMovement();
        if (dv.x * dv.x + dv.z * dv.z > RunModel.STILL2) return false;
        if (tick - lastRecipeTry < 20) return false;
        long key = ((long) p.getBlockX() << 40) ^ ((long) p.getBlockZ() << 16) ^ p.getBlockY();
        if (key == recipeTryKey) return false;
        recipeTryKey = key;
        lastRecipeTry = tick;
        return true;
    }

    /**
     * The jump search on its own thread: from where the walk stands to each landing in turn (the first that has a way);
     * if none has one, from the standing spots on the block it stands on (Recipe.spots: the centre, back for a run up,
     * the sides, the corners) and run-up spots further back on the floor behind it (Recipe.backSpots: 1 to 8 blocks),
     * each reached by a walk there and a stop (Recipe.approach): at each, the jump search from
     * the state the copy says it ends in, and if that finds nothing, layer 2's own run from there simulated in the copy
     * (RunSim: a curved run-up round a wall's corner, which the jump search's single heading can't make); the spots side
     * by side on SPOT_THREADS threads, the first in that order with a way giving the jump, its walk there first (RW
     * 2026-10-14 22:36:05, #67: "the repostion and try many jump searches fomr teh blcok", and "ok" to layer 2's run at
     * each spot). If no spot has one either way, a neo template that lands (NeoTemplates.fit: a sequence found offline,
     * fitted where the walk stands). Spots whose search found nothing are not searched again this walk.
     */
    private static void submitSearch(LocalPlayer p, Field f, BlockGrid g, Body body, int[][] land, boolean routed) {
        MoveState s = WorldSnapshot.state(p);
        float sent = KillAura.lastSentYaw(p), sentPitch = KillAura.lastSentPitch(p);
        int bx = s.hasSupport ? s.supX : p.getBlockX(), bz = s.hasSupport ? s.supZ : p.getBlockZ();
        int by = s.hasSupport ? s.supY : (int) Math.floor(s.y - 1e-3);
        RunSim.Goal goal = searchGoal;
        recipeRouted = routed;
        hopJumpNone = false;
        long key = searchKey(s, sent, land);
        if (failedSearches.contains(key)) {
            // the same search from the same state on the same terrain found nothing before: nothing again, at once
            searchesSkipped++;
            if (routed) routeJumpNone = true; else hopJumpNone = true;
            return;
        }
        recipeJob = RECIPES.submit(() -> {
            long t0 = System.nanoTime();
            Recipe result = null;
            try {
                result = searchAll(s, sent, sentPitch, g, body, f, goal, land, bx, by, bz);
                return result;
            } finally {
                if (SEARCH_LOG) {
                    StringBuilder ls = new StringBuilder();
                    for (int[] l : land) ls.append(' ').append(l[0]).append(',').append(l[1]).append(',').append(l[2]);
                    int[][] cl = climbs(f, g, s);
                    if (cl.length > 0) ls.append(" climbs");
                    for (int[] l : cl) ls.append(' ').append(l[0]).append(',').append(l[1]).append(',').append(l[2]);
                    Kine.LOGGER.info(String.format(java.util.Locale.ROOT, "Kine walk layer 2: search from %.3f %.3f %.3f (block %d %d %d, %s) to%s: %s in %.0f ms",
                        s.x, s.y, s.z, bx, by, bz, routed ? "routed" : "hop", ls, result == null ? "none" : result.how,
                        (System.nanoTime() - t0) / 1e6));
                }
                if (result == null) failedSearches.add(key);
                lastSearchNanos = System.nanoTime() - t0;
                searchNanos += lastSearchNanos;
                maxSearchNanos = Math.max(maxSearchNanos, lastSearchNanos);
                searches++;
            }
        });
    }

    /** The search itself (submitSearch's job): from here, the spots, the templates; the first way found, or null. */
    private static Recipe searchAll(MoveState s, float sent, float sentPitch, BlockGrid g, Body body, Field f, RunSim.Goal goal,
                                    int[][] land, int bx, int by, int bz) throws InterruptedException {
        // a climb template first, onto a ladder chain's far ladder (found offline for that layout: ladder_90_spiral,
        // whose hops went ladder by ladder and hung on the second; the jump search finds the first hop and stops there)
        for (int[] l : climbs(f, g, s)) {
            Recipe t = NeoTemplates.fit(new GroundPhysics(g, body), s, sent, bx, by, bz, l, true);
            if (t != null) return t;
        }
        Recipe here = searchLands(new GroundPhysics(g, body), s, sent, land, true);
        if (here != null) return here;
        double[][] near = Recipe.spots(bx, bz, s.x, s.z, land[0][0] + 0.5, land[0][2] + 0.5);
        double[][] back = Recipe.backSpots(new GroundPhysics(g, body), bx, by, bz, s.y, land[0][0] + 0.5, land[0][2] + 0.5);
        java.util.List<java.util.concurrent.Callable<Recipe>> jobs = new java.util.ArrayList<>();
        java.util.List<Long> keys = new java.util.ArrayList<>();
        for (int si = 0; si < near.length + back.length; si++) {
            double[] sp = si < near.length ? near[si] : back[si - near.length];
            boolean far = si >= near.length;
            long k = spotKey(sp[0], s.y, sp[1], land[0]);
            if (spotsEmpty.contains(k)) continue;
            keys.add(k);
            jobs.add(() -> {
                GroundPhysics ph = new GroundPhysics(g, body);
                Recipe a = far ? Recipe.approachFar(ph, s, sent, sp[0], sp[1]) : Recipe.approach(ph, s, sent, sp[0], sp[1]);
                if (a == null) return null;
                MoveState m = Recipe.after(ph, s, a);
                float view = a.yaw[a.ticks - 1];
                Recipe j = searchLands(ph, m, view, land, false);
                if (j == null && goal != null) j = RunSim.run(f, g, body, goal, m, view, sentPitch, land);
                return j == null ? null : Recipe.concat(a, j);
            });
        }
        if (!jobs.isEmpty()) {
            java.util.List<Future<Recipe>> fs = SPOTS.invokeAll(jobs);
            Recipe found = null;
            for (int i = 0; i < fs.size(); i++) {
                Recipe r;
                try { r = fs.get(i).get(); } catch (Exception ex) { r = null; }
                if (r == null) spotsEmpty.add(keys.get(i));
                else if (found == null) found = r;
            }
            if (found != null) return found;
        }
        // a template found offline (a neo, a long jump, a ladder chain) when neither search makes it from any spot:
        // fitted from the block the walk stands on and then from the floors of its level near it (its take-off: the walk
        // may rest short of it, as on RW's triple neo, 3 blocks back on the walkway), onto the search's landings and
        // every joined floor nearer the goal within reach (RW's triple's landing, right past the wall, was not among the
        // search's four). (Tried before the spots, de95110, the course's unbroken walks fell from 87 to 78 and 19:
        // templates took jumps the spots make; after them, as before.)
        return templates(f, g, body, s, sent, land, bx, by, bz);
    }

    private static Recipe templates(Field f, BlockGrid g, Body body, MoveState s, float sent, int[][] land, int bx, int by, int bz) {
        // first as before RW's triple neo (the block stood on, the search's landings, the file's order: what the course's
        // walks made 87 of 121 with, c20); then the wider pass. Wider at once, the walks fell to 78 and 19 (templates
        // before the spots, de95110) and to 31 and 81 (after them, a2f2c36: a template from another key or landing,
        // more robust in the generator but not here, taken where the narrow pass took the one that plays)
        GroundPhysics phys0 = new GroundPhysics(g, body);
        for (int[] l : land) {
            Recipe t0 = NeoTemplates.fit(phys0, s, sent, bx, by, bz, l);
            if (t0 != null) return t0;
        }
        java.util.LinkedHashMap<Long, int[]> lands = new java.util.LinkedHashMap<>();
        for (int[] l : land) lands.put(cellKey(l), l);
        java.util.LinkedHashMap<Long, int[]> keys = new java.util.LinkedHashMap<>();
        keys.put(cellKey(new int[]{bx, by, bz}), new int[]{bx, by, bz});
        try {
            if (f != null) {
                for (int[] l : f.progressNear(s.x, s.y, s.z, RECIPE_REACH, 32)) lands.putIfAbsent(cellKey(l), l);
                for (int[] k : f.levelNear(s.x, s.y, s.z, 6.0, 24)) keys.putIfAbsent(cellKey(k), k);
            }
        } catch (RuntimeException ignored) { }
        // the block stood on first (the common case, and the cheap one: no walk to another take-off), then the others
        GroundPhysics phys = new GroundPhysics(g, body);
        java.util.List<int[]> own = java.util.List.of(new int[]{bx, by, bz});
        Recipe t = NeoTemplates.fitAny(phys, s, sent, own, lands.values());
        if (t != null) return t;
        keys.remove(cellKey(own.get(0)));
        return keys.isEmpty() ? null : NeoTemplates.fitAny(phys, s, sent, keys.values(), lands.values());
    }

    /** The climb templates' landings: climbable cells within RECIPE_REACH and from 1 below to 6 above the feet, the
     *  field's joined ones first, then the snapshot's (a ladder chain's far ladders are not the field's: on
     *  ladder_90_spiral it had only the second), each named as the searches name a landing (the cell's feet, rounded,
     *  less one). At most 16. */
    private static int[][] climbs(Field f, BlockGrid g, MoveState s) {
        java.util.LinkedHashMap<Long, int[]> out = new java.util.LinkedHashMap<>();
        try {
            if (f != null) for (int[] l : f.climbsNear(s.x, s.y, s.z, RECIPE_REACH, 6.0, 8)) out.put(cellKey(l), l);
        } catch (RuntimeException ignored) { }
        if (g != null) {
            int px = (int) Math.floor(s.x), py = (int) Math.floor(s.y), pz = (int) Math.floor(s.z), r = (int) RECIPE_REACH;
            java.util.List<int[]> cells = new java.util.ArrayList<>();
            for (int y = py - 1; y <= py + 6; y++)
                for (int x = px - r; x <= px + r; x++)
                    for (int z = pz - r; z <= pz + r; z++) {
                        if (!g.inside(x, y, z) || !g.get(x, y, z).is(ai.rrw.kine.move.BlockInfo.CLIMBABLE)) continue;
                        cells.add(new int[]{x, y - 1, z});
                    }
            cells.sort(java.util.Comparator.comparingDouble(c -> Math.hypot(c[0] + 0.5 - s.x, c[2] + 0.5 - s.z)));
            for (int[] c : cells) out.putIfAbsent(cellKey(c), c);
        }
        return out.values().stream().limit(16).toArray(int[][]::new);
    }

    private static long cellKey(int[] l) {
        return ((long) l[0] << 40) ^ ((long) l[2] << 16) ^ (l[1] & 0xFFFF);
    }

    /** The jump searches' time (on their own thread, the walk standing): the last, the sum, the longest, how many. */
    public static volatile long lastSearchNanos = 0, searchNanos = 0, maxSearchNanos = 0;
    public static volatile int searches = 0;

    /** The copy's jump search from s (the view at sent) to each landing in turn; the first found, or null. parallel:
     *  each search split over the SEARCH threads. */
    private static Recipe searchLands(GroundPhysics phys, MoveState s, float sent, int[][] land, boolean parallel) {
        boolean sprint = Walk.gait() >= Settings.GAIT_SPRINT;   // the gait: at Walk, walking jumps only
        for (int[] l : land) {
            float bearing = Recipe.yawTo(s.x, s.z, l[0] + 0.5, l[2] + 0.5);
            Recipe r = parallel
                ? Recipe.searchParallel(SEARCH, SEARCH_THREADS, phys, s, sent, l[0], l[1], l[2], bearing - 90f, bearing + 90f, 6f, 8, 8, 60, sprint)
                : Recipe.search(phys, s, sent, l[0], l[1], l[2], bearing - 90f, bearing + 90f, 6f, 8, 8, 60, sprint);
            if (r.found()) return r;
        }
        return null;
    }

    private static long spotKey(double x, double y, double z, int[] l) {
        long k = Math.round(x * 20) * 0x9E3779B97F4A7C15L ^ Math.round(z * 20) * 0xC2B2AE3D27D4EB4FL ^ Math.round(y * 20) * 0x165667B19E3779F9L;
        return k ^ ((long) l[0] << 40) ^ ((long) l[2] << 16) ^ l[1];
    }

    private static boolean fallback(String why) {
        lastWhy = why;
        fallbackTicks++;
        return false;
    }

    private static void harvest() {
        if (job == null || !job.isDone()) return;
        try {
            Out o = job.get();
            if (o != null) {
                last = o;
                replans++;
                replanNanos += o.nanos();
                maxReplanNanos = Math.max(maxReplanNanos, o.nanos());
                TUNER.record(o.result().stats());
            }
        } catch (Exception ex) {
            Kine.LOGGER.warn("Kine walk layer 2: search failed", ex);
        }
        job = null;
    }

    /** The end of a tick while walking (Walk.endTick): the field for the route, the blocks, and the next replan. */
    static void endTick(Minecraft mc, Planner.Plan plan, int[] dest, double destY) {
        endTick(mc, plan, dest, destY, false);
    }

    /** As above; stand: the destination counts as reached only standing still on it (a hop's floor: RunModel.stand). */
    static void endTick(Minecraft mc, Planner.Plan plan, int[] dest, double destY, boolean stand) {
        tick++;
        LocalPlayer p = mc.player;
        if (!Settings.walkLayer2 || plan == null || p == null || mc.level == null || dest == null) return;
        // the field: built once a route's plan comes in, and again as the player gets half way along its corridor
        if (fieldJob != null && fieldJob.isDone()) {
            try {
                Field f = fieldJob.get();
                if (f != null) {
                    field = f;
                    fieldBuilds++;
                    fieldNanos += f.buildNanos;
                    maxFieldNanos = Math.max(maxFieldNanos, f.buildNanos);
                }
            } catch (Exception ex) {
                Kine.LOGGER.warn("Kine walk layer 2: field failed", ex);
            }
            fieldJob = null;
        }
        long[] path = plan.result.path;
        boolean stale = field == null || field.path != path || fieldSprint != Walk.canSprint(p)
            || field.to < path.length - 1 && Field.nearestIndex(path, p.getX(), p.getZ()) > (field.from + field.to) / 2;
        if (stale && fieldJob == null && path.length > 0) {
            View v = WalkTable.snapshot();
            double px = p.getX(), pz = p.getZ();
            boolean canSprint = Walk.canSprint(p);
            fieldSprint = canSprint;
            boolean reached = plan.result.found && !plan.result.viaExit && !plan.result.partial;
            Costs costs = Walk.costs();
            boolean glides = Settings.walkElytraHops && WorldSnapshot.body(p).canGlide;
            boolean gaps = Settings.walkParkour;
            fieldJob = plan.direct
                ? WORKER.submit(() -> Field.direct(v, costs, canSprint, gaps, glides, plan.id, path, dest[0], dest[1], destY))
                : WORKER.submit(() -> Field.build(v, costs, canSprint, gaps, glides, plan.id, path, plan.route, px, pz, dest[0], dest[1],
                    destY, reached));
        }
        Field f = field;
        if (f == null) return;

        // the blocks around the player
        long t0 = System.nanoTime();
        int down = GRID_DOWN, gr = GRID_R;
        Route rt = plan.route;
        for (int k = 0; k < rt.n; k++) {
            double hd = Math.hypot(rt.x[k] - p.getX(), rt.z[k] - p.getZ());
            if (hd <= GRID_R + 8) down = Math.max(down, (int) Math.ceil(p.getY() - rt.y[k]) + 3);
            if (rt.move[k] == Route.GLIDE && hd <= 32) gr = Math.max(gr, (int) Math.ceil(hd) + 8);
        }
        down = Math.min(down, GRID_MAX_DOWN);
        gr = Math.min(gr, GRID_MAX_R);
        int up = gridUp(mc.level, p);
        double dx = p.getX() - gridX, dz = p.getZ() - gridZ, dy = p.getY() - gridY, move = gridR / 4.0;
        if (grid == null || dx * dx + dz * dz > move * move || Math.abs(dy) > 2.0 || tick - gridTick >= GRID_AGE
            || down > gridDown || gr > gridR || up > gridUp) {
            grid = WorldSnapshot.around(mc.level, p, gr, down, up);
            gridX = p.getX(); gridY = p.getY(); gridZ = p.getZ();
            gridTick = tick;
            gridR = gr;
            gridDown = down;
            gridUp = up;
            long gn = System.nanoTime() - t0;
            gridBuilds++;
            gridNanos += gn;
            maxGridNanos = Math.max(maxGridNanos, gn);
        }

        var dv = p.getDeltaMovement();
        stillTicks = p.onGround() && dv.x * dv.x + dv.z * dv.z <= RunModel.STILL2 ? stillTicks + 1 : 0;
        maybeChain(p, grid, WorldSnapshot.body(p));
        searchGoal = new RunSim.Goal(dest[0], dest[1], destY, stand, p.getXRot(), Walk.costs().maxFall, Settings.walkParkour,
            Settings.walkFreeJumps);
        if (plan.direct && grid != null) maybeSearchRecipe(p, f, grid, WorldSnapshot.body(p));
        // walking a route, or without one on a floor the graph joins to the goal (a gap move layer 2's own actions
        // didn't make: end_rod_jump's rod, joined by the graph's GAP): standing still before the jump, the search
        if (grid != null && recipeJob == null) maybeSearchRouteGap(p, plan, f, grid, WorldSnapshot.body(p));
        if (job != null && !job.isDone()) return;   // still on the last one
        harvest();
        BlockGrid g = grid;
        Body body = WorldSnapshot.body(p);
        body.glide = Settings.walkElytraHops && body.canGlide;   // elytra hops: off by default (they wear the elytra)
        MoveState s = WorldSnapshot.state(p);
        RunWorld root = new RunWorld(s, KillAura.lastSentYaw(p), KillAura.lastSentPitch(p));
        // the take-off for the gap check (Parkour off): where the player stood last, not where a run's first tick in the
        // air happens to be (from 0.42 up, a floor one block under the take-off looked like a gap)
        if (p.onGround() || p.isInWater() || p.onClimbable()) groundY = p.getY();
        root.takeoffY = groundY;
        float viewPitch = p.getXRot();
        int[] previous = last != null && last.result().plan() != null ? last.result().plan() : null;
        int shift = last != null ? tick - last.tick() : 0;
        RecedingSearch.Effort e = TUNER.effort().copy();
        if (s.inWater) e.hold = RunModel.WATER_HOLD;
        long seed = tick * 0x9E3779B97F4A7C15L;
        int at = tick;
        boolean display = Layer2View.frameDue(tick);
        if (Settings.walkLayer2Display) Layer2View.ensureRegistered();
        double maxFall = Walk.costs().maxFall;
        boolean parkour = Settings.walkParkour;
        double goalY = destY;
        // a jump ahead (the field's next GAP within 5 blocks, or walking without a route a joined floor in reach): free
        // actions aimed at its landing (with Parkour on: the jumps are parkour's)
        double[] target = parkour && Settings.walkFreeJumps ? jumpAhead(plan, f, p.getX(), p.getY(), p.getZ(), 5.0) : null;
        RunModel.FreeMemory mem = null;
        if (target != null) {
            long key = RunModel.FreeMemory.key(target[0], target[1], target[2]);
            mem = freeMemory;
            if (mem == null || mem.target != key) freeMemory = mem = new RunModel.FreeMemory(key);
        }
        RunModel.FreeMemory freeMem = mem;
        int gait = Walk.gait();
        job = WORKER.submit(() -> {
            long r0 = System.nanoTime();
            RunModel model = new RunModel(new GroundPhysics(g, body), f, dest[0], dest[1], viewPitch, maxFall);
            model.parkour = parkour;
            model.goalY = goalY;
            model.cap = gait;
            model.stand = stand && model.arrived(s);   // settling once on the hop's floor (RunModel.stand)
            if (target != null) { model.targetX = target[0]; model.targetY = target[1]; model.targetZ = target[2]; }
            model.freeMemory = freeMem;
            model.now = at;
            if (body.glide) model.gaits = RunModel.GLIDE_GAITS;
            RecedingSearch<RunWorld> rs = new RecedingSearch<>(model);
            Layer2View.Recorder rec = display ? new Layer2View.Recorder(e.hold) : null;
            if (rec != null) rs.observe(rec);
            RecedingSearch.Result r = RunModel.plan(model, rs, root, previous, shift, e, seed);
            long nanos = System.nanoTime() - r0;
            int a = RunModel.STOP;
            float y = root.sentYaw, hd = Float.NaN;
            if (r.plan() != null) {
                RunWorld w = root.copy();
                model.step(w, r.plan()[0], 0, null);
                a = r.plan()[0];
                y = w.firstYaw;
                hd = w.firstHeading;
            }
            Layer2View.Frame frame = rec != null ? rec.frame(model, root, r) : null;
            boolean unsafe = r.plan() == null || r.score() - model.estimate(root) >= RunModel.UNSAFE / 2;
            return new Out(r, a, y, f, at, nanos, frame, unsafe, hd);
        });
    }
}
