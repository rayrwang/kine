package ai.rrw.kine.combat;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Projectile flight and the aim solve, plus the kill aura's and auto weapon's arithmetic. */
class BallisticsTest {
    static final Ballistics.Spec ARROW = new Ballistics.Spec(0.05, 0.99, 3.0, 0.0, false, true);
    static final Ballistics.Spec SNOWBALL = new Ballistics.Spec(0.03, 0.99, 1.5, 0.0, true, false);

    @Test
    void flightOrderMatchesTheGame() {
        // an arrow moves first, then loses 1% and falls; a thrown item falls and slows first, then moves
        List<Vec3> a = Ballistics.simulate(Vec3.ZERO, new Vec3(1, 0, 0), ARROW, null, 2).path();
        assertEquals(new Vec3(1, 0, 0), a.get(1));
        assertEquals(1 + 0.99, a.get(2).x, 1e-12);
        assertEquals(-0.05, a.get(2).y, 1e-12);
        List<Vec3> b = Ballistics.simulate(Vec3.ZERO, new Vec3(1, 0, 0), SNOWBALL, null, 1).path();
        assertEquals(0.99, b.get(1).x, 1e-12);
        assertEquals(-0.03 * 0.99, b.get(1).y, 1e-12);
    }

    @Test
    void theAimSolveHitsStillTargets() {
        Vec3 eye = new Vec3(0, 70, 0);
        for (double[] t : new double[][] { {10, 0, 0}, {0, 5, 40}, {-30, -10, 25}, {50, 15, -45}, {0, 0, 90} }) {
            Vec3 target = eye.add(t[0], t[1], t[2]);
            Vec3 aim = Ballistics.solveAim(eye, target, Vec3.ZERO, ARROW, Vec3.ZERO, true);
            Vec3 d = aim.subtract(eye).normalize();
            double yaw = Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = Math.toDegrees(Math.asin(-d.y));
            List<Vec3> path = Ballistics.simulate(eye, Ballistics.launchVel(yaw, pitch, ARROW, Vec3.ZERO, true), ARROW, null).path();
            double best = Double.MAX_VALUE;
            for (int i = 1; i < path.size(); i++) {
                Vec3 p0 = path.get(i - 1), seg = path.get(i).subtract(p0);
                double u = Math.max(0, Math.min(1, target.subtract(p0).dot(seg) / seg.lengthSqr()));
                best = Math.min(best, target.subtract(p0.add(seg.scale(u))).length());
            }
            assertTrue(best < 0.15, "misses " + target + " by " + best);
        }
    }

    // closest distance between the no-spread path of an aim and a point
    static double closestPass(Vec3 eye, Vec3 aim, Ballistics.Spec spec, Vec3 target) {
        Vec3 d = aim.subtract(eye).normalize();
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = Math.toDegrees(Math.asin(-d.y));
        List<Vec3> path = Ballistics.simulate(eye, Ballistics.launchVel(yaw, pitch, spec, Vec3.ZERO, true), spec, null).path();
        double best = Double.MAX_VALUE;
        for (int i = 1; i < path.size(); i++) {
            Vec3 p0 = path.get(i - 1), seg = path.get(i).subtract(p0);
            double u = Math.max(0, Math.min(1, target.subtract(p0).dot(seg) / seg.lengthSqr()));
            best = Math.min(best, target.subtract(p0.add(seg.scale(u))).length());
        }
        return best;
    }

    @Test
    void theAimSolveReachesLongShots() {
        // full-power arrows out to near the longest range, level and above and below
        Vec3 eye = new Vec3(0, 70, 0);
        for (double[] t : new double[][] { {0, 0, 100}, {0, 0, 108}, {0, 0, 112}, {60, 15, 50}, {0, -20, 110}, {-70, 8, 60} }) {
            Vec3 target = eye.add(t[0], t[1], t[2]);
            Vec3 aim = Ballistics.solveAim(eye, target, Vec3.ZERO, ARROW, Vec3.ZERO, true);
            assertTrue(closestPass(eye, aim, ARROW, target) < 0.15, "misses " + target);
        }
    }

    @Test
    void outOfRangeTheAimSolveGivesItsBestTry() {
        Vec3 eye = new Vec3(0, 70, 0), target = eye.add(0, 0, 200);
        Vec3 aim = Ballistics.solveAim(eye, target, Vec3.ZERO, ARROW, Vec3.ZERO, true);
        Vec3 d = aim.subtract(eye).normalize();
        assertFalse(Double.isNaN(d.y));
        double elevation = Math.toDegrees(Math.asin(d.y));
        assertTrue(elevation > 30 && elevation < 50, "about the longest-range elevation, got " + elevation);
    }

    @Test
    void weaponsPassOnTheShootersMotionExceptTheCrossbow() {
        assertTrue(ARROW.inherits(), "the six-argument Spec defaults to passing it on");
        assertFalse(new Ballistics.Spec(0.05, 0.99, 3.15, 0.0, false, true, false).inherits());
        Vec3 dir = Ballistics.launchDir(30, -10, ARROW);
        assertEquals(1.0, dir.length(), 1e-12);
        assertEquals(dir.scale(3.0), Ballistics.launchVel(30, -10, ARROW, Vec3.ZERO, true));
    }

    @Test
    void shooterMotionIsAddedHorizontallyAlwaysAndVerticallyOnlyInTheAir() {
        Vec3 m = new Vec3(0.5, 0.3, -0.2);
        Vec3 ground = Ballistics.launchVel(0, 0, ARROW, m, true), air = Ballistics.launchVel(0, 0, ARROW, m, false);
        assertEquals(0.5, ground.x, 1e-12); assertEquals(0.0, ground.y, 1e-12); assertEquals(3.0 - 0.2, ground.z, 1e-12);
        assertEquals(0.3, air.y, 1e-12);
    }

    @Test
    void spamBeatsWaitingPastFiftyOverTheAttackDelay() {
        assertEquals(5, KillAura.spamThreshold(12.5f));   // a sword
        assertEquals(3, KillAura.spamThreshold(20f));     // an axe
    }

    @Test
    void weaponScore() {
        // diamond sword: base 1 + 6 = 7 damage, speed 4 - 2.4 = 1.6; Sharpness V adds 3
        assertEquals((7 + 3.0) * 1.6, WeaponSelect.damagePerSecond(true, 7, 1.6, 5, 0, 0, 0, 1), 1e-12);
        // Smite V against an undead target adds 12.5
        assertEquals((7 + 12.5) * 1.6, WeaponSelect.damagePerSecond(true, 7, 1.6, 0, 5, 0, 0, 1), 1e-12);
        // against a crowd a sword adds a sweep per neighbour, up to four
        double one = WeaponSelect.damagePerSecond(true, 7, 1.6, 0, 0, 0, 3, 1);
        assertEquals(one + (1 + 7 * 3 / 4.0) * 4 * 1.6, WeaponSelect.damagePerSecond(true, 7, 1.6, 0, 0, 0, 3, 9), 1e-12);
        assertEquals(one, WeaponSelect.damagePerSecond(false, 7, 1.6, 0, 0, 0, 3, 9), 1e-12, "an axe doesn't sweep");
    }
}
