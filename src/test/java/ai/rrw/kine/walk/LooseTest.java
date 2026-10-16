package ai.rrw.kine.walk;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/** The loosened layer 1 (Loose, Graph's LOOSE moves): offered where the proven moves have no way, within the reach
 *  calibrated for the landing's height, never onto a floor a proven move reaches, and left out once blocked. */
public class LooseTest {
    /** Two platforms with a gap the GAP table doesn't cover (its landing a fence post, not a plain floor): the strict
     *  search finds no way, the loose one a single loose jump. */
    @Test
    void aLooseJumpWhereTheProvenMovesHaveNone() {
        TestWorld w = blocks(-8, -8, 23, 23);
        w.fill(0, 10, 0, 2, 10, 2, STONE);       // platform A: feet 11
        w.set(6, 10, 1, FENCE);                   // a fence post 3 past the edge, its top 11.5 (half a block up; no plain floor)
        View v = w.view();
        long start = v.nodeAt(1, 11, 1);
        PathSearch.Result strict = new PathSearch(v, Costs.LAYER2).gaps(true).run(start, 6, 1, 2.0, Long.MAX_VALUE, 1_000_000, false);
        assertFalse(strict.found, "no proven way onto the fence");
        PathSearch.Result loose = new PathSearch(v, Costs.LAYER2).gaps(true).loose(true).run(start, 6, 1, 2.0, Long.MAX_VALUE, 1_000_000, false);
        assertTrue(loose.found, "a loose way");
        int n = 0;
        for (int i = 1; i < loose.moves.length; i++) if (loose.moves[i] == Graph.LOOSE) n++;
        assertEquals(1, n, "one loose jump");
        // blocked (its family with it): no way again
        long a = loose.path[loose.path.length - 2], b = loose.path[loose.path.length - 1];
        LongOpenHashSet blocked = new LongOpenHashSet(Loose.family(v, a, b));
        PathSearch.Result again = new PathSearch(v, Costs.LAYER2).gaps(true).loose(true).blocked(blocked)
            .run(start, 6, 1, 2.0, Long.MAX_VALUE, 1_000_000, false);
        if (again.found) {
            long a2 = again.path[again.path.length - 2];
            assertNotEquals(a, a2, "not the blocked take-off again");
            assertTrue(Math.hypot(View.nodeX(b) - View.nodeX(a2), View.nodeZ(b) - View.nodeZ(a2))
                < Math.hypot(View.nodeX(b) - View.nodeX(a), View.nodeZ(b) - View.nodeZ(a)) - 0.25 || Math.abs(View.nodeX(a2) - View.nodeX(a)) > 1
                || Math.abs(View.nodeZ(a2) - View.nodeZ(a)) > 1, "nothing of the blocked family");
        }
    }

    /** Nothing past the reach: a slab 7 blocks out on the level is no loose move. Inner floors of open ground offer none. */
    @Test
    void nothingPastTheReachAndNoneFromInnerFloors() {
        TestWorld w = blocks(-8, -8, 31, 23);
        w.fill(0, 10, 0, 4, 10, 4, STONE);       // a 5 by 5 platform
        w.set(12, 10, 2, SLAB);                   // 8 past the edge's column: past any reach
        View v = w.view();
        long start = v.nodeAt(4, 11, 2);
        PathSearch.Result loose = new PathSearch(v, Costs.LAYER2).gaps(true).loose(true).run(start, 12, 2, 2.0, Long.MAX_VALUE, 1_000_000, false);
        assertFalse(loose.found);
        Graph g = new Graph(v, Costs.LAYER2);
        g.loose = true;
        g.expand(v.nodeAt(2, 11, 2));             // the middle: all eight round it are floors
        for (int k = 0; k < g.n; k++) assertNotEquals(Graph.LOOSE, (int) g.move[k], "no loose move from an inner floor");
    }

