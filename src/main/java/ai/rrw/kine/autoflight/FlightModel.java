package ai.rrw.kine.autoflight;

/**
 * Pure forward simulator for the elytra autopilot: the reverse-engineered
 * updateFallFlyingMovement physics, plus the FlightDirector control law (altitude-triggered
 * hold / speed-triggered climb), plus the Autopilot pitch lag.
 *
 * The law and lag modelled here are exactly what the live controller runs, so a rollout predicts
 * the path that will actually be flown -- that equality is the whole point, and Phase C unifies
 * FlightDirector to call into this class. For now the constants/law mirror FlightDirector.java and
 * are audited identical; see the cross-check in the verification harness.
 *
 * All arithmetic is double and the operation order matches the Python reference (terrain_poc.py)
 * tick-for-tick, so the two simulators agree to within floating-point noise.
 */
public final class FlightModel {
    private FlightModel() {}

    // ---- physics ----
    public static final double GRAV = 0.08, SMOOTH = 0.15, CAP = 7.0;

    // ---- CLIMB profile (FlightDirector C_*): speed-triggered, max climb ----
    static final double C_DIVE = 40.0, C_UP = -62.0, C_TRIG = 44.0, C_SWEEP = 18.0 / 20.0;
    static final int    C_TOP  = 11;
    // ---- HOLD profile (FlightDirector L_*): altitude-triggered ----
    static final double L_DIVE = 38.0, L_UP = -66.0, L_SWEEP = 24.0 / 20.0;
    static final int    L_TOP  = 13;
    static final double DESC_MARGIN = 6.0;   // holding: pull up when y <= target + this

    static final int PH_HOLD = 0, PH_TOP = 1, PH_SWEEP = 2;

    /** One tick of updateFallFlyingMovement in the vertical plane (pitch already eased).
     *  v[0]=vx, v[1]=vy in blocks/tick; mutated in place. */
    public static void physicsStep(double pitch, double[] v) {
        double f = Math.toRadians(pitch);
        double cf = Math.cos(f), sf = Math.sin(f);
        double lift = cf * cf;
        double h0 = v[0];
        v[1] += GRAV * (-1.0 + lift * 0.75);
        if (v[1] < 0.0 && cf > 0.0) {                 // descent redirection
            double conv = v[1] * -0.1 * lift;
            v[0] += conv; v[1] += conv;
        }
        if (f < 0.0 && cf > 0.0) {                     // nose-up zoom: x3.2 into vertical
            double conv3 = h0 * (-sf) * 0.04;
            v[0] -= conv3; v[1] += conv3 * 3.2;
        }
        if (cf > 0.0) {                                // steering toward h0
            v[0] += (h0 - v[0]) * 0.1;
        }
        v[0] *= 0.99; v[1] *= 0.98;                    // drag
    }

    /** Full controller + kinematic state. One instance is the "live" aircraft; rollouts copy it. */
    public static final class State {
        public double x, y, vx, vy;          // kinematics (blocks, blocks/tick)
        public double target;                 // commanded trough altitude
        public int phase;
        public double pitch, cmd;
        public int topT;
        public boolean climbing;
        // active profile (a pure function of `climbing`)
        double pDive, pUp, pSweep;
        int pTop;

        public State(double x, double y, double vx, double vy,
                     double target, double pitch, boolean climbing) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy;
            this.target = target; this.pitch = pitch; this.cmd = pitch;
            this.phase = PH_HOLD; this.topT = 0; this.climbing = climbing;
            loadProfile();
        }

        void loadProfile() {
            if (climbing) { pDive = C_DIVE; pUp = C_UP; pSweep = C_SWEEP; pTop = C_TOP; }
            else          { pDive = L_DIVE; pUp = L_UP; pSweep = L_SWEEP; pTop = L_TOP; }
        }

        public State copy() {
            State s = new State(x, y, vx, vy, target, pitch, climbing);
            s.phase = phase; s.cmd = cmd; s.topT = topT;   // profile already set from `climbing`
            return s;
        }
    }

    /** Autopilot lag: ease the actual pitch toward the command (clamped slew + range). */
    static double ease(double pitch, double cmd) {
        pitch += Math.max(-CAP, Math.min(CAP, (cmd - pitch) * SMOOTH));
        return Math.max(-90.0, Math.min(90.0, pitch));
    }

    /** Law state machine: recompute cmd/phase from post-physics y and vx. */
    static void update(State s) {
        double hSpeed = s.vx * 20.0;
        if (s.phase == PH_HOLD) {
            s.cmd = s.pDive;
            boolean pull = s.climbing ? (hSpeed >= C_TRIG) : (s.y <= s.target + DESC_MARGIN);
            if (pull) {                                   // trough: decide next cycle
                s.climbing = s.y < s.target;
                s.loadProfile();
                s.phase = PH_TOP; s.topT = 0; s.cmd = s.pUp;
            }
        } else if (s.phase == PH_TOP) {
            s.cmd = s.pUp; s.topT++;
            if (s.topT >= s.pTop) s.phase = PH_SWEEP;
        } else {                                          // SWEEP
            s.cmd += s.pSweep;
            if (s.cmd >= s.pDive) { s.cmd = s.pDive; s.phase = PH_HOLD; }
        }
    }

    /** Advance one tick: ease -> physics -> integrate -> update. Returns the pitch flown. */
    public static double step(State s) {
        s.pitch = ease(s.pitch, s.cmd);
        double[] v = { s.vx, s.vy };
        physicsStep(s.pitch, v);
        s.vx = v[0]; s.vy = v[1];
        s.x += s.vx; s.y += s.vy;
        update(s);
        return s.pitch;
    }
}
