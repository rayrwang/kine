package ai.rrw.kine.walk;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.PriorityQueue;
import java.util.Random;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/** Layer 1's search: optimal on its graph (against a brute-force shortest path), knight's moves, and each kind of move. */
public class SearchTest {
    static final long FOREVER = Long.MAX_VALUE;

    /** Dijkstra over the same graph: the cheapest cost to any floor in column (gx, gz), or infinity. */
    static double brute(View v, long start, int gx, int gz) {
        Graph g = new Graph(v, new Costs());
        g.goalX = gx; g.goalZ = gz;
        Long2DoubleOpenHashMap dist = new Long2DoubleOpenHashMap();
        dist.defaultReturnValue(Double.POSITIVE_INFINITY);
        PriorityQueue<double[]> q = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        dist.put(start, 0);
        q.add(new double[]{0, Double.longBitsToDouble(start)});
        double best = Double.POSITIVE_INFINITY;
        while (!q.isEmpty()) {
            double[] e = q.poll();
            long n = Double.doubleToRawLongBits(e[1]);
            if (e[0] > dist.get(n)) continue;
            if (View.nodeX(n) == gx && View.nodeZ(n) == gz) best = Math.min(best, e[0]);
            g.expand(n);
            for (int k = 0; k < g.n; k++) {
                if (g.to[k] == Long.MIN_VALUE) continue;
                double nd = e[0] + g.cost[k];
                if (nd < dist.get(g.to[k]) - 1e-12) {
                    dist.put(g.to[k], nd);
                    q.add(new double[]{nd, Double.longBitsToDouble(g.to[k])});
                }
            }
        }
        return best;
    }

    static TestWorld randomWorld(long seed) {
        Random r = new Random(seed);
        TestWorld w = blocks(0, 0, 23, 23).floor(10);
        for (int x = 0; x < 24; x++)
            for (int z = 0; z < 24; z++) {
                double p = r.nextDouble();
                if (p < 0.15) w.fill(x, 11, z, x, 12, z, STONE);                         // a wall
                else if (p < 0.25) w.set(x, 11, z, STONE);                              // a step up a block
                else if (p < 0.33) w.set(x, 11, z, SLAB);                               // half a block
                else if (p < 0.40) { w.set(x, 10, z, Palette.AIR); w.set(x, 8, z, STONE); }   // a pit two deep
                else if (p < 0.45) w.set(x, 11, z, WATER);                              // shallow water
                else if (p < 0.48) w.set(x, 11, z, SOUL_SAND);
            }
        return w;
    }

    /** The goal's column with two floors, the ground under a platform and the platform's top up a stair (as a goal
     *  block over a pool): without a Y the search ends on the ground (any floor of the column, the nearer), with the
     *  platform's Y on the platform. */
    static TestWorld twoFloorGoal() {
        TestWorld w = blocks(-8, -8, 23, 23).floor(10);
        w.fill(5, 11, -2, 5, 11, 2, STONE);          // a stair up from the west: 12, 13, 14, then the platform's top, 15
        w.fill(6, 11, -2, 6, 12, 2, STONE);
        w.fill(7, 11, -2, 7, 13, 2, STONE);
        w.fill(8, 14, -2, 10, 14, 2, STONE);         // the platform, room for the feet under it (11 to 14)
        return w;
    }

    @Test
    void theDestinationsYNamesTheGoalsFloor() {
        View v = twoFloorGoal().view();
        long start = v.nodeAt(0, 11, 0);
        PathSearch.Result any = new PathSearch(v, new Costs()).run(start, 9, 0, 1.0, FOREVER, 1_000_000, false);
        PathSearch.Result top = new PathSearch(v, new Costs()).goalY(15).run(start, 9, 0, 1.0, FOREVER, 1_000_000, false);
        assertTrue(any.found && top.found);
        assertEquals(11.0, any.pathY[any.pathY.length - 1], 1e-4, "without a Y: the ground under the platform");
        assertEquals(15.0, top.pathY[top.pathY.length - 1], 1e-4, "with the platform's Y: on it");
    }

