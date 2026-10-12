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
