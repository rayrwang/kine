package ai.rrw.kine.fireworks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A rocket used while fast hitting its own player, against RW's three logged takeoffs and the rules found with them. */
class RocketHitTest {
    static final double[] PITCHES = { -85.0, -80.0, -75.0, -70.0, -65.0 };

    @Test
    void rwsThreeTakeoffs() {
        // 2026-10-11 22:53 EDT, right arm, leaning at -70: 167 burst at life 4 (logged by the server), 145 and 205 burnt out
        assertEquals(4, RocketHit.hitTick(167, -70, true, 1.7));
        assertEquals(-1, RocketHit.hitTick(145, -70, true, 1.7));
        assertEquals(-1, RocketHit.hitTick(205, -70, true, 1.7));
    }

    @Test
    void neverStraightUpNorAtCardinals() {
        for (double yaw = 0; yaw < 360; yaw += 0.5) {
            assertFalse(RocketHit.hits(yaw, -90, true), "straight up, right, yaw " + yaw);
            assertFalse(RocketHit.hits(yaw, -90, false), "straight up, left, yaw " + yaw);
        }
        for (double yaw : new double[]{ 0, 90, 180, 270, -90, -180 })
            for (double pitch : new double[]{ -80, -70, -45, 0 }) {
                assertFalse(RocketHit.hits(yaw, pitch, true));
                assertFalse(RocketHit.hits(yaw, pitch, false));
            }
    }

    @Test
    void neverBothArmsAtOnce() {
        for (double pitch : new double[]{ -70, 0 })
            for (double yaw = 0; yaw < 360; yaw += 0.5)
                assertFalse(RocketHit.hits(yaw, pitch, true) && RocketHit.hits(yaw, pitch, false), "yaw " + yaw + " pitch " + pitch);
    }

    @Test
    void theBands() {
        assertTrue(RocketHit.hits(70, -70, true));     // right arm: about 63 to 84 and every 90
        assertTrue(RocketHit.hits(160, -70, true));
        assertFalse(RocketHit.hits(70, -70, false));
        assertTrue(RocketHit.hits(15, -70, false));    // left arm: about 6 to 27 and every 90
        assertFalse(RocketHit.hits(15, -70, true));
    }

    @Test
    void theClimbHeading() {
        float y = RocketHit.safeYaw(167f, PITCHES, true);
        assertTrue(RocketHit.safe(y, PITCHES, true));
        assertTrue(Math.abs(y - 167f) <= 15f, "moved " + (y - 167f));
        assertEquals(145f, RocketHit.safeYaw(145f, PITCHES, true));   // already safe: unchanged
        for (float w = -180f; w < 180f; w += 7f) {
            float s = RocketHit.safeYaw(w, PITCHES, false);
            assertTrue(RocketHit.safe(s, PITCHES, false), "from " + w);
            assertTrue(Math.abs(s - w) <= 15f, "from " + w + " to " + s);
        }
    }
}
