package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/** The far-target fallback's choice (Hop.pick): the next floor past where layer 1 stops, toward the goal. */
public class HopTest {
    @Test
    void theNextFloorPastAWallTowardTheGoal() {
        TestWorld w = blocks(-8, -8, 31, 23);
        w.set(0, 10, 0, STONE);                                       // standing here, feet 11
        w.fill(1, 10, 0, 1, 12, 0, STONE);                            // a wall: its top 13, two over the feet
        w.set(2, 10, 0, STONE);                                       // the landing past it
        w.set(5, 10, 0, STONE);                                       // the next block on
        w.set(0, 10, 3, STONE);                                       // a block aside, no nearer the goal
        View v = w.view();
        Set<Long> tried = new HashSet<>();
        long a = Hop.pick(v, 0.5, 11, 0.5, 40, 0, 39.5, tried, 3.0, 48.0);
        assertEquals(2, View.nodeX(a), "the landing past the wall, not the wall's top (two up) nor the block aside");
        tried.add(a);
        long b = Hop.pick(v, 0.5, 11, 0.5, 40, 0, 39.5, tried, 3.0, 48.0);
        assertEquals(5, View.nodeX(b), "then the next one on");
        tried.add(b);
        assertEquals(View.NONE, Hop.pick(v, 0.5, 11, 0.5, 40, 0, 39.5, tried, 3.0, 48.0), "nothing else nearer the goal");
        // nothing a block nearer the goal than the walk has stood: none
        assertEquals(View.NONE, Hop.pick(v, 0.5, 11, 0.5, 40, 0, 35.0, new HashSet<>(), 3.0, 48.0));
    }

    /** A far goal past the loaded area, the start a pad beside a floor a block up and a block across (the course's join
     *  onto a take-off): the weighted pass aims through where the line leaves the loaded area, and that floor, a hair
     *  nearer the goal itself but off the aimed line, must still be the floor nearest the goal. */
    @Test
    void theFloorBesideThePadIsNearerTheFarGoal() {
        TestWorld w = blocks(-8, -8, 23, 23);
        w.set(0, 10, 1, STONE);   // the pad: feet 11
        w.set(0, 11, 0, STONE);   // the take-off beside it, a block up: feet 12
        View v = w.view();
        long start = v.nodeAt(0, 11, 1);
        for (double weight : new double[]{2.0, 1.0}) {
            PathSearch.Result r = new PathSearch(v, new Costs()).run(start, 750, -2, weight, Long.MAX_VALUE, 1_000_000, false);
            assertTrue(r.nearest, "weight " + weight + ": a way to the floor nearest the far goal");
            long end = r.path[r.path.length - 1];
            assertEquals(0, View.nodeZ(end), "weight " + weight + ": the take-off beside the pad");
        }
    }

    /** On the course's pad, the next jump's start a block up beside it and its landing 4 on: the start is the hop's floor
     *  (a hair nearer the far goal), not the landing past it (end_rod_jump: no graph move onto the rod). */
    @Test
    void theStartBesideThePadBeforeTheLanding() {
        TestWorld w = blocks(-8, -8, 23, 23);
        w.set(0, 10, 1, STONE);   // the pad: feet 11
        w.set(0, 11, 0, STONE);   // the start beside it, a block up
        w.set(4, 11, 0, STONE);   // the landing 4 on
        View v = w.view();
        double px = 0.5, pz = 1.5;
        double best = Math.hypot(750 + 0.5 - px, -2 + 0.5 - pz);
        long a = Hop.pick(v, px, 11, pz, 750, -2, best, new HashSet<>(), 3.0, 8.0);
        assertEquals(0, View.nodeX(a), "the start beside the pad");
        assertEquals(0, View.nodeZ(a));
    }

    /** The start beside the pad on the side away from the goal (farther from it than the pad): a step up beside the
     *  walk is taken; not a step down (back onto the pad from the start). */
    @Test
    void aStepUpBesideThePadAwayFromTheGoal() {
        TestWorld w = blocks(-8, -8, 23, 23);
        w.set(0, 10, 0, STONE);   // the pad: feet 11
        w.set(0, 11, 1, STONE);   // the start beside it, a block up, a block farther from the goal's z (-2)
        w.set(4, 11, 1, STONE);   // the landing 4 on
        View v = w.view();
        double best = Math.hypot(750 - 0, -2 - 0);
        long a = Hop.pick(v, 0.5, 11, 0.5, 750, -2, best, new HashSet<>(), 3.0, 8.0);
        assertEquals(0, View.nodeX(a), "the start beside the pad, a step up");
        assertEquals(1, View.nodeZ(a));
        // from the start: the landing, not back down onto the pad
        double best2 = Math.min(best, Math.hypot(750 - 0, -2 - 1));
        long b = Hop.pick(v, 0.5, 12, 1.5, 750, -2, best2, new HashSet<>(), 3.0, 8.0);
        assertEquals(4, View.nodeX(b), "the landing");
    }

