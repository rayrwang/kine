package ai.rrw.kine.autoflight;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * From the flight audit of 2026-10-11 (findings 2 and 3), adapted to the fixes: the endless-wall streak ends after
 * real progress past the wall in every nav mode, and the emergency bank keeps the side it was planned on.
 */
class AuditFlightTest {

    @BeforeEach
    void fullSight() { TurnPlanner.setRenderRange(TurnPlanner.SCAN_DIST); }

    // ---- the endless-wall streak outside MANAGED ----

    static int emergencyCount(TurnController c) throws Exception {
        Field f = TurnController.class.getDeclaredField("emergencyCount"); f.setAccessible(true);
        return f.getInt(c);
    }
    static int controllerTick(TurnController c) throws Exception {
        Field f = TurnController.class.getDeclaredField("t"); f.setAccessible(true);
        return f.getInt(c);
    }

    // fly on in the clear until fast, with a re-plan due, then put an endless tall wall 30 blocks ahead and plan
    static Terrain2D meetWall(TurnController c, FlightModel3D.State s, Terrain2D flat) throws Exception {
        while (!(Math.hypot(s.vx, s.vz) * 20 >= 28 && controllerTick(c) % TurnController.REPLAN_EVERY == 0)) {
            double h = c.update(s.copy(), s.x, s.z + 3000.0, flat);
            FlightModel3D.step(s, h);
        }
        final double z0 = s.z;
        Terrain2D wallAhead = (x, z) -> (z >= z0 + 30 && z <= z0 + 60) ? 900 : 64;
        c.update(s.copy(), s.x, s.z + 3000.0, wallAhead);
        return wallAhead;
    }

    // fly the reversal through until the emergency ends
    static void flyReversal(TurnController c, FlightModel3D.State s, Terrain2D wallAhead, double[] dest) {
        for (int t = 0; t < 200 && c.action() == TurnPlanner.EMERGENCY; t++) {
            if (dest == null) FlightModel3D.step(s, c.update(s.copy(), s.x, s.z + 3000.0, wallAhead), FlightModel3D.EMERGENCY_DELTA);
            else FlightModel3D.step(s, c.update(s.copy(), dest[0], dest[1], wallAhead), FlightModel3D.EMERGENCY_DELTA);
        }
    }

    /** SELECTED heading (or no nav): TurnGuard.destOf puts the destination 3000 blocks ahead of the aircraft every tick,
     *  so the distance to it never falls. Three unrelated walls, about 13 km apart, each met once: the third gets its
     *  own reversal, not an immediate hand-off. */
    @Test
    void separateWallsInSelectedModeDoNotAddUpToAnEndlessWallHandOff() throws Exception {
        TurnController c = new TurnController();
        var s = new FlightModel3D.State(0, 300, 0, 0, -0.3, 1.5, 350, 30, true);
        s.cmd = PorpoiseLaw.CLIMB.dive();
        Terrain2D flat = (x, z) -> 64;
        for (int wall = 1; wall <= 3; wall++) {
            Terrain2D wallAhead = meetWall(c, s, flat);
            assertNotEquals(TurnPlanner.DISENGAGE, c.action(),
                "wall " + wall + ": handed off at once (emergencyCount carried over from earlier walls)");
            assertEquals(TurnPlanner.EMERGENCY, c.action(), "wall " + wall + " is inside the turn radius");
            flyReversal(c, s, wallAhead, null);
            // a long clear flight: about 13 km past the wall
            for (int t = 0; t < 10000; t++) FlightModel3D.step(s, c.update(s.copy(), s.x, s.z + 3000.0, flat));
            assertEquals(0, emergencyCount(c), "wall " + wall + ": the streak should end after getting well past the wall");
        }
    }

    /** The streak still holds while the aircraft is short of where it began: a clear path on the way back toward
     *  the wall is not progress past it. (A few blocks of slack: the destination 3000 blocks ahead counts a little of
     *  the sideways offset the reversal leaves as progress.) */
    @Test
    void theStreakHoldsUntilTheAircraftIsWellPastWhereItBegan() throws Exception {
        TurnController c = new TurnController();
        var s = new FlightModel3D.State(0, 300, 0, 0, -0.3, 1.5, 350, 30, true);
        s.cmd = PorpoiseLaw.CLIMB.dive();
        Terrain2D flat = (x, z) -> 64;
        Terrain2D wallAhead = meetWall(c, s, flat);
        assertEquals(TurnPlanner.EMERGENCY, c.action());
        double zStart = s.z;
        flyReversal(c, s, wallAhead, null);
        boolean reset = false;
        for (int t = 0; t < 3000 && !reset; t++) {
            FlightModel3D.step(s, c.update(s.copy(), s.x, s.z + 3000.0, flat));
            reset = emergencyCount(c) == 0;
            if (reset) assertTrue(s.z > zStart + TurnController.PROGRESS_RESET - 5.0,
                "reset at z " + s.z + ", only " + (s.z - zStart) + " past where the streak began");
        }
        assertTrue(reset, "the streak never ended");
    }

