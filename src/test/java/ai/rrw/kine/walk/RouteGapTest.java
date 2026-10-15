package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jump ahead on a short route to the floor nearest an unreachable goal: the floor stood on is one of the field's
 * sources (the route's last stretch), so the field has no move on from it and Field.jumpAhead finds no gap; Layer2's
 * jumpAhead takes the route's own GAP move (the course's ladder_4b: layer 2 stood 215 ticks before the gap).
 */
public class RouteGapTest {
    static final int FLOOR = 20;

    @Test
    void theRoutesOwnGapIsTheJumpAheadWhenTheFieldHasNone() {
        TestWorld w = TestWorld.blocks(-16, -16, 63, 15);
        w.set(0, FLOOR - 1, 0, TestWorld.STONE);
        w.set(3, FLOOR - 1, 0, TestWorld.STONE);
        w.set(40, FLOOR - 1, 0, TestWorld.STONE);   // the goal, out of reach
        Layer2Sim sim = new Layer2Sim(w);
        long start = sim.view.nodeAt(0, FLOOR, 0);
        PathSearch.Result r = new PathSearch(sim.view, sim.costs).gaps(true).run(start, 40, 0, 1.0, Long.MAX_VALUE, 2_000_000, false);
        assertTrue(r.nearest && r.path.length >= 2, "a route to the floor nearest the goal: found " + r.found + " nearest " + r.nearest + " partial " + r.partial + " n " + r.path.length);
        boolean gap = false;
        for (int i = 1; i < r.moves.length; i++) gap |= r.moves[i] == Graph.GAP;
        assertTrue(gap, "over the gap");
        Route route = new Tighten(sim.view, sim.costs, 0.2, true).route(r, 40, 0);
        Field f = Field.build(sim.view, sim.costs, true, true, false, 1, r.path, route, 0.5, 0.5, 40, 0, Double.NaN, false);
        Planner.Plan plan = new Planner.Plan(route, r, 0, 1, null, null, null, null, false);
        assertNull(f.jumpAhead(0.5, FLOOR, 0.5, 8.0), "the field alone has no move on from the floor stood on");
        double[] a = Layer2.jumpAhead(plan, f, 0.5, FLOOR, 0.5, 8.0);
        assertNotNull(a, "a jump ahead");
        assertEquals(3.5, a[0], 1e-9);
        assertEquals(0.5, a[2], 1e-9);
        assertEquals(FLOOR, a[1], 1e-6);
    }
}
