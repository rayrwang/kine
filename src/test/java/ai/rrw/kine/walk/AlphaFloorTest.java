package ai.rrw.kine.walk;

import ai.rrw.kine.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The alpha floor's timing (AlphaFloor.step): on at a trigger, off HOLD ticks after the last, at most MAX ticks on,
 *  then not again until the triggers have been clear for HOLD; the gait lifted to Sprint-jump only while on. */
public class AlphaFloorTest {
    @Test
    void onOffHoldAndTimeout() {
        AlphaFloor.reset();
        AlphaFloor.step(null, 0);
        assertFalse(AlphaFloor.on());
        assertEquals(Settings.GAIT_WALK, AlphaFloor.raise(Settings.GAIT_WALK));
        AlphaFloor.step("a zombie closing", 1);
        assertTrue(AlphaFloor.on());
        assertEquals(Settings.GAIT_SPRINT_JUMP, AlphaFloor.raise(Settings.GAIT_WALK));
        assertEquals(Settings.GAIT_SPRINT_JUMP, AlphaFloor.raise(Settings.GAIT_SPRINT));
        assertEquals(Settings.GAIT_HOPS, AlphaFloor.raise(Settings.GAIT_HOPS), "never lower than the setting");
        // the trigger gone: still on for HOLD ticks, then off
        for (int t = 2; t <= 1 + AlphaFloor.HOLD; t++) { AlphaFloor.step(null, t); assertTrue(AlphaFloor.on(), "tick " + t); }
        AlphaFloor.step(null, 2 + AlphaFloor.HOLD);
        assertFalse(AlphaFloor.on());
        assertEquals(1, AlphaFloor.engaged);
        // a trigger that never clears: on for MAX, then off, and not again until clear for HOLD
        int t0 = 1000;
        int t = t0;
        for (; t <= t0 + AlphaFloor.MAX; t++) { AlphaFloor.step("a zombie closing", t); assertTrue(AlphaFloor.on(), "tick " + t); }
        AlphaFloor.step("a zombie closing", t);
        assertFalse(AlphaFloor.on(), "timed out");
        for (int k = 0; k < 100; k++) { AlphaFloor.step("a zombie closing", ++t); assertFalse(AlphaFloor.on()); }
        for (int k = 0; k <= AlphaFloor.HOLD; k++) AlphaFloor.step(null, ++t);
        AlphaFloor.step("a creeper lit", ++t);
        assertTrue(AlphaFloor.on(), "clear for HOLD: armed again");
        assertEquals("a creeper lit", AlphaFloor.why());
        AlphaFloor.reset();
        assertFalse(AlphaFloor.on());
    }
}
