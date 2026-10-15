package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Search and tightening times on this machine over a made-up loaded area the size of render distance 12 (25 by 25
 * chunks): open ground with 6% of columns walled 2 high and some one-block steps, goals 100 to 300 blocks away, each
 * weight of the planner's passes. Prints KINEMEASURE lines; asserts only that the paths are found and cost what the
 * weights promise.
 */
public class SearchBench {
    @Test
    void timesOverARenderDistanceOfTerrain() {
        TestWorld w = blocks(-200, -200, 199, 199).floor(10);
        Random r = new Random(5);
        for (int x = -200; x < 200; x++)
            for (int z = -200; z < 200; z++) {
                double p = r.nextDouble();
                if (p < 0.06) w.fill(x, 11, z, x, 12, z, STONE);
                else if (p < 0.09) w.set(x, 11, z, STONE);
            }
        w.fill(-1, 11, -1, 1, 12, 1, Palette.AIR);
        long t0 = System.nanoTime();
        View v = w.view();
        System.out.printf("KINEMEASURE bench table: %d chunks in %.0f ms%n", v.chunkCount(), (System.nanoTime() - t0) / 1e6);
        long start = v.nodeAt(0, 11, 0);
        for (int[] g : new int[][]{{70, 70}, {100, 140}, {180, -180}}) {
            w.fill(g[0], 11, g[1], g[0], 12, g[1], Palette.AIR);
        }
        v = w.view();
        Costs c = new Costs();
        for (int[] g : new int[][]{{70, 70}, {100, 140}, {180, -180}}) {
            for (double wt : Planner.WEIGHTS) {
                PathSearch ps = new PathSearch(v, c);
                PathSearch.Result res = ps.run(start, g[0], g[1], wt, Long.MAX_VALUE, 3_000_000, true);
                assertTrue(res.found, res.reason);
                long t1 = System.nanoTime();
                Route route = new Tighten(v, c, 0.2, true).route(res, g[0], g[1]);
                long tt = System.nanoTime() - t1;
                System.out.printf(java.util.Locale.ROOT,
                    "KINEMEASURE bench goal %d,%d w=%.2f: search %.1f ms, %d expanded, %d seen, %d sweeps, cost %.1f; tighten %.1f ms (%d checks), grid %.1f, route %.1f, straight %.1f%n",
                    g[0], g[1], wt, res.nanos / 1e6, res.expanded, res.generated, res.sweeps, res.cost, tt / 1e6, route.checks,
                    route.gridLength, route.length, Math.hypot(g[0], g[1]));
            }
        }
    }

    /** What Parkour's gap jumps cost the search: the same kind of terrain with trenches (1 to 4 wide, 8 deep) and pits
     *  cut in it, searched with the jumps off and on. */
    @Test
    void timesWithParkour() {
        TestWorld w = blocks(-200, -200, 199, 199).floor(10);
        Random r = new Random(7);
        for (int x = -200; x < 200; x++)
            for (int z = -200; z < 200; z++) {
                double p = r.nextDouble();
                if (p < 0.04) w.fill(x, 11, z, x, 12, z, STONE);
                else if (p < 0.07) w.fill(x, 3, z, x, 10, z, Palette.AIR);   // a pit
            }
        for (int k = 0; k < 60; k++) {
            int x0 = r.nextInt(380) - 190, z0 = r.nextInt(380) - 190, wd = 1 + r.nextInt(4), len = 10 + r.nextInt(30);
            if (r.nextBoolean()) w.fill(x0, 3, z0, x0 + wd - 1, 10, z0 + len, Palette.AIR);
            else w.fill(x0, 3, z0, x0 + len, 10, z0 + wd - 1, Palette.AIR);
        }
        w.fill(-1, 10, -1, 1, 12, 1, STONE).fill(-1, 11, -1, 1, 12, 1, Palette.AIR);
        int[][] goals = {{70, 70}, {100, 140}, {180, -180}};
        for (int[] g : goals) w.fill(g[0], 10, g[1], g[0], 10, g[1], STONE).fill(g[0], 11, g[1], g[0], 12, g[1], Palette.AIR);
        View v = w.view();
        long start = v.nodeAt(0, 11, 0);
        Costs c = Costs.layer2();
        for (int[] g : goals)
            for (boolean gaps : new boolean[]{false, true}) {
                PathSearch.Result res = new PathSearch(v, c).gaps(gaps).run(start, g[0], g[1], 1.0, Long.MAX_VALUE, 3_000_000, false);
                int jumps = 0;
                for (int i = 1; i < res.path.length; i++) if (res.moves[i] == Graph.GAP) jumps++;
                System.out.printf(java.util.Locale.ROOT, "KINEMEASURE bench parkour %s goal %d,%d: search %.1f ms, %d expanded, cost %.1f, found %b, %d jumps%n",
                    gaps ? "on " : "off", g[0], g[1], res.nanos / 1e6, res.expanded, res.cost, res.found, jumps);
            }
    }

