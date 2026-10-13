package ai.rrw.kine.walk;

/**
 * The walking rails' shape, worked out once per plan on the search's worker: one smooth centreline along the route and
 * two rails either side of it, each a continuous strip.
 *
 * <ul>
 *   <li>The route's ground points are resampled evenly (every STEP blocks across the ground) and the centreline is
 *       their running average over a window either side, so a bend of the route becomes an arc instead of a kink and
 *       straight runs stay straight; its ends stay where they are.</li>
 *   <li>Each point's across direction is square to the centreline there, and the rails lie HALF either side along it,
 *       always 2 x HALF apart: where one would be in a wall (an inside corner the averaged centreline cuts) the pair
 *       slides across together, eased along the route, rather than one drawing in. Consecutive pieces of a rail share
 *       their edge points, so a rail has no gaps or overlaps, at bends included.</li>
 *   <li>Heights are what you see on top: a collision top, a snow layer's or soul sand's drawn top, or the surface of
 *       water or lava over it (not the floor under it). The centreline runs along the upper envelope of the surface
 *       under it with slopes of at most SLOPE, ramping over stairs like a handrail; both rails share one height, the
 *       same envelope over the highest of the centre's and both rails' surfaces, so they never dip below the
 *       centreline, never part in height where one side drops, and never sink into a block. Plants are drawn over
 *       them.</li>
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
    static final double SHIFT_STEP = 0.05;   // the pair slides across in these steps, up to HALF

    /** Points: the centreline (x, z), its height, the across direction (unit, pointing left of travel), the distance
     *  along it, and each rail's height (left = +across, right = -across). */
    public final int n;
    public final double[] x, z, y, ax, az, along, leftY, rightY;
    /** Each rail's distance from the centreline, point by point: HALF, or HALF plus and minus the pair's slide away
     *  from a wall (they always add up to 2 x HALF). */
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

        // 4. the rails' slide across: none, or the least that takes both out of the walls (the route hugs inside
        //    corners at the safety margin, and the averaged centreline cuts them a little more); then eased: the
        //    largest within a few points either side, averaged, so the pair slides over and back instead of stepping
        Sweep probe = new Sweep(view, Costs.BARITONE, 0);
        double[] shift = shifts(probe, sy);
        for (int i = 0; i < n; i++) { leftOff[i] = HALF + shift[i]; rightOff[i] = HALF - shift[i]; }

        // 5. heights: what you see on top under the centre and under each rail; the centreline on the envelope of its
        //    own, both rails on the envelope of the highest of the three
        double[] sc = new double[n], sh = new double[n];
        for (int i = 0; i < n; i++) {
            double base = sy[i];
            sc[i] = surface(view, x[i], z[i], base);
            double sl = surface(view, x[i] + ax[i] * leftOff[i], z[i] + az[i] * leftOff[i], base);
            double sr = surface(view, x[i] - ax[i] * rightOff[i], z[i] - az[i] * rightOff[i], base);
            sh[i] = Math.max(sc[i], Math.max(sl, sr));
        }
        envelope(sc, y);
        envelope(sh, leftY);
        for (int i = 0; i < n; i++) { y[i] += LIFT; leftY[i] += LIFT; rightY[i] = leftY[i]; }
    }

    /** How far the rail pair slides toward the left (negative: right) at each point, eased. */
    private double[] shifts(Sweep probe, double[] sy) {
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            if (pairClear(probe, i, 0, sy[i])) continue;
            for (double d = SHIFT_STEP; d <= HALF + 1e-9; d += SHIFT_STEP) {
                if (pairClear(probe, i, d, sy[i])) { s[i] = d; break; }
                if (pairClear(probe, i, -d, sy[i])) { s[i] = -d; break; }
            }
        }
        double[] big = new double[n];
        for (int i = 0; i < n; i++) {
            double m = s[i];
            for (int j = Math.max(0, i - 3); j <= Math.min(n - 1, i + 3); j++) if (Math.abs(s[j]) > Math.abs(m)) m = s[j];
            big[i] = m;
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int w = Math.min(2, Math.min(i, n - 1 - i));
            double sum = 0;
            for (int j = i - w; j <= i + w; j++) sum += big[j];
            out[i] = sum / (2 * w + 1);
        }
        return out;
    }

    /** Both rails out of the walls with the pair slid `d` toward the left: nothing solid where each lies, from above a
     *  step (so a slab or a stair beside is fine, the rail lies on it) to body height. */
    private boolean pairClear(Sweep probe, int i, double d, double base) {
        for (int side = -1; side <= 1; side += 2) {
            double o = side * HALF + d;
            if (!probe.clear(x[i] + ax[i] * o, z[i] + az[i] * o, 0.06, base + 0.6, base + 1.5)) return false;
        }
        return true;
    }

    /** What you see on top under a small square at (px, pz), from 3 below to a step above `base`: in each column the
     *  first thing from above, a collision top or a drawn top (a snow layer, soul sand), or the surface of water or
     *  lava (followed up while the same fluid is above); the highest over the square's columns, `base` if nothing. */
    static double surface(View v, double px, double pz, double base) {
        double half = 0.04, top = base + 0.6, lowest = base - 3.0;
        int xa = (int) Math.floor(px - half), xb = (int) Math.floor(px + half - 1e-9);
        int za = (int) Math.floor(pz - half), zb = (int) Math.floor(pz + half - 1e-9);
        int ya = (int) Math.floor(lowest), yb = (int) Math.floor(top);
        double best = Double.NaN;
        for (int bx = xa; bx <= xb; bx++)
            for (int bz = za; bz <= zb; bz++)
                for (int y = yb; y >= ya; y--) {
                    Kind k = v.block(bx, y, bz);
                    if (k == Palette.UNKNOWN) break;
                    double t = k.topWithin(px - half - bx, px + half - bx, pz - half - bz, pz + half - bz, top - y);
                    if (!Float.isNaN(k.drawnTop) && k.drawnTop <= top - y + 1e-9) t = Double.isNaN(t) ? k.drawnTop : Math.max(t, k.drawnTop);
                    double h = Double.isNaN(t) ? Double.NaN : y + t;
                    int fluid = k.flags & (Kind.WATER | Kind.LAVA);
                    if (fluid != 0) {
                        int fy = y;
                        while (fy < y + 8 && (v.block(bx, fy + 1, bz).flags & fluid) != 0) fy++;
                        float fh = v.block(bx, fy, bz).info.fluidHeight;   // a source's own height, 8/9 (tests: none set)
                        double surf = fy + (fh > 0 ? fh : 8 / 9.0);
                        h = Double.isNaN(h) ? surf : Math.max(h, surf);
                    }
                    if (Double.isNaN(h) || h < lowest) continue;
                    if (Double.isNaN(best) || h > best) best = h;
                    break;   // lower blocks in this column are under this one
                }
        return Double.isNaN(best) ? base : best;
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
