package ai.rrw.kine.autoflight;

/**
 * How long the autopilot's landing ({@link LandingModel}: the 60 degree spiral plunge, the brake from 60 above, the
 * 25 degree middle spiral, the nose-up finish) takes from a height down to the world's bottom (level.getMinY(): -64 in
 * the overworld, 0 in the Nether and the End). It sizes the landing reserve (RW, 2026-10-11: "the time to get down from
 * the current altitude down to -64 flying the normal profile", "a precomputed interpolated curve so don't need to
 * resimulate each time each flight"). The height is the player's y minus the world's bottom, never the height above
 * the ground, so the reserve doesn't jump with the terrain.
 *
 * <p>A table rolled out offline by LandingTimeGenerator (in the tests), read by linear interpolation; nothing is
 * simulated in flight. Each entry is the longest landing over the phases of a steady altitude-hold porpoise placed at
 * that height with the spot straight below. Below 60 the law brakes at once, and from the fast bottom of a dive that
 * brake zooms up before it comes down, so there the curve is higher than just above 60; a cell across such a step
 * reads high, never low. LandingTimeTest checks the table against a fresh rollout and prints a new one when the
 * landing law or the flight model has changed.
 */
public final class LandingTime {
    private LandingTime() {}

    /** Heights above the world's bottom (blocks), increasing. */
    static final double[] HEIGHT = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
        10, 11, 12, 16, 20, 24, 28, 32, 36, 40,
        44, 48, 52, 56, 60, 64, 96, 128, 160, 192,
        224, 256, 288, 320, 352, 384, 416, 448, 480, 512,
        640, 768, 896, 1024, 1152, 1280, 1408, 1536, 1664, 1792,
        1920, 2048, 2176
    };
    /** The landing's seconds from each height. */
    static final double[] SECONDS = {
        0.05, 17.00, 22.20, 23.80, 25.40, 26.80, 28.05, 28.40, 29.55, 29.90,
        31.00, 16.10, 16.30, 17.50, 18.50, 19.35, 20.10, 20.85, 21.60, 22.30,
        23.05, 23.75, 24.50, 25.20, 25.95, 15.65, 16.70, 17.80, 18.95, 20.10,
        21.25, 22.40, 23.55, 24.70, 25.85, 27.00, 28.15, 29.30, 30.45, 31.60,
        36.15, 40.75, 45.35, 49.90, 54.50, 59.10, 63.70, 68.25, 72.85, 77.45,
        82.00, 86.60, 91.20
    };

    /** Seconds the landing takes from `height` blocks above the world's bottom: linear between the table's heights,
     *  the first entry at or below the bottom, and above the table the last cell's slope carried on. */
    public static double seconds(double height) {
        int n = HEIGHT.length;
        if (!(height > HEIGHT[0])) return SECONDS[0];   // NaN too
        int i = 1;
        while (i < n - 1 && HEIGHT[i] < height) i++;
        double h0 = HEIGHT[i - 1], h1 = HEIGHT[i];
        return SECONDS[i - 1] + (SECONDS[i] - SECONDS[i - 1]) * (height - h0) / (h1 - h0);
    }
}
