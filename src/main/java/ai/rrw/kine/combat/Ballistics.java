package ai.rrw.kine.combat;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Projectile flight and the aim solve, free of the level: tick-by-tick flight as the game steps it
 * (gravity and drag, in the projectile's own order), and the launch pitch and yaw that put the
 * projectile on a target, allowing for drag, the shooter's motion and the target's lead (its course, which
 * falls back to the ground for a target gravity acts on). Collision is supplied by the caller.
 */
public final class Ballistics {
    private Ballistics() {}

    public static final int MAX_TICKS   = 120;   // the longest flight simulated
    static final int        LEAD_ITERS  = 12;    // most outer passes for target lead and azimuth (flight time depends on the arc)
    static final double     LEAD_OK     = 0.02;  // blocks: the lead has settled when its flight time moves the target less than this
    static final double     YAW_OK      = 1e-3;  // degrees: and the azimuth has settled when a pass moves it less than this
    static final int        PITCH_ITERS = 6;     // secant steps for the vertical solve (it converges in 2 to 4)

    /** A projectile's flight: gravity and drag per tick, launch speed, a launch pitch offset (thrown
     *  potions go 20 degrees high), whether gravity applies before drag, whether it is a weapon, and
     *  whether it takes on the shooter's motion (shootFromRotation does; a crossbow's shoot does not). */
    public record Spec(double gravity, double drag, double speed,
                       double yOffsetDeg, boolean gravityFirst, boolean weapon, boolean inherits) {
        public Spec(double gravity, double drag, double speed, double yOffsetDeg, boolean gravityFirst, boolean weapon) {
            this(gravity, drag, speed, yOffsetDeg, gravityFirst, weapon, true);
        }
    }

    /** Where a flight segment hits something: the point, the surface normal (null to keep up), and the
     *  entity hit, if any. */
    public record Hit<E>(Vec3 point, Vec3 normal, E entity) {}

    /** Tests one tick's segment of flight for a hit; null if clear. `tick` counts from 1 for the first
     *  tick of flight (the game widens entity hitboxes with a projectile's age). */
    public interface Collider<E> { Hit<E> test(Vec3 from, Vec3 to, int tick); }

    /** A simulated flight: impact (null if none within MAX_TICKS), normal, entity hit, the points flown
     *  through (start first), and the tick of impact. */
    public record Flight<E>(Vec3 impact, Vec3 normal, E entity, List<Vec3> path, int ticks) {}

    /** Launch velocity for an aim, as in Projectile.shootFromRotation, plus the shooter's own motion
     *  (vertical motion only when airborne). */
    public static Vec3 launchVel(double yaw, double pitch, Spec spec, Vec3 shooterMotion, boolean onGround) {
        Vec3 dir = launchDir(yaw, pitch, spec).scale(spec.speed());
        Vec3 km = shooterMotion;
        return dir.add(km.x, onGround ? 0.0 : km.y, km.z);
    }

    /**
     * Ticks from a turn made at the end of a client tick to the shot it aims (26.1.2). The turn is made on
     * END_CLIENT_TICK, after LocalPlayer.tick has sent the tick's move packet, so the server first has it from the
     * next tick's move packet. A use packet (a crossbow, a thrown item) carries the rotation and goes in the next
     * tick's handleKeybinds: 1 tick. A release (RELEASE_USE_ITEM: a bow, a trident) carries none, so the server
     * shoots along the rotation of the last move packet; handleKeybinds runs before the tick's move packet, so the
     * release that fires this turn is the one two ticks on: 2 ticks.
     */
    public static int ticksToShot(boolean release) { return release ? 2 : 1; }

    /**
     * Where the server spawns the shot a turn made now aims: the shooter's position in the last move packet it has,
     * at eye height less 0.1 (AbstractArrow, Projectile). For a use packet, this tick's position (eye); for a
     * release, the next tick's move packet's, one tick of the shooter's motion further on.
     */
    public static Vec3 spawnPoint(Vec3 eye, Vec3 shooterMotion, boolean release) {
        Vec3 s = new Vec3(eye.x, eye.y - 0.1F, eye.z);
        return release ? s.add(shooterMotion) : s;
    }

    /** Unit launch direction for an aim, before the server's random spread (the throw offset included). */
    public static Vec3 launchDir(double yaw, double pitch, Spec spec) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch), pro = Math.toRadians(pitch + spec.yOffsetDeg());
        double xd = -Math.sin(yr) * Math.cos(pr);
        double yd = -Math.sin(pro);
        double zd = Math.cos(yr) * Math.cos(pr);
        return new Vec3(xd, yd, zd).normalize();
    }

    /** Step the projectile tick by tick until the collider reports a hit or MAX_TICKS pass. A null
     *  collider flies a free arc (the aim solve measures the drop at the target's range on it). */
    public static <E> Flight<E> simulate(Vec3 start, Vec3 vel, Spec spec, Collider<E> collider) {
        return simulate(start, vel, spec, collider, MAX_TICKS);
    }

    /** The same for up to `ticks` ticks. */
    public static <E> Flight<E> simulate(Vec3 start, Vec3 vel, Spec spec, Collider<E> collider, int ticks) {
        Vec3 pos = start, v = vel, nrm = new Vec3(0, 1, 0), imp = null;
        E ent = null;
        int hitTick = ticks;
        List<Vec3> pts = new ArrayList<>();
        pts.add(pos);
        for (int i = 0; i < ticks; i++) {
            if (spec.gravityFirst()) { v = v.add(0, -spec.gravity(), 0); v = v.scale(spec.drag()); }
            Vec3 next = pos.add(v);
            if (collider != null) {
                Hit<E> h = collider.test(pos, next, i + 1);
                if (h != null) {
                    imp = h.point(); ent = h.entity();
                    if (h.normal() != null) nrm = h.normal();
                    pts.add(imp); hitTick = i + 1; break;
                }
            }
            pos = next; pts.add(pos);
            if (!spec.gravityFirst()) { v = v.scale(spec.drag()); v = v.add(0, -spec.gravity(), 0); }
        }
        return new Flight<>(imp, nrm, ent, pts, hitTick);
    }

    /** Unit launch direction for a yaw and pitch (degrees, up negative). */
    static Vec3 dirOf(double yaw, double pitch) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        return new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
    }

    // Vertical miss (projectile minus target) where the arc crosses the target's horizontal range: the
    // objective of the pitch solve, monotonic for the low arc. Same arithmetic as launchVel and simulate, so
    // drag, the shooter's motion, the gravity order and any throw offset are included; flown past MAX_TICKS
    // until the arc reaches the range or falls far below it, so long lobs are measured where they cross.
    // Negative means short.
    static double vMissAtRange(Vec3 eye, double yaw, double pitch, Spec spec, double horiz, double dy,
                               Vec3 shooterMotion, boolean onGround) {
        Vec3 v0 = launchVel((float) yaw, (float) pitch, spec, shooterMotion, onGround);
        double vx = v0.x, vy = v0.y, vz = v0.z, px = eye.x, py = eye.y, pz = eye.z, h0 = 0;
        double g = spec.gravity(), drag = spec.drag();
        for (int i = 0; i < RANGE_TICKS; i++) {
            if (spec.gravityFirst()) { vy = vy - g; vx = vx * drag; vy = vy * drag; vz = vz * drag; }
            double nx = px + vx, ny = py + vy, nz = pz + vz;
            double h1 = Math.hypot(nx - eye.x, nz - eye.z);
            if (h1 >= horiz && h1 != h0) {
                double t = (horiz - h0) / (h1 - h0);
                return (py + t * (ny - py) - eye.y) - dy;
            }
            px = nx; py = ny; pz = nz; h0 = h1;
            if (!spec.gravityFirst()) { vx = vx * drag; vy = vy * drag; vz = vz * drag; vy = vy - g; }
            if (vy < 0 && py - eye.y < dy - 1000) break;   // far below the target and falling
        }
        return (py - eye.y) - dy;   // never reached the range
    }

    // Where the arc crosses a horizontal range, and when: ticks from the launch, fractional (the arrow moves a whole
    // tick at a time, so it passes there on its tick ceil(tick)).
    private record Crossing(Vec3 point, double tick) {}

    // The crossing on the flight of vMissAtRange; null if it never gets there.
    private static Crossing crossAtRange(Vec3 eye, double yaw, double pitch, Spec spec, double horiz, double dy,
                                         Vec3 shooterMotion, boolean onGround) {
        Vec3 v0 = launchVel((float) yaw, (float) pitch, spec, shooterMotion, onGround);
        double vx = v0.x, vy = v0.y, vz = v0.z, px = eye.x, py = eye.y, pz = eye.z, h0 = 0;
        double g = spec.gravity(), drag = spec.drag();
        for (int i = 0; i < RANGE_TICKS; i++) {
            if (spec.gravityFirst()) { vy = vy - g; vx = vx * drag; vy = vy * drag; vz = vz * drag; }
            double nx = px + vx, ny = py + vy, nz = pz + vz;
            double h1 = Math.hypot(nx - eye.x, nz - eye.z);
            if (h1 >= horiz && h1 != h0) {
                double t = (horiz - h0) / (h1 - h0);
                return new Crossing(new Vec3(px + t * (nx - px), py + t * (ny - py), pz + t * (nz - pz)), i + t);
            }
            px = nx; py = ny; pz = nz; h0 = h1;
            if (!spec.gravityFirst()) { vx = vx * drag; vy = vy * drag; vz = vz * drag; vy = vy - g; }
            if (vy < 0 && py - eye.y < dy - 1000) break;   // far below the target and falling
        }
        return null;
    }

    // A solved aim's miss: how far the arc passes from the led point where it crosses that point's range (c), above
    // (+) or below (-), counting a miss to the side. The yaw solve can't always take out the shooter's sideways
    // motion (solveYaw then falls back to the bearing), and a shot that crosses the range at the right height but
    // blocks to the side is out of reach, not on target. Without a crossing, the vertical miss where it falls short.
    private static double missAt(Crossing c, Vec3 eye, double yaw, double pitch, Spec spec, Vec3 lead, Vec3 shooterMotion, boolean onGround) {
        if (c == null) {
            Vec3 d = lead.subtract(eye);
            return vMissAtRange(eye, yaw, pitch, spec, Math.sqrt(d.x * d.x + d.z * d.z), d.y, shooterMotion, onGround);
        }
        Vec3 p = c.point();
        double up = p.y - lead.y, m = Math.sqrt(up * up + (p.x - lead.x) * (p.x - lead.x) + (p.z - lead.z) * (p.z - lead.z));
        return up >= 0 ? m : -m;
    }

    // Out of reach, the best try is the shot that comes down at the target's height farthest out: the pitch
    // with the longest range at that height, coarse in 2 degree steps, then refined to a quarter degree.
    private static double longestPitch(Vec3 eye, double yaw, Spec spec, double dy, Vec3 shooterMotion, boolean onGround) {
        double best = -45.0, bestR = Double.NEGATIVE_INFINITY;
        for (double p = 0.0; p >= -80.0; p -= 2.0) {
            double r = rangeAtHeight(eye, yaw, p, spec, dy, shooterMotion, onGround);
            if (r > bestR) { bestR = r; best = p; }
        }
        double c = best;
        for (double p = c - 2.0; p <= c + 2.0; p += 0.25) {
            double r = rangeAtHeight(eye, yaw, p, spec, dy, shooterMotion, onGround);
            if (r > bestR) { bestR = r; best = p; }
        }
        return best;
    }

    // How far out (horizontally) the arc comes back down through the target's height; if it never rises that
    // high, a large negative number ranked by how far its top falls short.
    private static double rangeAtHeight(Vec3 eye, double yaw, double pitch, Spec spec, double dy, Vec3 shooterMotion, boolean onGround) {
        Vec3 v0 = launchVel((float) yaw, (float) pitch, spec, shooterMotion, onGround);
        double vx = v0.x, vy = v0.y, vz = v0.z, px = eye.x, py = eye.y, pz = eye.z, top = py;
        double g = spec.gravity(), drag = spec.drag(), level = eye.y + dy;
        for (int i = 0; i < RANGE_TICKS; i++) {
            if (spec.gravityFirst()) { vy = vy - g; vx = vx * drag; vy = vy * drag; vz = vz * drag; }
            double nx = px + vx, ny = py + vy, nz = pz + vz;
            if (vy < 0 && py >= level && ny < level) {
                double t = (py - level) / (py - ny);
                return Math.hypot(px + t * (nx - px) - eye.x, pz + t * (nz - pz) - eye.z);
            }
            px = nx; py = ny; pz = nz; top = Math.max(top, py);
            if (!spec.gravityFirst()) { vx = vx * drag; vy = vy * drag; vz = vz * drag; vy = vy - g; }
            if (vy < 0 && py < level) break;
        }
        return -1e6 - (level - top);
    }

    static final int RANGE_TICKS = 2000;   // longest flight the pitch solve measures (horizontal speed decays to nothing)

    /** Closed-form launch pitch without drag (low arc), degrees, up negative; NaN beyond the drag-free range. */
    static double seedPitch(Spec spec, double horiz, double dy) {
        double v2 = spec.speed() * spec.speed();
        double disc = v2 * v2 - spec.gravity() * (spec.gravity() * horiz * horiz + 2 * dy * v2);
        if (disc < 0) return Double.NaN;
        double tan = (v2 - Math.sqrt(disc)) / (spec.gravity() * horiz);
        return -Math.toDegrees(Math.atan(tan));
    }

    /** Launch pitch so the arc crosses the target's range at the target's height, on the low arc. First
     *  the closed-form seed and secant steps on the vertical miss, which converge in 2 to 4 steps on most
     *  shots; if they miss by more than MISS_OK or land on the high arc (near the longest range the seed is
     *  poor and the miss curve flattens), a bracket is found from below and closed by regula falsi. Out of
     *  range, the pitch that carries furthest toward the target's height. */
    static double solvePitch(Vec3 eye, double yaw, Spec spec, double horiz, double dy, Vec3 shooterMotion, boolean onGround) {
        double seed = seedPitch(spec, horiz, dy);
        double p0 = Double.isNaN(seed) ? -45.0 : seed;       // beyond the drag-free range: start at 45 degrees up
        // 1 degree higher, for the first secant slope; lower instead when higher would pass straight up
        double p1 = p0 - 1.0 >= -MAX_PITCH ? p0 - 1.0 : p0 + 1.0;
        double f0 = vMissAtRange(eye, yaw, p0, spec, horiz, dy, shooterMotion, onGround);
        double f1 = vMissAtRange(eye, yaw, p1, spec, horiz, dy, shooterMotion, onGround);
        for (int i = 0; i < PITCH_ITERS; i++) {
            if (Math.abs(f1) < MISS_OK) break;
            double den = f1 - f0;
            if (Math.abs(den) < 1e-9) break;
            double p2 = Mth.clamp(p1 - f1 * (p1 - p0) / den, -MAX_PITCH, MAX_PITCH);
            p0 = p1; f0 = f1; p1 = p2; f1 = vMissAtRange(eye, yaw, p1, spec, horiz, dy, shooterMotion, onGround);
        }
        // accept only a low-arc answer: there, aiming a little lower lands lower
        if (Math.abs(f1) < MISS_OK
            && vMissAtRange(eye, yaw, Math.min(MAX_PITCH, p1 + 0.5), spec, horiz, dy, shooterMotion, onGround) < f1) return p1;
        return bracketPitch(eye, yaw, spec, horiz, dy, shooterMotion, onGround);
    }

    static final double MISS_OK = 0.02;   // vertical miss (blocks) a pitch solve accepts
    static final double MAX_PITCH = 89.99;   // steepest pitch tried, up or down (a target 1 block out and 50 up needs 89.07)

    // From 10 degrees below the line of sight, short unless the shooter's own forward or upward motion carries the
    // shot on (then step down 2 degrees at a time to the first short pitch), step up 2 degrees at a time to the
    // first pitch that is not short, then close the bracket by regula falsi (the Illinois variant, which can't
    // stall on one side). With no sign change before the miss starts falling (out of range), the best pitch seen.
    private static double bracketPitch(Vec3 eye, double yaw, Spec spec, double horiz, double dy, Vec3 shooterMotion, boolean onGround) {
        double los = -Math.toDegrees(Math.atan2(dy, horiz));
        double a = Math.min(MAX_PITCH, los + 10.0);
        double fa = vMissAtRange(eye, yaw, a, spec, horiz, dy, shooterMotion, onGround);
        while (fa >= 0 && a < MAX_PITCH) {
            a = Math.min(MAX_PITCH, a + 2.0);
            fa = vMissAtRange(eye, yaw, a, spec, horiz, dy, shooterMotion, onGround);
        }
        if (fa >= 0) return a;   // over the target even aimed straight down
        double b = Double.NaN, fb = Double.NaN;
        for (double p = a - 2.0; p >= -MAX_PITCH; p -= 2.0) {
            double f = vMissAtRange(eye, yaw, p, spec, horiz, dy, shooterMotion, onGround);
            if (f >= 0) { b = p; fb = f; break; }
            if (f < fa) break;   // the miss is falling again: past the longest range, so out of reach
            a = p; fa = f;
        }
        if (Double.isNaN(b)) return longestPitch(eye, yaw, spec, dy, shooterMotion, onGround);   // out of reach
        int side = 0;
        double c = b;
        for (int i = 0; i < 30; i++) {
            c = (a * fb - b * fa) / (fb - fa);
            double fc = vMissAtRange(eye, yaw, c, spec, horiz, dy, shooterMotion, onGround);
            if (Math.abs(fc) < MISS_OK) return c;
            if (fc < 0) { a = c; fa = fc; if (side == -1) fb /= 2; side = -1; }
            else        { b = c; fb = fc; if (side == 1) fa /= 2; side = 1; }
        }
        return c;
    }

    /** Yaw that points the projectile's horizontal velocity at the target, allowing for the shooter's
     *  motion: speed*cos(pitch)*[-sin yaw, cos yaw] + shooter velocity must be parallel to the bearing.
     *  Drag scales x and z equally, so the horizontal path is a straight line and one step is exact. */
    static double solveYaw(Vec3 eye, Vec3 lead, double pitch, Spec spec, Vec3 shooterMotion) {
        double bearing = Math.toDegrees(Math.atan2(-(lead.x - eye.x), lead.z - eye.z));
        double ux = lead.x - eye.x, uz = lead.z - eye.z, un = Math.sqrt(ux * ux + uz * uz);
        if (un < 1e-9) return bearing;
        ux /= un; uz /= un;
        double pr = Math.toRadians(pitch), pro = Math.toRadians(pitch + spec.yOffsetDeg());
        double hu = Math.cos(pr);
        double a = spec.speed() * hu / Math.hypot(hu, Math.sin(pro));   // horizontal launch speed, without the shooter's motion
        double kmx = shooterMotion.x, kmz = shooterMotion.z;
        double ukm = ux * kmx + uz * kmz, c = kmx * kmx + kmz * kmz - a * a, disc = ukm * ukm - c;
        if (disc < 0 || a < 1e-9) return bearing;
        double s = ukm + Math.sqrt(disc);
        return Math.toDegrees(Math.atan2(-(s * ux - kmx), s * uz - kmz));
    }

    /** A solved aim: the launch yaw and pitch, and how far the shot passes from the led point where it crosses
     *  that point's range, in blocks, above (+) or below (-), a miss to the side included: about zero in reach,
     *  far off out of reach (the pitch is then the one that carries furthest). */
    public record Solution(double yaw, double pitch, double miss) {}

    /** The vertical motion a living entity keeps each tick of a fall (LivingEntity.travelInAir, the float 0.98). */
    static final double FALL_KEEP = 0.98F;

    /**
     * How a target moves on from where it is now, tick by tick. Straight on at its per-tick motion; or, for a target
     * gravity acts on (falls), straight on across while its height follows LivingEntity.travelInAir (26.1.2): each
     * tick it moves by its vertical motion, which then loses the gravity (with slow falling, at most 0.01 while it
     * isn't rising) and keeps FALL_KEEP of the rest, a motion under 0.003 counting as none (LivingEntity.aiStep);
     * and it stops on the floor, the height its box comes down on (relative to now, 0 or below; negative infinity
     * when none is known). A new jump isn't foreseen.
     */
    public record Course(Vec3 motion, boolean falls, double gravity, boolean slowFalling, double floor) {
        /** A target that keeps its present motion, the vertical part included. */
        public static Course straight(Vec3 motion) { return new Course(motion, false, 0.0, false, Double.NEGATIVE_INFINITY); }

        /** Where the target is t ticks on, relative to now; between whole ticks, on the line from one to the next. */
        public Vec3 at(double t) { return new Vec3(motion.x * t, dy(t), motion.z * t); }

        /** Its height t ticks on, relative to now. */
        public double dy(double t) {
            if (!falls) return motion.y * t;
            int k = (int) Math.floor(t);
            double y = 0.0, v = motion.y;   // the height after i ticks, and the move that took it there (at first, the last tick's)
            for (int i = 0; ; i++) {
                double g = slowFalling && v <= 0 ? Math.min(gravity, 0.01) : gravity;
                v = (v - g) * FALL_KEEP;
                if (Math.abs(v) < 0.003) v = 0.0;
                double next = Math.max(floor, y + v);
                if (i == k) return y + (t - k) * (next - y);
                if (next == floor && v < 0) return floor;   // landed, and stays there
                y = next;
            }
        }

        /** Its height t0 + age ticks on, relative to t0, for each age from 0 to n (the offsets a hit chance moves
         *  the target's box by, the arrow's age counting from its spawn t0 ticks on). */
        public double[] dyTable(double t0, int n) {
            double base = dy(t0);
            double[] out = new double[n + 1];
            for (int age = 0; age <= n; age++) out[age] = dy(t0 + age) - base;
            return out;
        }
    }

    /**
     * Ballistic solve with target lead. The target's centre is `center` when the shot spawns, which is `start` ticks
     * along its course; at a flight time of t ticks it is that course's t ticks further on. The lead is a fixed point
     * on the flight time: the shot solved for the target's position at time t crosses that position's range at a
     * time T(t), and the lead is right when T(t) = t. Each pass solves pitch, then yaw, for the lead at the present
     * guess, and the next guess is a secant step on T(t) - t, with T the fractional tick of the crossing so that it
     * changes smoothly; once there are guesses on both sides, a step that leaves them halves them instead. (The plain
     * step t = T(t), of which there used to be three passes, runs away from a target
     * moving off near the end of the range, where the flight time grows faster than the target's distance.) Settled
     * when the flight time moves the target less than LEAD_OK and the yaw moves less than YAW_OK; a lead that
     * doesn't settle in LEAD_ITERS passes is out of reach, an infinite miss. Then onWholeTick.
     */
    public static Solution solve(Vec3 eye, Vec3 center, Course course, double start, Spec spec, Vec3 shooterMotion, boolean onGround) {
        Vec3 from = course.at(start);
        double yaw = Math.toDegrees(Math.atan2(-(center.x - eye.x), center.z - eye.z));
        double pitch = 0.0, miss = 0.0;
        double t = 0.0, lastT = Double.NaN, lastGap = Double.NaN;
        double lo = 0.0, hi = Double.NaN;   // flight times the lead is short of (T > t) and past (T < t), once found
        boolean settled = false;
        for (int pass = 0; pass < LEAD_ITERS; pass++) {
            Vec3 lead = center.add(course.at(start + t).subtract(from));
            Vec3 d = lead.subtract(eye);
            double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
            if (horiz < 1e-3) return new Solution(yaw, pitch, miss);
            double lastYaw = yaw;
            pitch = solvePitch(eye, yaw, spec, horiz, d.y, shooterMotion, onGround);
            yaw   = solveYaw(eye, lead, pitch, spec, shooterMotion);
            Crossing c = crossAtRange(eye, yaw, pitch, spec, horiz, d.y, shooterMotion, onGround);
            miss  = missAt(c, eye, yaw, pitch, spec, lead, shooterMotion, onGround);
            double flight = c != null ? c.tick() : RANGE_TICKS;   // never reaches the range: as long a flight as there is
            settled = course.at(start + flight).subtract(course.at(start + t)).length() < LEAD_OK;
            if (settled && Math.abs(Mth.wrapDegrees(yaw - lastYaw)) < YAW_OK) break;
            if (settled) continue;   // the lead holds; another pass for the yaw
            double gap = flight - t;
            if (gap > 0 && t >= lo && !(t >= hi)) lo = t;
            if (gap < 0 && t > lo && !(t >= hi)) hi = t;
            double next = Double.isNaN(lastGap) || gap == lastGap || t == lastT ? flight : t - gap * (t - lastT) / (gap - lastGap);
            if (!Double.isNaN(hi) && !(next > lo && next < hi)) next = (lo + hi) / 2;   // a secant step out of the bracket: halve it
            lastT = t; lastGap = gap;
            t = Mth.clamp(next, 0.0, RANGE_TICKS);
        }
        if (!settled) return new Solution(yaw, pitch, Double.POSITIVE_INFINITY);
        Solution whole = onWholeTick(eye, center.add(course.at(start + Math.ceil(t)).subtract(from)), (int) Math.ceil(t),
                                     yaw, spec, shooterMotion, onGround);
        return whole != null && Math.abs(whole.miss()) <= Math.max(Math.abs(miss), MISS_OK) ? whole : new Solution(yaw, pitch, miss);
    }

    // The game moves an arrow a whole tick at a time and tests each move against the target's box where the target is
    // that tick (AbstractArrow.tick, after the target's own move: the level ticks entities in the order they joined
    // it, and a player moves on its packets between ticks), as the hit chance and the reticle model it. So the arrow
    // that crosses the range during its tick j meets the target's tick-j position, not the one at the fractional time
    // the lead settled on, which can be most of a tick of the target's motion short of it. The aim at the tick-j
    // position (j the settled time rounded up), if its arc does cross that range during tick j; null if not (a target
    // coming on fast can pass between two of the arrow's ticks). The caller keeps the settled aim when this one
    // reaches its point no better.
    private static Solution onWholeTick(Vec3 eye, Vec3 lead, int j, double yaw, Spec spec, Vec3 shooterMotion, boolean onGround) {
        Vec3 d = lead.subtract(eye);
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        if (j < 1 || horiz < 1e-3) return null;
        double pitch = 0.0;
        for (int pass = 0; pass < LEAD_ITERS; pass++) {
            double lastYaw = yaw;
            pitch = solvePitch(eye, yaw, spec, horiz, d.y, shooterMotion, onGround);
            yaw   = solveYaw(eye, lead, pitch, spec, shooterMotion);
            if (Math.abs(Mth.wrapDegrees(yaw - lastYaw)) < YAW_OK) break;
        }
        Crossing c = crossAtRange(eye, yaw, pitch, spec, horiz, d.y, shooterMotion, onGround);
        if (c == null || c.tick() <= j - 1 || c.tick() > j) return null;
        return new Solution(yaw, pitch, missAt(c, eye, yaw, pitch, spec, lead, shooterMotion, onGround));
    }

    /** The solve for a target that keeps its present motion. */
    public static Solution solve(Vec3 eye, Vec3 center, Vec3 targetMotion, Spec spec, Vec3 shooterMotion, boolean onGround) {
        return solve(eye, center, Course.straight(targetMotion), 0.0, spec, shooterMotion, onGround);
    }

    /** The solve as a point along the launch direction from `eye` (the caller recovers yaw and pitch from it). */
    public static Vec3 solveAim(Vec3 eye, Vec3 center, Course course, double start, Spec spec, Vec3 shooterMotion, boolean onGround) {
        Solution s = solve(eye, center, course, start, spec, shooterMotion, onGround);
        return eye.add(dirOf(s.yaw(), s.pitch()));
    }

    public static Vec3 solveAim(Vec3 eye, Vec3 center, Vec3 targetMotion, Spec spec, Vec3 shooterMotion, boolean onGround) {
        return solveAim(eye, center, Course.straight(targetMotion), 0.0, spec, shooterMotion, onGround);
    }
}
