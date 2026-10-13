package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RW's two route bugs on open ground (2026-10-12): toward a goal past the loaded area the route went along an axis
 * before turning toward the goal ("rather than a straight line drawn from teh curnet location to the target"), and
 * within the loaded area layer 2 veered off layer 1's straight route ("veers liek 10 degrees to the letr"). Goals in
 * many directions: layer 1's way out must leave where the straight line crosses the loaded edge, and layer 2's walked
 * track must keep to the straight line.
 */
public class RouteStraightTest {
    static double lateral(double x, double z, double ax, double az, double bx, double bz) {
        double lx = bx - ax, lz = bz - az, len = Math.hypot(lx, lz);
        return ((x - ax) * lz - (z - az) * lx) / len;
    }

    @Test
    void theWayOutIsWhereTheStraightLineLeaves() {
        for (double deg : new double[]{0, 5, 10, 20, 30, 45, 60, 80, -10, -30}) {
            TestWorld w = TestWorld.blocks(-48, -48, 47, 47).floor(10);   // the loaded area: 96 by 96 round the start
            double rad = Math.toRadians(deg);
            int gx = (int) Math.round(1000 * Math.cos(rad)), gz = (int) Math.round(1000 * Math.sin(rad));
            View v = w.view();
            PathSearch.Result r = new PathSearch(v, Costs.layer2()).gaps(true).run(v.nodeAt(0, 11, 0), gx, gz, 1.0, Long.MAX_VALUE, 2_000_000, false);
            Route route = new Tighten(v, Costs.layer2(), 0.2, true).route(r, gx, gz);
            double lat = lateral(route.x[route.n - 1], route.z[route.n - 1], 0.5, 0.5, gx + 0.5, gz + 0.5);
            System.out.printf(Locale.ROOT, "out deg=%.0f route ends %.1f,%.1f lateral %.2f%n", deg, route.x[route.n - 1], route.z[route.n - 1], lat);
            assertTrue(route.viaExit, "past the loaded area");
            assertTrue(Math.abs(lat) < 0.75, deg + " degrees: the way out is " + lat + " off the straight line");
        }
    }

    /** RW's third bug's setting (2026-10-12, "its siwmign along fine, that after liea minute it says stuck"): a sea out
     *  to the loaded edge every way. With the way on past the edge charged as a sprint, the shortest swim to the edge
     *  (along an axis) made the cheapest way out, up to 26 blocks off the straight line; charged at the swimming rate
     *  and scored with the eight-neighbour grid's excess taken off, the way out is where the straight line leaves. */
    @Test
    void theWayOutOfASeaIsWhereTheStraightLineLeaves() {
        int top = 30;
        TestWorld w = TestWorld.blocks(-48, -48, 47, 47);
        w.fill(-48, top - 4, -48, 47, top - 4, 47, TestWorld.STONE);
        w.fill(-48, top - 3, -48, 47, top, 47, TestWorld.WATER);
        w.fill(-1, top - 3, -1, 1, top, 1, TestWorld.STONE);   // a rock to start from
        View v = w.view();
        for (double deg : new double[]{0, 10, 20, 30, 45, 60, -30}) {
            double rad = Math.toRadians(deg);
            int gx = (int) Math.round(1000 * Math.cos(rad)), gz = (int) Math.round(1000 * Math.sin(rad));
            PathSearch.Result r = new PathSearch(v, Costs.layer2()).gaps(true).run(v.nodeAt(0, top + 1, 0), gx, gz, 1.0, Long.MAX_VALUE, 2_000_000, false);
            Route route = new Tighten(v, Costs.layer2(), 0.2, true).route(r, gx, gz);
            double lat = lateral(route.x[route.n - 1], route.z[route.n - 1], 0.5, 0.5, gx + 0.5, gz + 0.5);
            System.out.printf(Locale.ROOT, "sea deg=%.0f route ends %.1f,%.1f lateral %.2f expanded %d%n", deg, route.x[route.n - 1], route.z[route.n - 1], lat, r.expanded);
            assertTrue(route.viaExit, "past the loaded area");
            assertTrue(Math.abs(lat) < 1.5, deg + " degrees: the way out of the sea is " + lat + " off the straight line");
        }
    }

