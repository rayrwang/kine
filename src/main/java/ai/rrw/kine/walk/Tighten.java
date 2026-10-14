package ai.rrw.kine.walk;

import java.util.ArrayList;
import java.util.List;

/**
 * Pulls the grid path taut. The path is cut into runs of plain walking (walks and drops on dry ground) and runs of
 * swimming (across a water's top); jumps, ladders, wading and the ways into and out of water stay as they are. In each
 * run, from each point the farthest later point reachable in a straight walk (or swim) is taken, trying far to near
 * (the walk-straight check, {@link Sweep}, with the safety margin; for a swim, open water under the box grown by the
 * margin); then each bend is slid toward its neighbours, replaced by two points on its two legs as far out as a
 * straight walk between them allows (a chamfer), which shortens the path around a corner toward the taut line at the
 * margin. Every new leg is checked, so the route never goes through a wall, over a hole, off a drop of more than 3,
 * near a hazard or out of open water, and every leg is no longer than the grid path it replaces (a straight line
 * between two of its points).
 */
final class Tighten {
    /** How many grid points ahead the farthest-point search looks. */
    static final int LOOK = 64;
    static final double[] CHAMFER = {0.5, 0.35, 0.22, 0.12};
    /** Rounds of sliding the bends, each followed by pulling taut again over the new points. */
    static final int CHAMFER_PASSES = 2;

    private final View v;
    private final Costs c;
    private final Sweep sweep;
    private int checks = 0;

    Tighten(View v, Costs c, double margin, boolean canSprint) {
        this.v = v;
        this.c = c;
        this.sweep = new Sweep(v, c, margin);
        this.sweep.canSprint = canSprint;
    }

    /** The kind of run a move belongs to: WALK for plain walking (walks and drops on dry ground), SWIM for swimming
     *  across a water's top, -1 for the grid's other moves, which stay as they are. Swims were kept step by step until
     *  2026-10-14, so across open water the route was a staircase on the grid's eight directions, up to 13.7 blocks off
     *  the straight line over 70, and layer 2's corridor followed it (RW, 2026-10-13: "path tightneing over water
     *  doenst' seem to work"). */
    private static int runKind(int move) {
        if (move == Graph.WALK || move == Graph.DROP) return Graph.WALK;
        if (move == Graph.SWIM) return Graph.SWIM;
        return -1;
    }

    /** The route along a search result's path. */
    Route route(PathSearch.Result r, int goalX, int goalZ) {
        long t0 = System.nanoTime();
        int n = r.path.length;
        double[] px = new double[n], py = new double[n], pz = new double[n];
        for (int k = 0; k < n; k++) {
            long key = r.path[k];
            px[k] = View.nodeX(key) + 0.5;
            pz[k] = View.nodeZ(key) + 0.5;
            ChunkFloors c = v.chunk(View.nodeX(key), View.nodeZ(key));
            py[k] = c.h(v.index(key));
        }
        List<double[]> pts = new ArrayList<>();       // x, y, z, move
        pts.add(new double[]{px[0], py[0], pz[0], Graph.START});
        int k = 0;
        while (k < n - 1) {
            int kind = runKind(r.moves[k + 1]);
            if (kind < 0) {
                pts.add(new double[]{px[k + 1], py[k + 1], pz[k + 1], r.moves[k + 1]});
                k++;
                continue;
            }
            int e = k + 1;
            while (e + 1 < n && runKind(r.moves[e + 1]) == kind) e++;
            List<double[]> run = pull(kind, px, py, pz, r.moves, k, e);
            for (int pass = 0; pass < CHAMFER_PASSES; pass++) {
                int before = run.size();
                double len0 = length(run);
                run = repull(kind, chamfer(kind, run));
                if (run.size() == before && length(run) > len0 - 1e-3) break;   // nothing left to gain
            }
            for (int i = 1; i < run.size(); i++) pts.add(run.get(i));
            k = e;
        }
        int m = pts.size();
        double[] x = new double[m], y = new double[m], z = new double[m];
        byte[] mv = new byte[m];
        for (int i = 0; i < m; i++) {
            double[] p = pts.get(i);
            x[i] = p[0]; y[i] = p[1]; z[i] = p[2]; mv[i] = (byte) p[3];
        }
        Route route = new Route(x, y, z, mv, m, r.viaExit, goalX, goalZ);
        route.gridCost = r.cost;
        for (int i = 1; i < n; i++) route.gridLength += Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
        route.straightLength = Math.hypot(px[n - 1] - px[0], pz[n - 1] - pz[0]);
        groundAndTime(route);
        route.checks = checks;
        route.tightenNanos = System.nanoTime() - t0;
        return route;
    }

