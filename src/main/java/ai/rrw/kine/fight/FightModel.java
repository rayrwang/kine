package ai.rrw.kine.fight;

import ai.rrw.kine.combat.SwingPlan;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.search.Model;

import java.util.SplittableRandom;

/**
 * The combat movement as a {@link Model} for the receding-horizon search. Actions are {@link Moves}; each tick the
 * action becomes the keys the executor would press at the rotation the kill aura sends (simulated), and the fight
 * plays on ({@link FightWorld#tick}). The cost of a tick is the health the player loses, less the health taken off the
 * mobs weighted by the slider (survive ... kill fast), plus a small risk for every live mob, plus large marks for
 * anything unsafe (a fall of more than 2 blocks, a hazard, a fluid, the edge of the known world, movement the copy
 * doesn't cover). The end-of-plan estimate counts the fight still to come while mobs live and follow, so pure survival
 * gains nothing by running away: each live mob is a share of a hit wherever it stands, every tick the fight is
 * expected to last is risk (and time, by the slider), mobs about to reach the player where the aura's aim can't meet
 * them in time are danger, and standing where a nearby mob's hit would knock the player off a drop or into a hazard
 * costs a lot.
 */
public final class FightModel implements Model<FightWorld> {
    private final GroundPhysics phys;
    /** 0 = survive, 1 = kill fast. */
    private final double slider;
    private final double wDealt, wTime;

    public static final double UNSAFE = 1000.0;
    /** The risk of a fight going on, per live mob per tick, in health: a fight dragged out by keeping away still has
     *  to be fought, and every tick of it is a chance for a hit. */
    static final double RISK_PER_MOB_TICK = 0.02;
    /** What each live mob is expected to cost before it dies, in hits, wherever it is now. */
    static final double HITS_PER_MOB = 0.3;
    /** Ending where a hit from a mob within EDGE_MOB_RANGE would knock the player off a drop or into a hazard: a hit's
     *  knockback carries a standing player about 0.9 blocks (measured arc), two about 1.8. In health. */
    static final double EDGE_ONE_HIT = 30.0, EDGE_TWO_HITS = 8.0, EDGE_MOB_RANGE = 5.0;
    static final double ZOMBIE_STEP = 0.114;   // blocks a tick a zombie walks (MobSim)

    /** Move exactly along the heading (keys W, sprint in any direction) instead of the nearest keys at the rotation
     *  sent: an upper bound on what tilting the rotation could gain, for measurement only. */
    private final boolean ideal;

    public FightModel(GroundPhysics phys, double slider) {
        this(phys, slider, false);
    }

    public FightModel(GroundPhysics phys, double slider, boolean ideal) {
        this.ideal = ideal;
        this.phys = phys;
        this.slider = slider;
        this.wDealt = 0.05 + 0.45 * slider;
        this.wTime = 0.004 + 0.06 * slider;
    }

    public GroundPhysics physics() { return phys; }

    @Override public FightWorld copy(FightWorld w) { return w.copy(); }

    @Override public int actionCount() { return Moves.COUNT; }

    @Override
    public double step(FightWorld w, int action, int tickInSegment, SplittableRandom rnd) {
        if (action != Moves.HOLD && (tickInSegment == 0 || Float.isNaN(w.segmentYaw)))
            w.segmentYaw = headingFor(w, Moves.direction(action));
        double[] r = w.tick(phys, chooser(w, action, ideal), rnd);
        return r[1] - wDealt * r[0] + RISK_PER_MOB_TICK * w.alive() + safety(w);
    }

    /** How the action becomes keys this tick. */
    static FightWorld.Keys chooser(FightWorld w, int action, boolean ideal) {
        if (!ideal || action == Moves.HOLD) return yaw -> keysFor(w, action, yaw);
        return new FightWorld.Keys() {
            public int at(float sentYaw) {
                if (Float.isNaN(w.segmentYaw)) return 0;
                return Moves.keys(w.segmentYaw, w.segmentYaw, Moves.gait(action));
            }
            public float moveYaw(float sentYaw) { return Float.isNaN(w.segmentYaw) ? sentYaw : w.segmentYaw; }
        };
    }

    /** The keys for an action at the rotation sent: the executor presses the same. */
    static int keysFor(FightWorld w, int action, float sentYaw) {
        if (action == Moves.HOLD || Float.isNaN(w.segmentYaw)) return 0;
        return Moves.keys(w.segmentYaw, sentYaw, Moves.gait(action));
    }

    /** The world yaw an action's direction means now: a compass heading, or away from the nearest mob or the crowd's
     *  middle (NaN if there is none). */
    static float headingFor(FightWorld w, int dir) {
        if (dir < Moves.HEADINGS) return Moves.headingYaw(dir);
        double px = w.player.x, pz = w.player.z;
        if (dir == Moves.AWAY_NEAREST) {
            MobSim n = null;
            double best = Double.MAX_VALUE;
            for (MobSim m : w.mobs) {
                if (!m.alive) continue;
                double d = (m.x - px) * (m.x - px) + (m.z - pz) * (m.z - pz);
                if (d < best) { best = d; n = m; }
            }
            return n == null ? Float.NaN : (float) AuraSim.yawTo(n.x, n.z, px, pz);
        }
        double sx = 0, sz = 0, sw = 0;
        for (MobSim m : w.mobs) {
            if (!m.alive) continue;
            double d = Math.sqrt((m.x - px) * (m.x - px) + (m.z - pz) * (m.z - pz));
            double wt = 1.0 / Math.max(1.0, d);   // nearer mobs count more
            sx += m.x * wt; sz += m.z * wt; sw += wt;
        }
        if (sw == 0) return Float.NaN;
        return (float) AuraSim.yawTo(sx / sw, sz / sw, px, pz);
    }

