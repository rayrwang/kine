package ai.rrw.kine.walk;

/**
 * The walking rails' shape, worked out once per plan on the search's worker: one smooth centreline along the route and
 * two rails either side of it, each a continuous strip.
 *
 * <ul>
 *   <li>The route's ground points are resampled evenly (every STEP blocks across the ground) and the centreline is
 *       their running average over a window either side, so a bend of the route becomes an arc instead of a kink and
 *       straight runs stay straight; its ends stay where they are.</li>
 *   <li>Each point's across direction is square to the centreline there, and the rails lie HALF either side along it;
 *       consecutive pieces of a rail share their edge points, so a rail has no gaps or overlaps, at bends included.</li>
 *   <li>Each rail point's height is the top of whatever is right under it (the table's blocks, found near the
 *       walker's height), and the rail runs along the upper envelope of those tops with slopes of at most SLOPE: over
 *       stairs and steps it ramps like a handrail instead of stepping, and it never dips into a block.</li>
 * </ul>
 * Plain arrays and no Minecraft class: WalkRender streams the part near the player each frame, fading it by distance
 * along the centreline.
 */
public final class RailPath {
    static final double STEP = 0.25;      // spacing of the points along the ground
    static final int WINDOW = 4;          // running average over this many points either side (one block)
    static final double HALF = 0.45;      // rails this far either side of the centreline
    static final double SLOPE = 1.0;      // the steepest a rail ramps (blocks up a block along)
    static final double LIFT = 0.04;      // above the surface
    static final double MIN_OFF = 0.12;   // the closest a rail is drawn in toward the centreline

    /** Points: the centreline (x, z), its height, the across direction (unit, pointing left of travel), the distance
     *  along it, and each rail's height (left = +across, right = -across). */
    public final int n;
    public final double[] x, z, y, ax, az, along, leftY, rightY;
    /** Each rail's distance from the centreline, point by point (HALF unless a wall is closer). */
    public double[] leftOff, rightOff;

    RailPath(View view, Route r) {
        // 1. even samples along the ground points (across the ground; points one above another are kept as they are)
        int cap = 16;
        double[] sx = new double[cap], sy = new double[cap], sz = new double[cap];
        int m = 0;
        for (int i = 0; i < r.gn; i++) {
            if (i == 0) { sx[0] = r.gx[0]; sy[0] = r.gy[0]; sz[0] = r.gz[0]; m = 1; continue; }
            double x0 = r.gx[i - 1], y0 = r.gy[i - 1], z0 = r.gz[i - 1], x1 = r.gx[i], y1 = r.gy[i], z1 = r.gz[i];
            double len = Math.hypot(x1 - x0, z1 - z0);
            int k = Math.max(1, (int) Math.round(len / STEP));
            for (int j = 1; j <= k; j++) {
                if (m + 1 >= cap) {
                    cap *= 2;
                    sx = java.util.Arrays.copyOf(sx, cap); sy = java.util.Arrays.copyOf(sy, cap); sz = java.util.Arrays.copyOf(sz, cap);
                }
                double f = (double) j / k;
                sx[m] = x0 + (x1 - x0) * f; sy[m] = y0 + (y1 - y0) * f; sz[m] = z0 + (z1 - z0) * f;
                m++;
            }
        }
        n = m;
        x = new double[n]; z = new double[n]; y = new double[n];
        ax = new double[n]; az = new double[n]; along = new double[n];
        leftY = new double[n]; rightY = new double[n];
        leftOff = new double[n]; rightOff = new double[n];
        if (n == 0) return;

        // 2. the centreline: a running average across the ground, the window shrunk evenly near the ends
        for (int i = 0; i < n; i++) {
            int w = Math.min(WINDOW, Math.min(i, n - 1 - i));
            double ux = 0, uz = 0;
            for (int j = i - w; j <= i + w; j++) { ux += sx[j]; uz += sz[j]; }
            x[i] = ux / (2 * w + 1);
            z[i] = uz / (2 * w + 1);
        }

        // 3. across directions and the distance along
        double hx = 0, hz = 1;
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
            double dx = x[b] - x[a], dz = z[b] - z[a], len = Math.hypot(dx, dz);
            if (len > 1e-6) { hx = dx / len; hz = dz / len; }
            ax[i] = hz;     // left of travel in Minecraft's frame: (-z, x) turned; +x east, +z south: left of (hx, hz) is (hz, -hx)
            az[i] = -hx;
            along[i] = i == 0 ? 0 : along[i - 1] + Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
        }
        // a bend's points before the first move along it take the first direction
        for (int i = 0; i < n && ax[i] == 0 && az[i] == 0; i++) { ax[i] = hz; az[i] = -hx; }

