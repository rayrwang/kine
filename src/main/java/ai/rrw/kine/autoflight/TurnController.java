package ai.rrw.kine.autoflight;

/**
 * Closed-loop policy around {@link TurnPlanner}. Called once per game tick with a freshly seeded
 * {@link FlightModel3D.State}; returns the absolute target heading (yaw, deg) to steer toward this
 * tick, and exposes the current {@link #action()}.
 *
 * Normal steering points the look at velYaw + clamp(target - velYaw, +-DELTA_MAX) (gentle dodges).
 * An EMERGENCY is a hard banked turn toward the open side when a wall is inside the turn radius; the
 * captured escape heading is held until the course we are flying is no longer imminently blocked. If
 * the aircraft stays stuck in emergency (banking against an endless wall, possibly oscillating in and
 * out) for STUCK_MAX ticks without ever resolving to a normal climb/turn, it escalates to DISENGAGE
 * and hands control back rather than bank into the ground forever.
 */
public final class TurnController {
    static final int REPLAN_EVERY = 10, TURN_COMMIT = 30, STUCK_MAX = 60;
    static final double MIN_MANEUVER_AGL = 25.0;   // if turning/banking this low, hand off (sinking out)
    static final double TURN_DONE_TOL = 15.0;      // emergency turn-around finished within this many deg

    private int t = 0;
    private int committedUntil = -1;
    private double committedHeading = 0.0;
    private int action = TurnPlanner.CLB;
    private boolean emergency = false;
    private int stuckTicks = 0;          // emergency ticks since the last resolved CLB/TURN (survives oscillation)
    private double escapeHeading = 0.0;

    public int action() { return action; }

    /** Decide the heading to fly this tick. `live` is the current seeded aircraft state. */
    public double update(FlightModel3D.State live, double destX, double destZ, Terrain2D terrain) {
        double destBearing = FlightModel3D.velYaw(destX - live.x, destZ - live.z);

        // Low-AGL backstop: a sinking avoidance maneuver (turn or emergency bank) that has dropped the
        // aircraft dangerously close to the ground can't be recovered by more turning -- hand off. Only
        // while actively maneuvering, so a normal low climb-out (straight CLB) is never tripped.
        double g = terrain.heightAt(live.x, live.z);
        boolean maneuvering = emergency || action == TurnPlanner.TURN || action == TurnPlanner.EMERGENCY;
        if (maneuvering && !Double.isNaN(g) && live.y - g < MIN_MANEUVER_AGL) {
            emergency = false; stuckTicks = 0; action = TurnPlanner.DISENGAGE; t++;
            return FlightModel3D.velYaw(live.vx, live.vz);
        }

        // Emergency hard bank in progress: hold the captured reversed heading and keep arcing until the
        // turn-around is actually complete (heading reached). Exiting the instant the immediate collision
        // clears would only jink a few degrees -- this commits to the full ~180.
        if (emergency) {
            double course = FlightModel3D.velYaw(live.vx, live.vz);
            if (Math.abs(FlightModel3D.wrap180(escapeHeading - course)) <= TURN_DONE_TOL) {
                emergency = false; stuckTicks = 0;       // turn-around complete -> resume normal planning
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
                emergency = true; escapeHeading = pl.heading; action = TurnPlanner.EMERGENCY;
                if (++stuckTicks > STUCK_MAX) {           // re-entered emergency too many times -> hand off
                    emergency = false; stuckTicks = 0; action = TurnPlanner.DISENGAGE;
                }
                t++; return escapeHeading;
            } else if (pl.action == TurnPlanner.TURN) {
                committedHeading = pl.heading; action = TurnPlanner.TURN; stuckTicks = 0;
                committedUntil = t + TURN_COMMIT;
            } else {
                committedHeading = destBearing; action = TurnPlanner.CLB; stuckTicks = 0;
            }
        }
        t++;
        return committedHeading;
    }
}
