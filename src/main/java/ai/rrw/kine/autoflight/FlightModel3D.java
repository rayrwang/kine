package ai.rrw.kine.autoflight;

/**
 * 3-D forward simulator: LivingEntity.updateFallFlyingMovement in full, the {@link PorpoiseLaw} and
 * the Autopilot's pitch response. Horizontal velocity turns 10% per tick toward the look heading at
 * the same horizontal speed, so pointing the look off the velocity turns the aircraft, and the turn
 * costs climb as the physics dictates.
 */
public final class FlightModel3D {
    private FlightModel3D() {}

    /** The autopilot's pitch response while the porpoise law flies: straight toward the command at up to CAP degrees a
     *  tick (300 deg/s), within a fast human mouse flick (~/_/kine_fastpitch_2026-10-11/REPORT.md, RW 2026-10-11 "try
     *  2"). Was 0.15 of the gap, capped at 7 deg a tick. */
    public static final double GRAV = 0.08, SMOOTH = 1.0, CAP = 15.0;
    /** The landing law's pitch response, kept as it was tuned (~/_/kine_landing_2026-10-11/): 0.15 of the gap, at most
     *  7 deg a tick. */
    public static final double LANDING_SMOOTH = 0.15, LANDING_CAP = 7.0;

    /** Max look-offset (deg) the steering will command in one tick: caps turn aggressiveness. */
    public static final double DELTA_MAX = 20.0;
    /** Look offset for the emergency bank: a sharper turn that bleeds speed without the stall a look
     *  flipped straight backwards would cause. */
    public static final double EMERGENCY_DELTA = 45.0;

    // heading helpers (Minecraft yaw: 0 = +Z, 90 = -X, clockwise from above)
    /** Velocity heading (yaw, deg) of a horizontal velocity (vx,vz). */
    public static double velYaw(double vx, double vz) { return Math.toDegrees(Math.atan2(-vx, vz)); }
    /** Wrap an angle to (-180, 180]. */
    public static double wrap180(double a) {
        while (a > 180.0)  a -= 360.0;
        while (a <= -180.0) a += 360.0;
        return a;
    }

    /** One tick of updateFallFlyingMovement in full 3-D (pitch already eased, look at `yaw`).
     *  v[0]=vx, v[1]=vy, v[2]=vz in blocks/tick; mutated in place. */
    public static void physicsStep(double yaw, double pitch, double[] v) {
        double pr = Math.toRadians(pitch);
        double yr = -Math.toRadians(yaw);
        double cp = Math.cos(pr), sp = Math.sin(pr);
        double lx = Math.sin(yr) * cp;          // horizontal look components (cy*cp etc.)
        double lz = Math.cos(yr) * cp;
        double lean = pr;
        double lookHor = Math.sqrt(lx * lx + lz * lz);
        double moveHor = Math.sqrt(v[0] * v[0] + v[2] * v[2]);
        double lift = cp * cp;
        v[1] += GRAV * (-1.0 + lift * 0.75);
        if (v[1] < 0.0 && lookHor > 0.0) {                       // descent redirection
            double c = v[1] * -0.1 * lift;
            v[0] += lx * c / lookHor; v[1] += c; v[2] += lz * c / lookHor;
        }
        if (lean < 0.0 && lookHor > 0.0) {                       // nose-up zoom: x3.2 into vertical
            double c = moveHor * (-Math.sin(lean)) * 0.04;
            v[0] += -lx * c / lookHor; v[1] += c * 3.2; v[2] += -lz * c / lookHor;
        }
        if (lookHor > 0.0) {                                     // steering toward look heading
            v[0] += (lx / lookHor * moveHor - v[0]) * 0.1;
            v[2] += (lz / lookHor * moveHor - v[2]) * 0.1;
        }
        v[0] *= 0.99; v[1] *= 0.98; v[2] *= 0.99;                // drag
    }

    /** Law and kinematic state. One instance is the live aircraft; rollouts copy it. */
    public static final class State extends PorpoiseLaw {
        public double x, y, z, vx, vy, vz;     // world kinematics (blocks, blocks/tick)
        public double target;                   // trough floor the law flies to
        public double pitch;

        public State(double x, double y, double z, double vx, double vy, double vz,
                     double target, double pitch, boolean climbing) {
            this.x = x; this.y = y; this.z = z; this.vx = vx; this.vy = vy; this.vz = vz;
            this.target = target; this.pitch = pitch; this.cmd = pitch;
            this.climbing = climbing;
        }

        public State copy() {
            State s = new State(x, y, z, vx, vy, vz, target, pitch, climbing);
            s.copyLaw(this);
            return s;
        }
    }

    /** The Autopilot's pitch response to the porpoise law, per tick: SMOOTH of the way toward the command, capped at
     *  CAP degrees. */
    static double ease(double pitch, double cmd) {
        pitch += Math.max(-CAP, Math.min(CAP, (cmd - pitch) * SMOOTH));
        return Math.max(-90.0, Math.min(90.0, pitch));
    }

    /** The same for the landing law's pitch, with the landing's own response. */
    static double easeLanding(double pitch, double cmd) {
        pitch += Math.max(-LANDING_CAP, Math.min(LANDING_CAP, (cmd - pitch) * LANDING_SMOOTH));
        return Math.max(-90.0, Math.min(90.0, pitch));
    }

    /** Advance one tick with a given look yaw and no steering clamp: ease pitch, physics, integrate,
     *  law. Returns the pitch flown. */
    public static double stepLook(State s, double lookYaw) {
        s.pitch = ease(s.pitch, s.cmd);
        double[] v = { s.vx, s.vy, s.vz };
        physicsStep(lookYaw, s.pitch, v);
        s.vx = v[0]; s.vy = v[1]; s.vz = v[2];
        s.x += s.vx; s.y += s.vy; s.z += s.vz;
        s.update(s.y, Math.sqrt(s.vx * s.vx + s.vz * s.vz) * 20.0, s.target);
        return s.pitch;
    }

    /** Advance one tick steering toward `targetHeading` (yaw, deg), rate-limited by DELTA_MAX:
     *  the look is placed at velYaw + clamp(targetHeading - velYaw, +-DELTA_MAX). */
    public static double step(State s, double targetHeading) {
        return step(s, targetHeading, DELTA_MAX);
    }

    /** The same with another look offset limit (EMERGENCY_DELTA for the emergency bank). */
    public static double step(State s, double targetHeading, double maxDelta) {
        double vy0 = velYaw(s.vx, s.vz);
        double delta = Math.max(-maxDelta, Math.min(maxDelta, wrap180(targetHeading - vy0)));
        return stepLook(s, vy0 + delta);
    }
}
