package ai.rrw.kine.guard;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guardian offline, in the movement copy over synthetic terrain: the cases it must catch (a wall, the ground, a
 * stall high up, a low pass into a cliff) and those it must leave alone (under the Nether roof, an arch, a canopy, a dive
 * into deep water). Each flown hands off (an AFK pilot), the guardian's loop as the game runs it.
 */
class GuardScenarioTest {
    static Guard.Config cfg() { return new Guard.Config(); }

    static GuardLoop.Run hands(BlockGrid g, MoveState s, float yaw, float pitch, int ticks) {
        GuardLoop.Run r = GuardLoop.run(g, s, yaw, pitch, Pilot.HOLD, null, cfg(), ticks, -1, 0, false);
        System.out.println(r);
        for (String l : r.log) System.out.println("  " + l);
        return r;
    }

    @Test
    void wallAheadIsTurnedAwayFrom() {
        // gliding south (+z) at 30 m/s, 36 above the ground, a wall 120 blocks ahead
        var r = hands(Worlds.wall(120), GuardLoop.gliding(0.5, 100, 0.5, 0, -0.1, 1.5), 0f, 3f, 400);
        assertTrue(r.fired());
        assertEquals(0.0, r.damage, "no damage");
    }

    @Test
    void steepDiveIntoTheGroundIsPulledOutOf() {
        var r = hands(Worlds.flat(), GuardLoop.gliding(0.5, 160, 0.5, 0, -1.0, 1.2), 0f, 40f, 400);
        assertTrue(r.fired());
        assertEquals(0.0, r.damage, "no damage");
    }

    @Test
    void stallHighUpIsRecovered() {
        // a takeoff stalled with its nose held 70 up, 100 above the ground, nearly stopped
        var r = hands(Worlds.flat(), GuardLoop.gliding(0.5, 164, 0.5, 0, 0.1, 0.1), 0f, -70f, 600);
        assertTrue(r.fired());
        assertEquals(0.0, r.damage, "no damage");
    }

    @Test
    void lowIntoACliffIsEscaped() {
        // 11 above the ground at 30 m/s, a cliff 60 ahead rising 56
        var r = hands(Worlds.cliff(60, 120), GuardLoop.gliding(0.5, 75, 0.5, 0, -0.05, 1.5), 0f, 0f, 400);
        assertTrue(r.fired());
        assertEquals(0.0, r.damage, "no damage");
    }

    @Test
    void underTheNetherRoofNothingFires() {
        // level under the roof, a gentle sink: nothing in reach within the horizon
        var r = hands(Worlds.netherRoof(), GuardLoop.gliding(0.5, 100, 0.5, 0, 0.0, 1.5), 0f, 2f, 100);
        assertFalse(r.fired());
    }

    @Test
    void diveIntoDeepWaterNeverFires() {
        var r = hands(Worlds.lake(), GuardLoop.gliding(0.5, 120, 0.5, 0, -1.0, 1.0), 0f, 50f, 200);
        assertFalse(r.fired(), "water takes the fall");
        assertEquals(0.0, r.damage);
    }
}
