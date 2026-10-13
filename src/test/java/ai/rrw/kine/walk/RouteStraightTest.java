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
