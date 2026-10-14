package ai.rrw.kine.guard;

/**
 * The pilot's recent track: where the player has been, one point a tick for the last {@link #TICKS} ticks, oldest
 * first. Air flown through is known to be clear, so a rescue that ends heading back along it scores well.
 */
public final class Track {
    public static final int TICKS = 400;   // 20 s

    final double[] xs, ys, zs;
    final int n;

    public Track(double[] xs, double[] ys, double[] zs, int n) {
        this.xs = xs; this.ys = ys; this.zs = zs; this.n = n;
    }

    public static final Track NONE = new Track(new double[0], new double[0], new double[0], 0);

    public int size() { return n; }

    /** A ring of points kept on the game's thread; {@link #snapshot} copies it out in order. */
    public static final class Ring {
        private final double[] xs = new double[TICKS], ys = new double[TICKS], zs = new double[TICKS];
        private int head = 0, n = 0;

        public void add(double x, double y, double z) {
            xs[head] = x; ys[head] = y; zs[head] = z;
            head = (head + 1) % TICKS;
            if (n < TICKS) n++;
        }

        public void clear() { n = 0; head = 0; }

        public Track snapshot() {
            double[] x = new double[n], y = new double[n], z = new double[n];
            for (int i = 0; i < n; i++) {
                int j = (head - n + i + TICKS) % TICKS;
                x[i] = xs[j]; y[i] = ys[j]; z[i] = zs[j];
            }
            return new Track(x, y, z, n);
        }
    }

    /**
     * How far a rescue ending at (x, y, z) heading `yaw` is from heading back along the track, 0 best: from the track
     * point nearest (x, z), mostly how far the heading is from the reverse of the direction flown there (0 back along
     * it, 1 the same way), a little how far from the track it is (out to 64 blocks); 0.5 with no track to go by. Plus
     * the height below that point (out to 40 blocks, up to `heightWeight`): air flown through is known clear only at
     * the height it was flown at, and a turn loses height (RW 2026-10-14 00:17:30).
     */
    public double cost(double x, double y, double z, double yaw, double heightWeight) {
        if (n < 10) return 0.5;
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < n - 5; i += 2) {
            double dx = xs[i] - x, dz = zs[i] - z, d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = i; }
        }
        double dx = xs[best + 5] - xs[best], dz = zs[best + 5] - zs[best];
        double len = Math.hypot(dx, dz);
        if (len < 1.0e-3) return 0.5;
        double r = Math.toRadians(yaw);
        double hx = -Math.sin(r), hz = Math.cos(r);          // the heading as a unit vector (the game's yaw)
        double cos = -(hx * dx + hz * dz) / len;             // with the reverse of the direction flown
        double heading = (1.0 - cos) / 2.0;
        double dist = Math.min(Math.sqrt(bd), 64.0) / 64.0;
        double drop = Math.max(0.0, Math.min(40.0, ys[best] - y)) / 40.0;
        return 0.8 * heading + 0.2 * dist + heightWeight * drop;
    }
}
