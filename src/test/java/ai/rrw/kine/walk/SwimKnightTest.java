package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Knight's moves for swims (RW, 2026-10-14: "ok addd the kgiht moves"): across open water the search had straight and
 * diagonal swims only, so it priced a swim off those headings up to 8.2% high and from a shore swam off at 0 or 45
 * degrees only. A knight's swim is offered where the player's box passes over open water only: every column of the two
 * by three it spans a swimming floor at the same water top.
 */
public class SwimKnightTest {
    static final long FOREVER = Long.MAX_VALUE;
    /** The water's top (the swimming floors' height): water from 7 to 10 over stone at 6. */
    static final int TOP = 11;

    static TestWorld sea(int x0, int z0, int x1, int z1) {
        TestWorld w = blocks(x0, z0, x1, z1);
        w.fill(x0, 6, z0, x1, 6, z1, STONE);
        w.fill(x0, 7, z0, x1, 10, z1, WATER);
        return w;
    }

    static double pathLength(PathSearch.Result r) {
        double len = 0;
        for (int i = 1; i < r.path.length; i++)
            len += Math.hypot(View.nodeX(r.path[i]) - View.nodeX(r.path[i - 1]), View.nodeZ(r.path[i]) - View.nodeZ(r.path[i - 1]));
        return len;
    }

    /** The grid's way across open water against the straight line, at every heading from 0 to 45 degrees: at most
     *  2.7% longer (it was 8.2%), with both cost tables (Baritone's, with its penalty a move onto water, and layer 2's). */
    @Test
    void theWorstSwimDetourIsAKnightsNotAnOctiles() {
        View v = sea(-8, -8, 70, 70).view();
        long start = v.nodeAt(0, TOP, 0);
        assertNotEquals(View.NONE, start);
        for (Costs c : new Costs[]{new Costs(), Costs.layer2()}) {
            double worst = 0, worstOctile = 0;
            int knights = 0;
            for (int gz = 0; gz <= 60; gz++) {
                int gx = 60;
                PathSearch.Result r = new PathSearch(v, c).run(start, gx, gz, 1.0, FOREVER, 2_000_000, false);
                assertTrue(r.found, r.reason);
                for (int i = 1; i < r.path.length; i++) {
                    assertEquals(Graph.SWIM, r.moves[i], "all swims");
                    int dx = Math.abs(View.nodeX(r.path[i]) - View.nodeX(r.path[i - 1])), dz = Math.abs(View.nodeZ(r.path[i]) - View.nodeZ(r.path[i - 1]));
                    if (dx + dz == 3) knights++;
                }
                double straight = Math.hypot(gx, gz);
                assertEquals(PathSearch.gridLength(gx, gz), pathLength(r), 1e-6, "the shortest way on the grid, to " + gx + "," + gz);
                worst = Math.max(worst, pathLength(r) / straight - 1);
                worstOctile = Math.max(worstOctile, PathSearch.octileLength(gx, gz) / straight - 1);
            }
            System.out.printf(Locale.ROOT, "KINEMEASURE swim worst detour %.4f (straight and diagonal only %.4f), %d knight's moves, water %.2f penalty %.1f%n",
                worst, worstOctile, knights, c.water, c.waterPenalty);
            assertTrue(worst < 0.028, "with knight's moves: " + worst);
            assertTrue(worstOctile > 0.08, "without: " + worstOctile);
            assertTrue(knights > 0);
        }
    }

