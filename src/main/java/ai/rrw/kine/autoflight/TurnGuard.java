package ai.rrw.kine.autoflight;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;

/**
 * Minecraft-side bridge for the TURN-capable terrain-avoidance planner -- the 2-D / lateral sibling
 * of {@link TerrainGuard}. Once per tick (when engaged, gliding, and avoidance is on) it:
 *   1. wraps the live surface heightmap as a {@link Terrain2D} (lazily sampled, cached per call),
 *   2. picks a destination -- the Nav managed target if set, else a far point along the current
 *      course -- and seeds a {@link FlightModel3D.State} from the live player + FlightDirector law
 *      state (so the rollout continues the actual porpoise cycle: predicted == flown), and
 *   3. runs the single persistent {@link TurnController}, exposing the absolute heading to steer.
 *
 * The Autopilot consumes {@link #desiredHeading()} by pointing the look at
 * velYaw + clamp(heading - velYaw, +-DELTA_MAX) -- exactly the per-tick steering
 * {@link FlightModel3D#step} simulates, which is what makes the planner's rollouts predictive.
 *
 * All decision logic lives in the verified pure classes; this only marshals game state in and a
 * heading out. NOTE: HORIZON / MIN_LOOKAHEAD / BUFFER in {@link TurnPlanner} were validated at
 * sim scale (large features, full knowledge); live values must be matched to render distance in
 * runClient -- the same open tuning the straight guard carries.
 */
public final class TurnGuard {
    private TurnGuard() {}

    private static TurnController ctl = new TurnController();
    private static boolean steering = false;
    private static double  desiredHeading = 0.0;
    private static int     action = TurnPlanner.CLB;

    public static boolean steering()       { return steering; }
    public static double  desiredHeading() { return desiredHeading; }
    public static int     action()         { return action; }
    /** True when an emergency bank couldn't escape an endless wall and control should be handed back. */
    public static boolean handOff()        { return steering && action == TurnPlanner.DISENGAGE; }

    /** Drop the steering latch and the committed-leg state (call on disengage / not gliding). */
    public static void reset() {
        steering = false; action = TurnPlanner.CLB; ctl = new TurnController();
    }

    /** Compute this tick's steer heading toward the destination, dodging terrain. Call once/tick
     *  while engaged. After this, {@link #steering()} and {@link #desiredHeading()} are current. */
    public static void evaluate(Minecraft mc, LocalPlayer p, int cruise) {
        if (mc.level == null) { reset(); return; }
        double[] d = destOf(p);
        FlightModel3D.State seed = seedOf(p, cruise, FlightDirector.climbingState());
        Terrain2D terrain = terrainOf(mc.level);
        desiredHeading = ctl.update(seed, d[0], d[1], terrain);
        action = ctl.action();
        steering = true;
    }

    /** Engagement gate: is there a flyable path from here? Arms on path-feasibility rather than a fixed
     *  altitude -- the climb's own dive scrapes the ground in every direction when too low, so feasible()
     *  returns false there; it also refuses to arm boxed-in with no way out. Seeded as a climb (arming
     *  always begins a climb to cruise), so the predicted dive is the one we'd actually fly. */
    public static boolean feasibleToArm(Minecraft mc, LocalPlayer p, int cruise) {
        if (mc.level == null) return false;
        double[] d = destOf(p);
        FlightModel3D.State seed = seedOf(p, cruise, true);
        double destBearing = FlightModel3D.velYaw(d[0] - seed.x, d[1] - seed.z);
        return TurnPlanner.feasible(seed, destBearing, d[0], d[1], terrainOf(mc.level));
    }

    // destination: Nav managed target if set, else a far point along the current ground course
    private static double[] destOf(LocalPlayer p) {
        double px = p.getX(), pz = p.getZ();
        if (Nav.hasTarget()) return new double[] { Nav.targetX(), Nav.targetZ() };
        double dx = px - p.xOld, dz = pz - p.zOld;
        double course = (dx * dx + dz * dz > 1.0e-8) ? FlightModel3D.velYaw(dx, dz) : p.getYRot();
        double r = Math.toRadians(course);
        return new double[] { px - Math.sin(r) * 3000.0, pz + Math.cos(r) * 3000.0 };
    }

    // seed the 3-D rollout from live kinematics + the live FlightDirector law state (climbing overridden
    // for the arming feasibility probe, which always asks "if I engage and climb from here...")
    private static FlightModel3D.State seedOf(LocalPlayer p, int cruise, boolean climbing) {
        double vx = p.getX() - p.xOld, vz = p.getZ() - p.zOld, vy = p.getY() - p.yOld;
        FlightModel3D.State seed = new FlightModel3D.State(
            p.getX(), p.getY(), p.getZ(), vx, vy, vz, cruise, p.getXRot(), climbing);
        seed.phase = FlightDirector.phaseState();
        seed.cmd   = FlightDirector.commandedPitch();
        seed.topT  = FlightDirector.topTicksState();
        return seed;
    }

    // lazy surface heightmap, cached per call; NaN where unloaded (planner treats as "unseen")
    private static Terrain2D terrainOf(net.minecraft.world.level.Level level) {
        final HashMap<Long, Double> cache = new HashMap<>();
        return (x, z) -> {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            long key = (((long) bx) << 32) ^ (bz & 0xffffffffL);
            Double c = cache.get(key);
            if (c != null) return c;
            double h = level.hasChunkAt(bx, bz)
                     ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz)
                     : Double.NaN;
            cache.put(key, h);
            return h;
        };
    }
}
