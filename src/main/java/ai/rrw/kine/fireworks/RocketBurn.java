package ai.rrw.kine.fireworks;

/**
 * How long a firework rocket pushes a gliding player, from FireworkRocketEntity in 26.1.2.
 *
 * <p>The server rolls each rocket's lifetime as {@code 10 * (1 + flightDuration) + nextInt(6) + nextInt(7)}
 * ticks and boosts the attached player on every tick until {@code life > lifetime}, so a rocket burns
 * {@code lifetime + 1} ticks. A rocket item with no fireworks component counts as flight duration 0.
 * The random part is never sent to the client, so a client can only know the range and the mean.
 *
 * <p>Pure arithmetic, no game classes, so it can be tested on its own.
 */
public final class RocketBurn {
    private RocketBurn() {}

    public static final int TICKS_PER_DURATION = 10;
    /** Largest value of nextInt(6) + nextInt(7). */
    public static final int RANDOM_MAX = 11;
    /** Mean of nextInt(6) + nextInt(7): 2.5 + 3. */
    public static final double RANDOM_MEAN = 5.5;

    /** Fewest ticks a rocket of this flight duration can burn. */
    public static int minTicks(int flightDuration) {
        return TICKS_PER_DURATION * (1 + flightDuration) + 1;
    }

    /** Most ticks a rocket of this flight duration can burn. */
    public static int maxTicks(int flightDuration) {
        return minTicks(flightDuration) + RANDOM_MAX;
    }

    /** Mean burn in ticks. */
    public static double meanTicks(int flightDuration) {
        return minTicks(flightDuration) + RANDOM_MEAN;
    }

    /** Mean burn in seconds (20 ticks per second). */
    public static double meanSeconds(int flightDuration) {
        return meanTicks(flightDuration) / 20.0;
    }

    /**
     * Probability that a rocket of this duration is still burning after {@code elapsedTicks} ticks of boost,
     * from the exact distribution of nextInt(6) + nextInt(7) (42 equally likely pairs).
     */
    public static double stillBurning(int flightDuration, int elapsedTicks) {
        int extraNeeded = elapsedTicks - minTicks(flightDuration);   // burning past tick t needs extra > this
        if (extraNeeded < 0) return 1.0;
        int pairs = 0;
        for (int a = 0; a < 6; a++) {
            for (int b = 0; b < 7; b++) {
                if (a + b > extraNeeded) pairs++;
            }
        }
        return pairs / 42.0;
    }

    /**
     * Geometry of the burn bar for one rocket, as fractions of the bar's length, which spans 0 to the
     * maximum burn: the solid part ends at the minimum, the faded part runs from the minimum to the end,
     * a mark sits at the mean, and the fill shows the ticks burned so far (capped at the end).
     */
    public record Bar(double minFrac, double meanFrac, double fillFrac) {}

    /**
     * Running total for the FUEL readout: mean burn seconds summed over usable rockets. Rockets carrying
     * firework stars are skipped, since each one ends in an explosion that hurts the flyer (RW's ruling:
     * "ignore rockets with burst").
     */
    public static final class Fuel {
        private double seconds = 0;

        public void add(int flightDuration, int count, boolean hasExplosions) {
            if (hasExplosions || count <= 0) return;
            seconds += count * meanSeconds(flightDuration);
        }

        public double seconds() { return seconds; }
    }

    public static Bar bar(int flightDuration, int elapsedTicks) {
        double max = maxTicks(flightDuration);
        double fill = Math.max(0, Math.min(elapsedTicks, max)) / max;
        return new Bar(minTicks(flightDuration) / max, meanTicks(flightDuration) / max, fill);
    }
}
