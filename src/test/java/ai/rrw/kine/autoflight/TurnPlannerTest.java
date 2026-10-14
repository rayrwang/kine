package ai.rrw.kine.autoflight;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The terrain-avoidance planner and controller on small synthetic terrains. */
class TurnPlannerTest {
    // flying along +z (yaw 0) at 1.5 blocks/tick, climbing toward a 350 cruise floor from 250
    static FlightModel3D.State seed(double y) {
        var s = new FlightModel3D.State(0, y, 0, 0, -0.3, 1.5, 350, 30, true);
        s.cmd = PorpoiseLaw.CLIMB.dive();
        return s;
    }
    static final Terrain2D FLAT_LOW = (x, z) -> 64;
    static final Terrain2D UNSEEN = (x, z) -> Double.NaN;
    // a wall across the path 120 blocks ahead, 30 thick, 600 wide, rising to `top`
    static Terrain2D wall(double top, double halfWidth) {
        return (x, z) -> (z >= 120 && z <= 150 && Math.abs(x) <= halfWidth) ? top : 64;
    }

    @BeforeEach
    void fullSight() { TurnPlanner.setRenderRange(TurnPlanner.SCAN_DIST); }

    @Test
    void clearGroundFliesStraightAtTheCruiseFloor() {
        var p = TurnPlanner.plan(seed(250), 0.0, 0, 3000, FLAT_LOW);
        assertEquals(TurnPlanner.STRAIGHT, p.action);
        assertEquals(0.0, p.heading, 0.0);
        assertEquals(350.0, p.floor, 0.0);
    }

    @Test
    void unseenTerrainIsNotTreatedAsClear() {
        var p = TurnPlanner.plan(seed(250), 0.0, 0, 3000, UNSEEN);
        assertEquals(TurnPlanner.STRAIGHT, p.action, "can't see a problem: go");
        assertTrue(TurnPlanner.feasible(seed(250), 0.0, 0, 3000, UNSEEN));
    }

    @Test
    void aLowRidgeIsClimbedOverByRaisingTheTrough() {
        // holding above the 350 floor; a ridge just too high for the corridor at that floor
        var s = new FlightModel3D.State(0, 380, 0, 0, 0, 1.5, 350, 30, false);
        s.cmd = PorpoiseLaw.ALT_HOLD.dive();
        var p = TurnPlanner.plan(s, 0.0, 0, 3000, wall(345, 300));
        assertEquals(TurnPlanner.STRAIGHT, p.action, "stays on course");
        assertTrue(p.floor > 350.0 && p.floor <= 350.0 + 60.0, "with a raised floor: " + p.floor);
    }

    @Test
    void aTallFiniteWallIsTurnedAround() {
        var p = TurnPlanner.plan(seed(250), 0.0, 0, 3000, wall(600, 60));
        assertEquals(TurnPlanner.TURN, p.action);
        assertNotEquals(0.0, p.heading);
    }

    @Test
    void boxedInItTurnsTowardTheMostRoom() {
        // an endless wall 30 blocks ahead, inside the turn radius: no heading clears, so the planner turns toward the
        // one whose path meets the wall farthest away (the guardian rescues if it closes; the emergency reversal that
        // was here is purged, RW 2026-10-11)
        Terrain2D close = (x, z) -> (z >= 30 && z <= 60) ? 900 : 64;
        var p = TurnPlanner.plan(seed(250), 0.0, 0, 3000, close);
        assertEquals(TurnPlanner.TURN, p.action);
        assertTrue(Math.abs(p.heading) >= 20.0, "turns away: " + p.heading);   // 52 here: the longest path before the wall
        TurnPlanner.boxedInTurns = false;
        try {
            var q = TurnPlanner.plan(seed(250), 0.0, 0, 3000, close);
            assertEquals(TurnPlanner.STRAIGHT, q.action, "the other policy: straight on");
        } finally { TurnPlanner.boxedInTurns = true; }
    }

    @Test
    void tooLowOverFlatGroundIsNotFeasible() {
        assertFalse(TurnPlanner.feasible(seed(70), 0.0, 0, 3000, FLAT_LOW), "the climb's first dive would hit the ground");
        assertTrue(TurnPlanner.feasible(seed(250), 0.0, 0, 3000, FLAT_LOW));
    }

    @Test
    void headingsThatCantSeeFarEnoughDontClimbTheFloorLadder() {
        // a tall wall 30 blocks ahead on a narrow loaded strip, nothing loaded either side (the render edge): every
        // turn leaves the strip and can't see far enough at any floor, so each heading is tried at one floor only
        int[] samples = { 0 };
        Terrain2D strip = (x, z) -> {
            samples[0]++;
            if (Math.abs(x) > 4) return Double.NaN;
            return (z >= 30 && z <= 60) ? 900 : 64;
        };
        var p = TurnPlanner.plan(seed(250), 0.0, 0, 3000, strip);
        assertEquals(TurnPlanner.STRAIGHT, p.action, "nothing clears and no turn is seen to hit: straight on");
        // about 19,000 samples (one rollout per heading); climbing every heading's ladder took about 273,000
        assertTrue(samples[0] < 40_000, "terrain sampled " + samples[0] + " times");
    }
}
