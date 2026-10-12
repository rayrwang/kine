package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/** The search display's replay: at every point of the replay the expanded floors are among those seen, both grow,
 *  and at the end everything recorded is shown; segments go from a parent to its floor. */
public class ReplayTest {
    @Test
    void theReplayGrowsInTheSearchesOrder() {
        TestWorld w = blocks(0, 0, 47, 47).floor(10);
        w.fill(20, 11, 0, 20, 13, 40, STONE);
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, new Costs()).run(v.nodeAt(5, 11, 5), 40, 5, 1.0, Long.MAX_VALUE, 1_000_000, true);
        assertTrue(r.found);
        Route route = new Tighten(v, new Costs(), 0.2, true).route(r, 40, 5);
        Replay rp = new Replay(r, route, new RailPath(v, route));
        int n = rp.seenAt.length;
        assertTrue(n > 100, "segments " + n);
        assertEquals(n, rp.bright.n);
        assertEquals(n, rp.dim.n);
        int prevSeen = 0, prevClosed = 0;
        for (int e = 0; e <= rp.expansions + 1; e++) {
            int s = rp.seenBy(e), c = rp.closedBy(e);
            assertTrue(c <= s, "expanded but not seen at " + e);
            assertTrue(s >= prevSeen && c >= prevClosed);
            prevSeen = s; prevClosed = c;
        }
        assertEquals(n, rp.seenBy(rp.expansions + 1));
        assertTrue(rp.grid.n == r.path.length - 1);
        assertTrue(rp.route.n >= 1);
        // colours run from cyan near the start to rose far out
        int near = Replay.ramp(0, 1, 255), far = Replay.ramp(1, 1, 255);
        assertTrue((near & 0xFF) > 200 && ((near >> 8) & 0xFF) > 200, "cyan");
        assertTrue(((far >> 16) & 0xFF) > 200 && ((far >> 8) & 0xFF) < 120, "rose");
    }
}
