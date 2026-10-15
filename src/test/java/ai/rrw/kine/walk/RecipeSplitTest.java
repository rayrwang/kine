package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The jump search split over threads by its first heading finds what the one-thread search finds. */
public class RecipeSplitTest {
    static final int FLOOR = 20;

    @Test
    void theSplitSearchFindsWhatTheOneThreadSearchFinds() {
        TestWorld w = TestWorld.blocks(-16, -16, 31, 15);
        w.set(0, FLOOR - 1, 0, TestWorld.STONE);
        w.set(3, FLOOR - 1, 1, TestWorld.STONE);
        w.set(4, FLOOR, -2, TestWorld.STONE);
        Layer2Sim sim = new Layer2Sim(w);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int[] l : new int[][]{{3, FLOOR - 1, 1}, {4, FLOOR, -2}}) {
                MoveState s = Layer2Sim.standing(0.5, FLOOR, 0.5);
                float b = Recipe.yawTo(0.5, 0.5, l[0] + 0.5, l[2] + 0.5);
                Recipe one = Recipe.search(sim.phys, s, 10f, l[0], l[1], l[2], b - 90f, b + 90f, 6f, 8, 8, 60);
                Recipe split = Recipe.searchParallel(pool, 4, sim.phys, s, 10f, l[0], l[1], l[2], b - 90f, b + 90f, 6f, 8, 8, 60);
                assertEquals(one.found(), split.found());
                assertEquals(one.how, split.how);
                assertEquals(one.ways, split.ways);
                assertEquals(one.tried, split.tried);
                assertArrayEquals(one.keys, split.keys);
                assertArrayEquals(one.yaw, split.yaw);
            }
        } finally {
            pool.shutdown();
        }
    }

}
