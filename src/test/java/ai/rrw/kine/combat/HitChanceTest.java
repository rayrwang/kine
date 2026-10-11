package ai.rrw.kine.combat;

import ai.rrw.kine.combat.HitChance.Box;
import ai.rrw.kine.combat.HitChance.Shot;
import ai.rrw.kine.combat.HitChance.Target;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The hit chance against the game's own rules (26.1.2), and the best-aim search. */
class HitChanceTest {
    static final double SPAWN_Y = (double) 1.62F - (double) 0.1F;   // eye height minus 0.1, where the server spawns it
    static final List<Box> GROUND = List.of(new Box(-4000, -1, -4000, 4000, 0, 4000));
    static final Ballistics.Spec ARROW = new Ballistics.Spec(0.05, 0.99, 3.0, 0.0, false, true);
    static final Vec3 EYE = new Vec3(0, SPAWN_Y, 0);

    // aims through a point with the auto aim's own solve, as AimAdvisor does in the game
    static final HitChance.Aimer AIMER = (px, py, pz) -> {
        Vec3 d = Ballistics.solveAim(EYE, new Vec3(px, py, pz), Vec3.ZERO, ARROW, Vec3.ZERO, true).subtract(EYE).normalize();
        return new double[]{d.x, d.y, d.z};
    };

    static Shot aimedAt(Box b) {
        double[] d = AIMER.aim(b.centerX(), b.centerY(), b.centerZ());
        return new Shot(0, SPAWN_Y, 0, d[0], d[1], d[2], 3.0, 1.0, 0, 0, 0);
    }

    @Test
    void theMarginGrowsWithTheArrowsAge() {
        double[] m = {0, 0, 0.05F, 0.1F, 0.15F, 0.2F, 0.25F, 0.3F, 0.3F};
        for (int age = 1; age <= 9; age++) assertEquals(m[age - 1], HitChance.margin(age), "age " + age);
    }

    @Test
    void boxEntryMatchesAabbClip() {
        Box b = new Box(0, 0, 0, 1, 1, 1);
        assertEquals(0.5, b.clip(-1, 0.5, 0.5, 2, 0, 0));
        assertTrue(Double.isNaN(b.clip(0.5, 0.5, 0.5, 2, 0, 0)), "a segment starting inside does not enter");
        assertTrue(Double.isNaN(b.clip(-1, 2, 0.5, 2, 0, 0)));
        assertEquals(0.5, b.clip(0.5, 3, 0.5, 0, -4, 0));
    }

    @Test
    void theFixedRollsHaveTheTrianglesSpread() {
        double[][] r = HitChance.rolls(4096);
        for (int k = 0; k < 3; k++) {
            double mean = 0, var = 0, cov = 0;
            for (double[] x : r) { mean += x[k] / r.length; var += x[k] * x[k] / r.length; cov += x[k] * x[(k + 1) % 3] / r.length; }
            assertEquals(0, mean, 2e-3);
            assertEquals(1.0 / 6, var, 1e-3);
            assertEquals(0, cov, 1e-3);
        }
    }

    @Test
    void theChanceAgreesWithTheGamesOwnRoll() {
        // a zombie at 80 blocks, aimed at its centre: the fixed rolls against 100,000 rolls as the server makes them
        Target t = Target.still(Box.ofFeet(0, 0, 80, 0.6, 1.95));
        Shot s = aimedAt(t.box());
        double fixed = HitChance.chance(s, t, GROUND, 4096, 400);
        Random rnd = new Random(1);
        double a = HitChance.SPREAD;
        int hits = 0, n = 100_000;
        for (int i = 0; i < n; i++)
            if (HitChance.hits(s, a * (rnd.nextDouble() - rnd.nextDouble()), a * (rnd.nextDouble() - rnd.nextDouble()),
                               a * (rnd.nextDouble() - rnd.nextDouble()), t, GROUND, 400)) hits++;
        assertEquals(hits / (double) n, fixed, 0.02);
        assertTrue(fixed > 0.5 && fixed < 0.8, "about 65% at 80 blocks, got " + fixed);
    }

    @Test
    void aWallInFrontHidesTheTargetAndBestAimGoesOverIt() {
        Target t = Target.still(Box.ofFeet(0, 0, 40, 0.6, 1.95));
        List<Box> wall = List.of(GROUND.get(0), new Box(-20, 0, 37.5, 20, 1, 38.5));
        double centre = HitChance.chance(aimedAt(t.box()), t, wall, 4096, 400);
        HitChance.Aim best = HitChance.bestAim(aimedAt(t.box()), t, HitChance.Blocks.of(wall), 400, AIMER);
        assertNotNull(best);
        assertTrue(best.chance() > centre + 0.1, "best " + best.chance() + " against centre " + centre);
        assertTrue(best.up() > 0, "aims above the centre");
        // on open ground a tall target keeps the centre
        HitChance.Aim open = HitChance.bestAim(aimedAt(t.box()), t, HitChance.Blocks.of(GROUND), 400, AIMER);
        assertEquals(0.0, open.up(), 1e-12);
        assertEquals(0.0, open.side(), 1e-12);
    }

    static HitChance.Aim aim(double chance, double up) { return new HitChance.Aim(0, 0, 1, 0, 0, 0, chance, up, 0); }

    @Test
    void nearTiesDontWalkTheBestAimAwayFromTheBest() {
        // the best chance far from the centre, then points each a little worse and a little nearer: compared one
        // by one with the last kept, each would replace it and the pick would end 1.2 points below the best
        List<HitChance.Aim> aims = List.of(aim(0.800, 3), aim(0.796, 2), aim(0.792, 1), aim(0.788, 0.5));
        assertEquals(0.796, HitChance.nearestCentreOfBest(aims).chance(), 1e-12);
    }
}
