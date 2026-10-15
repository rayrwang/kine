package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.search.RecedingSearch;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 2 at the floor nearest a goal it can't reach, in water, as the game's walk runs it: layer 1's way to the
 * nearest floor as the route, the model's goal the real one (the top of a pillar 3 above the land in a pond 4 deep,
 * by its Y). Nothing there gets nearer, so every run scores about the same; the eyes under water must cost more than
 * the field's unevenness, or the run that lets the player sink wins and the walk stands on the bottom with the eyes
 * under. Without RunModel.UNDER_END: from the water by the pillar to the bottom, the eyes under 295 of 400 ticks; from
 * the bank into the water and down 2.3 blocks, 90 (in a lockstep test the walk gave up on the bottom, pondIslandFar).
 * With it: the eyes out nearly throughout, never near the bottom (from the bank it now waits on the bank, the water's
 * floor by the pillar no nearer by enough to pay for getting wet).
 */
public class Layer2IdleWaterTest {
    @Test
    void keepsTheEyesOutAtTheNearestFloorInWater() {
        MoveState bank = Layer2Sim.standing(0.5, 11, 0.5);
        MoveState water = new MoveState();
        water.x = 8.5; water.y = 10.0; water.z = 0.5; water.inWater = true;
        idle("from the bank", bank);
        idle("from the water", water);
    }

    static void idle(String name, MoveState s) {
        TestWorld w = AfloatTest.pond(4).fill(10, 6, 0, 10, 13, 0, TestWorld.STONE);
        View v = w.view();
        Costs costs = Costs.layer2();
        int gx = 10, gz = 0;
        double goalY = 14;
        long start = v.nodeAt(0, 11, 0);
        PathSearch.Result r = new PathSearch(v, costs).gaps(true).goalY(goalY).run(start, gx, gz, 1.0, Long.MAX_VALUE, 2_000_000, false);
        assertFalse(r.found);
        assertTrue(r.nearest && r.path.length > 1, "a way to the floor nearest the goal");
        Route route = new Tighten(v, costs, 0.2, true).route(r, gx, gz);
        Field f = Field.build(v, costs, true, true, false, 1, r.path, route, 0.5, 0.5, gx, gz, goalY, false);
        BlockGrid g = Layer2Sim.grid(w);
        GroundPhysics phys = new GroundPhysics(g, new Body());
        RecedingSearch.Effort effort = new RecedingSearch.Effort();
        effort.beam = 4; effort.depth = 5; effort.hold = 4; effort.draws = 1; effort.budgetNanos = 2_000_000_000L;
        float sentYaw = -90f, sentPitch = 0f;
        int[] prev = null;
        int under = 0, inWater = 0;
        double lowest = s.y;
        for (int t = 0; t < 400; t++) {
            RunModel model = new RunModel(phys, f, gx, gz, 0f, costs.maxFall);
            model.goalY = goalY;
            RecedingSearch<RunWorld> rs = new RecedingSearch<>(model);
            RunWorld root = new RunWorld(s.copy(), sentYaw, sentPitch);
            root.takeoffY = s.y;
            RecedingSearch.Effort e = effort.copy();
            if (s.inWater) e.hold = RunModel.WATER_HOLD;
            RecedingSearch.Result res = RunModel.plan(model, rs, root, prev, 1, e, t);
            prev = res.plan();
            RunWorld ww = root.copy();
            model.step(ww, res.plan()[0], 0, null);
            s = ww.s; sentYaw = ww.sentYaw; sentPitch = ww.sentPitch;
            if (s.eyesInWater) under++;
            if (s.inWater) inWater++;
            lowest = Math.min(lowest, s.y);
        }
        System.out.printf(Locale.ROOT, "idle %s: eyes under %d of 400 ticks, in water %d, lowest feet %.2f (bottom 7, top 10.89)%n",
            name, under, inWater, lowest);
        assertTrue(under <= 20, name + ": the eyes out nearly throughout: " + under);
        assertTrue(lowest > 8.5, name + ": never near the bottom: " + lowest);
        assertEquals(0.0, s.drownDamage);
    }
}
