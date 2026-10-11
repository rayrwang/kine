package ai.rrw.kine.hud;

import ai.rrw.kine.autoflight.ElytraGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** HUD rows stay ordered and clear of the crosshair on any screen; the landing reserve grows with height. */
class HudLayoutTest {

    @Test
    void rowsAreOrderedAboveTheBarsTravel() {
        for (int h = 150; h <= 1200; h++) {
            int lh = 9;
            int warn = HudLayout.warningRow(h, lh), lower = HudLayout.annunciatorLower(h, lh), upper = HudLayout.annunciatorUpper(h, lh);
            assertTrue(upper + lh < lower + 1 && lower + lh < warn + 1, "rows overlap at h=" + h);
            assertTrue(warn + lh <= h / 2 - HudLayout.directorTravel(h), "warning row overlaps the bars at h=" + h);
            assertTrue(HudLayout.rangeEnduranceRow(h) < HudLayout.radioAltimeterRow(h));
        }
        for (int cx = 100; cx < 1000; cx += 7)
            for (int half = 0; half < 200; half += 5) {
                assertTrue(HudLayout.modeRightEdge(cx, half) <= cx - half - 12);
                assertTrue(HudLayout.apLeftEdge(cx, half) >= cx + half + 12);
            }
    }

    @Test
    void landingReserve() {
        assertEquals(15.0, ElytraGuard.landingReserveSeconds(0), 0.0);
        double prev = 0;
        for (int agl = 0; agl <= 2000; agl += 10) {
            double r = ElytraGuard.landingReserveSeconds(agl);
            assertTrue(r > prev);
            prev = r;
        }
        assertEquals(300 / 6.0 * (1.5 + 0.3) + 15, ElytraGuard.landingReserveSeconds(300), 1e-12);
    }
}
