package ai.rrw.kine.util;

/** Duration formatting shared by every readout (ETA, endurance, mining time, failsafe countdowns). */
public final class KineTime {

    private KineTime() {}

    /** Whole-unit duration: "45s", "3m 05s", "2h 05m". Returns "--" for negative or non-finite input. */
    public static String format(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) return "--";
        long t = Math.round(seconds);
        long h = t / 3600, m = (t % 3600) / 60, s = t % 60;
        if (h > 0) return String.format("%dh %02dm", h, m);
        if (m > 0) return String.format("%dm %02ds", m, s);
        return s + "s";
    }
}
