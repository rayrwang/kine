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
        4.75, 11.70, 13.90, 17.50, 19.20, 19.50, 21.10, 22.55, 23.75, 24.10,
        25.25, 13.40, 13.55, 15.15, 16.15, 17.05, 17.80, 18.50, 19.25, 19.95,
        20.70, 21.40, 22.15, 22.90, 23.60, 15.15, 16.25, 17.35, 18.50, 19.65,
        20.80, 21.90, 23.10, 24.25, 25.40, 26.55, 27.65, 28.80, 29.95, 31.10,
        35.70, 40.30, 44.85, 49.45, 54.05, 58.65, 63.20, 67.80, 72.40, 76.95,
        81.55, 86.15, 90.75
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
