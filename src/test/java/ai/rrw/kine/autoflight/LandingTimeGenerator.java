package ai.rrw.kine.autoflight;

import java.util.ArrayList;
import java.util.List;

/**
 * Rolls out the table in {@link LandingTime}: for each height above the world's bottom, the longest the autopilot's
 * landing ({@link LandingModel#simulate}) takes from a steady altitude-hold porpoise cycle placed at that height, the
 * spot straight below on level ground at the bottom. Only the cycle's phases that keep its lowest point at or above
 * the bottom count (a cruise can't trough below the floor of the world). Run {@link #main} to print the table as Java
 * source; LandingTimeTest prints it too when the table no longer matches.
 */
public final class LandingTimeGenerator {
    private LandingTimeGenerator() {}

    static final int MAX_TICKS = 20000;

    /** The table's heights: every block to 12 (the law's FINAL_HEIGHT step at 10), every 4 to 64 (its BRAKE_HEIGHT
     *  step at 60), every 32 to 512, every 128 to 2176 (y 2000, the nav altitude's top, is 2064 above the overworld's
     *  bottom). */
    static double[] heights() {
        List<Double> hs = new ArrayList<>();
        for (int h = 0; h < 12; h++) hs.add((double) h);
        for (int h = 12; h < 64; h += 4) hs.add((double) h);
        for (int h = 64; h < 512; h += 32) hs.add((double) h);
        for (int h = 512; h <= 2176; h += 128) hs.add((double) h);
        return hs.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** One cycle of steady altitude-hold cruise, from one pull-up trigger to the tick before the next, after six cycles in a
     *  row in altitude hold. Heights are relative: only the velocity, the pitch and the height above the cycle's
     *  lowest point are used. */
    static List<FlightModel3D.State> steadyCycle() {
        double target = 1000;
        FlightModel3D.State s = new FlightModel3D.State(0, target + 10, 0, 0, 0, 1.5, target, 0, false);
        s.restartHold();
        int holds = 0;
        List<FlightModel3D.State> out = new ArrayList<>();
        for (int t = 0; t < 200000 && holds < 7; t++) {
            int phase = s.phase;
            FlightModel3D.stepLook(s, 0.0);
            if (phase == PorpoiseLaw.DIVE && s.phase != PorpoiseLaw.DIVE) holds = s.climbing ? 0 : holds + 1;   // the pull-up's program starts
            if (holds == 6) out.add(s.copy());
        }
        if (out.isEmpty()) throw new IllegalStateException("the porpoise never settled into altitude hold");
        return out;
    }

    /** Seconds of the longest landing from `height` above the bottom, over the cycle's phases. */
    static double seconds(double height, List<FlightModel3D.State> cycle) {
        double low = Double.MAX_VALUE;
        for (FlightModel3D.State s : cycle) low = Math.min(low, s.y);
        int worst = 0;
        for (FlightModel3D.State s : cycle) {
            if (s.y - low > height) continue;   // this phase would put the cycle's lowest point below the bottom
            LandingModel.Result r = LandingModel.simulate(new LandingModel.Law(), s.x, height, s.z, s.vx, s.vy, s.vz,
                FlightModel3D.velYaw(s.vx, s.vz), s.pitch, s.x, 0.0, s.z, MAX_TICKS, 0);
            if (!r.touched()) throw new IllegalStateException("no touchdown from " + height);
            worst = Math.max(worst, r.ticks());
        }
        return worst / 20.0;
    }

    /** The whole table: heights() and their seconds. */
    static double[][] table() {
        List<FlightModel3D.State> cycle = steadyCycle();
        double[] h = heights(), t = new double[h.length];
        for (int i = 0; i < h.length; i++) t[i] = seconds(h[i], cycle);
        return new double[][]{ h, t };
    }

    /** The table as the two arrays' Java source. */
    static String source(double[][] table) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < 2; k++) {
            b.append(k == 0 ? "    static final double[] HEIGHT = {\n" : "    static final double[] SECONDS = {\n");
            for (int i = 0; i < table[k].length; i++) {
                if (i % 10 == 0) b.append("        ");
                b.append(k == 0 ? String.valueOf((int) table[k][i]) : String.format("%.2f", table[k][i]));
                b.append(i + 1 < table[k].length ? (i % 10 == 9 ? ",\n" : ", ") : "\n");
            }
            b.append("    };\n");
        }
        return b.toString();
    }

    public static void main(String[] args) {
        System.out.print(source(table()));
    }
}
