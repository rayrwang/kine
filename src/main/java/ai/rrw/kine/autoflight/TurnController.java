package ai.rrw.kine.autoflight;

/**
 * Closed-loop policy around {@link TurnPlanner}. Called once per tick with a freshly seeded
 * {@link FlightModel3D.State}; returns the heading (yaw, deg) to steer toward this tick and exposes the
 * current {@link #action()} and trough {@link #floor()}.
 *
 * Plans are re-made every REPLAN_EVERY ticks; a turn is held for TURN_COMMIT ticks so the aircraft
 * doesn't chase a moving offset. An EMERGENCY reverses toward the open side: the reversed heading is held
 * until the course has come round (within TURN_DONE_TOL), then flown for ESCAPE_COMMIT ticks to build
 * separation, since a hard bank pivots almost in place. Control is handed back (DISENGAGE) when a
 * reversal doesn't come round within STUCK_MAX ticks (boxed in), or after MAX_EMERGENCIES reversals
 * against one wall without getting past it (an endless wall). Height lost in the bank is accepted.
 */
public final class TurnController {
    static final int REPLAN_EVERY = 10, TURN_COMMIT = 30, STUCK_MAX = 60, ESCAPE_COMMIT = 50;
    static final int MAX_EMERGENCIES = 3;          // turn-arounds against one wall before giving up (endless wall)
    static final double PROGRESS_RESET = 120.0;    // dest distance gained that counts as getting past the wall
    static final double TURN_DONE_TOL = 15.0;      // emergency turn-around finished within this many deg

    private int t = 0;
    private int committedUntil = -1;
    private double committedHeading = 0.0;
    private int action = TurnPlanner.STRAIGHT;
    private boolean emergency = false;
    private int stuckTicks = 0;          // ticks within one turn-around that hasn't come around yet (boxed)
    private int emergencyCount = 0;      // turn-arounds since last getting clear; trips the endless-wall hand-off
    private double emergencyRefX = 0, emergencyRefZ = 0;   // where the current turn-around streak began
    private double escapeHeading = 0.0;
    private int escapeSide = 1;          // the side the planner chose for the turn-around (+1 or -1 in yaw), kept with it
    private double committedFloor = Double.NaN;   // trough floor latched with the committed leg (NaN until first plan)

    public int action() { return action; }
    /** Trough altitude the live law should fly to this tick: the cruise floor, or higher to climb over
     *  rising terrain. Held across a committed leg, like the heading. */
    public double floor() { return committedFloor; }
    /** The side of the emergency bank, fixed when it was planned: +1 turns toward increasing yaw, -1 the other way. */
    public int emergencySide() { return escapeSide; }

    /** The emergency bank's look offset from the track (deg), toward the escape heading on the planned side and at
     *  most maxDelta. The escape heading lies 179 deg off the course when planned, so once the track turns a degree
     *  or two further (a frame that spans two ticks) the wrapped offset points the other way, toward the wall; an
     *  offset more than 90 deg off the planned side is that wrap, and is turned back to the planned side. */
    static double bankOffset(double escapeHeading, double velYaw, int side, double maxDelta) {
        double off = FlightModel3D.wrap180(escapeHeading - velYaw);
        if (off * side < -90.0) off += side * 360.0;
        return Math.max(-maxDelta, Math.min(maxDelta, off));
    }

    /** Decide the heading to fly this tick. `live` is the current seeded aircraft state. */
    public double update(FlightModel3D.State live, double destX, double destZ, Terrain2D terrain) {
        double destBearing = FlightModel3D.velYaw(destX - live.x, destZ - live.z);
        if (Double.isNaN(committedFloor)) committedFloor = live.target;   // first call: cruise floor

        // Emergency bank in progress: hold the reversed heading until the course has come round. Leaving
        // as soon as the immediate collision clears would only jink a few degrees.
        if (emergency) {
            committedFloor = live.target;            // bank at cruise; a raised trough was for the abandoned dodge
            double course = FlightModel3D.velYaw(live.vx, live.vz);
            if (Math.abs(FlightModel3D.wrap180(escapeHeading - course)) <= TURN_DONE_TOL) {
                // Turned round. The bank pivots almost in place, so the wall is still close: fly the escape
                // heading for a while before re-planning toward the destination behind the wall.
                emergency = false; stuckTicks = 0;
                committedHeading = course; action = TurnPlanner.TURN; committedUntil = t + ESCAPE_COMMIT;
                t++; return committedHeading;
            } else if (++stuckTicks > STUCK_MAX) {        // never came round (boxed in): hand off
                emergency = false; stuckTicks = 0; action = TurnPlanner.DISENGAGE; t++;
                return course;
            } else {
                action = TurnPlanner.EMERGENCY; t++;
                return escapeHeading;
            }
        }

        if (t % REPLAN_EVERY == 0 && t >= committedUntil) {
            TurnPlanner.Plan pl = TurnPlanner.plan(live, destBearing, destX, destZ, terrain);
            committedFloor = pl.floor;               // adopt the planner's trough floor (cruise, or raised to climb over)
            if (pl.action == TurnPlanner.EMERGENCY) {
                if (emergencyCount == 0) {                   // start of a streak: remember where it began
                    emergencyRefX = live.x; emergencyRefZ = live.z;
                }
                emergency = true; escapeHeading = pl.heading; action = TurnPlanner.EMERGENCY; stuckTicks = 0;
                // the planner put the escape heading 179 deg to the open side of this course: keep that side
                escapeSide = FlightModel3D.wrap180(pl.heading - FlightModel3D.velYaw(live.vx, live.vz)) < 0 ? -1 : 1;
                if (++emergencyCount >= MAX_EMERGENCIES) {   // keeps reversing against one wall: endless, hand off
                    emergency = false; emergencyCount = 0; action = TurnPlanner.DISENGAGE;
                }
                t++; return escapeHeading;
            } else if (pl.action == TurnPlanner.TURN) {
                committedHeading = pl.heading; action = TurnPlanner.TURN;
                committedUntil = t + TURN_COMMIT;
            } else {
                committedHeading = destBearing; action = TurnPlanner.STRAIGHT;
                // reset the streak only on real progress past the wall, not because the path is briefly
                // clear while still approaching it. Progress is toward this tick's destination from where the
                // streak began: with a selected heading or no nav the destination is a point kept 3000 blocks
                // ahead, so the distance to it never falls, but the distance gained from the start still grows.
                if (emergencyCount > 0
                    && Math.hypot(destX - live.x, destZ - live.z)
                       < Math.hypot(destX - emergencyRefX, destZ - emergencyRefZ) - PROGRESS_RESET)
                    emergencyCount = 0;
            }
        }
        // Outside a committed leg, follow the destination bearing every tick, so a moving destination (the
        // A/D heading bug) is tracked smoothly between re-plans instead of in half-second jumps.
        if (t >= committedUntil) committedHeading = destBearing;
        t++;
        return committedHeading;
    }
}
