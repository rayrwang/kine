package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The air more than MAX_CLEAR (24) above a column's highest floor (RW 2026-10-13: on the Kine Parkour course, over a pool
 * kine has no floor in and the ground 160 below, the walk didn't move: no gap was clear and the air round it read as
 * solid). The highest floor whose free space reached the cap, with no hazard above it, is open to the sky.
 */
public class OpenSkyTest {
    static final long FOREVER = Long.MAX_VALUE;

    /** Two pillars 30 high over the ground, a 2-block gap between: a chasm deeper than 24. */
    static TestWorld chasm() {
        TestWorld w = blocks(-8, -8, 23, 23).floor(0);
        w.fill(0, 1, 0, 0, 30, 0, STONE);    // the take-off pillar, top 31
        w.fill(3, 1, 0, 3, 30, 0, STONE);    // the landing pillar, 3 on
        return w;
    }

    @Test
    void aGapOverAChasmDeeperThan24IsAGap() {
        View v = chasm().view();
        long start = v.nodeAt(0, 31, 0);
        assertNotEquals(View.NONE, start);
        PathSearch.Result r = new PathSearch(v, new Costs()).gaps(true).run(start, 3, 0, 1.0, FOREVER, 1_000_000, false);
        assertTrue(r.found && !r.viaExit, "over the 30-deep chasm: " + r.reason);
        boolean gap = false;
        for (byte m : r.moves) gap |= m == Graph.GAP;
        assertTrue(gap, "by a gap jump");
        assertEquals(31.0, r.pathY[r.pathY.length - 1], 1e-4, "on the landing pillar, not the ground");
    }

    @Test
    void theAirBesideAPillarHighAboveTheGroundIsAir() {
        View v = chasm().view();
        // beside the take-off pillar, at the feet and the head of a player on it (31, 32): the column's only floor is the
        // ground (top 1), 30 below; it had read as solid
        assertSame(Palette.AIR, v.block(1, 31, 0));
        assertSame(Palette.AIR, v.block(1, 32, 0));
        assertSame(Palette.AIR, v.block(-1, 40, 0));
        // the ground's own block and the pillar are what they are
        assertSame(STONE, v.block(1, 0, 0));
        assertSame(STONE, v.block(0, 30, 0));
    }

    @Test
    void aRealCeiling30UpIsStillSolidWhereItIsKnown() {
        TestWorld w = blocks(-8, -8, 23, 23).floor(0);
        w.set(1, 32, 0, STONE);   // a block 32 over the ground
        View v = w.view();
        assertSame(STONE, v.block(1, 32, 0), "the block itself (its floor's window)");
        // between 24 over the ground and the block: not known (the ground's free space was measured 24 up), not open sky
        // (the ground isn't the column's highest floor: the block's top is)
        assertSame(Palette.SOLID, v.block(1, 28, 0));
        assertSame(Palette.AIR, v.block(1, 33, 0), "over the block: its top is the highest floor, open above");
        // a gap past it isn't offered through it: the take-off pillar beside it (feet 30), the landing 3 on; the block is in
        // the jump's arc (the head up to 33), its top 3 over the take-off (no stepping on it)
        w.fill(0, 1, 0, 0, 29, 0, STONE);
        w.fill(3, 1, 0, 3, 29, 0, STONE);
        View v2 = w.view();
        long start = v2.nodeAt(0, 30, 0);
        PathSearch.Result r = new PathSearch(v2, new Costs()).gaps(true).run(start, 3, 0, 1.0, FOREVER, 1_000_000, false);
        boolean gap = false;
        for (byte m : r.moves) gap |= m == Graph.GAP;
        assertFalse(r.found && gap, "no gap jump through the block in the arc: " + r.reason);
        // the same with the block gone: the gap is there
        w.set(1, 32, 0, Palette.AIR);
        View v3 = w.view();
        PathSearch.Result r3 = new PathSearch(v3, new Costs()).gaps(true).run(v3.nodeAt(0, 30, 0), 3, 0, 1.0, FOREVER, 1_000_000, false);
        assertTrue(r3.found, "without the block: " + r3.reason);
    }

    /** The course's first pool: water over soul sand as bubble columns (listed as blocks to avoid, no floor in them), the
     *  ground far below: what they cover is unknown, the air over them open. */
    @Test
    void aGapOverAPoolOfBubbleColumnsIsAGap() {
        TestWorld w = chasm();
        w.fill(-6, 9, -6, 9, 9, 6, SOUL_SAND);
        w.fill(-6, 10, -6, 9, 11, 6, CourseStartTest.BUBBLE);
        w.fill(0, 1, 0, 0, 30, 0, STONE);
        w.fill(3, 1, 0, 3, 30, 0, STONE);
        View v = w.view();
        assertSame(Palette.AIR, v.block(1, 31, 0), "over the pool: open");
        assertSame(Palette.SOLID, v.block(1, 9, 0), "the soul sand under the bubbles: not known");
        PathSearch.Result r = new PathSearch(v, new Costs()).gaps(true).run(v.nodeAt(0, 31, 0), 3, 0, 1.0, FOREVER, 1_000_000, false);
        assertTrue(r.found && !r.viaExit, "over the pool: " + r.reason);
    }

    @Test
    void aHazardAboveKeepsItClosed() {
        TestWorld w = blocks(-8, -8, 23, 23).floor(0);
        w.set(1, 35, 0, CACTUS);
        View v = w.view();
        assertSame(Palette.SOLID, v.block(1, 30, 0), "a hazard listed above the highest floor: not open sky");
    }
}
