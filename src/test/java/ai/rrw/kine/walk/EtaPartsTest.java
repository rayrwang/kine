package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Parts of the ETA measured wrong in lockstep (2026-10-13): a sea's swim timed on the grid's way, and a sprint that
 *  hunger stopped. */
public class EtaPartsTest {
    static PathSearch.Result sea(TestWorld w, int gx, int gz) {
        View v = w.view();
        return new PathSearch(v, Costs.layer2()).run(v.nodeAt(0, 31, 0), gx, gz, 1.0, Long.MAX_VALUE, 2_000_000, false);
    }

    static TestWorld seaWorld() {
        TestWorld w = TestWorld.blocks(-16, -16, 95, 95);
        w.fill(-16, 26, -16, 95, 26, 95, TestWorld.STONE);
        w.fill(-16, 27, -16, 95, 30, 95, TestWorld.WATER);
        w.fill(-1, 27, -1, 1, 30, 1, TestWorld.STONE);
        return w;
    }

    @Test
    void aSwimIsTimedOnTheStraightLine() {
        TestWorld w = seaWorld();
        PathSearch.Result r = sea(w, 80, 46);   // 30 degrees: the grid's way 6% longer than the straight line
        assertTrue(r.found);
        View v = w.view();
        Route route = new Tighten(v, Costs.layer2(), 0.2, true).route(r, 80, 46);
        TimeProfile p = TimeProfile.of(v, Costs.layer2(), true, true, false, r, route, 80, 46);
        double straight = Math.hypot(80, 46), perBlock = p.total / straight;
        System.out.printf(java.util.Locale.ROOT, "sea swim: %.1f ticks for %.1f blocks straight, %.3f a block (swim rate %.2f)%n",
            p.total, straight, perBlock, Costs.layer2().water);
        assertEquals(Costs.layer2().water, perBlock, 0.12, "planned at the swim rate on the straight line");
        // progress on the straight line counts in full: at each share of the way, that share of the time is used
        double worst = 0, hint = -1;
        for (double f = 0.1; f < 0.95; f += 0.1) {
            double px = 0.5 + 80 * f, pz = 0.5 + 46 * f;
            hint = p.locate(px, pz, hint);
            double used = 1 - p.ticksLeft(hint) / p.total;
            worst = Math.max(worst, Math.abs(used - f));
        }
        System.out.printf(java.util.Locale.ROOT, "sea progress: worst share off %.3f%n", worst);
        assertTrue(worst < 0.03, "progress along the straight line off by " + worst);
    }

    @Test
    void hungerThatStoppedTheSprintSlowsTheRest() {
        TestWorld w = TestWorld.blocks(-16, -16, 111, 15).floor(10);
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, Costs.layer2()).run(v.nodeAt(0, 11, 0), 100, 0, 1.0, Long.MAX_VALUE, 2_000_000, false);
        Route route = new Tighten(v, Costs.layer2(), 0.2, true).route(r, 100, 0);
        TimeProfile p = TimeProfile.of(v, Costs.layer2(), true, true, false, r, route, 100, 0);
        Eta e = new Eta();
        e.onPlan(p, route);
        e.tick(0.5, 0.5, true, true);
        double sprinting = e.rawSeconds(20, 5f, true, false), stopped = e.rawSeconds(6, 0f, false, false);
        System.out.printf(java.util.Locale.ROOT, "hunger: %.1f s sprinting, %.1f s once it stopped (factor %.2f)%n",
            sprinting, stopped, p.noSprintFactor);
        assertEquals(sprinting * p.noSprintFactor, stopped, sprinting * 0.05, "the rest at the walking rate");
    }
}
