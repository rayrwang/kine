package ai.rrw.kine.fight;

import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.search.RecedingSearch;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RW's creeper rule in the model world: a creeper must never be let swell to a blast beside the player when stepping
 * away would prevent it. A creeper already swelling 2.5 blocks off, with the kill aura on and off, at both ends of the
 * slider: the search, replanning every tick, takes no blast damage; the player standing still takes a blast.
 */
class CreeperSafetyTest {
    static FightWorld scene(boolean aura, int swell) {
        FightWorld w = FightBench.scene(0, 1, 360);
        w.health = 100; w.maxHealth = 100;
        w.aura.on = aura;
        MobSim c = new MobSim();
        c.kind = MobSim.Kind.CREEPER; c.speed = 0.25; c.height = 1.7; c.armour = 0;
        c.x = 0.5 + 2.5; c.z = 0.5; c.y = 64; c.floorY = 64;
        c.swell = swell; c.swellDir = 1; c.swelling = true; c.active = false; c.walking = false;
        c.drawnX = c.x; c.drawnY = c.y; c.drawnZ = c.z; c.delay = 4;
        w.mobs = new MobSim[]{c};
        return w;
    }

    static double run(boolean aura, double slider, int swell, boolean search, long seed) {
        GroundPhysics phys = new GroundPhysics(FightBench.flat(40), new Body());
        FightModel model = new FightModel(phys, slider);
        RecedingSearch<FightWorld> rs = new RecedingSearch<>(model);
        RecedingSearch.Effort e = new RecedingSearch.Effort();
        e.beam = 3; e.depth = 4; e.hold = 5; e.draws = 2; e.budgetNanos = 1_000_000_000L;
        FightWorld w = scene(aura, swell);
        SplittableRandom real = new SplittableRandom(seed);
        int[] plan = null;
        double h0 = w.health;
        for (int t = 0; t < 80 && w.alive() > 0; t++) {
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
    void standingStillTakesTheBlast() {
        assertTrue(run(false, 0.0, 5, false, 1) > 10.0);
    }

    @Test
    void theSearchNeverLetsASwellingCreeperBlowBesideThePlayer() {
        for (boolean aura : new boolean[]{false, true})
            for (double slider : new double[]{0.0, 1.0})
                for (long seed = 1; seed <= 2; seed++) {
                    double lost = run(aura, slider, 5, true, seed);
                    assertEquals(0.0, lost, 1e-9, "aura " + aura + " slider " + slider + " seed " + seed);
                }
    }

    /** RW, 2026-10-12, verbatim: "no exp[losions" and "jsut at least at 0 no wepxoistoins". Groups of four creepers,
     *  6 to 10 blocks off on flat ground, the kill aura on: at survive, with the 40-tick horizon combat movement uses
     *  when a creeper is near, the search kills them and none blows up. */
    @Test
    void atSurviveNoCreeperBlows() {
        GroundPhysics phys = new GroundPhysics(FightBench.flat(40), new Body());
        RecedingSearch.Effort e = new RecedingSearch.Effort();
        e.beam = 4; e.depth = 4; e.hold = CombatMovement.CREEPER_HOLD; e.draws = 2; e.budgetNanos = 1_000_000_000L;
        System.setProperty("creepers", "4");
        try {
            int before = FightBench.blasts[1];
            for (long seed = 0; seed < 4; seed++) {
                FightBench.Outcome o = FightBench.fight(FightBench.scene(4, seed, 360), phys, true, false, 0.0, e, seed);
                System.out.printf("survive, four creepers, seed %d: lost %.1f in %d ticks, %d left%n", seed, o.lost(), o.ticks(), o.left());
                assertEquals(0, o.left(), "all killed, seed " + seed);
            }
            assertEquals(0, FightBench.blasts[1] - before, "blasts");
        } finally {
            System.clearProperty("creepers");
        }
    }

    /** RW's sprint out (2026-10-13): walking out of a lit creeper the aura lets it go and turns toward the way out, so
     *  the player sprints. A creeper lit 3.5 blocks off, the aura on, survive: the player sprints, is past the turn-back
     *  distance well before the fuse ends, and the creeper doesn't blow. */
    @Test
    void theWalkOutOfALitCreeperIsASprint() {
        GroundPhysics phys = new GroundPhysics(FightBench.flat(40), new Body());
        FightWorld w = scene(true, 5);
        w.mobs[0].x = 0.5 + 3.5;
        w.aura.k = 100;
        FightModel model = new FightModel(phys, 0.0);
        RecedingSearch<FightWorld> rs = new RecedingSearch<>(model);
        RecedingSearch.Effort e = new RecedingSearch.Effort();
        e.beam = 3; e.depth = 4; e.hold = CombatMovement.CREEPER_HOLD; e.draws = 2; e.budgetNanos = 1_000_000_000L;
        SplittableRandom real = new SplittableRandom(1);
        int[] plan = null;
        boolean sprinted = false;
        int outAt = -1;
        for (int t = 0; t < 40 && w.alive() > 0; t++) {
            RecedingSearch.Result r = rs.plan(w, plan, plan == null ? 0 : 1, e, 7919L + t);
            plan = r.plan();
            int a = plan == null ? Moves.HOLD : plan[0];
            if (a != Moves.HOLD) w.segmentYaw = FightModel.headingFor(w, Moves.direction(a));
            w.planHeading = a == Moves.HOLD ? Float.NaN : w.segmentYaw;
            w.tick(phys, FightModel.chooser(w, a, false), real);
            sprinted |= w.player.sprinting;
            MobSim c = w.mobs[0];
            if (outAt < 0 && Math.hypot(c.x - w.player.x, c.z - w.player.z) > MobSim.SWELL_BACK) outAt = t;
        }
        System.out.printf("sprint out: sprinted %b, past 7.15 at tick %d, creeper exploded %b%n", sprinted, outAt, w.mobs[0].exploded);
        assertTrue(sprinted, "sprinted");
        assertTrue(outAt >= 0 && outAt < 20, "out at " + outAt);
        assertFalse(w.mobs[0].exploded, "blew up");
    }
}
