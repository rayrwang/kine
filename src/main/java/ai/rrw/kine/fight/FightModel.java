package ai.rrw.kine.fight;

import ai.rrw.kine.combat.SwingPlan;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.search.Model;

import java.util.SplittableRandom;

/**
 * The combat movement as a {@link Model} for the receding-horizon search. Actions are {@link Moves}; each tick the
 * action becomes the keys the executor would press at the rotation the kill aura sends (simulated), and the fight
 * plays on ({@link FightWorld#tick}). The cost of a tick is the health the player loses, weighted by the slider and the
 * player's health (survive weighs it four times at full health and more as health drops, kill fast once), less the
 * health taken off the mobs weighted by the slider, plus a small risk for every live mob (a third of it at survive, so a
 * quicker end is never bought with a hit there), a spacing cost for standing near a mob whose attack is ready (at survive),
 * and large marks for anything unsafe (a fall of more than 2 blocks, a hazard, a fluid, the edge of the known world,
 * movement the copy doesn't cover). The end-of-plan estimate counts the fight still to come while mobs live and
 * follow, so pure survival gains nothing by running away: each live mob is a share of a hit wherever it stands, every
 * tick the fight is expected to last is risk (and time, by the slider), mobs about to reach the player where the aura's
 * aim can't meet them in time are danger, and standing where a nearby mob's hit would knock the player off a drop or
 * into a hazard costs a lot.
 *
 * <p>The slider s (0 survive, 1 kill fast) sets the weights, all in units of the player's health:
 * <ul>
 *   <li>health lost: 1 + (1 - s) * (4 * low - 1), where low = 1 + 2 * (the share of max health already lost), so 4
 *       at survive and full health, 8 at half health, 2.5 at the middle, 1 at kill fast;</li>
 *   <li>health dealt: 0.05 + 0.45 * s; time: 0.016 + 0.048 * s a tick; risk per live mob per tick
 *       0.007 + 0.013 * s;</li>
 *   <li>spacing: (1 - s) * 0.15 * the mob's hit, weighted as health lost, each tick the player stands within
 *       0.25 + 0.35 * (1 - s) blocks of a mob's reach while its attack is ready.</li>
 * </ul>
 */
public final class FightModel implements Model<FightWorld> {
    private final GroundPhysics phys;
    /** 0 = survive, 1 = kill fast. */
    private final double slider;
    private final double wDealt, wTime, risk;
    /** Health lost weighs this much at survive and full health (times more as health drops; see wHealth). */
    static final double SURVIVE_HEALTH = 4.0;
    /** At survive, the extra distance kept outside a ready mob's reach, and the spacing cost's weight. */
    static final double SPACING_MARGIN = 0.35, SPACING_BASE = 0.25, SPACING_WEIGHT = 0.15;
    /** At survive, time and the per-mob risk keep this much of their kill-fast weight (0.064 and 0.02 a tick): enough
     *  that a fight is not dragged out for nothing, far too little to buy a quicker end with a hit. Measured in the
     *  model-world bench with the client's view 5 ticks late: 6 zombies took 379 ticks at survive with a tenth of the
     *  risk and 0.004 of time, 253 with these, no damage either way (kill fast 194 ticks, 1.9 lost). */
    static final double RISK_FLOOR = 0.35, TIME_FLOOR = 0.016;
    /** The cost of a tick with the aura charged and no mob in its reach, at survive. */
    static final double IDLE_COST = 0.25;

