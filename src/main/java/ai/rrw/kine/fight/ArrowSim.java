package ai.rrw.kine.fight;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;

/**
 * A projectile in the fight simulation: one a skeleton shoots in the plan, or one already flying when the snapshot was
 * taken (any the projectile dodge would consider). It flies as the game flies it (AbstractArrow.tick: move, then x0.99
 * and 0.05 down; thrown items fall first, then move; others straight), hits the player if its move this tick meets the
 * player's box grown by the arrow's margin (ProjectileUtil.computeMargin: (age - 2) / 20, at most 0.3, age 1 on its
 * first tick), and stops at the first full block in its way (the copy counts only full blocks, and none outside the
 * block snapshot, so it is never stopped sooner than the game's).
 */
public final class ArrowSim {
    public double x, y, z, vx, vy, vz;
    public double gravity = 0.05, drag = 0.99;
    public boolean gravityFirst = false;
    /** A fireball (AbstractHurtingProjectile.applyInertia): before each move its velocity gains accel along itself,
     *  then x inertia; no gravity. 0 for the others. */
    public double accel = 0, inertia = 0.95;
    /** Ticks flown so far (the game's tickCount, which counts up before each tick). */
    public int age = 0;
    /** Damage: ceil(speed * base) for an arrow (AbstractArrow.onHitEntity); a fixed amount when fixed > 0. */
    public double base = 2.794, fixed = 0;
    /** Whether its damage scales with difficulty (a mob's arrow: WHEN_CAUSED_BY_LIVING_NON_PLAYER). */
    public boolean scales = true;
    /** What a hit costs beyond the damage, in health (the arrow's effect). */
    public double extra = 0;
    public boolean alive = true;
    /** A splash potion: on a hit or a block it splashes, reaching the player within 4 blocks (ThrownSplashPotion:
     *  intensity 1 on a direct hit, else 1 - distance / 4, box to box); splashDamage is its health at full intensity (harming: 6,
     *  magic, no armour), splashCost what its effect costs beyond that (poison, slowness, weakness). */
    public boolean splash = false;
    public double splashDamage = 0, splashCost = 0;
    /** Where it stopped (a block in its way), for a splash. */
    double endX, endY, endZ;
    /** Client entity id for a projectile from the snapshot, 0 for one shot in the plan. */
    public int id;
    /** Made by a mob this tick: the game doesn't tick an entity in the tick it is added (its tickCount is 0 on the
     *  first row of every recorded arrow, potion and fireball), so it first flies the next tick. */
    public boolean fresh = false;
    /** A wind charge: where it stops (on the player or a block) it bursts with this radius, pushing the player as a
     *  blast does but hurting no one (AbstractWindCharge.explode, SimpleExplosionDamageCalculator). 0 for the others. */
    public double burst = 0;

    public ArrowSim copy() {
        ArrowSim a = new ArrowSim();
        a.x = x; a.y = y; a.z = z; a.vx = vx; a.vy = vy; a.vz = vz;
        a.gravity = gravity; a.drag = drag; a.gravityFirst = gravityFirst; a.age = age; a.accel = accel; a.inertia = inertia;
        a.base = base; a.fixed = fixed; a.scales = scales; a.extra = extra; a.alive = alive; a.id = id;
        a.splash = splash; a.splashDamage = splashDamage; a.splashCost = splashCost; a.fresh = fresh; a.burst = burst;
        return a;
    }

    /** The damage of a hit at its speed now, before difficulty and armour. */
    double damage() {
        if (fixed > 0) return fixed;
        double pow = Math.sqrt(vx * vx + vy * vy + vz * vz);
        return Math.ceil(pow * base);
    }

