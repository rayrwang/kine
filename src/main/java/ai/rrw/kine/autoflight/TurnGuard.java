package ai.rrw.kine.autoflight;

import ai.rrw.kine.util.Motion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;

/**
 * Game-side bridge for terrain avoidance. Once per tick while engaged and gliding with avoidance on,
 * it wraps the live heightmap as a {@link Terrain2D}, picks a destination (the managed nav target,
 * else a far point along the selected heading or the current course), seeds a
 * {@link FlightModel3D.State} from the player and the live law state, and runs the
 * {@link TurnController}.
 *
 * The Autopilot points the look at velYaw + clamp(heading - velYaw, DELTA_MAX), the same steering
 * {@link FlightModel3D#step} simulates, so the planner's rollouts predict the flown path. The decisions
 * live in TurnPlanner and TurnController; this class only moves game state in and a heading out.
 */
public final class TurnGuard {
    private TurnGuard() {}

    private static TurnController ctl = new TurnController();
    private static boolean steering = false;
    private static double  desiredHeading = 0.0;
    private static double  desiredFloor = 0.0;
    private static int     action = TurnPlanner.STRAIGHT;
    private static double  destX, destZ;          // this tick's destination

    public static boolean steering()       { return steering; }
    public static double  desiredHeading() { return desiredHeading; }
    /** Trough altitude the live law should fly to, raised by the planner to climb over rising terrain. */
    public static double  desiredFloor()   { return desiredFloor; }
    public static int     action()         { return action; }
    /** This tick's destination: the nav target, or a far point along the selected heading or the course. */
    public static double  destX()          { return destX; }
    public static double  destZ()          { return destZ; }
    /** True when an emergency bank couldn't escape an endless wall and control should be handed back. */
    public static boolean handOff()        { return steering && action == TurnPlanner.DISENGAGE; }

    /** Drop the steering latch and the committed-leg state (on disengage, or when not gliding). */
    public static void reset() {
        steering = false; action = TurnPlanner.STRAIGHT; desiredFloor = 0.0; ctl = new TurnController();
    }

    /** Compute this tick's steer heading toward the destination, dodging terrain. Call once/tick
     *  while engaged. After this, {@link #steering()} and {@link #desiredHeading()} are current. */
    public static void evaluate(Minecraft mc, LocalPlayer p, int cruise) {
        if (mc.level == null) { reset(); return; }
        TurnPlanner.setRenderRange(mc.options.getEffectiveRenderDistance() * 16.0);
        double[] d = destOf(p);
        destX = d[0]; destZ = d[1];
        FlightModel3D.State seed = seedOf(p, cruise, FlightDirector.law().climbing);
        Terrain2D terrain = terrainOf(mc.level);
        desiredHeading = ctl.update(seed, d[0], d[1], terrain);
        action = ctl.action();
        desiredFloor = ctl.floor();
        steering = true;
    }

    /** Arming gate: is there a flyable path from here? Seeded as a climb, since arming always starts by
     *  climbing to cruise, so the predicted dive is the one that would be flown. */
    public static boolean feasibleToArm(Minecraft mc, LocalPlayer p, int cruise) {
        if (mc.level == null) return false;
        TurnPlanner.setRenderRange(mc.options.getEffectiveRenderDistance() * 16.0);
        double[] d = destOf(p);
        FlightModel3D.State seed = seedOf(p, cruise, true);
        double destBearing = FlightModel3D.velYaw(d[0] - seed.x, d[1] - seed.z);
        return TurnPlanner.feasible(seed, destBearing, d[0], d[1], terrainOf(mc.level));
    }

    // destination: the nav target in MANAGED, else a far point along the selected heading or the ground course
    private static double[] destOf(LocalPlayer p) {
        double px = p.getX(), pz = p.getZ();
        // hasTarget() stays set after leaving MANAGED (the destination is remembered), so check the mode too,
        // or a heading selected after a managed leg would lose to the old target.
        if (Nav.mode() == Nav.Mode.MANAGED && Nav.hasTarget())
            return new double[] { Nav.targetX(), Nav.targetZ() };
        // A selected heading (the nav dial or the A/D heading bug) is the course to fly, dodging terrain on
        // the way; otherwise continue along the current ground course.
        double course;
        if (Nav.steering()) {                              // not managed here, so this is a selected heading
            course = Nav.desiredYaw(p);                    // selected compass heading as MC yaw
        } else {
            course = Motion.trackYaw(p, p.getYRot());
        }
        double r = Math.toRadians(course);
        return new double[] { px - Math.sin(r) * 3000.0, pz + Math.cos(r) * 3000.0 };
    }

    /** A rollout seed from the live kinematics and law state, flying to the given trough floor. */
    public static FlightModel3D.State seed(LocalPlayer p, int floor) {
        return seedOf(p, floor, FlightDirector.law().climbing);
    }

    // seed the 3-D rollout from the live kinematics and law state (`climbing` is forced true for the
    // arming probe, which asks "if I engage and climb from here")
    private static FlightModel3D.State seedOf(LocalPlayer p, int cruise, boolean climbing) {
        double vx = p.getX() - p.xOld, vz = p.getZ() - p.zOld, vy = p.getY() - p.yOld;
        FlightModel3D.State seed = new FlightModel3D.State(
            p.getX(), p.getY(), p.getZ(), vx, vy, vz, cruise, p.getXRot(), climbing);
        seed.copyLaw(FlightDirector.law());
        seed.climbing = climbing;
        return seed;
    }

    // lazily sampled surface heightmap, cached per call; NaN where unloaded (the planner treats it as unseen)
    private static Terrain2D terrainOf(Level level) {
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
