package ai.rrw.kine.autoflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HoldModelTest {

    @Test
    void turnsLessTowardTheFixAndMoreAway() {
        // flying north (yaw 180) from 100 south of the fix: straight at it, so 2 deg less than the 7
        assertEquals(180.0 + 5.0, HoldModel.lookYaw(0, 100, 0, 0, 180.0, false), 1e-9);
        // flying south, away from it: 2 more
        assertEquals(0.0 + 9.0, HoldModel.lookYaw(0, 100, 0, 0, 0.0, false), 1e-9);
        // square to it (a centred circle): the plain 7
        assertEquals(90.0 + 7.0, HoldModel.lookYaw(0, 100, 0, 0, 90.0, false), 1e-9);
        // while the law climbs, at most 5
        assertEquals(0.0 + 5.0, HoldModel.lookYaw(0, 100, 0, 0, 0.0, true), 1e-9);
    }

    @Test
    void spawnLightFollowsTheGame() {
        // overworld: brightness 15 - darkening at most a roll of 0 to 7
        assertFalse(HoldModel.darkEnoughToSpawn(0, false, 7));    // noon
        assertFalse(HoldModel.darkEnoughToSpawn(7, false, 7));    // dawn, the first tick with no spawning
        assertTrue(HoldModel.darkEnoughToSpawn(8, false, 7));
        assertTrue(HoldModel.darkEnoughToSpawn(11, false, 7));    // midnight
        assertTrue(HoldModel.darkEnoughToSpawn(0, true, 7));      // a thunderstorm, any time
    }

    @Test
    void dawnCountdown() {
        assertEquals(22822 - 18000, HoldModel.ticksToDawn(18000, 0f));        // midnight, clear
        assertEquals(23046 - 18000, HoldModel.ticksToDawn(18000, 1f));        // midnight, full rain
        assertEquals(22822 - 18000, HoldModel.ticksToDawn(18000 + 24000L * 5, 0f));   // any day
        assertEquals(22822 + 24000 - 23500, HoldModel.ticksToDawn(23500, 0f)); // after dawn: the next one
    }

    // Flown on kine's flight model with the live law holding 350, entered from steady straight flight over the fix:
    // from the first tick it never goes below the target (its lowest about 352.6, at any phase of entry), and once
    // centred (after about 5 minutes) it stays within about 120 blocks of the fix.
    @Test
    void holdsHeightAndStaysOverTheFix() {
        double target = 350.0;
        var s = new FlightModel3D.State(0, 400, 0, 0, -0.3, 1.5, target, 30, false);
        s.cmd = PorpoiseLaw.ALT_HOLD.dive();
        int pulls = 0;
        for (int t = 0; t < 20000 && pulls < 12; t++) {   // straight flight until the cycle is steady
            int ph = s.phase;
            FlightModel3D.step(s, 0.0);
            if (ph == PorpoiseLaw.DIVE && s.phase != PorpoiseLaw.DIVE) pulls++;
        }
        assertEquals(12, pulls);
        s.x = 0; s.z = 0;

        double farthest = 0, lowestTrough = Double.MAX_VALUE, lowest = Double.MAX_VALUE;
        int troughs = 0;
        for (int t = 0; t < 14400; t++) {
            int ph = s.phase;
            FlightModel3D.stepLook(s, HoldModel.lookYaw(s.x, s.z, 0, 0, FlightModel3D.velYaw(s.vx, s.vz), s.climbing));
            lowest = Math.min(lowest, s.y);
            if (t < 7200) continue;   // the circle centres over its first laps
            farthest = Math.max(farthest, Math.hypot(s.x, s.z));
            if (ph == PorpoiseLaw.DIVE && s.phase != PorpoiseLaw.DIVE) { lowestTrough = Math.min(lowestTrough, s.trough); troughs++; }
        }
        assertTrue(troughs > 20, "the porpoise kept cycling: " + troughs);
        assertTrue(lowestTrough >= target, "lowest trough " + lowestTrough);
        assertTrue(lowest >= target, "lowest point " + lowest);
        assertTrue(farthest < 160, "farthest from the fix " + farthest);   // 121 with the a1b6901 law; about 150 with the level-off law at 15 deg a tick (its faster cruise widens the circle)
    }
}
