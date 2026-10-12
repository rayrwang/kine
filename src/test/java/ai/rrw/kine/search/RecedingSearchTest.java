package ai.rrw.kine.search;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** The search on a toy model: a point on a line pushed left, right or not at all, to reach a goal and stop there. */
class RecedingSearchTest {
    static final class Point {
        double x, v;
        Point(double x, double v) { this.x = x; this.v = v; }
    }

    /** Actions 0 none, 1 push +0.1, 2 push -0.1; drag 0.8; cost |x - goal| a tick; a random shove when noisy. */
    static final class Line implements Model<Point> {
        final double goal, noise, wall;
        Line(double goal, double noise, double wall) { this.goal = goal; this.noise = noise; this.wall = wall; }
        public Point copy(Point p) { return new Point(p.x, p.v); }
        public int actionCount() { return 3; }
        public double step(Point p, int a, int t, SplittableRandom rnd) {
            p.v = (p.v + (a == 1 ? 0.1 : a == 2 ? -0.1 : 0.0) + (noise > 0 ? (rnd.nextDouble() - 0.5) * noise : 0)) * 0.8;
            p.x += p.v;
            return Math.abs(p.x - goal);
        }
        public double estimate(Point p) { return Math.abs(p.x - goal) * 5 + Math.abs(p.v) * 10; }
        public boolean forbidden(Point p) { return p.x > wall; }
    }

    static RecedingSearch.Effort effort() {
        RecedingSearch.Effort e = new RecedingSearch.Effort();
        e.beam = 4; e.depth = 5; e.hold = 3; e.draws = 1; e.budgetNanos = 1_000_000_000L;
        return e;
    }

    @Test
    void pushesTowardTheGoal() {
        RecedingSearch<Point> s = new RecedingSearch<>(new Line(5, 0, 100));
        RecedingSearch.Result r = s.plan(new Point(0, 0), null, 0, effort(), 1);
        assertNotNull(r.plan());
        assertEquals(15, r.plan().length);
        assertEquals(1, r.plan()[0], "push right first");
        r = new RecedingSearch<>(new Line(-5, 0, 100)).plan(new Point(0, 0), null, 0, effort(), 1);
        assertEquals(2, r.plan()[0], "push left first");
    }

    @Test
    void receding() {
        Line m = new Line(3, 0, 100);
        RecedingSearch<Point> s = new RecedingSearch<>(m);
        Point p = new Point(0, 0);
        int[] plan = null;
        for (int tick = 0; tick < 80; tick++) {
            RecedingSearch.Result r = s.plan(p, plan, 1, effort(), tick);
            plan = r.plan();
            m.step(p, plan[0], 0, new SplittableRandom(0));
        }
        assertEquals(3, p.x, 0.25, "settles at the goal");
        assertEquals(0, p.v, 0.05);
    }

    @Test
    void neverEntersAForbiddenWorld() {
        RecedingSearch<Point> s = new RecedingSearch<>(new Line(10, 0, 2.0));
        Point p = new Point(0, 0);
        int[] plan = null;
        Line m = new Line(10, 0, 2.0);
        for (int tick = 0; tick < 60; tick++) {
            RecedingSearch.Result r = s.plan(p, plan, 1, effort(), tick);
            assertNotNull(r.plan());
            plan = r.plan();
            m.step(p, plan[0], 0, new SplittableRandom(0));
            assertTrue(p.x <= 2.0, "x " + p.x);
        }
    }

    @Test
    void thePreviousPlanIsKeptWhenNothingBetterTurnsUp() {
        RecedingSearch<Point> s = new RecedingSearch<>(new Line(5, 0, 100));
        RecedingSearch.Result r1 = s.plan(new Point(0, 0), null, 0, effort(), 1);
        // replanning from the same world with the same plan, unshifted: nothing can beat it, so it is the answer
        RecedingSearch.Result r2 = s.plan(new Point(0, 0), r1.plan(), 0, effort(), 1);
        assertArrayEquals(r1.plan(), r2.plan());
        assertTrue(r2.score() <= r1.score() + 1e-12);
    }

    @Test
    void drawsAndWorstCase() {
        RecedingSearch.Effort e = effort();
        e.draws = 5;
        e.worst = true;
        RecedingSearch.Result r = new RecedingSearch<>(new Line(5, 0.3, 100)).plan(new Point(0, 0), null, 0, e, 7);
        assertNotNull(r.plan());
        RecedingSearch.Effort m = e.copy();
        m.worst = false;
        RecedingSearch.Result mean = new RecedingSearch<>(new Line(5, 0.3, 100)).plan(new Point(0, 0), null, 0, m, 7);
        assertTrue(r.score() >= mean.score() - 1e-9, "the worst draw scores no better than the mean");
    }

    @Test
    void stopsAtTheBudget() {
        RecedingSearch.Effort e = effort();
        e.beam = 50; e.depth = 30; e.draws = 20; e.budgetNanos = 3_000_000L;
        long t0 = System.nanoTime();
        RecedingSearch.Result r = new RecedingSearch<>(new Line(5, 0.1, 100)).plan(new Point(0, 0), null, 0, e, 3);
        long took = System.nanoTime() - t0;
        assertNotNull(r.plan());
        assertTrue(r.stats().timedOut());
        assertTrue(took < 60_000_000L, "took " + took / 1e6 + " ms");
        assertEquals(e.horizon(), r.plan().length);
    }

    @Test
    void tunerWidensWhenThereIsTimeAndNarrowsWhenNot() {
        RecedingSearch.Effort e = effort();
        e.beam = 4; e.draws = 2; e.budgetNanos = 10_000_000L;
        EffortTuner t = new EffortTuner(e, 2, 12, 1, 4);
        for (int i = 0; i < 100; i++) t.record(new RecedingSearch.Stats(1_000_000L, 10, 100, 5, false, false));
        assertTrue(e.beam > 4 || e.draws > 2, "wider with time to spare");
        int beam = e.beam, draws = e.draws;
        for (int i = 0; i < 10; i++) t.record(new RecedingSearch.Stats(20_000_000L, 10, 100, 2, true, false));
        assertTrue(e.beam < beam || e.draws < draws, "narrower when over budget");
    }
}