    /** From each point the farthest later one a straight walk (or swim, by the run's kind) reaches, far to near. The
     *  next grid step is always taken; when even it fails the check (a drop that ends in the air, a margin the grid's
     *  corridor can't give) it keeps the grid's own move, which the follower executes as such. Points are {x, y, z,
     *  move into it}. */
    private List<double[]> pull(int kind, double[] px, double[] py, double[] pz, byte[] moves, int s, int e) {
        List<double[]> out = new ArrayList<>();
        out.add(new double[]{px[s], py[s], pz[s], Graph.START});
        int i = s;
        while (i < e) {
            int j = Math.min(e, i + LOOK);
            boolean ok = false;
            while (j > i) {
                if (reaches(kind, px[i], py[i], pz[i], px[j], py[j], pz[j])) { ok = true; break; }
                if (j == i + 1) break;
                j--;
            }
            out.add(new double[]{px[j], py[j], pz[j], ok ? kind : moves[j]});
            i = j;
        }
        return out;
    }

    /** A straight walk from a to b that ends on b's floor. */
    private boolean walks(double ax, double ay, double az, double bx, double by, double bz) {
        checks++;
        return sweep.walk(ax, ay, az, bx, bz) && Math.abs(sweep.endH - by) < 0.05;
    }

    /** A straight walk, or for a run of swims a straight swim, from a to b. */
    private boolean reaches(int kind, double ax, double ay, double az, double bx, double by, double bz) {
        return kind == Graph.SWIM ? swims(ax, ay, az, bx, bz) : walks(ax, ay, az, bx, by, bz);
    }

