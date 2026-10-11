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

    @Test
    void aFallingTargetIsMetOnItsWayDown() {
        // a zombie stepping off a ledge 12 blocks up, 30 blocks out: aimed along its fall, the chance counts it there
        Ballistics.Course fall = new Ballistics.Course(new Vec3(0, -0.0784, 0), true, 0.08, false, -12.0);
        Box box = Box.ofFeet(0, 12, 30, 0.6, 1.95);
        Target t = Target.falling(box, 0, 0, fall.dyTable(0.0, 400));
        Vec3 aim = Ballistics.solveAim(EYE, new Vec3(0, 12.975, 30), fall, 0.0, ARROW, Vec3.ZERO, true).subtract(EYE);
        double led = HitChance.chance(new Shot(0, SPAWN_Y, 0, aim.x, aim.y, aim.z, 3.0, 1.0, 0, 0, 0), t, GROUND, 4096, 400);
        assertTrue(led > 0.95, "led along its fall, got " + led);
        // aimed where it stands, the arrow passes over it
        double[] d = AIMER.aim(0, 12.975, 30);
        double still = HitChance.chance(new Shot(0, SPAWN_Y, 0, d[0], d[1], d[2], 3.0, 1.0, 0, 0, 0), t, GROUND, 4096, 400);
        assertTrue(still < 0.05, "aimed at where it stood, got " + still);
        // the table's lowest point ahead, for the flight's early stop
        assertEquals(-12.0, t.low()[0], 1e-12);
    }

    // ---- the block boxes filed by cell give exactly the answers of testing every box

    // the first entry into any of the boxes and whether one holds the point, testing every box (as fly did)
    static double everyEntry(List<Box> boxes, double x, double y, double z, double dx, double dy, double dz) {
        double best = 1.0;
        for (Box b : boxes) {
            double s = b.clip(x, y, z, dx, dy, dz);
            if (!Double.isNaN(s) && s < best) best = s;
        }
        return best;
    }

    @Test
    void theFiledBoxesAnswerAsEveryBoxDoes() {
        Random rnd = new Random(7);
        for (int trial = 0; trial < 200; trial++) {
            // full blocks, slabs, carpets, fences, thin panes, a box past a cell edge, now and then a wide one
            List<Box> boxes = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int bx = rnd.nextInt(8) - 4, by = rnd.nextInt(5) - 2, bz = rnd.nextInt(8) - 4;
                switch (rnd.nextInt(6)) {
                    case 0 -> boxes.add(new Box(bx, by, bz, bx + 1, by + 1, bz + 1));
                    case 1 -> boxes.add(new Box(bx, by, bz, bx + 1, by + 0.5, bz + 1));
                    case 2 -> boxes.add(new Box(bx, by, bz, bx + 1, by + 0.0625, bz + 1));
                    case 3 -> boxes.add(new Box(bx + 0.375, by, bz + 0.375, bx + 0.625, by + 1.5, bz + 0.625));
                    case 4 -> boxes.add(new Box(bx, by, bz + 0.4375, bx + 1, by + 1, bz + 0.5625));
                    default -> boxes.add(new Box(bx + 0.5, by, bz, bx + 1.5, by + 1, bz + 1));
                }
            }
            if (rnd.nextInt(4) == 0) boxes.add(new Box(-20, -3, -20, 20, -2, 20));
            HitChance.Grid grid = new HitChance.Grid(boxes);
            for (int k = 0; k < 500; k++) {
                // points on block edges and faces half the time, where the 1e-7 tolerance decides
                double x = edgy(rnd), y = edgy(rnd), z = edgy(rnd);
                double dx = (rnd.nextDouble() - 0.5) * 6, dy = (rnd.nextDouble() - 0.5) * 3, dz = (rnd.nextDouble() - 0.5) * 6;
                if (rnd.nextInt(5) == 0) dx = 0;
                if (rnd.nextInt(5) == 0) dy = rnd.nextBoolean() ? 0 : 1e-8;
                if (rnd.nextInt(5) == 0) { double e = (rnd.nextInt(3) - 1) * 5e-8; x = Math.round(x) + e; }
                assertEquals(everyEntry(boxes, x, y, z, dx, dy, dz), grid.firstEntry(x, y, z, dx, dy, dz), 0.0);
                boolean in = false;
                for (Box b : boxes) in |= b.contains(x, y, z);
                assertEquals(in, grid.contains(x, y, z));
            }
        }
    }

    static double edgy(Random rnd) {
        double v = (rnd.nextDouble() - 0.5) * 10;
        return rnd.nextBoolean() ? Math.round(v * 2) / 2.0 : v;
    }

    // the flight as it was before the boxes were filed: every box of the tick tested on every move
    static boolean everyBoxHits(Shot shot, double sx, double sy, double sz, Target target, HitChance.Blocks blocks, int maxTicks) {
        double len = Math.sqrt(shot.aimX() * shot.aimX() + shot.aimY() * shot.aimY() + shot.aimZ() * shot.aimZ());
        double vx = (shot.aimX() / len + sx) * shot.speed() + shot.inheritX();
        double vy = (shot.aimY() / len + sy) * shot.speed() + shot.inheritY();
        double vz = (shot.aimZ() / len + sz) * shot.speed() + shot.inheritZ();
        double x = shot.x(), y = shot.y(), z = shot.z();
        Box box0 = target.box();
        for (int age = 1; age <= maxTicks; age++) {
            List<Box> near = blocks.near(age);
            for (Box b : near) if (b.contains(x, y, z)) return false;
            double blockAt = everyEntry(near, x, y, z, vx, vy, vz);
            double ex = vx * blockAt, ey = vy * blockAt, ez = vz * blockAt;
            double[] dy = target.dy();
            int at = dy == null ? 0 : Math.min(age, dy.length - 1);
            Box tb = box0.move(target.vx() * age, dy == null ? target.vy() * age : dy[at], target.vz() * age);
            if (!Double.isNaN(tb.clip(x, y, z, ex, ey, ez))) return true;
            double m = HitChance.margin(age);
            if (m > 0) {
                double s = tb.inflate(m).clip(x, y, z, ex, ey, ez);
                if (!Double.isNaN(s)) {
                    double px = x + s * ex, py = y + s * ey, pz = z + s * ez;
                    double cx = tb.centerX() - px, cy = tb.centerY() - py, cz = tb.centerZ() - pz;
                    double end = everyEntry(near, px, py, pz, cx, cy, cz);
                    if (!Double.isNaN(tb.clip(px, py, pz, cx * end, cy * end, cz * end))) return true;
                }
            }
            if (blockAt < 1.0) return false;
            x += vx; y += vy; z += vz;
            vx *= HitChance.DRAG; vy *= HitChance.DRAG; vz *= HitChance.DRAG;
            vy -= HitChance.GRAVITY;
            boolean below = dy == null ? y < tb.minY() - 0.3 - Math.abs(target.vy()) * (maxTicks - age)
                                       : y < box0.minY() + target.low()[at] - 0.3;
            if (vy < 0 && below) return false;
        }
        return false;
    }

    // the block boxes along a shot, tick by tick, as AimAdvisor.blocksAlong reads them (one box per solid block)
    static List<List<Box>> boxesAlong(java.util.function.Predicate<int[]> solid, Shot s, int maxTicks, double widen) {
        double len = Math.sqrt(s.aimX() * s.aimX() + s.aimY() * s.aimY() + s.aimZ() * s.aimZ());
        double x = s.x(), y = s.y(), z = s.z();
        double vx = s.aimX() / len * s.speed(), vy = s.aimY() / len * s.speed(), vz = s.aimZ() / len * s.speed();
        double reach = HitChance.SPREAD * Math.sqrt(3), travelled = 0;
        List<List<Box>> out = new java.util.ArrayList<>();
        out.add(List.of());
        for (int age = 1; age <= maxTicks; age++) {
            double nx = x + vx, ny = y + vy, nz = z + vz;
            travelled += Math.sqrt(vx * vx + vy * vy + vz * vz);
            double r = reach * travelled + 0.8 + widen;
            List<Box> near = new java.util.ArrayList<>();
            for (int bx = (int) Math.floor(Math.min(x, nx) - r); bx <= (int) Math.floor(Math.max(x, nx) + r); bx++)
                for (int by = (int) Math.floor(Math.min(y, ny) - r); by <= (int) Math.floor(Math.max(y, ny) + r); by++)
                    for (int bz = (int) Math.floor(Math.min(z, nz) - r); bz <= (int) Math.floor(Math.max(z, nz) + r); bz++)
                        if (solid.test(new int[]{bx, by, bz})) near.add(new Box(bx, by, bz, bx + 1, by + 1, bz + 1));
            out.add(near);
            x = nx; y = ny; z = nz;
            vx *= HitChance.DRAG; vy *= HitChance.DRAG; vz *= HitChance.DRAG; vy -= HitChance.GRAVITY;
        }
        return out;
    }

    @Test
    void theChanceIsTheSameWithTheBoxesFiled() {
        // the best-aim measure's cases: flat ground, and a 1-high wall 2 blocks in front of the zombie; aims over the
        // box as the search tries them, a still and a moving target
        for (double dist : new double[]{20, 40, 60, 70, 100})
            for (boolean wall : new boolean[]{false, true}) {
                int wz = (int) Math.floor(dist - 2);
                java.util.function.Predicate<int[]> solid = c -> c[1] < 0 || (wall && c[1] == 0 && c[2] == wz && Math.abs(c[0]) <= 3);
                Box b = Box.ofFeet(0, 0, dist, 0.6, 1.95);
                Shot centre = aimedAt(b);
                List<List<Box>> perTick = boxesAlong(solid, centre, 70, 1.6);
                HitChance.Blocks blocks = age -> age < perTick.size() ? perTick.get(age) : List.of();
                for (Target t : new Target[]{Target.still(b), new Target(b, 0.2, 0, 0.1)})
                    for (double up : new double[]{-0.6, 0, 0.6})
                        for (double side : new double[]{-0.3, 0.3}) {
                            double[] d = AIMER.aim(b.centerX() + side, b.centerY() + up, b.centerZ());
                            Shot shot = new Shot(0, SPAWN_Y, 0, d[0], d[1], d[2], 3.0, 1.0, 0, 0, 0);
                            int hits = 0;
                            for (double[] r : HitChance.rolls(512))
                                if (everyBoxHits(shot, HitChance.SPREAD * r[0], HitChance.SPREAD * r[1], HitChance.SPREAD * r[2], t, blocks, 70)) hits++;
                            assertEquals(hits / 512.0, HitChance.chance(shot, t, blocks, 512, 70), 0.0,
                                         (wall ? "wall " : "flat ") + dist + " up " + up + " side " + side);
                        }
            }
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
