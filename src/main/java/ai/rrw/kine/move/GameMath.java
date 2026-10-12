package ai.rrw.kine.move;

/**
 * The game's own math helpers, copied so the movement simulation runs with no Minecraft class in its loop and gives
 * the same floats: Mth.sin and Mth.cos read a 65536-entry table, not Math.sin, and the movement code depends on it.
 */
public final class GameMath {
    private GameMath() {}

    // Mth.SIN: sin[i] = (float) Math.sin(i / 10430.378350470453)
    private static final float[] SIN = new float[65536];
    static {
        for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin(i / 10430.378350470453);
    }

    /** Mth.sin(double). */
    public static float sin(double i) {
        return SIN[(int) ((long) (i * 10430.378350470453) & 65535L)];
    }

    /** Mth.cos(double). */
    public static float cos(double i) {
        return SIN[(int) ((long) (i * 10430.378350470453 + 16384.0) & 65535L)];
    }

    /** Mth.floor(double). */
    public static int floor(double v) {
        return (int) Math.floor(v);
    }

    /** Mth.wrapDegrees(float). */
    public static float wrapDegrees(float angle) {
        float normalized = angle % 360.0F;
        if (normalized >= 180.0F) normalized -= 360.0F;
        if (normalized < -180.0F) normalized += 360.0F;
        return normalized;
    }

    /** Mth.wrapDegrees(double). */
    public static double wrapDegrees(double angle) {
        double normalized = angle % 360.0;
        if (normalized >= 180.0) normalized -= 360.0;
        if (normalized < -180.0) normalized += 360.0;
        return normalized;
    }

    /** Mth.equal(double, double): within 1.0E-5F. */
    public static boolean equal(double a, double b) {
        return Math.abs(b - a) < 1.0E-5F;
    }
}
