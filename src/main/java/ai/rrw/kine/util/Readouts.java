package ai.rrw.kine.util;

/** Number formats for the HUD readouts. Durations are in KineTime. */
public final class Readouts {
    private Readouts() {}

    /** Distance to the nav target: whole metres under 1 km, then kilometres to one decimal. */
    public static String distance(double m) {
        return m >= 1000 ? String.format("%.1f km", m / 1000.0) : Math.round(m) + " m";
    }

    /** Range, rounded down to two significant figures (840 m, 6.4 km, 880 km): never overstate it, and a
     *  smoothed estimate has no more precision than that. */
    public static String range(double blocks) {
        double v = Math.max(0, blocks);
        if (v < 10) return (long) v + " m";
        double mag = Math.pow(10, Math.floor(Math.log10(v) + 1e-9) - 1);
        v = Math.floor(v / mag) * mag;
        if (v < 1000) return (long) v + " m";
        double km = v / 1000.0;
        return km < 10 ? String.format("%.1f km", km) : String.format("%.0f km", km);
    }

    /**
     * An ETA: round up to 2 significant figures, then up to the finest unit the readout prints (whole
     * seconds under an hour, whole minutes from an hour on, where KineTime drops the seconds). Without
     * the second step 3661 s would round to 3700 and print "1h 01m", less than the true ETA. The printed
     * value is never below the true ETA.
     */
    public static String eta(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return KineTime.format(seconds);
        double r = roundUpSig2(seconds);
        double unit = (r < 3600) ? 1.0 : 60.0;                 // finest unit KineTime shows at this magnitude
        return KineTime.format(Math.ceil(r / unit - 1e-9) * unit);
    }

    /** Round a duration (s) up to 2 significant figures, so the ETA never reads shorter than it is. */
    public static double roundUpSig2(double v) {
        if (!(v > 0)) return 0;
        double mag = Math.pow(10, Math.floor(Math.log10(v) + 1e-9) - 1);   // unit of the 2nd significant figure
        return Math.ceil(v / mag - 1e-9) * mag;
    }

    /** A compass heading as three digits, 000 to 359. */
    public static String heading(long n) {
        n = ((n % 360) + 360) % 360;
        return String.format("%03d", n);
    }
}