    /** From a coast across a sea, the goal past the loaded area (the lockstep sea scene, measured 2026-10-12): with the
     *  way on charged at the rate of the floor each way out leaves from, the land along the coast looked cheaper and
     *  the walk went round the sea (1176 blocks walked for 506 across); with one rate for every way out, taken from
     *  the floors on the straight line, the way out is in the sea where the straight line leaves. */
    @Test
    void fromACoastTheWayOutIsAcrossTheSea() {
        int top = 30;
        TestWorld w = TestWorld.blocks(-96, -96, 95, 95);
        w.fill(-96, top - 4, -96, 95, top - 4, 95, TestWorld.STONE);
        w.fill(-96, top - 3, -96, -3, top, 95, TestWorld.STONE);      // the land, west
        w.fill(-2, top - 3, -96, 95, top, 95, TestWorld.WATER);       // the sea, east, past the loaded area
        View v = w.view();
        // (at 45 degrees and more the way goes north along the coast's land first, then swims: cheaper by the costs, as
        // land is quicker than the sea, and not the bug)
        for (double deg : new double[]{0, 15, 23, -23}) {
            double rad = Math.toRadians(deg);
            int gx = (int) Math.round(1000 * Math.cos(rad)), gz = (int) Math.round(1000 * Math.sin(rad));
            PathSearch ps = new PathSearch(v, Costs.layer2()).gaps(true);
            PathSearch.Result r = ps.run(v.nodeAt(-8, top + 1, 0), gx, gz, 1.0, Long.MAX_VALUE, 4_000_000, false);
            Route route = new Tighten(v, Costs.layer2(), 0.2, true).route(r, gx, gz);
            double ex = route.x[route.n - 1], ez = route.z[route.n - 1];
            double lat = lateral(ex, ez, -7.5, 0.5, gx + 0.5, gz + 0.5);
            System.out.printf(Locale.ROOT, "coast deg=%.0f route ends %.1f,%.1f lateral %.2f beyondRate %.2f%n", deg, ex, ez, lat,
                ps.beyondRate(v.nodeAt(-8, top + 1, 0), gx, gz));
            assertTrue(route.viaExit, "past the loaded area");
            assertTrue(ex > 0, deg + " degrees: the way out is on the coast's land at " + ex + "," + ez);
            assertTrue(Math.abs(lat) < 1.5, deg + " degrees: the way out is " + lat + " off the straight line");
        }
    }

    /** The planner's quick passes (weights 2.0 and 1.27, published first) left the loaded area at the grid's headings
     *  (goals at 5 to 20 degrees left at 26 to 27, an 80 degree goal at 64; up to 18.8 blocks off), and only the
     *  weight 1.0 pass went straight (the main session's probe and RW's flashing stray route, 2026-10-13). Every pass's
     *  way out must be where the straight line leaves. */
    @Test
    void everyPassLeavesWhereTheStraightLineDoes() {
        TestWorld w = TestWorld.blocks(-48, -48, 47, 47).floor(10);
        View v = w.view();
        double worst = 0;
        for (double weight : Planner.WEIGHTS)
            for (double deg : new double[]{0, 5, 10, 20, 30, 45, 60, 80, -10, -30}) {
                double rad = Math.toRadians(deg);
                int gx = (int) Math.round(1000 * Math.cos(rad)), gz = (int) Math.round(1000 * Math.sin(rad));
                PathSearch.Result r = new PathSearch(v, Costs.layer2()).gaps(true).run(v.nodeAt(0, 11, 0), gx, gz, weight, Long.MAX_VALUE, 2_000_000, false);
                Route route = new Tighten(v, Costs.layer2(), 0.2, true).route(r, gx, gz);
                double lat = lateral(route.x[route.n - 1], route.z[route.n - 1], 0.5, 0.5, gx + 0.5, gz + 0.5);
                double heading = Math.toDegrees(Math.atan2(route.z[route.n - 1] - 0.5, route.x[route.n - 1] - 0.5));
                System.out.printf(Locale.ROOT, "pass w=%.2f deg=%.0f leaves at heading %.1f lateral %.2f expanded %d%n", weight, deg, heading, lat, r.expanded);
                worst = Math.max(worst, Math.abs(lat));
            }
        assertTrue(worst < 1.5, "a pass leaves " + worst + " off the straight line");
    }

