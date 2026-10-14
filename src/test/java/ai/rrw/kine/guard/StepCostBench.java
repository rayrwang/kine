package ai.rrw.kine.guard;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.Shape;
import org.junit.jupiter.api.Test;

/** Not a check: how long a glide step in the movement copy takes here (printed). */
class StepCostBench {
    @Test
    void glideStepCost() {
        BlockGrid g = new BlockGrid(-200, 0, -200, 400, 120, 400);
        BlockInfo stone = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
        for (int x = -200; x < 200; x++) for (int z = -200; z < 200; z++) for (int y = 0; y < 60; y++) g.set(x, y, z, stone);
        GlideSim sim = new GlideSim(g, new Body(), -64, 3.0);
        for (int rep = 0; rep < 5; rep++) {
            long t0 = System.nanoTime();
            int steps = 0;
            for (int k = 0; k < 200; k++) {
                MoveState s = new MoveState();
                s.x = -150; s.y = 100; s.z = 0.5; s.vx = 1.5; s.vy = -0.2; s.fallFlying = true; s.glidePose = true;
                for (int t = 0; t < 150; t++) { sim.step(s, -90f, 5f, false); steps++; }
            }
            long ns = System.nanoTime() - t0;
            System.out.printf("glide step: %.2f us (%d steps)%n", ns / 1000.0 / steps, steps);
        }
    }
}
