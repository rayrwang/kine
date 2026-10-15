package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Wading pulled taut (RW, 2026-10-14: "ok addd the kgiht moves and wding fixes"): across a shallow lake (water at the
 * feet, standing on the bottom) the search had straight moves only (the diagonal and knight's checks refused water at
 * the feet) and the route kept every grid step, a staircase on the axes with a waypoint a block (measured on 3cb9312: at
 * 45 degrees 120 blocks of grid for 84.9 straight, 121 waypoints). Now the graph wades diagonally and by knight's moves
 * over wadeable cells, and the tightening pulls a run of wades taut, every straight wade checked to stay on wadeable
 * ground under the box grown by the margin.
 */
public class WadeTightenTest {
    static final double MARGIN = 0.2;

    /** A lake one deep over stone at 10 (the feet at 11, in water), from the start at the origin. */
    static TestWorld lake() {
        TestWorld w = blocks(-16, -80, 95, 79).floor(10);
        w.fill(-16, 11, -80, 95, 11, 79, WATER);
        return w;
    }

    static Route route(TestWorld w, Costs c, int gx, int gz) {
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, c).run(v.nodeAt(0, 11, 0), gx, gz, 1.0, Long.MAX_VALUE, 2_000_000, false);
        assertTrue(r.found, r.reason);
        return new Tighten(v, c, MARGIN, true).route(r, gx, gz);
    }

    /** Every straight wade against the world's own blocks: under the player's box grown by the margin (less a hair),
     *  every column stone at 10 (the lake's bottom, not a hole), water, air or a slab (a sandbar) at 11 and air at 12
     *  and 13. */
    static void checkWadesAgainstWorld(TestWorld w, Route r) {
        for (int i = 1; i < r.n; i++) {
            if (r.move[i] != Route.WADE) continue;
            double x0 = r.x[i - 1], z0 = r.z[i - 1], x1 = r.x[i], z1 = r.z[i];
            int n = Math.max(1, (int) Math.ceil(Math.hypot(x1 - x0, z1 - z0) / 0.05));
            double out = 0.3 + MARGIN - 0.01;
            for (int k = 0; k <= n; k++) {
                double x = x0 + (x1 - x0) * k / n, z = z0 + (z1 - z0) * k / n;
                for (int bx = (int) Math.floor(x - out); bx <= (int) Math.floor(x + out); bx++)
                    for (int bz = (int) Math.floor(z - out); bz <= (int) Math.floor(z + out); bz++) {
                        String at = "wade leg " + i + " over " + bx + " " + bz + " at " + x + " " + z;
                        assertEquals(STONE, w.get(bx, 10, bz), at);
                        Kind at11 = w.get(bx, 11, bz);
                        assertTrue(at11 == WATER || at11 == Palette.AIR || at11 == SLAB, at);
                        assertTrue(w.get(bx, 12, bz).empty() && w.get(bx, 13, bz).empty(), at);
                    }
            }
        }
    }

    /** The graph wades diagonally and by knight's moves where every cell the box crosses or clips is wadeable: 16
     *  wades out of a floor in open shallow water (4 before), none over a hole three deep beside it, and the grid's way
     *  across a lake the knight's metric's (at most 2.7% over the straight line; on the axes alone it was up to 41%). */
    @Test
    void theGridWadesDiagonallyAndByKnightsMoves() {
        TestWorld w = lake();
        View v = w.view();
        Graph g = new Graph(v, new Costs());
        g.expand(v.nodeAt(0, 11, 0));
        int wades = 0;
        for (int k = 0; k < g.n; k++) if (g.move[k] == Graph.WADE) wades++;
        assertEquals(16, wades, "open shallow water: eight neighbours and eight knight's moves");
        w.fill(1, 8, 0, 1, 10, 0, WATER);   // a hole three deep east of the start
        v = w.view();
        g = new Graph(v, new Costs());
        g.expand(v.nodeAt(0, 11, 0));
        java.util.Set<String> to = new java.util.HashSet<>();
        for (int k = 0; k < g.n; k++) if (g.move[k] == Graph.WADE) to.add(View.nodeX(g.to[k]) + "," + View.nodeZ(g.to[k]));
        for (String gone : new String[]{"1,0", "1,1", "1,-1", "2,1", "2,-1", "1,2", "1,-2"})
            assertFalse(to.contains(gone), "no wade to " + gone + " past the hole");
        assertEquals(9, to.size(), to.toString());
        v = lake().view();
        long start = v.nodeAt(0, 11, 0);
        double worst = 0;
        for (int gz = 0; gz <= 60; gz++) {
            PathSearch.Result r = new PathSearch(v, Costs.layer2()).run(start, 60, gz, 1.0, Long.MAX_VALUE, 2_000_000, false);
            assertTrue(r.found, r.reason);
            double len = 0;
            for (int i = 1; i < r.path.length; i++)
                len += Math.hypot(View.nodeX(r.path[i]) - View.nodeX(r.path[i - 1]), View.nodeZ(r.path[i]) - View.nodeZ(r.path[i - 1]));
            assertEquals(PathSearch.gridLength(60, gz), len, 1e-6, "the shortest way on the grid, to 60," + gz);
            worst = Math.max(worst, len / Math.hypot(60, gz) - 1);
        }
        System.out.printf(Locale.ROOT, "KINEMEASURE wade worst detour %.4f%n", worst);
        assertTrue(worst < 0.028, "worst detour " + worst);
    }

    @Test
    void shallowWaterIsWadedStraight() {
        StringBuilder bad = new StringBuilder();
        for (double deg : new double[]{0, 10, 20, 26.6, 30, 45, 60, -15}) {
            double rad = Math.toRadians(deg);
            int gx = (int) Math.round(70 * Math.cos(rad)), gz = (int) Math.round(70 * Math.sin(rad));
            TestWorld w = lake();
            for (Costs c : new Costs[]{Costs.layer2(), new Costs()}) {
                Route r = route(w, c, gx, gz);
                int wades = 0;
                double routeLat = 0;
                for (int i = 0; i < r.n; i++) {
                    if (r.move[i] == Route.WADE) wades++;
                    routeLat = Math.max(routeLat, Math.abs(SwimTightenTest.lateral(r.x[i], r.z[i], 0.5, 0.5, gx + 0.5, gz + 0.5)));
                }
                double maxGap = 0;
                for (int k = 1; k < r.gn; k++) maxGap = Math.max(maxGap, Math.hypot(r.gx[k] - r.gx[k - 1], r.gz[k] - r.gz[k - 1]));
                String sim = "";
                if (c.wade == Costs.layer2().wade) {
                    Layer2Sim.Outcome o = new Layer2Sim(w).walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, gx, gz, 1500);
                    double trackLat = 0;
                    for (double[] q : o.path()) trackLat = Math.max(trackLat, Math.abs(SwimTightenTest.lateral(q[0], q[2], 0.5, 0.5, gx + 0.5, gz + 0.5)));
                    sim = String.format(Locale.ROOT, " | layer2 arrived=%b ticks=%d trackLateral=%.2f", o.arrived(), o.ticks(), trackLat);
                    if (!o.arrived()) bad.append(deg).append(" not arrived; ");
                    // layer 2's own track: on 3cb9312 up to 4.9 off the line (10 degrees), now up to about 2
                    if (trackLat > 2.5) bad.append(deg).append(" track strays ").append(trackLat).append("; ");
                }
                System.out.printf(Locale.ROOT, "KINEMEASURE wade deg=%.1f costs=%s waypoints=%d wadeLegs=%d route %.2f grid %.2f straight %.2f routeLateral=%.2f groundGap=%.2f%s%n",
                    deg, c.wade == Costs.layer2().wade ? "layer2" : "baritone", r.n, wades, r.length, r.gridLength, r.straightLength, routeLat, maxGap, sim);
                checkWadesAgainstWorld(w, r);
                if (r.n > 4) bad.append(deg).append(" ").append(r.n).append(" waypoints; ");
                if (r.gridLength > r.straightLength * 1.028) bad.append(deg).append(" grid ").append(r.gridLength).append("; ");
                if (routeLat > 1.0) bad.append(deg).append(" route strays ").append(routeLat).append("; ");
                if (maxGap > 0.75) bad.append(deg).append(" ground gap ").append(maxGap).append("; ");
            }
        }
        assertEquals("", bad.toString());
    }

    /** Holes in the lake's bottom (water three deep), a pillar and a sandbar a slab high: every straight wade keeps the
     *  box grown by the margin on the bottom (round the holes and the pillar; the sandbar is wadeable ground, a step
     *  up), and the route is never longer than the grid's way. */
    @Test
    void holesAndPillarsAreWadedRound() {
        for (double deg : new double[]{0, 20, 45}) {
            double rad = Math.toRadians(deg);
            int gx = (int) Math.round(70 * Math.cos(rad)), gz = (int) Math.round(70 * Math.sin(rad));
            TestWorld w = lake();
            int cx = gx / 2, cz = gz / 2;
            w.fill(cx - 4, 8, cz - 3, cx + 4, 10, cz + 3, WATER);          // a hole three deep across the line
            w.fill(cx - 20, 11, cz - 20, cx - 18, 13, cz - 18, STONE);    // a pillar
            w.fill(cx + 12, 11, cz + 6, cx + 16, 11, cz + 10, SLAB);      // a sandbar half a block up
            Route r = route(w, Costs.layer2(), gx, gz);
            System.out.printf(Locale.ROOT, "KINEMEASURE wade round a hole deg=%.0f waypoints=%d route %.2f grid %.2f straight %.2f moves=%s%n",
                deg, r.n, r.length, r.gridLength, r.straightLength, moves(r));
            for (int i = 1; i < r.n; i++) assertTrue(r.move[i] == Route.WADE || r.move[i] == Route.WALK, "wades and walks: " + moves(r));
            checkWadesAgainstWorld(w, r);
            assertTrue(r.length <= r.gridLength + 1e-6, "no longer than the grid path");
        }
    }

    static String moves(Route r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < r.n; i++) sb.append(r.moveName(i)).append(i + 1 < r.n ? "," : "");
        return sb.toString();
    }
}
