package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Air in layer 2's search (RW: "it swam below the surface and i gootok dmaange without the noticing or surfacing"),
 * closed loop in the copy: a lake 5 deep and 120 across, from the bank and from low air; with the air counted and
 * without (for the record), the drowning taken, the time, how often the eyes came out, and the share of ticks with
 * the eyes under. Layer 1's routes never hold a breath (its floors all have the head out of water), so a flooded
 * tunnel is refused.
 */
public class Layer2AirTest {
    record Stats(double drowned, int ticks, int surfacings, int eyesUnder, int swimTicks, double minAir) {}

    static Stats stats(Layer2Sim.Outcome o) {
        List<double[]> p = o.path();
        int surf = 0, under = 0, swim = 0;
        double minAir = 1e9;
        boolean wasUnder = false;
        for (double[] q : p) {
            int f = (int) q[3];
            boolean in = (f & 1) != 0, eyes = (f & 8) != 0;
            if (eyes) under++;
            if ((f & 2) != 0) swim++;
            if (in && wasUnder && !eyes) surf++;
            wasUnder = eyes;
            minAir = Math.min(minAir, q[5]);
        }
        double drowned = p.isEmpty() ? 0 : p.get(p.size() - 1)[6];
        return new Stats(drowned, o.ticks(), surf, under, swim, minAir);
    }

    static TestWorld lake() {
        TestWorld w = TestWorld.blocks(-8, -16, 143, 15).floor(10);
        w.fill(5, 6, -16, 124, 10, 15, TestWorld.WATER).fill(5, 5, -16, 124, 5, 15, TestWorld.STONE);
        return w;
    }

    static Stats run(String name, TestWorld w, MoveState start, int gx, boolean aware) {
        Layer2Sim sim = new Layer2Sim(w);
        sim.airAware = aware;
        Layer2Sim.Outcome o = sim.walk(start, -90f, gx, 0, 2000);
        Stats st = stats(o);
        System.out.printf(Locale.ROOT, "air %s aware=%b: arrived=%b ticks=%d drowned=%.0f surfacings=%d eyesUnder=%d swimming=%d minAir=%.0f meanMs=%.1f%n",
            name, aware, o.arrived(), st.ticks(), st.drowned(), st.surfacings(), st.eyesUnder(), st.swimTicks(), st.minAir(), o.meanMs());
        assertTrue(o.arrived(), name + ": " + o.why());
        return st;
    }

    @Test
    void crossesALongLakeWithoutDrowning() {
        // the old way (no air counted, the swim-up pitch 25 up): RW's drowning, in the copy
        float keep = RunModel.swimUpPitch;
        Stats before;
        try {
            RunModel.swimUpPitch = -25f;
            before = run("lake120 old", lake(), Layer2Sim.standing(0.5, 11, 0.5), 130, false);
        } finally {
            RunModel.swimUpPitch = keep;
        }
        Stats off = run("lake120", lake(), Layer2Sim.standing(0.5, 11, 0.5), 130, false);
        Stats on = run("lake120", lake(), Layer2Sim.standing(0.5, 11, 0.5), 130, true);
        assertTrue(before.drowned() > 0, "the old way drowns: " + before.drowned());
        assertEquals(0.0, on.drowned(), "no drowning with the air counted");
        assertTrue(on.ticks() <= before.ticks() + 20, "about as fast: " + on.ticks() + " vs " + before.ticks());
        assertTrue(on.eyesUnder() < 100, "mostly breathing: " + on.eyesUnder());
    }

    @Test
    void comesUpAtOnceWithLowAir() {
        // under water, the air nearly gone (40 of 300)
        MoveState s = new MoveState();
        s.x = 10.5; s.y = 8.6; s.z = 0.5; s.inWater = true; s.eyesInWater = true; s.air = 40;
        Stats off = run("lowAir", lake(), s.copy(), 60, false);
        Stats on = run("lowAir", lake(), s, 60, true);
        assertEquals(0.0, on.drowned(), "no drowning");
    }

    @Test
    void layer1RefusesAFloodedTunnel() {
        // two banks joined only by a flooded tunnel (no air in it) under a wall
        TestWorld w = TestWorld.blocks(-8, -16, 47, 15).floor(10);
        w.fill(10, 11, -16, 14, 20, 15, TestWorld.STONE);           // a wall across the whole width
        w.fill(6, 6, -1, 18, 10, 1, TestWorld.WATER);             // a tunnel under it, full of water
        w.fill(6, 5, -1, 18, 5, 1, TestWorld.STONE);
        w.fill(10, 11, -1, 14, 11, 1, TestWorld.STONE);           // its roof: the wall's foot
        View v = w.view();
        long start = v.nodeAt(0, 11, 0);
        PathSearch.Result r = new PathSearch(v, Costs.layer2()).gaps(true).run(start, 25, 0, 1.0, Long.MAX_VALUE, 2_000_000, false);
        System.out.println("air tunnel: found=" + r.found + " exhausted=" + r.exhausted + " reason=" + r.reason);
        assertFalse(r.found, "no route through water with no air over it");
    }
}