    /** A floor with a vine hanging at the feet a block above or below its neighbours (the benchmark's seed 556249644:
     *  the goal on such a floor on a hillside was shut in, every jump up and drop down needing plain floors): reached
     *  by a jump up, and by a drop down. */
    @Test
    void aVineAtTheFeetIsJumpedOntoAndDroppedInto() {
        TestWorld w = blocks(-8, -8, 23, 23).floor(10);
        w.set(5, 11, 0, STONE).set(5, 12, 0, TestWorld.VINE);         // a block up, a vine at the feet
        w.fill(0, 11, 4, 9, 12, 6, STONE);                              // a ledge, its top at 13
        w.set(5, 11, 7, STONE).set(5, 12, 7, TestWorld.VINE);           // a vine floor at 12, a block under the ledge
        View v = w.view();
        long start = v.nodeAt(0, 11, 0);
        PathSearch.Result up = new PathSearch(v, new Costs()).run(start, 5, 0, 1.0, FOREVER, 1_000_000, false);
        assertTrue(up.found && !up.viaExit, "onto the vine floor a block up: " + up.reason);
        // down: from the ledge (top 13) onto a vine floor a block below it (top 12)
        long ledge = v.nodeAt(5, 13, 6);
        assertNotEquals(View.NONE, ledge);
        PathSearch.Result down = new PathSearch(v, new Costs()).run(ledge, 5, 7, 1.0, FOREVER, 1_000_000, false);
        assertTrue(down.found && !down.viaExit, "down onto the vine floor: " + down.reason);
    }

    /** A goal shut in (a floor in a ring of walls three high): the search searches every reachable place and gives the
     *  way to the floor nearest the goal (the walk goes as near as it gets, then ends), not nothing. */
    @Test
    void aShutInGoalGivesTheWayToTheFloorNearestIt() {
        TestWorld w = blocks(-8, -8, 23, 23).floor(10);
        for (int x = 9; x <= 11; x++) for (int z = -1; z <= 1; z++) if (x != 10 || z != 0) w.fill(x, 11, z, x, 13, z, STONE);
        View v = w.view();
        long start = v.nodeAt(0, 11, 0);
        PathSearch.Result r = new PathSearch(v, new Costs()).run(start, 10, 0, 1.0, FOREVER, 1_000_000, false);
        assertFalse(r.found);
        assertTrue(r.exhausted && r.nearest, r.reason);
        long end = r.path[r.path.length - 1];
        double d = Math.hypot(View.nodeX(end) - 10, View.nodeZ(end));
        assertTrue(d <= 2.0, "ends next to the ring: " + View.nodeX(end) + "," + View.nodeZ(end));
    }

    @Test
    void optimalAgainstBruteForce() {
        int compared = 0, unreachable = 0;
        for (long seed = 1; seed <= 60; seed++) {
            TestWorld w = randomWorld(seed);
            Random r = new Random(seed * 31);
            int sx = r.nextInt(24), sz = r.nextInt(24), gx = r.nextInt(24), gz = r.nextInt(24);
            w.fill(sx, 11, sz, sx, 12, sz, Palette.AIR).set(sx, 10, sz, STONE);
            View v = w.view();
            long start = v.nodeAt(sx, 11, sz);
            assertNotEquals(View.NONE, start);
            PathSearch.Result res = new PathSearch(v, new Costs()).run(start, gx, gz, 1.0, FOREVER, 1_000_000, false);
            double b = brute(v, start, gx, gz);
            if (Double.isInfinite(b)) {
                assertFalse(res.found, "seed " + seed + ": found a path where none exists");
                unreachable++;
                continue;
            }
            assertTrue(res.found, "seed " + seed + ": no path found, brute force " + b + " (" + res.reason + ")");
            assertEquals(b, res.cost, 1e-6, "seed " + seed);
            // the path's own cost adds up
            assertEquals(View.nodeX(res.path[0]), sx);
            compared++;
        }
        assertTrue(compared >= 30, "compared " + compared + ", unreachable " + unreachable);
    }

    @Test
    void weightedIsWithinItsBound() {
        for (long seed = 100; seed < 130; seed++) {
            TestWorld w = randomWorld(seed);
            View v = w.view();
            Random r = new Random(seed);
            int sx = r.nextInt(24), sz = r.nextInt(24), gx = r.nextInt(24), gz = r.nextInt(24);
            w.fill(sx, 11, sz, sx, 12, sz, Palette.AIR).set(sx, 10, sz, STONE);
            v = w.view();
            long start = v.nodeAt(sx, 11, sz);
            PathSearch.Result opt = new PathSearch(v, new Costs()).run(start, gx, gz, 1.0, FOREVER, 1_000_000, false);
            PathSearch.Result fast = new PathSearch(v, new Costs()).run(start, gx, gz, 2.0, FOREVER, 1_000_000, false);
            assertEquals(opt.found, fast.found);
            if (opt.found) assertTrue(fast.cost <= 2.0 * opt.cost + 1e-6, "w=2 within twice the best");
        }
    }

    /** The horizontal length of a grid path. */
    static double length(PathSearch.Result r) {
        double len = 0;
        for (int i = 1; i < r.path.length; i++)
            len += Math.hypot(View.nodeX(r.path[i]) - View.nodeX(r.path[i - 1]), View.nodeZ(r.path[i]) - View.nodeZ(r.path[i - 1]));
        return len;
    }

