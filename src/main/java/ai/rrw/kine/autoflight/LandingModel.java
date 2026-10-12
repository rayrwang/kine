package ai.rrw.kine.autoflight;

/**
 * The landing law, free of game types so it can be tested: where to look each tick to land on a spot, and a
 * rollout of the landing that says where it would touch down and what it would cost.
 *
 * <p>Five phases. APPROACH: glide at 5 degrees nose down toward the spot, which stays above the clear line of sight
 * to it, until within ENTRY. DIVE: a spiral of radius 2 around the spot at 60 degrees nose down, nearly a vertical
 * plunge at about 28 m/s. BRAKE: from BRAKE_HEIGHT above the spot, nose up 30 degrees with the look on the spot, until
 * sinking slower than 0.35 blocks a tick. MID: the spiral again at 25 degrees nose down, about 5.5 m/s down, to
 * FINAL_HEIGHT. FINAL: nose up 5 degrees with the look on the spot: about 3 m/s down, drifting onto it.
 *
 * <p>Tuned on the 26.1.2 physics (~/_/kine_landing_2026-10-11/) for speed under failure: a 1 s freeze of the controls
 * anywhere in the landing costs no health, and once braked, any frozen or bumped look (+-30 pitch, 60 yaw) touches
 * down with no fall damage (sinking under 0.5 blocks a tick resets the fall distance while gliding,
 * Entity.checkFallDistanceAccumulation). MID at 25 degrees is the steepest that keeps that true (30 does not: a bumped
 * freeze there costs 32), and saves about a quarter of the time (from 100 up: 22 s to 15 s). Pitch is Minecraft's,
 * positive nose down.
 */
public final class LandingModel {
    private LandingModel() {}

    public static final int APPROACH = 0, DIVE = 1, BRAKE = 2, MID = 3, FINAL = 4;

    public static final double ENTRY = 8.0;            // horizontal distance at which the spiral starts
    public static final double RADIUS = 2.0;           // the spiral's radius
    public static final double RADIAL_GAIN = 20.0;     // degrees of turn-in per block off the radius
    public static final double APPROACH_PITCH = 5.0;
    public static final double DIVE_PITCH = 60.0;
    public static final double BRAKE_HEIGHT = 60.0;    // above the spot
    public static final double BRAKE_PITCH = -30.0;
    public static final double MID_PITCH = 25.0;
    public static final double FINAL_HEIGHT = 10.0;   // above the spot: the finish starts here
    public static final double FINAL_PITCH = -5.0;
    public static final double SLOW_SINK = -0.35;      // blocks a tick: braked once sinking slower than this
    public static final double YAW_RATE = 15.0;        // degrees a tick the look may turn, 300 a second
    /** Accepted touchdown error for a landing to be offered (blocks from the spot's centre). */
    public static final double MAX_OFFSET = 1.5;

    /** The law's phase and command for one landing. Advance once per tick with {@link #update}. */
    public static final class Law {
        public int phase = APPROACH;
        public double yaw, pitch;

        public Law copy() {
            Law l = new Law();
            l.phase = phase; l.yaw = yaw; l.pitch = pitch;
            return l;
        }

        /** This tick's command from the position, the sink (vy, blocks a tick) and the spot (centre cx, cz; its top
         *  surface at `top`). */
        public void update(double x, double y, double z, double vy, double cx, double top, double cz) {
            double dx = cx - x, dz = cz - z;
            double dist = Math.sqrt(dx * dx + dz * dz), h = y - top;
            double bearing = Math.toDegrees(Math.atan2(-dx, dz));
            if (phase == APPROACH && dist <= ENTRY) phase = DIVE;            // over the spot
            if (phase == DIVE && h <= BRAKE_HEIGHT) phase = BRAKE;           // low arrivals brake at once
            if (phase == BRAKE && vy > SLOW_SINK) phase = h > FINAL_HEIGHT ? MID : FINAL;
            if (phase == MID && h <= FINAL_HEIGHT) phase = FINAL;
            switch (phase) {
                case APPROACH -> { yaw = bearing; pitch = APPROACH_PITCH; }
                case DIVE, MID -> {
                    double corr = Math.max(-90.0, Math.min(90.0, (dist - RADIUS) * RADIAL_GAIN));
                    yaw = bearing + 90.0 - corr;
                    pitch = phase == DIVE ? DIVE_PITCH : MID_PITCH;
                }
                case BRAKE -> { yaw = bearing; pitch = BRAKE_PITCH; }
                default -> { yaw = bearing; pitch = FINAL_PITCH; }
            }
        }
    }

    /** Touchdown of a rollout: whether it reached the spot's height, how far from the spot's centre, the fall damage,
     *  the ticks, and the path. */
    public record Result(boolean touched, double offset, double damage, int ticks,
                         double[] xs, double[] ys, double[] zs, int count) {}

    /**
     * Roll a landing out from the given state (velocity in blocks a tick; the look's yaw and pitch) to the spot's
     * height, as the autopilot flies it: the look turns at most YAW_RATE a tick toward the command and the pitch eases
     * as {@link FlightModel3D#easeLanding}. The ground is taken as level with the spot. Positions are kept for at most
     * `keep` ticks.
     */
    public static Result simulate(Law law, double x, double y, double z, double vx, double vy, double vz,
                                  double yaw, double pitch, double cx, double top, double cz, int maxTicks, int keep) {
        double[] xs = new double[Math.max(1, keep)], ys = new double[xs.length], zs = new double[xs.length];
        int n = 0;
        if (keep > 0) { xs[0] = x; ys[0] = y; zs[0] = z; n = 1; }
        double[] v = { vx, vy, vz };
        double fall = 1.0;
        for (int t = 1; t <= maxTicks; t++) {
            law.update(x, y, z, v[1], cx, top, cz);
            yaw += Math.max(-YAW_RATE, Math.min(YAW_RATE, FlightModel3D.wrap180(law.yaw - yaw)));
            pitch = FlightModel3D.easeLanding(pitch, law.pitch);
            FlightModel3D.physicsStep(yaw, pitch, v);
            x += v[0]; y += v[1]; z += v[2];
            if (v[1] < 0) fall -= v[1];
            if (v[1] > -0.5 && fall > 1.0) fall = 1.0;
            if (n < keep) { xs[n] = x; ys[n] = y; zs[n] = z; n++; }
            if (y <= top) {
                double off = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                return new Result(true, off, Math.max(0.0, fall - 3.0), t, xs, ys, zs, n);
            }
        }
        return new Result(false, Double.NaN, Double.NaN, maxTicks, xs, ys, zs, n);
    }
}
