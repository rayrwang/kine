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
}
