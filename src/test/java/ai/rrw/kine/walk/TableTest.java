package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/** The walkability table: what floors a column gets from its blocks. */
public class TableTest {
    static int[] floors(View v, int x, int z) {
        ChunkFloors c = v.chunk(x, z);
        int n = c.count(x & 15, z & 15);
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = c.index(x & 15, z & 15, i);
        return idx;
    }

    @Test
    void flatGroundAndPartialBlocks() {
        TestWorld w = blocks(0, 0, 15, 15).floor(10);
        w.set(1, 11, 1, SLAB).set(2, 11, 2, STAIR_EAST).set(3, 11, 3, FENCE).set(4, 11, 4, SOUL_SAND);
        View v = w.view();
        ChunkFloors c = v.chunk(0, 0);
        int[] f = floors(v, 0, 0);
        assertEquals(1, f.length);
        assertEquals(ChunkFloors.GROUND, c.type(f[0]));
        assertEquals(11.0, c.h(f[0]), 1e-6);
        assertEquals(ChunkFloors.MAX_CLEAR, c.head(f[0]), 1e-6);
        assertTrue((c.flags(f[0]) & ChunkFloors.SIMPLE) != 0, "a stone floor is plain");
        assertEquals(11.5, c.h(floors(v, 1, 1)[0]), 1e-6, "slab");
        int stair = floors(v, 2, 2)[0];
        assertEquals(12.0, c.h(stair), 1e-6, "stairs: the top step is under the middle");
        assertEquals(0, c.flags(stair) & ChunkFloors.SIMPLE, "stairs aren't plain");
        assertEquals(12.5, c.h(floors(v, 3, 3)[0]), 1e-6, "fence top");
        int soul = floors(v, 4, 4)[0];
        assertEquals(11.875, c.h(soul), 1e-6);
        assertTrue((c.flags(soul) & ChunkFloors.SLOW) != 0);
    }

    @Test
    void headroomCrawlSpaceAndCeilings() {
        TestWorld w = blocks(0, 0, 15, 15).floor(10);
        w.set(1, 12, 1, SLAB);                         // a slab hanging at 12 to 12.5: 1.0 free above 11, too low
        w.set(2, 13, 2, STONE);                        // ceiling at 13: 2.0 free above 11
        w.fill(3, 12, 3, 3, 12, 3, STONE);             // a block at 12: no floor at 11 (and a floor on top of it)
        View v = w.view();
        ChunkFloors c = v.chunk(0, 0);
        int[] f1 = floors(v, 1, 1);
        assertEquals(1, f1.length);                    // only the top of the hanging slab
        assertEquals(12.5, c.h(f1[0]), 1e-6);
        int[] f2 = floors(v, 2, 2);
        assertEquals(2, f2.length);
        assertEquals(11.0, c.h(f2[0]), 1e-6);
        assertEquals(2.0, c.head(f2[0]), 1e-6);
        int[] f3 = floors(v, 3, 3);
        assertEquals(1, f3.length);
        assertEquals(13.0, c.h(f3[0]), 1e-6);
    }