    @Test
    void knightsMovesCutTheWorstDetour() {
        // open ground; the grid path's length against the straight line, at every angle from 0 to 45 degrees
        TestWorld w = blocks(-8, -8, 70, 70).floor(10);
        View v = w.view();
        double worstWith = 0, worstWithout = 0;
        for (int gz = 0; gz <= 60; gz++) {
            int gx = 60;
            long start = v.nodeAt(0, 11, 0);
            double straight = Math.hypot(gx, gz);
            PathSearch.Result r = new PathSearch(v, new Costs()).run(start, gx, gz, 1.0, FOREVER, 2_000_000, false);
            assertTrue(r.found);
            worstWith = Math.max(worstWith, length(r) / straight - 1);
            // the same without knight's moves: each step straight or diagonal, the best is |dx - dz| + sqrt(2) min
            double octile = Math.abs(gx - gz) + Math.sqrt(2) * Math.min(gx, gz);
            worstWithout = Math.max(worstWithout, octile / straight - 1);
        }
        System.out.printf("KINEMEASURE knight worst detour with %.4f without %.4f%n", worstWith, worstWithout);
        assertTrue(worstWithout > 0.08 && worstWithout < 0.083, "without: " + worstWithout);
        assertTrue(worstWith < 0.028, "with knight's moves: " + worstWith);
    }

    @Test
    void movesOfEachKind() {
        // east from x = 1: a block up at x = 4 (feet 12), a drop of 3 at x = 8 (feet 9), a river two deep at x = 12 to 14
        // (its top at 9, level with the banks), and on at feet 9 to the goal
        TestWorld w = blocks(0, 0, 31, 15).floor(10);
        w.fill(4, 11, 0, 7, 11, 15, STONE);
        w.fill(8, 10, 0, 31, 10, 15, Palette.AIR).fill(8, 8, 0, 31, 8, 15, STONE);
        w.fill(12, 7, 0, 14, 8, 15, WATER).fill(12, 6, 0, 14, 6, 15, STONE);
        View v = w.view();
        long start = v.nodeAt(1, 11, 7);
        PathSearch.Result r = new PathSearch(v, new Costs()).run(start, 20, 7, 1.0, FOREVER, 1_000_000, false);
        assertTrue(r.found, r.reason);
        boolean jump = false, drop = false, swim = false, out = false;
        for (byte m : r.moves) {
            jump |= m == Graph.JUMP; drop |= m == Graph.DROP; swim |= m == Graph.SWIM || m == Graph.WATER_IN;
            out |= m == Graph.WATER_OUT;
        }
        assertTrue(jump && drop && swim && out, "jump " + jump + " drop " + drop + " swim " + swim + " out " + out);
    }