    /** A knight's swim needs open water under every column its box passes over: a block standing out of the water
     *  next to the start takes away the straight swim into it, the two diagonals beside it and the four knight's moves
     *  whose two by three holds it. */
    @Test
    void aKnightsSwimKeepsToOpenWater() {
        TestWorld w = sea(-8, -8, 23, 23);
        View v = w.view();
        Graph g = new Graph(v, new Costs());
        g.expand(v.nodeAt(0, TOP, 0));
        int open = 0;
        for (int k = 0; k < g.n; k++) if (g.move[k] == Graph.SWIM) open++;
        assertEquals(16, open, "open water: eight neighbours and eight knight's moves");
        w.fill(1, 6, 0, 1, 12, 0, STONE);   // a pillar east of the start, out of the water
        v = w.view();
        g = new Graph(v, new Costs());
        g.expand(v.nodeAt(0, TOP, 0));
        java.util.Set<String> to = new java.util.HashSet<>();
        for (int k = 0; k < g.n; k++) if (g.move[k] == Graph.SWIM) to.add(View.nodeX(g.to[k]) + "," + View.nodeZ(g.to[k]));
        for (String gone : new String[]{"1,0", "1,1", "1,-1", "2,1", "2,-1", "1,2", "1,-2"})
            assertFalse(to.contains(gone), "no swim to " + gone + " past the pillar");
        for (String kept : new String[]{"-1,0", "0,1", "0,-1", "-1,1", "-1,-1", "-2,1", "-2,-1", "-1,2", "-1,-2"})
            assertTrue(to.contains(kept), "a swim to " + kept);
        assertEquals(9, to.size());
        // a knight's swim between two waters at different tops, or past a cell of another water's top, is none either
        TestWorld w2 = sea(-8, -8, 23, 23);
        w2.set(0, 10, 1, Palette.AIR);   // the column north of (0, 2): its water a block lower
        View v2 = w2.view();
        Graph g2 = new Graph(v2, new Costs());
        g2.expand(v2.nodeAt(0, TOP, 0));
        for (int k = 0; k < g2.n; k++)
            if (g2.move[k] == Graph.SWIM) {
                int dx = View.nodeX(g2.to[k]), dz = View.nodeZ(g2.to[k]);
                assertFalse(dx == 1 && dz == 2 || dx == -1 && dz == 2, "no knight's swim over the lower water at 0,1");
            }
    }

    /** The planned time (the ETA's) of a swim with knight's moves still counts the straight line the walk swims, not
     *  the grid's way: TimeProfile scales a run of swims that is a shortest way on the grid (it knew the eight
     *  directions' way only, so a run with knight's moves was left at the grid's length, 1.5% high at 30 degrees). */
    @Test
    void aSwimWithKnightsMovesIsTimedOnTheStraightLine() {
        TestWorld w = EtaPartsTest.seaWorld();
        View v = w.view();
        Costs c = Costs.layer2();
        for (int[] g : new int[][]{{80, 46}, {80, 40}, {80, 20}, {80, 8}}) {
            PathSearch.Result r = EtaPartsTest.sea(w, g[0], g[1]);
            assertTrue(r.found, r.reason);
            Route route = new Tighten(v, c, 0.2, true).route(r, g[0], g[1]);
            TimeProfile p = TimeProfile.of(v, c, true, true, false, r, route, g[0], g[1]);
            double straight = Math.hypot(g[0], g[1]), perBlock = p.total / straight;
            System.out.printf(Locale.ROOT, "KINEMEASURE swim timed to %d,%d: %.1f ticks for %.1f blocks straight, %.3f a block (swim %.2f), grid %.1f%n",
                g[0], g[1], p.total, straight, perBlock, c.water, pathLength(r));
            assertEquals(c.water, perBlock, 0.06, "planned at the swim rate on the straight line, to " + g[0] + "," + g[1]);
        }
    }

    /** The search stays optimal on the graph with knight's swims: against a brute-force shortest path over random
     *  seas with islands and pillars. */
    @Test
    void optimalAtSeaAgainstBruteForce() {
        int compared = 0;
        for (long seed = 1; seed <= 40; seed++) {
            Random r = new Random(seed);
            TestWorld w = sea(0, 0, 31, 31);
            for (int k = 0; k < 40; k++) {
                int x = r.nextInt(32), z = r.nextInt(32), wd = 1 + r.nextInt(3), ln = 1 + r.nextInt(5);
                if (r.nextBoolean()) w.fill(x, 6, z, Math.min(31, x + wd - 1), 11, Math.min(31, z + ln - 1), STONE);
                else w.fill(x, 6, z, Math.min(31, x + ln - 1), 11, Math.min(31, z + wd - 1), STONE);
            }
            int sx = r.nextInt(32), sz = r.nextInt(32), gx = r.nextInt(32), gz = r.nextInt(32);
            w.fill(sx, 7, sz, sx, 10, sz, WATER).fill(sx, 11, sz, sx, 12, sz, Palette.AIR);
            View v = w.view();
            long start = v.nodeAt(sx, TOP, sz);
            assertNotEquals(View.NONE, start);
            PathSearch.Result res = new PathSearch(v, new Costs()).run(start, gx, gz, 1.0, FOREVER, 1_000_000, false);
            double b = SearchTest.brute(v, start, gx, gz);
            if (Double.isInfinite(b)) { assertFalse(res.found, "seed " + seed); continue; }
            assertTrue(res.found, "seed " + seed + ": " + res.reason);
            assertEquals(b, res.cost, 1e-6, "seed " + seed);
            compared++;
        }
        assertTrue(compared >= 25, "compared " + compared);
    }
}
