package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The walk's place on its route (Walk.nearestSegment) heeds height: a route that comes back over its own start. */
class RouteSegmentTest {
    @Test
    void aRouteBackOverItsStartKeepsTheWalkAtTheStart() {
        // out along x at 90, up a staircase to 99 on the far side, and back over the start at 99
        double[] x = {0.5, 10.5, 10.5, 10.5, 0.5, 0.5};
        double[] y = {90, 90, 94, 99, 99, 99};
        double[] z = {0.5, 0.5, 4.5, 8.5, 8.5, 1.5};
        byte[] move = new byte[x.length];
        Route r = new Route(x, y, z, move, x.length, false, 0, 1);
        assertEquals(1, Walk.nearestSegment(0.6, 90.0, 0.7, r), "standing at the start, under the route's last leg");
        assertEquals(5, Walk.nearestSegment(0.6, 99.0, 1.2, r), "on the route's last leg");
        assertEquals(1, Walk.nearestSegment(3.0, 91.2, 0.5, r), "mid-jump on the first leg");
    }

    /** The field's place on the grid path (Field.nearestIndex) heeds height too: a path out at 90 and back over itself
     *  at 99, the player under its later part. */
    @Test
    void theFieldsPlaceOnThePathKeepsToTheFloorStoodOn() {
        int n = 22;
        long[] path = new long[n];
        float[] ys = new float[n];
        for (int i = 0; i < 11; i++) { path[i] = View.node(i, 0, 0); ys[i] = 90; }
        for (int i = 11; i < n; i++) { path[i] = View.node(21 - i, 0, 1); ys[i] = 99; }
        assertEquals(2, Field.nearestIndex(path, ys, 2.5, 90.0, 0.5), "on the first stretch, under the last");
        assertEquals(19, Field.nearestIndex(path, ys, 2.5, 99.0, 0.5), "on the last stretch");
    }
}
