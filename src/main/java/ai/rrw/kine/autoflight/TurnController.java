package ai.rrw.kine.autoflight;

/**
 * Closed-loop policy around {@link TurnPlanner}. Called once per game tick with a freshly seeded
 * {@link FlightModel3D.State}; returns the absolute target heading (yaw, deg) to steer toward this
 * tick. The Minecraft side then points the player's look at velYaw + clamp(target - velYaw, +-DELTA_MAX),
 * which is exactly the per-tick steering {@link FlightModel3D#step} simulates -- so flown == predicted.
 *
 * The committed legs are the crucial bit: a chosen TURN is held as an absolute-heading leg for
 * TURN_COMMIT ticks (turn onto it, then climb straight), and a RETREAT becomes a BACKOFF_COMMIT-tick
 * climbing leg flown away from the blocked destination. Without this the live loop would re-derive a
 * heading off the moving destination bearing every replan and never stop turning, and continuous
 * turning sinks the aircraft into the ground (observed in sim). CLB re-plans freely every replan.
 */
public final class TurnController {
    // cadence / commitment. Replan often so a turn starts promptly (reaction time is the main limit
    // on close obstacles). TURN_COMMIT is kept under the verified lookahead so a committed turn never
    // flies blind past what its rollout checked.
    static final int REPLAN_EVERY = 10, TURN_COMMIT = 30, BACKOFF_COMMIT = 600;

    private int t = 0;
    private int committedUntil = -1;
    private double committedHeading = 0.0;
    private int action = TurnPlanner.CLB;

    public int action() { return action; }

    /** Decide the heading to fly this tick. `live` is the current seeded aircraft state. */
    public double update(FlightModel3D.State live, double destX, double destZ, Terrain2D terrain) {
        double destBearing = FlightModel3D.velYaw(destX - live.x, destZ - live.z);
        if (t % REPLAN_EVERY == 0 && t >= committedUntil) {
            TurnPlanner.Plan pl = TurnPlanner.plan(live, destBearing, destX, destZ, terrain);
            if (pl.action == TurnPlanner.TURN) {
                committedHeading = pl.heading; action = TurnPlanner.TURN;
                committedUntil = t + TURN_COMMIT;
            } else if (pl.action == TurnPlanner.DISENGAGE) {
                committedHeading = destBearing; action = TurnPlanner.DISENGAGE;   // can't avoid -> hand off
            } else {
                committedHeading = destBearing; action = TurnPlanner.CLB;
            }
        }
        t++;
        return committedHeading;
    }
}
