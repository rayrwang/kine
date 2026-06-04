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

    public static final int CLB = 0, TURN = 1, EMERGENCY = 2, DISENGAGE = 3;
    static final int CLEARS = 1, HITS = 0, INSUFFICIENT = -1;
    static final int CAUSE_NONE = 0, CAUSE_CORRIDOR = 1, CAUSE_GROUND = 2;

    // tuning (mirror terrain_turn_poc.py)
    static final double[] OFFSETS = {4,8,12,16,20,24,28,32,36,40,44,48,52,56,60,64,72,80,90,102,116,130,144,158,172};
    static final double PROG_MIN = 40.0;    // a turn must close at least this far along the bearing
    public static final int HORIZON = 900;
    public static final double MIN_LOOKAHEAD = 80.0;  // must verify-clear this far (loaded) to call a path clear;
                                                      // sized to fit an 8-chunk (128-block) server render with margin
    public static final double SCAN_DIST = 480.0;     // the most we ever scan ahead (~ the ribbon's predicted length)
    private static double scanRange = SCAN_DIST;      // effective scan, clamped to loaded terrain each tick (setRenderRange)
    // Climb-corridor collision test. An obstacle is terrain that rises into the altitude we will be
    // climbing through -- NOT the low ground the porpoise momentarily dives toward. Modelling the
    // corridor as a steady climb (decoupled from the dive) makes obstacle detection phase-independent,
    // so a mountain is seen every tick instead of only when the porpoise happens to be near its trough.
    static final double CLIMB_GRAD = 0.05;     // assumed climb per block of path (below the real ~6.6%)
    static final double CORRIDOR_CLEAR = 8.0;  // terrain must stay this far below the climbing corridor
    static final double GROUND_MIN = 5.0;      // actual path must stay this far above ground (real-crash guard)
    static final double IMMINENT = 50.0;       // tall obstacle closer than this can't be turned out of (~turn radius)

    /** Clamp scanning to how far terrain is actually loaded (blocks). Servers cap render distance -- often
     *  8-12 chunks (128-192 blocks) -- so scanning the full {@link #SCAN_DIST} both wastes work over unloaded
     *  chunks (which sample as unknown and are skipped anyway) and implies sight we don't have. Held at
     *  >= MIN_LOOKAHEAD + a small margin (a "clear" verdict must still span the verify distance) and
     *  <= SCAN_DIST. Set once per tick from the live render distance before planning; defaults to SCAN_DIST,
     *  so anything not calling this (e.g. the test suite) keeps the original full-sight behaviour. */
    public static void setRenderRange(double loadedBlocks) {
        scanRange = Math.max(MIN_LOOKAHEAD + 16.0, Math.min(SCAN_DIST, loadedBlocks));
    }

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
        double maxLoaded = 0.0;          // furthest along-path distance we actually had terrain data for
        boolean hit = false;
        for (int i = 0; i < horizon; i++) {
            double prx = s.x, prz = s.z;
            FlightModel3D.step(s, targetHeading);
            pathLen += Math.hypot(s.x - prx, s.z - prz);
            if (pathLen > scanRange) break;                 // only look as far as terrain is loaded / the ribbon predicts
            double g = terrain.heightAt(s.x, s.z);
            if (Double.isNaN(g)) continue;                  // unloaded sample: skip it, keep scanning ahead
            maxLoaded = pathLen;
            // obstacle = terrain rising into the climbing corridor (a wall/mountain ahead). Separately,
            // the actual porpoise path must not skim into the floor -- a small buffer so only a real
            // impending ground hit flags, not a normal mid-altitude dive (which would falsely block).
            double corridorY = y0 + grad * pathLen;
            if (g + CORRIDOR_CLEAR > corridorY) { r.status = HITS; r.cause = CAUSE_CORRIDOR; r.hitDist = pathLen; hit = true; break; }
            if (s.y - g < GROUND_MIN)            { r.status = HITS; r.cause = CAUSE_GROUND;   r.hitDist = pathLen; hit = true; break; }
        }
        // Only declare a path CLEAR if we actually saw loaded terrain far enough out; otherwise we simply
        // can't see (render edge close, or all-NaN ahead) -- report INSUFFICIENT rather than a false clear.
        if (!hit) r.status = (maxLoaded >= minLookahead) ? CLEARS : INSUFFICIENT;
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
        // Stage 2: can a NORMAL turn clear it? Scan headings smallest-offset first, keeping two candidates:
        //  - progressing: smallest offset that clears AND still makes real progress toward the dest
        //    (the productive dodge: thread a gap / round an end while heading where we want to go)
        //  - evasive: smallest offset that merely clears (e.g. turn to fly parallel along a wall)
        // This runs BEFORE any emergency. A banked turn whose rolled-out path actually clears always beats
        // a hard turn-around, and it keeps plan() consistent with feasible() -- which arms whenever some
        // heading clears. Escalating to an emergency here while a normal turn exists is exactly what made a
        // just-engaged autopilot trip: feasible() arms, but plan() emergency-banks and at high speed can't
        // come around within STUCK_MAX, handing off and disengaging.
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

        // Stage 3: no normal turn clears. If a tall obstacle is closer than the turn radius, a normal turn
        // can't get out of it -- commit to a hard turn-around (~180) toward the more-open side. The look is
        // held only EMERGENCY_DELTA off the (rotating) velocity each tick, so it ARCS around to reverse
        // rather than pointing backward and stalling. Target just under 180 to fix the arc direction.
        if (straight.status == HITS && straight.cause == CAUSE_CORRIDOR && straight.hitDist < IMMINENT) {
            double course = FlightModel3D.velYaw(base.vx, base.vz);
            boolean rightBlocked = imminentAhead(base, course + 90.0, terrain);
            boolean leftBlocked  = imminentAhead(base, course - 90.0, terrain);
            double side = (rightBlocked && !leftBlocked) ? -1.0 : 1.0;     // arc toward the open side
            return new Plan(course + side * 179.0, EMERGENCY);
        }
        // No clear heading and not imminently walled: keep climbing straight (we may yet top it or find a
        // gap as it nears; if it becomes imminent the emergency turn-around fires). Low ground / can't-see-far
        // is likewise right to keep climbing through.
        return new Plan(destBearing, CLB);
    }

    /** Is there a flyable path from here? True if flying straight at the destination doesn't hit (it
     *  clears, or we simply can't see far enough), or failing that some heading clears. Used to gate
     *  ENGAGEMENT instead of a fixed altitude: the climb law's own dive scrapes the ground in every
     *  direction when too low, so every rollout HITS -> infeasible -> don't arm. It also refuses to arm
     *  when boxed by terrain with no clear heading, and allows arming facing a wall there's room to turn
     *  around. This adapts the arming floor to the actual situation rather than a guessed AGL. */
    public static boolean feasible(FlightModel3D.State base, double destBearing,
                                   double destX, double destZ, Terrain2D terrain) {
        Roll straight = rollout(base, destBearing, terrain, HORIZON, MIN_LOOKAHEAD, destX, destZ);
        if (straight.status != HITS) return true;            // clear ahead, or can't see a problem
        for (double off : OFFSETS)
            for (int sgn = +1; sgn >= -1; sgn -= 2)
                if (rollout(base, destBearing + sgn * off, terrain, HORIZON, MIN_LOOKAHEAD, destX, destZ).status == CLEARS)
                    return true;                             // some heading is flyable
        return false;                                        // no way out from here -> don't arm
    }

    /** True if flying `heading` runs into a tall obstacle within the emergency (turn-radius) distance.
     *  The emergency maneuver holds until the current course is no longer imminently blocked. */
    public static boolean imminentAhead(FlightModel3D.State base, double heading, Terrain2D terrain) {
        Roll r = rollout(base, heading, terrain, HORIZON, MIN_LOOKAHEAD, base.x, base.z);
        return r.status == HITS && r.cause == CAUSE_CORRIDOR && r.hitDist < IMMINENT;
    }
}
