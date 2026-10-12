package ai.rrw.kine.fight;

import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GameMath;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.GroundPhysicsTest;
import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The actions' keys: what the executor presses, checked by walking them through the exact movement copy. */
class MovesTest {
    @Test
    void theKeysWalkWithin22AndAHalfDegreesOfTheHeadingAtAnyRotation() {
        GroundPhysics phys = new GroundPhysics(GroundPhysicsTest.flat(), new Body());
        for (int h = 0; h < Moves.HEADINGS; h++) {
            for (float yaw = -180; yaw < 180; yaw += 13.7f) {
                float want = Moves.headingYaw(h);
                int k = Moves.keys(want, yaw, Moves.WALK);
                MoveState s = GroundPhysicsTest.rest();
                for (int i = 0; i < 10; i++) phys.step(s, k, yaw);
                double moved = Math.toDegrees(Math.atan2(-(s.x - 0.5), s.z - 0.5));
                double err = Math.abs(GameMath.wrapDegrees(moved - want));
                assertTrue(err <= 22.5 + 1e-3, "heading " + want + " at yaw " + yaw + ": walked " + moved);
                assertEquals(Moves.walkYaw(k, yaw), moved, 0.02);   // the game's sine table, not Math.sin
            }
        }
    }

    @Test
    void sprintOnlyWithForward() {
        for (float rel = -180; rel < 180; rel += 5) {
            int k = Moves.keys(rel, 0f, Moves.SPRINT);
            boolean sprint = (k & MoveState.SPRINT) != 0;
            assertEquals((k & MoveState.FORWARD) != 0, sprint, "rel " + rel);
            assertEquals(Math.abs(rel) <= 67.5, sprint, "rel " + rel);
            int j = Moves.keys(rel, 0f, Moves.SPRINT_JUMP);
            assertEquals(sprint, (j & MoveState.JUMP) != 0, "sprint-jump jumps only where it can sprint, rel " + rel);
        }
    }

    @Test
    void everyActionHasANameAndHoldPressesNothing() {
        for (int a = 0; a < Moves.COUNT; a++) assertFalse(Moves.name(a).isEmpty());
        FightWorld w = new FightWorld();
        w.segmentYaw = 0f;
        assertEquals(0, FightModel.keysFor(w, Moves.HOLD, 0f));
    }
}
