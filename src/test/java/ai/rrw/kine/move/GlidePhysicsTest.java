package ai.rrw.kine.move;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gliding in the movement copy against real ticks: glide_traces.txt was recorded in a lockstep client test (clienttest
 * GlidePhysicsTest, 2026-10-12) with an elytra worn and the game's own keys and pitch pressed tick by tick: a hop on
 * flat ground, glides off a platform 12 high at several pitches, one into a wall, one landing and sliding on. Replayed
 * open loop with gliding copied (Body.glide), the copy must give the same doubles and the same flags every tick: the
 * landing too (3 ticks of gliding on the ground, then the server's end of the glide and the pose stood up at the
 * start of the 4th). The wall run only up to its hit: the server's hurt then resets the velocity to its own, two ticks
 * old, which the copy doesn't follow (a plan that flies into a wall is marked unsafe instead).
 */
public class GlidePhysicsTest {
    static Traces.File traces() throws Exception {
        try (var in = GlidePhysicsTest.class.getResourceAsStream("/move/glide_traces.txt")) {
            assertNotNull(in, "golden traces missing");
            return Traces.read(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** The tick glide_wall hits the wall (its horizontal speed to 0). */
    static final int WALL_HIT = 31;

    @Test
    void everyRecordedTickIsReproducedExactly() throws Exception {
        Traces.File f = traces();
        assertTrue(f.body().glide, "recorded with gliding copied");
        GroundPhysics phys = new GroundPhysics(f.grid(), f.body());
        int ticks = 0, glided = 0;
        for (Traces.Run full : f.runs()) {
            Traces.Run run = full.name().equals("glide_wall")
                ? new Traces.Run(full.name(), full.start(), full.ticks().subList(0, WALL_HIT + 2)) : full;
            Traces.Diff d = Traces.replay(phys, run);
            assertEquals(0.0, d.pos(), run.name() + ": position");
            assertEquals(0.0, d.vel(), run.name() + ": velocity");
            assertEquals(-1, d.flagTick(), run.name() + ": a flag");
            ticks += run.ticks().size();
            for (Traces.Tick t : run.ticks()) if ((t.water() & 16) != 0) glided++;
        }
        assertTrue(glided > 100, "gliding ticks: " + glided);
        assertEquals(5, f.runs().size());
    }

    @Test
    void theWallRunHitsTheWallAtItsTick() throws Exception {
        Traces.Run wall = traces().runs().stream().filter(r -> r.name().equals("glide_wall")).findFirst().orElseThrow();
        assertTrue(wall.ticks().get(WALL_HIT - 1).vx() > 0.4, "flying at the wall");
        assertEquals(0.0, wall.ticks().get(WALL_HIT).vx(), "stopped by it");
    }
}