        // 4. each rail's distance from the centreline: HALF, drawn in where that would put it in a wall (the route
        //    hugs inside corners at the safety margin, and the averaged centreline cuts them a little more); then eased:
        //    the smallest within a few points either side, averaged, so it bends in and out instead of stepping
        Sweep probe = new Sweep(view, Costs.BARITONE, 0);
        leftOff = offsets(probe, sy, 1);
        rightOff = offsets(probe, sy, -1);

        // 5. heights: the surface right under each rail point and under the centre, then the upper envelope
        double[] sl = new double[n], sr = new double[n], sc = new double[n];
        for (int i = 0; i < n; i++) {
            double base = sy[i];
            sc[i] = surface(probe, x[i], z[i], base);
            sl[i] = surface(probe, x[i] + ax[i] * leftOff[i], z[i] + az[i] * leftOff[i], base);
            sr[i] = surface(probe, x[i] - ax[i] * rightOff[i], z[i] - az[i] * rightOff[i], base);
        }
        envelope(sc, y);
        envelope(sl, leftY);
        envelope(sr, rightY);
        for (int i = 0; i < n; i++) { y[i] += LIFT; leftY[i] += LIFT; rightY[i] += LIFT; }
    }

    /** One rail's distances from the centreline (side 1 left, -1 right). */
    private double[] offsets(Sweep probe, double[] sy, int side) {
        double[] off = new double[n];
        for (int i = 0; i < n; i++) {
            double o = HALF;
            while (o > MIN_OFF) {
                double px = x[i] + side * ax[i] * o, pz = z[i] + side * az[i] * o;
                if (probe.clear(px, pz, 0.06, sy[i] + 0.6, sy[i] + 1.5)) break;
                o -= 0.05;
            }
            off[i] = Math.max(MIN_OFF, o);
        }
        double[] lo = new double[n];
        for (int i = 0; i < n; i++) {
            double m = off[i];
            for (int j = Math.max(0, i - 3); j <= Math.min(n - 1, i + 3); j++) m = Math.min(m, off[j]);
            lo[i] = m;
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int w = Math.min(2, Math.min(i, n - 1 - i));
            double sum = 0;
            for (int j = i - w; j <= i + w; j++) sum += lo[j];
            out[i] = sum / (2 * w + 1);
        }
        return out;
    }

    /** The highest top under a small square at (px, pz) from 3 below to a step above `base`; `base` if nothing. */
    private static double surface(Sweep probe, double px, double pz, double base) {
        double g = probe.ground(px, pz, 0.04, base + 0.6, base - 3.0);
        return Double.isNaN(g) ? base : g;
    }

    /** out[i] = the largest of h[j] - SLOPE * (distance from i to j), j within the reach of a full ramp: a rail over
     *  those tops that never goes under one, ramping instead of stepping. */
    private void envelope(double[] h, double[] out) {
        int reach = (int) Math.ceil(1.0 / STEP);   // a ramp at most a block long: stairs and one-block steps ramp, a cliff stays a cliff
        for (int i = 0; i < n; i++) {
            double best = h[i];
            for (int j = Math.max(0, i - reach); j <= Math.min(n - 1, i + reach); j++) {
                double v = h[j] - SLOPE * Math.abs(along[i] - along[j]);
                if (v > best) best = v;
            }
            out[i] = best;
        }
    }

    /** The index of the centreline segment nearest (px, pz), searching from `hint` outward (the last frame's), and the
     *  distance along the centreline of the nearest point, in out[0]. */
    int nearest(double px, double pz, int hint, double[] out) {
        if (n < 2) { out[0] = 0; return 0; }
        int lo = Math.max(0, hint - 40), hi = Math.min(n - 2, hint + 40);
        if (hint < 0) { lo = 0; hi = n - 2; }
        int best = lo;
        double bestD = Double.MAX_VALUE, bestT = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = lo; i <= hi; i++) {
                double dx = x[i + 1] - x[i], dz = z[i + 1] - z[i], len2 = dx * dx + dz * dz;
                double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((px - x[i]) * dx + (pz - z[i]) * dz) / len2));
                double qx = x[i] + dx * t - px, qz = z[i] + dz * t - pz, d = qx * qx + qz * qz;
                if (d < bestD) { bestD = d; best = i; bestT = t; }
            }
            if (bestD < 9 || (lo == 0 && hi == n - 2)) break;
            lo = 0; hi = n - 2;   // far from the hint: search it all
        }
        out[0] = along[best] + (along[best + 1] - along[best]) * bestT;
        return best;
    }
}
