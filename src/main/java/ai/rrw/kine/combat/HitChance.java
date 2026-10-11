package ai.rrw.kine.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The chance that an arrow hits its target, from the game's own launch spread and arrow physics
 * (Minecraft 26.1.2: Projectile.getMovementToShoot and shootFromRotation, AbstractArrow.tick,
 * ProjectileUtil.getManyEntityHitResult and computeMargin, AABB.clip). Pure: no game classes, so it can be
 * checked on its own; the client code feeds it the shot, the target and the nearby block boxes.
 *
 * The server adds triangle(0, SPREAD * uncertainty) to each axis of the unit launch direction, scales the
 * result by the launch speed and adds the shooter's own motion. The roll happens on the server, so one shot
 * can't be predicted, but the distribution is exact. The chance is the share of that distribution whose path
 * reaches the target's box before any block, over a fixed set of rolls, so the same inputs always give the
 * same number.
 *
 * Not modelled: water (drag 0.6 instead of 0.99), targets that dodge or deflect arrows, other entities in the
 * way, and any change in the target's motion after the shot (it moves at a constant velocity, one box move per
 * arrow tick). The target's box is where it will be when the arrow spawns, which the caller works out: the client
 * draws other entities behind the server, and the release reaches the server after a network trip.
 */
public final class HitChance {
    private HitChance() {}

    /** Spread per axis of the unit launch direction, times the weapon's uncertainty (a bow uses 1). */
    public static final double SPREAD = 0.0172275;
    /** Velocity kept after each move in air (the game's float 0.99). */
    public static final double DRAG = 0.99F;
    /** Taken off the vertical velocity after the drag. */
    public static final double GRAVITY = 0.05;

