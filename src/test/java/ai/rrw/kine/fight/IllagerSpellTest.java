package ai.rrw.kine.fight;

import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.search.RecedingSearch;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Evokers' fangs and vexes in the model world: a line of fangs bites a player standing on it; a charging vex hits a
 * still player; the search, replanning every tick, steps off the fangs' line.
 */
class IllagerSpellTest {
    static FightWorld evokerScene() {
        FightWorld w = FightBench.scene(0, 1, 360);
        w.health = 100; w.maxHealth = 100;
        w.aura.on = false;
        MobSim ev = new MobSim();
        ev.kind = MobSim.Kind.EVOKER; ev.width = 0.6; ev.height = 1.95; ev.health = 24; ev.maxHealth = 24;
        ev.x = 0.5; ev.z = 0.5 + 8; ev.y = 64; ev.floorY = 64; ev.onGround = true; ev.castIn = 0; ev.walking = false;
        ev.drawnX = ev.x; ev.drawnY = ev.y; ev.drawnZ = ev.z; ev.delay = 4;
        w.mobs = new MobSim[]{ev};
        return w;
    }

    static double run(FightWorld w, boolean search, double slider, int ticks, long seed) {
        GroundPhysics phys = new GroundPhysics(FightBench.flat(40), new Body());
        FightModel model = new FightModel(phys, slider);
        RecedingSearch<FightWorld> rs = new RecedingSearch<>(model);
        RecedingSearch.Effort e = new RecedingSearch.Effort();
        e.beam = 3; e.depth = 4; e.hold = 5; e.draws = 2; e.budgetNanos = 1_000_000_000L;
        SplittableRandom real = new SplittableRandom(seed);
        int[] plan = null;
        double h0 = w.health;
        for (int t = 0; t < ticks; t++) {
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
    void fangsBiteAPlayerStandingOnTheirLine() {
        double lost = run(evokerScene(), false, 0.0, 40, 1);
        System.out.printf("fangs, standing still: lost %.1f%n", lost);
        assertEquals(6.0, lost, 1e-9);
    }

    @Test
    void theSearchStepsOffTheFangs() {
        for (double s : new double[]{0.0, 1.0}) {
            double lost = run(evokerScene(), true, s, 40, 1);
            System.out.printf("fangs, search at %.0f: lost %.1f%n", s * 100, lost);
            assertEquals(0.0, lost, 1e-9, "at " + s);
        }
    }

    @Test
    void aChargingVexHits() {
        FightWorld w = FightBench.scene(0, 1, 360);
        w.health = 100; w.maxHealth = 100;
        w.aura.on = false;
        MobSim v = new MobSim();
        v.kind = MobSim.Kind.VEX; v.width = 0.4; v.height = 0.8; v.health = 14; v.maxHealth = 14; v.attackDamage = 9;
        v.x = 0.5; v.z = 0.5 + 6; v.y = 66; v.floorY = 64; v.walking = false;
        v.drawnX = v.x; v.drawnY = v.y; v.drawnZ = v.z; v.delay = 4;
        w.mobs = new MobSim[]{v};
        double lost = run(w, false, 0.0, 40, 1);
        System.out.printf("vex charge, standing still: lost %.1f%n", lost);
        assertTrue(lost >= 9.0, "lost " + lost);
    }
}