    /** Marks for what the plan must not do: fall (any fall past 2 blocks), touch a hazard, leave what the copy covers. */
    private double safety(FightWorld w) {
        double c = 0;
        var p = w.player;
        if (p.fallDistance > 2.0) c += UNSAFE * (p.fallDistance > 3.0 ? 1.0 : 0.3);
        if ((p.touched & BlockInfo.HAZARD) != 0) c += UNSAFE;
        if ((p.touched & (BlockInfo.FLUID | BlockInfo.SPECIAL)) != 0) c += UNSAFE * 0.3;
        if (p.wouldGlide) c += UNSAFE;
        p.touched = 0;   // marked once
        if (w.health <= 0) c += UNSAFE;
        w.unsafe = Math.max(w.unsafe, c);
        return c;
    }

    @Override
    public double estimate(FightWorld w) {
        if (w.health <= 0) return UNSAFE;
        int n = w.alive();
        if (n == 0) return 0.0;
        var p = w.player;
        AuraSim a = w.aura;
        double ex = p.x, ez = p.z;
        // time left: the hits it takes to kill each mob at full charge, one full hit every full charge, and no mob hit
        // again sooner than its knockback lets it walk back (about 22 ticks); plus the walk in of the nearest
        SwingPlan.Weapon wpn = a.weapon;
        int full = wpn.fullTicks();
        double totalHits = 0, slowest = 0, nearestIn = Double.MAX_VALUE;
        double danger = 0;
        int near = 0;
        double minYaw = Double.MAX_VALUE, maxYaw = -Double.MAX_VALUE;
        for (MobSim m : w.mobs) {
            if (!m.alive) continue;
            double hit = SwingPlan.afterArmour(SwingPlan.hitDamage(wpn, m.bonus, 1.0F, false), m.armour, m.toughness);
            int hits = (int) Math.ceil(m.health / Math.max(0.5, hit));
            totalHits += hits;
            double d = Math.hypot(m.x - ex, m.z - ez);
            double inReach = Math.max(0.0, d - (a.reach + 0.3)) / ZOMBIE_STEP;
            nearestIn = Math.min(nearestIn, inReach);
            slowest = Math.max(slowest, inReach + (hits - 1) * 22.0);
            // danger: how soon it can hit, and whether the aura's aim can meet it first
            double contactGap = Math.max(0.0, d - (0.3 + m.width / 2 + MobSim.REACH));
            double tc = Math.max(contactGap / ZOMBIE_STEP, m.attackCooldown);
            double prox = clamp(1.0 - tc / 30.0, 0.0, 1.0);
            if (prox <= 0) continue;
            near++;
            double yaw = AuraSim.yawTo(ex, ez, m.x, m.z);
            double rel = ai.rrw.kine.move.GameMath.wrapDegrees(yaw - a.sentYaw);
            minYaw = Math.min(minYaw, rel);
            maxYaw = Math.max(maxYaw, rel);
            double miss;
            if (a.on) {
                double turnTicks = Math.abs(rel) / a.turnBudget;
                double ready = Math.max(turnTicks, m.freshIn);
                miss = clamp((ready + 1.0 - tc) / 4.0, 0.0, 1.0);
            } else {
                miss = 1.0;
            }
            danger += m.attackDamage * prox * miss;
        }
        if (near >= 2) danger += 0.3 * (maxYaw - minYaw) / 180.0 * near;
        double tLeft = a.on ? Math.max(totalHits * full, slowest) + nearestIn : (totalHits * full + nearestIn);
        // the fight still to come: each live mob is a share of a hit wherever it stands, and every tick the fight lasts
        // is risk, so keeping away gains nothing unless it really avoids a hit
        double baseline = 0;
        for (MobSim m : w.mobs) if (m.alive) baseline += HITS_PER_MOB * m.attackDamage;
        return danger + baseline + (wTime + RISK_PER_MOB_TICK * n) * tLeft + edge(w);
    }

    /** The cost of standing where a nearby mob's hit would knock the player off an edge or into a hazard: the probe
     *  goes the way the knockback would (straight away from the mob). */
    private double edge(FightWorld w) {
        var p = w.player;
        if (!p.onGround) return 0.0;
        double worst = 0;
        for (MobSim m : w.mobs) {
            if (!m.alive) continue;
            double dx = p.x - m.x, dz = p.z - m.z, d = Math.sqrt(dx * dx + dz * dz);
            if (d > EDGE_MOB_RANGE || d < 1e-6) continue;
            dx /= d; dz /= d;
            if (phys.unsafeAt(p, dx * 0.9, dz * 0.9, 3.0)) worst = Math.max(worst, EDGE_ONE_HIT);
            else if (phys.unsafeAt(p, dx * 1.8, dz * 1.8, 3.0)) worst = Math.max(worst, EDGE_TWO_HITS);
        }
        return worst;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    public double slider() { return slider; }
}
