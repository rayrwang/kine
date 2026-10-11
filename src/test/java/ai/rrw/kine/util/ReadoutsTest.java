package ai.rrw.kine.util;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** Number formats, durations, compass headings and smoothing. */
class ReadoutsTest {

    @Test
    void durations() {
        assertEquals("45s", KineTime.format(45));
        assertEquals("3m 05s", KineTime.format(185));
        assertEquals("2h 05m", KineTime.format(7500));
        assertEquals("--", KineTime.format(-1));
        assertEquals("--", KineTime.format(Double.NaN));
    }

    @Test
    void rangeRoundsDownToTwoSignificantFigures() {
        assertEquals("9 m", Readouts.range(9.9));
        assertEquals("840 m", Readouts.range(849));
        assertEquals("6.4 km", Readouts.range(6499));
        assertEquals("88 km", Readouts.range(88_999));
        assertEquals("0 m", Readouts.range(-5));
    }

    @Test
    void distanceToTheTarget() {
        assertEquals("412 m", Readouts.distance(411.6));
        assertEquals("1.5 km", Readouts.distance(1530));
    }

    @Test
    void etaNeverReadsShorterThanTheTruth() {
        assertEquals("1h 02m", Readouts.eta(3661));
        assertEquals("46s", Readouts.eta(45.2));
        var r = new SplittableRandom(1);
        for (int i = 0; i < 100_000; i++) {
            double s = Math.pow(10, r.nextDouble() * 5);
            assertTrue(parse(Readouts.eta(s)) >= s - 1e-6, s + " -> " + Readouts.eta(s));
        }
    }

    static double parse(String t) {
        double v = 0;
        for (String part : t.split(" ")) {
            double n = Double.parseDouble(part.replaceAll("[hms]", ""));
            v += part.endsWith("h") ? n * 3600 : part.endsWith("m") ? n * 60 : n;
        }
        return v;
    }

    @Test
    void headings() {
        assertEquals("005", Readouts.heading(365));
        assertEquals("359", Readouts.heading(-1));
        assertEquals(180.0, Compass.heading(0.0), 0.0);    // yaw 0 faces south
        assertEquals(90.0, Compass.heading(-90.0), 0.0);   // yaw -90 faces east
        assertEquals(0.0, Compass.heading(180.0), 0.0);
        assertEquals(270f, Compass.headingF(90f), 0f);
    }

    @Test
    void smoothingStep() {
        assertEquals(5.0, Smoothing.toward(5.0, 10.0, 0.0, 1.0), 0.0);
        assertEquals(10.0 - 5.0 * Math.exp(-1.0), Smoothing.toward(5.0, 10.0, 1.0, 1.0), 1e-12);
    }
}