    /** The course's start a block up beside its pad, the pad on the far goal's line (its z) and the start a block off it:
     *  the pad is a hair nearer the goal by its z. With the destination's Y (above both), the start is the floor nearest
     *  the goal (layer 1) and, standing on it, the pad is not a floor nearer (Hop.pick): the landing 3 on is (RW
     *  2026-10-14: flower_pot_jump and mm_backwalled_1bm_4b, the walk went to the pad and never tried the jump). */
    @Test
    void thePadOnTheGoalsLineIsNotNearerThanTheStart() {
        TestWorld w = blocks(-8, -8, 23, 23);
        w.set(0, 10, 0, STONE);   // the pad, on the goal's line (z 0): feet 11
        w.set(0, 11, -1, STONE);  // the start beside it, a block up and a block off the line: feet 12
        w.set(3, 11, -1, STONE);  // the landing 3 on
        View v = w.view();
        double goalY = 52;
        long pad = v.nodeAt(0, 11, 0);
        for (double weight : new double[]{2.0, 1.0}) {
            PathSearch.Result r = new PathSearch(v, new Costs()).goalY(goalY).run(pad, 750, 0, weight, Long.MAX_VALUE, 1_000_000, false);
            assertTrue(r.nearest, "weight " + weight + ": a way to the floor nearest the far goal");
            long end = r.path[r.path.length - 1];
            assertEquals(-1, View.nodeZ(end), "weight " + weight + ": the start, a block up, not the pad");
        }
        // standing on the start: as near the goal as the pad (a block aside), and higher toward its Y: the landing
        double best = Math.hypot(750 - 0, 0 - (-1));
        long a = Hop.pick(v, 0.5, 12, -0.5, 750, 0, goalY, best, new HashSet<>(), 3.0, 8.0);
        assertEquals(3, View.nodeX(a), "the landing, not the pad");
        // without a Y, as before: the pad counts as nearer
        long b = Hop.pick(v, 0.5, 12, -0.5, 750, 0, best, new HashSet<>(), 3.0, 8.0);
        assertEquals(0, View.nodeX(b), "with no Y, the pad (a hair nearer)");
        // standing on the pad: the start beside it, a block up, is the hop's floor (as for the end rod)
        long c = Hop.pick(v, 0.5, 11, 0.5, 750, 0, goalY, 750.0, new HashSet<>(), 3.0, 8.0);
        assertEquals(-1, View.nodeZ(c), "from the pad, the start");
        assertEquals(0, View.nodeX(c));
    }

    /** Beside a tower's goal column with the destination's Y on its top (the Parkour Spiral, RW 2026-10-15: the walk
     *  stood beside the block it should have gone up onto, with or without a Y): no floor is horizontally nearer, but
     *  with a Y the climb counts (Hop.nearness), so the floor a block up and two out is nearer, and the walk hops there.
     *  Without a Y, as before: nothing. Still no more than a jump up. */
    @Test
    void besideATowerAFloorUpAndOutwardIsNearerWithAY() {
        TestWorld w = blocks(-8, -8, 23, 23);
        w.fill(-6, 10, -6, 12, 10, 6, STONE);    // the ground: feet 11
        w.fill(0, 11, 0, 0, 15, 0, STONE);       // the tower, the goal's column: its top 16
        w.set(3, 11, 0, STONE);                  // a block up, two farther out than where the walk stands: feet 12
        w.fill(6, 11, 3, 6, 12, 3, STONE);       // two up: more than a jump
        View v = w.view();
        Costs c = Costs.LAYER2;
        double goalY = 16, px = 1.5, py = 11, pz = 0.5;
        Hop.Best best = new Hop.Best();
        double g = Math.hypot(0 - 1, 0 - 0);
        best.stood(Hop.nearness(c, g, py, goalY), g, Hop.tie(c, goalY));
        long a = Hop.pick(v, c, px, py, pz, 0, 0, goalY, best, new HashSet<>(), 3.0, 8.0);
        assertNotEquals(View.NONE, a, "a floor up and outward");
        assertEquals(3, View.nodeX(a));
        assertEquals(0, View.nodeZ(a));
        // without a Y: nothing horizontally nearer than beside the column, and no step up within 1.5
        assertEquals(View.NONE, Hop.pick(v, px, py, pz, 0, 0, 1.0, new HashSet<>(), 3.0, 8.0));
        // from the block up: the tower's side again at its foot is lower, not nearer; nothing more than a jump up
        Hop.Best b2 = best.copy();
        double g2 = Math.hypot(0 - 3, 0 - 0);
        b2.stood(Hop.nearness(c, g2, 12, goalY), g2, Hop.tie(c, goalY));
        Set<Long> tried = new HashSet<>();
        tried.add(a);
        assertEquals(View.NONE, Hop.pick(v, c, 3.5, 12, 0.5, 0, 0, goalY, b2, tried, 3.0, 8.0), "the ground is lower: not nearer");
    }

    /** With a Y the climb ties two floors at one height beside a tower: the one horizontally nearer than the best stand
     *  by more than TIE is the nearer (Hop.Best keeps the horizontal distance among the stands as near). */
    @Test
    void atOneHeightTheHorizontallyNearerIsNearer() {
        TestWorld w = blocks(-8, -8, 23, 23);
        w.fill(-6, 10, -6, 12, 10, 6, STONE);    // the ground: feet 11
        w.fill(0, 11, 0, 0, 20, 0, STONE);       // a tall tower, the goal's column: its top 21
        View v = w.view();
        Costs c = Costs.LAYER2;
        double goalY = 21;
        Hop.Best best = new Hop.Best();
        double g = Math.hypot(0 - 5, 0 - 0);      // standing 5 out on the ground
        best.stood(Hop.nearness(c, g, 11, goalY), g, Hop.tie(c, goalY));
        long a = Hop.pick(v, c, 5.5, 11, 0.5, 0, 0, goalY, best, new HashSet<>(), 3.0, 8.0);
        assertNotEquals(View.NONE, a, "the ground a block nearer the tower");
        assertTrue(Math.hypot(View.nodeX(a), View.nodeZ(a)) < 5 - Hop.TIE);
    }
}
