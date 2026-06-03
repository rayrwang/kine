package ai.rrw.kine.autoflight;

/**
 * Pure forward simulator for the TURN-capable elytra autopilot.
 *
 * This is the full 3-D sibling of {@link FlightModel}: the same reverse-engineered
 * updateFallFlyingMovement physics and the same FlightDirector porpoise law (altitude-triggered
 * hold / speed-triggered climb), but in the horizontal plane it keeps vx AND vz and adds the
 * steering term -- horizontal velocity lerps 10%/tick toward a vector of the same horizontal speed
 * pointing along the look heading. Pointing the look off the velocity by a capped offset turns the
 * aircraft; the turn bleeds climb, exactly as the physics dictates.
 *
 * Flown straight (target heading == velocity heading, so the steering offset is 0) this reduces to
 * the 1-D projection in {@link FlightModel} and matches it tick-for-tick to floating-point noise --
 * that equality is asserted in the verification harness, so the 3-D port inherits the 1-D
 * validation. Arithmetic order matches the Python reference (turn_sim.py / terrain_turn_poc.py).
 */
public final class FlightModel3D {
    private FlightModel3D() {}

    // physics + law constants: identical to FlightModel (audited equal)
    public static final double GRAV = 0.08, SMOOTH = 0.15, CAP = 7.0;
    static final double C_DIVE = 40.0, C_UP = -62.0, C_TRIG = 44.0, C_SWEEP = 18.0 / 20.0;
    static final int    C_TOP  = 11;
    static final double L_DIVE = 38.0, L_UP = -66.0, L_SWEEP = 24.0 / 20.0;
    static final int    L_TOP  = 13;
    static final double DESC_MARGIN = 6.0;
    static final int PH_HOLD = 0, PH_TOP = 1, PH_SWEEP = 2;

    /** Max look-offset (deg) the steering will command in one tick: caps turn aggressiveness. */
    public static final double DELTA_MAX = 20.0;

    // ---- heading helpers (MC yaw convention: 0=+Z, 90=-X, clockwise from above) ----
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

    /** Full controller + kinematic state. One instance is the "live" aircraft; rollouts copy it. */
    public static final class State {
        public double x, y, z, vx, vy, vz;     // world kinematics (blocks, blocks/tick)
        public double target;                   // commanded trough altitude
        public int phase;
        public double pitch, cmd;
        public int topT;
        public boolean climbing;
        double pDive, pUp, pSweep;
        int pTop;

        public State(double x, double y, double z, double vx, double vy, double vz,
                     double target, double pitch, boolean climbing) {
            this.x = x; this.y = y; this.z = z; this.vx = vx; this.vy = vy; this.vz = vz;
            this.target = target; this.pitch = pitch; this.cmd = pitch;
            this.phase = PH_HOLD; this.topT = 0; this.climbing = climbing;
            loadProfile();
        }

        void loadProfile() {
            if (climbing) { pDive = C_DIVE; pUp = C_UP; pSweep = C_SWEEP; pTop = C_TOP; }
            else          { pDive = L_DIVE; pUp = L_UP; pSweep = L_SWEEP; pTop = L_TOP; }
        }

        public State copy() {
            State s = new State(x, y, z, vx, vy, vz, target, pitch, climbing);
            s.phase = phase; s.cmd = cmd; s.topT = topT;
            return s;
        }
    }

    static double ease(double pitch, double cmd) {
        pitch += Math.max(-CAP, Math.min(CAP, (cmd - pitch) * SMOOTH));
        return Math.max(-90.0, Math.min(90.0, pitch));
    }

    /** Law state machine: recompute cmd/phase from post-physics y and horizontal speed. */
    static void update(State s) {
        double hSpeed = Math.sqrt(s.vx * s.vx + s.vz * s.vz) * 20.0;
        if (s.phase == PH_HOLD) {
            s.cmd = s.pDive;
            boolean pull = s.climbing ? (hSpeed >= C_TRIG) : (s.y <= s.target + DESC_MARGIN);
            if (pull) {
                s.climbing = s.y < s.target;
                s.loadProfile();
                s.phase = PH_TOP; s.topT = 0; s.cmd = s.pUp;
            }
        } else if (s.phase == PH_TOP) {
            s.cmd = s.pUp; s.topT++;
            if (s.topT >= s.pTop) s.phase = PH_SWEEP;
        } else {
            s.cmd += s.pSweep;
            if (s.cmd >= s.pDive) { s.cmd = s.pDive; s.phase = PH_HOLD; }
        }
    }

    /** Advance one tick steering toward `targetHeading` (yaw, deg), rate-limited by DELTA_MAX:
     *  ease pitch -> steer + physics -> integrate -> law. Returns the pitch flown. */
    public static double step(State s, double targetHeading) {
        double vy0 = velYaw(s.vx, s.vz);
        double delta = Math.max(-DELTA_MAX, Math.min(DELTA_MAX, wrap180(targetHeading - vy0)));
        double lookYaw = vy0 + delta;
        s.pitch = ease(s.pitch, s.cmd);
        double[] v = { s.vx, s.vy, s.vz };
        physicsStep(lookYaw, s.pitch, v);
        s.vx = v[0]; s.vy = v[1]; s.vz = v[2];
        s.x += s.vx; s.y += s.vy; s.z += s.vz;
        update(s);
        return s.pitch;
    }
}
