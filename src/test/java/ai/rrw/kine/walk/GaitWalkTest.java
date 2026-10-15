package ai.rrw.kine.walk;

import ai.rrw.kine.Settings;
import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The gait setting in the movement copy (RW 2026-10-14, "add teh gaint"): the walk at each level, layer 1 and layer 2
 * together (Layer2Sim). Open ground: at Walk never a sprint (the keys and the copy's sprint flag), at Walk and Sprint
 * no jump (nothing to jump over), at Sprint-jump the jumps it makes for speed; each slower than the next. A block up is
 * jumped at Walk too, without sprint, and nothing else; gaps only with Parkour, which needs Sprint-jump.
 */
public class GaitWalkTest {
    static final int F = 20;   // the floor's top

    record Run(boolean arrived, int ticks, int jumps, int sprintTicks, int sprintKeys, String why) {}

    /** One walk at a gait from (sx, sz) on the floor to (gx, gz). */
    static Run walk(TestWorld w, int gait, double sx, double sz, int gx, int gz, double goalY, int maxTicks) {
        Layer2Sim sim = new Layer2Sim(w);
        sim.gait = gait;
        sim.goalY = goalY;
        MoveState s = Layer2Sim.standing(sx, F, sz);
        Layer2Sim.Outcome o = sim.walk(s, -90f, gx, gz, maxTicks);
        int sprintKeys = 0;
        for (double[] q : o.path()) if (((int) q[4] & MoveState.SPRINT) != 0) sprintKeys++;
        return new Run(o.arrived(), o.ticks(), o.jumps(), sim.sprintTicks, sprintKeys, o.why());
    }

    static TestWorld flat() {
        TestWorld w = blocks(-8, -8, 63, 23);
        w.fill(-4, F - 1, -4, 60, F - 1, 4, STONE);
        return w;
    }

    @Test
    void openGroundAtEachGait() {
        String[] names = {"walk", "sprint", "sprint-jump"};
        Run[] r = new Run[3];
        for (int g = 0; g < 3; g++) {
            r[g] = walk(flat(), g, 0.5, 0.5, 50, 0, F, 600);
            System.out.printf(Locale.ROOT, "open ground 50 blocks at %s: %s in %d ticks, %d jumps, %d ticks sprinting, %d with the sprint key%n",
                names[g], r[g].arrived() ? "arrived" : "not arrived (" + r[g].why() + ")", r[g].ticks(), r[g].jumps(), r[g].sprintTicks(), r[g].sprintKeys());
            assertTrue(r[g].arrived(), names[g] + ": " + r[g].why());
        }
        assertEquals(0, r[0].sprintTicks(), "walk: never sprinting");
        assertEquals(0, r[0].jumps(), "walk: no jump on open ground");
        assertEquals(0, r[0].sprintKeys(), "walk: never the sprint key");
        assertTrue(r[1].sprintTicks() > 0, "sprint: sprints");
        assertEquals(0, r[1].jumps(), "sprint: no jump on open ground");
        assertTrue(r[2].jumps() > 0, "sprint-jump: jumps for speed");
        assertTrue(r[0].ticks() > r[1].ticks() && r[1].ticks() > r[2].ticks(), "each level slower than the next");
    }

    /** A block up (x 11 to 20) and down again: the walk jumps where it must and nowhere else, at Walk and Sprint. */
    @Test
    void jumpsTheGroundNeedsAtWalk() {
        TestWorld w = blocks(-8, -8, 47, 23);
        w.fill(-4, F - 1, -2, 34, F - 1, 2, STONE);   // feet F
        w.fill(11, F, -2, 20, F, 2, STONE);           // a block up: feet F + 1
        for (int gait = 0; gait < 3; gait++) {
            Run r = walk(w, gait, 0.5, 0.5, 30, 0, F, 600);
            System.out.printf(Locale.ROOT, "a block up and down at gait %d: %s in %d ticks, %d jumps, %d ticks sprinting%n",
                gait, r.arrived() ? "arrived" : "not arrived (" + r.why() + ")", r.ticks(), r.jumps(), r.sprintTicks());
            assertTrue(r.arrived(), "gait " + gait + ": " + r.why());
            if (gait == 0) assertEquals(0, r.sprintTicks(), "walking");
            if (gait <= 1) assertEquals(1, r.jumps(), "the step up, and no other jump");
        }
    }

    /** A gap one block across: below Sprint-jump Parkour is off (Settings.parkourAllowed), so no way over; at
     *  Sprint-jump, with Parkour, over. */
    @Test
    void noGapJumpBelowSprintJump() {
        TestWorld w = blocks(-8, -8, 47, 23);
        w.fill(-4, F - 1, -2, 10, F - 1, 2, STONE);
        w.fill(12, F - 1, -2, 30, F - 1, 2, STONE);   // x 11 empty
        for (int gait = 0; gait < 3; gait++) {
            Run r = walk(w, gait, 0.5, 0.5, 25, 0, F, 400);
            System.out.printf(Locale.ROOT, "a one-block gap at gait %d: %s (%s), %d jumps%n", gait, r.arrived(), r.why(), r.jumps());
            assertEquals(gait == Settings.GAIT_SPRINT_JUMP, r.arrived(), "gait " + gait);
        }
    }

    /** A gap of four blocks: a sprint-jump with a run-up crosses it; at Walk the walk doesn't try (layer 1 offers only
     *  the gaps a walking jump makes). */
    @Test
    void aGapOnlyASprintJumpCrosses() {
        TestWorld w = blocks(-8, -8, 47, 23);
        w.fill(-4, F - 1, -2, 10, F - 1, 2, STONE);
        w.fill(15, F - 1, -2, 30, F - 1, 2, STONE);   // x 11 to 14 empty: four across
        Run walking = walk(w, Settings.GAIT_WALK, 0.5, 0.5, 25, 0, F, 400);
        Run jumping = walk(w, Settings.GAIT_SPRINT_JUMP, 0.5, 0.5, 25, 0, F, 400);
        System.out.printf(Locale.ROOT, "a four-block gap: walk %s (%s), sprint-jump %s in %d ticks%n", walking.arrived(),
            walking.why(), jumping.arrived(), jumping.ticks());
        assertFalse(walking.arrived(), "walking: no way over");
        assertEquals(0, walking.sprintTicks());
        assertTrue(jumping.arrived(), "sprint-jumping: over");
    }

    @Test
    void theEstimateAtEachGaitStaysUnderTheCosts() {
        for (int g = 0; g < 4; g++) {
            Costs c = Costs.forGait(Costs.LAYER2, g), b = Costs.forGait(Costs.BARITONE, g);
            for (Costs k : List.of(c, b)) {
                assertTrue(k.fastest <= k.sprint + 1e-9 && k.fastest <= k.walk + 1e-9 && k.fastest <= k.water + 1e-9
                    && k.fastest <= k.noSprint + 1e-9, "gait " + g + ": the estimate's rate under every rate");
            }
        }
        assertEquals(Costs.LAYER2, Costs.forGait(Costs.LAYER2, Settings.GAIT_SPRINT_JUMP), "the default as before");
        assertEquals(Costs.LAYER2, Costs.forGait(Costs.LAYER2, Settings.GAIT_HOPS));
    }
}
