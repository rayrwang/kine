package ai.rrw.kine.autoflight;

/**
 * Prioritized model-predictive planner for the TURN-capable autopilot (2-D).
 *
 * One decision per call, given the live-seeded {@link FlightModel3D.State} and the bearing to the
 * destination. Stages, in order:
 *   1. CLB     -- steer straight at the destination, climbing; if that rollout clears, keep course.
 *   2. TURN    -- otherwise the smallest heading offset whose rollout clears AND makes real forward
 *                 progress (displacement projected onto the destination bearing >= PROG_MIN). This
 *                 threads a gap or rounds the end of a finite obstacle; it is a descending maneuver.
 *   3. RETREAT -- nothing makes progress: reverse course. The closed loop ({@link TurnController})
 *                 turns this into a committed back-off-and-climb leg.
 *
 * "Progress" is directional, not straight-line distance: sliding sideways past an impassable wall
 * scores ~0 and is rejected, while rounding a finite end scores positive. Why directional and why
 * committed legs are both lessons the sim taught (raw distance gets gamed; chasing a moving offset
 * never stops turning and sinks). Mirrors terrain_turn_poc.py tick-for-tick.
 */
public final class TurnPlanner {
    private TurnPlanner() {}

    public static final int CLB = 0, TURN = 1, DISENGAGE = 2;
    static final int CLEARS = 1, HITS = 0, INSUFFICIENT = -1;
    static final int CAUSE_NONE = 0, CAUSE_CORRIDOR = 1, CAUSE_GROUND = 2;

    // tuning (mirror terrain_turn_poc.py)
    static final double[] OFFSETS = {10, 20, 30, 45, 60, 75, 90, 110, 130, 150};
    static final double PROG_MIN = 40.0;    // a turn must close at least this far along the bearing
    public static final int HORIZON = 900;
    public static final double MIN_LOOKAHEAD = 96.0;  // must verify-clear this far (below render dist) to proceed
    // Climb-corridor collision test. An obstacle is terrain that rises into the altitude we will be
    // climbing through -- NOT the low ground the porpoise momentarily dives toward. Modelling the
    // corridor as a steady climb (decoupled from the dive) makes obstacle detection phase-independent,
    // so a mountain is seen every tick instead of only when the porpoise happens to be near its trough.
    static final double CLIMB_GRAD = 0.05;     // assumed climb per block of path (below the real ~6.6%)
    static final double CORRIDOR_CLEAR = 8.0;  // terrain must stay this far below the climbing corridor
    static final double GROUND_MIN = 5.0;      // actual path must stay this far above ground (real-crash guard)
    static final double IMMINENT = 50.0;       // tall obstacle closer than this can't be turned out of (~turn radius)

    /** A planner decision: an absolute target heading (yaw, deg) and the action that produced it. */
    public static final class Plan {
        public final double heading; public final int action;
        Plan(double heading, int action) { this.heading = heading; this.action = action; }
    }

    private static final class Roll { int status; double progress; int cause = CAUSE_NONE; double hitDist = 0.0; }

    /** Roll `base` forward at a fixed target heading; report clearance + directional progress. */
    static Roll rollout(FlightModel3D.State base, double targetHeading, Terrain2D terrain,
                        int horizon, double minLookahead, double destX, double destZ) {
        FlightModel3D.State s = base.copy();
        double sx = s.x, sz = s.z, y0 = base.y;
        double grad = base.climbing ? CLIMB_GRAD : 0.0;   // at cruise (not climbing) the corridor is flat
        double dx = destX - sx, dz = destZ - sz;
        double dlen = Math.sqrt(dx * dx + dz * dz); if (dlen == 0.0) dlen = 1.0;
        double ux = dx / dlen, uz = dz / dlen;
        Roll r = new Roll(); r.status = CLEARS;
        double pathLen = 0.0;
        for (int i = 0; i < horizon; i++) {
            double prx = s.x, prz = s.z;
            FlightModel3D.step(s, targetHeading);
            pathLen += Math.hypot(s.x - prx, s.z - prz);
            double g = terrain.heightAt(s.x, s.z);
            if (Double.isNaN(g)) {
                double travelled = Math.hypot(s.x - sx, s.z - sz);
                r.status = travelled >= minLookahead ? CLEARS : INSUFFICIENT;
                break;
            }
            // obstacle = terrain rising into the climbing corridor (a wall/mountain ahead). Separately,
            // the actual porpoise path must not skim into the floor -- a small buffer so only a real
            // impending ground hit flags, not a normal mid-altitude dive (which would falsely block).
            double corridorY = y0 + grad * pathLen;
            if (g + CORRIDOR_CLEAR > corridorY) { r.status = HITS; r.cause = CAUSE_CORRIDOR; r.hitDist = pathLen; break; }
            if (s.y - g < GROUND_MIN)            { r.status = HITS; r.cause = CAUSE_GROUND;   r.hitDist = pathLen; break; }
        }
        r.progress = (s.x - sx) * ux + (s.z - sz) * uz;
        return r;
    }

    /** Prioritized decision for the situation ahead. */
    public static Plan plan(FlightModel3D.State base, double destBearing,
                            double destX, double destZ, Terrain2D terrain) {
        // Stage 1: straight at the destination, climbing
        Roll straight = rollout(base, destBearing, terrain, HORIZON, MIN_LOOKAHEAD, destX, destZ);
        if (straight.status == CLEARS) {
            return new Plan(destBearing, CLB);
        }
        // A tall obstacle closer than the turn radius can't be avoided by turning -- hand off now.
        if (straight.status == HITS && straight.cause == CAUSE_CORRIDOR && straight.hitDist < IMMINENT) {
            return new Plan(destBearing, DISENGAGE);
        }
        // Straight would clip terrain. Scan headings smallest-offset first and keep two candidates:
        //  - progressing: smallest offset that clears AND still makes real progress toward the dest
        //    (the productive dodge: thread a gap / round an end while heading where we want to go)
        //  - evasive: smallest offset that merely clears (e.g. turn to fly parallel along a wall)
        // Progress decides which is PREFERRED, not whether to turn at all: a sharp low-progress turn
        // that avoids a close wall always beats flying straight into it.
        double progHeading = Double.NaN, progOff = Double.POSITIVE_INFINITY;
        double anyHeading  = Double.NaN, anyOff  = Double.POSITIVE_INFINITY;
        for (double off : OFFSETS) {
            for (int sgn = +1; sgn >= -1; sgn -= 2) {
                double h = destBearing + sgn * off;
                Roll r = rollout(base, h, terrain, HORIZON, MIN_LOOKAHEAD, destX, destZ);
                if (r.status != CLEARS) continue;
                if (off < anyOff) { anyOff = off; anyHeading = h; }
                if (r.progress >= PROG_MIN && off < progOff) { progOff = off; progHeading = h; }
            }
        }
        if (!Double.isNaN(progHeading)) return new Plan(progHeading, TURN);   // productive dodge
        if (!Double.isNaN(anyHeading))  return new Plan(anyHeading, TURN);    // evasive turn (avoid the wall)
        // No clear heading in any direction. Distinguish WHY straight is blocked:
        //  - a tall obstacle ahead (corridor) with no way around -> we cannot avoid it; hand control
        //    back to the pilot rather than fly straight in (graceful disengage, the last resort).
        //  - merely too low (the porpoise dive over low ground) or can't see far enough -> keep
        //    climbing straight; being low is an altitude problem, not something to hand off for.
        if (straight.status == HITS && straight.cause == CAUSE_CORRIDOR) {
            return new Plan(destBearing, DISENGAGE);
        }
        return new Plan(destBearing, CLB);
    }
}
