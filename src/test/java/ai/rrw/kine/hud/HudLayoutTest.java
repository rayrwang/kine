package ai.rrw.kine.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** HUD rows stay ordered and clear of the crosshair on any screen. */
class HudLayoutTest {

    @Test
    void rowsAreOrderedAboveTheBarsTravel() {
        for (int h = 150; h <= 1200; h++) {
            int lh = 9;
            int warn = HudLayout.warningRow(h, lh), lower = HudLayout.annunciatorLower(h, lh), upper = HudLayout.annunciatorUpper(h, lh);
            assertTrue(upper + lh < lower + 1 && lower + lh < warn + 1, "rows overlap at h=" + h);
            assertTrue(warn + lh <= h / 2 - HudLayout.directorTravel(h), "warning row overlaps the bars at h=" + h);
            assertTrue(HudLayout.rangeEnduranceRow(h) < HudLayout.radioAltimeterRow(h));
            int notice = HudLayout.noticeRow(h, lh);   // LAST ELYTRA: just under the warning row, well above the crosshair
            assertTrue(warn + lh < notice + 1 && notice + lh < h / 2 - 20, "notice row at h=" + h);
        }
        for (int cx = 100; cx < 1000; cx += 7)
            for (int half = 0; half < 200; half += 5) {
                assertTrue(HudLayout.modeRightEdge(cx, half) <= cx - half - 12);
                assertTrue(HudLayout.apLeftEdge(cx, half) >= cx + half + 12);
            }
    }
}
