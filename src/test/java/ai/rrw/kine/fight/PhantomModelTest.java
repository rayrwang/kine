package ai.rrw.kine.fight;

import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.search.RecedingSearch;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phantoms in the model world (PhantomSweepAttackGoal, PhantomMoveControl): one diving at the player from above hits a
 * player standing still; the search, replanning every tick, takes less.
 */
class PhantomModelTest {
    static FightWorld scene(boolean aura) {
        FightWorld w = FightBench.scene(0, 1, 360);
        w.health = 100; w.maxHealth = 100;
        w.aura.on = aura;
        MobSim f = new MobSim();
        f.kind = MobSim.Kind.PHANTOM; f.width = 0.9; f.height = 0.5;
        f.health = 20; f.maxHealth = 20; f.attackDamage = 6; f.armour = 0;
        f.x = 0.5; f.z = 0.5 + 12; f.y = 64 + 10; f.floorY = 64; f.onGround = false;
        f.yRot = 180; f.flySpeed = 0.8; f.vz = -0.4; f.vy = -0.2; f.swoop = true;
        f.drawnX = f.x; f.drawnY = f.y; f.drawnZ = f.z; f.delay = 4;
        f.walking = false;
        w.mobs = new MobSim[]{f};
        return w;
    }

    static double run(boolean search, boolean aura, double slider, long seed) {
        GroundPhysics phys = new GroundPhysics(FightBench.flat(40), new Body());
        FightModel model = new FightModel(phys, slider);
        RecedingSearch<FightWorld> rs = new RecedingSearch<>(model);
        RecedingSearch.Effort e = new RecedingSearch.Effort();
        e.beam = 3; e.depth = 4; e.hold = 5; e.draws = 2; e.budgetNanos = 1_000_000_000L;
        FightWorld w = scene(aura);
        SplittableRandom real = new SplittableRandom(seed);
        int[] plan = null;
        double h0 = w.health;
        for (int t = 0; t < 60; t++) {
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
    void aDivingPhantomHitsAStillPlayer() {
        double lost = run(false, false, 0.0, 1);
        System.out.printf("phantom dive, standing still, no aura: lost %.1f%n", lost);
        assertTrue(lost >= 6.0, "lost " + lost);
    }

    @Test
    void theSearchTakesLess() {
        double still = run(false, false, 0.0, 1);
        for (double s : new double[]{0.0, 1.0}) {
            double lost = run(true, false, s, 1);
            System.out.printf("phantom dive, search at %.0f, no aura: lost %.1f%n", s * 100, lost);
            assertTrue(lost < still, "lost " + lost + " at " + s);
        }
    }
}
