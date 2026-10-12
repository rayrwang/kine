package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The rails' shape: one smooth centreline near the route, rails that turn gradually through a bend without folding,
 * ramp over stairs a little at a time, and never dip under the ground they lie on.
 */
public class RailPathTest {
    static RailPath rails(TestWorld w, int sx, int sz, int gx, int gz) {
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, new Costs()).run(v.nodeAt(sx, 11, sz), gx, gz, 1.0, Long.MAX_VALUE, 1_000_000, false);
        assertTrue(r.found, r.reason);
        Route route = new Tighten(v, new Costs(), 0.2, true).route(r, gx, gz);
        return new RailPath(v, route);
    }

    @Test
    void aSharpBendTurnsGraduallyWithoutFolding() {
        // a corridor 3 wide: east along z = 6..8, then north along x = 20..22: a 90 degree bend
        TestWorld w = blocks(0, 0, 31, 31).floor(10);
        w.fill(0, 11, 0, 31, 13, 31, STONE);
        w.fill(1, 11, 6, 22, 13, 8, Palette.AIR);
        w.fill(20, 11, 0, 22, 13, 8, Palette.AIR);
        RailPath rp = rails(w, 2, 7, 21, 1);
        assertTrue(rp.n > 40);
        double worstTurn = 0;
        for (int i = 1; i < rp.n; i++) {
            double cos = rp.ax[i] * rp.ax[i - 1] + rp.az[i] * rp.az[i - 1];
            worstTurn = Math.max(worstTurn, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos)))));
            // each rail moves forward along the route at every point: no folding back on the inside of the bend
            for (int side = -1; side <= 1; side += 2) {
                double[] off = side > 0 ? rp.leftOff : rp.rightOff;
                double x0 = rp.x[i - 1] + side * rp.ax[i - 1] * off[i - 1], z0 = rp.z[i - 1] + side * rp.az[i - 1] * off[i - 1];
                double x1 = rp.x[i] + side * rp.ax[i] * off[i], z1 = rp.z[i] + side * rp.az[i] * off[i];
                double tx = -rp.az[i], tz = rp.ax[i];   // the direction of travel (across turned back)
                assertTrue((x1 - x0) * tx + (z1 - z0) * tz > -1e-9, "rail " + side + " folds back at " + i);
            }
        }
        System.out.printf("KINEMEASURE rails bend: worst turn between neighbouring points %.1f deg over %d points%n", worstTurn, rp.n);
        // no rail point inside the corridor's walls (at body height above the floor)
        View v = w.view();
        Sweep probe = new Sweep(v, new Costs(), 0);
        double inner = RailPath.HALF;
        for (int i = 0; i < rp.n; i++)
            for (int side = -1; side <= 1; side += 2) {
                double[] off = side > 0 ? rp.leftOff : rp.rightOff;
                inner = Math.min(inner, off[i]);
                double px = rp.x[i] + side * rp.ax[i] * off[i], pz = rp.z[i] + side * rp.az[i] * off[i];
                assertTrue(probe.clear(px, pz, 0.04, 11.6, 12.5), "rail " + side + " in a wall at " + i + ": " + px + " " + pz);
            }
        System.out.printf("KINEMEASURE rails bend: closest a rail comes to the centreline %.2f%n", inner);
        assertTrue(worstTurn < 20, "turns in steps of " + worstTurn);
    }

    @Test
    void stairsRampAndTheRailsNeverDipUnderTheGround() {
        // stairs up three toward +x from x = 6, a landing, stairs down three from x = 14 (STAIR_EAST up, then blocks down)
        TestWorld w = blocks(0, 0, 31, 15).floor(10);
        w.fill(6, 11, 0, 6, 11, 15, STAIR_EAST);
        w.fill(7, 11, 0, 7, 11, 15, STONE).fill(7, 12, 0, 7, 12, 15, STAIR_EAST);
        w.fill(8, 11, 0, 8, 12, 15, STONE).fill(8, 13, 0, 8, 13, 15, STAIR_EAST);
        w.fill(9, 11, 0, 13, 13, 15, STONE);
        w.fill(14, 11, 0, 14, 12, 15, STONE).fill(15, 11, 0, 15, 11, 15, STONE);
        RailPath rp = rails(w, 1, 7, 24, 7);
        double worstStep = 0;
        View v = w.view();
        Sweep probe = new Sweep(v, new Costs(), 0);
        for (int i = 0; i < rp.n; i++) {
            if (i > 0) worstStep = Math.max(worstStep, Math.max(Math.abs(rp.leftY[i] - rp.leftY[i - 1]), Math.abs(rp.rightY[i] - rp.rightY[i - 1])));
            for (int side = -1; side <= 1; side += 2) {
                double[] off = side > 0 ? rp.leftOff : rp.rightOff;
                double px = rp.x[i] + side * rp.ax[i] * off[i], pz = rp.z[i] + side * rp.az[i] * off[i];
                double ry = side > 0 ? rp.leftY[i] : rp.rightY[i];
                double g = probe.ground(px, pz, 0.04, ry + 0.5, ry - 4);
                assertTrue(Double.isNaN(g) || ry >= g - 1e-9, "rail under the ground at " + i + ": " + ry + " < " + g);
            }
        }
        System.out.printf("KINEMEASURE rails stairs: largest rise between neighbouring points %.3f (points %.2f apart)%n", worstStep, RailPath.STEP);
        assertTrue(worstStep <= RailPath.SLOPE * RailPath.STEP * 1.6 + 1e-6, "steps of " + worstStep);
    }

    @Test
    void theCentrelineStaysOnTheRouteAndStraightRunsStayStraight() {
        TestWorld w = blocks(-4, -4, 60, 40).floor(10);
        RailPath rp = rails(w, 0, 0, 50, 20);
        // a straight route: the centreline is that line
        for (int i = 0; i < rp.n; i++) {
            double d = Math.abs((rp.x[i] - 0.5) * 20 - (rp.z[i] - 0.5) * 50) / Math.hypot(50, 20);
            assertTrue(d < 1e-6, "off the line by " + d);
        }
    }
}
