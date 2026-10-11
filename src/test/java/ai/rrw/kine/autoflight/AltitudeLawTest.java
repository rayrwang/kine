package ai.rrw.kine.autoflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The flight director's law: the arming hysteresis and the vertical modes. */
class AltitudeLawTest {

    @Test
    void firstVerdictOfAGlideIsTakenRaw() {
        var a = new AltitudeLaw();
        assertFalse(a.tick(false, 30, 100));
        assertTrue(a.tooLow);
        var b = new AltitudeLaw();
        assertTrue(b.tick(true, 30, 100));
        assertFalse(b.tooLow);
    }

    @Test
    void shortLossesOfAPathDoNotDisarm() {
        var a = new AltitudeLaw();
        assertTrue(a.tick(true, 30, 100));
        for (int i = 0; i < AltitudeLaw.LOW_TRIP - 1; i++) assertTrue(a.tick(false, 30, 100), "tick " + i);
        assertFalse(a.tick(false, 30, 100), "trips on the LOW_TRIP-th bad tick");
        assertTrue(a.tooLow);
        // draining: -2 per good tick; it clears once at or below LOW_CLEAR
        int good = 0;
        while (!a.tick(true, 30, 100)) good++;
        assertEquals(3, good);
        assertFalse(a.tooLow);
    }

    @Test
    void alternatingVerdictsNeverTrip() {
        var a = new AltitudeLaw();
        a.tick(true, 30, 100);
        for (int i = 0; i < 1000; i++) assertTrue(a.tick(i % 2 == 0, 30, 100));
    }

    @Test
    void verticalModeBands() {
        assertEquals(AltitudeLaw.VM_ALT, AltitudeLaw.verticalMode(3));
        assertEquals(AltitudeLaw.VM_ALT, AltitudeLaw.verticalMode(-10));
        assertEquals(AltitudeLaw.VM_ALT_STAR, AltitudeLaw.verticalMode(-25));
        assertEquals(AltitudeLaw.VM_CLB, AltitudeLaw.verticalMode(-41));
        assertEquals(AltitudeLaw.VM_DESC, AltitudeLaw.verticalMode(41));
    }

    @Test
    void cruiseLevelFollowsTheInitialHeadingAndTheTargetIsClamped() {
        var a = new AltitudeLaw();
        a.initCruise(90);   assertEquals(350, a.targetAlt);   // eastbound: odd
        a.initCruise(270);  assertEquals(360, a.targetAlt);   // westbound: even
        a.setTargetAltitude(5000); assertEquals(AltitudeLaw.ALT_MAX, a.targetAlt);
        a.setTargetAltitude(-500); assertEquals(AltitudeLaw.ALT_MIN, a.targetAlt);
    }

    @Test
    void holdsAltitudeOnTheModelPhysics() {
        // fly the law on the 3-D model straight; after settling, every trough sits just above the target
        var a = new AltitudeLaw();
        a.setTargetAltitude(300); a.floorAlt = 300;
        var s = new FlightModel3D.State(0, 320, 0, 0, -0.5, 1.5, 300, 30, true);
        double min = Double.MAX_VALUE;
        for (int t = 0; t < 12000; t++) {
            a.startTick();
            s.pitch = FlightModel3D.ease(s.pitch, a.law.cmd);
            double[] v = { s.vx, s.vy, s.vz };
            FlightModel3D.physicsStep(0.0, s.pitch, v);
            s.vx = v[0]; s.vy = v[1]; s.vz = v[2]; s.x += s.vx; s.y += s.vy; s.z += s.vz;
            assertTrue(a.tick(true, Math.sqrt(s.vx * s.vx + s.vz * s.vz) * 20.0, s.y));
            if (t > 6000) min = Math.min(min, s.y);
        }
        assertFalse(a.law.climbing);
        assertTrue(min > 300 && min < 300 + PorpoiseLaw.DESC_MARGIN, "troughs settle just above the target: " + min);
        assertEquals(AltitudeLaw.VM_ALT, a.vmode);
        assertTrue(a.measuredPeriod > 300 && a.measuredPeriod < 350, "hold period about 325 ticks: " + a.measuredPeriod);
    }
}
