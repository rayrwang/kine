package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.Shape;
import ai.rrw.kine.search.RecedingSearch;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 2 in a made-up world, closed loop, for the unit tests: a TestWorld turned into both the table (layer 1) and
 * the movement copy's blocks; layer 1's route from the start, layer 2's field around it, then tick by tick a replan
 * from the state now and its first tick done in the copy (which is the world here).
 */
final class Layer2Sim {
    static final BlockInfo STONE = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
    static final BlockInfo WATER = new BlockInfo(Shape.EMPTY, 0.6F, 1.0F, 1.0F, BlockInfo.WATER | BlockInfo.FLUID,
        BlockInfo.WATER_FLUID, 8.0F / 9.0F);
    static final BlockInfo LAVA = new BlockInfo(Shape.EMPTY, 0.6F, 1.0F, 1.0F, BlockInfo.FLUID | BlockInfo.HAZARD,
        BlockInfo.LAVA_FLUID, 8.0F / 9.0F);

    /** The world's blocks as the movement copy reads them (full blocks, water and lava only). */
    static BlockGrid grid(TestWorld w) {
        int x0 = w.cx0 * 16, z0 = w.cz0 * 16, nx = (w.cx1 - w.cx0 + 1) * 16, nz = (w.cz1 - w.cz0 + 1) * 16;
        BlockGrid g = new BlockGrid(x0, TestWorld.MIN_Y, z0, nx, TestWorld.MAX_Y - TestWorld.MIN_Y, nz);
        for (int x = x0; x < x0 + nx; x++)
            for (int z = z0; z < z0 + nz; z++)
                for (int y = TestWorld.MIN_Y; y < TestWorld.MAX_Y; y++) {
                    Kind k = w.get(x, y, z);
                    if (k == Palette.AIR) continue;
                    g.set(x, y, z, info(k));
                }
        return g;
    }

    private static final java.util.Map<Kind, BlockInfo> INFOS = new java.util.HashMap<>();

    /** A kind's blocks as the copy reads them: its own shape and factors; water and lava with their fluid; a ladder
     *  climbable. */
    static BlockInfo info(Kind k) {
        if (k == TestWorld.WATER) return WATER;
        if (k == TestWorld.LAVA) return LAVA;
        return INFOS.computeIfAbsent(k, kk -> {
            BlockInfo b = kk.info;
            int flags = b.flags | (kk.is(Kind.CLIMB) ? BlockInfo.CLIMBABLE | BlockInfo.SPECIAL : 0);
            return new BlockInfo(b.shape, b.friction, b.speedFactor, b.jumpFactor, flags);
        });
    }

    /** Standing still on the ground at (x, y, z) (y the floor's top). */
    static MoveState standing(double x, double y, double z) {
        MoveState s = new MoveState();
        s.x = x; s.y = y; s.z = z;
        s.vy = -0.0784000015258789;
        s.onGround = true;
        s.hasSupport = true; s.supX = (int) Math.floor(x); s.supY = (int) Math.floor(y) - 1; s.supZ = (int) Math.floor(z);
        return s;
    }

    record Outcome(boolean arrived, int ticks, int jumps, int replans, double worstMs, double meanMs, List<double[]> path,
                   String why) {}

    final TestWorld world;
    final View view;
    final BlockGrid grid;
    final Body body = new Body();
    final GroundPhysics phys;
    final Costs costs = Costs.layer2();
    RecedingSearch.Effort effort = new RecedingSearch.Effort();
    PathSearch.Result lastRoute;
    /** The gaits the search may use (RunModel.GAITS, or BASE_GAITS without sneaking). */
    int gaits = RunModel.GAITS;
    /** Elytra hops: a glider worn, glides copied and offered (layer 1's glide moves, layer 2's glide gaits). */
    boolean glides = false;
    /** Count the air in the search (off only to measure what it changes). */
    boolean airAware = true;
    int glideTicks = 0;

    Layer2Sim(TestWorld w) {
        this.world = w;
        this.view = w.view();
        this.grid = grid(w);
        this.phys = new GroundPhysics(grid, body);
        effort.beam = 4; effort.depth = 5; effort.hold = 4; effort.draws = 1;
        effort.budgetNanos = 2_000_000_000L;   // a test: no clock in the way
    }

    java.util.Set<Integer> debugTicks = new java.util.HashSet<>();

