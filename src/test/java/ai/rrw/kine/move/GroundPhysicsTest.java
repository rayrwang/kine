package ai.rrw.kine.move;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The movement copy against real ticks: physics_traces.txt was recorded in a lockstep client test (clienttest
 * PhysicsTest, 2026-10-12) with the game's own keys pressed tick by tick over flat ground, a block step, slabs, stairs,
 * edges walked off and held by sneaking, blue and packed ice, soul sand, a low ceiling, a fence, a wall and a corner,
 * with turns between ticks. Replayed open loop from each run's start, the copy must give the same doubles.
 */
public class GroundPhysicsTest {
    static Traces.File traces() throws Exception {
        try (var in = GroundPhysicsTest.class.getResourceAsStream("/move/physics_traces.txt")) {
            assertNotNull(in, "golden traces missing");
            return Traces.read(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void everyRecordedTickIsReproducedExactly() throws Exception {
        Traces.File f = traces();
        assertTrue(f.runs().size() >= 20, "runs: " + f.runs().size());
        GroundPhysics phys = new GroundPhysics(f.grid(), f.body());
        int ticks = 0;
        for (Traces.Run run : f.runs()) {
            Traces.Diff d = Traces.replay(phys, run);
            assertEquals(0.0, d.pos(), run.name() + ": position");
            assertEquals(0.0, d.vel(), run.name() + ": velocity");
            assertEquals(-1, d.flagTick(), run.name() + ": on-ground or sprint flag");
            ticks += run.ticks().size();
        }
        assertTrue(ticks > 1000, "ticks: " + ticks);
    }

    @Test
    void theRunsMoveAndTheCheckCanFail() throws Exception {
        Traces.File f = traces();
        // the runs go somewhere: the sprint run covers several blocks
        Traces.Run sprint = f.runs().stream().filter(r -> r.name().equals("flat_sprint")).findFirst().orElseThrow();
        Traces.Tick last = sprint.ticks().get(sprint.ticks().size() - 1);
        assertTrue(Math.abs(last.z() - sprint.start().z) > 8.0, "sprint distance");
        // a copy one float step off in walking speed no longer matches
        Body off = f.body();
        off.walkSpeed = Math.nextUp(off.walkSpeed);
        off.sprintSpeed = Math.nextUp(off.sprintSpeed);
        GroundPhysics phys = new GroundPhysics(f.grid(), off);
        double worst = 0;
        for (Traces.Run run : f.runs()) worst = Math.max(worst, Traces.replay(phys, run).pos());
        assertTrue(worst > 0.0, "a wrong copy must show a difference");
    }

    @Test
    void sprintNeedsForwardAndStopsOnBackward() {
        BlockGrid g = flat();
        GroundPhysics phys = new GroundPhysics(g, new Body());
        MoveState s = rest();
        phys.step(s, MoveState.LEFT | MoveState.SPRINT, 0f);
        assertFalse(s.sprinting, "sideways keys can't sprint");
        phys.step(s, MoveState.FORWARD | MoveState.LEFT | MoveState.SPRINT, 0f);
        assertTrue(s.sprinting, "forward plus a side key can");
        phys.step(s, MoveState.BACKWARD | MoveState.LEFT | MoveState.SPRINT, 0f);
        assertFalse(s.sprinting, "no forward impulse stops it");
    }

    @Test
    void walkingSpeedsAreTheKnownOnes() {
        BlockGrid g = flat();
        GroundPhysics phys = new GroundPhysics(g, new Body());
        MoveState s = rest();
        for (int i = 0; i < 60; i++) phys.step(s, MoveState.FORWARD, 0f);
        double z0 = s.z;
        phys.step(s, MoveState.FORWARD, 0f);
        assertEquals(4.317 / 20, s.z - z0, 2e-3, "walk, blocks a tick");
        s = rest();
        for (int i = 0; i < 60; i++) phys.step(s, MoveState.FORWARD | MoveState.SPRINT, 0f);
        z0 = s.z;
        phys.step(s, MoveState.FORWARD | MoveState.SPRINT, 0f);
        assertEquals(5.612 / 20, s.z - z0, 2e-3, "sprint, blocks a tick");
    }

    /** Stone at y = 63, air above, from -20 to 20. */
    public static BlockGrid flat() {
        BlockGrid g = new BlockGrid(-20, 60, -20, 41, 10, 41);
        BlockInfo stone = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) g.set(x, 63, z, stone);
        return g;
    }

    public static MoveState rest() {
        MoveState s = new MoveState();
        s.x = 0.5; s.y = 64; s.z = 0.5;
        s.vy = -0.0784000015258789;
        s.onGround = true;
        s.hasSupport = true; s.supX = 0; s.supY = 63; s.supZ = 0;
        return s;
    }
}
