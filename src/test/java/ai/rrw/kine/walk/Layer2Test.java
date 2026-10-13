package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 2 closed loop in made-up worlds (Layer2Sim): it arrives, and what it does on the way comes out of the search
 * over the movement copy, nothing scripted: sprint-jumping on open ground, swimming across and climbing out onto a bank
 * level with the water, jumping a gap that layer 1 walks round.
 */
public class Layer2Test {
    static void print(String name, Layer2Sim.Outcome o) {
        System.out.printf("layer2 %s: arrived=%b ticks=%d jumps=%d replans=%d meanMs=%.2f worstMs=%.2f %s%n",
            name, o.arrived(), o.ticks(), o.jumps(), o.replans(), o.meanMs(), o.worstMs(), o.why());
    }

    @Test
    void sprintJumpsOnOpenGround() {
        TestWorld w = TestWorld.blocks(-8, -8, 63, 23).floor(10);
        Layer2Sim sim = new Layer2Sim(w);
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 40, 0, 400);
        print("open", o);
        assertTrue(o.arrived(), o.why());
        // sprinting the 40 blocks from rest takes about 40 / 0.2806 + 6 = 149 ticks; sprint-jumping about 120
        assertTrue(o.ticks() < 140, "ticks " + o.ticks());
        assertTrue(o.jumps() >= 5, "jumps " + o.jumps());
    }

    static void trace(Layer2Sim.Outcome o) {
        StringBuilder sb = new StringBuilder("  trace:");
        for (int i = 0; i < o.path().size(); i += 4) {
            double[] p = o.path().get(i);
            int f = (int) p[3], k = (int) p[4];
            sb.append(String.format(java.util.Locale.ROOT, " %d:%.1f,%.2f%s%s%s", i, p[0], p[1], (f & 1) != 0 ? "w" : "", (f & 2) != 0 ? "S" : "",
                (k & MoveState.JUMP) != 0 ? "j" : ""));
        }
        System.out.println(sb);
    }

    static String moves(PathSearch.Result r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < r.moves.length; i++) if (r.moves[i] != Graph.WALK) sb.append(Graph.NAMES[r.moves[i]]).append('@').append(View.nodeX(r.path[i])).append(',').append(View.nodeZ(r.path[i])).append(' ');
        return sb.toString();
    }

    /** A pool three deep, its top level with the ground both sides: in, across and out onto the far bank. */
    @Test
    void swimsAcrossAndClimbsOutOntoALevelBank() {
        TestWorld w = TestWorld.blocks(-8, -16, 47, 15).floor(10);
        w.fill(5, 8, -16, 14, 10, 15, TestWorld.WATER).fill(5, 7, -16, 14, 7, 15, TestWorld.STONE);
        Layer2Sim sim = new Layer2Sim(w);
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 22, 0, 600);
        print("pool", o);
        trace(o);
        System.out.println("  route moves: " + moves(sim.lastRoute));
        assertTrue(o.arrived(), o.why());
        // it sprint swims (found, not scripted): 87 ticks measured; W and jump along the top would take about 120
        assertTrue(o.path().stream().anyMatch(q -> ((int) q[3] & 2) != 0), "sprint swimming");
        assertTrue(o.ticks() < 105, "ticks " + o.ticks());
    }

    /** Out of a one-deep channel onto a bank level with the water's top (the ground): the jump out of water. */
    @Test
    void wadesOutOfAShallowChannel() {
        TestWorld w = TestWorld.blocks(-8, -16, 47, 15).floor(10);
        w.fill(5, 10, -16, 14, 10, 15, TestWorld.WATER).fill(5, 9, -16, 14, 9, 15, TestWorld.STONE);
        Layer2Sim sim = new Layer2Sim(w);
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 22, 0, 600);
        print("channel", o);
        trace(o);
        System.out.println("  route moves: " + moves(sim.lastRoute));
        assertTrue(o.arrived(), o.why());
        assertTrue(o.ticks() < 140, "ticks " + o.ticks());
    }

    /** A ravine three wide and deep across the way, its ends far to the sides: layer 2 jumps it. */
    @Test
    void jumpsAGap() {
        TestWorld w = TestWorld.blocks(-8, -40, 47, 39).floor(10);
        w.fill(10, 1, -36, 12, 10, 36, Palette.AIR);
        Layer2Sim sim = new Layer2Sim(w);
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 22, 0, 400);
        print("gap", o);
        System.out.println("  route moves: " + moves(sim.lastRoute));
        assertTrue(o.arrived(), o.why());
        assertTrue(o.ticks() < 120, "ticks " + o.ticks());
    }

    /** An island in a moat whose water's top is a block below both banks (the game can't climb out of deep water
     *  there): layer 1 plans the way out by the ladder on the island's west wall, and layer 2 swims to it and climbs. */
    @Test
    void outOfAMoatByItsLadder() {
        TestWorld w = TestWorld.blocks(-8, -16, 47, 15).floor(10);
        w.fill(6, 7, -12, 28, 7, 12, TestWorld.STONE).fill(6, 8, -12, 28, 9, 12, TestWorld.WATER)
            .fill(6, 10, -12, 28, 10, 12, Palette.AIR);
        w.fill(10, 7, -8, 24, 10, 8, TestWorld.STONE);
        w.fill(9, 9, 4, 9, 10, 4, TestWorld.LADDER_EAST);
        Layer2Sim sim = new Layer2Sim(w);
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 11, 0.5), -90f, 17, 0, 800);
        print("moat", o);
        System.out.println("  route moves: " + moves(sim.lastRoute));
        assertTrue(o.arrived(), o.why());
        assertTrue(moves(sim.lastRoute).contains("climb-on"), "out by the ladder");
    }

    /** A cliff 15 high, the destination 28 blocks out on the ground below and no way down on foot: with elytra hops
     *  layer 1 plans a glide off the edge and layer 2 finds how to make it (the jump, opening the glider, the pitch). */
    @Test
    void glidesOffACliffWithElytraHops() {
        TestWorld w = TestWorld.blocks(-8, -8, 47, 7).floor(10);
        w.fill(-8, 11, -8, 2, 25, 7, TestWorld.STONE);
        Layer2Sim off = new Layer2Sim(w);
        Layer2Sim.Outcome o0 = off.walk(Layer2Sim.standing(0.5, 26, 0.5), -90f, 30, 0, 400);
        assertFalse(o0.arrived(), "no way down on foot");
        Layer2Sim sim = new Layer2Sim(w);
        sim.glides = true;
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, 26, 0.5), -90f, 30, 0, 400);
        print("cliffGlide", o);
        System.out.println("  route moves: " + (sim.lastRoute == null ? "none" : moves(sim.lastRoute)) + " glideTicks=" + sim.glideTicks);
        assertTrue(o.arrived(), o.why());
        assertTrue(sim.glideTicks > 0, "glided");
    }
}
