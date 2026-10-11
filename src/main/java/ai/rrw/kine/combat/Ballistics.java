package ai.rrw.kine.combat;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Projectile flight and the aim solve, free of the level: tick-by-tick flight as the game steps it
 * (gravity and drag, in the projectile's own order), and the launch pitch and yaw that put the
 * projectile on a target, allowing for drag, the shooter's motion and the target's lead. Collision is
 * supplied by the caller.
 */
public final class Ballistics {
    private Ballistics() {}

    public static final int MAX_TICKS   = 120;   // the longest flight simulated
    static final int        LEAD_ITERS  = 3;     // outer passes for target lead and azimuth (flight time depends on the arc)
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

    /** First tick at which the free arc reaches the target's horizontal range (the flight time for lead). */
    static int ticksToHoriz(List<Vec3> path, Vec3 eye, double horiz) {
        for (int i = 1; i < path.size(); i++)
            if (Math.hypot(path.get(i).x - eye.x, path.get(i).z - eye.z) >= horiz) return i;
        return path.size() - 1;
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
        double p1 = p0 - 1.0;                                 // 1 degree higher, for the first secant slope
        double f0 = vMissAtRange(eye, yaw, p0, spec, horiz, dy, shooterMotion, onGround);
        double f1 = vMissAtRange(eye, yaw, p1, spec, horiz, dy, shooterMotion, onGround);
        for (int i = 0; i < PITCH_ITERS; i++) {
            if (Math.abs(f1) < MISS_OK) break;
            double den = f1 - f0;
            if (Math.abs(den) < 1e-9) break;
            double p2 = Mth.clamp(p1 - f1 * (p1 - p0) / den, -89.0, 89.0);
            p0 = p1; f0 = f1; p1 = p2; f1 = vMissAtRange(eye, yaw, p1, spec, horiz, dy, shooterMotion, onGround);
        }
        // accept only a low-arc answer: there, aiming a little lower lands lower
        if (Math.abs(f1) < MISS_OK
            && vMissAtRange(eye, yaw, Math.min(89.0, p1 + 0.5), spec, horiz, dy, shooterMotion, onGround) < f1) return p1;
        return bracketPitch(eye, yaw, spec, horiz, dy, shooterMotion, onGround);
    }

    static final double MISS_OK = 0.02;   // vertical miss (blocks) a pitch solve accepts

    // From 10 degrees below the line of sight (always short), step up 2 degrees at a time to the first pitch
    // that is not short, then close the bracket by regula falsi (the Illinois variant, which can't stall on
    // one side). With no sign change before the miss starts falling (out of range), the best pitch seen.
    private static double bracketPitch(Vec3 eye, double yaw, Spec spec, double horiz, double dy, Vec3 shooterMotion, boolean onGround) {
        double los = -Math.toDegrees(Math.atan2(dy, horiz));
        double a = Math.min(89.0, los + 10.0);
        double fa = vMissAtRange(eye, yaw, a, spec, horiz, dy, shooterMotion, onGround);
        if (fa >= 0) return a;
        double b = Double.NaN, fb = Double.NaN;
        for (double p = a - 2.0; p >= -89.0; p -= 2.0) {
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

    /** Ballistic solve with target lead: a point along the launch direction from `eye`. The outer loop
     *  refines the lead, since the flight time depends on the arc; each pass solves pitch, then yaw. */
    public static Vec3 solveAim(Vec3 eye, Vec3 center, Vec3 targetMotion, Spec spec, Vec3 shooterMotion, boolean onGround) {
        Vec3 lead = center;
        double yaw = Math.toDegrees(Math.atan2(-(center.x - eye.x), center.z - eye.z));
        double pitch = 0.0;
        for (int outer = 0; outer < LEAD_ITERS; outer++) {
            Vec3 d = lead.subtract(eye);
            double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
            if (horiz < 1e-3) break;
            pitch = solvePitch(eye, yaw, spec, horiz, d.y, shooterMotion, onGround);
            yaw   = solveYaw(eye, lead, pitch, spec, shooterMotion);
            int k = ticksToHoriz(simulate(eye, launchVel((float) yaw, (float) pitch, spec, shooterMotion, onGround), spec, null).path(), eye, horiz);
            lead = center.add(targetMotion.scale(k));
        }
        return eye.add(dirOf(yaw, pitch));   // the caller recovers yaw and pitch from the direction to this point
    }
}