    /** The reach by height: the most at 1 down, none above a jump's peak, less where a jump's fall would hurt. */
    @Test
    void reachByHeight() {
        assertEquals(0.0, Loose.reach(1.3));
        assertTrue(Loose.reach(1.0) < Loose.reach(0.0));
        assertTrue(Loose.reach(0.0) < Loose.reach(-1.0));
        // at the game's safe fall (3.9) a jump 2.5 down falls 3.75, one 3 down 4.25: that one is a step off the edge
        assertTrue(Loose.reach(-3.0) < Loose.reach(-2.5), "a drop of 3 is a step off the edge, not a jump");
        assertTrue(Loose.cost(Costs.LAYER2, 5.5, 0.0) > Loose.cost(Costs.LAYER2, 3.0, 0.0) + 2.5 * Costs.LAYER2.sprint,
            "a harder jump costs more than its run");
    }

    /** No loose drop through a ceiling: from a closed room's roof onto its floor two blocks in, nothing (the flight line
     *  alone let it through); with a hole in the roof between them, the drop by the hole is offered. */
    @Test
    void noLooseDropThroughACeiling() {
        TestWorld w = blocks(-8, -8, 23, 23);
        w.fill(-1, 8, -1, 7, 8, 7, STONE);       // the room's floor: feet 9
        w.fill(-1, 9, -1, 7, 10, -1, STONE);     // its walls, two high
        w.fill(-1, 9, 7, 7, 10, 7, STONE);
        w.fill(-1, 9, -1, -1, 10, 7, STONE);
        w.fill(7, 9, -1, 7, 10, 7, STONE);
        w.fill(-1, 11, -1, 7, 11, 7, STONE);     // the roof: feet 12, the room's ceiling at 11
        boolean was = Graph.DESCENT_CHECK;
        try {
            for (boolean hole : new boolean[]{false, true}) {
                if (hole) w.set(3, 11, 0, Palette.AIR);
                View v = w.view();
                long from = v.nodeAt(3, 12, -1), to = v.nodeAt(3, 9, 1);
                assertNotEquals(View.NONE, from);
                assertNotEquals(View.NONE, to);
                for (boolean check : new boolean[]{false, true}) {
                    Graph.DESCENT_CHECK = check;
                    Graph g = new Graph(v, Costs.LAYER2);
                    g.loose = true;
                    g.expand(from);
                    boolean offered = false;
                    for (int k = 0; k < g.n; k++) offered |= g.to[k] == to && g.move[k] == Graph.LOOSE;
                    assertEquals(hole || !check, offered, (hole ? "a hole in the roof" : "a closed roof") + ", descent check " + check);
                }
            }
        } finally {
            Graph.DESCENT_CHECK = was;
        }
    }

    static final Kind CLOSED_DOOR_WEST =Palette.add("closed_door_west", java.util.List.of(TestWorld.box(0, 0, 0, 0.1875, 1, 1)),
        0.6F, 1.0F, Kind.DOOR | Kind.CLOSED_DOOR);

    /** A corridor shut by a closed wooden door: with doors on, a way through it by an OPEN move (the walk opens it);
     *  off, none. */
    @Test
    void aClosedDoorIsAWayWhenDoorsOpen() {
        TestWorld w = blocks(-8, -8, 23, 23);
        w.fill(0, 10, -1, 8, 10, 1, STONE);          // the corridor's floor: feet 11
        w.fill(0, 11, -1, 8, 13, -1, STONE);         // its walls
        w.fill(0, 11, 1, 8, 13, 1, STONE);
        w.set(4, 11, 0, CLOSED_DOOR_WEST);           // the door, two high, across the corridor
        w.set(4, 12, 0, CLOSED_DOOR_WEST);
        View v = w.view();
        long start = v.nodeAt(1, 11, 0);
        PathSearch.Result shut = new PathSearch(v, Costs.LAYER2).run(start, 7, 0, 2.0, Long.MAX_VALUE, 1_000_000, false);
        assertFalse(shut.found, "a closed door is a wall without doors on");
        PathSearch.Result open = new PathSearch(v, Costs.LAYER2).doors(true).run(start, 7, 0, 2.0, Long.MAX_VALUE, 1_000_000, false);
        assertTrue(open.found, "through the door");
        boolean through = false;
        for (int i = 1; i < open.moves.length; i++) through |= open.moves[i] == Graph.OPEN;
        assertTrue(through, "by an OPEN move");
    }
}
