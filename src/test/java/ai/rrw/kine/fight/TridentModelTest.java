package ai.rrw.kine.fight;

import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.search.RecedingSearch;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A drowned with a trident in the model world (DrownedTridentAttackGoal, a RangedAttackGoal every 40 ticks within 10
 * blocks; Drowned.performRangedAttack, a skeleton's throw at 8 damage): standing still takes its tridents; the search,
 * replanning every tick, steps off their line.
 */
class TridentModelTest {
    static double run(boolean search, double slider, long seed) {
        GroundPhysics phys = new GroundPhysics(FightBench.flat(40), new Body());
        FightModel model = new FightModel(phys, slider);
        RecedingSearch<FightWorld> rs = new RecedingSearch<>(model);
        RecedingSearch.Effort e = new RecedingSearch.Effort();
        e.beam = 3; e.depth = 4; e.hold = 5; e.draws = 2; e.budgetNanos = 1_000_000_000L;
        FightWorld w = FightBench.scene(0, 1, 360);
        w.health = 100; w.maxHealth = 100;
        w.aura.on = false;
        MobSim d = new MobSim();
        d.kind = MobSim.Kind.TRIDENT; d.rangedInterval = 40; d.speed = 0.23; d.width = 0.6; d.height = 1.95; d.eyeHeight = 1.74;
        d.health = 20; d.maxHealth = 20;
        d.x = 0.5; d.z = 0.5 + 8; d.y = 64; d.floorY = 64; d.onGround = true;
        d.seeTime = 20; d.attackTime = 5; d.walking = false;
        d.drawnX = d.x; d.drawnY = d.y; d.drawnZ = d.z; d.delay = 4;
        w.mobs = new MobSim[]{d};
        SplittableRandom real = new SplittableRandom(seed);
        int[] plan = null;
        double h0 = w.health;
        for (int t = 0; t < 100; t++) {
            int a = Moves.HOLD;
            if (search) {
                RecedingSearch.Result r = rs.plan(w, plan, plan == null ? 0 : 1, e, seed * 7919 + t);
                plan = r.plan();
                a = plan == null ? Moves.HOLD : plan[0];
                if (a != Moves.HOLD) w.segmentYaw = FightModel.headingFor(w, Moves.direction(a));
                w.planHeading = a == Moves.HOLD ? Float.NaN : w.segmentYaw;   // the real aura's sprint out reads it
            }
            w.tick(phys, FightModel.chooser(w, a, false), real);
        }
        return h0 - w.health;
    }

    @Test
    void standingStillTakesTheTridents() {
        double lost = run(false, 0.0, 1);
        System.out.printf("tridents, standing still: lost %.1f%n", lost);
        assertTrue(lost >= 16.0, "lost " + lost);   // throws at 5, 45 and 85: at least two of 8
    }

    @Test
    void theSearchStepsOffTheirLine() {
        for (double s : new double[]{0.0, 1.0}) {
            double lost = run(true, s, 1);
            System.out.printf("tridents, search at %.0f: lost %.1f%n", s * 100, lost);
            assertTrue(lost <= 8.0, "lost " + lost + " at " + s);
        }
    }
}
