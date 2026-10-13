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
               boolean unsafe) {}

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
    private static float yaw = 0f;
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
        field = null;
        if (fieldJob != null) fieldJob.cancel(false);
        fieldJob = null;
        grid = null;
        if (job != null) job.cancel(false);
        job = null;
        last = null;
        controlling = false;
    }

    /** Whether layer 2 steers this tick. */
    public static boolean controlling() {
        return controlling;
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

    /** The keys this tick at the rotation sent now. */
    public static int keys(LocalPlayer p) {
        int last = lastPressedTick == tick - 1 || lastPressedTick == tick ? lastPressed : 0;
        int k = RunModel.keys(action, yaw, p.getYRot(), p.isInWater(), p.onGround(), p.isFallFlying(), last);
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
        safe = !o.unsafe();
        controlling = true;
        controlledTicks++;
        lastWhy = "";
        return true;
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
    static void endTick(Minecraft mc, Planner.Plan plan, int[] dest) {
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
        boolean stale = field == null || field.path != path
            || field.to < path.length - 1 && Field.nearestIndex(path, p.getX(), p.getZ()) > (field.from + field.to) / 2;
        if (stale && fieldJob == null && path.length > 0) {
            View v = WalkTable.snapshot();
            double px = p.getX(), pz = p.getZ();
            boolean canSprint = p.getFoodData().getFoodLevel() > 6;
            boolean reached = plan.result.found && !plan.result.viaExit && !plan.result.partial;
            Costs costs = Walk.costs();
            boolean glides = Settings.walkElytraHops && WorldSnapshot.body(p).canGlide;
            fieldJob = WORKER.submit(() -> Field.build(v, costs, canSprint, true, glides, plan.id, path, plan.route, px, pz, dest[0], dest[1], reached));
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

        if (job != null && !job.isDone()) return;   // still on the last one
        harvest();
        BlockGrid g = grid;
        Body body = WorldSnapshot.body(p);
        body.glide = Settings.walkElytraHops && body.canGlide;   // elytra hops: off by default (they wear the elytra)
        MoveState s = WorldSnapshot.state(p);
        RunWorld root = new RunWorld(s, KillAura.lastSentYaw(p), KillAura.lastSentPitch(p));
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
        job = WORKER.submit(() -> {
            long r0 = System.nanoTime();
            RunModel model = new RunModel(new GroundPhysics(g, body), f, dest[0], dest[1], viewPitch, maxFall);
            if (body.glide) model.gaits = RunModel.GLIDE_GAITS;
            RecedingSearch<RunWorld> rs = new RecedingSearch<>(model);
            Layer2View.Recorder rec = display ? new Layer2View.Recorder(e.hold) : null;
            if (rec != null) rs.observe(rec);
            RecedingSearch.Result r = RunModel.plan(model, rs, root, previous, shift, e, seed);
            long nanos = System.nanoTime() - r0;
            int a = RunModel.STOP;
            float y = root.sentYaw;
            if (r.plan() != null) {
                RunWorld w = root.copy();
                model.step(w, r.plan()[0], 0, null);
                a = r.plan()[0];
                y = w.firstYaw;
            }
            Layer2View.Frame frame = rec != null ? rec.frame(model, root, r) : null;
            boolean unsafe = r.plan() == null || r.score() - model.estimate(root) >= RunModel.UNSAFE / 2;
            return new Out(r, a, y, f, at, nanos, frame, unsafe);
        });
    }
}
