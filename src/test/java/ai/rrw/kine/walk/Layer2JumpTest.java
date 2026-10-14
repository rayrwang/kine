package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each gap jump layer 1 now offers (Jumps), made by layer 2 in the movement copy: a take-off block with the run-up the
 * jump needs behind it, a landing block (dx, dz) away and dh up or down, and nothing else (no bottom). Layer 1 must plan
 * the jump (a GAP move) and layer 2 must arrive on the landing block. With KINE_MEASURE set, every jump of the table;
 * otherwise a few.
 */
public class Layer2JumpTest {
    static final int FLOOR = 20;

    /** The outcome of one jump: whether layer 1 planned it, whether layer 2 arrived, and in how many ticks. */
    record Outcome(int dx, int dz, int dh, int runUp, boolean planned, boolean arrived, int ticks, String why) {}

    static Outcome run(int dx, int dz, int dh, int runUp) {
        TestWorld w = TestWorld.blocks(-16, -16, 31, 31);
        Jumps.Jump j = new Jumps.Jump(dx, dz, dh, runUp);
        w.set(0, FLOOR - 1, 0, TestWorld.STONE);
        for (int i = 0; i < j.runway.length; i += 2) w.set(j.runway[i], FLOOR - 1, j.runway[i + 1], TestWorld.STONE);
        w.set(dx, FLOOR - 1 + dh, dz, TestWorld.STONE);
        // the start: the run-up's far end (or the take-off)
        double len = Math.hypot(dx, dz), ux = dx / len, uz = dz / len;
        double sx = 0.5 - ux * runUp, sz = 0.5 - uz * runUp;
        if (runUp > 0) {
            // stand on the runway column nearest that point
            int bx = (int) Math.floor(sx), bz = (int) Math.floor(sz);
            if (w.get(bx, FLOOR - 1, bz) != TestWorld.STONE) { sx = 0.5; sz = 0.5; }
            else { sx = bx + 0.5; sz = bz + 0.5; }
        }
        Layer2Sim sim = new Layer2Sim(w);
        float yaw = (float) Math.toDegrees(Math.atan2(-ux, uz));
        Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(sx, FLOOR, sz), yaw, dx, dz, 400);
        boolean planned = sim.lastRoute != null && Layer2Test.moves(sim.lastRoute).contains("gap");
        return new Outcome(dx, dz, dh, runUp, planned, o.arrived(), o.ticks(), o.why());
    }

    @Test
    void layer2MakesTheJumpsLayer1Offers() {
        boolean full = System.getenv("KINE_MEASURE") != null;
        int[][] cases = full ? Jumps.TABLE : new int[][]{{4, 0, 0, 0}, {3, 3, 0, 0}, {4, 2, 1, 2}, {5, 0, 0, 1}};
        String only = System.getenv("KINE_JUMPS");   // "dx,dz,dh,runUp;..." to measure just these
        if (only != null) {
            String[] parts = only.split(";");
            cases = new int[parts.length][];
            for (int i = 0; i < parts.length; i++) cases[i] = java.util.Arrays.stream(parts[i].split(",")).mapToInt(Integer::parseInt).toArray();
        }
        int planned = 0, arrived = 0;
        StringBuilder fails = new StringBuilder();
        for (int[] c : cases) {
            Outcome o = run(c[0], c[1], c[2], c[3]);
            System.out.printf(Locale.ROOT, "jump %d,%d dh %+d run-up %d: planned=%b arrived=%b ticks=%d %s%n", o.dx(), o.dz(), o.dh(),
                o.runUp(), o.planned(), o.arrived(), o.ticks(), o.why());
            if (o.planned()) planned++;
            if (o.arrived()) arrived++; else fails.append(String.format(Locale.ROOT, " %d,%d,%+d", o.dx(), o.dz(), o.dh()));
        }
        System.out.printf(Locale.ROOT, "jumps: %d of %d planned by layer 1, %d made by layer 2; not made:%s%n", planned, cases.length, arrived, fails);
        if (!full) {
            assertEquals(cases.length, planned, "layer 1 plans each");
            assertEquals(cases.length, arrived, "layer 2 makes each:" + fails);
        }
    }
}
