package ai.rrw.kine.autoflight;

/**
 * Model-predictive terrain-avoidance planner (1-D, straight-ahead).
 *
 * Each replan: scan a ladder of candidate target floors low -> high, roll the SAME controller
 * forward over the look-ahead horizon at each, and pick the LOWEST whose trajectory clears
 * terrain + buffer everywhere. If none clears -- not even continuous max-climb -- or the terrain
 * within the look-ahead is unknown, return DISENGAGE (NaN).
 *
 * DISENGAGE is a graceful hand-off, not a crash: the autopilot is fenced to straight + vertical,
 * while the player who takes over has turning and circle-to-land available.
 */
public final class TerrainPlanner {
    private TerrainPlanner() {}

    /** Sentinel target meaning "no feasible floor; hand control back". */
    public static final double DISENGAGE = Double.NaN;

    public static boolean isDisengage(double target) { return Double.isNaN(target); }

    static final int CLEARS = 1, HITS = 0, INSUFFICIENT = -1;

    /** Roll `base` forward `horizon` ticks at `target`; verdict over the rolled path.
     *  CLEARS  -- cleared the whole horizon, or ran into unknown terrain only after minLookahead
     *             blocks of verified-clear path (good enough; the receding plan re-checks later)
     *  HITS    -- would dip below terrain + buffer
     *  INSUFFICIENT -- ran into unknown terrain before minLookahead (can't see far enough) */
    static int rollout(FlightModel.State base, double target, Terrain terrain,
                       int horizon, double buffer, double minLookahead) {
        FlightModel.State s = base.copy();
        s.target = target;
        for (int i = 0; i < horizon; i++) {
            FlightModel.step(s);
            double g = terrain.heightAt(s.x);
            if (Double.isNaN(g)) return s.x >= minLookahead ? CLEARS : INSUFFICIENT;
            if (s.y < g + buffer)  return HITS;           // would clip terrain + buffer
        }
        return CLEARS;                                    // clears whole horizon
    }

    /** Lowest target floor whose rollout clears, scanning cruise upward; NaN == disengage. */
    public static double plan(FlightModel.State base, Terrain terrain,
                              double cruise, double raiseMax, double raiseStep,
                              int horizon, double buffer, double minLookahead) {
        int n = (int) Math.round(raiseMax / raiseStep);
        for (int k = 0; k <= n; k++) {
            double tgt = cruise + k * raiseStep;
            if (rollout(base, tgt, terrain, horizon, buffer, minLookahead) == CLEARS) return tgt;
        }
        return DISENGAGE;
    }
}
