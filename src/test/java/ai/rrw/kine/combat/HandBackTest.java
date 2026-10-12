package ai.rrw.kine.combat;

import ai.rrw.kine.fight.Moves;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.WorldSnapshot;
import net.minecraft.world.entity.player.Input;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The walk's hand-back (walk/Walk): the tick you press a movement key, the rotation the server has may be up to 180
 * degrees from your camera, and the game moves you by your keys relative to it. The kill aura's key swap
 * (KillAura.walkKeys, used while its rotation is away from your view, which the walk's facing goes through) re-presses
 * your keys as the ones nearest your camera-relative direction at the rotation sent. Checked here for every key
 * combination and every offset: the keys pressed move you within 22.5 degrees of where your keys point on your camera,
 * the same keys combat movement's Moves.keys would pick, and sprint stays only with W among them.
 */
public class HandBackTest {
    static final int[] COMBOS = {MoveState.FORWARD, MoveState.FORWARD | MoveState.RIGHT, MoveState.RIGHT,
        MoveState.BACKWARD | MoveState.RIGHT, MoveState.BACKWARD, MoveState.BACKWARD | MoveState.LEFT, MoveState.LEFT,
        MoveState.FORWARD | MoveState.LEFT};

    static double diff(double a, double b) {
        double d = ((a - b) % 360 + 540) % 360 - 180;
        return Math.abs(d);
    }

    @Test
    void yourDirectionHoldsAtAnySentRotation() {
        int checked = 0;
        for (int combo : COMBOS)
            for (double cam = -170; cam <= 180; cam += 37.3)
                for (double off = -180; off <= 180; off += 3.7) {
                    float camYaw = (float) cam, sentYaw = (float) (cam + off);
                    float yours = Moves.walkYaw(combo, camYaw);                 // where your keys point on your camera
                    Input swapped = KillAura.walkKeys(WorldSnapshot.input(combo), sentYaw - camYaw);
                    int k = WorldSnapshot.keys(swapped);
                    float got = Moves.walkYaw(k, sentYaw);                      // where the game moves you
                    assertFalse(Float.isNaN(got), "keys dropped");
                    assertTrue(diff(got, yours) <= 22.5 + 1e-3, combo + " cam " + cam + " off " + off + ": " + got + " vs " + yours);
                    // the same choice as Moves.keys (nearest of the eight), away from exact ties
                    double rel = ((yours - sentYaw) % 45 + 45) % 45;
                    if (Math.abs(rel - 22.5) > 0.5) assertEquals(Moves.keys(yours, sentYaw, Moves.WALK), k & MoveState.MOVE_KEYS);
                    checked++;
                }
        assertTrue(checked > 5000);
    }

    @Test
    void sprintOnlyWithForward() {
        Input sideSprint = new Input(false, false, true, false, false, false, true);
        assertFalse(ai.rrw.kine.walk.WalkTestAccess.handBack(sideSprint).sprint());
        Input fwdSprint = new Input(true, false, true, false, false, false, true);
        assertTrue(ai.rrw.kine.walk.WalkTestAccess.handBack(fwdSprint).sprint());
    }
}
