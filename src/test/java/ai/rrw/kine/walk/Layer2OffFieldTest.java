package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A jump over a gap on a long way round (RunModel.OffField, 2026-10-16): the goal is 6 blocks from the gap in a straight
 * line, but the only way there runs some 160 blocks round, so the field's values at the gap (the time left) are far
 * more than the off-field cost (OFF_FIELD and the straight way). Under the gap, 2 down, a pool with no way out (off
 * the field's values): water, so a fall into it doesn't hurt (as the pool by leg 1). In the air over the gap the run's start has no floor of the field under it; with the floor from
 * the start's value alone (as before) every off-field end cost less than landing on the far side, and the run gave
 * the jump up for the pool (the Parkour Spiral's leg 18 and the pool gap by leg 1, live). With the value where
 * the player last stood, it lands across and walks on to the goal.
 */
public class Layer2OffFieldTest {
    static final int FLOOR = 20;

    static TestWorld world() {
        TestWorld w = TestWorld.blocks(-8, -4, 95, 12);
        w.fill(-4, FLOOR - 1, 0, 3, FLOOR - 1, 0, TestWorld.STONE);    // the start's ledge, its take-off at x 3
        w.fill(4, FLOOR - 5, 0, 6, FLOOR - 5, 0, TestWorld.STONE);     // the pit: water 2 deep, its top 2 down, no way on
        w.fill(4, FLOOR - 4, 0, 6, FLOOR - 3, 0, TestWorld.WATER);
        w.fill(7, FLOOR - 1, 0, 80, FLOOR - 1, 0, TestWorld.STONE);    // across the 3-block gap, on to x 80
        w.fill(80, FLOOR - 1, 0, 80, FLOOR - 1, 6, TestWorld.STONE);   // round
        w.fill(0, FLOOR - 1, 6, 80, FLOOR - 1, 6, TestWorld.STONE);    // and back to the goal at (0, 6)
        return w;
    }

    static Layer2Sim.Outcome walk(boolean remember) {
        Layer2Sim sim = new Layer2Sim(world());
        sim.offField.remember = remember;
        return sim.walk(Layer2Sim.standing(-3.5, FLOOR, 0.5), -90f, 0, 6, 1500);
    }

    static double lowest(Layer2Sim.Outcome o) {
        double low = Double.MAX_VALUE;
        for (double[] p : o.path()) low = Math.min(low, p[1]);
        return low;
    }

    @Test
    void aRunInTheAirOverAGapKeepsToTheFarSide() {
        Layer2Sim.Outcome before = walk(false), now = walk(true);
        System.out.printf(Locale.ROOT, "off-field floor: start's value only: arrived=%b ticks=%d lowest=%.2f %s; with the last value: arrived=%b ticks=%d lowest=%.2f %s%n",
            before.arrived(), before.ticks(), lowest(before), before.why(), now.arrived(), now.ticks(), lowest(now), now.why());
        assertTrue(lowest(now) > FLOOR - 1.0, "never down in the pool: lowest " + lowest(now));
        assertTrue(now.arrived(), "arrives: " + now.why());
    }
}
