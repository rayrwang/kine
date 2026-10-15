package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.search.RecedingSearch;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 2's own run simulated in the movement copy: from a state, replanning every tick as Layer2.endTick does (the
 * same model, field, goal, free actions toward the jump ahead), its first tick played, for up to a couple of seconds;
 * whether it comes down on one of the landings, and the inputs it pressed (the rotation sent and the keys each tick).
 *
 * <p>For the standing spots (RW 2026-10-14, "ok" to the proposal: at each spot the jump search first, then layer 2's own
 * run from there): layer 2's run curves a run-up round a wall's corner, which the jump search's single heading can't
 * (the course's #67, neo_2_5bc, was crossed 4 of 5 times by layer 2's own moves when the walk came onto its start one
 * way, and not at all when it came to rest against the wall). The run found is played as a recipe (its inputs tick by
 * tick, checked each tick to still land from the state then), so the walk does what the copy found, not a fresh run
 * whose replans fall out differently with the clock.
 */
final class RunSim {
    private RunSim() {}

    /** What the simulated run plans with: the effort, with no clock in the way (a budget no replan reaches, so the run
     *  is the same each time from the same state: the failed-search memory and the play as a recipe count on it; with
     *  the live 20 ms the machine's load cut the free plans short and the same spot landed one time and not the next),
     *  and how long the run may take. */
    static final int MAX_TICKS = 50;
    static final long REPLAN_BUDGET = 2_000_000_000L;

    /** The rest of what a replan takes: the goal (column, feet height, standing to arrive), the view's pitch, the fall
     *  that hurts, Parkour, free actions near jumps. */
    record Goal(int x, int z, double y, boolean stand, float viewPitch, double maxFall, boolean parkour, boolean freeJumps) {}

    /** The live replans' starting effort (Layer2.startEffort: not the tuned one, which moves with the machine's load)
     *  at the beam BEAM, with REPLAN_BUDGET; beam and draws set here instead when above 0 (tests). */
    static int beam = 0, draws = 0;
    /** The beam's width: the live tuner's widest (EffortTuner's 8), with no clock to keep it narrow. */
    static final int BEAM = 8;

    static RecedingSearch.Effort effort() {
        RecedingSearch.Effort e = Layer2.startEffort();
        e.budgetNanos = REPLAN_BUDGET;
        e.beam = BEAM;
        if (beam > 0) e.beam = beam;
        if (draws > 0) e.draws = draws;
        return e;
    }

    /**
     * Layer 2's run from `start` (the view sent at sentYaw, sentPitch), up to MAX_TICKS; a recipe of its inputs ending on
     * the tick it first stands on one of the landings (on the ground in its column at its top, or held on a climbable
     * there), or null if it doesn't within the time, falls, or makes a run the copy marks unsafe.
     */
    /** Whether q stands over one of the landings' columns. */
    private static boolean landsOn(MoveState q, int[][] land) {
        for (int[] l : land)
            if (q.x + 0.3 > l[0] && q.x - 0.3 < l[0] + 1 && q.z + 0.3 > l[2] && q.z - 0.3 < l[2] + 1) return true;
        return false;
    }

    static Recipe run(Field f, BlockGrid g, Body body, Goal goal, MoveState start, float sentYaw, float sentPitch, int[][] land) {
        GroundPhysics phys = new GroundPhysics(g, body);
        RunWorld w = new RunWorld(start.copy(), sentYaw, sentPitch);
        RecedingSearch.Effort e = effort();
        int[] prev = null;
        RunModel.FreeMemory mem = null;
        double groundY = start.y;
        List<Float> yaws = new ArrayList<>();
        List<Integer> keys = new ArrayList<>();
        for (int t = 0; t < MAX_TICKS; t++) {
            MoveState s = w.s;
            RunModel model = new RunModel(phys, f, goal.x(), goal.z(), goal.viewPitch(), goal.maxFall());
            model.parkour = goal.parkour();
            model.goalY = goal.y();
            model.stand = goal.stand() && model.arrived(s);   // settling once on the hop's floor (RunModel.stand)
            double[] target = goal.parkour() && goal.freeJumps() ? f.jumpAhead(s.x, s.y, s.z, 5.0) : null;
            if (target != null) {
                model.targetX = target[0]; model.targetY = target[1]; model.targetZ = target[2];
                long key = RunModel.FreeMemory.key(target[0], target[1], target[2]);
                if (mem == null || mem.target != key) mem = new RunModel.FreeMemory(key);
                model.freeMemory = mem;
                model.now = t;
            }
            if (body.glide) model.gaits = RunModel.GLIDE_GAITS;
            RecedingSearch<RunWorld> rs = new RecedingSearch<>(model);
            RunWorld root = w.copy();
            root.ticks = 0;
            root.arrived = false;
            root.firstYaw = Float.NaN;
            if (s.onGround || s.inWater || phys.onClimbable(s)) groundY = s.y;
            root.takeoffY = groundY;
            RecedingSearch.Effort et = e.copy();
            if (s.inWater) et.hold = RunModel.WATER_HOLD;
            RecedingSearch.Result r = RunModel.plan(model, rs, root, prev, 1, et, t * 0x9E3779B97F4A7C15L);
            if (r.plan() == null || r.score() - model.estimate(root) >= RunModel.UNSAFE / 2) return null;
            prev = r.plan();
            boolean ground0 = s.onGround;
            double cost = model.step(w, r.plan()[0], 0, null);
            // a tick the copy marks (a block that hurts, a fall that hurts, movement it leaves out: a cobweb, honey): the
            // run's path is no longer the game's, or hurts
            if (cost >= RunModel.UNCOPIED) return null;
            yaws.add(w.sentYaw);
            keys.add(w.lastKeys);
            MoveState q = w.s;
            if (q.y < start.y - 6) return null;
            // onto another floor on the way (Recipe.offFloor: a join pad beside the start, a fence's arm half a block up):
            // a jump from another take-off, not this one
            if (Recipe.offFloor(start, q) && !landsOn(q, land)) return null;
            for (int[] l : land) {
                boolean onCol = q.x + 0.3 > l[0] && q.x - 0.3 < l[0] + 1 && q.z + 0.3 > l[2] && q.z - 0.3 < l[2] + 1;
                boolean landed = q.onGround && !(q.vy > 0.0) && onCol && q.y > l[1] + 0.4 && q.y < l[1] + 1.6;
                boolean held = !q.onGround && Recipe.holdsOn(phys, q, l[0], l[1], l[2]);
                if (landed || held) {
                    float[] fy = new float[yaws.size()];
                    int[] fk = new int[keys.size()];
                    for (int i = 0; i < fy.length; i++) { fy[i] = yaws.get(i); fk[i] = keys.get(i); }
                    Recipe out = Recipe.of(phys, start, fy, fk, l);
                    out.how = "layer 2's run, " + fy.length + " ticks";
                    out.margin = Math.min(Math.min(q.x - l[0], l[0] + 1 - q.x), Math.min(q.z - l[2], l[2] + 1 - q.z));
                    return out;
                }
            }
            // came to rest anywhere but a landing: this run goes nowhere further
            if (ground0 && q.onGround && t > 10 && q.vx * q.vx + q.vz * q.vz <= RunModel.STILL2) return null;
        }
        return null;
    }
}
