package ai.rrw.kine.autoflight;

import ai.rrw.kine.Settings;
import ai.rrw.kine.guard.Pilot;
import ai.rrw.kine.move.MoveState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * The engaged autopilot's own future commands, as a {@link Pilot} for the elytra guardian's rollouts: the look it will
 * set each tick if nothing changes, flown in the movement copy. It mirrors Autopilot.plan and the path prediction
 * (PathPrediction): the pitch eased toward the porpoise law's command (FlightModel3D.ease), the law updated each tick
 * from the altitude and the ground speed toward the trough floor; the yaw by the landing law (15 degrees a tick, its
 * own pitch response), terrain avoidance's look placement (the track plus the committed heading's offset, at most
 * DELTA_MAX), the hold's circle, or the nav heading at the nav turn rate; and on the way to a managed destination,
 * from where it comes within Nav's arrival radius, the hold or the landing law onto the spot Nav would pick. As the
 * path prediction does, terrain avoidance's current plan is held (its replans are not foreseen).
 *
 * <p>Snapshotted on the game's thread ({@link #of}); the rollouts run anywhere.
 */
public final class ApModel implements Pilot {
    static final int NONE = 0, LANDING = 1, HOLD = 2, AVOID = 3, NAV = 4;

    int mode;
    final PorpoiseLaw law = new PorpoiseLaw();
    double floor;                       // the trough floor the law flies to
    // landing
    LandingModel.Law landing;
    double spotX, spotTop, spotZ;
    // hold, and a managed destination
    double fixX, fixZ;
    // terrain avoidance's plan: straight at (destX, destZ), or a committed heading
    boolean straight;
    double heading, destX, destZ;
    // nav without terrain avoidance: a selected heading (as a yaw), else a managed destination
    boolean selected;
    double selectedYaw;
    // on the way to a managed destination: what happens within the arrival radius
    boolean arriving, holdThere;
    double[] arrivalSpot;               // null: no terrain there yet, the rollout's landing can't start
    boolean avoidanceOn;

    ApModel() {}

    @Override
    public ApModel copy() {
        ApModel m = new ApModel();
        m.mode = mode; m.law.copyLaw(law); m.law.cmd = law.cmd; m.floor = floor;
        m.landing = landing == null ? null : landing.copy();
        m.spotX = spotX; m.spotTop = spotTop; m.spotZ = spotZ;
        m.fixX = fixX; m.fixZ = fixZ;
        m.straight = straight; m.heading = heading; m.destX = destX; m.destZ = destZ;
        m.selected = selected; m.selectedYaw = selectedYaw;
        m.arriving = arriving; m.holdThere = holdThere; m.arrivalSpot = arrivalSpot; m.avoidanceOn = avoidanceOn;
        return m;
    }

    /**
     * The autopilot's commands from now, read on the game's thread at the end of a tick (after the flight director,
     * terrain avoidance and nav have run), or null when the autopilot doesn't steer: not engaged, or engaged without
     * an armed director outside a landing (it holds the view then, and trips off).
     */
    public static ApModel of(Minecraft mc, LocalPlayer p) {
        if (!Autopilot.isEngaged() || !p.isFallFlying()) return null;
        ApModel m = new ApModel();
        if (Nav.landing()) {
            m.mode = LANDING;
            m.landing = Nav.landingLaw();
            m.spotX = Nav.spotX(); m.spotTop = Nav.spotTop(); m.spotZ = Nav.spotZ();
            return m;
        }
        if (!FlightDirector.isActive()) return null;
        m.law.copyLaw(FlightDirector.law());
        m.law.cmd = FlightDirector.law().cmd;
        m.floor = FlightDirector.floorAltitude();
        boolean avoidance = Settings.terrainAvoidance && TurnGuard.steering();
        m.avoidanceOn = avoidance;
        int action = TurnGuard.action();
        m.fixX = Nav.targetX() + 0.5; m.fixZ = Nav.targetZ() + 0.5;
        if (Nav.holding() && !(avoidance && action != TurnPlanner.STRAIGHT)) m.mode = HOLD;
        else if (avoidance) {
            m.mode = AVOID;
            m.straight = action == TurnPlanner.STRAIGHT;
            m.heading = TurnGuard.desiredHeading();
            m.destX = TurnGuard.destX(); m.destZ = TurnGuard.destZ();
        } else if (Nav.steering()) {
            m.mode = NAV;
            m.selected = Nav.mode() == Nav.Mode.SELECTED;
            m.selectedYaw = Nav.selectedHeading() + 180.0;
        } else m.mode = NONE;
        m.arriving = Nav.arriving();
        if (m.arriving) {
            m.holdThere = Nav.holdNow(mc);
            if (!m.holdThere) m.arrivalSpot = Nav.arrivalSpot(mc.level);
        }
        return m;
    }

    @Override
    public void look(MoveState s, float yaw, float pitch, float[] out) {
        double velYaw = FlightModel3D.velYaw(s.vx, s.vz);
        if (arriving && Math.hypot(s.x - fixX, s.z - fixZ) < Nav.ARRIVAL_RADIUS) {
            arriving = false;
            if (holdThere) mode = HOLD;
            else if (arrivalSpot != null) {
                mode = LANDING;
                landing = new LandingModel.Law();
                spotX = arrivalSpot[0]; spotTop = arrivalSpot[1]; spotZ = arrivalSpot[2];
            }
        }
        double y = yaw, p;
        if (mode == LANDING) {
            landing.update(s.x, s.y, s.z, s.vy, spotX, spotTop, spotZ);
            y = yaw + Math.max(-LandingModel.YAW_RATE, Math.min(LandingModel.YAW_RATE, FlightModel3D.wrap180(landing.yaw - yaw)));
            p = FlightModel3D.easeLanding(pitch, landing.pitch);
        } else {
            p = FlightModel3D.ease(pitch, law.cmd);
            double navRate = Autopilot.NAV_TURN_DPS / 20.0;
            switch (mode) {
                case HOLD -> {
                    double want = HoldModel.lookYaw(s.x, s.z, fixX, fixZ, velYaw, law.climbing);
                    y = avoidanceOn ? want : yaw + Math.max(-navRate, Math.min(navRate, FlightModel3D.wrap180(want - yaw)));
                }
                case AVOID -> {
                    double target = straight ? FlightModel3D.velYaw(destX - s.x, destZ - s.z) : heading;
                    double dmax = FlightModel3D.DELTA_MAX;
                    y = velYaw + Math.max(-dmax, Math.min(dmax, FlightModel3D.wrap180(target - velYaw)));
                }
                case NAV -> {
                    double want = selected ? selectedYaw : FlightModel3D.velYaw(fixX - s.x, fixZ - s.z);
                    y = yaw + Math.max(-navRate, Math.min(navRate, FlightModel3D.wrap180(want - yaw)));
                }
                default -> { }
            }
        }
        out[0] = (float) y;
        out[1] = (float) Math.max(-90.0, Math.min(90.0, p));
    }

    @Override
    public void after(MoveState s) {
        if (mode == LANDING) return;
        law.update(s.y, Math.sqrt(s.vx * s.vx + s.vz * s.vz) * 20.0, floor);
    }

    @Override
    public String toString() {
        return switch (mode) {
            case LANDING -> "autopilot landing";
            case HOLD -> "autopilot hold";
            case AVOID -> "autopilot terrain avoidance" + (straight ? " straight" : " turn " + Math.round(heading));
            case NAV -> "autopilot nav" + (selected ? " heading" : " destination");
            default -> "autopilot";
        };
    }
}