    /** A straight swim from a to b across open water: at every step along the line, every column under the player's
     *  box grown by the margin holds a swimming floor at a's height (the water's top, one height for a whole run of
     *  swims, as the graph's swims keep it), so the line keeps the margin off banks, islands and anything over the
     *  water. */
    private boolean swims(double ax, double ay, double az, double bx, double bz) {
        checks++;
        double len = Math.hypot(bx - ax, bz - az);
        int steps = Math.max(1, (int) Math.ceil(len / (Sweep.STEP / 2)));
        double r = Sweep.HALF + sweep.margin - 1e-6;
        int lx0 = Integer.MIN_VALUE, lx1 = 0, lz0 = 0, lz1 = 0;
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps, x = ax + (bx - ax) * t, z = az + (bz - az) * t;
            int x0 = (int) Math.floor(x - r), x1 = (int) Math.floor(x + r), z0 = (int) Math.floor(z - r), z1 = (int) Math.floor(z + r);
            if (x0 == lx0 && x1 == lx1 && z0 == lz0 && z1 == lz1) continue;   // the same columns as the last step
            lx0 = x0; lx1 = x1; lz0 = z0; lz1 = z1;
            for (int cx = x0; cx <= x1; cx++)
                for (int cz = z0; cz <= z1; cz++)
                    if (!openWater(cx, cz, ay)) return false;
        }
        return true;
    }

    /** A swimming floor in column (x, z) at the water's top `top`. */
    private boolean openWater(int x, int z, double top) {
        ChunkFloors c = v.chunk(x, z);
        if (c == null) return false;
        int lx = x & 15, lz = z & 15, n = c.count(lx, lz);
        for (int i = 0; i < n; i++) {
            int idx = c.index(lx, lz, i);
            if (c.type(idx) == ChunkFloors.SWIM && Math.abs(c.h(idx) - top) < 1e-4) return true;
        }
        return false;
    }

    /** Pull taut again over points that are no longer grid points: from each, the farthest later one a straight walk
     *  (or swim) reaches (only across straight legs; the grid's own moves stay). */
    private List<double[]> repull(int kind, List<double[]> run) {
        List<double[]> out = new ArrayList<>();
        out.add(run.get(0));
        int i = 0, n = run.size();
        while (i < n - 1) {
            int j = i + 1;
            if (run.get(j)[3] == kind) {
                int far = i + 1;
                while (far + 1 < n && run.get(far + 1)[3] == kind && far - i < LOOK) far++;
                for (int c = far; c > i + 1; c--) {
                    double[] a = run.get(i), b = run.get(c);
                    if (reaches(kind, a[0], a[1], a[2], b[0], b[1], b[2])) { j = c; break; }
                }
            }
            double[] p = run.get(j);
            out.add(j == i + 1 ? p : new double[]{p[0], p[1], p[2], kind});
            i = j;
        }
        return out;
    }

    private static double length(List<double[]> run) {
        double len = 0;
        for (int i = 1; i < run.size(); i++) len += Math.hypot(run.get(i)[0] - run.get(i - 1)[0], run.get(i)[2] - run.get(i - 1)[2]);
        return len;
    }

    /** Slide each bend: replace it with a point on each leg, as far out as a straight walk between them allows. */
    private List<double[]> chamfer(int kind, List<double[]> run) {
        if (run.size() < 3) return run;
        List<double[]> out = new ArrayList<>();
        out.add(run.get(0));
        for (int i = 1; i < run.size() - 1; i++) {
            double[] a = out.get(out.size() - 1), b = run.get(i), c = run.get(i + 1);
            if (b[3] != kind || c[3] != kind) { out.add(b); continue; }   // only bends between two straight legs
            double[] best = null, bestQ = null;
            for (double u : CHAMFER) {
                double[] p = along(b, a, u, kind), q = along(b, c, u, kind);
                if (p == null || q == null) continue;
                if (kind == Graph.SWIM) {
                    // one height across a run of swims: open water from the leg's start to p, p to q, q on to c
                    if (!swims(a[0], a[1], a[2], p[0], p[2]) || !swims(p[0], p[1], p[2], q[0], q[2])
                        || !swims(q[0], q[1], q[2], c[0], c[2])) continue;
                    best = p; bestQ = q;
                    break;
                }
                // heights: walk out from the leg's start to p, then p to q, then q on to c
                if (!walks2(a, p)) continue;
                p[1] = sweep.endH;
                if (!walks2(p, q)) continue;
                q[1] = sweep.endH;
                if (!walks(q[0], q[1], q[2], c[0], c[1], c[2])) continue;
                best = p; bestQ = q;
                break;
            }
            if (best != null) { out.add(best); out.add(bestQ); }
            else out.add(b);
        }
        out.add(run.get(run.size() - 1));
        return out;
    }

    /** Walk from a toward p (any ending height): sets sweep.endH. */
    private boolean walks2(double[] a, double[] p) {
        checks++;
        return sweep.walk(a[0], a[1], a[2], p[0], p[2]);
    }

    /** The point a fraction u of the way from b to a, if that leg is long enough to be worth cutting. */
    private static double[] along(double[] b, double[] a, double u, int kind) {
        double dx = a[0] - b[0], dz = a[2] - b[2];
        double len = Math.sqrt(dx * dx + dz * dz);
        double d = Math.min(len * u, 6.0);
        if (d < 0.25) return null;
        return new double[]{b[0] + dx / len * d, b[1], b[2] + dz / len * d, kind};
    }

    /** The ground along the route for drawing, the route's length and its time from the blocks underfoot. */
    /** A straight route of two points, floor a to floor b (walking without a route: the way between is layer 2's). */
    Route straight(long a, long b, int goalX, int goalZ) {
        double[] x = {View.nodeX(a) + 0.5, View.nodeX(b) + 0.5}, z = {View.nodeZ(a) + 0.5, View.nodeZ(b) + 0.5};
        double[] y = {height(a), height(b)};
        Route r = new Route(x, y, z, new byte[]{(byte) Graph.START, (byte) Graph.WALK}, 2, false, goalX, goalZ);
        r.straightLength = r.gridLength = Math.hypot(x[1] - x[0], z[1] - z[0]);
        groundAndTime(r);
        return r;
    }

    private double height(long key) {
        ChunkFloors ch = v.chunk(View.nodeX(key), View.nodeZ(key));
        return ch.h(v.index(key));
    }

    private void groundAndTime(Route r) {
        List<float[]> g = new ArrayList<>();
        sweep.record = true;
        double time = 0, len = 0;
        g.add(new float[]{(float) r.x[0], (float) r.y[0], (float) r.z[0]});
        for (int i = 1; i < r.n; i++) {
            double seg = Math.hypot(r.x[i] - r.x[i - 1], r.z[i] - r.z[i - 1]);
            len += seg;
            if (r.move[i] == Graph.WALK && sweep.walk(r.x[i - 1], r.y[i - 1], r.z[i - 1], r.x[i], r.z[i])) {
                for (int s = 1; s < sweep.samples; s++) g.add(new float[]{(float) sweep.sx[s], (float) sweep.sh[s], (float) sweep.sz[s]});
                time += sweep.time;
            } else {
                if (r.move[i] == Graph.SWIM) {
                    // a swim pulled taut can be long: its line sampled every half block, so what follows the route's
                    // ground (layer 2's corridor, the rails) takes in all of it, not only its ends
                    int k = (int) Math.ceil(seg / 0.5);
                    for (int s = 1; s < k; s++) {
                        double t = s / (double) k;
                        g.add(new float[]{(float) (r.x[i - 1] + (r.x[i] - r.x[i - 1]) * t), (float) (r.y[i - 1] + (r.y[i] - r.y[i - 1]) * t),
                            (float) (r.z[i - 1] + (r.z[i] - r.z[i - 1]) * t)});
                    }
                }
                g.add(new float[]{(float) r.x[i], (float) r.y[i], (float) r.z[i]});
                time += seg / 0.2;   // the grid's own moves: about walking pace (ladders and water are slower; see gridCost)
            }
        }
        sweep.record = false;
        // the turns: each bend's share of a 90 degree corner's time (Costs.turn, fitted from layer 2's runs)
        for (int i = 1; i + 1 < r.n && c.turn > 0; i++) {
            double ax = r.x[i] - r.x[i - 1], az = r.z[i] - r.z[i - 1], bx = r.x[i + 1] - r.x[i], bz = r.z[i + 1] - r.z[i];
            double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
            if (la < 1e-6 || lb < 1e-6) continue;
            double cos = Math.max(-1, Math.min(1, (ax * bx + az * bz) / (la * lb)));
            time += c.turn * Math.toDegrees(Math.acos(cos)) / 90.0;
        }
        r.gn = g.size();
        r.gx = new float[r.gn]; r.gy = new float[r.gn]; r.gz = new float[r.gn];
        for (int i = 0; i < r.gn; i++) { r.gx[i] = g.get(i)[0]; r.gy[i] = g.get(i)[1]; r.gz[i] = g.get(i)[2]; }
        r.length = len;
        r.time = time;
    }
}
