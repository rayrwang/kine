package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 1's costs for layer 2 (Costs.layer2) against layer 2's own runs: layer 2 walks closed loop on the movement copy
 * (Layer2Sim) over test courses, and the times it takes, per block once going (open ground, a sprint swim, wading,
 * soul sand, a ladder up and down) and extra per feature (a step up, a drop of 3, a 90 degree corner), must match the
 * table. This is where the table's numbers come from (RW: layer 2 generates the data that feeds back into layer 1's
 * costs); a change to layer 2 that makes it faster or slower shows here, and the table is refitted from the printout.
 */
public class CostFitTest {
    static int crossX(Layer2Sim.Outcome o, double x) {
        for (int i = 0; i < o.path().size(); i++) if (o.path().get(i)[0] >= x) return i + 1;
        return -1;
    }

    static int crossY(Layer2Sim.Outcome o, double y, boolean up) {
        for (int i = 0; i < o.path().size(); i++) if (up ? o.path().get(i)[1] >= y : o.path().get(i)[1] <= y) return i + 1;
        return -1;
    }

    static Layer2Sim.Outcome run(String name, TestWorld w, double sx, double sy, double sz, int gx, int gz, int max) {
        Layer2Sim sim = new Layer2Sim(w);
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(sx, sy, sz), -90f, gx, gz, max);
        System.out.printf(Locale.ROOT, "fit %s: arrived=%b ticks=%d jumps=%d replanMsMean=%.2f%n", name, o.arrived(), o.ticks(),
            o.jumps(), o.meanMs());
        assertTrue(o.arrived(), name + ": " + o.why());
        return o;
    }

    static void near(String what, double measured, double table, double tol) {
        System.out.printf(Locale.ROOT, "fit %s: measured %.3f, table %.3f%n", what, measured, table);
        assertEquals(table, measured, tol, what);
    }

    @Test
    void theLayer2TableMatchesLayer2sRuns() {
        Costs c = Costs.layer2();
        // open ground: the rate once running, and from rest
        TestWorld flat = TestWorld.blocks(-8, -8, 127, 7).floor(10);
        Layer2Sim.Outcome f = run("flat", flat, 0.5, 11, 0.5, 110, 0, 800);
        near("flat ticks a block", (crossX(f, 90) - crossX(f, 20)) / 70.0, c.sprint, 0.06);
        assertTrue(c.fastest <= c.sprint + 1e-9, "the estimate's rate never above the cheapest move's");

        // a 90 degree corner round a block: the time against the flat run's over the same distance
        TestWorld turn = TestWorld.blocks(-8, -48, 47, 15).floor(10);
        turn.fill(-8, 11, -48, 27, 13, -2, TestWorld.STONE);
        Layer2Sim.Outcome t = run("turn90", turn, 0.5, 11, 0.5, 30, -30, 600);
        double legs = Math.hypot(27.7 - 0.5, -1.7 - 0.5) + Math.hypot(30.5 - 27.7, -29.5 + 1.7);
        near("corner extra", t.ticks() - crossX(f, legs + 0.5), c.turn, 5.0);

        // terraces up one, and down three: 8 steps, one every 6 blocks
        for (int h : new int[]{1, -3}) {
            TestWorld st = TestWorld.blocks(-8, -8, 79, 7).floor(5);
            st.fill(-8, 6, -8, 9, 30, 7, TestWorld.STONE);
            for (int k = 0; k < 8; k++) {
                int x0 = 10 + 6 * k, x1 = k == 7 ? 79 : x0 + 5;
                st.fill(x0, 6, -8, x1, 30 + h * (k + 1), 7, TestWorld.STONE);
            }
            Layer2Sim.Outcome o = run("steps" + h, st, 0.5, 31, 0.5, 70, 0, 800);
            double extra = (o.ticks() - crossX(f, 70.5)) / 8.0;
            if (h > 0) near("step up extra", extra, c.jumpPenalty, 1.0);
            else near("drop 3 extra", extra, Math.max(0, Costs.fallTicks(3) - c.fallHidden), 1.5);
        }

        // a pool 60 long and 3 deep, a channel one deep, soul sand: across the whole width, the rate in the middle
        TestWorld pool = TestWorld.blocks(-8, -8, 95, 7).floor(10);
        pool.fill(10, 8, -16, 69, 10, 15, TestWorld.WATER).fill(10, 7, -16, 69, 7, 15, TestWorld.STONE);
        Layer2Sim.Outcome p = run("swim", pool, 0.5, 11, 0.5, 80, 0, 1500);
        near("swim ticks a block", (crossX(p, 60) - crossX(p, 20)) / 40.0, c.water, 0.5);
        // into and out of the water: the whole crossing against flat ground and the swim's rate
        near("water in and out extra", p.ticks() - (crossX(f, 80.5) + 60 * (c.water - c.sprint)), c.waterPenalty, 8.0);

        TestWorld ch = TestWorld.blocks(-8, -8, 95, 7).floor(10);
        ch.fill(10, 10, -16, 69, 10, 15, TestWorld.WATER).fill(10, 9, -16, 69, 9, 15, TestWorld.STONE);
        Layer2Sim.Outcome w = run("wade", ch, 0.5, 11, 0.5, 80, 0, 1500);
        near("wade ticks a block", (crossX(w, 60) - crossX(w, 20)) / 40.0, c.wade, 0.8);

        TestWorld ss = TestWorld.blocks(-8, -8, 95, 7).floor(10);
        ss.fill(10, 10, -16, 69, 10, 15, TestWorld.SOUL_SAND);
        Layer2Sim.Outcome so = run("soulsand", ss, 0.5, 11, 0.5, 80, 0, 1500);
        near("soul sand ticks a block", (crossX(so, 60) - crossX(so, 20)) / 40.0, c.sprint * c.slowFactor, 0.6);

        // a ladder 16 up a wall, the goal on top; and back down
        TestWorld lad = TestWorld.blocks(-8, -8, 31, 7).floor(10);
        lad.fill(10, 11, -8, 31, 26, 7, TestWorld.STONE);
        lad.fill(9, 11, 0, 9, 26, 0, TestWorld.LADDER_WEST);
        Layer2Sim.Outcome l = run("ladderUp", lad, 0.5, 11, 0.5, 15, 0, 800);
        near("ladder up ticks a block", (crossY(l, 24, true) - crossY(l, 13, true)) / 11.0, c.ladderUp, 1.3);
        Layer2Sim.Outcome ld = run("ladderDown", lad, 15.5, 27, 0.5, 0, 0, 800);
        near("ladder down ticks a block", (crossY(ld, 13, false) - crossY(ld, 24, false)) / 11.0, c.ladderDown, 1.1);
    }
}
