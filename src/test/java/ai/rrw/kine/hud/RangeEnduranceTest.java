package ai.rrw.kine.hud;

import ai.rrw.kine.autoflight.ElytraGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The range and endurance readout's arithmetic. */
class RangeEnduranceTest {

    @Test
    void overTheVoidTheReserveIsTheFailsafes() {
        // the End at y 200 over the void (the altimeter reads -1): the failsafe plans the glide down from the
        // world floor, 200/6 x 1.7 + 15 = 71.7 s; the readout used to plan it from 0, a 15 s reserve
        assertEquals(200.0, RangeEndurance.reserveHeight(-1, 200.0, 0), 0.0);
        assertEquals(71.67, ElytraGuard.landingReserveSeconds(RangeEndurance.reserveHeight(-1, 200.0, 0)), 0.01);
        // over ground, the altimeter's height
        assertEquals(40.0, RangeEndurance.reserveHeight(40, 100.0, -64), 0.0);
        assertEquals(0.0, RangeEndurance.reserveHeight(0, 64.0, -64), 0.0);
        // below the world floor, no height to glide down from
        assertEquals(0.0, RangeEndurance.reserveHeight(-1, -70.0, -64), 0.0);
    }

    @Test
    void aWingThatCantWearOutHasUnlimitedRange() {
        // unlimited endurance is unlimited range, gliding or not, whatever the speed (0 x infinity would be NaN)
        double r = RangeEndurance.nextRange(Double.NaN, Double.POSITIVE_INFINITY, 30.0, true);
        assertEquals(Double.POSITIVE_INFINITY, r, 0.0);
        r = RangeEndurance.nextRange(r, Double.POSITIVE_INFINITY, 0.0, true);
        assertEquals(Double.POSITIVE_INFINITY, r, 0.0);
        // swapped for a wing that wears: the finite estimate at once, then filtered as usual
        r = RangeEndurance.nextRange(r, 100.0, 30.0, true);
        assertEquals(3000.0, r, 0.0);
        for (int i = 0; i < 100; i++) {
            r = RangeEndurance.nextRange(r, 100.0, 30.0, true);
            assertTrue(Double.isFinite(r) && r >= 0 && r <= 3000.0, "range " + r);
        }
    }
}
