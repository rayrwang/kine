package ai.rrw.kine.autoflight;

/**
 * Closed-loop policy around {@link TurnPlanner}. Called once per tick with a freshly seeded
 * {@link FlightModel3D.State}; returns the heading (yaw, deg) to steer toward this tick and exposes the
 * current {@link #action()} and trough {@link #floor()}.
 *
 * Plans are re-made every REPLAN_EVERY ticks; a turn is held for TURN_COMMIT ticks so the aircraft
 * doesn't chase a moving offset. Boxed in, the planner turns toward the heading with the most room, and the
 * elytra guardian (Guardian) is the emergency path. Was, until 2026-10-14: an emergency reversal held until the
 * course came round, a hand-back when it didn't or after three against one wall (RW 2026-10-11: purged once the
 * guardian is written).
 */
public final class TurnController {
    static final int REPLAN_EVERY = 10, TURN_COMMIT = 30;

    private int t = 0;
    private int committedUntil = -1;
    private double committedHeading = 0.0;
    private int action = TurnPlanner.STRAIGHT;
    private double committedFloor = Double.NaN;   // trough floor latched with the committed leg (NaN until first plan)

    public int action() { return action; }
    /** Trough altitude the live law should fly to this tick: the cruise floor, or higher to climb over
     *  rising terrain. Held across a committed leg, like the heading. */
    public double floor() { return committedFloor; }

    /** Decide the heading to fly this tick. `live` is the current seeded aircraft state. */
    public double update(FlightModel3D.State live, double destX, double destZ, Terrain2D terrain) {
        double destBearing = FlightModel3D.velYaw(destX - live.x, destZ - live.z);
        if (Double.isNaN(committedFloor)) committedFloor = live.target;   // first call: cruise floor

        if (t % REPLAN_EVERY == 0 && t >= committedUntil) {
            TurnPlanner.Plan pl = TurnPlanner.plan(live, destBearing, destX, destZ, terrain);
            committedFloor = pl.floor;               // adopt the planner's trough floor (cruise, or raised to climb over)
            if (pl.action == TurnPlanner.TURN) {
                committedHeading = pl.heading; action = TurnPlanner.TURN;
                committedUntil = t + TURN_COMMIT;
            } else {
                committedHeading = destBearing; action = TurnPlanner.STRAIGHT;
            }
        }
        // Outside a committed leg, follow the destination bearing every tick, so a moving destination (the
        // A/D heading bug) is tracked smoothly between re-plans instead of in half-second jumps.
        if (t >= committedUntil) committedHeading = destBearing;
        t++;
        return committedHeading;
    }
}
