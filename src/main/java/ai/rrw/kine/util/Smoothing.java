package ai.rrw.kine.util;

/** First-order smoothing that is the same at any frame or tick rate. */
public final class Smoothing {
    private Smoothing() {}

    /** Move `current` toward `target` by the fraction a first-order filter with time constant `tc`
     *  covers in `dt` (same units as tc). */
    public static double toward(double current, double target, double dt, double tc) {
        return current + (target - current) * (1.0 - Math.exp(-dt / tc));
    }
}
