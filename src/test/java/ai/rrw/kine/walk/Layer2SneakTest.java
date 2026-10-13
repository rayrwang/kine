package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the sneaking gaits add (RW, 2026-10-12: "to sprint in wter you acutaly nee dto go a bit under hte surface first
 * so shift or do ntohign, then sporint"): layer 2 with and without them, from floating at the top of a long pool to the
 * first tick of a sprint swim, the crossing, and the planning time per replan; and the pool and channel crossings.
 */
public class Layer2SneakTest {
    static int firstSwim(Layer2Sim.Outcome o) {
        for (int i = 0; i < o.path().size(); i++) if (((int) o.path().get(i)[3] & 2) != 0) return i + 1;
        return -1;
    }

    static Layer2Sim.Outcome run(String name, TestWorld w, MoveState s, int gx, int gaits) {
        Layer2Sim sim = new Layer2Sim(w);
        sim.gaits = gaits;
        Layer2Sim.Outcome o = sim.walk(s, -90f, gx, 0, 1200);
        System.out.printf(Locale.ROOT, "sneak %s gaits=%d: arrived=%b ticks=%d firstSprintSwim=%d replanMsMean=%.2f worst=%.2f%n",
            name, gaits, o.arrived(), o.ticks(), firstSwim(o), o.meanMs(), o.worstMs());
        assertTrue(o.arrived(), name + ": " + o.why());
        return o;
    }

    static MoveState floating() {
        MoveState s = new MoveState();
        s.x = 2.5; s.y = 10.3; s.z = 0.5;
        s.inWater = true;
        s.waterHeight = 10.0 + 8.0 / 9.0 - 10.3;
        return s;
    }

    @Test
    void fromTheTopToASprintSwimAndAcross() {
        // a pool 4 deep from x -8 to 59, the bank level with its top beyond; the walk starts floating at the top
        TestWorld w = TestWorld.blocks(-8, -16, 79, 15).floor(10);
        w.fill(-8, 7, -16, 59, 10, 15, TestWorld.WATER).fill(-8, 6, -16, 59, 6, 15, TestWorld.STONE);
        Layer2Sim.Outcome without = run("pool60", w, floating(), 66, RunModel.BASE_GAITS);
        Layer2Sim.Outcome with = run("pool60", w, floating(), 66, RunModel.GAITS);
        assertTrue(with.ticks() <= without.ticks() + 5, "sneaking never costs much: " + with.ticks() + " vs " + without.ticks());

        // the earlier scenes: into a pool 3 deep from the bank and out; a channel one deep
        TestWorld pool = TestWorld.blocks(-8, -16, 47, 15).floor(10);
        pool.fill(5, 8, -16, 14, 10, 15, TestWorld.WATER).fill(5, 7, -16, 14, 7, 15, TestWorld.STONE);
        run("pool10", pool, Layer2Sim.standing(0.5, 11, 0.5), 22, RunModel.BASE_GAITS);
        run("pool10", pool, Layer2Sim.standing(0.5, 11, 0.5), 22, RunModel.GAITS);
        TestWorld ch = TestWorld.blocks(-8, -16, 47, 15).floor(10);
        ch.fill(5, 10, -16, 14, 10, 15, TestWorld.WATER).fill(5, 9, -16, 14, 9, 15, TestWorld.STONE);
        run("channel", ch, Layer2Sim.standing(0.5, 11, 0.5), 22, RunModel.BASE_GAITS);
        run("channel", ch, Layer2Sim.standing(0.5, 11, 0.5), 22, RunModel.GAITS);
        TestWorld flat = TestWorld.blocks(-8, -8, 63, 23).floor(10);
        run("open", flat, Layer2Sim.standing(0.5, 11, 0.5), 40, RunModel.BASE_GAITS);
        run("open", flat, Layer2Sim.standing(0.5, 11, 0.5), 40, RunModel.GAITS);
    }
}