    /** Every held action's score from this state, and the plan chosen. */
    void debug(int t, RunModel model, RecedingSearch<RunWorld> rs, RunWorld root, RecedingSearch.Result res) {
        MoveState s = root.s;
        System.out.printf(java.util.Locale.ROOT, "  t=%d at %.2f,%.2f,%.2f v %.3f,%.3f,%.3f water=%b eyes=%b swim=%b field=%.1f score=%.2f plan:",
            t, s.x, s.y, s.z, s.vx, s.vy, s.vz, s.inWater, s.eyesInWater, s.swimming, model.field.at(s.x, s.y, s.z), res.score());
        for (int i = 0; i < res.plan().length; i += effort.hold) System.out.print(" " + RunModel.name(res.plan()[i]));
        System.out.println();
        StringBuilder sb = new StringBuilder("    held:");
        for (int g = 0; g < RunModel.GAITS; g++) {
            int a = RunModel.action(RunModel.DESCENT, g);
            int[] plan = new int[effort.horizon()];
            java.util.Arrays.fill(plan, a);
            RunWorld w = root.copy();
            for (int i = 0; i < plan.length; i++) model.step(w, a, i == 0 ? 0 : 1, null);
            sb.append(String.format(java.util.Locale.ROOT, " %s=%.2f(end %.2f,%.2f swim=%b est %.1f)", RunModel.name(a),
                rs.score(root, plan, effort, t), w.s.x, w.s.y, w.s.swimming, model.estimate(w)));
        }
        System.out.println(sb);
    }

    /** Walk with layer 2 from s to column (gx, gz). */
    Outcome walk(MoveState s, float yaw, int gx, int gz, int maxTicks) {
        long start = view.nodeAt((int) Math.floor(s.x), s.y, (int) Math.floor(s.z));
        if (start == View.NONE) return new Outcome(false, 0, 0, 0, 0, 0, List.of(), "no start floor");
        if (glides) { body.canGlide = true; body.glide = true; }
        PathSearch ps = new PathSearch(view, costs).gaps(true).glides(glides);
        PathSearch.Result r = ps.run(start, gx, gz, 1.0, Long.MAX_VALUE, 2_000_000, false);
        if (!r.found) return new Outcome(false, 0, 0, 0, 0, 0, List.of(), "layer 1: no route");
        lastRoute = r;
        Field f = null;
        Route route = null;
        float sentYaw = yaw, sentPitch = 0f;
        int[] prev = null;
        int jumps = 0, replans = 0;
        double worst = 0, sum = 0;
        List<double[]> path = new ArrayList<>();
        for (int t = 0; t < maxTicks; t++) {
            if (f == null || f.to < r.path.length - 1
                && Field.nearestIndex(r.path, s.x, s.z) > (f.from + f.to) / 2) {
                if (route == null) route = new Tighten(view, costs, 0.2, true).route(r, gx, gz);
                f = Field.build(view, costs, true, true, glides, 1, r.path, route, s.x, s.z, gx, gz, !r.viaExit && !r.partial);
                prev = null;
            }
            RunModel model = new RunModel(phys, f, gx, gz, 0f, costs.maxFall);
            model.gaits = glides ? RunModel.GLIDE_GAITS : gaits;
            model.airAware = airAware;
            RecedingSearch<RunWorld> rs = new RecedingSearch<>(model);
            RunWorld root = new RunWorld(s.copy(), sentYaw, sentPitch);
            long t0 = System.nanoTime();
            RecedingSearch.Effort e = effort.copy();
            if (s.inWater) e.hold = RunModel.WATER_HOLD;
            RecedingSearch.Result res = RunModel.plan(model, rs, root, prev, 1, e, t);
            double ms = (System.nanoTime() - t0) / 1e6;
            worst = Math.max(worst, ms);
            sum += ms;
            replans++;
            if (res.plan() == null) return new Outcome(false, t, jumps, replans, worst, sum / replans, path, "no plan");
            if (debugTicks.contains(t)) debug(t, model, rs, root, res);
            prev = res.plan();
            RunWorld w = root.copy();
            boolean ground = w.s.onGround;
            model.step(w, res.plan()[0], 0, null);
            if ((w.lastKeys & MoveState.JUMP) != 0 && ground && !w.s.onGround) jumps++;
            s = w.s;
            if (s.fallFlying) glideTicks++;
            sentYaw = w.sentYaw; sentPitch = w.sentPitch;
            path.add(new double[]{s.x, s.y, s.z, (s.inWater ? 1 : 0) + (s.swimming ? 2 : 0) + (s.onGround ? 4 : 0) + (s.eyesInWater ? 8 : 0),
                w.lastKeys, s.air, s.drownDamage});
            if (w.arrived) return new Outcome(true, t + 1, jumps, replans, worst, sum / replans, path, "");
        }
        return new Outcome(false, maxTicks, jumps, replans, worst, sum / replans, path, "out of ticks");
    }
}