    /** No nav: the destination is 3000 blocks along the current track, which the reversal turns round. Flying on
     *  away from the wall ends the streak, so a later wall gets its own reversal. */
    @Test
    void withNoNavFlyingOnAwayFromTheWallEndsTheStreak() throws Exception {
        TurnController c = new TurnController();
        var s = new FlightModel3D.State(0, 300, 0, 0, -0.3, 1.5, 350, 30, true);
        s.cmd = PorpoiseLaw.CLIMB.dive();
        Terrain2D flat = (x, z) -> 64;
        Terrain2D wallAhead = meetWall(c, s, flat);
        assertEquals(TurnPlanner.EMERGENCY, c.action());
        for (int t = 0; t < 200 && c.action() == TurnPlanner.EMERGENCY; t++) {
            double sp = Math.hypot(s.vx, s.vz);
            FlightModel3D.step(s, c.update(s.copy(), s.x + s.vx / sp * 3000.0, s.z + s.vz / sp * 3000.0, wallAhead),
                FlightModel3D.EMERGENCY_DELTA);
        }
        for (int t = 0; t < 2000; t++) {
            double sp = Math.hypot(s.vx, s.vz);
            FlightModel3D.step(s, c.update(s.copy(), s.x + s.vx / sp * 3000.0, s.z + s.vz / sp * 3000.0, wallAhead));
        }
        assertEquals(0, emergencyCount(c), "the streak should end once well clear of the wall");
    }

    // ---- the emergency bank's side at two ticks per frame (10 fps: minimised window, or 10 min AFK) ----

    /** A wall ahead and to the right (-x, yaw +90) inside the turn radius, room to the left (+x) only as far as
     *  another wall, and walls behind: nothing clears, so the planner reverses toward the open left side (yaw
     *  course - 179), and the controller keeps that side. */
    @Test
    void theControllerKeepsThePlannedSide() {
        Terrain2D box = (x, z) -> ((z >= 30 && x <= 8) || x >= 60 || x <= -60 || z <= -60) ? 900 : 64;
        var s = new FlightModel3D.State(0, 250, 0, 0, -0.3, 1.5, 350, 30, true);
        s.cmd = PorpoiseLaw.CLIMB.dive();
        TurnController c = new TurnController();
        double h = c.update(s.copy(), 0, 3000, box);
        assertEquals(TurnPlanner.EMERGENCY, c.action());
        assertEquals(-179.0, h, 1e-9, "reverses toward the open left side");
        assertEquals(-1, c.emergencySide());
        // the bank keeps that side while the escape heading is still being turned to
        for (int t = 0; t < 40 && c.action() == TurnPlanner.EMERGENCY; t++) {
            h = c.update(s.copy(), 0, 3000, box);
            assertEquals(-1, c.emergencySide());
            FlightModel3D.step(s, h, FlightModel3D.EMERGENCY_DELTA);
        }
    }

    /** In a committed right turn (look = velYaw + 20) the planner calls an EMERGENCY toward the open left side
     *  (heading course - 179). Minecraft.runTick runs the due ticks before control(); when the frame after the plan
     *  spans two ticks, the second tick still turns right and the escape heading falls more than 180 deg to the left
     *  of the track, so reading the side from it would bank right. The bank uses the planned side instead. */
    @Test
    void emergencyBankKeepsThePlannedSideWhenAFrameSpansTwoTicks() {
        var s = new FlightModel3D.State(0, 300, 0, 0, -0.3, 1.5, 350, 30, false);
        s.cmd = PorpoiseLaw.ALT_HOLD.dive();
        double look = 0;
        for (int i = 0; i < 20; i++) {
            double vy = FlightModel3D.velYaw(s.vx, s.vz);
            look = vy + Math.max(-20, Math.min(20, FlightModel3D.wrap180(90 - vy)));
            FlightModel3D.stepLook(s, look);
        }
        double course = FlightModel3D.velYaw(s.vx, s.vz), desired = course - 179.0, prev = course, rot = 0;
        int side = -1;                                           // as the controller latches it for course - 179
        FlightModel3D.stepLook(s, look);                         // the frame's second tick, old look
        rot += FlightModel3D.wrap180(FlightModel3D.velYaw(s.vx, s.vz) - prev); prev = FlightModel3D.velYaw(s.vx, s.vz);
        assertTrue(FlightModel3D.wrap180(desired - prev) > 0, "the setup: the escape heading has wrapped to the right");
        for (int frame = 0; frame < 30; frame++) {
            double dt = frame == 0 ? 0.1 : 0.05;
            double velYaw = FlightModel3D.velYaw(s.vx, s.vz);
            double bank = velYaw + TurnController.bankOffset(desired, velYaw, side, FlightModel3D.EMERGENCY_DELTA);
            look += Math.max(-450 * dt, Math.min(450 * dt, FlightModel3D.wrap180(bank - look)));   // Autopilot's flick rate
            FlightModel3D.stepLook(s, look);
            double c = FlightModel3D.velYaw(s.vx, s.vz); rot += FlightModel3D.wrap180(c - prev); prev = c;
        }
        assertTrue(rot < 0, "planned a left (open side) reversal, flew " + rot + " deg (right, into the blocked side)");
    }

    @Test
    void bankOffsetTurnsThePlannedWayAndEasesOffNearTheEscapeHeading() {
        double ed = FlightModel3D.EMERGENCY_DELTA;
        assertEquals(-ed, TurnController.bankOffset(-179.0, 0.0, -1, ed), 1e-9);
        assertEquals(-ed, TurnController.bankOffset(-179.0, 1.5, -1, ed), 1e-9, "wrapped past 180: still left");
        assertEquals(ed, TurnController.bankOffset(179.0, -1.5, 1, ed), 1e-9, "wrapped past 180: still right");
        assertEquals(-10.0, TurnController.bankOffset(-179.0, -169.0, -1, ed), 1e-9, "close to the escape heading");
        assertEquals(2.0, TurnController.bankOffset(-179.0, -181.0, -1, ed), 1e-9, "a small overshoot is taken back");
    }
}
