package ai.rrw.kine.combat;

import ai.rrw.kine.Settings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Sidesteps incoming projectiles. Each projectile not fired by you is flown forward as the game flies it
 * (gravity and drag in its own order, for arrows and thrown items; other projectiles in a straight line);
 * if its path passes within HIT_RADIUS of your body within REACT_TICKS, a sideways velocity at about
 * sprint speed carries you off its line. It can't beat a point-blank or very fast shot, and a shooter
 * who leads your dodge still hits.
 */
public class ProjectileDodge {

    private static final int    REACT_TICKS  = 20;     // start dodging when impact is within 1 s
    private static final double HIT_RADIUS   = 1.1;    // body half-width + projectile + margin
    private static final double DODGE_SPEED  = 0.32;   // lateral speed to top up to (about sprinting)
    private static final double MIN_SPEED_SQ = 0.01;   // ignore stuck or nearly stopped projectiles

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ProjectileDodge::tick);
    }

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

    private static void tick(Minecraft mc) {
        if (!Settings.projectileDodge) return;
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || p.isSpectator()) return;

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

        if (bestDodge == null) return;

        // Top the lateral component up to DODGE_SPEED along the escape direction. Re-running each
        // tick sustains the jink against ground friction without the velocity running away, and
        // leaving the rest of the velocity alone preserves whatever the player was already doing.
        Vec3 cur = p.getDeltaMovement();
        double along = cur.x * bestDodge.x + cur.z * bestDodge.z;
        if (along < DODGE_SPEED) {
            double k = DODGE_SPEED - along;
            p.setDeltaMovement(cur.add(bestDodge.x * k, 0, bestDodge.z * k));
        }
    }

    // Horizontal unit vector that moves us off the projectile's line: push further along the miss
    // offset (straight away from the impact point); if we're dead-on, sidestep perpendicular to it.
    private static Vec3 dodgeDir(Vec3 miss, Vec3 pv) {
        Vec3 dir = new Vec3(miss.x, 0, miss.z);
        if (dir.lengthSqr() < 1e-6) {
            dir = new Vec3(-pv.z, 0, pv.x);                     // perpendicular to incoming, horizontal
        }
        return dir.normalize();
    }
}
