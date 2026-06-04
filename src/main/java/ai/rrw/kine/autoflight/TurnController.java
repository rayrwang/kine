package ai.rrw.kine.autoflight;

/**
 * Closed-loop policy around {@link TurnPlanner}. Called once per game tick with a freshly seeded
 * {@link FlightModel3D.State}; returns the absolute target heading (yaw, deg) to steer toward this
 * tick, and exposes the current {@link #action()}.
 *
 * Normal steering points the look at velYaw + clamp(target - velYaw, +-DELTA_MAX) (gentle dodges).
 * An EMERGENCY is a hard banked turn-around toward the open side when a wall is inside the turn radius:
 * the captured reversed heading is held until the heading has actually come around (a full ~180), then
 * it commits to flying that escape heading (ESCAPE_COMMIT) to build separation -- a hard bank pivots
 * nearly in place, so it must fly away before re-planning toward the destination. It hands control back
 * only when avoidance genuinely can't work: a single turn-around that can't come around in STUCK_MAX
 * ticks (boxed), or MAX_EMERGENCIES turn-arounds against one wall without ever getting past it (endless
 * wall). Altitude lost to the bank is left to the physics -- cutting the turn off when it sinks would
 * defeat the maneuver, since avoidance only runs during low climb-out in the first place.
 */
public final class TurnController {
    static final int REPLAN_EVERY = 10, TURN_COMMIT = 30, STUCK_MAX = 60, ESCAPE_COMMIT = 50;
    static final int MAX_EMERGENCIES = 3;          // turn-arounds against one wall before giving up (endless wall)
    static final double PROGRESS_RESET = 120.0;    // dest distance gained that counts as getting past the wall
    static final double TURN_DONE_TOL = 15.0;      // emergency turn-around finished within this many deg

    private int t = 0;
    private int committedUntil = -1;
    private double committedHeading = 0.0;
    private int action = TurnPlanner.CLB;
    private boolean emergency = false;
    private int stuckTicks = 0;          // ticks within one turn-around that hasn't come around yet (boxed)
    private int emergencyCount = 0;      // turn-arounds since last getting clear; trips the endless-wall hand-off
    private double emergencyRefDist = 0; // dest distance when the current turn-around streak began
    private double escapeHeading = 0.0;

    public int action() { return action; }

    /** Decide the heading to fly this tick. `live` is the current seeded aircraft state. */
    public double update(FlightModel3D.State live, double destX, double destZ, Terrain2D terrain) {
        double destBearing = FlightModel3D.velYaw(destX - live.x, destZ - live.z);

        // Emergency hard bank in progress: hold the captured reversed heading and keep arcing until the
        // turn-around is actually complete (heading reached). Exiting the instant the immediate collision
        // clears would only jink a few degrees -- this commits to the full ~180.
        if (emergency) {
            double course = FlightModel3D.velYaw(live.vx, live.vz);
            if (Math.abs(FlightModel3D.wrap180(escapeHeading - course)) <= TURN_DONE_TOL) {
                // Turn-around complete. A hard bank pivots almost in place (speed bled off), so the wall
                // is still right there -- fly the escape heading straight for a bit to build real
                // separation before re-planning toward the destination (which still points at the wall).
                emergency = false; stuckTicks = 0;
                committedHeading = course; action = TurnPlanner.TURN; committedUntil = t + ESCAPE_COMMIT;
                t++; return committedHeading;
            } else if (++stuckTicks > STUCK_MAX) {        // never came around (boxed in) -> hand off
                emergency = false; stuckTicks = 0; action = TurnPlanner.DISENGAGE; t++;
                return course;
            } else {
                action = TurnPlanner.EMERGENCY; t++;
                return escapeHeading;
            }
        }

        if (t % REPLAN_EVERY == 0 && t >= committedUntil) {
            TurnPlanner.Plan pl = TurnPlanner.plan(live, destBearing, destX, destZ, terrain);
            if (pl.action == TurnPlanner.EMERGENCY) {
                if (emergencyCount == 0)                     // start of a streak: remember how far the dest was
                    emergencyRefDist = Math.hypot(destX - live.x, destZ - live.z);
                emergency = true; escapeHeading = pl.heading; action = TurnPlanner.EMERGENCY; stuckTicks = 0;
                if (++emergencyCount >= MAX_EMERGENCIES) {   // keeps reversing against one wall -> endless -> hand off
                    emergency = false; emergencyCount = 0; action = TurnPlanner.DISENGAGE;
                }
                t++; return escapeHeading;
            } else if (pl.action == TurnPlanner.DISENGAGE) {
                action = TurnPlanner.DISENGAGE;             // planner: no way out (can't climb out) -> hand off
                committedHeading = destBearing;
            } else if (pl.action == TurnPlanner.TURN) {
                committedHeading = pl.heading; action = TurnPlanner.TURN;   // routing dodge / escape leg
                committedUntil = t + TURN_COMMIT;
            } else {
                committedHeading = destBearing; action = TurnPlanner.CLB;
                // reset the streak only on real progress past the wall (meaningfully closer to the dest),
                // not just because the path is briefly clear while still approaching it
                if (emergencyCount > 0
                    && Math.hypot(destX - live.x, destZ - live.z) < emergencyRefDist - PROGRESS_RESET)
                    emergencyCount = 0;
            }
        }
        // Clear cruise, outside any turn/escape commitment: track the destination bearing every tick so a
        // smoothly-moving destination -- a swept A/D heading bug or selected heading -- is followed
        // continuously, instead of refreshing the bearing only at each REPLAN_EVERY re-plan and holding it
        // in between (which reads as one big jerk every half second). The expensive plan() stays throttled;
        // only this cheap bearing-follow runs each tick.
        if (t >= committedUntil) committedHeading = destBearing;
        t++;
        return committedHeading;
    }
}
