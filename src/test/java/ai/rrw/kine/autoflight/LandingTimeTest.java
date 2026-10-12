package ai.rrw.kine.autoflight;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The landing-time curve: its interpolation, and the table against a fresh rollout of the landing law. */
class LandingTimeTest {

    @Test
    void theTableMatchesAFreshRollout() {
        double[][] fresh = LandingTimeGenerator.table();
        String hint = "\nThe landing law or the flight model changed; paste this into LandingTime:\n" + LandingTimeGenerator.source(fresh);
        assertArrayEquals(fresh[0], LandingTime.HEIGHT, 0.0, hint);
        assertArrayEquals(fresh[1], LandingTime.SECONDS, 1e-9, hint);
    }

    @Test
    void linearBetweenTheTablesHeights() {
        assertEquals(LandingTime.SECONDS[0], LandingTime.seconds(0), 0.0);
        assertEquals(LandingTime.SECONDS[0], LandingTime.seconds(-5), 0.0);                // below the bottom (the void)
        assertEquals(LandingTime.SECONDS[0], LandingTime.seconds(Double.NaN), 0.0);
        for (int i = 0; i < LandingTime.HEIGHT.length; i++)
            assertEquals(LandingTime.SECONDS[i], LandingTime.seconds(LandingTime.HEIGHT[i]), 1e-12, "at " + LandingTime.HEIGHT[i]);
        // halfway between 384 and 416
        assertEquals((26.55 + 27.65) / 2, LandingTime.seconds(400), 1e-9);
        // cruising at 350 in the overworld is 414 above its bottom
        double t = LandingTime.seconds(350 - (-64));
        assertTrue(t > 27.5 && t < 27.65, "414 up: " + t);
        // above the table, the last slope carried on
        int n = LandingTime.HEIGHT.length;
        double slope = (LandingTime.SECONDS[n - 1] - LandingTime.SECONDS[n - 2]) / (LandingTime.HEIGHT[n - 1] - LandingTime.HEIGHT[n - 2]);
        assertEquals(LandingTime.SECONDS[n - 1] + 100 * slope, LandingTime.seconds(LandingTime.HEIGHT[n - 1] + 100), 1e-9);
    }

    @Test
    void upFromTheBrakeHeightTheCurveRisesWithHeight() {
        double last = 0;
        for (double h = 64; h <= 2500; h += 7) {
            double t = LandingTime.seconds(h);
            assertTrue(t >= last, "at " + h);
            last = t;
        }
    }

    @Test
    void theInterpolationIsCloseToARolloutBetweenTheEntries() {
        // Between entries, from 12 up, the interpolation is never more than a quarter of a second short of a fresh
        // rollout (measured 2026-10-11: 0.25 s at most, at 12.5); below 12 the steps are a block wide. Across the
        // law's steps at 10 and 60 it reads high, the safe side.
        List<FlightModel3D.State> cycle = LandingTimeGenerator.steadyCycle();
        double worstShort = 0;
        for (int i = 0; i + 1 < LandingTime.HEIGHT.length; i++) {
            if (LandingTime.HEIGHT[i] < 12) continue;
            for (int k = 1; k < 4; k++) {
                double h = LandingTime.HEIGHT[i] + (LandingTime.HEIGHT[i + 1] - LandingTime.HEIGHT[i]) * k / 4.0;
                worstShort = Math.max(worstShort, LandingTimeGenerator.seconds(h, cycle) - LandingTime.seconds(h));
            }
        }
        assertTrue(worstShort <= 0.3, "short by " + worstShort + " s");
    }
}
