package ai.rrw.kine.combat;

import ai.rrw.kine.Settings;
import ai.rrw.kine.Kine;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * Sidesteps incoming projectiles. For every projectile not owned by us, project its current
 * velocity forward to the point of closest approach; if it would pass within HIT_RADIUS of our
 * body within REACT_TICKS, inject a lateral velocity that carries us off its line.
 *
 * Like the other movement helpers this only ADDS a sideways nudge at a realistic speed — it never
 * teleports. Limits worth knowing: it can't beat a point-blank or very fast shot (not enough ticks
 * to clear the hitbox), and an opponent who leads where you dodge to will still connect. Vanilla has
 * no hitscan, so every threat is a real entity we can see coming.
 */
public class ProjectileDodge {

    private static final double REACT_TICKS  = 20.0;   // start dodging when impact is within ~1s
    private static final double HIT_RADIUS   = 1.1;    // body half-width + projectile + margin
    private static final double DODGE_SPEED  = 0.32;   // lateral speed to top up to (~sprint)
    private static final double MIN_SPEED_SQ = 0.01;   // ignore stuck / near-stationary projectiles

    private static final boolean DEBUG = false;        // flip on to log detection to the console
    private static int dbgTicks = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ProjectileDodge::tick);
    }

    private static void tick(Minecraft mc) {
        if (!Settings.projectileDodge) return;
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || p.isSpectator()) return;

        Vec3 center = new Vec3(p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ());

        double bestT = Double.MAX_VALUE;
        Vec3 bestDodge = null;
        int seen = 0;                                  // moving, non-owned projectiles found
        double dbgNearDist = Double.MAX_VALUE;         // nearest one by raw distance, for logging
        double dbgMiss = -1, dbgT = 0;

        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof Projectile proj)) continue;
            if (proj.getOwner() == p) continue;                 // never dodge our own shots

            Vec3 pp = proj.position();
            // Per-tick displacement is the most reliable velocity for a non-local entity (getDeltaMovement
            // can read stale/zero on interpolated projectiles); fall back to it only if there's no delta yet.
            Vec3 pv = new Vec3(proj.getX() - proj.xOld, proj.getY() - proj.yOld, proj.getZ() - proj.zOld);
            if (pv.lengthSqr() < MIN_SPEED_SQ) pv = proj.getDeltaMovement();
            double v2 = pv.lengthSqr();
            if (v2 < MIN_SPEED_SQ) continue;                    // stuck arrow / spent projectile
            seen++;

            double t = center.subtract(pp).dot(pv) / v2;        // ticks to closest approach
            Vec3 closest = pp.add(pv.scale(Math.max(t, 0)));     // where it passes us
            Vec3 miss = center.subtract(closest);
            double dist = center.subtract(pp).length();
            if (dist < dbgNearDist) { dbgNearDist = dist; dbgMiss = miss.length(); dbgT = t; }

            if (t < 0 || t > REACT_TICKS) continue;             // moving away, or not imminent
            if (miss.length() > HIT_RADIUS) continue;           // it'll miss anyway
            if (t < bestT) {
                bestT = t;
                bestDodge = dodgeDir(miss, pv);
            }
        }

        if (DEBUG && dbgTicks++ % 20 == 0) {
            if (seen == 0) {
                Kine.LOGGER.info("kine dodge: on, no moving projectiles in range");
            } else {
                Kine.LOGGER.info("kine dodge: on, {} projectile(s); nearest dist={} miss={} t={} dodging={}",
                    seen, String.format("%.1f", dbgNearDist), String.format("%.2f", dbgMiss),
                    String.format("%.1f", dbgT), bestDodge != null);
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
