package ai.rrw.kine.fight;

import ai.rrw.kine.combat.SwingPlan;
import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.Shape;
import ai.rrw.kine.search.RecedingSearch;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RW's safety rule in the model world: with zombies coming from one side and a drop of six blocks or a lava pool on the
 * other, the search, replanning every tick and fighting with the kill aura, never walks the player off the edge or
 * into the lava, even as hits knock it toward them.
 */
class FightSafetyTest {
    static final BlockInfo STONE = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
    static final BlockInfo LAVA = new BlockInfo(Shape.EMPTY, 0.6F, 1.0F, 1.0F, BlockInfo.FLUID | BlockInfo.HAZARD);

    /** Stone floor at y 63 for x < 3, and for x >= 3 either nothing down to y 57 (a drop of six) or lava at y 63. */
    static BlockGrid arena(boolean lava) {
        BlockGrid g = new BlockGrid(-20, 50, -20, 41, 20, 41);
        for (int x = -20; x <= 20; x++)
            for (int z = -20; z <= 20; z++) {
                g.set(x, 56, z, STONE);
                if (x < 3) g.set(x, 63, z, STONE);
                else if (lava && x <= 5) g.set(x, 63, z, LAVA);
                else if (lava) g.set(x, 63, z, STONE);
            }
        return g;
    }

    static FightWorld scene(long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        FightWorld w = new FightWorld();
        MoveState p = new MoveState();
        p.x = 2.6; p.y = 64; p.z = 0.5; p.onGround = true; p.vy = -0.0784000015258789;   // 0.1 from the edge
        p.hasSupport = true; p.supX = 2; p.supY = 63; p.supZ = 0;
        w.player = p;
        w.health = 100;
        w.aura = new AuraSim();
        w.aura.weapon = new SwingPlan.Weapon(8.0, 12.500001F, true, 0.0);
        w.aura.sentYaw = w.aura.viewYaw = 90f;   // facing west, toward them
        w.mobs = new MobSim[4];
        for (int i = 0; i < 4; i++) {
            MobSim m = new MobSim();
            double a = Math.toRadians(20 + 140 * rnd.nextDouble()), r = 3.5 + 3 * rnd.nextDouble();   // west half, close
            m.x = 2.6 - Math.sin(a) * r; m.z = 0.5 + Math.cos(a) * r; m.y = 64; m.floorY = 64;
            m.drawnX = m.x; m.drawnY = m.y; m.drawnZ = m.z; m.delay = 5;
            w.mobs[i] = m;
        }
        return w;
    }

    /** The same scene with the kill aura alone (no keys): how often hits knock the player off or in. */
    static String auraAlone(boolean lava, long seed) {
        GroundPhysics phys = new GroundPhysics(arena(lava), new Body());
        FightWorld truth = scene(seed);
        SplittableRandom real = new SplittableRandom(seed + 99);
        double lowest = truth.player.y;
        boolean burnt = false;
        for (int t = 0; t < 160 && truth.alive() > 0; t++) {
            truth.tick(phys, 0, real);
            lowest = Math.min(lowest, truth.player.y);
            burnt |= (truth.player.touched & BlockInfo.HAZARD) != 0;
        }
        return String.format(java.util.Locale.ROOT, "aura alone: took %.1f, lowest feet %.2f, lava %b", truth.taken, lowest, burnt);
    }

    static void run(boolean lava, long seed) {
        GroundPhysics phys = new GroundPhysics(arena(lava), new Body());
        FightModel model = new FightModel(phys, 0.25);
        RecedingSearch<FightWorld> rs = new RecedingSearch<>(model);
        RecedingSearch.Effort e = new RecedingSearch.Effort();
        e.beam = 3; e.depth = 4; e.hold = 5; e.draws = 2; e.budgetNanos = 1_000_000_000L;
        FightWorld truth = scene(seed);
        SplittableRandom real = new SplittableRandom(seed + 99);
        int[] plan = null;
        double lowest = truth.player.y, maxX = truth.player.x;
        for (int t = 0; t < 160 && truth.alive() > 0; t++) {
            RecedingSearch.Result r = rs.plan(truth, plan, plan == null ? 0 : 1, e, seed * 7919 + t);
            plan = r.plan();
            int a = plan == null ? Moves.HOLD : plan[0];
            if (a != Moves.HOLD) truth.segmentYaw = FightModel.headingFor(truth, Moves.direction(a));
            truth.tick(phys, FightModel.chooser(truth, a, false), real);
            lowest = Math.min(lowest, truth.player.y);
            maxX = Math.max(maxX, truth.player.x);
            assertEquals(0, truth.player.touched & BlockInfo.HAZARD, "touched lava at tick " + t);
            assertTrue(truth.player.x < 3.3 || lava, "over the drop at tick " + t + ": x " + truth.player.x);
        }
        assertTrue(lowest > 63.99, "fell: lowest feet " + lowest);
        System.out.printf(java.util.Locale.ROOT, "safety %s seed %d: took %.1f, dealt %.1f, %d left, nearest the edge x %.2f; %s%n",
            lava ? "lava" : "drop", seed, truth.taken, truth.dealt, truth.alive(), maxX, auraAlone(lava, seed));
    }

    @Test
    void neverOffTheEdge() {
        for (long seed = 1; seed <= 4; seed++) run(false, seed);
    }

    @Test
    void neverIntoTheLava() {
        for (long seed = 1; seed <= 4; seed++) run(true, seed);
    }
}
