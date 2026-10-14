package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 2 closed loop in made-up worlds (Layer2Sim): it arrives, and what it does on the way comes out of the search
 * over the movement copy, nothing scripted: sprint-jumping on open ground, swimming across and climbing out onto a bank
 * level with the water, jumping a gap that layer 1 walks round.
 */
public class Layer2Test {
    static void print(String name, Layer2Sim.Outcome o) {
        System.out.printf("layer2 %s: arrived=%b ticks=%d jumps=%d replans=%d meanMs=%.2f worstMs=%.2f %s%n",
            name, o.arrived(), o.ticks(), o.jumps(), o.replans(), o.meanMs(), o.worstMs(), o.why());
    }

    @Test
    void sprintJumpsOnOpenGround() {
        TestWorld w = TestWorld.blocks(-8, -8, 63, 23).floor(10);
        Layer2Sim sim = new Layer2Sim(w);
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 40, 0, 400);
        print("open", o);
        assertTrue(o.arrived(), o.why());
        // sprinting the 40 blocks from rest takes about 40 / 0.2806 + 6 = 149 ticks; sprint-jumping about 120
        assertTrue(o.ticks() < 140, "ticks " + o.ticks());
        assertTrue(o.jumps() >= 5, "jumps " + o.jumps());
    }

    static void trace(Layer2Sim.Outcome o) {
        StringBuilder sb = new StringBuilder("  trace:");
        for (int i = 0; i < o.path().size(); i += 4) {
            double[] p = o.path().get(i);
            int f = (int) p[3], k = (int) p[4];
            sb.append(String.format(java.util.Locale.ROOT, " %d:%.1f,%.2f%s%s%s", i, p[0], p[1], (f & 1) != 0 ? "w" : "", (f & 2) != 0 ? "S" : "",
                (k & MoveState.JUMP) != 0 ? "j" : ""));
        }
        System.out.println(sb);
    }

    static String moves(PathSearch.Result r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < r.moves.length; i++) if (r.moves[i] != Graph.WALK) sb.append(Graph.NAMES[r.moves[i]]).append('@').append(View.nodeX(r.path[i])).append(',').append(View.nodeZ(r.path[i])).append(' ');
        return sb.toString();
    }

    /** A pool three deep, its top level with the ground both sides: in, across and out onto the far bank. */
    @Test
    void swimsAcrossAndClimbsOutOntoALevelBank() {
        TestWorld w = TestWorld.blocks(-8, -16, 47, 15).floor(10);
        w.fill(5, 8, -16, 14, 10, 15, TestWorld.WATER).fill(5, 7, -16, 14, 7, 15, TestWorld.STONE);
        Layer2Sim sim = new Layer2Sim(w);
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 22, 0, 600);
        print("pool", o);
        trace(o);
        System.out.println("  route moves: " + moves(sim.lastRoute));
        assertTrue(o.arrived(), o.why());
        // it sprint swims (found, not scripted): 87 ticks measured; W and jump along the top would take about 120
        assertTrue(o.path().stream().anyMatch(q -> ((int) q[3] & 2) != 0), "sprint swimming");
        assertTrue(o.ticks() < 105, "ticks " + o.ticks());
    }

    /** Out of a one-deep channel onto a bank level with the water's top (the ground): the jump out of water. */
    @Test
    void wadesOutOfAShallowChannel() {
        TestWorld w = TestWorld.blocks(-8, -16, 47, 15).floor(10);
        w.fill(5, 10, -16, 14, 10, 15, TestWorld.WATER).fill(5, 9, -16, 14, 9, 15, TestWorld.STONE);
        Layer2Sim sim = new Layer2Sim(w);
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 22, 0, 600);
        print("channel", o);
        trace(o);
        System.out.println("  route moves: " + moves(sim.lastRoute));
        assertTrue(o.arrived(), o.why());
        assertTrue(o.ticks() < 140, "ticks " + o.ticks());
    }

    /** A ravine three wide and deep across the way, its ends far to the sides: layer 2 jumps it. */
    @Test
    void jumpsAGap() {
        TestWorld w = TestWorld.blocks(-8, -40, 47, 39).floor(10);
        w.fill(10, 1, -36, 12, 10, 36, Palette.AIR);
        Layer2Sim sim = new Layer2Sim(w);
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 22, 0, 400);
        print("gap", o);
        System.out.println("  route moves: " + moves(sim.lastRoute));
        assertTrue(o.arrived(), o.why());
        assertTrue(o.ticks() < 120, "ticks " + o.ticks());
    }

    /** The same ravine with Parkour off (the default): layer 1 goes round its end and layer 2 never jumps it. */
    @Test
    void parkourOffGoesRoundTheGap() {
        TestWorld w = TestWorld.blocks(-8, -40, 47, 39).floor(10);
        w.fill(10, 1, -36, 12, 10, 36, Palette.AIR);
        Layer2Sim sim = new Layer2Sim(w);
        sim.parkour = false;
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 22, 0, 1200);
        print("gap, parkour off", o);
        System.out.println("  route moves: " + moves(sim.lastRoute));
        assertTrue(o.arrived(), o.why());
        assertFalse(moves(sim.lastRoute).contains("gap"), "no gap move in the route");
        // never over the ravine except round its end (it spans z -36 to 36)
        for (double[] q : o.path())
            if (q[0] >= 10 && q[0] < 13) assertTrue(Math.abs(q[2]) > 36 - 0.31, "over the ravine at " + q[0] + "," + q[2]);
    }

    /**
     * Parkour off, the gap check on a jump up a slope that steps a block at a time, diagonally (the stuck walks of the
     * Baritone benchmark, 2026-10-13: seed 556249644, badlands): from a floor at 65 to the north-east one at 66, over the
     * corner one at 64 (a block below the take-off). Its first tick in the air is over that lower floor; the take-off
     * was taken there (0.42 up), so 64 looked like a gap (more than 1.25 under 65.42) and the landing unsafe: every jump
     * up was, and the walk stood still. Taken where it stood (65), the corner is a step down, not a gap. The same jump
     * over a floor at 63 (two under) still is a gap.
     */
    @Test
    void parkourOffAJumpUpOverALowerCornerIsNoGap() {
        for (int corner : new int[]{64, 63}) {
            TestWorld w = TestWorld.blocks(-8, -8, 23, 23);
            int[][] top = {{65, corner}, {65, 66}};   // [dz][dx]: the start's floor, the corner, the side, the landing
            for (int x = 0; x <= 1; x++) for (int z = 0; z <= 1; z++) w.fill(x, 1, z, x, top[z][x] - 35, z, TestWorld.STONE);
            Layer2Sim sim = new Layer2Sim(w);
            RunModel m = new RunModel(sim.phys, null, 1, 1, 0f, 3.0);
            // the jump's ticks as the copy has them (from the trace): over the corner just off the ground, over the
            // corner's edge at the top of the arc, then down on the landing
            double[][] ticks = {{1.30, 65.42, 0.40, 0}, {1.55, 66.15, 0.75, 0}, {1.40, 66.00, 1.30, 1}};
            double[] g = {65 - 34, Double.NaN, Double.NaN};   // the take-off: where it stood (Layer2: groundY)
            double cost = 0;
            for (double[] q : ticks) {
                MoveState s = Layer2Sim.standing(q[0], q[1] - 34, q[2]);
                s.onGround = q[3] > 0;
                cost += m.gapCheck(s, g);
            }
            if (corner == 64) assertEquals(0.0, cost, "a block under the take-off is no gap");
            else assertEquals(RunModel.UNSAFE, cost, "two under the take-off is a gap");
            // (with the take-off taken at the first tick in the air, 65.42, the corner at 64 counted as a gap: UNSAFE)
        }
    }

    /**
     * Parkour off, a drop down a hillside (the benchmark's seed 369819014: from 88 down to 85, the next ledge at 84 under
     * the arc): it sails over the lower ledge and lands on the one above it. Landing a block over the lowest floor
     * crossed is a slope, not a gap (the landing used to have to be within a step of it: unsafe, and the walk stood at
     * the top); landing more than a jump over it still is a gap.
     */
    @Test
    void parkourOffADropOverALowerLedgeIsNoGap() {
        for (int ledge : new int[]{84, 83}) {
            TestWorld w = TestWorld.blocks(-8, -8, 23, 23);
            int[] top = {88, ledge, 85};   // the take-off, the ledge under the arc, the landing (along x)
            for (int x = 0; x <= 2; x++) w.fill(x, 1, 0, x, top[x] - 61, 0, TestWorld.STONE);
            Layer2Sim sim = new Layer2Sim(w);
            RunModel m = new RunModel(sim.phys, null, 2, 0, 0f, 3.0);
            double[][] ticks = {{1.40, 87.8, 0.5, 0}, {1.90, 86.6, 0.5, 0}, {2.30, 85.0, 0.5, 1}};
            double[] g = {88 - 60, Double.NaN, Double.NaN};
            double cost = 0;
            for (double[] q : ticks) {
                MoveState s = Layer2Sim.standing(q[0], q[1] - 60, q[2]);
                s.onGround = q[3] > 0;
                cost += m.gapCheck(s, g);
            }
            if (ledge == 84) assertEquals(0.0, cost, "a block under the landing is a slope");
            else assertEquals(RunModel.UNSAFE, cost, "two under the landing is a gap");
        }
    }

    /**
     * Parkour off, down a steep slope of drops (the benchmark's seed 369819014: 93, 91, 88, 86, 83, 80, 78 along the
     * route, a step or two across between): the arc down from a ledge passes over the next one below before landing on
     * its own. That lower ledge is on the way (its time left no more than the landing's), so crossing it is no gap; it
     * was, and the walk stood at the top.
     */
    @Test
    void parkourOffGoesDownASteepSlopeOfDrops() {
        TestWorld w = TestWorld.blocks(-16, -16, 31, 31);
        int[][] steps = {{0, 0, 40}, {0, 1, 38}, {0, 2, 35}, {1, 2, 33}, {2, 2, 30}, {2, 3, 27}, {2, 4, 25}, {2, 5, 22}, {3, 5, 20},
            {3, 6, 17}, {3, 7, 15}};   // x, z, floor
        for (int[] st : steps) w.fill(st[0], 1, st[1], st[0], st[2] - 1, st[1], TestWorld.STONE);
        for (int x = -2; x <= 6; x++) for (int z = 8; z <= 12; z++) w.fill(x, 1, z, x, 14, z, TestWorld.STONE);   // the foot, at 15
        Layer2Sim sim = new Layer2Sim(w);
        sim.parkour = false;
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 40, 0.5), 0f, 2, 10, 600);
        print("steep slope of drops, parkour off", o);
        assertTrue(o.arrived(), o.why());
    }

    /**
     * Under a spruce (the benchmark's seed 369819014, d200: the blocks round the stuck place from its trace, heights less
     * 70): the way on is a jump a block up to the north, under the canopy's leaves. Straight up it bumps the leaves and
     * falls back; a jump off to the side makes it. The beam, scoring each first move by where it is 4 ticks on, dropped
     * that jump (half way, in the air under the leaves) for standing, and stood for good.
     */
    @Test
    void jumpsUpUnderATreesLeaves() {
        int[][] floor = {   // [dz + 2][dx + 2] for dx -2..2: the floor's height (0: none, the trunk)
            {100, 100, 99, 99, 98},
            {100, 0, 99, 99, 98},
            {100, 100, 99, 99, 98},
            {100, 100, 100, 99, 103},
            {100, 100, 100, 103, 103}};
        int[][] leaves = {   // the lowest leaves over each column (0: none)
            {102, 101, 102, 102, 0},
            {101, 0, 101, 102, 0},
            {102, 101, 102, 102, 0},
            {102, 102, 102, 0, 0},
            {0, 0, 0, 0, 0}};
        int dy = 70;
        TestWorld w = TestWorld.blocks(-24, -16, 15, 15);
        for (int x = -12; x <= 2; x++)
            for (int z = -2; z <= 2; z++) {
                boolean in = x >= -2;
                int f = in ? floor[z + 2][x + 2] : 100, lv = in ? leaves[z + 2][x + 2] : 0;
                if (f > 0) w.fill(x, 1, z, x, f - 1 - dy, z, TestWorld.STONE);
                else w.fill(x, 1, z, x, 101 - dy, z, TestWorld.STONE);   // the trunk, to the leaves
                if (lv > 0) w.fill(x, lv - dy, z, x, 104 - dy, z, TestWorld.STONE);
            }
        Layer2Sim sim = new Layer2Sim(w);
        sim.parkour = false;
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.498, 99 - dy, 0.7), 0f, -10, 1, 600);
        print("up under a tree's leaves", o);
        assertTrue(o.arrived(), o.why());
    }

    /** The destination's Y through layer 2 (the field's sources, the model's arrival): the goal's column has the ground
     *  under a platform and the platform's top up a stair. With the ground's Y the walk arrives under the platform, with
     *  the platform's Y on it, without a Y on either (the stair's on the straight way: on top). */
    @Test
    void theDestinationsYNamesTheFloorItArrivesOn() {
        for (double y : new double[]{11, 15, Double.NaN}) {
            Layer2Sim sim = new Layer2Sim(SearchTest.twoFloorGoal());
            sim.goalY = y;
            Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 9, 0, 400);
            assertTrue(o.arrived(), o.why());
            double feet = o.path().get(o.path().size() - 1)[1];
            if (Double.isNaN(y)) assertTrue(Math.abs(feet - 11) < 0.01 || Math.abs(feet - 15) < 0.01, "a floor of the column: " + feet);
            else assertEquals(y, feet, 0.01, "arrived at feet " + feet + " with Y " + y);
        }
    }

    /** A gap layer 2 could jump but layer 1 routes round (the ravine with its end near): with Parkour off, layer 2's
     *  shortcut never takes it either. */
    @Test
    void parkourOffNeverJumpsAShortGap() {
        TestWorld w = TestWorld.blocks(-8, -16, 47, 15).floor(10);
        w.fill(10, 1, -6, 11, 10, 6, Palette.AIR);    // two wide, its end 6 to the side
        Layer2Sim sim = new Layer2Sim(w);
        sim.parkour = false;
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 22, 0, 600);
        print("short gap, parkour off", o);
        assertTrue(o.arrived(), o.why());
        // never in the air over the gap (walking its edge, held up by the corner's block, is not a jump over it: the
        // game resolves the fall before the step off, so the last tick on the edge still stands)
        for (double[] q : o.path())
            if (q[0] >= 10 && q[0] < 12 && q[2] >= -6 && q[2] < 7)
                assertTrue((((int) q[3]) & 4) != 0, "in the air over the gap at " + q[0] + "," + q[2]);
        sim = new Layer2Sim(w);
        Layer2Sim.Outcome on = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 22, 0, 600);
        print("short gap, parkour on", on);
        assertTrue(on.arrived(), on.why());
        assertTrue(on.ticks() < o.ticks(), "the jump is quicker: " + on.ticks() + " against " + o.ticks());
    }

    /** An island in a moat whose water's top is a block below both banks (the game can't climb out of deep water
     *  there): layer 1 plans the way out by the ladder on the island's west wall, and layer 2 swims to it and climbs. */
    @Test
    void outOfAMoatByItsLadder() {
        TestWorld w = TestWorld.blocks(-8, -16, 47, 15).floor(10);
        w.fill(6, 7, -12, 28, 7, 12, TestWorld.STONE).fill(6, 8, -12, 28, 9, 12, TestWorld.WATER)
            .fill(6, 10, -12, 28, 10, 12, Palette.AIR);
        w.fill(10, 7, -8, 24, 10, 8, TestWorld.STONE);
        w.fill(9, 9, 4, 9, 10, 4, TestWorld.LADDER_EAST);
        Layer2Sim sim = new Layer2Sim(w);
        sim.parkour = false;   // with Parkour on, the 4 wide moat is jumped from the bank (a block of run-up)
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 17, 0, 800);
        print("moat", o);
        System.out.println("  route moves: " + moves(sim.lastRoute));
        assertTrue(o.arrived(), o.why());
        assertTrue(moves(sim.lastRoute).contains("climb-on"), "out by the ladder");
    }

    /** A cliff 15 high, the destination 28 blocks out on the ground below and no way down on foot: with elytra hops
     *  layer 1 plans a glide off the edge and layer 2 finds how to make it (the jump, opening the glider, the pitch). */
    @Test
    void glidesOffACliffWithElytraHops() {
        TestWorld w = TestWorld.blocks(-8, -8, 47, 7).floor(10);
        w.fill(-8, 11, -8, 2, 25, 7, TestWorld.STONE);
        Layer2Sim off = new Layer2Sim(w);
        Layer2Sim.Outcome o0 = off.walk(Layer2Sim.standing(0.5, 26, 0.5), -90f, 30, 0, 400);
        assertFalse(o0.arrived(), "no way down on foot");
        Layer2Sim sim = new Layer2Sim(w);
        sim.glides = true;
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 26, 0.5), -90f, 30, 0, 400);
        print("cliffGlide", o);
        System.out.println("  route moves: " + (sim.lastRoute == null ? "none" : moves(sim.lastRoute)) + " glideTicks=" + sim.glideTicks);
        assertTrue(o.arrived(), o.why());
        assertTrue(sim.glideTicks > 0, "glided");
    }
}