    @Test
    void layer2KeepsToTheStraightLine() {
        for (double deg : new double[]{0, 5, 10, 20, 30, 45, 60, 80, -10}) {
            TestWorld w = TestWorld.blocks(-16, -80, 95, 79).floor(10);
            double rad = Math.toRadians(deg);
            int gx = (int) Math.round(70 * Math.cos(rad)), gz = (int) Math.round(70 * Math.sin(rad));
            Layer2Sim sim = new Layer2Sim(w);
            Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, gx, gz, 600);
            assertTrue(o.arrived(), o.why());
            double max = 0;
            for (double[] q : o.path()) max = Math.max(max, Math.abs(lateral(q[0], q[2], 0.5, 0.5, gx + 0.5, gz + 0.5)));
            System.out.printf(Locale.ROOT, "track deg=%.0f ticks=%d maxLateral=%.2f%n", deg, o.ticks(), max);
            assertTrue(max < 1.0, deg + " degrees: the track strays " + max + " from the straight line");
        }
    }

    /** The goal's chunk loaded but cut off from the start's by unloaded ones (a chunk left over after a teleport): the
     *  search had found "every reachable place searched"; it heads out past the loaded edge toward it instead. */
    /** The 60 degree stray (lockstep, 2026-10-13): the goal's chunks known from an earlier walk, joined to the
     *  player's by known ground but not on the straight line, so the route bent through the known ground (4.9 blocks
     *  off the line) and was walked to the end. Such a route is marked, and the walk refreshes it every 5 s; once the
     *  gap is loaded the route is straight. */
    @Test
    void aRouteRoundGroundNotLoadedIsMarked() {
        TestWorld full = TestWorld.blocks(-16, -16, 127, 223).floor(10);
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ChunkFloors> m = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        for (int cx = full.cx0; cx <= full.cx1; cx++)
            for (int cz = full.cz0; cz <= full.cz1; cz++) {
                // the player's chunks (x < 64, z < 128) and the goal's (x >= 80, z >= 112), joined by one chunk
                // (x 64..79, z 112..127); the straight line crosses x 64..79 at z 128..160, not loaded
                boolean mine = cx < 4 && cz < 8, goals = cx >= 5 && cz >= 7, join = cx == 4 && cz == 7;
                if (mine || goals || join) m.put(View.key(cx, cz), full.chunk(cx, cz));
            }
        View part = new View(m), all = full.view();
        int gx = 100, gz = 200;
        for (View v : new View[]{part, all}) {
            PathSearch.Result r = new PathSearch(v, Costs.layer2()).gaps(true).run(v.nodeAt(0, 11, 0), gx, gz, 1.0, Long.MAX_VALUE, 2_000_000, false);
            assertTrue(r.found && !r.viaExit, "the goal is reached");
            Route route = new Tighten(v, Costs.layer2(), 0.2, true).route(r, gx, gz);
            double worst = 0;
            for (int i = 0; i < route.n; i++) worst = Math.max(worst, Math.abs(lateral(route.x[i], route.z[i], 0.5, 0.5, gx + 0.5, gz + 0.5)));
            boolean blind = Planner.crossesUnknown(v, route.x[0], route.z[0], gx + 0.5, gz + 0.5);
            System.out.printf(Locale.ROOT, "gap %s: route n=%d worst lateral %.2f crossesUnknown=%b%n", v == part ? "not loaded" : "loaded", route.n, worst, blind);
            if (v == part) assertTrue(blind && worst > 1.0, "bent round the gap, and marked");
            else assertTrue(!blind && worst < 0.75, "straight once loaded, and not marked");
        }
    }

    @Test
    void aCutOffGoalChunkIsHeadedFor() {
        TestWorld near = TestWorld.blocks(-48, -48, 47, 47).floor(10);
        TestWorld far = TestWorld.blocks(192, 96, 207, 111).floor(10);
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ChunkFloors> m = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        for (int cx = near.cx0; cx <= near.cx1; cx++) for (int cz = near.cz0; cz <= near.cz1; cz++) m.put(View.key(cx, cz), near.chunk(cx, cz));
        m.put(View.key(12, 6), far.chunk(12, 6));
        View v = new View(m);
        PathSearch ps = new PathSearch(v, Costs.layer2());
        long start = v.nodeAt(0, 11, 0);
        assertTrue(ps.goalCutOff(start, 200, 100));
        PathSearch.Result r = ps.run(start, 200, 100, 1.0, Long.MAX_VALUE, 2_000_000, false);
        assertTrue(r.exhausted, "without: every reachable place searched");
        PathSearch.Result r2 = ps.exitAlways(true).run(start, 200, 100, 1.0, Long.MAX_VALUE, 2_000_000, false);
        assertTrue(r2.found && r2.viaExit, "with: out past the edge");
    }
}
