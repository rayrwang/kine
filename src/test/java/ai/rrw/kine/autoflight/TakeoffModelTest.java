package ai.rrw.kine.autoflight;

import ai.rrw.kine.fireworks.RocketBurn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TakeoffModelTest {
    static final Terrain2D FLAT_64 = (x, z) -> 64.0;
    static final double TOP = 319;

    @Test
    void steadyClimbStraightUpUnderARocket() {
        // straight up, gravity and drag against the push: about 1.48 blocks a tick, 29.6 m/s
        double[] v = { 0, 0, 0 };
        double moved = 0;
        for (int k = 0; k < 400; k++) {
            FlightModel3D.physicsStep(0, -90, v);
            moved = v[1];
            TakeoffModel.boost(0, -90, v);
        }
        assertEquals(1.4796, moved, 1e-3);
    }

    @Test
    void leaningTo70ClimbsAboutAsFastAsStraightUp() {
        double up = steadyClimb(-90), lean = steadyClimb(TakeoffModel.CLIMB_PITCH);
        assertTrue(lean > up * 0.99, "70 deg climbs at least 99% as fast: " + lean + " vs " + up);
    }

    static double steadyClimb(double pitch) {
        double[] v = { 0, 0, 0 };
        double moved = 0;
        for (int k = 0; k < 400; k++) {
            FlightModel3D.physicsStep(0, pitch, v);
            moved = v[1];
            TakeoffModel.boost(0, pitch, v);
        }
        return moved;
    }

    @Test
    void fromFlatGroundReachesTheCruiseWithSevenOrEightRockets() {
        TakeoffModel.Plan p = TakeoffModel.fromGround(0.5, 64, 0.5, 0, 350, 27, FLAT_64, TOP, 3000);
        assertTrue(p.reachesCruise());
        assertTrue(p.rockets() >= 7 && p.rockets() <= 8, "rockets " + p.rockets());
        // after the hand-over the path settles into the hold, never far below the cruise
        double low = Double.MAX_VALUE;
        for (int i = p.count() - 600; i < p.count(); i++) low = Math.min(low, p.ys()[i]);
        assertTrue(low >= 350 - TakeoffModel.HOLD_BAND, "low " + low);
    }

    @Test
    void shortestBurnsNeedAtLeastAsManyRockets() {
        TakeoffModel.Plan shortest = TakeoffModel.fromGround(0.5, 64, 0.5, 0, 350, RocketBurn.minTicks(1), FLAT_64, TOP, 10);
        TakeoffModel.Plan mean = TakeoffModel.fromGround(0.5, 64, 0.5, 0, 350, (int) Math.round(RocketBurn.meanTicks(1)), FLAT_64, TOP, 10);
        assertTrue(shortest.reachesCruise() && mean.reachesCruise());
        assertTrue(shortest.rockets() >= mean.rockets());
        TakeoffModel.Plan d3 = TakeoffModel.fromGround(0.5, 64, 0.5, 0, 350, RocketBurn.minTicks(3), FLAT_64, TOP, 10);
        assertTrue(d3.rockets() < shortest.rockets());
    }

    @Test
    void staysStraightUpPastACliffAheadAndClearsIt() {
        // a 100-block wall from 30 blocks ahead (+z at yaw 0)
        Terrain2D cliff = (x, z) -> z > 30 ? 164.0 : 64.0;
        TakeoffModel.Plan p = TakeoffModel.fromGround(0.5, 64, 0.5, 0, 350, 27, cliff, TOP, 3000);
        assertTrue(p.reachesCruise());
        for (int i = 3; i < p.count(); i++) {
            if (p.zs()[i] < 1.0) continue;   // still over the start column, which is clear above by the arming check
            double h = cliff.heightAt(p.xs()[i], p.zs()[i]);
            assertTrue(p.ys()[i] >= h + TakeoffModel.CLEARANCE - 1e-9, "tick " + i + " at y " + p.ys()[i] + " over " + h);
        }
        // and it went straight up for longer than over flat ground
        TakeoffModel.Plan flat = TakeoffModel.fromGround(0.5, 64, 0.5, 0, 350, 27, FLAT_64, TOP, 3000);
        assertTrue(firstForward(p) > firstForward(flat));
    }

    static int firstForward(TakeoffModel.Plan p) {
        for (int i = 0; i < p.count(); i++) if (p.zs()[i] > 1.0) return i;
        return p.count();
    }

    @Test
    void unknownTerrainKeepsTheClimbStraightUp() {
        Terrain2D unknown = (x, z) -> Double.NaN;
        assertFalse(TakeoffModel.leanClear(0.5, 80, 0.5, 0, 1.4, 0, -90, 0, unknown, TOP));
        assertTrue(TakeoffModel.leanClear(0.5, 330, 0.5, 0, 1.4, 0, -90, 0, unknown, TOP));
    }
}
