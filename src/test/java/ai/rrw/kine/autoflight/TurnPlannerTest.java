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
    void aWallInsideTheTurnRadiusIsAnEmergency() {
        Terrain2D close = (x, z) -> (z >= 30 && z <= 60) ? 900 : 64;   // endless, 30 blocks ahead
        var p = TurnPlanner.plan(seed(250), 0.0, 0, 3000, close);
        assertEquals(TurnPlanner.EMERGENCY, p.action);
        assertEquals(179.0, Math.abs(p.heading), 1e-9, "reverses");
    }

    @Test
    void tooLowOverFlatGroundIsNotFeasible() {
        assertFalse(TurnPlanner.feasible(seed(70), 0.0, 0, 3000, FLAT_LOW), "the climb's first dive would hit the ground");
        assertTrue(TurnPlanner.feasible(seed(250), 0.0, 0, 3000, FLAT_LOW));
    }

    @Test
    void controllerHandsBackAfterRepeatedReversalsAgainstOneWall() {
        Terrain2D close = (x, z) -> 900;   // a wall everywhere: every plan is an emergency
        var c = new TurnController();
        var s = seed(250);
        boolean handedBack = false;
        for (int t = 0; t < 400 && !handedBack; t++) {
            c.update(s.copy(), 0, 3000, close);
            handedBack = c.action() == TurnPlanner.DISENGAGE;
        }
        assertTrue(handedBack);
    }
}