    /** What knight's moves for swims cost the search (2026-10-14): a sea the size of render distance 12, 3 deep, with
     *  islands (4% of 8 by 8 cells), goals 100 to 300 blocks away at 0, 25 and 45 degrees, and a sea at render
     *  distance 4 (9 by 9 chunks) with goals 448 and 320 past it (the ocean scenes' oceanFar and oceanDiag), each
     *  weight of the planner's passes, layer 2's costs; the best of three runs. */
    @Test
    void timesAtSea() {
        for (boolean near : new boolean[]{true, false}) {
            int half = near ? 200 : 80;
            TestWorld w = blocks(-half, -half, half - 1, half - 1);
            w.fill(-half, 6, -half, half - 1, 6, half - 1, STONE).fill(-half, 7, -half, half - 1, 10, half - 1, WATER);
            Random r = new Random(11);
            for (int cx = -half; cx < half; cx += 8)
                for (int cz = -half; cz < half; cz += 8)
                    if (r.nextDouble() < 0.04 && Math.abs(cx) + Math.abs(cz) > 16) w.fill(cx, 6, cz, cx + 7, 12, cz + 7, STONE);
            int[][] goals = near ? new int[][]{{100, 0}, {180, 84}, {150, 150}, {-170, 120}} : new int[][]{{448, 209}, {320, 320}};
            for (int[] g : goals) if (near) w.fill(g[0] - 1, 7, g[1] - 1, g[0] + 1, 12, g[1] + 1, WATER).fill(g[0] - 1, 11, g[1] - 1, g[0] + 1, 12, g[1] + 1, Palette.AIR);
            View v = w.view();
            long start = v.nodeAt(0, 11, 0);
            assertNotEquals(View.NONE, start);
            Costs c = Costs.layer2();
            for (int[] g : goals) {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format(java.util.Locale.ROOT, "KINEMEASURE bench sea %s goal %d,%d (%.0f blocks):", near ? "rd12" : "rd4 ", g[0], g[1], Math.hypot(g[0], g[1])));
                for (double wt : Planner.WEIGHTS) {
                    PathSearch.Result best = null;
                    for (int k = 0; k < 3; k++) {
                        PathSearch.Result res = new PathSearch(v, c).run(start, g[0], g[1], wt, Long.MAX_VALUE, 3_000_000, false);
                        if (best == null || res.nanos < best.nanos) best = res;
                    }
                    assertTrue(best.found, best.reason);
                    double len = 0;
                    for (int i = 1; i < best.path.length; i++)
                        len += Math.hypot(View.nodeX(best.path[i]) - View.nodeX(best.path[i - 1]), View.nodeZ(best.path[i]) - View.nodeZ(best.path[i - 1]));
                    sb.append(String.format(java.util.Locale.ROOT, " w=%.2f %.1f ms %d expanded cost %.0f grid %.1f%s;", wt, best.nanos / 1e6,
                        best.expanded, best.viaExit ? best.totalCost : best.cost, len, best.viaExit ? " viaExit" : ""));
                }
                System.out.println(sb);
            }
        }
    }

    @Test
    void timesByDensityAndDistance() {
        for (double density : new double[]{0.0, 0.06, 0.15}) {
            TestWorld w = blocks(-200, -200, 199, 199).floor(10);
            Random r = new Random(17);
            for (int x = -200; x < 200; x++)
                for (int z = -200; z < 200; z++)
                    if (r.nextDouble() < density && Math.abs(x) + Math.abs(z) > 2) w.fill(x, 11, z, x, 12, z, STONE);
            int[][] goals = {{30, 40}, {80, 110}, {150, -150}};
            for (int[] g : goals) w.fill(g[0], 11, g[1], g[0], 12, g[1], Palette.AIR);
            View v = w.view();
            long start = v.nodeAt(0, 11, 0);
            Costs c = new Costs();
            for (int[] g : goals) {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format(java.util.Locale.ROOT, "KINEMEASURE bench density %.2f goal %d,%d (%.0f blocks):", density, g[0], g[1], Math.hypot(g[0], g[1])));
                for (double wt : Planner.WEIGHTS) {
                    PathSearch.Result res = new PathSearch(v, c).run(start, g[0], g[1], wt, Long.MAX_VALUE, 3_000_000, false);
                    if (!res.found) { sb.append(" w=").append(wt).append(" no path"); continue; }
                    sb.append(String.format(java.util.Locale.ROOT, " w=%.2f %.1f ms %d expanded cost %.0f;", wt, res.nanos / 1e6, res.expanded, res.cost));
                }
                System.out.println(sb);
            }
        }
    }
}
