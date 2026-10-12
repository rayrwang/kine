package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.Shape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cost table's numbers: Baritone's, and the estimate's fastest speed measured on the movement copy, so the estimate
 * never charges more than a move can cost.
 */
public class CostsTest {
    /** Stone at y = 63 from x -4 to 4 and z -4 to 800, air above. */
    static BlockGrid runway() {
        BlockGrid g = new BlockGrid(-4, 60, -4, 9, 10, 805);
        BlockInfo stone = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 800; z++) g.set(x, 63, z, stone);
        return g;
    }

    static MoveState rest() {
        MoveState s = new MoveState();
        s.x = 0.5; s.y = 64; s.z = 0.5;
        s.vy = -0.0784000015258789;
        s.onGround = true;
        s.hasSupport = true; s.supX = 0; s.supY = 63; s.supZ = 0;
        return s;
    }

    /** Average speed (m/s) holding these keys facing +z, from the 10th landing to the last landing within 2000 ticks
     *  (whole jump cycles; without jumping, ticks 200 to 2000). */
    static double topSpeed(int keys) {
        GroundPhysics phys = new GroundPhysics(runway(), new Body());
        MoveState s = rest();
        int landings = 0, t0 = -1, t1 = -1;
        double z0 = 0, z1 = 0;
        boolean wasGround = true;
        for (int t = 1; t <= 2000; t++) {
            phys.step(s, keys, 0f);
            boolean landed = s.onGround && !wasGround;
            wasGround = s.onGround;
            if ((keys & MoveState.JUMP) == 0 ? t == 200 : landed && ++landings == 10) { t0 = t; z0 = s.z; }
            if (t0 >= 0 && ((keys & MoveState.JUMP) == 0 ? t == 2000 : landed)) { t1 = t; z1 = s.z; }
        }
        return (z1 - z0) / (t1 - t0) * 20;
    }

    @Test
    void sprintJumpTopSpeed() {
        double walk = topSpeed(MoveState.FORWARD);
        double sprint = topSpeed(MoveState.FORWARD | MoveState.SPRINT);
        double jump = topSpeed(MoveState.FORWARD | MoveState.SPRINT | MoveState.JUMP);
        System.out.printf("KINEMEASURE walk %.4f sprint %.4f sprintJump %.4f m/s%n", walk, sprint, jump);
        assertEquals(4.317, walk, 0.01);
        assertEquals(5.612, sprint, 0.01);
        // the estimate's speed must be at least the measured top speed (never overestimate the time)
        Costs c = new Costs();
        assertTrue(20 / c.fastest >= jump - 1e-3, "estimate speed " + 20 / c.fastest + " below measured " + jump);
        assertEquals(jump, 20 / c.fastest, 0.01, "the estimate uses the measured top speed");
    }

    @Test
    void jumpRisesOneBlockInThreeTicks() {
        // vy = 0.42 at the jump, then (vy - 0.08) * 0.98 a tick
        double vy = 0.42, y = 0;
        int t = 0;
        while (y < 1.0) { y += vy; vy = (vy - 0.08) * 0.98; t++; }
        assertEquals(3, t);
        assertTrue(new Costs().fastestClimb <= t);
    }

    @Test
    void baritoneNumbers() {
        Costs c = new Costs();
        assertEquals(4.633, c.walk, 1e-3);
        assertEquals(3.564, c.sprint, 1e-3);
        assertEquals(9.091, c.water, 1e-3);
        assertEquals(8.511, c.ladderUp, 1e-3);
        assertEquals(6.667, c.ladderDown, 1e-3);
        // Baritone's JUMP_ONE_BLOCK_COST and FALL_N_BLOCKS_COST from its distanceToTicks
        assertEquals(Costs.fallTicks(1.25) - Costs.fallTicks(0.25), c.jumpOne, 1e-12);
        assertTrue(c.jumpOne > 3 && c.jumpOne < 3.3, "jumpOne " + c.jumpOne);
        assertEquals(0, Costs.fallTicks(0), 0);
        assertTrue(Costs.fallTicks(3) > Costs.fallTicks(2));
        // every move's cost is at least the estimate over its own displacement
        for (double d : new double[]{1, Math.sqrt(2), Math.sqrt(5)}) {
            assertTrue(d * c.sprint >= c.estimate(d, 0));
            assertTrue(c.jumpCost(d, 1.0) >= c.estimate(d, 1.0));
            assertTrue(c.dropCost(d, 3.0) >= c.estimate(d, 0));
        }
        assertTrue(c.ladderUp >= c.estimate(0, 1));
    }
}
