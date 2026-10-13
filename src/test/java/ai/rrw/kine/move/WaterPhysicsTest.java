package ai.rrw.kine.move;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Water and climbing in the movement copy against real ticks: water_traces.txt was recorded in a lockstep client test
 * (clienttest WaterPhysicsTest, 2026-10-12) with the game's own keys and pitch pressed tick by tick: floating with jump
 * held, swimming into a bank level with the water's top and one a block higher, sinking, sneaking down, sprint swimming
 * level and with the pitch changed under water, wading one deep, dropping into the pool from three high, and walking
 * into, jumping up and sneaking down a ladder and climbing a vine. Replayed open loop from each run's start, the copy
 * must give the same doubles and the same in-water, swimming, swim-pose and eyes-in-water flags every tick.
 */
public class WaterPhysicsTest {
    static Traces.File traces() throws Exception {
        try (var in = WaterPhysicsTest.class.getResourceAsStream("/move/water_traces.txt")) {
            assertNotNull(in, "golden traces missing");
            return Traces.read(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    static Traces.Run run(Traces.File f, String name) {
        return f.runs().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void everyRecordedTickIsReproducedExactly() throws Exception {
        Traces.File f = traces();
        assertEquals(14, f.runs().size());
        GroundPhysics phys = new GroundPhysics(f.grid(), f.body());
        int ticks = 0;
        for (Traces.Run run : f.runs()) {
            Traces.Diff d = Traces.replay(phys, run);
            assertEquals(0.0, d.pos(), run.name() + ": position");
            assertEquals(0.0, d.vel(), run.name() + ": velocity");
            assertEquals(-1, d.flagTick(), run.name() + ": a flag");
            ticks += run.ticks().size();
        }
        assertTrue(ticks > 700, "ticks: " + ticks);
    }

    @Test
    void theRecordedRunsDoWhatTheirNamesSay() throws Exception {
        Traces.File f = traces();
        // forward and jump into a bank level with the water's top: out onto it (the game's jump out of water)
        Traces.Run level = run(f, "swim_exit_level_bank");
        Traces.Tick end = level.ticks().get(level.ticks().size() - 1);
        assertEquals(-60.0, end.y(), 1e-9, "on the bank");
        assertTrue(end.z() < 1.0, "past the pool's edge");
        // the same into a bank a block higher than the water's top: the game doesn't get out there
        Traces.Run raised = run(f, "swim_exit_raised_bank");
        Traces.Tick r = raised.ticks().get(raised.ticks().size() - 1);
        assertTrue(r.y() < -61.0 && r.x() < 11.0, "still in the pool");
        // sprint swimming: the swim pose and the swimming flag on, fast
        Traces.Run sw = run(f, "sprint_swim_pitch");
        assertTrue(sw.ticks().stream().anyMatch(t -> (t.water() & 2) != 0 && (t.water() & 4) != 0), "swimming");
        // up the ladder by walking into it
        Traces.Run ladder = run(f, "ladder_walk_into");
        assertTrue(ladder.ticks().get(ladder.ticks().size() - 1).y() > -57.0, "climbed");
    }
}
