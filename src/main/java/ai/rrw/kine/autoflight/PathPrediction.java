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
 * the porpoise law; the landing program's descent is not modelled.
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

        FlightModel3D.State s = TurnGuard.seed(p, FlightDirector.floorAltitude());
        double px = s.x, py = s.y, pz = s.z;
        boolean avoidance = Settings.terrainAvoidance && TurnGuard.steering();
        int action = TurnGuard.action();
        double heading = TurnGuard.desiredHeading(), destX = TurnGuard.destX(), destZ = TurnGuard.destZ();
        boolean emergency = avoidance && action == TurnPlanner.EMERGENCY;
        double look = p.getYRot();
        float navRate = (Nav.landing() ? Autopilot.LANDING_TURN_DPS : Autopilot.NAV_TURN_DPS) / 20f;   // per tick

        xs[0] = 0; ys[0] = 0; zs[0] = 0;
        for (int k = 1; k <= TICKS; k++) {
            if (avoidance) {
                if (emergency) {
                    double course = FlightModel3D.velYaw(s.vx, s.vz);
                    if (Math.abs(FlightModel3D.wrap180(heading - course)) <= TurnController.TURN_DONE_TOL) emergency = false;
                }
                if (emergency) FlightModel3D.step(s, heading, FlightModel3D.EMERGENCY_DELTA);
                else if (action == TurnPlanner.STRAIGHT) FlightModel3D.step(s, FlightModel3D.velYaw(destX - s.x, destZ - s.z));
                else FlightModel3D.step(s, heading);
            } else {
                if (Nav.steering()) look += Mth.clamp(Mth.wrapDegrees(Nav.desiredYaw(s.x, s.z) - (float) look), -navRate, navRate);
                FlightModel3D.stepLook(s, look);
            }
            xs[k] = s.x - px; ys[k] = s.y - py; zs[k] = s.z - pz;
        }
        count = TICKS + 1;
    }
}
