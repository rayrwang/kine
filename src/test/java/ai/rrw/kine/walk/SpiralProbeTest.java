package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Locale;

/**
 * Helper probe (offline, env-gated by KINE_SPIRAL_PROBE): brute-force sweeps of plain sprint jumps in the movement copy
 * from a take-off box, for the highest point a slime bounce reaches and the best landings. Not an assertion: a tool for
 * the spiral helper's analysis of legs whose loose moves fail their checks.
 *
 * KINE_SPIRAL_PROBE="x0,z0,x1,z1,y,tx,tz" (take-off box corners in block coordinates, the take-off's feet height, and a
 * target column to aim round); prints the top bounce peaks and where the tries end on the ground.
 */
public class SpiralProbeTest {
    @Test
    void probe() throws Exception {
        String p = System.getenv("KINE_SPIRAL_PROBE");
        Assumptions.assumeTrue(p != null, "KINE_SPIRAL_PROBE not set");
        SpiralMapTest.load();
        String[] f = p.split(",");
        double x0 = Double.parseDouble(f[0]), z0 = Double.parseDouble(f[1]), x1 = Double.parseDouble(f[2]), z1 = Double.parseDouble(f[3]);
        double y = Double.parseDouble(f[4]), tx = Double.parseDouble(f[5]) + 0.5, tz = Double.parseDouble(f[6]) + 0.5;
        double minEndY = f.length > 7 ? Double.parseDouble(f[7]) : y - 0.5;
        BlockGrid g = SpiralMapTest.map.grid((int) Math.floor(Math.min(x0, tx)) - 16, (int) Math.floor(y) - 12,
            (int) Math.floor(Math.min(z0, tz)) - 16, (int) Math.ceil(Math.abs(x1 - tx)) + 40, 26, (int) Math.ceil(Math.abs(z1 - tz)) + 40);
        Body body = new Body();
        GroundPhysics ph = new GroundPhysics(g, body);
        java.util.List<String> best = new java.util.ArrayList<>();
        double bestPeak = -1e9;
        String bestPeakWhat = "";
        java.util.Map<String, double[]> endsY = new java.util.HashMap<>();
        java.util.Map<String, String> ends = new java.util.HashMap<>();
        int tries = 0;
        for (double sx = x0 + 0.31; sx <= x1 - 0.31 + 1e-9; sx += 0.1)
            for (double sz = z0 + 0.31; sz <= z1 - 0.31 + 1e-9; sz += 0.1) {
                float base = Recipe.yawTo(sx, sz, tx, tz);
                for (float dy = -40f; dy <= 40f; dy += 2f) {
                    float yaw = base + dy;
                    for (int jumpAt = 0; jumpAt <= 12; jumpAt++)
                        for (int jumpHold = 0; jumpHold <= 1; jumpHold++) {
                            tries++;
                            MoveState s = Loose.standing(sx, y, sz);
                            boolean bounced = false, jumped = false;
                            double peakAfter = -1e9;
                            int t;
                            for (t = 0; t < 120; t++) {
                                int keys = MoveState.FORWARD | MoveState.SPRINT;
                                if (t == jumpAt) keys |= MoveState.JUMP;
                                if (jumpHold == 1 && t > jumpAt) keys |= MoveState.JUMP;
                                double vyBefore = s.vy;
                                boolean wasGround = s.onGround;
                                ph.step(s, keys, yaw);
                                if (t >= jumpAt && !s.onGround) jumped = true;
                                if (jumped && vyBefore < -0.1 && s.vy > 0.1) bounced = true;
                                if (bounced) peakAfter = Math.max(peakAfter, s.y);
                                if (jumped && s.onGround && !bounced && s.y < y - 1.0) {
                                    // landed below without a bounce yet: on slime the next tick bounces
                                }
                                if (bounced && s.onGround && s.vy <= 0.0 && t > jumpAt + 3) break;
                                if (s.y < y - 8) break;
                            }
                            if (bounced && peakAfter > bestPeak) {
                                bestPeak = peakAfter;
                                bestPeakWhat = String.format(Locale.ROOT, "from %.2f,%.2f yaw %.1f jumpAt %d hold %d", sx, sz, yaw, jumpAt, jumpHold);
                            }
                            if (bounced && s.onGround && s.y >= minEndY)
                            {
                                String key = (int) Math.floor(s.x) + "," + Math.round(s.y * 1000) + "," + (int) Math.floor(s.z);
                                if (!ends.containsKey(key)) {
                                    endsY.put(key, new double[]{s.y});
                                    ends.put(key, String.format(Locale.ROOT, "end %.2f,%.3f,%.2f peak %.3f from %.2f,%.2f yaw %.1f j%d h%d",
                                        s.x, s.y, s.z, peakAfter, sx, sz, yaw, jumpAt, jumpHold));
                                }
                            }
                        }
                }
            }
        System.out.printf(Locale.ROOT, "probe: %d tries; best bounce peak %.3f (%s)%n", tries, bestPeak, bestPeakWhat);
        int k = 0;
        java.util.List<String> keys = new java.util.ArrayList<>(ends.keySet());
        keys.sort((a, b) -> Double.compare(endsY.get(b)[0], endsY.get(a)[0]));
        for (String key : keys) {
            System.out.println("probe " + ends.get(key));
            if (++k >= 25) break;
        }
    }

    /** The loose check (LooseCheck.check: quick, then full) of each move in KINE_SPIRAL_MOVES ("tx,th,tz>lx,lh,lz;..."),
     *  from standing at the take-off's centre: the verdict, how long, the recipe's description and where it ends. */
    @Test
    void checkMoves() throws Exception {
        String p = System.getenv("KINE_SPIRAL_MOVES");
        Assumptions.assumeTrue(p != null, "KINE_SPIRAL_MOVES not set");
        SpiralMapTest.load();
        SpiralMapTest.costs();
        Body body = new Body();
        LooseCheck.Grids grids = (x, y, z, r, down, up) -> SpiralMapTest.map.grid(x - r, y - down, z - r, 2 * r + 1, down + up + 1, 2 * r + 1);
        for (String mv : p.split(";")) {
            String[] ab = mv.split(">");
            String[] a = ab[0].split(","), b = ab[1].split(",");
            int tx = Integer.parseInt(a[0].trim()), tz = Integer.parseInt(a[2].trim()), lx = Integer.parseInt(b[0].trim()), lz = Integer.parseInt(b[2].trim());
            double th = Double.parseDouble(a[1]), lh = Double.parseDouble(b[1]);
            for (boolean quick : new boolean[]{true, false}) {
                LooseCheck.Verdict v = LooseCheck.check(grids, body, null, Float.NaN, tx, th, tz, lx, lh, lz, true, () -> false, quick, true);
                System.out.printf(Locale.ROOT, "move %s %s: %s in %.0f ms%s%n", mv, quick ? "quick" : "full", v.ok() ? "WAY" : "no way", v.nanos() / 1e6,
                    v.ok() ? String.format(Locale.ROOT, " (%s; ends %.2f,%.3f,%.2f)", v.recipe().how, v.end().x, v.end().y, v.end().z) : "");
                if (v.ok()) break;
            }
        }
    }
}
