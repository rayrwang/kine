package ai.rrw.kine.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The range and endurance readouts: the range, the displayed END's filter and its colours. */
class RangeEnduranceTest {

    @Test
    void rangeIsEnduranceTimesSpeedLessTenPercent() {
        // the first sample, or on the ground: the estimate itself
        assertEquals(300.0 * 30.0 * 0.9, RangeEndurance.nextRange(Double.NaN, 300.0, 30.0, true), 1e-9);
        assertEquals(300.0 * 30.0 * 0.9, RangeEndurance.nextRange(5000.0, 300.0, 30.0, false), 1e-9);
        // gliding, steady: counts down at 0.9 x speed a second, so a range that matches its estimate stays on it
        double r = 8100.0;
        for (int i = 0; i < 20; i++) r = RangeEndurance.nextRange(r, 300.0 - 0.05 * (i + 1), 30.0, true);
        assertEquals((300.0 - 1.0) * 30.0 * 0.9, r, 1e-6);
        // END 0: range 0 at once
        assertEquals(0.0, RangeEndurance.nextRange(400.0, 0.0, 30.0, true), 0.0);
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
        assertEquals(2700.0, r, 1e-9);
        for (int i = 0; i < 100; i++) {
            r = RangeEndurance.nextRange(r, 100.0, 30.0, true);
            assertTrue(Double.isFinite(r) && r >= 0 && r <= 2700.0, "range " + r);
        }
    }

    @Test
    void endCountsDownWithTheClockAndDriftsTowardTheEstimate() {
        // Unbreaking III: the estimate holds still between points and drops 4 s with each; the display counts down
        // a second a second and drifts toward it, so it never jumps
        double shown = RangeEndurance.nextEndurance(Double.NaN, 400.0, true);
        assertEquals(400.0, shown, 0.0);
        double prev = shown, maxStep = 0;
        double estimate = 400.0;
        for (int t = 1; t <= 20 * 60; t++) {
            if (t % 80 == 0) estimate -= 4.0;                  // a point lost every 4 s on average
            shown = RangeEndurance.nextEndurance(shown, estimate, true);
            maxStep = Math.max(maxStep, Math.abs(shown - prev));
            prev = shown;
        }
        assertTrue(maxStep < 0.1, "a step of " + maxStep + " s");
        assertEquals(estimate, shown, 3.0);                    // 60 s in, within a few seconds of it
        // on the ground: the estimate as it is
        assertEquals(123.0, RangeEndurance.nextEndurance(400.0, 123.0, false), 0.0);
        // a big jump (a wing picked up): the estimate at once
        assertEquals(900.0, RangeEndurance.nextEndurance(400.0, 900.0, true), 0.0);
    }

    @Test
    void endReadsZeroExactlyWhenTheLandingMustStart() {
        // the estimate still 4 s (one point of Unbreaking III left above the reserve): the countdown would run under
        // it, but the display stays at 1 s until the estimate itself is 0
        double shown = 4.0;
        for (int t = 0; t < 200; t++) {
            shown = RangeEndurance.nextEndurance(shown, 4.0, true);
            assertTrue(shown >= 1.0, "reads " + shown + " before the landing");
        }
        assertEquals(0.0, RangeEndurance.nextEndurance(shown, 0.0, true), 0.0);
        assertEquals(0.0, RangeEndurance.nextEndurance(Double.NaN, 0.0, true), 0.0);
        assertEquals(Double.POSITIVE_INFINITY, RangeEndurance.nextEndurance(30.0, Double.POSITIVE_INFINITY, true), 0.0);
    }

    @Test
    void endTurnsAmberInItsLastMinuteAndRedInItsLastTwentySeconds() {
        assertEquals(RangeEndurance.END_NORMAL, RangeEndurance.enduranceColor(Double.POSITIVE_INFINITY));
        assertEquals(RangeEndurance.END_NORMAL, RangeEndurance.enduranceColor(61.0));
        assertEquals(RangeEndurance.END_NORMAL, RangeEndurance.enduranceColor(60.5));   // shown as 1m 01s
        assertEquals(HudColors.AMBER, RangeEndurance.enduranceColor(60.4));             // shown as 1m 00s
        assertEquals(HudColors.AMBER, RangeEndurance.enduranceColor(21.0));
        assertEquals(HudColors.RED, RangeEndurance.enduranceColor(20.4));
        assertEquals(HudColors.RED, RangeEndurance.enduranceColor(20.0));
        assertEquals(HudColors.RED, RangeEndurance.enduranceColor(0.0));
        assertNotEquals(HudColors.AMBER, RangeEndurance.END_NORMAL);
    }

    @Test
    void theReserveHeightIsTheLastTwentySecondsTopLockedAcrossLandings() {
        int full = RangeEndurance.RESERVE_WINDOW;
        // before any flight that long: the autopilot's cruise height stands in, on the ground and early in a flight
        assertEquals(350.0, RangeEndurance.reserveY(Double.NaN, 0, Double.NaN, 350), 0.0);
        assertEquals(350.0, RangeEndurance.reserveY(80.0, 100, Double.NaN, 350), 0.0);
        // a whole window of this flight: its own top, whatever the lock says
        assertEquals(426.0, RangeEndurance.reserveY(426.0, full, 300.0, 350), 0.0);
        assertEquals(120.0, RangeEndurance.reserveY(120.0, full, 426.0, 350), 0.0);
        // on the ground after a flight: the locked top, not the ground
        assertEquals(426.0, RangeEndurance.reserveY(Double.NaN, 0, 426.0, 350), 0.0);
        // a hop or a short glide below it keeps the lock; one that goes higher is covered at once
        assertEquals(426.0, RangeEndurance.reserveY(72.0, 15, 426.0, 350), 0.0);
        assertEquals(500.0, RangeEndurance.reserveY(500.0, full - 1, 426.0, 350), 0.0);
    }
}
