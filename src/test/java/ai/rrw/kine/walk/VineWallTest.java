package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plain vine wall (VineReplayTest.wall: a wall 3 or 5 high, vines on its near face), walked to its top with Parkour
 * on and off. With Parkour off the walk hung at the vine's top in a lockstep test (vine5off, "Stuck"): climbing off onto
 * the wall looked like a gap crossing over the vine's column (RunModel.gapCheck).
 */
public class VineWallTest {
    static final int FLOOR = VineReplayTest.FLOOR;

    @Test
    void theWalkClimbsOntoTheWallWithParkourOnAndOff() {
        for (int h : new int[]{3, 5})
            for (boolean parkour : new boolean[]{true, false}) {
                Layer2Sim sim = new Layer2Sim(VineReplayTest.wall(h, false));
                sim.parkour = parkour;
                sim.goalY = FLOOR + h;
                Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(-5.5, FLOOR, 0.5), -90f, 3, 0, 400);
                System.out.printf("vine wall %d high, parkour %b: %s in %d ticks (%s)%n", h, parkour, o.arrived() ? "arrived" : "not", o.ticks(), o.why());
                assertTrue(o.arrived(), "vine wall " + h + " parkour " + parkour + ": " + o.why());
            }
    }
}