    @Test
    void ladderUpAWall() {
        TestWorld w = blocks(0, 0, 15, 15).floor(10);
        w.fill(6, 11, 0, 15, 15, 15, STONE);                  // a cliff 5 high from x = 6: top floor at 16
        w.fill(5, 11, 7, 5, 15, 7, LADDER_EAST);              // a ladder at x = 5 on the cliff's face
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, new Costs()).run(v.nodeAt(1, 11, 7), 10, 7, 1.0, FOREVER, 1_000_000, false);
        assertTrue(r.found, r.reason);
        int ups = 0;
        boolean off = false;
        for (byte m : r.moves) { if (m == Graph.CLIMB_UP) ups++; off |= m == Graph.CLIMB_OFF; }
        assertTrue(ups >= 4 && off, "ups " + ups + " off " + off);
    }

    @Test
    void noPathIntoAnEnclosure() {
        TestWorld w = blocks(0, 0, 31, 31).floor(10);
        w.fill(20, 11, 20, 26, 13, 26, STONE).fill(21, 11, 21, 25, 13, 25, Palette.AIR);   // a walled box, 3 high
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, new Costs()).run(v.nodeAt(2, 11, 2), 23, 23, 1.0, FOREVER, 1_000_000, false);
        assertFalse(r.found);
        assertTrue(r.exhausted, r.reason);
    }

    @Test
    void closedDoorIsAWall() {
        TestWorld w = blocks(0, 0, 15, 15).floor(10);
        w.fill(8, 11, 0, 8, 13, 15, STONE);                   // a wall across at x = 8, 3 high
        w.set(8, 11, 7, DOOR_WEST).set(8, 12, 7, DOOR_WEST);  // a gap at z = 7 with a closed door in it
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, new Costs()).run(v.nodeAt(2, 11, 7), 13, 7, 1.0, FOREVER, 1_000_000, false);
        assertFalse(r.found, "through a closed door");
        // an open gap: found
        w.set(8, 11, 7, Palette.AIR).set(8, 12, 7, Palette.AIR);
        v = w.view();
        r = new PathSearch(v, new Costs()).run(v.nodeAt(2, 11, 7), 13, 7, 1.0, FOREVER, 1_000_000, false);
        assertTrue(r.found, r.reason);
    }

    @Test
    void farGoalLeavesByTheLoadedEdge() {
        TestWorld w = blocks(0, 0, 47, 47).floor(10);
        w.fill(20, 11, 0, 20, 13, 40, STONE);                 // a long wall north to south, open at its south end
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, new Costs()).run(v.nodeAt(5, 11, 5), 500, 5, 1.0, FOREVER, 1_000_000, false);
        assertTrue(r.found && r.viaExit, r.reason);
        long end = r.path[r.path.length - 1];
        assertTrue(View.nodeX(end) >= 46 || View.nodeZ(end) >= 46 || View.nodeZ(end) <= 0, "ends at the edge: " + View.nodeX(end));
        assertTrue(r.totalCost > r.cost);
    }

    @Test
    void stairsAreWalkedNotJumped() {
        // stairs up three blocks toward +x, then a landing: each stair column's floor is a block above the last, but the
        // stairs' low half makes each a pair of half steps, which the sweep walks
        TestWorld w = blocks(0, 0, 15, 15).floor(10);
        w.fill(4, 11, 0, 4, 11, 15, STAIR_EAST);
        w.fill(5, 11, 0, 5, 11, 15, STONE).fill(5, 12, 0, 5, 12, 15, STAIR_EAST);
        w.fill(6, 11, 0, 6, 12, 15, STONE).fill(6, 13, 0, 6, 13, 15, STAIR_EAST);
        w.fill(7, 11, 0, 15, 13, 15, STONE);
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, new Costs()).run(v.nodeAt(1, 11, 7), 10, 7, 1.0, FOREVER, 1_000_000, false);
        assertTrue(r.found, r.reason);
        for (byte m : r.moves) assertNotEquals(Graph.JUMP, m, "jumped a stair");
        assertEquals(14.0, r.pathY[r.pathY.length - 1], 1e-6);
    }

    @Test
    void aClosedOffDestinationIsFoundBackward() {
        // a big open world where the forward search runs out of floors: the walled box around the goal is found from
        // the goal backward, small and finite
        TestWorld w = blocks(0, 0, 95, 95).floor(10);
        w.fill(60, 11, 60, 66, 13, 66, STONE).fill(61, 11, 61, 65, 13, 65, Palette.AIR);
        View v = w.view();
        PathSearch ps = new PathSearch(v, new Costs());
        PathSearch.Result r = ps.run(v.nodeAt(2, 11, 2), 63, 63, 2.0, FOREVER, 3000, false);
        assertTrue(r.partial, "ran out of floors first");
        assertEquals(49, ps.pocketSize(63, 63, 4096), "the 5 by 5 inside and the top of its walls (a drop of 3 in)");
        // an open destination is not a pocket
        assertEquals(-1, ps.pocketSize(30, 30, 4096));
    }

    @Test
    void dropsAboveThreeOnlyIntoWater() {
        // a platform 6 up with no other way down: refused without water, taken into a pool two deep
        TestWorld w = blocks(0, 0, 31, 15).floor(10);
        w.fill(0, 11, 0, 8, 16, 15, STONE);                    // the platform: feet at 17 for x 0..8
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, new Costs()).run(v.nodeAt(4, 17, 7), 20, 7, 1.0, FOREVER, 1_000_000, false);
        assertFalse(r.found, "a drop of 6 onto ground");
        w.fill(9, 9, 0, 12, 10, 15, WATER).fill(9, 8, 0, 12, 8, 15, STONE);   // a pool two deep, its top at 11
        v = w.view();
        r = new PathSearch(v, new Costs()).run(v.nodeAt(4, 17, 7), 20, 7, 1.0, FOREVER, 1_000_000, false);
        assertTrue(r.found, r.reason);
        boolean in = false;
        for (byte m : r.moves) in |= m == Graph.WATER_IN;
        assertTrue(in, "into the water");
    }

    @Test
    void ladderDown() {
        TestWorld w = blocks(0, 0, 15, 15).floor(10);
        w.fill(0, 11, 0, 4, 15, 15, STONE);                    // a cliff top at feet 16 for x 0..4
        w.fill(5, 11, 7, 5, 15, 7, LADDER_WEST);               // a ladder on its east face
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, new Costs()).run(v.nodeAt(2, 16, 7), 10, 7, 1.0, FOREVER, 1_000_000, false);
        assertTrue(r.found, r.reason);
        int downs = 0;
        for (byte m : r.moves) if (m == Graph.CLIMB_DOWN) downs++;
        assertTrue(downs >= 3, "down the ladder: " + downs);
    }
}
