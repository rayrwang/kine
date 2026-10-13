package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/** The search display's replay: at every point of the replay the expanded floors are among those seen, both grow,
 *  and at the end everything recorded is shown; segments go from a parent to its floor. */
public class ReplayTest {
    static double[] gridX(PathSearch.Result r) {
        double[] a = new double[r.path.length];
        for (int k = 0; k < a.length; k++) a[k] = View.nodeX(r.path[k]) + 0.5;
        return a;
    }

    static double[] gridZ(PathSearch.Result r) {
        double[] a = new double[r.path.length];
        for (int k = 0; k < a.length; k++) a[k] = View.nodeZ(r.path[k]) + 0.5;
        return a;
    }

    static double toLeg(double x, double z, Route rt, int k) {
        double ax = rt.x[k - 1], az = rt.z[k - 1], lx = rt.x[k] - ax, lz = rt.z[k] - az, len2 = lx * lx + lz * lz;
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - ax) * lx + (z - az) * lz) / len2));
        return Math.hypot(x - (ax + lx * t), z - (az + lz * t));
    }

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
        // the display's two stages: the feeler's last step is the grid path; the searched columns grow to all of them
        assertEquals(rp.feelSteps, Math.min(Replay.FEEL_STEPS, rp.expansions));
        assertEquals(r.path.length - 1, rp.feelCount[rp.feelSteps]);
        for (int k = 0; k < rp.feelSteps; k++) assertTrue(rp.feelCount[k] >= 1, "feeler step " + k + " empty");
        assertNotNull(rp.cellOrder);
        int prev = 0;
        for (int e = 0; e <= rp.expansions + 1; e += 7) { int c = rp.cellsBy(e); assertTrue(c >= prev); prev = c; }
        assertEquals(rp.cellOrder.length, rp.cellsBy(rp.expansions + 1));
        // the tightening: keyframe 0 is the staircase (the grid path), the last lies on the route's straight legs
        assertNotNull(Replay.waypointsOnPath(route, gridX(r), gridZ(r), r.path.length), "the route's waypoints are grid floors");
        assertEquals(r.path.length - 1, rp.tautCount[0]);
        int last = Replay.TAUT_STEPS;
        double worst = 0;
        for (int j = rp.tautFrom[last]; j < rp.tautFrom[last] + rp.tautCount[last]; j++) {
            double x = rp.taut.p[6 * j] + rp.ox, z = rp.taut.p[6 * j + 2] + rp.oz;
            double d = Double.MAX_VALUE;
            for (int k = 1; k < route.n; k++) d = Math.min(d, toLeg(x, z, route, k));
            worst = Math.max(worst, d);
        }
        assertTrue(worst < 1e-3, "the last keyframe lies on the route: " + worst);
        // colours run from cyan near the start to rose far out
        int near = Replay.ramp(0, 1, 255), far = Replay.ramp(1, 1, 255);
        assertTrue((near & 0xFF) > 200 && ((near >> 8) & 0xFF) > 200, "cyan");
        assertTrue(((far >> 16) & 0xFF) > 200 && ((far >> 8) & 0xFF) < 120, "rose");
    }
}
