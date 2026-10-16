package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import static ai.rrw.kine.walk.TestWorld.STONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The floor nearest a goal that can't be reached keeps to the height the walk has climbed while the goal is above
 *  (PathSearch.keepAbove, Walk.keepAbove): the Parkour Spiral's sea under a high goal. */
class KeepAboveTest {
    static final long FOREVER = Long.MAX_VALUE;

    /** A plateau (feet 21) with stairs down to the ground (feet 11) on its far side, and the goal on a pillar (feet 31)
     *  35 blocks off, out of reach: by the estimate the ground at the pillar's foot is nearest (20 under the goal, a
     *  block off) rather than the plateau's edge (10 under it, 35 off); kept above 20, the plateau's edge. */
    @Test
    void theNearestFloorKeepsTheHeightClimbed() {
        TestWorld w = TestWorld.blocks(-8, -20, 44, 8).floor(10);
        w.fill(-8, 11, -8, 5, 20, 8, STONE);                                   // the plateau, its top at 21
        for (int z = -9; z >= -17; z--) w.fill(0, 11, z, 0, 19 - (-9 - z), z, STONE);   // stairs down to the ground
        w.fill(40, 11, 0, 40, 30, 0, STONE);                                  // the goal's pillar, its top at 31
        View v = w.view();
        long start = v.nodeAt(0, 21, 0);
        PathSearch.Result free = new PathSearch(v, new Costs()).goalY(31).run(start, 40, 0, 1.0, FOREVER, 2_000_000, false);
        assertFalse(free.found);
        assertTrue(free.nearest, free.reason);
        assertEquals(11.0, free.pathY[free.pathY.length - 1], 1e-4, "by the estimate alone: down at the pillar's foot");
        PathSearch.Result kept = new PathSearch(v, new Costs()).goalY(31).keepAbove(20).run(start, 40, 0, 1.0, FOREVER, 2_000_000, false);
        assertFalse(kept.found);
        assertTrue(kept.nearest, kept.reason);
        assertEquals(21.0, kept.pathY[kept.pathY.length - 1], 1e-4, "kept above 20: on the plateau");
        assertEquals(5, View.nodeX(kept.path[kept.path.length - 1]), "the plateau's edge nearest the goal");
    }

    /** The floor nearest the goal keeps the height, and so does the way to it: two plateaus at 21 joined by a ditch
     *  stepped down to 18 (the short way) and by a bridge at 21 round its end (the long way); the goal on a pillar out
     *  of reach past the second. Kept above 20, the way goes over the bridge (a second search without the floors under
     *  20), not through the ditch. */
    @Test
    void theWayToTheNearestFloorKeepsTheHeightToo() {
        TestWorld w = TestWorld.blocks(-8, -10, 44, 20).floor(10);
        w.fill(-8, 11, -8, 10, 20, 17, STONE);                     // plateau A, top 21
        w.fill(18, 11, -8, 30, 20, 17, STONE);                     // plateau B, top 21
        w.fill(11, 11, 15, 17, 20, 17, STONE);                     // the bridge, top 21
        int[] tops = {20, 19, 18, 18, 18, 19, 20};                  // the ditch's steps, x 11 to 17
        for (int i = 0; i < tops.length; i++) w.fill(11 + i, 11, -8, 11 + i, tops[i] - 1, 14, STONE);
        w.fill(40, 11, 0, 40, 30, 0, STONE);                       // the goal's pillar, top 31
        View v = w.view();
        long start = v.nodeAt(0, 21, 0);
        PathSearch.Result free = new PathSearch(v, new Costs()).goalY(31).run(start, 40, 0, 1.0, FOREVER, 2_000_000, false);
        assertTrue(free.nearest && PathSearch.dips(free, 20), "by cost alone the way goes through the ditch");
        PathSearch.Result kept = new PathSearch(v, new Costs()).goalY(31).keepAbove(20).run(start, 40, 0, 1.0, FOREVER, 2_000_000, false);
        assertTrue(kept.nearest, kept.reason);
        assertFalse(PathSearch.dips(kept, 20), "kept above 20 all the way");
        assertEquals(30, View.nodeX(kept.path[kept.path.length - 1]), "to plateau B's edge nearest the goal");
    }
}
