package ai.rrw.kine.fight;

import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.search.RecedingSearch;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blazes in the model world (BlazeAttackGoal, SmallFireball): a 60-tick charge, then three fireballs 6 ticks apart
 * that speed up as they fly; the hurt window lets the first and third of a volley land on a player standing still.
 * The search, replanning every tick, steps off their line.
 */
class BlazeModelTest {
    static FightWorld scene(boolean aura) {
        FightWorld w = FightBench.scene(0, 1, 360);
        w.health = 100; w.maxHealth = 100;
        w.aura.on = aura;
        MobSim b = new MobSim();
        b.kind = MobSim.Kind.BLAZE; b.width = 0.6; b.height = 1.8; b.eyeHeight = 1.53;
        b.health = 20; b.maxHealth = 20; b.attackDamage = 6;
        b.x = 0.5; b.z = 0.5 + 9; b.y = 64; b.floorY = 64; b.onGround = true;
        b.blazeStep = 1; b.charged = true; b.attackTime = 10;   // its first fireball 10 ticks on
        b.drawnX = b.x; b.drawnY = b.y; b.drawnZ = b.z; b.delay = 4;
        b.walking = false;
        w.mobs = new MobSim[]{b};
        return w;
    }

    static double run(boolean search, double slider, long seed) {
        GroundPhysics phys = new GroundPhysics(FightBench.flat(40), new Body());
        FightModel model = new FightModel(phys, slider);
        RecedingSearch<FightWorld> rs = new RecedingSearch<>(model);
        RecedingSearch.Effort e = new RecedingSearch.Effort();
        e.beam = 3; e.depth = 4; e.hold = 5; e.draws = 2; e.budgetNanos = 1_000_000_000L;
        FightWorld w = scene(false);
        SplittableRandom real = new SplittableRandom(seed);
        int[] plan = null;
        double h0 = w.health;
        for (int t = 0; t < 40; t++) {
            int a = Moves.HOLD;
            if (search) {
                RecedingSearch.Result r = rs.plan(w, plan, plan == null ? 0 : 1, e, seed * 7919 + t);
                plan = r.plan();
                a = plan == null ? Moves.HOLD : plan[0];
                if (a != Moves.HOLD) w.segmentYaw = FightModel.headingFor(w, Moves.direction(a));
            }
            w.tick(phys, FightModel.chooser(w, a, false), real);
        }
        return h0 - w.health;
    }

    @Test
    void aFireballSpeedsUpAsTheGameFliesIt() {
        ArrowSim a = new ArrowSim();
        a.y = 66; a.vx = 0.1; a.gravity = 0; a.drag = 1; a.accel = 0.1; a.fixed = 5;
        double[] far = {100, 100, 100, 101, 101, 101};
        double x0 = a.x;
        a.tick(far, FightBench.flat(40));
        assertEquals(0.19, a.x - x0, 1e-9);   // (0.1 + 0.1) x 0.95
        for (int i = 0; i < 200; i++) { a.age = 0; a.x = 0; a.tick(far, FightBench.flat(40)); }
        assertEquals(1.9, a.vx, 1e-3);         // its top speed: 0.1 x 0.95 / 0.05
    }

    @Test
    void standingStillTakesTheVolley() {
        double lost = run(false, 0.0, 1);
        System.out.printf("blaze volley, standing still: lost %.1f%n", lost);
        assertTrue(lost >= 10.0, "lost " + lost);   // the first and third fireballs (5 each), the second inside the hurt window
    }

    @Test
    void theSearchStepsOffTheLine() {
        for (double s : new double[]{0.0, 1.0}) {
            double lost = run(true, s, 1);
            System.out.printf("blaze volley, search at %.0f: lost %.1f%n", s * 100, lost);
            assertTrue(lost < 5.0, "lost " + lost + " at " + s);
        }
    }
}
