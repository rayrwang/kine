package ai.rrw.kine.fight;

import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A breeze in the model world (its brain's Shoot, BreezeWindCharge): the wind charge flies straight at 0.7 a tick,
 * hurts 1 and bursts where it stops, throwing a player standing still.
 */
class BreezeModelTest {
    @Test
    void aWindChargeHurtsOneAndThrows() {
        FightWorld w = FightBench.scene(0, 1, 360);
        w.health = 100; w.maxHealth = 100;
        w.aura.on = false;
        MobSim b = new MobSim();
        b.kind = MobSim.Kind.BREEZE; b.width = 0.6; b.height = 1.77; b.health = 30; b.maxHealth = 30;
        b.x = 0.5; b.z = 0.5 + 8; b.y = 64; b.floorY = 64; b.onGround = true;
        b.shootIn = 2; b.walking = false;
        b.drawnX = b.x; b.drawnY = b.y; b.drawnZ = b.z; b.delay = 4;
        w.mobs = new MobSim[]{b};
        GroundPhysics phys = new GroundPhysics(FightBench.flat(30), new Body());
        SplittableRandom rnd = new SplittableRandom(1);
        double z0 = w.player.z, h0 = w.health, most = 0;
        for (int t = 0; t < 30; t++) {
            w.tick(phys, FightModel.chooser(w, Moves.HOLD, false), rnd);
            most = Math.max(most, Math.abs(w.player.z - z0));
        }
        System.out.printf("wind charge: lost %.1f, thrown %.2f blocks%n", h0 - w.health, most);
        assertEquals(1.0, h0 - w.health, 1e-9);
        assertTrue(most > 0.5, "thrown " + most);
    }
}
