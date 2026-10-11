package ai.rrw.kine.combat;

import ai.rrw.kine.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Steps aside from incoming projectiles by pressing movement keys. Each projectile not fired by you is flown
 * forward as the game flies it (gravity and drag in its own order, for arrows and thrown items; other
 * projectiles in a straight line); if its path passes within HIT_RADIUS of your body within REACT_TICKS,
 * it holds the W/A/S/D combination that walks you most directly off its line, for as long as the threat
 * lasts. The game moves you by its own physics, so this works on foot (not while gliding or riding), at
 * walking or sprinting speed. It can't beat a point-blank or very fast shot, and a shooter who leads your
 * dodge still hits.
 */
public class ProjectileDodge {

    private static final int    REACT_TICKS  = 20;     // start dodging when impact is within 1 s
    private static final double HIT_RADIUS   = 1.1;    // body half-width + projectile + margin
    private static final double MIN_SPEED_SQ = 0.01;   // ignore stuck or nearly stopped projectiles

    // the eight key combinations: forward, backward, left, right
    private static final boolean[][] COMBOS = {
        {true, false, false, false}, {true, false, true, false}, {false, false, true, false}, {false, true, true, false},
        {false, true, false, false}, {false, true, false, true}, {false, false, false, true}, {true, false, false, true},
    };

    /** Where a projectile passes closest to a point within REACT_TICKS: the time (ticks), the vector from
     *  that point to `center`, and the projectile's velocity there; null if it is moving away. */
    record Pass(double time, Vec3 miss, Vec3 velocity) {}

    /** How a projectile flies on: arrows (and tridents) move, then lose 1% and fall 0.05; thrown items
     *  fall 0.03, lose 1%, then move (AbstractArrow.tick and ThrowableProjectile.tick). Other projectiles
     *  (fireballs, wind charges, shulker bullets) are taken to fly straight. */
    static Ballistics.Spec flightOf(Projectile proj) {
        if (proj instanceof AbstractArrow) return new Ballistics.Spec(proj.getGravity(), 0.99, 0, 0, false, false);
        if (proj instanceof ThrowableProjectile) return new Ballistics.Spec(proj.getGravity(), 0.99, 0, 0, true, false);
        return new Ballistics.Spec(0.0, 1.0, 0, 0, true, false);
    }

    /** The closest pass of a projectile at `pos` that moved `lastMove` in its last tick. For arrows that
     *  displacement is the velocity before this tick's drag and gravity, so those are applied first. */
    static Pass closestPass(Vec3 center, Vec3 pos, Vec3 lastMove, Ballistics.Spec flight) {
        Vec3 v = flight.gravityFirst() ? lastMove : lastMove.scale(flight.drag()).add(0, -flight.gravity(), 0);
        List<Vec3> path = Ballistics.simulate(pos, v, flight, null, REACT_TICKS).path();
        double best = Double.MAX_VALUE, bestT = 0; Vec3 bestMiss = null, bestVel = null;
        for (int i = 1; i < path.size(); i++) {
            Vec3 a = path.get(i - 1), seg = path.get(i).subtract(a);
            double len2 = seg.lengthSqr();
            double u = len2 < 1e-12 ? 0.0 : Math.max(0.0, Math.min(1.0, center.subtract(a).dot(seg) / len2));
            Vec3 miss = center.subtract(a.add(seg.scale(u)));
            double d = miss.lengthSqr();
            if (d < best) { best = d; bestT = i - 1 + u; bestMiss = miss; bestVel = seg; }
        }
        if (bestMiss == null || (bestT == 0.0 && center.subtract(pos).dot(path.get(1).subtract(pos)) < 0)) return null;   // moving away
        return new Pass(bestT, bestMiss, bestVel);
    }

    /** The keys to hold this tick: the dodge's movement keys while a projectile is about to hit, else as given. */
    public static Input adjustInput(LocalPlayer p, Input keys) {
        if (!Settings.projectileDodge || p.isSpectator() || p.isFallFlying() || p.isPassenger() || p.getAbilities().flying) return keys;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return keys;
        Vec3 dodge = threat(p, level);
        if (dodge == null) return keys;
        boolean[] c = bestCombo(dodge, p.getYRot());
        return new Input(c[0], c[1], c[2], c[3], keys.jump(), keys.shift(), keys.sprint());
    }

    // The escape direction from the soonest projectile on course to hit, or null if none is.
    private static Vec3 threat(LocalPlayer p, ClientLevel level) {
        Vec3 center = new Vec3(p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ());
        double bestT = Double.MAX_VALUE;
        Vec3 bestDodge = null;
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof Projectile proj)) continue;
            if (proj.getOwner() == p) continue;                 // never dodge our own shots

            Vec3 pp = proj.position();
            // Per-tick displacement is the most reliable velocity for a non-local entity (getDeltaMovement
            // can read stale or zero on interpolated projectiles); fall back to it only if there's no delta yet.
            Vec3 pv = new Vec3(proj.getX() - proj.xOld, proj.getY() - proj.yOld, proj.getZ() - proj.zOld);
            if (pv.lengthSqr() < MIN_SPEED_SQ) pv = proj.getDeltaMovement();
            if (pv.lengthSqr() < MIN_SPEED_SQ) continue;        // stuck arrow or spent projectile

            Pass pass = closestPass(center, pp, pv, flightOf(proj));
            if (pass == null || pass.miss().length() > HIT_RADIUS) continue;   // moving away, or it will miss
            if (pass.time() < bestT) {
                bestT = pass.time();
                bestDodge = dodgeDir(pass.miss(), pass.velocity());
            }
        }
        return bestDodge;
    }

    /** The key combination (forward, backward, left, right) whose walk direction at this yaw lies closest to
     *  the horizontal direction d. The game turns the keys' (left, forward) push by the yaw
     *  (Entity.getInputVector): x = left cos - forward sin, z = forward cos + left sin. */
    static boolean[] bestCombo(Vec3 d, float yaw) {
        double sin = Math.sin(Math.toRadians(yaw)), cos = Math.cos(Math.toRadians(yaw));
        boolean[] best = COMBOS[0];
        double bestDot = -Double.MAX_VALUE;
        for (boolean[] c : COMBOS) {
            double fwd = (c[0] ? 1 : 0) - (c[1] ? 1 : 0), left = (c[2] ? 1 : 0) - (c[3] ? 1 : 0);
            double n = Math.sqrt(fwd * fwd + left * left);
            double x = (left * cos - fwd * sin) / n, z = (fwd * cos + left * sin) / n;
            double dot = x * d.x + z * d.z;
            if (dot > bestDot) { bestDot = dot; best = c; }
        }
        return best;
    }

    /** Horizontal unit vector that steps us off the projectile's line: sideways to its horizontal flight, toward
     *  the side we already sit on (straight away from the line), either side if we're dead on it. A projectile
     *  coming straight down has no line to step off sideways, so then it's straight away from where it lands. */
    static Vec3 dodgeDir(Vec3 miss, Vec3 pv) {
        Vec3 hv = new Vec3(pv.x, 0, pv.z);
        if (hv.lengthSqr() < 1e-6) {
            Vec3 away = new Vec3(miss.x, 0, miss.z);
            return away.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : away.normalize();
        }
        Vec3 side = new Vec3(-hv.z, 0, hv.x).normalize();
        return miss.x * side.x + miss.z * side.z >= 0 ? side : side.scale(-1);
    }
}
