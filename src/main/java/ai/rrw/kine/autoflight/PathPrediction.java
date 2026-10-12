package ai.rrw.kine.autoflight;

import ai.rrw.kine.Settings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * The autopilot's predicted path, for the rails and the profile map: the 3-D flight model rolled out
 * from the live state and steered the way the autopilot steers, so turns show. Computed once per tick
 * after the flight director and the planner, while the autopilot is engaged and gliding.
 *
 * <p>Steering in the rollout, as in Autopilot.control: with terrain avoidance steering, straight at the
 * destination, or along the committed heading in a turn, or in an emergency the look held up to
 * EMERGENCY_DELTA off the velocity until the course has come round; without it, the look turned
 * toward the nav heading at the autopilot's nav turn rate, or the current heading held. Pitch follows
 * the porpoise law. While landing, the landing law ({@link LandingModel}) is rolled out instead, down to the spot.
 * On the way to a managed destination the rollout does, from where it comes within Nav's arrival radius, what Nav
 * will do there: the hold's circle, or the landing law onto the spot Nav would pick.
 */
public final class PathPrediction {
    private PathPrediction() {}

    public static final int TICKS = 900;      // 45 s, enough for a 32-chunk render distance

    // positions relative to the player at this tick; index 0 is the player
    private static final double[] xs = new double[TICKS + 1], ys = new double[TICKS + 1], zs = new double[TICKS + 1];
    private static int count = 0;            // points in the current prediction; 0 when there is none

    /** Points in the current prediction (0 when there is none); point i is i ticks ahead. */
    public static int count() { return count; }
    public static double x(int i) { return xs[i]; }
    public static double y(int i) { return ys[i]; }
    public static double z(int i) { return zs[i]; }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PathPrediction::tick);
    }

    private static void tick(Minecraft mc) {
        count = 0;
        if (!Settings.shows(Settings.flightRibbon) && !Settings.shows(Settings.displayFlightProfile)) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isFallFlying() || !Autopilot.isEngaged()) return;

        if (Nav.landing()) {
            var v = p.getDeltaMovement();
            LandingModel.Result r = LandingModel.simulate(Nav.landingLaw(), p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z,
                p.getYRot(), p.getXRot(), Nav.spotX(), Nav.spotTop(), Nav.spotZ(), TICKS, TICKS + 1);
            for (int k = 0; k < r.count(); k++) {
                xs[k] = r.xs()[k] - p.getX(); ys[k] = r.ys()[k] - p.getY(); zs[k] = r.zs()[k] - p.getZ();
            }
            count = r.count();
            return;
        }
        FlightModel3D.State s = TurnGuard.seed(p, FlightDirector.floorAltitude());
        double px = s.x, py = s.y, pz = s.z;
        boolean avoidance = Settings.terrainAvoidance && TurnGuard.steering();
        int action = TurnGuard.action();
        double heading = TurnGuard.desiredHeading(), destX = TurnGuard.destX(), destZ = TurnGuard.destZ();
        boolean emergency = avoidance && action == TurnPlanner.EMERGENCY;
        double look = p.getYRot();
        float navRate = (Nav.landing() ? Autopilot.LANDING_TURN_DPS : Autopilot.NAV_TURN_DPS) / 20f;   // per tick
        // circling over the destination: the circle, unless terrain avoidance is turning away from something
        boolean hold = Nav.holding() && !(avoidance && action != TurnPlanner.STRAIGHT);
        double fixX = Nav.targetX() + 0.5, fixZ = Nav.targetZ() + 0.5;
        // on the way to a managed destination: on coming within Nav's arrival radius the rollout does what Nav will do
        // there, the hold's circle or the landing law onto the spot Nav would pick, instead of looping round the point
        boolean arriving = Nav.arriving();
        boolean holdThere = arriving && Nav.holdNow(mc);

        xs[0] = 0; ys[0] = 0; zs[0] = 0;
        for (int k = 1; k <= TICKS; k++) {
            if (arriving && Math.hypot(s.x - fixX, s.z - fixZ) < Nav.ARRIVAL_RADIUS) {
                arriving = false;
                if (holdThere) hold = true;
                else {
                    // points 0 to k-1 are drawn; the landing goes on from point k-1, its own point 0
                    double[] spot = Nav.arrivalSpot(mc.level);
                    double yaw = avoidance ? FlightModel3D.velYaw(s.vx, s.vz) : look;
                    int left = TICKS - (k - 1);
                    LandingModel.Result r = LandingModel.simulate(new LandingModel.Law(), s.x, s.y, s.z, s.vx, s.vy, s.vz,
                        yaw, s.pitch, spot[0], spot[1], spot[2], left, left + 1);
                    for (int j = 1; j < r.count(); j++) {
                        xs[k - 1 + j] = r.xs()[j] - px; ys[k - 1 + j] = r.ys()[j] - py; zs[k - 1 + j] = r.zs()[j] - pz;
                    }
                    count = k - 1 + r.count();
                    return;
                }
            }
            if (hold) {
                FlightModel3D.stepLook(s, HoldModel.lookYaw(s.x, s.z, fixX, fixZ, FlightModel3D.velYaw(s.vx, s.vz), s.climbing));
            } else if (avoidance) {
                if (emergency) {
                    double course = FlightModel3D.velYaw(s.vx, s.vz);
                    if (Math.abs(FlightModel3D.wrap180(heading - course)) <= TurnController.TURN_DONE_TOL) emergency = false;
                }
                if (emergency) FlightModel3D.step(s, heading, FlightModel3D.EMERGENCY_DELTA);
                else if (action == TurnPlanner.STRAIGHT) FlightModel3D.step(s, FlightModel3D.velYaw(destX - s.x, destZ - s.z));
                else FlightModel3D.step(s, heading);
            } else {
                if (Nav.steering()) look += Mth.clamp(Mth.wrapDegrees(Nav.desiredYaw(s.x, s.z, FlightModel3D.velYaw(s.vx, s.vz), s.climbing)
                    - (float) look), -navRate, navRate);
                FlightModel3D.stepLook(s, look);
            }
            xs[k] = s.x - px; ys[k] = s.y - py; zs[k] = s.z - pz;
        }
        count = TICKS + 1;
    }
}
