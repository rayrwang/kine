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
    private final Costs base = Costs.layer2();
    /** The costs at the gait (set at each walk). */
    Costs costs = base;
    /** The gait setting (Settings.GAIT_*): the costs, layer 1's sprinting, layer 2's cap and the jump search's. */
    int gait = ai.rrw.kine.Settings.GAIT_SPRINT_JUMP;
    RecedingSearch.Effort effort = new RecedingSearch.Effort();
    PathSearch.Result lastRoute;
    /** The gaits the search may use (RunModel.GAITS, or BASE_GAITS without sneaking). */
    int gaits = RunModel.GAITS;
    /** Elytra hops: a glider worn, glides copied and offered (layer 1's glide moves, layer 2's glide gaits). */
    boolean glides = false;
    /** Count the air in the search (off only to measure what it changes). */
    boolean airAware = true;
    /** Parkour (the setting; on here so the jumping tests run): off, no gap moves and no gap crossings. */
    boolean parkour = true;
    /** Walking without a route when layer 1 finds none (Settings.walkDirect): Field.direct. */
    boolean direct = false;
    /** The jump being played (walking without a route), its tick, and the floor it was searched from. */
    Recipe recipe;
    int recipeT;
    long recipeKey = Long.MIN_VALUE;
    int recipesFound;
    /** Free actions near a jump (Settings.walkFreeJumps), and the copy-found jumps without a route (recipes). */
    boolean freeJumps = true, recipes = true;
    /** Ticks before the first recipe search (the game's runs on the worker for some 5 ticks, and waits out a try
     *  made in the 20 ticks before). */
    int recipeDelay = 0;
    double groundY = Double.NaN;
    /** The destination's Y (feet): only that floor of the goal column is the goal; NaN: any floor (the Walk tab's Y). */
    double goalY = Double.NaN;
    int freeSeeds;
    RunModel.FreeMemory freeMemory;
    /** Every replan of the walks so far: its time in ms, the free plans' part of it, and 1 if it had a jump ahead. */
    final List<double[]> replanTimes = new ArrayList<>();
    /** Walk without a route even when layer 1 has one (to measure layer 2 alone). */
    boolean forceDirect = false;
    /** Walk layer 1's way to the floor nearest an unreachable goal (as the game's walk does), arriving at its end. */
    boolean acceptNearest = false;
    /** Where the last walk was aiming: the goal, or the nearest floor's column when it took layer 1's nearest way. */
    int[] lastTarget;
    /** Whether the last walk went without a route, and its field's build time. */
    boolean wentDirect;
    long directFieldNanos;
    int glideTicks = 0;
    /** Ticks the copy's player was sprinting (after the tick), all walks. */
    int sprintTicks = 0;
    /** The goal counts as reached only standing still on it (a hop's floor: RunModel.stand, as the game's walk). */
    boolean stand = false;
    /** A recipe's arrival also within 0.6 of goalY, as the game's Walk counts it (off: the column alone, as before). */
    boolean arrivalY = false;

    Layer2Sim(TestWorld w) {
        this.world = w;
        this.view = w.view();
        this.grid = grid(w);
        this.phys = new GroundPhysics(grid, body);
        effort.beam = 4; effort.depth = 5; effort.hold = 4; effort.draws = 1;
        effort.budgetNanos = 2_000_000_000L;   // a test: no clock in the way
    }

    java.util.Set<Integer> debugTicks = new java.util.HashSet<>();
    /** The off-field floor as Layer2 sets it (RunModel.OffField); off: none (0), as the sim ran before 2026-10-16. */
    boolean offFieldFloor = true;
    final RunModel.OffField offField = new RunModel.OffField();

    /** Every held action's score from this state, and the plan chosen. */
    void debug(int t, RunModel model, RecedingSearch<RunWorld> rs, RunWorld root, RecedingSearch.Result res) {
        MoveState s = root.s;
        System.out.printf(java.util.Locale.ROOT, "  t=%d at %.2f,%.2f,%.2f v %.3f,%.3f,%.3f water=%b eyes=%b swim=%b field=%.1f score=%.2f plan:",
            t, s.x, s.y, s.z, s.vx, s.vy, s.vz, s.inWater, s.eyesInWater, s.swimming, model.field.at(s.x, s.y, s.z), res.score());
        for (int i = 0; i < res.plan().length; i += effort.hold) System.out.print(" " + RunModel.name(res.plan()[i]));
        System.out.println();
        StringBuilder sb = new StringBuilder("    held:");
        for (int a = 0; a < model.actionCount(); a++) {
            int[] plan = new int[effort.horizon()];
            java.util.Arrays.fill(plan, a);
            RunWorld w = root.copy();
            double c = 0;
            String bad = "";
            for (int i = 0; i < plan.length; i++) {
                double ci = model.step(w, a, i % effort.hold, new java.util.SplittableRandom(i));
                if (ci >= 500 && bad.isEmpty()) bad = String.format(java.util.Locale.ROOT, " !t%d@%.2f,%.2f,%.2f gf%.2f", i, w.s.x, w.s.y, w.s.z, w.gapFloor);
                c += ci;
            }
            sb.append(String.format(java.util.Locale.ROOT, " %s=%.2f(end %.2f,%.2f,%.2f est %.1f%s)", RunModel.name(a),
                rs.score(root, plan, effort, t), w.s.x, w.s.y, w.s.z, model.estimate(w), bad));
        }
        System.out.println(sb);
        // the chosen plan, stepped: its end and estimate, and its cost tick by tick
        RunWorld w = root.copy();
        StringBuilder cs = new StringBuilder("    chosen:");
        double c = 0;
        for (int i = 0; i < res.plan().length; i++) {
            double ci = model.step(w, res.plan()[i], i % effort.hold, new java.util.SplittableRandom(i));
            c += ci;
            if (i % effort.hold == effort.hold - 1) cs.append(String.format(java.util.Locale.ROOT, " [%s %.2f,%.2f,%.2f c%.1f]", RunModel.name(res.plan()[i]), w.s.x, w.s.y, w.s.z, c));
        }
        cs.append(String.format(java.util.Locale.ROOT, " end est %.1f arrived %b", model.estimate(w), w.arrived));
        System.out.println(cs);
        // and on-walk held, the same way
        RunWorld w2 = root.copy();
        StringBuilder cs2 = new StringBuilder("    on-walk held:");
        double c2 = 0;
        int a = -1;
        for (int k = 0; k < model.actionCount(); k++) if (RunModel.name(k).equals("on-walk")) a = k;
        for (int i = 0; i < res.plan().length; i++) {
            double ci = model.step(w2, a, i % effort.hold, new java.util.SplittableRandom(i));
            c2 += ci;
            if (i % effort.hold == effort.hold - 1) cs2.append(String.format(java.util.Locale.ROOT, " [%.2f,%.2f,%.2f c%.1f g%b cl%b]", w2.s.x, w2.s.y, w2.s.z, c2, w2.s.onGround, phys.onClimbable(w2.s)));
        }
        cs2.append(String.format(java.util.Locale.ROOT, " end est %.1f arrived %b", model.estimate(w2), w2.arrived));
        System.out.println(cs2);
    }

    /** Walk with layer 2 from s to column (gx, gz). */
    Outcome walk(MoveState s, float yaw, int gx, int gz, int maxTicks) {
        costs = Costs.atGait(base, gait);
        boolean canSprint = gait >= ai.rrw.kine.Settings.GAIT_SPRINT;
        // Parkour needs sprint-jumping (as Settings.parkourAllowed): off below Sprint-jump for this walk
        boolean parkourSet = parkour;
        if (gait < ai.rrw.kine.Settings.GAIT_SPRINT_JUMP) parkour = false;
        try {
            return walkAtGait(s, yaw, gx, gz, maxTicks, canSprint);
        } finally {
            parkour = parkourSet;
        }
    }

    private Outcome walkAtGait(MoveState s, float yaw, int gx, int gz, int maxTicks, boolean canSprint) {
        long start = view.nodeAt((int) Math.floor(s.x), s.y, (int) Math.floor(s.z));
        if (start == View.NONE) return new Outcome(false, 0, 0, 0, 0, 0, List.of(), "no start floor");
        if (glides) { body.canGlide = true; body.glide = true; }
        PathSearch ps = new PathSearch(view, costs).canSprint(canSprint).gaps(parkour).glides(glides).goalY(goalY);
        PathSearch.Result r = ps.run(start, gx, gz, 1.0, Long.MAX_VALUE, 2_000_000, false);
        wentDirect = false;
        boolean nearestWay = !r.found && !forceDirect && acceptNearest && r.nearest && r.path.length > 1;
        if ((!r.found || forceDirect) && direct && !nearestWay) {
            Planner.Plan dp = Planner.direct(view, start, gx, gz, goalY, costs, 0.2, canSprint);
            if (dp == null) return new Outcome(false, 0, 0, 0, 0, 0, List.of(), "layer 1: no route, and no goal floor");
            r = dp.result;
            wentDirect = true;
        }
        double keepGoalY = goalY;
        if (nearestWay) {
            long end = r.path[r.path.length - 1];
            gx = View.nodeX(end); gz = View.nodeZ(end);
            goalY = r.pathY[r.pathY.length - 1];
            r.found = true;   // walked as far as it goes
        }
        lastTarget = new int[]{gx, gz};
        if (!r.found && !wentDirect) return new Outcome(false, 0, 0, 0, 0, 0, List.of(), "layer 1: no route" + (r.nearest ? " (nearest is here)" : ""));
        try {
            return walkOn(s, yaw, gx, gz, maxTicks, r);
        } finally {
            goalY = keepGoalY;
        }
    }

    private Outcome walkOn(MoveState s, float yaw, int gx, int gz, int maxTicks, PathSearch.Result r) {
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
                if (route == null) route = new Tighten(view, costs, 0.2, gait >= ai.rrw.kine.Settings.GAIT_SPRINT).route(r, gx, gz);
                if (wentDirect) {
                    f = Field.direct(view, costs, gait >= ai.rrw.kine.Settings.GAIT_SPRINT, parkour, glides, 1, r.path, gx, gz, goalY);
                    directFieldNanos = f.buildNanos;
                } else f = Field.build(view, costs, gait >= ai.rrw.kine.Settings.GAIT_SPRINT, parkour, glides, 1, r.path, route, s.x, s.z, gx, gz, goalY, !r.viaExit && !r.partial);
                prev = null;
            }
            // walking without a route (as Layer2): on a floor the graph doesn't join to the goal, a jump found in the copy
            // to one it does join, played while it still lands
            if (wentDirect && parkour && recipes && recipe == null && t >= recipeDelay && s.onGround
                && s.vx * s.vx + s.vz * s.vz <= 1e-4 && !f.joinedAt(s.x, s.y, s.z)) {   // from a standing start (as Layer2)
                long key = ((long) Math.floor(s.x) << 40) ^ ((long) Math.floor(s.z) << 16) ^ (long) Math.floor(s.y);
                if (key != recipeKey) {
                    recipeKey = key;
                    for (int c : f.joinedNear(s.x, s.y, s.z, 6.0, 3)) {
                        int lx = (int) Math.floor(f.x(c)), ly = (int) Math.round(f.y(c)) - 1, lz = (int) Math.floor(f.z(c));
                        float bearing = Recipe.yawTo(s.x, s.z, lx + 0.5, lz + 0.5);
                        Recipe rc = Recipe.search(phys, s, sentYaw, lx, ly, lz, bearing - 90f, bearing + 90f, 6f, 8, 8, 60, gait >= ai.rrw.kine.Settings.GAIT_SPRINT);
                        if (rc.found()) { recipe = rc; recipeT = 0; recipesFound++; break; }
                    }
                }
            }
            if (recipe != null && (recipeT >= recipe.ticks || !recipe.stillLands(phys, s, recipeT, recipe.lx, recipe.ly, recipe.lz)))
                recipe = null;
            if (recipe != null) {
                // the recipe's tick: the rotation and keys as recorded
                sentYaw = recipe.yaw[recipeT];
                boolean ground0 = s.onGround;
                phys.step(s, recipe.keys[recipeT], sentYaw, 0f);
                if (s.sprinting) sprintTicks++;
                if ((recipe.keys[recipeT] & MoveState.JUMP) != 0 && ground0 && !s.onGround) jumps++;
                recipeT++;
                path.add(new double[]{s.x, s.y, s.z, (s.inWater ? 1 : 0) + (s.swimming ? 2 : 0) + (s.onGround ? 4 : 0) + (s.eyesInWater ? 8 : 0),
                    recipe.keys[Math.min(recipeT, recipe.ticks) - 1], s.air, s.drownDamage});
                double gdx = gx + 0.5 - s.x, gdz = gz + 0.5 - s.z;
                // as Walk's arrival: climbing counts where it holds (not inside scaffolding, 2026-10-15)
                if (gdx * gdx + gdz * gdz <= 0.25 && (s.onGround || s.inWater || phys.onClimbable(s) && !phys.inScaffolding(s))
                    && (!arrivalY || Double.isNaN(goalY) || Math.abs(s.y - goalY) < 0.6))
                    return new Outcome(true, t + 1, jumps, replans, worst, replans == 0 ? 0 : sum / replans, path, "");
                prev = null;
                continue;
            }
            RunModel model = new RunModel(phys, f, gx, gz, 0f, costs.maxFall);
            if (offFieldFloor) model.offFieldFloor = offField.floor(f, s.x, s.y, s.z);   // as Layer2
            model.gaits = glides ? RunModel.GLIDE_GAITS : gaits;
            model.airAware = airAware;
            model.parkour = parkour;
            model.cap = gait;
            model.goalY = goalY;   // the destination's Y (as the game's): only that floor of the goal column
            model.stand = stand && model.arrived(s);   // settling once on the floor (as Layer2)
            if (parkour && freeJumps) {
                double[] tg = f.jumpAhead(s.x, s.y, s.z, 5.0);
                if (tg != null) {
                    model.targetX = tg[0]; model.targetY = tg[1]; model.targetZ = tg[2];
                    long key = RunModel.FreeMemory.key(tg[0], tg[1], tg[2]);
                    if (freeMemory == null || freeMemory.target != key) freeMemory = new RunModel.FreeMemory(key);
                    model.freeMemory = freeMemory;
                    model.now = t;
                }
            }
            RecedingSearch<RunWorld> rs = new RecedingSearch<>(model);
            RunWorld root = new RunWorld(s.copy(), sentYaw, sentPitch);
            if (s.onGround || s.inWater || phys.onClimbable(s)) groundY = s.y;   // the gap check's take-off (as Layer2)
            root.takeoffY = groundY;
            long t0 = System.nanoTime();
            RecedingSearch.Effort e = effort.copy();
            if (s.inWater) e.hold = RunModel.WATER_HOLD;
            RecedingSearch.Result res = RunModel.plan(model, rs, root, prev, 1, e, t);
            double ms = (System.nanoTime() - t0) / 1e6;
            worst = Math.max(worst, ms);
            sum += ms;
            replans++;
            freeSeeds += model.freeSeeds;
            replanTimes.add(new double[]{ms, model.freeNanos / 1e6, Double.isNaN(model.targetX) ? 0 : 1, model.freeRollouts});
            if (res.plan() == null) return new Outcome(false, t, jumps, replans, worst, sum / replans, path, "no plan");
            if (debugTicks.contains(t)) debug(t, model, rs, root, res);
            prev = res.plan();
            RunWorld w = root.copy();
            boolean ground = w.s.onGround;
            model.step(w, res.plan()[0], 0, null);
            if ((w.lastKeys & MoveState.JUMP) != 0 && ground && !w.s.onGround) jumps++;
            s = w.s;
            if (s.fallFlying) glideTicks++;
            if (s.sprinting) sprintTicks++;
            sentYaw = w.sentYaw; sentPitch = w.sentPitch;
            path.add(new double[]{s.x, s.y, s.z, (s.inWater ? 1 : 0) + (s.swimming ? 2 : 0) + (s.onGround ? 4 : 0) + (s.eyesInWater ? 8 : 0),
                w.lastKeys, s.air, s.drownDamage});
            // standing to arrive (a hop's floor): once on it, on until still (as the game's walk)
            if (w.arrived && (!stand || !s.onGround || s.vx * s.vx + s.vz * s.vz <= RunModel.STILL2))
                return new Outcome(true, t + 1, jumps, replans, worst, sum / replans, path, "");
        }
        return new Outcome(false, maxTicks, jumps, replans, worst, sum / replans, path, "out of ticks");
    }
}
