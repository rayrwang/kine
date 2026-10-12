package ai.rrw.kine.autoflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The porpoise law: the program after the pull-up, the profile limits, and the climb or hold choice from the trough. */
class PorpoiseLawTest {

    @Test
    void cyclesThroughItsProgramWithinTheProfileLimits() {
        var law = new PorpoiseLaw();
        law.restartClimb();
        int pulls = 0;
        double speed = 20, y = 100;
        int[] order = {PorpoiseLaw.LEVEL, PorpoiseLaw.PULL_UP, PorpoiseLaw.SWEEP, PorpoiseLaw.GLIDE, PorpoiseLaw.DIVE};
        int stage = 4;
        for (int t = 0; t < 4000; t++) {
            int before = law.phase;
            speed = before == PorpoiseLaw.DIVE ? speed + 0.5 : speed - 0.1;   // dives build speed
            boolean pulled = law.update(y, speed, 350);
            if (pulled) { pulls++; speed = 20; assertEquals(PorpoiseLaw.DIVE, before); stage = 0; }
            var p = law.profile();
            double lo = Math.min(p.pullUp(), p.level()), hi = Math.max(p.dive(), Math.max(p.glide(), p.level()));
            assertTrue(law.cmd >= lo - 1e-9 && law.cmd <= hi + 1e-9, "command within the profile");
            // the phases come in program order, skipping none that have ticks
            while (stage < 4 && law.phase != order[stage]) stage++;
            assertEquals(order[stage], law.phase, "phase out of order at tick " + t);
            if (law.phase == PorpoiseLaw.DIVE) assertEquals(p.dive(), law.cmd, 0.0);
        }
        assertTrue(pulls >= 5, "pulls: " + pulls);
        assertTrue(law.climbing, "always below the target here");
    }

    @Test
    void theProgramIsLevelThenPullUpThenSweepThenGlide() {
        var p = PorpoiseLaw.CLIMB;
        int t = 0;
        for (; t < p.levelTicks(); t++) assertEquals(p.level(), p.programCmd(t), 0.0);
        for (int k = 0; k < p.pullUpHold(); k++, t++) assertEquals(p.pullUp(), p.programCmd(t), 0.0);
        double prev = p.pullUp();
        for (int k = 0; k < p.sweepTicks(); k++, t++) {
            double c = p.programCmd(t);
            assertTrue(c > prev && c < p.sweepTo(), "the sweep eases toward sweepTo");
            prev = c;
        }
        for (int k = 0; k < p.glideTicks(); k++, t++) assertEquals(p.glide(), p.programCmd(t), 0.0);
        assertTrue(Double.isNaN(p.programCmd(t)), "then back to the dive");
    }

    @Test
    void climbPullsUpAtTheTriggerSpeed() {
        var law = new PorpoiseLaw();
        law.restartClimb();
        assertFalse(law.update(100, PorpoiseLaw.CLIMB.triggerSpeed() - 1e-6, 350));
        assertTrue(law.update(100, PorpoiseLaw.CLIMB.triggerSpeed(), 350));
        assertNotEquals(PorpoiseLaw.DIVE, law.phase);
        assertEquals(PorpoiseLaw.CLIMB.programCmd(0), law.cmd, 0.0);
    }

    @Test
    void holdPullsUpOnAltitudeAndTheTroughChoosesTheProfile() {
        var law = new PorpoiseLaw();
        law.restartClimb();
        law.climbing = false;                                   // holding
        double m = PorpoiseLaw.DESC_MARGIN;
        assertFalse(law.update(350 + m + 1, 60, 350), "above target + DESC_MARGIN keeps diving whatever the speed");
        assertTrue(law.update(350 + m, 0, 350));                // within DESC_MARGIN of the target
        assertFalse(law.climbing, "the trough is above the target: keep holding");

        var low = new PorpoiseLaw();
        low.restartClimb();
        low.update(340, 0, 350);                                // a lower point earlier in the cycle
        assertTrue(low.update(355, 50, 350));                   // climbing, pulls up at the trigger speed
        assertTrue(low.climbing, "the trough is the cycle's lowest point (340), below the target");
        assertEquals(340, low.trough, 0.0);
    }
}
