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
    void theAimSolveReachesTargetsAlmostStraightUpOrDown() {
        // within about 3 degrees of vertical (the audit's cases, 2026-10-11, aimed 15 to 40 degrees off before): the
        // pitch needed can be past 89 degrees, and a first secant step 1 degree higher can pass straight up
        Vec3 eye = new Vec3(0, 70, 0);
        for (double[] t : new double[][] { {0, 50, 1}, {0, 30, 0.5}, {0, 15, 0.3}, {0, 50, 2}, {0, 8, 0.2}, {0.7, 40, -0.7},
                                           {0, 5, 0.05}, {0, 20, 0.04}, {0, -50, 1}, {0, -20, 0.3} }) {
            Vec3 target = eye.add(t[0], t[1], t[2]);
            Vec3 aim = Ballistics.solveAim(eye, target, Vec3.ZERO, ARROW, Vec3.ZERO, true);
            assertTrue(closestPass(eye, aim, ARROW, target) < 0.15, "misses " + target + " by " + closestPass(eye, aim, ARROW, target));
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

    // a zombie that jumped this tick: it moved up 0.42 (the jump's first move) and its feet are 0.42 above the ground
    static final Ballistics.Course JUMP = new Ballistics.Course(new Vec3(0.1, 0.42, 0), true, 0.08, false, -0.42);

    @Test
    void aJumpingTargetsCourseComesBackDownToItsFloor() {
        // LivingEntity.travelInAir: move, then take off the gravity and keep 0.98F; aiStep drops a motion under 0.003
        double y = 0, v = 0.42;
        for (int k = 1; k <= 40; k++) {
            v = (v - 0.08) * (double) 0.98F;
            if (Math.abs(v) < 0.003) v = 0;
            y = Math.max(-0.42, y + v);
            assertEquals(y, JUMP.dy(k), 1e-12, "tick " + k);
        }
        assertEquals(-0.42, JUMP.dy(12), 0.0, "back on the ground 12 ticks after the take-off");
        assertEquals(-0.42, JUMP.dy(100), 0.0, "and stays there");
        assertEquals((JUMP.dy(4) + JUMP.dy(5)) / 2, JUMP.dy(4.5), 1e-12, "between ticks, on the line from one to the next");
        assertEquals(0.1 * 4.5, JUMP.at(4.5).x, 1e-12, "straight on across");
        // the straight-line lead had it 0.42 a tick higher for as long as the flight lasts
        assertEquals(0.42 * 20, Ballistics.Course.straight(new Vec3(0.1, 0.42, 0)).dy(20), 1e-12);
        double[] table = JUMP.dyTable(2.0, 30);
        assertEquals(0.0, table[0], 0.0);
        assertEquals(JUMP.dy(12) - JUMP.dy(2), table[10], 1e-12, "offsets from the spawn, by the arrow's age");
    }

    @Test
    void slowFallingCapsTheGravityOnlyOnTheWayDown() {
        Ballistics.Course c = new Ballistics.Course(new Vec3(0, 0.42, 0), true, 0.08, true, Double.NEGATIVE_INFINITY);
        assertEquals((0.42 - 0.08) * (double) 0.98F, c.dy(1), 1e-12, "rising: the full gravity");
        // falling: at most 0.01 a tick, so it settles near 0.01 * 0.98 / 0.02 = 0.49 blocks a tick
        assertEquals(-0.49, c.dy(400) - c.dy(399), 1e-3);
    }

    // closest the no-spread arrow comes to a target's centre on its course, the target at its tick-j position during
    // the arrow's tick j (as the game tests a hit)
    static double closestOnCourse(Vec3 eye, Ballistics.Solution s, Vec3 centre, Ballistics.Course c) {
        List<Vec3> path = Ballistics.simulate(eye, Ballistics.launchVel((float) s.yaw(), (float) s.pitch(), ARROW, Vec3.ZERO, true), ARROW, null).path();
        double best = Double.MAX_VALUE;
        for (int j = 1; j < path.size(); j++) {
            Vec3 target = centre.add(c.at(j)), p0 = path.get(j - 1), seg = path.get(j).subtract(p0);
            double u = Math.max(0, Math.min(1, target.subtract(p0).dot(seg) / seg.lengthSqr()));
            best = Math.min(best, target.subtract(p0.add(seg.scale(u))).length());
        }
        return best;
    }

    @Test
    void theLeadFollowsAJumpingTargetBackDown() {
        Ballistics.Course still = new Ballistics.Course(new Vec3(0, 0.42, 0), true, 0.08, false, -0.42);
        Vec3 eye = new Vec3(0, 1.52, 0);
        for (double dist : new double[]{15, 30, 50, 70}) {
            Vec3 centre = new Vec3(0, 0.42 + 0.975, dist);
            Ballistics.Solution falling = Ballistics.solve(eye, centre, still, 0.0, ARROW, Vec3.ZERO, true);
            Ballistics.Solution straight = Ballistics.solve(eye, centre, new Vec3(0, 0.42, 0), ARROW, Vec3.ZERO, true);
            assertTrue(closestOnCourse(eye, falling, centre, still) < 0.15, "led along its fall, at " + dist);
            assertTrue(closestOnCourse(eye, straight, centre, still) > 1.5, "led in a straight line, over its head at " + dist);
        }
    }

    @Test
    void theLeadSettlesOnFastTargets() {
        // three passes of t = T(t) left the first three 0.16 to 2.1 blocks off (the second audit's measure, a target at
        // 0.6 to 1.6 a tick moving off or coming on); the rest it left 0.9 to 2.8 off
        Vec3 eye = new Vec3(0, 1.52, 0);
        for (double[] c : new double[][] { {0, 0.6, 50}, {0, 1.0, 50}, {0, 1.6, 20}, {0, -1.0, 80}, {0, -1.6, 80}, {1.6, 0, 50} }) {
            Vec3 v = new Vec3(c[0], 0, c[1]), centre = new Vec3(0, 0.975, c[2]);
            Ballistics.Solution s = Ballistics.solve(eye, centre, v, ARROW, Vec3.ZERO, true);
            assertTrue(closestOnCourse(eye, s, centre, Ballistics.Course.straight(v)) < 0.15, "misses " + v + " at " + c[2]);
            assertTrue(Math.abs(s.miss()) < 0.15, "reported miss " + s.miss());
        }
    }

    @Test
    void aSecantStepOutOfTheBracketIsHalvedInstead() {
        // 1.6 a tick across and toward, 105 out and 15 up: the first steps overshoot into lead points out of range,
        // and the plain secant ran out of passes, though the lead settles at a flight of about 45.8 ticks
        Vec3 eye = new Vec3(0, 1.52, 0), centre = new Vec3(0, 16.52, 105);
        for (double ang : new double[]{120, 240}) {
            Vec3 v = new Vec3(Math.sin(Math.toRadians(ang)) * 1.6, 0, Math.cos(Math.toRadians(ang)) * 1.6);
            Ballistics.Solution s = Ballistics.solve(eye, centre, v, ARROW, Vec3.ZERO, true);
            assertTrue(Math.abs(s.miss()) < 0.15, "reported miss " + s.miss());
            assertTrue(closestOnCourse(eye, s, centre, Ballistics.Course.straight(v)) < 0.15);
        }
    }

    @Test
    void aTargetTheArrowCantCatchIsOutOfReach() {
        // moving off faster than the arrow carries that far: the old solve called both in reach (misses 0.00 and -0.02)
        Vec3 eye = new Vec3(0, 1.52, 0);
        for (double[] c : new double[][] { {0.6, 80}, {1.6, 50} }) {
            Ballistics.Solution s = Ballistics.solve(eye, new Vec3(0, 0.975, c[1]), new Vec3(0, 0, c[0]), ARROW, Vec3.ZERO, true);
            assertTrue(Math.abs(s.miss()) > 5, c[0] + " a tick from " + c[1] + ": miss " + s.miss());
        }
    }

    @Test
    void theArrowMeetsATargetWhereItIsOnTheArrowsTick() {
        // a target crossing at 1.6 a tick, 20 blocks out: the arrow crosses its range part way through a tick, and the
        // game tests that move against the target where it is that tick, a tick's motion on from where it is at the
        // crossing time itself
        Vec3 eye = new Vec3(0, 1.52, 0), v = new Vec3(1.6, 0, 0), centre = new Vec3(0, 0.975, 20);
        Ballistics.Solution s = Ballistics.solve(eye, centre, v, ARROW, Vec3.ZERO, true);
        assertTrue(closestOnCourse(eye, s, centre, Ballistics.Course.straight(v)) < 0.1);
        Vec3 d = Ballistics.launchDir(s.yaw(), s.pitch(), ARROW);
        HitChance.Target t = new HitChance.Target(HitChance.Box.ofFeet(0, 0, 20, 0.6, 1.95), v.x, v.y, v.z);
        double chance = HitChance.chance(new HitChance.Shot(0, 1.52, 0, d.x, d.y, d.z, 3.0, 1.0, 0, 0, 0), t,
                                         List.of(new HitChance.Box(-4000, -1, -4000, 4000, 0, 4000)), 4096, 400);
        assertTrue(chance > 0.95, "hit chance " + chance);
    }

    @Test
    void aReleaseFiresTwoTicksAfterTheTurnAndOneStepOn() {
        assertEquals(2, Ballistics.ticksToShot(true), "a bow or trident");
        assertEquals(1, Ballistics.ticksToShot(false), "a crossbow or a thrown item");
        Vec3 eye = new Vec3(10, 71.62, -4), sprint = new Vec3(0.28, 0, 0.1);
        assertEquals(new Vec3(10, 71.62 - 0.1F, -4), Ballistics.spawnPoint(eye, sprint, false));
        Vec3 released = Ballistics.spawnPoint(eye, sprint, true);
        assertEquals(10.28, released.x, 1e-12); assertEquals(71.62 - 0.1F, released.y, 1e-12); assertEquals(-3.9, released.z, 1e-12);
        // an archer strafing at 0.28 a tick: aimed from where the arrow really spawns it hits a target 10 blocks off;
        // aimed from the eye, the shot that spawns a step on passes the step to the side
        Vec3 target = new Vec3(10, 71.52, 6), motion = new Vec3(0.28, 0, 0);
        Vec3 from = Ballistics.spawnPoint(eye, motion, true), old = Ballistics.spawnPoint(eye, motion, false);
        Ballistics.Solution right = Ballistics.solve(from, target, Vec3.ZERO, ARROW, motion, true);
        Ballistics.Solution wrong = Ballistics.solve(old, target, Vec3.ZERO, ARROW, motion, true);
        assertTrue(passes(from, right, motion, true, target) < 0.05);
        assertEquals(0.28, passes(from, wrong, motion, true, target), 0.05);
    }

    // closest the no-spread arrow from `from` comes to a still point
    static double passes(Vec3 from, Ballistics.Solution s, Vec3 shooterMotion, boolean onGround, Vec3 target) {
        List<Vec3> path = Ballistics.simulate(from, Ballistics.launchVel((float) s.yaw(), (float) s.pitch(), ARROW, shooterMotion, onGround), ARROW, null).path();
        double best = Double.MAX_VALUE;
        for (int i = 1; i < path.size(); i++) {
            Vec3 p0 = path.get(i - 1), seg = path.get(i).subtract(p0);
            double u = Math.max(0, Math.min(1, target.subtract(p0).dot(seg) / seg.lengthSqr()));
            best = Math.min(best, target.subtract(p0.add(seg.scale(u))).length());
        }
        return best;
    }

    @Test
    void aShotTheShootersMotionCarriesPastIsAimedLower() {
        // rising 1 block a tick, 0.5 forward: 10 degrees below the line of sight still passes over these, and the
        // bracket used to stop there and call them out of reach
        Vec3 eye = new Vec3(0, 70, 0), motion = new Vec3(0, 1.0, 0.5);
        for (double[] t : new double[][] { {0, -10, 5}, {0, -20, 8}, {0, -30, 12}, {0, -20, 12} }) {
            Vec3 target = eye.add(t[0], t[1], t[2]);
            Ballistics.Solution s = Ballistics.solve(eye, target, Vec3.ZERO, ARROW, motion, false);
            assertTrue(passes(eye, s, motion, false, target) < 0.15, "misses " + target.subtract(eye));
            assertTrue(Math.abs(s.miss()) < 0.15, "reported miss " + s.miss());
        }
        // the second audit's case: rising 0.5, 2 forward, a target 20 down and 15 out
        Vec3 target = eye.add(0, -20, 15), fast = new Vec3(0, 0.5, 2);
        for (boolean onGround : new boolean[]{false, true}) {
            Ballistics.Solution s = Ballistics.solve(eye, target, Vec3.ZERO, ARROW, fast, onGround);
            assertTrue(passes(eye, s, fast, onGround, target) < 0.15, "onGround " + onGround);
        }
    }

    @Test
    void aShotThatFliesOffSidewaysIsOutOfReach() {
        // the second audit's case: moving 1 a tick sideways and 1 forward, a target 20 down and 15 out; at the pitch
        // the solve lands on, the arrow's own speed across is less than the shooter's, so no yaw takes the drift out.
        // It crossed the range at the right height 6.8 blocks to the side and read as in reach, miss 0.00.
        Vec3 eye = new Vec3(0, 70, 0), target = eye.add(0, -20, 15), motion = new Vec3(1, 0, 1);
        for (boolean onGround : new boolean[]{false, true}) {
            Ballistics.Solution s = Ballistics.solve(eye, target, Vec3.ZERO, ARROW, motion, onGround);
            double passed = passes(eye, s, motion, onGround, target);
            assertTrue(passed > 5, "the shot passes " + passed + " away");
            assertTrue(Math.abs(s.miss()) > 5, "and the miss says so: " + s.miss());
        }
    }
}
