package ai.rrw.kine.autoflight;

/**
 * The rocket takeoff, free of game types so it can be tested: the rocket's push as FireworkRocketEntity gives it,
 * the pitch program, the rule for firing another rocket, and a rollout of the whole takeoff, for the rails and
 * for counting the rockets it needs.
 *
 * <p>The profile came from simulating the game's physics (~/_/kine_takeoff_2026-10-11/). Under a steady push the
 * climb rate hardly depends on the climb angle between 90 and 60 degrees above the horizon (29.6 m/s straight up,
 * 30.6 at 75, 29.3 at 60) while the forward speed grows with the lean. So: straight up while the leaning path
 * would meet terrain, then lean over to 70 degrees; at the end of each rocket, fire another only if the autopilot,
 * handed over now in altitude hold, would fall short of the cruise level. From the ground at y 64 this needs about
 * 15% fewer rockets than climbing straight up and handing over to the autopilot's usual climb.
 */
public final class TakeoffModel {
    private TakeoffModel() {}

    /** Pitch of the powered climb (degrees, nose up negative): 70 above the horizon. */
    public static final double CLIMB_PITCH = -70.0;
    /** Pitch change per tick while leaning over or looking up: 120 degrees a second. */
    public static final double PITCH_RATE = 6.0;
    /** Blocks the leaning climb keeps above the terrain under it. */
    public static final double CLEARANCE = 6.0;
    /** After the hand-over, the autopilot must stay within this of the cruise level, never below. */
    public static final double HOLD_BAND = 5.0;
    /** Ticks from a rocket's use to its first push (the server spawns it, the client then sees it). */
    public static final int LATENCY = 2;
    /** Most rockets one takeoff fires; beyond this something is wrong. */
    public static final int MAX_ROCKETS = 40;

    static final int HOLD_TICKS = 1500;    // how long the hand-over is followed
    static final int SETTLE_TICKS = 60;    // the first 3 s after it, the law's first dive, are not judged
    static final int CLIMB_TICKS = 600;    // longest powered climb looked at for clearance
    static final double JUMP_V = 0.42, G = FlightModel3D.GRAV;
    static final double DRAG_V = 0.98F;

    /** One tick of a rocket's push on a gliding player: v = v + 0.1 L + 0.5 (1.5 L - v), L the look. */
    public static void boost(double yaw, double pitch, double[] v) {
        double pr = Math.toRadians(pitch), yr = -Math.toRadians(yaw);
        double cp = Math.cos(pr);
        double lx = Math.sin(yr) * cp, ly = -Math.sin(pr), lz = Math.cos(yr) * cp;
        v[0] += lx * 0.1 + (lx * 1.5 - v[0]) * 0.5;
        v[1] += ly * 0.1 + (ly * 1.5 - v[1]) * 0.5;
        v[2] += lz * 0.1 + (lz * 1.5 - v[2]) * 0.5;
    }

    /**
     * Is the look up far enough to jump: within 0.5 degrees of straight up, or within one mouse count
     * (`countDeg` degrees) of it. Turning by whole counts can stop up to a count short of -90, which at a
     * sensitivity above about 183% is more than 0.5 degrees.
     */
    public static boolean lookedUp(double pitch, double countDeg) {
        return pitch <= -90.0 + Math.max(0.5, countDeg + 1.0e-3);
    }

    /** Move a pitch toward a target by at most `rate`. */
    public static double toward(double pitch, double target, double rate) {
        return pitch + Math.max(-rate, Math.min(rate, target - pitch));
    }

    /**
     * Would the autopilot, handed over now in altitude hold with no more rockets, settle into the cruise at
     * `target`: never below target - HOLD_BAND after its first dive, and still holding (not climbing) at the end?
     * Velocity in blocks per tick; `yaw` is the heading flown.
     */
    public static boolean holdsCruise(double y, double vx, double vy, double vz, double pitch, double yaw, double target) {
        FlightModel3D.State s = new FlightModel3D.State(0, y, 0, vx, vy, vz, target, pitch, false);
        s.restartHold();
        double low = Double.MAX_VALUE;
        for (int k = 0; k < HOLD_TICKS; k++) {
            FlightModel3D.stepLook(s, yaw);
            if (k >= SETTLE_TICKS) low = Math.min(low, s.y);
        }
        return low >= target - HOLD_BAND && !s.climbing;
    }

    /**
     * May the climb lean over now? True if a powered climb leaning from this state to CLIMB_PITCH keeps CLEARANCE
     * above the terrain until it is above `top`, the highest block the world can hold. Unknown terrain (NaN)
     * counts as in the way, so the climb stays straight up until it can see where it goes.
     */
    public static boolean leanClear(double x, double y, double z, double vx, double vy, double vz, double pitch,
                                    double yaw, Terrain2D terrain, double top) {
        double[] v = { vx, vy, vz };
        for (int k = 0; k < CLIMB_TICKS; k++) {
            if (y > top + CLEARANCE) return true;
            pitch = toward(pitch, CLIMB_PITCH, PITCH_RATE);
            FlightModel3D.physicsStep(yaw, pitch, v);
            x += v[0]; y += v[1]; z += v[2];
            boost(yaw, pitch, v);
            double h = terrain.heightAt(x, z);
            if (Double.isNaN(h) || y < h + CLEARANCE) return false;
        }
        return false;
    }

    /** A rolled-out takeoff: positions per tick from the start, the rockets it fired, whether it reached the
     *  cruise (else it ran out of rockets or of time), and the ticks from the start to the hand-over (or to where
     *  it gave up). */
    public record Plan(double[] xs, double[] ys, double[] zs, int count, int rockets, boolean reachesCruise, int ticks) {}

