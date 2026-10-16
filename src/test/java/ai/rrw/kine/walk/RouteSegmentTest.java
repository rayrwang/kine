package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A loose job's route that comes by over the player's head (a leg three blocks up, over a wall) or far off is not
     *  taken where the player stands (Walk.offNewRoute): the walk plans from there instead. */
    @Test
    void aRouteOverheadOrFarIsOffTheWalk() {
        // along x at 59, then up to 63 and back west over the start's side at 63
        double[] x = {0.5, 8.5, 8.5, 0.5};
        double[] y = {59, 59, 63, 63};
        double[] z = {0.5, 0.5, 4.5, 4.5};
        byte[] move = new byte[x.length];
        Route r = new Route(x, y, z, move, x.length, false, 0, 1);
        assertFalse(Walk.offNewRoute(4.0, 59.0, 0.7, r), "on the first leg");
        assertTrue(Walk.offNewRoute(1.0, 59.0, 4.4, r), "under the last leg, four blocks down");
        assertTrue(Walk.offNewRoute(4.0, 59.0, -6.0, r), "6.5 blocks to the side of the first leg");
        assertFalse(Walk.offNewRoute(3.0, 59.6, 0.6, r), "mid-step on the first leg");
        // the walk can't be further along a new route than it is from its start: by the last leg, 8.5 off the start,
        // it is off (the route came from elsewhere), though the last leg itself passes by
        assertTrue(Walk.offNewRoute(1.0, 63.0, 4.4, r), "on the last leg, far along");
    }

    /** A new route's leg for the walk (Walk.startSegment): not a later leg passing near, only as far along as the walk
     *  is from the route's start (and 4 more). */
    @Test
    void aNewRoutesLegIsNoFurtherThanTheWalkHasCome() {
        // out along x at 59, round at 59 and back toward the start at z 2.5 (a loop passing by the start)
        double[] x = {0.5, 10.5, 10.5, 1.5};
        double[] y = {59, 59, 59, 59};
        double[] z = {0.5, 0.5, 2.5, 2.5};
        byte[] move = new byte[x.length];
        Route r = new Route(x, y, z, move, x.length, false, 0, 1);
        assertEquals(3, Walk.nearestSegment(1.6, 59.0, 2.0, r), "the nearest leg is the way back");
        assertEquals(1, Walk.startSegment(1.6, 59.0, 2.0, r), "but the walk is at the start");
        assertEquals(3, Walk.nearestSegment(1.6, 59.0, 2.0, r, 2, 30.0), "as it goes, the window reaches the way back");
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
