package ai.rrw.kine.autoflight;

/**
 * Model-predictive terrain-avoidance planner. Each call rolls the flight model forward from the live
 * state over the heightmap and returns one decision, in order of preference:
 *   1. STRAIGHT at the destination at the cruise floor, if that path clears.
 *   2. Straight, with the trough floor raised in steps (climb over the obstacle).
 *   3. TURN: the smallest heading offset whose path clears (with the same floor ladder) and makes
 *      forward progress along the destination bearing; else the smallest offset that clears at all.
 *   4. EMERGENCY: a wall inside the turn radius: reverse toward the more open side.
 *   5. Otherwise straight at the cruise floor, in case the path opens up as it nears.
 * Progress is measured along the destination bearing, not as straight-line distance, so sliding
 * sideways along an endless wall scores zero.
 */
public final class TurnPlanner {
    private TurnPlanner() {}

    public static final int STRAIGHT = 0, TURN = 1, EMERGENCY = 2, DISENGAGE = 3;
    static final int CLEARS = 1, HITS = 0, INSUFFICIENT = -1;
    static final int CAUSE_NONE = 0, CAUSE_CORRIDOR = 1, CAUSE_GROUND = 2;

    static final double[] OFFSETS = {4,8,12,16,20,24,28,32,36,40,44,48,52,56,60,64,72,80,90,102,116,130,144,158,172};
    static final double PROG_MIN = 40.0;    // a turn must close at least this far along the bearing
    public static final int HORIZON = 900;
    public static final double MIN_LOOKAHEAD = 80.0;  // loaded terrain must be seen clear this far to call a path clear;
                                                      // fits an 8-chunk (128-block) server render distance
    public static final double SCAN_DIST = 480.0;     // the most the planner ever scans ahead (blocks)
    private static double scanRange = SCAN_DIST;      // effective scan, clamped to loaded terrain each tick (setRenderRange)
    // Climb-corridor collision test. An obstacle is terrain that rises into the altitude the aircraft will
    // climb through, not the low ground the porpoise dives toward. Testing against a steady climb instead
    // of the porpoise path makes the verdict independent of the porpoise phase, so a mountain is seen on
    // every tick rather than only near the trough.
    static final double CLIMB_GRAD = 0.05;     // assumed climb per block of path (below the real ~6.6%)
    static final double CORRIDOR_CLEAR = 8.0;  // terrain must stay this far below the climbing corridor
    static final double GROUND_MIN = 5.0;      // actual path must stay this far above ground (real-crash guard)
    // Trough-floor raising: before turning, try lifting the porpoise's trough so the aircraft climbs over
    // rising terrain and keeps its course. Each stage scans a ladder of floors from low to high and takes
    // the lowest that clears; the turn search uses the same ladder. The floor is the State.target the live
    // law flies its trough to.
    static final double FLOOR_STEP      = 4.0;   // trough-floor ladder increment (blocks)
    static final double FLOOR_RAISE_MAX = 60.0;  // most we lift the trough above the cruise floor before turning
    static final double IMMINENT = 50.0;       // an obstacle closer than this can't be turned out of (about the turn radius)

    /** Clamp scanning to how far terrain is loaded (blocks), set once per tick from the render distance.
     *  Servers often cap it at 8 to 12 chunks; scanning further only samples unloaded columns. Held between
     *  MIN_LOOKAHEAD + 16 (a clear verdict must still span the lookahead) and SCAN_DIST, the default. */
    public static void setRenderRange(double loadedBlocks) {
        scanRange = Math.max(MIN_LOOKAHEAD + 16.0, Math.min(SCAN_DIST, loadedBlocks));
    }

    /** A planner decision: an absolute target heading (yaw, deg), the action, and the trough floor to fly. */
    public static final class Plan {
        public final double heading; public final int action; public final double floor;
        Plan(double heading, int action, double floor) { this.heading = heading; this.action = action; this.floor = floor; }
    }

    private static final class Roll { int status; double progress; int cause = CAUSE_NONE; double hitDist = 0.0; }