    /** The least END a takeoff must leave at the cruise (RW: "takeoff limit maybe yeah 60 seems sensible?"). */
    public static final double MIN_CRUISE_ENDURANCE = 60.0;

    /**
     * Would END still be at least MIN_CRUISE_ENDURANCE after a takeoff of `climbTicks`? `endurance` is END from the
     * elytra carried now with the landing reserve of the cruise height (Durability.endurance); the climb uses it up
     * a second a second on average.
     */
    public static boolean elytraLasts(double endurance, int climbTicks) {
        return endurance - climbTicks / 20.0 >= MIN_CRUISE_ENDURANCE;
    }

    /** The state of a takeoff in flight: position, velocity (blocks per tick), pitch, whether the lean has begun,
     *  the ground it left, ticks left in the rocket burning now (0 for none) and ticks until a rocket already
     *  fired starts to push (-1 for none). */
    public record Flight(double x, double y, double z, double vx, double vy, double vz, double pitch, boolean lean,
                         double ground, int burnLeft, int untilPush) {}

    /**
     * Roll out a whole takeoff from standing at (x, y, z) facing `yaw` and looking straight up: the jump, the
     * elytra opening on the third tick, the first rocket the tick after, then as {@link #fly}. Rockets burn `burn`
     * ticks each; positions are kept for at most `maxPoints` ticks.
     */
    public static Plan fromGround(double x, double y, double z, double yaw, double target, int burn,
                                  Terrain2D terrain, double top, int maxPoints) {
        double[] xs = new double[maxPoints], ys = new double[maxPoints], zs = new double[maxPoints];
        int n = 0;
        xs[n] = x; ys[n] = y; zs[n] = z; n++;
        double ground = y, vy = JUMP_V;
        // the jump and the tick after it: air movement moves first, then gravity and drag
        for (int k = 0; k < 2; k++) {
            y += vy; vy = (vy - G) * DRAG_V;
            if (n < maxPoints) { xs[n] = x; ys[n] = y; zs[n] = z; n++; }
        }
        // the elytra opens and flies this tick; the first rocket is used the next
        double[] v = { 0, vy, 0 };
        FlightModel3D.physicsStep(yaw, -90.0, v);
        x += v[0]; y += v[1]; z += v[2];
        if (n < maxPoints) { xs[n] = x; ys[n] = y; zs[n] = z; n++; }
        Flight f = new Flight(x, y, z, v[0], v[1], v[2], -90.0, false, ground, 0, -1);
        return fly(f, true, yaw, target, burn, terrain, top, xs, ys, zs, n, 3);
    }

    /**
     * Roll out the rest of a takeoff in flight from `f`: `fireNow` fires a rocket this tick. A rocket fired now
     * pushes from LATENCY - 1 ticks later for `burn` ticks; when one burns out and none is coming, another is
     * fired unless {@link #holdsCruise} says the hand-over would hold the cruise. The climb stays straight up
     * until {@link #leanClear}, then leans to CLIMB_PITCH. After the hand-over the autopilot's altitude hold fills
     * the rest of `maxPoints`.
     */
    public static Plan fly(Flight f, boolean fireNow, double yaw, double target, int burn, Terrain2D terrain,
                           double top, int maxPoints) {
        double[] xs = new double[maxPoints], ys = new double[maxPoints], zs = new double[maxPoints];
        xs[0] = f.x(); ys[0] = f.y(); zs[0] = f.z();
        return fly(f, fireNow, yaw, target, burn, terrain, top, xs, ys, zs, 1, 0);
    }

    // `ticksBefore`: ticks already flown before `f` (the jump and the opening, from the ground)
    private static Plan fly(Flight f, boolean fire, double yaw, double target, int burn, Terrain2D terrain,
                            double top, double[] xs, double[] ys, double[] zs, int n, int ticksBefore) {
        int maxPoints = xs.length;
        double x = f.x(), y = f.y(), z = f.z(), pitch = f.pitch();
        double[] v = { f.vx(), f.vy(), f.vz() };
        boolean lean = f.lean();
        int burnLeft = f.burnLeft(), wait = f.untilPush(), rockets = 0, ticks = 0, flown = 0;
        boolean cruise = false;
        while (rockets <= MAX_ROCKETS && ticks++ < 4000) {
            if (fire) { rockets++; fire = false; wait = LATENCY - 1; }
            if (!lean && y - f.ground() > 2.0 && leanClear(x, y, z, v[0], v[1], v[2], pitch, yaw, terrain, top)) lean = true;
            if (lean) pitch = toward(pitch, CLIMB_PITCH, PITCH_RATE);
            FlightModel3D.physicsStep(yaw, pitch, v);
            x += v[0]; y += v[1]; z += v[2];
            flown++;
            if (wait > 0) wait--;
            else if (wait == 0) { wait = -1; burnLeft = burn; }
            boolean burntOut = false;
            if (burnLeft > 0) {
                boost(yaw, pitch, v);
                burntOut = --burnLeft == 0;
            }
            if (n < maxPoints) { xs[n] = x; ys[n] = y; zs[n] = z; n++; }
            if (burntOut && wait < 0) {
                if (holdsCruise(y, v[0], v[1], v[2], pitch, yaw, target)) { cruise = true; break; }
                fire = true;
            }
        }
        // the autopilot's start on the cruise, for the rails
        if (cruise && n < maxPoints) {
            FlightModel3D.State s = new FlightModel3D.State(x, y, z, v[0], v[1], v[2], target, pitch, false);
            s.restartHold();
            while (n < maxPoints) {
                FlightModel3D.stepLook(s, yaw);
                xs[n] = s.x; ys[n] = s.y; zs[n] = s.z; n++;
            }
        }
        return new Plan(xs, ys, zs, n, rockets, cruise, ticksBefore + flown);
    }
}
