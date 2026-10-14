package ai.rrw.kine.guard;

import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The guardian's damage rules in the movement copy, on synthetic blocks: a wall hit is the horizontal speed lost
 *  times 10 less 3; a landing's fall is cut to a block while gliding and sinking slowly, cut by hay, taken by water;
 *  the void. */
class GlideSimTest {
    static GlideSim sim(ai.rrw.kine.move.BlockGrid g) { return new GlideSim(g, new Body(), -64, 3.0); }

    @Test
    void aWallHitCostsTheSpeedLostTimesTenLessThree() {
        GlideSim sim = sim(Worlds.wall(20));
        MoveState s = GuardLoop.gliding(0.5, 100, 0.5, 0, 0, 1.6);
        double dmg = 0, speedBefore = 0;
        for (int t = 0; t < 40 && dmg == 0; t++) {
            speedBefore = Math.hypot(s.vx, s.vz);
            dmg = sim.step(s, 0f, 0f, false);
        }
        assertTrue(dmg > 0, "hit the wall");
        assertEquals(GlideSim.WALL, sim.lastKinds, "counted once, as the wall");
        assertEquals((speedBefore - Math.hypot(s.vx, s.vz)) * 10 - 3 + GlideSim.WALL_MARGIN, dmg, 1e-9);
    }

    @Test
    void aSlowWallTouchIsHarmless() {
        GlideSim sim = sim(Worlds.wall(2));
        MoveState s = GuardLoop.gliding(0.5, 100, 0.5, 0, 0, 0.2);
        double dmg = 0;
        for (int t = 0; t < 10; t++) dmg += sim.step(s, 0f, 0f, false);
        assertEquals(0.0, dmg, "about 0.2 a tick into a wall: 2 - 3 is nothing, even with the margin");
        assertTrue(s.z > 1.0 && s.z < 2.0, "against the wall: " + s.z);
    }

    @Test
    void aGentleGlideOntoTheGroundIsHarmlessASteepOneHurts() {
        GlideSim sim = sim(Worlds.flat());
        MoveState gentle = GuardLoop.gliding(0.5, 70, 0.5, 0, -0.2, 1.0);
        double d1 = 0;
        for (int t = 0; t < 400 && gentle.fallFlying; t++) d1 += sim.step(gentle, 0f, 8f, false);
        assertEquals(0.0, d1, "sinking under 0.5 a tick keeps the fall at a block");
        MoveState steep = GuardLoop.gliding(0.5, 120, 0.5, 0, -1.5, 0.5);
        double d2 = 0;
        for (int t = 0; t < 400 && d2 == 0; t++) d2 += sim.step(steep, 0f, 80f, false);
        assertTrue(d2 > 5, "a dive straight down hurts: " + d2);
        assertEquals(GlideSim.FALL, sim.lastKinds);
        assertEquals(Math.floor(d2), d2, "whole points, LivingEntity.calculateFallDamage");
    }

    @Test
    void hayTakesFourFifthsAndWaterAllOfAFall() {
        ai.rrw.kine.move.BlockGrid hay = Worlds.of((x, y, z) -> y < 63 ? Worlds.STONE : y == 63 ? Worlds.HAY : ai.rrw.kine.move.BlockInfo.AIR);
        double plain = dive(Worlds.flat()), onHay = dive(hay);
        assertTrue(plain > 10, "the plain fall: " + plain);
        assertEquals(Math.floor(plain * 0.2), onHay, 1.0, "hay: 0.2 of the damage");
        assertEquals(0.0, dive(Worlds.lake()), "water takes it all");
    }

    static double dive(ai.rrw.kine.move.BlockGrid g) {
        GlideSim sim = sim(g);
        MoveState s = GuardLoop.gliding(0.5, 130, 0.5, 0, -1.5, 0.2);
        double d = 0;
        for (int t = 0; t < 400; t++) {
            d += sim.step(s, 0f, 85f, false);
            if (s.onGround || s.inWater) break;
        }
        return d;
    }

    @Test
    void theVoidHurts() {
        GlideSim sim = sim(Worlds.of((x, y, z) -> ai.rrw.kine.move.BlockInfo.AIR));
        MoveState s = GuardLoop.gliding(0.5, -100, 0.5, 0, -2, 0);
        double d = 0;
        for (int t = 0; t < 40; t++) d += sim.step(s, 0f, 80f, false);
        assertTrue(d > 0);
        assertEquals(GlideSim.VOID, sim.lastKinds);
    }
}
