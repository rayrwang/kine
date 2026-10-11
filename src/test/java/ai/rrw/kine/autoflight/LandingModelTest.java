package ai.rrw.kine.autoflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LandingModelTest {
    // a spot at the origin, its top surface at y 0; starts from the south, facing it (yaw 0 faces +z)
    static LandingModel.Result land(double h, double depressionDeg, double speed, double headingDeg) {
        double d = h / Math.tan(Math.toRadians(depressionDeg));
        double yr = Math.toRadians(-headingDeg);
        return LandingModel.simulate(new LandingModel.Law(), 0.5, h, 0.5 - d,
            Math.sin(yr) * speed, -0.1, Math.cos(yr) * speed, headingDeg, 10.0, 0.5, 0.0, 0.5, 6000, 0);
    }

    @Test
    void landsOnTheSpotWithNoDamageFromManyStarts() {
        for (double h : new double[]{ 40, 80, 150, 250 })
            for (double ang : new double[]{ 30, 45, 80 })
                for (double speed : new double[]{ 0.5, 1.5, 2.5 })
                    for (double head : new double[]{ 0, 90, 180 }) {
                        LandingModel.Result r = land(h, ang, speed, head);
                        String c = "h " + h + " angle " + ang + " speed " + speed + " heading " + head;
                        assertTrue(r.touched(), c);
                        assertEquals(0.0, r.damage(), c);
                        assertTrue(r.offset() <= 3.0, c + " offset " + r.offset());
                    }
    }

    @Test
    void aOneSecondFreezeAnywhereCostsNothing() {
        // fly the landing from 250 up, freezing the look for 20 ticks from every 4th tick
        LandingModel.Result nominal = land(250, 45, 1.5, 0);
        for (int at = 0; at < nominal.ticks(); at += 4) {
            assertEquals(0.0, frozenLanding(250, 45, 1.5, at, 20), "freeze at tick " + at);
        }
    }

    // the same landing with the look held still for `len` ticks from tick `at`; returns the fall damage
    static double frozenLanding(double h, double ang, double speed, int at, int len) {
        double d = h / Math.tan(Math.toRadians(ang));
        double x = 0.5, y = h, z = 0.5 - d, yaw = 0, pitch = 10;
        double[] v = { 0, -0.1, speed };
        double fall = 1;
        LandingModel.Law law = new LandingModel.Law();
        for (int t = 0; t < 6000; t++) {
            law.update(x, y, z, v[1], 0.5, 0, 0.5);
            if (t < at || t >= at + len) {
                yaw += Math.max(-LandingModel.YAW_RATE, Math.min(LandingModel.YAW_RATE, FlightModel3D.wrap180(law.yaw - yaw)));
                pitch = FlightModel3D.ease(pitch, law.pitch);
            }
            FlightModel3D.physicsStep(yaw, pitch, v);
            x += v[0]; y += v[1]; z += v[2];
            if (v[1] < 0) fall -= v[1];
            if (v[1] > -0.5 && fall > 1) fall = 1;
            if (y <= 0) return Math.max(0, fall - 3);
        }
        return Double.NaN;
    }

    @Test
    void phasesRunInOrder() {
        LandingModel.Law law = new LandingModel.Law();
        double x = 0.5, y = 200, z = -100, yaw = 0, pitch = 10;
        double[] v = { 0, -0.1, 1.5 };
        int last = LandingModel.APPROACH;
        for (int t = 0; t < 6000 && y > 0; t++) {
            law.update(x, y, z, v[1], 0.5, 0, 0.5);
            assertTrue(law.phase >= last, "phase went back at tick " + t);
            last = law.phase;
            yaw += Math.max(-15, Math.min(15, FlightModel3D.wrap180(law.yaw - yaw)));
            pitch = FlightModel3D.ease(pitch, law.pitch);
            FlightModel3D.physicsStep(yaw, pitch, v);
            x += v[0]; y += v[1]; z += v[2];
        }
        assertEquals(LandingModel.FINAL, last);
    }

    @Test
    void lowAndFarAndFacingAwayIsNotOffered() {
        // 20 up, the spot 10 degrees below the horizon (113 blocks), heading away: the rollout falls short
        LandingModel.Result r = land(20, 10, 1.5, 180);
        assertTrue(!r.touched() || r.offset() > LandingModel.MAX_OFFSET);
    }
}