    /** Roll `base` forward at a fixed target heading toward a trough `floor`; report clearance + progress. */
    static Roll rollout(FlightModel3D.State base, double targetHeading, double floor, Terrain2D terrain,
                        int horizon, double minLookahead, double destX, double destZ) {
        FlightModel3D.State s = base.copy();
        s.target = floor;                                 // fly the porpoise to this candidate trough floor
        double sx = s.x, sz = s.z, y0 = base.y;
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
            // Obstacle: terrain rising into the climb corridor, which climbs from the current altitude at
            // CLIMB_GRAD up to `floor` and then stays flat (it never assumes a climb past the floor).
            // Separately, the porpoise path itself must stay GROUND_MIN above the ground; that small
            // margin flags a real ground hit without blocking a normal dive.
            double corridorY = Math.min(floor, y0 + CLIMB_GRAD * pathLen);
            if (g + CORRIDOR_CLEAR > corridorY) { r.status = HITS; r.cause = CAUSE_CORRIDOR; r.hitDist = pathLen; hit = true; break; }
            if (s.y - g < GROUND_MIN)            { r.status = HITS; r.cause = CAUSE_GROUND;   r.hitDist = pathLen; hit = true; break; }
        }
        // A path only clears if loaded terrain was seen far enough out; otherwise it is INSUFFICIENT
        // (render edge close, or nothing loaded ahead), not a false clear.
        if (!hit) r.status = (maxLoaded >= minLookahead) ? CLEARS : INSUFFICIENT;
        r.progress = (s.x - sx) * ux + (s.z - sz) * uz;
        return r;
    }

    /** The decision for the situation ahead: climb over first, then turn. Both raise the trough floor
     *  as needed; turning is the fallback only when no floor up to the cap clears straight ahead. */
    public static Plan plan(FlightModel3D.State base, double destBearing,
                            double destX, double destZ, Terrain2D terrain) {
        double cruiseFloor = base.target;
        // Stage 1: straight at the destination at the cruise floor. Clear, or can't see far enough: go.
        Roll straight = rollout(base, destBearing, cruiseFloor, terrain, HORIZON, MIN_LOOKAHEAD, destX, destZ);
        if (straight.status != HITS) return new Plan(destBearing, STRAIGHT, cruiseFloor);
        // Straight hits: climb over it with the lowest raised trough floor that clears, keeping course.
        // A floor that can't see far enough ends the ladder: a higher one flies over the same unseen columns.
        for (double f = cruiseFloor + FLOOR_STEP; f <= cruiseFloor + FLOOR_RAISE_MAX; f += FLOOR_STEP) {
            int st = rollout(base, destBearing, f, terrain, HORIZON, MIN_LOOKAHEAD, destX, destZ).status;
            if (st == CLEARS) return new Plan(destBearing, STRAIGHT, f);   // climb over, stay on course
            if (st == INSUFFICIENT) break;
        }
        // Stage 2: no straight floor up to the cap clears, so turn. For each heading, smallest offset first,
        // find the lowest floor that clears; prefer the smallest turn that makes progress, else the
        // smallest that clears at all (paralleling a wall).
        double anyHeading = Double.NaN, anyFloor = Double.NaN, anyOff = Double.POSITIVE_INFINITY;
        for (double off : OFFSETS) {
            for (int sgn = +1; sgn >= -1; sgn -= 2) {
                double h = destBearing + sgn * off;
                for (double f = cruiseFloor; f <= cruiseFloor + FLOOR_RAISE_MAX; f += FLOOR_STEP) {
                    Roll r = rollout(base, h, f, terrain, HORIZON, MIN_LOOKAHEAD, destX, destZ);
                    if (r.status == INSUFFICIENT) break;                       // unseen this way: higher floors too
                    if (r.status != CLEARS) continue;
                    if (r.progress >= PROG_MIN) return new Plan(h, TURN, f);   // smallest progressing turn wins
                    if (off < anyOff) { anyOff = off; anyHeading = h; anyFloor = f; }  // smallest evasive, remember
                    break;                                                     // lowest clearing floor for this heading
                }
            }
        }
        if (!Double.isNaN(anyHeading)) return new Plan(anyHeading, TURN, anyFloor);   // evasive turn
        // Stage 3: nothing clears. A wall inside the turn radius can't be turned out of normally, so
        // reverse hard toward the more open side. Otherwise keep straight at the cruise floor: the path
        // may open up as it nears.
        if (straight.status == HITS && straight.cause == CAUSE_CORRIDOR && straight.hitDist < IMMINENT) {
            double course = FlightModel3D.velYaw(base.vx, base.vz);
            boolean rightBlocked = imminentAhead(base, course + 90.0, terrain);
            boolean leftBlocked  = imminentAhead(base, course - 90.0, terrain);
            double side = (rightBlocked && !leftBlocked) ? -1.0 : 1.0;     // arc toward the open side
            return new Plan(course + side * 179.0, EMERGENCY, cruiseFloor);
        }
        return new Plan(destBearing, STRAIGHT, cruiseFloor);
    }

    /** Is there a flyable path from here? True if straight at the destination doesn't hit (it clears or
     *  can't see far enough), or a raised floor clears straight, or some heading clears at the cruise floor.
     *  This gates arming instead of a fixed altitude: when too low, the climb law's own dive hits the ground
     *  in every direction, so nothing clears. It also refuses when boxed in. */
    public static boolean feasible(FlightModel3D.State base, double destBearing,
                                   double destX, double destZ, Terrain2D terrain) {
        double cruiseFloor = base.target;
        Roll straight = rollout(base, destBearing, cruiseFloor, terrain, HORIZON, MIN_LOOKAHEAD, destX, destZ);
        if (straight.status != HITS) return true;            // clear, or nothing seen in the way
        for (double f = cruiseFloor + FLOOR_STEP; f <= cruiseFloor + FLOOR_RAISE_MAX; f += FLOOR_STEP) {
            int st = rollout(base, destBearing, f, terrain, HORIZON, MIN_LOOKAHEAD, destX, destZ).status;
            if (st == CLEARS) return true;                   // could climb over it straight ahead
            if (st == INSUFFICIENT) break;                   // unseen ahead: higher floors too
        }
        for (double off : OFFSETS)
            for (int sgn = +1; sgn >= -1; sgn -= 2)
                if (rollout(base, destBearing + sgn * off, cruiseFloor, terrain, HORIZON, MIN_LOOKAHEAD, destX, destZ).status == CLEARS)
                    return true;                             // some heading is flyable
        return false;                                        // no way out from here -> don't arm
    }

    /** True if flying `heading` runs into the climb corridor within IMMINENT (about the turn radius). */
    public static boolean imminentAhead(FlightModel3D.State base, double heading, Terrain2D terrain) {
        Roll r = rollout(base, heading, base.target, terrain, HORIZON, MIN_LOOKAHEAD, base.x, base.z);
        return r.status == HITS && r.cause == CAUSE_CORRIDOR && r.hitDist < IMMINENT;
    }
}
