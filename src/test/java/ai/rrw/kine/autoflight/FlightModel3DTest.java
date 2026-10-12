package ai.rrw.kine.autoflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The 3-D flight model: physics invariants and two short golden traces. The golden numbers are the
 *  model's output with the level-off law at 15 deg a tick (2026-10-11; the earlier law's were checked by the cleanup
 *  branch's equivalence harness); if one changes, the predicted path changed. */
class FlightModel3DTest {
    private static final double EPS = 1e-9;

    @Test
    void straightFlightStaysOnItsLineAndMatchesTheGoldenTrace() {
        var s = new FlightModel3D.State(0, 200, 0, 0, -0.5, 1.5, 350, 20, true);
        s.cmd = 40;
        double[][] golden = {   // tick, y, z
            {100, 190.50952971070458, 183.10516377411867}, {200, 241.04912355476148, 224.9065134610902},
            {300, 190.94381718219844, 367.9629869644756},  {400, 262.9273899972791, 492.0633073202938},
            {500, 242.31611420777938, 560.596276811804},   {600, 214.39079497653293, 746.9589539287169}};
        int g = 0, pulls = 0, prev = s.phase;
        for (int t = 1; t <= 600; t++) {
            FlightModel3D.step(s, 0.0);
            assertEquals(0.0, s.x, 0.0, "flying along +z never drifts sideways");
            if (prev == PorpoiseLaw.DIVE && s.phase != PorpoiseLaw.DIVE) pulls++;
            prev = s.phase;
            if (g < golden.length && t == (int) golden[g][0]) {
                assertEquals(golden[g][1], s.y, EPS, "y at tick " + t);
                assertEquals(golden[g][2], s.z, EPS, "z at tick " + t);
                g++;
            }
        }
        assertEquals(3, pulls);
        assertTrue(s.climbing, "still below the 350 target");
    }

    @Test
    void turningConvergesOnTheTargetHeadingAndMatchesTheGoldenTrace() {
        var s = new FlightModel3D.State(0, 300, 0, 0, -0.5, 1.5, 300, 20, false);
        s.cmd = 38;
        double[][] golden = {   // tick, x, y, z
            {50, -29.674080254107317, 313.9269100636415, 37.680810521284045},
            {100, -43.049185805217334, 317.72477305933614, 38.130478836228704},
            {300, -319.4165977846555, 298.3076599306456, 38.131885316882496}};
        int g = 0;
        double lastOff = 180;
        for (int t = 1; t <= 300; t++) {
            FlightModel3D.step(s, 90.0);
            double off = Math.abs(FlightModel3D.wrap180(90.0 - FlightModel3D.velYaw(s.vx, s.vz)));
            assertTrue(off <= lastOff + 1e-9, "the heading error never grows while turning toward a fixed heading");
            lastOff = off;
            if (g < golden.length && t == (int) golden[g][0]) {
                assertEquals(golden[g][1], s.x, EPS); assertEquals(golden[g][2], s.y, EPS); assertEquals(golden[g][3], s.z, EPS);
                g++;
            }
        }
        assertTrue(lastOff < 1e-9);
    }

    @Test
    void lookOffsetIsLimitedPerTick() {
        // a heading reversal can only turn the look DELTA_MAX off the velocity
        var a = new FlightModel3D.State(0, 300, 0, 0, 0, 1.5, 300, 30, true);
        var b = a.copy();
        FlightModel3D.step(a, 180.0);
        FlightModel3D.stepLook(b, FlightModel3D.DELTA_MAX);   // velocity yaw is 0 here
        assertEquals(b.x, a.x, 0.0); assertEquals(b.z, a.z, 0.0); assertEquals(b.y, a.y, 0.0);
    }

    @Test
    void velYawAndWrap() {
        assertEquals(0.0, FlightModel3D.velYaw(0, 1), EPS);       // +z is yaw 0
        assertEquals(90.0, FlightModel3D.velYaw(-1, 0), EPS);     // -x is yaw 90
        assertEquals(-90.0, FlightModel3D.velYaw(1, 0), EPS);
        assertEquals(180.0, FlightModel3D.wrap180(-180.0), 0.0);  // (-180, 180]
        assertEquals(-170.0, FlightModel3D.wrap180(190.0), 0.0);
        assertEquals(10.0, FlightModel3D.wrap180(730.0), 0.0);
    }

    @Test
    void copyIsIndependent() {
        var s = new FlightModel3D.State(1, 2, 3, 0.1, 0.2, 0.3, 350, 10, true);
        s.phase = PorpoiseLaw.SWEEP; s.cmd = 5; s.programTicks = 7; s.cycleMin = 2;
        var c = s.copy();
        FlightModel3D.step(c, 45);
        assertEquals(1, s.x, 0.0); assertEquals(PorpoiseLaw.SWEEP, s.phase); assertEquals(5, s.cmd, 0.0); assertEquals(2, s.cycleMin, 0.0);
    }
}
