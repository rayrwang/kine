package ai.rrw.kine.fight;

import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slimes and magma cubes in the model world (Slime's SlimeAttackGoal and SlimeMoveControl, Slime.playerTouch): they
 * hop toward the player and hurt it on touch, a tiny slime never; the hurt window keeps the hits at least 10 ticks
 * apart.
 */
class SlimeModelTest {
    static MobSim slime(int size, boolean magma, double x, double z) {
        MobSim m = new MobSim();
        m.kind = MobSim.Kind.SLIME;
        m.magma = magma;
        m.slimeSize = size;
        m.touchDamage = magma || size > 1;
        m.speed = 0.2F + 0.1F * size;
        m.jumpPower = 0.42F + (magma ? size * 0.1F : 0.0F);
        m.width = 0.52 * size; m.height = 0.52 * size;
        m.attackDamage = m.touchDamage ? size + (magma ? 2 : 0) : 0;
        m.health = size * size; m.maxHealth = m.health;
        m.x = x; m.z = z; m.y = 64; m.floorY = 64; m.onGround = true; m.vy = -0.0784000015258789;
        m.yRot = (float) AuraSim.yawTo(x, z, 0.5, 0.5);
        m.drawnX = m.x; m.drawnY = m.y; m.drawnZ = m.z; m.delay = 4;
        m.walking = false;
        return m;
    }

    /** The player standing still, aura off, against one slime: health lost and the ticks of each hit. */
    static double[] standStill(MobSim m, int ticks) {
        FightWorld w = FightBench.scene(0, 1, 360);
        w.health = 100; w.maxHealth = 100;
        w.aura.on = false;
        w.mobs = new MobSim[]{m};
        GroundPhysics phys = new GroundPhysics(FightBench.flat(30), new Body());
        SplittableRandom rnd = new SplittableRandom(3);
        double h0 = w.health, last = w.health;
        int hits = 0, firstHit = -1, minGap = Integer.MAX_VALUE, lastHit = -1;
        for (int t = 0; t < ticks; t++) {
            w.tick(phys, FightModel.chooser(w, Moves.HOLD, false), rnd);
            if (w.health < last) {
                hits++;
                if (firstHit < 0) firstHit = t;
                if (lastHit >= 0) minGap = Math.min(minGap, t - lastHit);
                lastHit = t;
            }
            last = w.health;
        }
        return new double[]{h0 - w.health, hits, firstHit, minGap};
    }

    @Test
    void hopSpeeds() {
        for (int size : new int[]{1, 2, 4})
            for (boolean magma : new boolean[]{false, true}) {
                MobSim m = slime(size, magma, 0, 0);
                double v = m.walkSpeed();
                System.out.printf("%s size %d: wait %d, %.4f blocks a tick%n", magma ? "magma cube" : "slime", size, m.minJumpDelay(), v);
                assertTrue(v > 0.01 && v < 0.25, "hop speed " + v);
            }
        // a big slime covers ground faster than a medium one, and a magma cube (four times the wait) slower than a slime
        assertTrue(slime(4, false, 0, 0).walkSpeed() > slime(2, false, 0, 0).walkSpeed());
        assertTrue(slime(4, true, 0, 0).walkSpeed() < slime(4, false, 0, 0).walkSpeed());
    }

    @Test
    void aSlimeHopsInAndHurtsOnTouch() {
        double[] r = standStill(slime(4, false, 0.5, 6.5), 200);
        System.out.printf("big slime: lost %.1f in %d hits, first at %d, closest gap %d%n", r[0], (int) r[1], (int) r[2], (int) r[3]);
        assertTrue(r[1] >= 3, "hits " + r[1]);
        assertTrue(r[3] >= 10, "the hurt window keeps hits 10 ticks apart: " + r[3]);
    }

    @Test
    void aTinySlimeNeverHurts() {
        double[] r = standStill(slime(1, false, 0.5, 3.5), 200);
        assertEquals(0.0, r[0], 1e-9);
        double[] mc = standStill(slime(1, true, 0.5, 3.5), 300);
        assertTrue(mc[0] > 0, "a tiny magma cube hurts");
    }

    @Test
    void theSlimeTurnsTenDegreesATick() {
        MobSim m = slime(2, false, 0.5, 6.5);
        m.yRot += 90;
        float before = m.yRot;
        m.thinkSlime(0.5, 0.5, new SplittableRandom(1));
        assertEquals(10.0, Math.abs(ai.rrw.kine.move.GameMath.wrapDegrees(m.yRot - before)), 1e-4);
    }
}
