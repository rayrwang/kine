package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Walking without a route (RW, 2026-10-13: a short-range layer 2 only mode, "when layer 1 finds no route and the goal
 * is close"): layer 2's own search over Field.direct finds a way the graph has no move for, keeps Parkour's setting,
 * and the field's build time sets how near "near" is.
 */
public class Layer2DirectTest {
    static final int FLOOR = 20;

    /** A neo: from block A round the end of a wall to block B on its other side, nothing else (no bottom). Layer 1 has
     *  no move for it (the straight jump hits the wall); layer 2 alone makes it. */
    static TestWorld neo() {
        TestWorld w = TestWorld.blocks(-16, -16, 31, 31);
        w.set(0, FLOOR - 1, 0, TestWorld.STONE);
        w.set(2, FLOOR - 1, 0, TestWorld.STONE);
        w.fill(1, FLOOR - 1, -4, 1, FLOOR + 2, 0, TestWorld.STONE);   // the wall, its end at z = 0
        return w;
    }

    /** Whether the neo can be made at all, by scripted inputs. */
    @Test
    void theNeoCanBeMade() {
        ScriptedJump j = new ScriptedJump(Layer2Sim.grid(neo()), 2, FLOOR - 1, 0).search(0.5, FLOOR, 0.5, -90f, 0f, 5f, 8, 12);
        System.out.printf(Locale.ROOT, "neo by script: %d ways of %d, e.g. %s%n", j.ways, j.tried, j.best);
    }

    @Test
    void aNeoWithoutARoute() {
        Layer2Sim sim = new Layer2Sim(neo());
        sim.direct = true;
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, FLOOR, 0.5), -90f, 2, 0, 400);
        double lowest = Double.MAX_VALUE;
        for (double[] q : o.path()) lowest = Math.min(lowest, q[1]);
        System.out.printf(Locale.ROOT, "direct neo: went direct %b, arrived %b in %d ticks (%s), field %.1f ms, lowest feet %.2f%n",
            sim.wentDirect, o.arrived(), o.ticks(), o.why(), sim.directFieldNanos / 1e6, lowest);
        assertTrue(sim.wentDirect, "layer 1 has no route");
        // layer 2's own actions don't find this neo (12 ways in 13338 by script: theNeoCanBeMade): a jump found in the
        // copy (Recipe) to a floor joined to the goal makes it
        assertTrue(o.arrived(), "the neo made: " + o.why());
        assertTrue(sim.recipesFound > 0, "by a recipe");
    }

    @Test
    void parkourOffNoNeo() {
        Layer2Sim sim = new Layer2Sim(neo());
        sim.direct = true;
        sim.parkour = false;
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, FLOOR, 0.5), -90f, 2, 0, 200);
        double lowest = Double.MAX_VALUE;
        for (double[] q : o.path()) lowest = Math.min(lowest, q[1]);
        System.out.printf(Locale.ROOT, "direct neo, parkour off: arrived %b, lowest feet %.2f%n", o.arrived(), lowest);
        assertFalse(o.arrived(), "no jump across a gap with Parkour off");
        assertTrue(lowest > FLOOR - 0.5, "and no fall");
    }

    /** How far layer 2 alone gets: walks over ground with pillars (8%) and pits (4%), forced without a route, by the
     *  straight distance to the goal. */
    @Test
    void howFarLayer2AloneGets() {
        // 44 s in full: with KINE_MEASURE set; otherwise one distance and three walks
        boolean full = System.getenv("KINE_MEASURE") != null;
        for (int d : full ? new int[]{8, 16, 24, 32, 48} : new int[]{24}) {
            int arrived = 0, n = 0;
            long ticks = 0;
            for (int seed = 0; seed < (full ? 12 : 3); seed++) {
                java.util.Random r = new java.util.Random(seed * 31 + d);
                TestWorld w = TestWorld.blocks(-16, -16, 63, 63).floor(FLOOR - 1);
                for (int x = -16; x < 64; x++)
                    for (int z = -16; z < 64; z++) {
                        double p = r.nextDouble();
                        if (p < 0.08) w.fill(x, FLOOR, z, x, FLOOR + 1, z, TestWorld.STONE);
                        else if (p < 0.12) w.fill(x, FLOOR - 4, z, x, FLOOR - 1, z, Palette.AIR);
                    }
                int gx = (int) Math.round(d * 0.8), gz = (int) Math.round(d * 0.6);
                w.fill(-1, FLOOR - 1, -1, 1, FLOOR - 1, 1, TestWorld.STONE).fill(-1, FLOOR, -1, 1, FLOOR + 1, 1, Palette.AIR);
                w.fill(gx, FLOOR - 1, gz, gx, FLOOR - 1, gz, TestWorld.STONE).fill(gx, FLOOR, gz, gx, FLOOR + 1, gz, Palette.AIR);
                Layer2Sim sim = new Layer2Sim(w);
                sim.direct = true;
                sim.forceDirect = true;
                sim.parkour = false;
                Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, FLOOR, 0.5), -90f, gx, gz, d * 12);
                n++;
                if (o.arrived()) { arrived++; ticks += o.ticks(); }
            }
            System.out.printf(Locale.ROOT, "layer 2 alone, goal %d blocks away: arrived %d of %d, mean %.0f ticks%n", d, arrived, n,
                arrived == 0 ? 0.0 : (double) ticks / arrived);
        }
    }

    /** The field's build time against the distance: what "near" can be. */
    @Test
    void howNearIsNear() {
        TestWorld w = TestWorld.blocks(-16, -16, 79, 79).floor(FLOOR - 1);
        java.util.Random r = new java.util.Random(3);
        for (int x = -16; x < 80; x++)
            for (int z = -16; z < 80; z++)
                if (r.nextDouble() < 0.08) w.fill(x, FLOOR, z, x, FLOOR + 1, z, TestWorld.STONE);
        Costs c = Costs.layer2();
        w.fill(0, FLOOR, 0, 0, FLOOR + 1, 0, Palette.AIR);
        for (int d : new int[]{8, 16, 24, 32, 48, 64}) {
            int gx = (int) Math.round(d * 0.8), gz = (int) Math.round(d * 0.6);
            w.fill(gx, FLOOR, gz, gx, FLOOR + 1, gz, Palette.AIR);
            View v = w.view();
            long start = v.nodeAt(0, FLOOR, 0);
            long[] path = {start, View.node(gx, gz, 0)};
            Field.direct(v, c, true, true, false, 1, path, gx, gz, Double.NaN);   // warm
            long best = Long.MAX_VALUE;
            int n = 0;
            for (int k = 0; k < 5; k++) {
                Field f = Field.direct(v, c, true, true, false, 1, path, gx, gz, Double.NaN);
                best = Math.min(best, f.buildNanos);
                n = f.size();
            }
            System.out.printf(Locale.ROOT, "direct field: goal %d blocks away, %d floors, built in %.1f ms%n", d, n, best / 1e6);
        }
    }
}
