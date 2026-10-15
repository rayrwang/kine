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
    public static int recipesFound = 0, recipesPlayed = 0, recipesDropped = 0;
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
                if (r != null && r.found()) { recipe = r; recipeT = 0; recipesFound++; lastRecipe = r.how; }
            } catch (Exception ex) {
                Kine.LOGGER.warn("Kine walk layer 2: recipe search failed", ex);
            }
            recipeJob = null;
        }
        Recipe r = recipe;
        if (r == null || grid == null) return false;
        if (recipeT >= r.ticks) { recipe = null; recipesPlayed++; return false; }
        GroundPhysics phys = new GroundPhysics(grid, WorldSnapshot.body(p));
        if (!r.stillLands(phys, WorldSnapshot.state(p), recipeT, r.lx, r.ly, r.lz)) {
            recipe = null;
            recipesDropped++;
            lastRecipeTry = tick;
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
        // from a standing start only: the walk stands while it searches (steer), so the state it searched from holds
        var dv = p.getDeltaMovement();
        if (dv.x * dv.x + dv.z * dv.z > 1e-4) return;
        if (tick - lastRecipeTry < 20) return;
        if (f.joinedAt(p.getX(), p.getY(), p.getZ())) return;
        long key = ((long) p.getBlockX() << 40) ^ ((long) p.getBlockZ() << 16) ^ p.getBlockY();
        if (key == recipeTryKey) return;
        recipeTryKey = key;
        lastRecipeTry = tick;
        int[] cand = f.joinedNear(p.getX(), p.getY(), p.getZ(), RECIPE_REACH, 3);
        if (cand.length == 0) return;
        MoveState s = WorldSnapshot.state(p);
        float sent = KillAura.lastSentYaw(p);
        int[][] land = new int[cand.length][];
        for (int k = 0; k < cand.length; k++)
            land[k] = new int[]{(int) Math.floor(f.x(cand[k])), (int) Math.round(f.y(cand[k])) - 1, (int) Math.floor(f.z(cand[k]))};
        boolean sprint = Walk.gait() >= Settings.GAIT_SPRINT;   // the gait: at Walk, walking jumps only
        recipeJob = RECIPES.submit(() -> {
            GroundPhysics phys = new GroundPhysics(g, body);
            for (int[] l : land) {
                float bearing = Recipe.yawTo(s.x, s.z, l[0] + 0.5, l[2] + 0.5);
                Recipe r = Recipe.search(phys, s, sent, l[0], l[1], l[2], bearing - 90f, bearing + 90f, 6f, 8, 8, 60, sprint);
                if (r.found()) return r;
            }
            return null;
        });
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
        double dx = p.getX() - gridX, dz = p.getZ() - gridZ, dy = p.getY() - gridY, move = gridR / 4.0;
        if (grid == null || dx * dx + dz * dz > move * move || Math.abs(dy) > 2.0 || tick - gridTick >= GRID_AGE
            || down > gridDown || gr > gridR) {
            grid = WorldSnapshot.around(mc.level, p, gr, down, GRID_UP);
            gridX = p.getX(); gridY = p.getY(); gridZ = p.getZ();
            gridTick = tick;
            gridR = gr;
            gridDown = down;
            long gn = System.nanoTime() - t0;
            gridBuilds++;
            gridNanos += gn;
            maxGridNanos = Math.max(maxGridNanos, gn);
        }

        if (plan.direct && grid != null) maybeSearchRecipe(p, f, grid, WorldSnapshot.body(p));
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
        double[] target = parkour && Settings.walkFreeJumps ? f.jumpAhead(p.getX(), p.getY(), p.getZ(), 5.0) : null;
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