    /** An axis-aligned box: a target's hitbox or a block's collision box. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public static Box ofFeet(double x, double y, double z, double width, double height) {
            double h = width / 2;
            return new Box(x - h, y, z - h, x + h, y + height, z + h);
        }

        public Box inflate(double d) {
            return new Box(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d);
        }

        public Box move(double dx, double dy, double dz) {
            return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
        }

        public double centerX() { return (minX + maxX) / 2; }
        public double centerY() { return (minY + maxY) / 2; }
        public double centerZ() { return (minZ + maxZ) / 2; }

        /** Half-open on the max side, like AABB.contains. */
        public boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
        }

        /**
         * Where the segment from (x, y, z) along (dx, dy, dz) first enters the box, as a fraction of the
         * segment in (0, 1), or NaN if it doesn't. A port of AABB.clip: only faces the segment enters
         * through count, so a segment that starts inside the box misses.
         */
        public double clip(double x, double y, double z, double dx, double dy, double dz) {
            double best = 1.0;
            boolean hit = false;
            final double e = 1.0E-7;
            if (dx > e || dx < -e) {
                double s = ((dx > 0 ? minX : maxX) - x) / dx;
                double pb = y + s * dy, pc = z + s * dz;
                if (0.0 < s && s < best && minY - e < pb && pb < maxY + e && minZ - e < pc && pc < maxZ + e) { best = s; hit = true; }
            }
            if (dy > e || dy < -e) {
                double s = ((dy > 0 ? minY : maxY) - y) / dy;
                double pb = z + s * dz, pc = x + s * dx;
                if (0.0 < s && s < best && minZ - e < pb && pb < maxZ + e && minX - e < pc && pc < maxX + e) { best = s; hit = true; }
            }
            if (dz > e || dz < -e) {
                double s = ((dz > 0 ? minZ : maxZ) - z) / dz;
                double pb = x + s * dx, pc = y + s * dy;
                if (0.0 < s && s < best && minX - e < pb && pb < maxX + e && minY - e < pc && pc < maxY + e) { best = s; hit = true; }
            }
            return hit ? best : Double.NaN;
        }
    }

    /**
     * The block boxes an arrow can meet on a given tick of its flight (1 on its first). A plain list serves
     * every tick; the client code passes, for each tick, only the boxes near where the arrow can be then.
     */
    public interface Blocks {
        List<Box> near(int age);

        static Blocks of(List<Box> all) { return age -> all; }
    }

    /**
     * One release of the weapon.
     * Start: where the server spawns the arrow, the shooter's position at eye height minus 0.1.
     * Aim: the launch direction before the spread, any length (the game normalizes it); for a bow, the
     * direction shootFromRotation builds from the shooter's rotation.
     * Speed: bow power times 3 (see bowPower). Uncertainty: 1 for a bow.
     * Inherited: the shooter's motion added to the arrow (its vertical part only while airborne).
     */
    public record Shot(double x, double y, double z, double aimX, double aimY, double aimZ,
                       double speed, double uncertainty, double inheritX, double inheritY, double inheritZ) {
        public Shot aimed(double ax, double ay, double az) {
            return new Shot(x, y, z, ax, ay, az, speed, uncertainty, inheritX, inheritY, inheritZ);
        }
    }

    /** The target's hitbox at release and its motion per tick. */
    public record Target(Box box, double vx, double vy, double vz) {
        public static Target still(Box box) { return new Target(box, 0, 0, 0); }
    }

    /**
     * An aim: the launch direction (unit length), the point on the target it was aimed at (before any lead
     * for the target's motion, which the aimer adds), its hit chance, and that point as offsets from the box
     * centre: up, and sideways as seen from the shooter (positive to the left of the line of fire).
     */
    public record Aim(double dirX, double dirY, double dirZ, double pointX, double pointY, double pointZ,
                      double chance, double up, double side) {}

    /** BowItem.getPowerForTime, in the game's float arithmetic. Launch speed is this times 3. */
    public static float bowPower(int ticksHeld) {
        float pow = ticksHeld / 20.0F;
        pow = (pow * pow + pow * 2.0F) / 3.0F;
        return Math.min(pow, 1.0F);
    }

    /** How far past a target's box an arrow still hits it, by the arrow's age (1 on its first tick). */
    public static double margin(int age) {
        return Math.max(0.0F, Math.min(0.3F, (age - 2) / 20.0F));
    }

    /** Inverse distribution function of triangle(0, a), the game's a * (nextDouble() - nextDouble()). */
    public static double triangleQuantile(double p, double a) {
        return p <= 0.5 ? a * (Math.sqrt(2 * p) - 1) : a * (1 - Math.sqrt(2 * (1 - p)));
    }

    /**
     * n equally likely spread rolls for a = 1 (scale by a), spread evenly over the three axes: the Halton
     * sequence in bases 2, 3 and 5 mapped through the triangle's quantile function. A product grid with the
     * same count leaves gaps along each axis, and the chance then moves in steps of several percent.
     */
    public static double[][] rolls(int n) {
        int[] base = {2, 3, 5};
        double[][] out = new double[n][3];
        for (int i = 0; i < n; i++)
            for (int k = 0; k < 3; k++) out[i][k] = triangleQuantile(radicalInverse(i + 1, base[k]), 1.0);
        return out;
    }

    private static double radicalInverse(int i, int b) {
        double f = 1.0, r = 0.0;
        while (i > 0) { f /= b; r += f * (i % b); i /= b; }
        return r;
    }

    // one set per count: the chance job uses 4,096 and the best-aim search 1,024 then 4,096, on two threads
    private static final Map<Integer, double[][]> cachedRolls = new ConcurrentHashMap<>();

    private static double[][] rollsCached(int n) {
        return cachedRolls.computeIfAbsent(n, HitChance::rolls);
    }

    /** The chance the shot hits, over n fixed spread rolls (4,096 is a good default), the same every call. */
    public static double chance(Shot shot, Target target, Blocks blocks, int n, int maxTicks) {
        double a = SPREAD * shot.uncertainty();
        int hits = 0;
        for (double[] s : rollsCached(n))
            if (hits(shot, a * s[0], a * s[1], a * s[2], target, blocks, maxTicks)) hits++;
        return hits / (double) n;
    }

    public static double chance(Shot shot, Target target, List<Box> blocks, int n, int maxTicks) {
        return chance(shot, target, Blocks.of(blocks), n, maxTicks);
    }

    /** Whether the shot hits with this spread roll (one value per axis, each in [-a, a]). */
    public static boolean hits(Shot shot, double sx, double sy, double sz, Target target, Blocks blocks, int maxTicks) {
        double len = Math.sqrt(shot.aimX() * shot.aimX() + shot.aimY() * shot.aimY() + shot.aimZ() * shot.aimZ());
        double vx = (shot.aimX() / len + sx) * shot.speed() + shot.inheritX();
        double vy = (shot.aimY() / len + sy) * shot.speed() + shot.inheritY();
        double vz = (shot.aimZ() / len + sz) * shot.speed() + shot.inheritZ();
        return fly(shot.x(), shot.y(), shot.z(), vx, vy, vz, target, blocks, maxTicks) == Outcome.TARGET;
    }

    public static boolean hits(Shot shot, double sx, double sy, double sz, Target target, List<Box> blocks, int maxTicks) {
        return hits(shot, sx, sy, sz, target, Blocks.of(blocks), maxTicks);
    }

    public enum Outcome { TARGET, BLOCK, NONE }

    /** Flies one arrow from (x, y, z) with launch velocity (vx, vy, vz), tick by tick as AbstractArrow.tick does. */
    public static Outcome fly(double x, double y, double z, double vx, double vy, double vz,
                              Target target, Blocks blocks, int maxTicks) {
        Box box0 = target.box();
        for (int age = 1; age <= maxTicks; age++) {
            List<Box> near = blocks.near(age);
            for (Box b : near) if (b.contains(x, y, z)) return Outcome.BLOCK;   // stuck where it stands

            // the block the move runs into first cuts the move short; only targets before it count
            double blockAt = 1.0;
            for (Box b : near) {
                double s = b.clip(x, y, z, vx, vy, vz);
                if (!Double.isNaN(s) && s < blockAt) blockAt = s;
            }
            double ex = vx * blockAt, ey = vy * blockAt, ez = vz * blockAt;

            Box tb = box0.move(target.vx() * age, target.vy() * age, target.vz() * age);
            if (!Double.isNaN(tb.clip(x, y, z, ex, ey, ez))) return Outcome.TARGET;
            double m = margin(age);
            if (m > 0) {
                double s = tb.inflate(m).clip(x, y, z, ex, ey, ez);
                if (!Double.isNaN(s) && marginHit(x + s * ex, y + s * ey, z + s * ez, tb, near)) return Outcome.TARGET;
            }
            if (blockAt < 1.0) return Outcome.BLOCK;

            x += vx; y += vy; z += vz;
            vx *= DRAG; vy *= DRAG; vz *= DRAG;
            vy -= GRAVITY;

            // falling and already below the target: it can't come back up
            if (vy < 0 && y < tb.minY() - 0.3 - Math.abs(target.vy()) * (maxTicks - age)) return Outcome.NONE;
        }
        return Outcome.NONE;
    }

    public static Outcome fly(double x, double y, double z, double vx, double vy, double vz,
                              Target target, List<Box> blocks, int maxTicks) {
        return fly(x, y, z, vx, vy, vz, target, Blocks.of(blocks), maxTicks);
    }

    /**
     * A move that only grazes the widened box still hits if, from where it touched, the straight line toward
     * the box's centre reaches the real box before any block (ProjectileUtil.getManyEntityHitResult).
     */
    private static boolean marginHit(double px, double py, double pz, Box tb, List<Box> blocks) {
        double dx = tb.centerX() - px, dy = tb.centerY() - py, dz = tb.centerZ() - pz;
        double end = 1.0;
        for (Box b : blocks) {
            double s = b.clip(px, py, pz, dx, dy, dz);
            if (!Double.isNaN(s) && s < end) end = s;
        }
        return !Double.isNaN(tb.clip(px, py, pz, dx * end, dy * end, dz * end));
    }

    /**
     * The aiming method the best-aim search uses: the unit launch direction whose no-spread path passes
     * through (px, py, pz), led for the target's motion, or null if the point is out of reach. In the game
     * this is Ballistics.solveAim, the same solve the auto aim uses every tick.
     */
    public interface Aimer {
        double[] aim(double px, double py, double pz);
    }

    /**
     * The aim with the best hit chance: the chance is worked out for aim points over the target's box (a
     * coarse grid in the plane facing the shooter, then two finer rounds around the best). Among points
     * within half a percentage point of the best, the one nearest the box centre wins, so an open target
     * keeps the centre and the choice doesn't wander; the result is never worse than aiming at the centre.
     * The shot's own aim is ignored. Null if no point on the box is in reach. Costs about 64 chance
     * evaluations (tens of milliseconds), so run it off the game thread.
     */
    public static Aim bestAim(Shot shot, Target target, Blocks blocks, int maxTicks, Aimer aimer) {
        Box b = target.box();
        double cx = b.centerX(), cy = b.centerY(), cz = b.centerZ();
        double bx = cx - shot.x(), bz = cz - shot.z(), dist = Math.sqrt(bx * bx + bz * bz);
        if (dist < 1e-6) return null;
        double lx = -bz / dist, lz = bx / dist;                 // sideways, as seen from the shooter
        double halfW = Math.abs(lx) * (b.maxX() - b.minX()) / 2 + Math.abs(lz) * (b.maxZ() - b.minZ()) / 2;
        double halfH = (b.maxY() - b.minY()) / 2 + 0.3;

        Aim best = null;
        List<Aim> tried = new ArrayList<>();
        double stepV = halfH / 4, stepL = halfW / 2;
        int[] rollsByRound = {1024, 4096, 4096};
        for (int round = 0; round < 3; round++) {
            int nv = round == 0 ? 4 : 1, nl = round == 0 ? 2 : 1;
            double v0 = best == null ? 0 : best.up(), l0 = best == null ? 0 : best.side();
            tried = new ArrayList<>();
            for (int i = -nv; i <= nv; i++)
                for (int j = -nl; j <= nl; j++) {
                    Aim a = aimAt(shot, target, blocks, maxTicks, aimer, cx, cy, cz, lx, lz, v0 + i * stepV, l0 + j * stepL, rollsByRound[round]);
                    if (a != null) tried.add(a);
                }
            if (tried.isEmpty()) return null;
            best = nearestCentreOfBest(tried);
            stepV /= 2; stepL /= 2;
        }
        // the box centre itself, at the last round's count, as a floor: the search never does worse than aiming at
        // the centre, and the centre wins any tie with the last round
        Aim centre = aimAt(shot, target, blocks, maxTicks, aimer, cx, cy, cz, lx, lz, 0, 0, 4096);
        if (centre != null) tried.add(centre);
        return nearestCentreOfBest(tried);
    }

    /** Chances within this of each other count as equal in the best-aim search. */
    static final double TIE = 0.005;

    /**
     * Of a round's aims, the one nearest the box centre among those within TIE of the round's best chance. Two
     * passes, so a run of near-ties can't walk the choice away from the best (comparing each aim only with the
     * last one kept can drift by TIE at every step).
     */
    static Aim nearestCentreOfBest(List<Aim> aims) {
        double max = Double.NEGATIVE_INFINITY;
        for (Aim a : aims) max = Math.max(max, a.chance());
        Aim pick = null;
        for (Aim a : aims)
            if (a.chance() >= max - TIE && (pick == null || Math.hypot(a.up(), a.side()) < Math.hypot(pick.up(), pick.side())))
                pick = a;
        return pick;
    }

    public static Aim bestAim(Shot shot, Target target, List<Box> blocks, int maxTicks, Aimer aimer) {
        return bestAim(shot, target, Blocks.of(blocks), maxTicks, aimer);
    }

    /** The aim through the box centre moved up by v and sideways by l, with its chance over n rolls. */
    private static Aim aimAt(Shot shot, Target target, Blocks blocks, int maxTicks, Aimer aimer,
                             double cx, double cy, double cz, double lx, double lz, double v, double l, int n) {
        double px = cx + l * lx, py = cy + v, pz = cz + l * lz;
        double[] dir = aimer.aim(px, py, pz);
        if (dir == null) return null;
        double c = chance(shot.aimed(dir[0], dir[1], dir[2]), target, blocks, n, maxTicks);
        return new Aim(dir[0], dir[1], dir[2], px, py, pz, c, v, l);
    }
}
