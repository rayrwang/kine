package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The course's ladder_diagonal (RW 2026-10-14, per-jump rates: made 2 of 5 as the course meets it, the misses falling
 * past the ladder's side): from the take-off, a jump 3 on and 3 across, 1 up, onto a ladder on the west face of a stone
 * column. The copy-found jump came in sideways (0.195 a tick across the ladder's face), touched the ladder and slid past
 * its side within 6 ticks, keys up or pressing into the column; the jump search now holds a climbable landing (Recipe.hold)
 * and keeps only ways that stay on it.
 */
public class LadderDiagonalTest {
    static final int F = 20;   // the take-off's top

    static BlockGrid station() {
        TestWorld w = TestWorld.blocks(-16, -16, 15, 15);
        w.set(0, F - 1, 0, TestWorld.STONE);                               // the take-off
        w.set(0, F - 2, 1, TestWorld.STONE);                               // the course's pad beside it
        for (int dy = -2; dy <= 2; dy++) w.set(4, F + dy, 3, TestWorld.STONE);   // the column
        for (int dy = 0; dy <= 2; dy++) w.set(3, F + dy, 3, TestWorld.LADDER_EAST);   // ladder[facing=west], and the course's extra rung
        return Layer2Sim.grid(w);
    }

    /** The recipe's inputs, then `after` ticks of the given keys toward the ladder's column: still on the ladder? */
    static boolean holds(GroundPhysics phys, Recipe r, MoveState start, int after, int keys) {
        MoveState m = start.copy();
        for (int t = 0; t < r.ticks; t++) {
            phys.step(m, r.keys[t], r.yaw[t], 0f);
            if (System.getenv("KINE_LADDER_DEBUG") != null && t >= r.ticks - 4)
                System.out.printf(Locale.ROOT, "  recipe %d: %.3f %.3f %.3f v %.3f %.3f %.3f climb %b%n", t, m.x, m.y - F, m.z, m.vx, m.vy, m.vz, phys.onClimbable(m));
        }
        float yaw = r.yaw[r.ticks - 1];
        for (int t = 0; t < after; t++) {
            float to = Recipe.yawTo(m.x, m.z, 4.5, 3.5);   // into the column the ladder is on
            phys.step(m, keys, keys == 0 ? yaw : to, 0f);
            if (System.getenv("KINE_LADDER_DEBUG") != null)
                System.out.printf(Locale.ROOT, "  hold %d keys %d: %.3f %.3f %.3f v %.3f %.3f %.3f ground %b climb %b%n", t, keys, m.x, m.y - F, m.z, m.vx, m.vy, m.vz, m.onGround, phys.onClimbable(m));
        }
        return phys.onClimbable(m) && Math.floor(m.x) == 3 && Math.floor(m.z) == 3;
    }

    @Test
    void theJumpOntoTheLadderAndAfter() {
        BlockGrid g = station();
        GroundPhysics phys = new GroundPhysics(g, new Body());
        MoveState s = Layer2Sim.standing(0.5, F, 0.5);
        float bearing = Recipe.yawTo(0.5, 0.5, 3.5, 3.5);
        Recipe r = Recipe.search(phys, s, -90f, 3, F, 3, bearing - 90f, bearing + 90f, 6f, 8, 8, 60);
        assertTrue(r.found(), "a way onto the ladder");
        double[] e = {r.endX, r.endY, r.endZ};
        boolean atEnd = holds(phys, r, s, 0, 0), released = holds(phys, r, s, 10, 0), pressed = holds(phys, r, s, 10, MoveState.FORWARD);
        System.out.printf(Locale.ROOT, "ladder_diagonal: %s, %d ways of %d, %d ticks, ends %.3f %.3f %.3f (%s); 10 ticks on, keys up: %s, forward into the column: %s%n",
            r.how, r.ways, r.tried, r.ticks, e[0], e[1] - F, e[2], atEnd ? "on the ladder" : "off", released ? "on the ladder" : "off", pressed ? "on the ladder" : "off");
        // the recipe ends held on the ladder, in its column, and from there pressing into the column keeps it there
        assertTrue(atEnd, "the recipe ends on the ladder");
        assertTrue(pressed, "pressing into the column after it, still on the ladder");
    }
}
