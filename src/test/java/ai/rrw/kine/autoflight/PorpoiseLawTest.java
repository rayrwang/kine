package ai.rrw.kine.autoflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The porpoise law: the phase cycle, the profile limits, and the climb or hold choice from the trough. */
class PorpoiseLawTest {

    @Test
    void cyclesThroughItsPhasesWithinTheProfileLimits() {
        var law = new PorpoiseLaw();
        law.restartClimb();
        int pulls = 0;
        double speed = 20, y = 100;
        for (int t = 0; t < 2000; t++) {
            int before = law.phase;
            speed = before == PorpoiseLaw.DIVE ? speed + 0.5 : speed - 0.3;   // dives build speed
            boolean pulled = law.update(y, speed, 350);
            if (pulled) { pulls++; speed = 20; assertEquals(PorpoiseLaw.DIVE, before); }
            var p = law.profile();
            assertTrue(law.cmd >= p.pullUp() - 1e-9 && law.cmd <= p.dive() + 1e-9, "command within the profile");
            if (law.phase == PorpoiseLaw.PULL_UP) assertTrue(law.pullUpTicks <= p.pullUpHold());
            if (before == PorpoiseLaw.SWEEP && law.phase == PorpoiseLaw.DIVE) assertEquals(p.dive(), law.cmd, 0.0);
        }
        assertTrue(pulls >= 5, "pulls: " + pulls);
        assertTrue(law.climbing, "always below the target here");
    }

    @Test
    void climbPullsUpAtTheTriggerSpeed() {
        var law = new PorpoiseLaw();
        law.restartClimb();
        assertFalse(law.update(100, PorpoiseLaw.CLIMB.triggerSpeed() - 1e-6, 350));
        assertTrue(law.update(100, PorpoiseLaw.CLIMB.triggerSpeed(), 350));
        assertEquals(PorpoiseLaw.PULL_UP, law.phase);
        assertEquals(PorpoiseLaw.CLIMB.pullUp(), law.cmd, 0.0);
    }

    @Test
    void holdPullsUpOnAltitudeAndTheTroughChoosesTheProfile() {
        var law = new PorpoiseLaw();
        law.restartClimb();
        law.climbing = false;                                   // holding
        assertFalse(law.update(357, 60, 350), "above target + DESC_MARGIN keeps diving whatever the speed");
        assertTrue(law.update(356, 0, 350));                    // within DESC_MARGIN of the target
        assertFalse(law.climbing, "the trough 356 is above the target: keep holding");

        var low = new PorpoiseLaw();
        low.restartClimb();
        low.update(340, 0, 350);                                // a lower point earlier in the cycle
        assertTrue(low.update(355, 50, 350));                   // climbing, pulls up at the trigger speed
        assertTrue(low.climbing, "the trough is the cycle's lowest point (340), below the target");
        assertEquals(340, low.trough, 0.0);
    }
}
