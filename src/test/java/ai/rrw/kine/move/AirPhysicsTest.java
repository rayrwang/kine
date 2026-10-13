package ai.rrw.kine.move;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The air supply in the movement copy against real ticks: air_traces.txt was recorded in a lockstep client test
 * (clienttest AirPhysicsTest, 2026-10-12): staying on the bottom of a pool until drowning hurts, coming up and
 * breathing and going down again, a sprint swim held at the top with the pitch 30 up, Water Breathing for 8 s, a
 * turtle helmet, Respiration III. The air the client sees each tick (the server's, synced) must equal the copy's,
 * the drowning resets too; Respiration keeps a point by chance, so there only the average is compared.
 */
public class AirPhysicsTest {
    static Traces.File traces() throws Exception {
        try (var in = AirPhysicsTest.class.getResourceAsStream("/move/air_traces.txt")) {
            assertNotNull(in, "golden traces missing");
            return Traces.read(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** The body for a run: Respiration III's oxygen bonus and the turtle helmet where the run had them. */
    static Body body(Traces.File f, String run) {
        Body b = f.body();
        b.oxygenBonus = run.equals("respiration") ? 3.0 : 0.0;
        b.turtleHelmet = run.equals("turtle");
        return b;
    }

    /** The copy's air after each tick of a run. */
    static double[] replayAir(Traces.File f, Traces.Run run) {
        GroundPhysics phys = new GroundPhysics(f.grid(), body(f, run.name()));
        MoveState s = run.start().copy();
        double[] out = new double[run.ticks().size()];
        for (int i = 0; i < out.length; i++) {
            Traces.Tick t = run.ticks().get(i);
            phys.step(s, t.keys(), t.yaw(), t.pitch());
            out[i] = s.air;
        }
        return out;
    }

    @Test
    void theAirIsTheGamesEveryTick() throws Exception {
        Traces.File f = traces();
        int compared = 0;
        for (Traces.Run run : f.runs()) {
            if (run.name().equals("respiration")) continue;
            double[] air = replayAir(f, run);
            for (int i = 0; i < air.length; i++) {
                assertEquals(run.ticks().get(i).air(), air[i], 1e-9, run.name() + " tick " + i);
                compared++;
            }
        }
        assertTrue(compared > 1000, "ticks compared: " + compared);
    }

    @Test
    void theRunsDoWhatTheirNamesSay() throws Exception {
        Traces.File f = traces();
        for (Traces.Run run : f.runs()) {
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, resets = 0;
            for (int i = 1; i < run.ticks().size(); i++) {
                int a = run.ticks().get(i).air(), b = run.ticks().get(i - 1).air();
                min = Math.min(min, a); max = Math.max(max, a);
                if (b <= -19 && a == 0) resets++;
            }
            switch (run.name()) {
                case "drown" -> assertTrue(resets >= 3, "drowning hurts more than twice: " + resets);
                case "sprint_top" -> assertEquals(300, max, "breathing at the top while sprint swimming fills the air");
                case "turtle" -> assertEquals(300, max);
                default -> { }
            }
        }
    }

    @Test
    void respirationKeepsAboutThreeQuartersOfThePoints() throws Exception {
        Traces.File f = traces();
        Traces.Run run = f.runs().stream().filter(r -> r.name().equals("respiration")).findFirst().orElseThrow();
        int n = run.ticks().size();
        double lost = run.start().air - run.ticks().get(n - 1).air();
        double expected = run.start().air - replayAir(f, run)[n - 1];   // the copy's expected drain, 1/4 a tick
        double sd = Math.sqrt(n * 0.25 * 0.75);
        assertEquals(n * 0.25, expected, 1e-6);
        assertEquals(expected, lost, 3 * sd, "the game's points lost against the copy's expectation");
    }
}