    public static final double UNSAFE = 1000.0;
    /** The risk of a fight going on, per live mob per tick, in health, at kill fast (RISK_FLOOR of it at survive):
     *  a fight dragged out by keeping away still has to be fought, and every tick of it is a chance for a hit. */
    static final double RISK_PER_MOB_TICK = 0.02;
    /** What each live mob at full health is expected to cost before it dies, in hits, wherever it is now (less as its
     *  health drops: a quarter of it fixed, the rest in proportion to its health left). */
    static final double HITS_PER_MOB = 0.3;
    /** Ending where a hit from a mob within EDGE_MOB_RANGE would knock the player off a drop or into a hazard: a hit's
     *  knockback carries a standing player about 0.9 blocks (measured arc), two about 1.8. In health. */
    static final double EDGE_ONE_HIT = 30.0, EDGE_TWO_HITS = 8.0, EDGE_MOB_RANGE = 5.0;
    static final double ZOMBIE_STEP = 0.1165;   // blocks a tick a zombie walks (MobSim)

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
        this.wTime = TIME_FLOOR + (0.064 - TIME_FLOOR) * slider;
        this.risk = RISK_PER_MOB_TICK * (RISK_FLOOR + (1 - RISK_FLOOR) * slider);
    }

    /** The weight on health the player loses: SURVIVE_HEALTH at survive and full health, rising as health drops (twice
     *  that at half health), 1 at kill fast. */
    double wHealth(FightWorld w) {
        double lostShare = w.maxHealth > 0 ? clamp(1.0 - w.health / w.maxHealth, 0.0, 1.0) : 0.0;
        double low = 1.0 + 2.0 * lostShare;
        return 1.0 + (1.0 - slider) * (SURVIVE_HEALTH * low - 1.0);
    }

    public GroundPhysics physics() { return phys; }

    @Override public FightWorld copy(FightWorld w) {
        FightWorld c = w.copy();
        c.fuseMargin = fuseMargin(w);
        c.swellBack = MobSim.SWELL_BACK + SWELL_BACK_MARGIN * (1.0 - slider);
        c.aura.noLitKill = slider < 0.5;
        return c;
    }

    /** The ticks of a creeper's fuse a plan wants left over when it has walked out of it (creepersDanger): the lag and
     *  2 at kill fast, FUSE_MARGIN more at survive. In a lockstep run the plan walked out at the last moment and held
     *  the player at the model's edge, and the real creepers blew up with it 6 to 7.1 blocks off. The plan itself flies
     *  the real fuse: taken shorter, a plan saw a blast it could no longer stop and stopped trying. */
    int fuseMargin(FightWorld w) {
        return (int) Math.round(2.0 + w.lag + FUSE_MARGIN * (1.0 - slider));
    }

    static final double FUSE_MARGIN = 4.0;

    /** How much further than SWELL_BACK a survive plan has to be from a lit creeper for its fuse to wind down: in a
     *  lockstep run the plan held the player 6.7 to 7.3 blocks off (the model's edge) and the real creepers blew up with
     *  it 7.05 to 7.13 off. */
    static final double SWELL_BACK_MARGIN = 0.5;

    @Override public int actionCount() { return Moves.COUNT; }

    @Override
    public double step(FightWorld w, int action, int tickInSegment, SplittableRandom rnd) {
        if (action != Moves.HOLD && (tickInSegment == 0 || Float.isNaN(w.segmentYaw)))
            w.segmentYaw = headingFor(w, Moves.direction(action));
        w.planHeading = action == Moves.HOLD ? Float.NaN : w.segmentYaw;
        double wh = wHealth(w);   // before this tick's loss
        // the nearest live mob before the move: the idle cost asks whether the player backs away from it
        MobSim near = null;
        double best = Double.MAX_VALUE;
        for (MobSim m : w.mobs) {
            if (!m.alive) continue;
            double d = (m.x - w.player.x) * (m.x - w.player.x) + (m.z - w.player.z) * (m.z - w.player.z);
            if (d < best) { best = d; near = m; }
        }
        double nx = near == null ? 0 : near.x, nz = near == null ? 0 : near.z;
        double[] r = w.tick(phys, chooser(w, action, ideal), rnd);
        boolean backing = false;
        if (near != null && near.kind != MobSim.Kind.CREEPER && !creeperNear(w)) {   // backing from a creeper never costs
            double ax = w.player.x - w.pdx - nx, az = w.player.z - w.pdz - nz, len = Math.sqrt(ax * ax + az * az);
            backing = len > 1e-6 && (w.pdx * ax + w.pdz * az) / len > BACKING;
        }
        return wh * (r[1] + w.extraNow + spacing(w) + blastCost() * w.blastsNow + creeperBand(w)) - wDealt * r[0] + risk * w.alive()
            + (backing ? idle(w) : 0.0) + lunge(w) + safety(w);
    }

    /** Below kill fast, the player's own move toward a mob whose attack is ready and that is within LUNGE_RANGE: the
     *  mobs come to the player, and at survive it lets them, stepping in only on one that has just attacked. (1 - s) *
     *  LUNGE_COST for a sprint's worth of closing a tick (0.28 blocks), in proportion. In a lockstep run of version 2 at
     *  survive with lag, 16% of replans near zombies began by walking or sprinting at one whose attack was ready (RW:
     *  "lunge at the mobs even while in the safest setting"). */
    double lunge(FightWorld w) {
        if (slider >= 1.0) return 0.0;
        double px = w.player.x - w.pdx, pz = w.player.z - w.pdz;   // where the move started
        double worst = 0;
        for (MobSim m : w.mobs) {
            if (!m.alive || !m.melee() || m.attackCooldown > 1) continue;   // the shooters are walked up to; creepers are
            double dx = m.x - px, dz = m.z - pz, d = Math.sqrt(dx * dx + dz * dz);   // judged by their fuses (creepersDanger)
            if (d > LUNGE_RANGE || d < 1e-6) continue;
            double toward = (w.pdx * dx + w.pdz * dz) / d;
            if (toward > BACKING) worst = Math.max(worst, toward);
        }
        return worst > 0 ? (1.0 - slider) * LUNGE_COST * worst / 0.28 : 0.0;
    }

    /** At survive, the cost of closing on a ready mob at a sprint for a tick, and how near a mob must be to count. */
    static final double LUNGE_COST = 0.5, LUNGE_RANGE = 4.5;

    /** A creeper's blast, wherever it happens, in health before the weight, beyond the damage it does: RW, verbatim,
     *  "no exp[losions" (terrain and drops matter), then "wel at highest vlaues ig that unvaoidable, jsut at least at 0
     *  no wepxoistoins". BLAST_COST at survive, none at kill fast. */
    double blastCost() {
        return BLAST_COST * (1.0 - slider);
    }

    static final double BLAST_COST = 25.0;

    /** Whether a creeper is near enough to matter: swelling within the plan's swellBack, or within its fuse distance
     *  plus the margin. */
    boolean creeperNear(FightWorld w) {
        for (MobSim m : w.mobs) {
            if (!m.alive || m.kind != MobSim.Kind.CREEPER) continue;
            double dx = m.x - w.player.x, dy = m.y - w.player.y, dz = m.z - w.player.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if ((m.swellDir > 0 || m.swelling) ? d < w.swellBack : d < FUSE + creeperMargin(w)) return true;
        }
        return false;
    }

    /** The distance a creeper's fuse starts at (SwellGoal: within 3 blocks, feet to feet). */
    static final double FUSE = 3.0;

    /** How far beyond a creeper's fuse distance a creeper counts as about to light (creepersDanger) and backing from it
     *  pays no idle cost: half a block and the lag's sprint at kill fast, a block and a half more at survive. */
    double creeperMargin(FightWorld w) {
        return 0.5 + PLAYER_SPRINT * w.lag + 1.5 * (1.0 - slider);
    }

    /** The player's move away from (or toward) a mob, blocks a tick, that counts (a walk is 0.216). */
    static final double BACKING = 0.05;

    private SwingPlan.Weapon idleWeapon;
    private int idleFull;

    /** Below kill fast, a tick in which the kill aura is fully charged and no live mob is in its reach: the fight makes
     *  no progress, and at survive keeping the crowd just out of reach would otherwise
     *  cost almost nothing (lockstep, version 2's first survive build: six zombies kited for 785 ticks, the nearest held
     *  at about 4 blocks). (1 - s) * IDLE_COST a tick, charged only while the player backs away from the nearest mob
     *  (step): charged whenever the aura waited, it paid the plan to step toward a mob to bring it into reach (a
     *  lockstep debug run at survive, the steps toward a ready zombie: walking away cost 2.1 more in idle than the step
     *  in), and RW saw it lunge at mobs; backing away while the aura waits is the kiting it is there to stop. */
    double idle(FightWorld w) {
        AuraSim a = w.aura;
        if (!a.on || slider >= 1.0) return 0.0;
        if (a.weapon != idleWeapon) { idleWeapon = a.weapon; idleFull = a.weapon.fullTicks(); }
        if (a.k < idleFull) return 0.0;
        double ex = w.player.x, ey = w.player.y + a.eyeHeight, ez = w.player.z;
        boolean any = false;
        for (MobSim m : w.mobs) {
            if (!m.alive) continue;
            any = true;
            if (AuraSim.eyeToBox(ex, ey, ez, m, m.seen()) <= a.reach) return 0.0;
        }
        // impatience: the longer the fight has gone without a hit landing, the more backing away costs, so the middle
        // settings retreat from a threat for a while but not for ever (in a lockstep run with lag the player ran from
        // three baby zombies for the whole 900 ticks: the aura, aiming where the client draws them, two blocks behind
        // a baby, could never knock one back first). Weighted by the slider, so survive has none and keeps backing
        // away rather than take a hit to end the fight (RW: "close to 0 means the second is very low or zero").
        double impatience = 1.0 + slider * Math.min(IMPATIENCE_MAX, (w.lull + w.ticks) / IMPATIENCE_TICKS);
        return any ? IDLE_COST * (1.0 - slider) * impatience : 0.0;
    }

    /** The idle cost grows by the slider's share of itself every IMPATIENCE_TICKS of the fight without a mob losing
     *  health, to at most 1 + slider x IMPATIENCE_MAX times: none at survive, and the idle cost itself is gone at kill
     *  fast, so it matters most in the middle. */
    static final double IMPATIENCE_TICKS = 60.0, IMPATIENCE_MAX = 5.0;

    /** At survive, the cost of standing near a mob whose attack is ready: within SPACING_BASE + SPACING_MARGIN of its
     *  reach (box to box), more the nearer; nothing at kill fast. In health, before the health weight. */
    double spacing(FightWorld w) {
        if (slider >= 1.0) return 0.0;
        double margin = SPACING_BASE + SPACING_MARGIN * (1.0 - slider);
        double pw = phys.body().width / 2.0;
        double c = 0;
        for (MobSim m : w.mobs) {
            if (!m.alive || !m.melee() || m.attackCooldown > 1) continue;
            double hw = m.width / 2 + MobSim.REACH + pw;
            double gap = Math.max(Math.abs(m.x - w.player.x), Math.abs(m.z - w.player.z)) - hw;
            if (gap >= margin) continue;
            c += SPACING_WEIGHT * (1.0 - slider) * (m.attackDamage + m.hitExtra) * (1.0 - Math.max(0.0, gap) / margin);
        }
        return c;
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

    /** When set, estimate() writes its parts here: {danger, per-mob share, edge, fight length}, weighted (debug). */
    private double[] parts;

    @Override
    public double estimate(FightWorld w) {
        if (w.health <= 0) return UNSAFE;
        double falling = falling(w);
        if (falling > 0) return falling;
        int n = w.alive();
        if (n == 0) return wHealth(w) * arrowsComing(w);
        var p = w.player;
        AuraSim a = w.aura;
        double ex = p.x, ez = p.z;
        // time left: the hits it takes to kill each mob at full charge, one full hit every full charge, and no mob hit
        // again sooner than its knockback lets it walk back (about 22 ticks); plus the walk in of the nearest (a mob
        // that keeps its distance, a skeleton, is walked to at a sprint)
        SwingPlan.Weapon wpn = a.weapon;
        int full = wpn.fullTicks();
        double totalHits = 0, slowest = 0, nearestIn = Double.MAX_VALUE;
        double danger = 0, shooting = 0;
        int near = 0;
        double minYaw = Double.MAX_VALUE, maxYaw = -Double.MAX_VALUE;
        for (MobSim m : w.mobs) {
            if (!m.alive) continue;
            double hit = SwingPlan.afterArmour(SwingPlan.hitDamage(wpn, m.bonus, 1.0F, false), m.armour, m.toughness);
            int hits = (int) Math.ceil(m.health / Math.max(0.5, hit));
            // a slime bigger than 1 splits on death into 2 to 4 of half its size (Slime.remove): 3 each, the mean, for
            // the fight's length
            int count = 1;
            for (int sz = m.slimeSize / 2; m.kind == MobSim.Kind.SLIME && sz >= 1; sz /= 2) {
                count *= 3;
                hits += count * (int) Math.ceil(sz * sz / Math.max(0.5, hit));
            }
            totalHits += hits;
            double d = Math.hypot(m.x - ex, m.z - ez);
            if (m.kind == MobSim.Kind.PHANTOM || m.kind == MobSim.Kind.VEX) d = Math.hypot(d, m.y - p.y);   // flyers
            boolean shooter = m.kind == MobSim.Kind.BOW || m.kind == MobSim.Kind.CROSSBOW || m.kind == MobSim.Kind.WITCH
                || m.kind == MobSim.Kind.BLAZE || m.kind == MobSim.Kind.TRIDENT || m.kind == MobSim.Kind.BREEZE
                || m.kind == MobSim.Kind.EVOKER;
            double closing = shooter ? PLAYER_SPRINT : Math.max(0.05, m.walkSpeed());
            double inReach = Math.max(0.0, d - (a.reach + 0.3)) / closing;
            nearestIn = Math.min(nearestIn, inReach);
            slowest = Math.max(slowest, inReach + (hits - 1) * 22.0);
            if (m.kind == MobSim.Kind.CREEPER) continue;   // all together below (creepersDanger)
            if (m.kind == MobSim.Kind.BOW) {
                shooting += ARROW_RATE * (m.arrowBase * 1.6 + m.arrowExtra) / (m.bowInterval + 20.0);   // an arrow a cycle
                continue;
            }
            if (m.kind == MobSim.Kind.CROSSBOW) {   // a shot every 45 to 64 ticks
                shooting += ARROW_RATE * (m.arrowBase * 1.6 + m.arrowExtra) / 55.0;
                continue;
            }
            if (m.kind == MobSim.Kind.EVOKER) {     // fangs every 100 ticks
                shooting += ARROW_RATE * 6.0 / 100.0;
                continue;
            }
            if (m.kind == MobSim.Kind.BREEZE) {     // a wind charge about every 40 ticks: 1 damage, the push is the edge's
                shooting += ARROW_RATE * typicalHit(m) / 40.0;
                continue;
            }
            if (m.kind == MobSim.Kind.TRIDENT) {    // a trident every 40 ticks
                shooting += ARROW_RATE * typicalHit(m) / 40.0;
                continue;
            }
            if (m.kind == MobSim.Kind.BLAZE) {      // three fireballs every 178 ticks
                shooting += ARROW_RATE * 3.0 * typicalHit(m) / 178.0;
                continue;
            }
            if (m.kind == MobSim.Kind.WITCH) {      // a potion every 60 ticks
                shooting += ARROW_RATE * 6.0 / 60.0;
                continue;
            }
            // danger: how soon it can hit, and whether the aura's aim can meet it first
            double contactGap = Math.max(0.0, d - (0.3 + m.width / 2 + MobSim.REACH));
            double tc = Math.max(contactGap / Math.max(0.05, m.walkSpeed()), m.attackCooldown);
            if (m.kind == MobSim.Kind.LEAPER && m.onGround && d >= 2.0 && d <= 4.0) tc = Math.min(tc, Math.max(8.0, m.attackCooldown));
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
            danger += (m.attackDamage + m.hitExtra) * prox * miss;
        }
        if (near >= 2) danger += 0.3 * (maxYaw - minYaw) / 180.0 * near;
        danger += creepersDanger(w) + creepersAround(w) + creepersLeft(w);
        for (double[] f : w.fangs)   // a fang still to bite where the plan ends
            if (f[3] >= 0 && Math.abs(f[0] - p.x) < 0.75 && Math.abs(f[2] - p.z) < 0.75) danger += 6.0;
        double tLeft = a.on ? Math.max(totalHits * full, slowest) + nearestIn : (totalHits * full + nearestIn);
        // the fight still to come: each live mob is a share of a hit wherever it stands, and every tick the fight lasts
        // is risk (and arrows, while skeletons live), so keeping away gains nothing unless it really avoids a hit. Each
        // live mob's share is a quarter fixed and the rest in proportion to its health left, so damage dealt counts as
        // progress before the kill (at survive the health weight makes this what draws the fight on).
        double baseline = 0;
        for (MobSim m : w.mobs)
            if (m.alive) baseline += HITS_PER_MOB * typicalHit(m) * (0.25 + 0.75 * clamp(m.health / m.maxHealth, 0.0, 1.0));
        double wh = wHealth(w);
        double edge = edge(w), coming = arrowsComing(w);
        if (parts != null) { parts[0] = wh * (danger + coming + shooting * tLeft); parts[1] = wh * baseline; parts[2] = wh * edge; parts[3] = (wTime + risk * n) * tLeft; }
        return wh * (danger + baseline + edge + coming + shooting * tLeft) + (wTime + risk * n) * tLeft;
    }

    /** A sprinting player's speed, blocks a tick: how fast it closes on a mob that keeps its distance. */
    static final double PLAYER_SPRINT = 0.28;
    /** The share of a skeleton's arrows taken as hitting over the fight still to come (moving players dodge most). */
    static final double ARROW_RATE = 0.3;

    /** What one hit of a mob costs, in health before the weight: its melee hit, its arrow, or a creeper's blast at
     *  4 blocks. */
    static double typicalHit(MobSim m) {
        return switch (m.kind) {
            case BOW, CROSSBOW -> Math.ceil(1.6 * m.arrowBase) + m.arrowExtra;
            case WITCH -> 6.0;
            case BLAZE -> 5.0 + FightWorld.FIRE_EXTRA;
            case TRIDENT -> 8.0;
            case BREEZE -> 1.0;
            case EVOKER -> 6.0;
            case CREEPER -> blastAt(m, 4.0);
            default -> m.attackDamage + m.hitExtra;
        };
    }

    /** A creeper's blast on a player d blocks away in the open (exposure 1), before difficulty and armour. */
    static double blastAt(MobSim c, double d) {
        double dist = d / (2.0 * c.blastRadius);
        if (dist > 1.0) return 0.0;
        double q = 1.0 - dist;
        return (q * q + q) / 2.0 * 7.0 * 2.0 * c.blastRadius + 1.0;
    }

    /**
     * The creepers whose fuse runs (or will: within the fuse distance plus a margin) at the plan's end, judged
     * together: the best of 16 ways out at a walk (the aura faces a mob, so fleeing it is walked, not sprinted) and
     * straight away from their middle must take the player beyond SWELL_BACK of each of them with the plan's fuse
     * margin to spare (fuseMargin: 2 + lag ticks, more at survive). Each one the best way doesn't clear costs its blast
     * where that way leaves the player when it goes off, and the blast itself (blastCost), in the share of the margin
     * the way out eats into.
     */
    private double creepersDanger(FightWorld w) {
        var p = w.player;
        double margin = creeperMargin(w);
        int n = 0;
        double cx = 0, cz = 0, wsum = 0;
        for (MobSim c : w.mobs) {
            if (!threat(w, c, margin)) continue;
            double wt = 1.0 / Math.max(1, c.maxSwell - c.swell);
            cx += c.x * wt; cz += c.z * wt; wsum += wt;
            n++;
        }
        if (n == 0) return 0.0;
        double slack = w.fuseMargin;
        // the aura lets a lit creeper go while the player walks out of it (the sprint out) unless another mob is in
        // its reach to face
        boolean sprintOut = true;
        for (MobSim m : w.mobs)
            if (m.alive && m.kind != MobSim.Kind.CREEPER && Math.hypot(m.x - p.x, m.z - p.z) < w.aura.reach + 1.0) sprintOut = false;
        double best = Double.MAX_VALUE;
        for (int k = 0; k <= 16 && best > 0; k++) {
            double ux, uz;
            if (k < 16) { double a = k * Math.PI / 8; ux = Math.cos(a); uz = Math.sin(a); }
            else {
                ux = p.x - cx / wsum; uz = p.z - cz / wsum;
                double ul = Math.sqrt(ux * ux + uz * uz);
                if (ul < 1e-6) continue;
                ux /= ul; uz /= ul;
            }
            double cost = 0;
            for (MobSim c : w.mobs) {
                if (!threat(w, c, margin)) continue;
                int left = c.maxSwell - c.swell;
                // the way along the way out until beyond swellBack of this one: |r + u s| = R, then its ticks
                double rx = p.x - c.x, ry = p.y - c.y, rz = p.z - c.z;
                double b = rx * ux + rz * uz;
                double cc = rx * rx + ry * ry + rz * rz - w.swellBack * w.swellBack;
                if (cc > 0) continue;   // out already: its fuse winds down from the next tick
                double t = escapeTicks(-b + Math.sqrt(Math.max(0.0, b * b - cc)), sprintOut);
                if (t + slack < left) continue;
                // the share of the margin the way out eats into: none with the whole margin left, all with none
                double share = Math.min(1.0, (t + slack - left) / Math.max(1.0, slack));
                double at = Math.max(0, left - 1), gone = escapeWay(at, sprintOut);
                double ex = rx + ux * gone, ez = rz + uz * gone;
                cost += share * (w.scale(blastAt(c, Math.min(Math.sqrt(ex * ex + ry * ry + ez * ez), w.swellBack))) + blastCost());
                if (cost >= best) break;
            }
            best = Math.min(best, cost);
        }
        return best;
    }

    /**
     * Below kill fast, a tick in a lit creeper's middle band: too far to hit it (beyond the aura's reach) and too near
     * for its fuse to wind down (inside the plan's swellBack). RW, verbatim: "it shoudl go in, hit and come back out
     * with both going in far, and coming out far, rahter than huanging out int eh idddle which is bad both bc ccan' thit
     * the creperm, and if it litghts not far enoguh away to defuse it". Walking out at BAND_PACE or more is free. (A
     * band for creepers not lit was tried too: it kept the player out and slowed the kills.) In health before the
     * weight, a tick.
     */
    double creeperBand(FightWorld w) {
        if (slider >= 1.0) return 0.0;
        double c = 0;
        for (MobSim m : w.mobs) {
            if (!m.alive || m.kind != MobSim.Kind.CREEPER || !(m.swellDir > 0 || m.swelling)) continue;
            double dx = m.x - w.player.x, dy = m.y - w.player.y, dz = m.z - w.player.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d <= w.aura.reach + m.width / 2 || d >= w.swellBack) continue;
            if (w.pdx * dx / d + w.pdz * dz / d < -BAND_PACE) continue;   // on its way out
            c += BAND_COST * (1.0 - slider);
        }
        return c;
    }

    static final double BAND_COST = 0.5, BAND_PACE = 0.15;

    /** Below kill fast, each creeper still alive at the plan's end: a lasting danger, so a plan that hits it is worth
     *  more (CREEPER_LEFT for one at full health, a quarter of it at none). */
    double creepersLeft(FightWorld w) {
        if (slider >= 1.0) return 0.0;
        double c = 0;
        for (MobSim m : w.mobs)
            if (m.alive && m.kind == MobSim.Kind.CREEPER)
                c += CREEPER_LEFT * (1.0 - slider) * (0.25 + 0.75 * clamp(m.health / m.maxHealth, 0.0, 1.0));
        return c;
    }

    static final double CREEPER_LEFT = 10.0;

    /** Below kill fast, creepers on more than one side: within 8 blocks, the widest way out between them (the largest
     *  gap in their directions from the player) under 180 degrees costs a share of a blast, all of it with no gap at
     *  all. In a lockstep run at survive the player let five creepers close in around it and one blew up with every way
     *  out passing another. */
    private double creepersAround(FightWorld w) {
        if (slider >= 1.0) return 0.0;
        double[] a = new double[w.mobs.length];
        int n = 0;
        for (MobSim c : w.mobs) {
            if (!c.alive || c.kind != MobSim.Kind.CREEPER) continue;
            double dx = c.x - w.player.x, dz = c.z - w.player.z;
            if (dx * dx + dz * dz > 64.0) continue;
            a[n++] = Math.toDegrees(Math.atan2(dz, dx));
        }
        if (n < 2) return 0.0;
        java.util.Arrays.sort(a, 0, n);
        double gap = 360.0 - (a[n - 1] - a[0]);
        for (int i = 1; i < n; i++) gap = Math.max(gap, a[i] - a[i - 1]);
        if (gap >= 180.0) return 0.0;
        return blastCost() * AROUND_SHARE * (180.0 - gap) / 180.0;
    }

    /** The share of a blast for creepers all around the player. */
    static final double AROUND_SHARE = 0.5;

    /** How fast the player gets away from creepers: a walk (0.216 a tick) while the aura faces a mob, since sprinting
     *  needs W within 67.5 degrees of the rotation; with the sprint out (the aura turning to the way out at up to 30
     *  degrees a tick once a lit creeper is let go) a walk for ESCAPE_TURN ticks, then a sprint (0.28). */
    static final double ESCAPE_SPEED = 0.216, ESCAPE_SPRINT = 0.28;
    static final int ESCAPE_TURN = 5;

    /** Ticks to go `way` blocks on the way out. */
    static double escapeTicks(double way, boolean sprint) {
        if (!sprint || way <= ESCAPE_TURN * ESCAPE_SPEED) return way / ESCAPE_SPEED;
        return ESCAPE_TURN + (way - ESCAPE_TURN * ESCAPE_SPEED) / ESCAPE_SPRINT;
    }

    /** Blocks gone on the way out in `ticks` ticks. */
    static double escapeWay(double ticks, boolean sprint) {
        if (!sprint || ticks <= ESCAPE_TURN) return ticks * ESCAPE_SPEED;
        return ESCAPE_TURN * ESCAPE_SPEED + (ticks - ESCAPE_TURN) * ESCAPE_SPRINT;
    }

    /** A creeper whose fuse runs, or that is within its fuse distance plus the margin (about to start). */
    private static boolean threat(FightWorld w, MobSim c, double margin) {
        if (!c.alive || c.kind != MobSim.Kind.CREEPER) return false;
        if (c.swellDir > 0 || c.swelling) return true;
        double dx = c.x - w.player.x, dy = c.y - w.player.y, dz = c.z - w.player.z;
        return dx * dx + dy * dy + dz * dz < (FUSE + margin) * (FUSE + margin);
    }

    /** Projectiles still flying at the end of a plan that would hit the player standing where it is, within 10 ticks:
     *  their damage, as if taken (in health before the weight). */
    private double arrowsComing(FightWorld w) {
        if (w.arrows.length == 0) return 0.0;
        double[] pb = w.playerBox(phys);
        double c = 0;
        for (ArrowSim a0 : w.arrows) {
            if (!a0.alive) continue;
            ArrowSim a = a0.copy();
            for (int t = 0; t < 10 && a.alive; t++) {
                double dmg = a.tick(pb, phys.grid());
                if (dmg >= 0) { c += (a.scales ? w.scale(dmg) : dmg) + a.extra; break; }
            }
        }
        return c;
    }

    /** A plan that ends in the air over a drop (nothing to land on within the 3 safe blocks, below where it is or
     *  where its speed carries it) will fall: the marks for an unsafe plan, though the fall comes after the horizon.
     *  Without this a plan that steps off an edge looks free at its end, and a beam can keep only such plans. */
    private double falling(FightWorld w) {
        var p = w.player;
        if (p.onGround) return 0.0;
        double depth = Math.max(0.5, 3.0 - p.fallDistance);
        if (phys.unsafeAt(p, 0.0, 0.0, depth) || phys.unsafeAt(p, p.vx * 5.0, p.vz * 5.0, depth)) return UNSAFE;
        return 0.0;
    }

    /** The cost of standing where a nearby mob's hit would knock the player off an edge or into a hazard: the probe
     *  goes the way the knockback would (straight away from the mob; a skeleton's arrow from any range; a swelling
     *  creeper's blast further, the nearer it is). */
    private double edge(FightWorld w) {
        var p = w.player;
        if (!p.onGround) return 0.0;
        double worst = 0;
        for (MobSim m : w.mobs) {
            if (!m.alive) continue;
            double dx = p.x - m.x, dz = p.z - m.z, d = Math.sqrt(dx * dx + dz * dz);
            if (m.kind == MobSim.Kind.WITCH) continue;   // a splash pushes nobody
            double range = m.kind == MobSim.Kind.BOW || m.kind == MobSim.Kind.CROSSBOW || m.kind == MobSim.Kind.BLAZE || m.kind == MobSim.Kind.TRIDENT
                || m.kind == MobSim.Kind.BREEZE ? 16.0 : m.kind == MobSim.Kind.CREEPER ? 6.0 : EDGE_MOB_RANGE;
            if (d > range || d < 1e-6) continue;
            dx /= d; dz /= d;
            double one = 0.9;
            if (m.kind == MobSim.Kind.BREEZE) one = 2.5;   // a wind charge's burst throws further than a hit
            if (m.kind == MobSim.Kind.CREEPER) {
                if (!(m.swellDir > 0 || m.swelling)) continue;
                one = Math.max(0.9, 4.0 * (1.0 - d / (2.0 * m.blastRadius)));   // a blast's push carries further
            }
            if (phys.unsafeAt(p, dx * one, dz * one, 3.0)) worst = Math.max(worst, EDGE_ONE_HIT);
            else if (phys.unsafeAt(p, dx * 2 * one, dz * 2 * one, 3.0)) worst = Math.max(worst, EDGE_TWO_HITS);
        }
        return worst;
    }

    /**
     * For the debug line: one draw of a plan (an action a tick) from the root, its cost split into terms, weighted as
     * the search weighs them: health lost, spacing, dealt (negative), risk, idle, safety; and the end-of-plan
     * estimate's danger, per-mob share, edge, fight length. "hl=.. sp=.. dl=.. rk=.. id=.. sf=.. | dg=.. sh=.. ed=.. tm=..".
     */
    public String explain(FightWorld root, int[] plan, int hold, long seed) {
        FightWorld w = root.copy();
        double hl = 0, sp = 0, dl = 0, rk = 0, id = 0, sf = 0;
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(seed);
        for (int i = 0; i < plan.length; i++) {
            int action = plan[i];
            int run = i % hold != 0 && plan[i] == plan[i - 1] ? 1 : 0;
            if (action != Moves.HOLD && (run == 0 || Float.isNaN(w.segmentYaw))) w.segmentYaw = headingFor(w, Moves.direction(action));
            double wh = wHealth(w);
            double[] r = w.tick(phys, chooser(w, action, ideal), rnd);
            hl += wh * (r[1] + w.extraNow); sp += wh * spacing(w); dl -= wDealt * r[0]; rk += risk * w.alive(); id += idle(w); sf += safety(w);
        }
        double[] pt = new double[4];
        parts = pt;
        double est = estimate(w);
        parts = null;
        return String.format(java.util.Locale.ROOT, "hl=%.2f sp=%.2f dl=%.2f rk=%.2f id=%.2f sf=%.1f | dg=%.2f sh=%.2f ed=%.2f tm=%.2f est=%.2f",
            hl, sp, dl, rk, id, sf, pt[0], pt[1], pt[2], pt[3], est);
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    public double slider() { return slider; }
}