    @Test
    void waterLadderHazards() {
        TestWorld w = blocks(0, 0, 15, 15).floor(10);
        w.set(1, 11, 1, WATER);                                    // one deep: wading
        w.fill(2, 9, 2, 2, 11, 2, WATER).set(2, 8, 2, STONE);      // three deep (the floor at 10 replaced): swim on top
        w.fill(3, 11, 3, 3, 15, 3, LADDER_WEST);                   // a ladder 5 high
        w.set(4, 11, 4, CACTUS).set(4, 12, 4, CACTUS);
        w.set(5, 10, 5, LAVA);                                     // lava as the floor block
        w.set(6, 10, 6, MAGMA);
        View v = w.view();
        ChunkFloors c = v.chunk(0, 0);
        int[] f1 = floors(v, 1, 1);
        assertEquals(ChunkFloors.GROUND, c.type(f1[0]));
        assertTrue((c.flags(f1[0]) & ChunkFloors.WADE) != 0);
        int[] f2 = floors(v, 2, 2);
        assertEquals(1, f2.length, "the floor under three of water isn't a floor; its top is");
        assertEquals(ChunkFloors.SWIM, c.type(f2[0]));
        assertEquals(12.0, c.h(f2[0]), 1e-6);
        int[] f3 = floors(v, 3, 3);
        assertEquals(ChunkFloors.GROUND, c.type(f3[0]));
        assertTrue((c.flags(f3[0]) & ChunkFloors.CLIMB_FEET) != 0);
        assertEquals(5, f3.length, "ground with a ladder at the feet, then ladder at 12 to 15");
        for (int i = 1; i < 5; i++) {
            assertEquals(ChunkFloors.CLIMB, c.type(f3[i]));
            assertEquals(11 + i, c.h(f3[i]), 1e-6);
        }
        assertTrue(v.hazardIn(4, 4, 11, 12));
        assertTrue(v.block(4, 11, 4).blocksBody(), "cactus: kept as a hazard height");
        assertTrue(v.block(4, 12, 4).blocksBody());
        int[] f5 = floors(v, 5, 5);
        for (int i : f5) assertNotEquals(11.0, c.h(i), 1e-6, "no floor on lava");
        int[] f6 = floors(v, 6, 6);
        for (int i : f6) assertNotEquals(11.0, c.h(i), 1e-6, "no floor on magma");
    }

    @Test
    void blockLookupMatchesTheWorldNearFloors() {
        TestWorld w = blocks(0, 0, 15, 15).floor(10);
        w.set(5, 11, 5, DOOR_WEST).set(5, 12, 5, DOOR_WEST).set(6, 11, 6, FENCE).set(7, 11, 7, SLAB);
        View v = w.view();
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int y = 10; y <= 13; y++) {
                    Kind real = w.get(x, y, z), seen = v.block(x, y, z);
                    if (real == Palette.AIR) assertTrue(seen.empty(), x + " " + y + " " + z);
                    else if (seen == Palette.SOLID) assertSame(STONE, real, "only full blocks stand in as solid: " + x + " " + y + " " + z);
                    else assertSame(real, seen, x + " " + y + " " + z);
                }
        assertEquals(Palette.SOLID, v.block(0, 5, 0), "below every floor: assumed solid");
        assertEquals(Palette.UNKNOWN, v.block(100, 11, 0), "outside: unknown");
    }

    @Test
    void aColumnKeepsItsStampUnlessItsFloorsChange() {
        Kind dirt = Palette.add("dirt", java.util.List.of(box(0, 0, 0, 1, 1, 1)), 0.6F, 1.0F, 0);
        TestWorld w = blocks(0, 0, 15, 15).floor(10);
        ChunkFloors a = w.chunk(0, 0);
        a.stampColumnsFrom(null);
        w.set(3, 10, 3, dirt);                      // grass to dirt, say: the same floor
        w.set(5, 11, 5, STONE);                     // a block on the floor: the floor rises
        ChunkFloors b = w.chunk(0, 0);
        b.stampColumnsFrom(a);
        assertEquals(a.columnStamp(3, 3), b.columnStamp(3, 3), "same floors keep their stamp");
        assertNotEquals(a.columnStamp(5, 5), b.columnStamp(5, 5), "a changed column takes the new build's");
        assertEquals(a.columnStamp(0, 0), b.columnStamp(0, 0));
    }

    @Test
    void negativeCoordinates() {
        TestWorld w = blocks(-40, -40, -1, -1).floor(10);
        w.fill(-20, 11, -30, -20, 12, -5, STONE);
        View v = w.view();
        long start = v.nodeAt(-35, 11, -20);
        assertNotEquals(View.NONE, start);
        assertTrue(start < 0, "a node key west of x = 0 is negative, and valid");
        assertEquals(-35, View.nodeX(start));
        assertEquals(-20, View.nodeZ(start));
        PathSearch.Result r = new PathSearch(v, new Costs()).run(start, -5, -20, 1.0, Long.MAX_VALUE, 1_000_000, false);
        assertTrue(r.found, r.reason);
    }
}
