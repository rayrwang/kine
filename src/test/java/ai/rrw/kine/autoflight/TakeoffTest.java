package ai.rrw.kine.autoflight;

import ai.rrw.kine.fireworks.RocketBurn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The takeoff's rules that need no game: the cruise it climbs to. */
class TakeoffTest {
    static final double TOP = 319;
    static final int ENGAGE_AGL = 140;   // FlightDirector arms without terrain avoidance with this many clear blocks below

    @Test
    void withTerrainAvoidanceTheCruiseIsTheTargetOrSixtyUp() {
        assertEquals(350, Takeoff.cruiseFor(350, 64, true), 0.0);
        assertEquals(310, Takeoff.cruiseFor(300, 250, true), 0.0);
    }

    @Test
    void withoutTerrainAvoidanceTheHandOverIsHighEnoughForTheAutopilotToArm() {
        // the audit's cases: from ground at 210, 250 and 290 under a 350 target the hand-over came 139, 100 and 60 up
        for (double ground : new double[]{ 64, 150, 210, 250, 290 }) {
            double cruise = Takeoff.cruiseFor(350, ground, false);
            Terrain2D flat = (x, z) -> ground;
            for (int d = 1; d <= 3; d++) {
                for (int burn : new int[]{ RocketBurn.minTicks(d), (int) Math.round(RocketBurn.meanTicks(d)) }) {
                    TakeoffModel.Plan p = TakeoffModel.fromGround(0.5, ground, 0.5, 0, cruise, burn, flat, TOP, 3000);
                    assertTrue(p.reachesCruise(), "ground " + ground + " burn " + burn);
                    double up = Math.floor(p.ys()[p.ticks()]) - ground;   // FlightDirector's clear blocks below
                    assertTrue(up >= ENGAGE_AGL, "ground " + ground + " burn " + burn + ": hand-over " + up + " up");
                }
            }
        }
    }
}