    /**
     * One tick of flight. Returns the hit's damage (before difficulty and armour) if it hits the player's box
     * (minX, minY, minZ, maxX, maxY, maxZ) this tick, else -1; it is dead after a hit or a block.
     */
    double tick(double[] pb, BlockGrid grid) {
        if (!alive) return -1;
        age++;
        if (accel > 0) inertia();
        else if (gravityFirst) { vy -= gravity; vx *= drag; vy *= drag; vz *= drag; }
        double t = blockAlong(grid);   // the share of this move before a block, 1 if none
        double m = Math.max(0.0F, Math.min(0.3F, (age - 2) / 20.0F));
        double s = clip(pb, m, t);
        if (s >= 0) {
            double dmg = damage();
            alive = false;
            endX = x + vx * s; endY = y + vy * s; endZ = z + vz * s;
            return dmg;
        }
        if (t < 1.0) { alive = false; endX = x + vx * t; endY = y + vy * t; endZ = z + vz * t; return -1; }
        x += vx; y += vy; z += vz;
        if (!gravityFirst && accel <= 0) { vx *= drag; vy *= drag; vz *= drag; vy -= gravity; }
        if (age > 100 || y < grid.y0 - 4) { alive = false; endX = Double.NaN; }
        return -1;
    }

    /** A fireball's speed-up before its move: v + |v|-normalised x accel, x inertia. */
    void inertia() {
        double l = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (l > 1e-9) { vx += vx / l * accel; vy += vy / l * accel; vz += vz / l * accel; }
        vx *= inertia; vy *= inertia; vz *= inertia;
    }

    /** A splash's intensity on the player (box minX, minY, minZ, maxX, maxY, maxZ) after it stopped at a block:
     *  1 - d / 4, d the distance between the potion's box at the stop (0.25 wide) and the player's grown by the margin,
     *  0 from 4 on (ThrownSplashPotion.onHitAsPotion). */
    double splashOn(double[] pb) {
        if (Double.isNaN(endX)) return 0.0;
        double m = Math.max(0.0F, Math.min(0.3F, (age - 2) / 20.0F)), h = 0.125;
        double gx = Math.max(0.0, Math.max(pb[0] - m - (endX + h), (endX - h) - (pb[3] + m)));
        double gy = Math.max(0.0, Math.max(pb[1] - m - (endY + 0.25), endY - (pb[4] + m)));
        double gz = Math.max(0.0, Math.max(pb[2] - m - (endZ + h), (endZ - h) - (pb[5] + m)));
        double d2 = gx * gx + gy * gy + gz * gz;
        return d2 < 16.0 ? 1.0 - Math.sqrt(d2) / 4.0 : 0.0;
    }

    /** Where along this tick's move (0 to t) the segment enters the player's box grown by m, or -1. */
    private double clip(double[] pb, double m, double t) {
        double lo = 0, hi = t;
        double[] o = {x, y, z}, d = {vx, vy, vz};
        for (int a = 0; a < 3; a++) {
            double min = pb[a] - m, max = pb[a + 3] + m;
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < min || o[a] > max) return -1;
            } else {
                double t1 = (min - o[a]) / d[a], t2 = (max - o[a]) / d[a];
                if (t1 > t2) { double k = t1; t1 = t2; t2 = k; }
                lo = Math.max(lo, t1);
                hi = Math.min(hi, t2);
                if (lo > hi) return -1;
            }
        }
        return lo;
    }

    /** The share of this tick's move before it enters a full block (a voxel walk), 1 if it enters none. */
    private double blockAlong(BlockGrid grid) {
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 1e-9) return 1.0;
        int steps = (int) Math.ceil(len / 0.2);
        for (int i = 1; i <= steps; i++) {
            double f = (double) i / steps;
            int bx = (int) Math.floor(x + vx * f), by = (int) Math.floor(y + vy * f), bz = (int) Math.floor(z + vz * f);
            if (!grid.inside(bx, by, bz)) continue;   // outside the snapshot: taken as open
            BlockInfo b = grid.get(bx, by, bz);
            if (b.shape.block) return (double) (i - 1) / steps;
        }
        return 1.0;
    }
}
