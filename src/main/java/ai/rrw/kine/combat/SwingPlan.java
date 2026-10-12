package ai.rrw.kine.combat;

import java.util.List;

/**
 * When the kill aura swings, and at which mob, on the 26.1.2 melee rules. No game state: KillAura gathers the mobs,
 * the unit tests call this directly.
 *
 * <p>The rules, from the decompile:
 * <ul>
 *   <li>Player.attack: a hit deals damage * (0.2 + 0.8 c^2) plus c times the enchantment bonus, where c, the charge,
 *       is clamp((ticks since the last swing + 0.5) / delay, 0, 1) (getAttackStrengthScale) and every swing starts it
 *       again (onAttack). Above c = 0.9 a hit crits (x1.5 on the damage) when falling and not sprinting, or, with a
 *       sword on the ground and not sprinting, sweeps: each mob whose box meets the hit mob's box grown by 1, 0.25, 1,
 *       within 3 blocks of you, takes (1 + sweep ratio * damage + its own bonus) * c (doSweepAttack).</li>
 *   <li>LivingEntity.hurtServer: a mob takes a full hit, then no full hit for 10 ticks; a hit inside those 10 ticks
 *       does only what it has over the biggest hit so far in them, and nothing if it is not bigger.</li>
 *   <li>CombatRules.getDamageAfterAbsorb: armour takes its share of what gets through.</li>
 * </ul>
 *
 * <p>Against N mobs in reach the most damage a tick (a dynamic program over the charge and each mob's 10 ticks,
 * sim/dp_still.py in the kill aura study) comes in cycles: N - 1 quick swings, a tick apart, and one charged swing,
 * each mob hit once a cycle, a cycle never shorter than 10 ticks. One mob: wait for the full charge. Two or three
 * (sword): a full-charge hit, then a quick hit on each of the others. Four to nine: a 10-tick cycle, the charged swing
 * at whatever charge 11 - N ticks give. Ten or more: every tick. When a full-charge hit's sweep deals more a tick than
 * the cycle, full-charge sweeps instead. So each tick, in order:
 * <ol>
 *   <li>Threats: a mob whose attack would reach you within THREAT_TICKS is hit now, whatever the charge; a fresh hit
 *       knocks it back out of its reach.</li>
 *   <li>A swing that pays for itself: if a hit now deals at least the sustained rate for each tick of charge it
 *       uses up, take it (the quick hits of a cycle, and cheap kills).</li>
 *   <li>Otherwise the charged swing waits for the cycle's charge (a full charge when sweeping) and takes the mob
 *       worth most, its sweep included, the one with least health first on a tie.</li>
 * </ol>
 * With the rotation turning at most so far a tick, the quick swings come as far apart as the turn from one mob to
 * the next takes, and while it charges between bursts against a crowd on one side, the aim waits at the near end of
 * the crowd so the next burst sweeps across it one way. In a jump, a full swing a few ticks before the fall waits for
 * the crit when that pays.
 */
public final class SwingPlan {
    private SwingPlan() {}

    /** A charge this close to 1 is a full one (see Weapon.fullTicks). */
    static final float FULL = 0.9999F;
    /** Ticks a struck mob takes before a full hit again (invulnerableTime 20, full hits at 10 and below). */
    public static final int WINDOW = 10;
    /** Hit a mob regardless of charge when its attack would reach you within this many ticks. */
    public static final double THREAT_TICKS = 3.0;
    /** Mobs whose attack would reach you within this many ticks are where the aim goes while it waits. */
    static final double AIM_THREAT_TICKS = 8.0;

    /** The held weapon: damage with the player's base 1, ticks to a full charge (Player.getCurrentItemAttackStrengthDelay),
     *  whether it sweeps (a sword), and its sweeping damage ratio (Sweeping Edge: level / (level + 1)). */
    public record Weapon(double damage, float delay, boolean sword, double sweepRatio) {
        /** The fewest ticks since the last swing that give a full charge, to within float rounding: a sword's speed
         *  modifier is the float -2.4F, so its speed is 4 - 2.4000001 = 1.5999999 and its delay 12.500001 ticks, and
         *  12 ticks give a charge of 0.99999994, as good as full (1e-7 less damage); waiting for 1.0 costs a 13th tick. */
        public int fullTicks() {
            int k = 1;
            while (charge(k, delay) < FULL && k < 1000) k++;
            return k;
        }
    }

    /** One mob in reach, as the aura sees it this tick. */
    public static final class Mob {
        public double health;
        public double armour, toughness;
        /** The damage the held weapon's enchantments add against this mob at full charge (Sharpness, Smite, Bane). */
        public double bonus;
        /** Ticks until it takes a full hit again, 0 now. */
        public int freshIn;
        /** The biggest hit in its current window, which a hit must beat to do anything (when freshIn > 0). */
        public double lastHurt;
        /** The other mobs (indices in the list) a sweep around this one would catch. */
        public int[] swept = new int[0];
        /** Ticks until its attack reaches you, 0 if it does now, infinity if it is not coming. */
        public double contact = Double.POSITIVE_INFINITY;
        /** Whether it can be hit at all: in reach with nothing in the way (a mob out of play still takes sweeps). */
        public boolean inPlay = true;
        /** Whether the crosshair can be put on it this tick without turning faster than allowed. */
        public boolean reachable = true;
        /** The yaw of its centre from the rotation sent last tick, in degrees (-180 to 180). */
        public double yaw;
        /** Half its width as seen from the eye, in degrees: aiming anywhere within that of its centre hits it. */
        public double halfWidth;
        /** Ticks of turning before the crosshair can be on it (0: this tick). */
        public int turnTicks;

        public Mob(double health, double armour) {
            this.health = health;
            this.armour = armour;
        }

        boolean fresh() { return freshIn <= 0; }
    }

    /** The decision: the mob to hit now (-1 for none), and the mob to turn toward while waiting (-1 for the view). */
    public record Swing(int hit, int aim) {
        public static final Swing NONE = new Swing(-1, -1);
    }

    /** The cycle with the most damage a tick for n like mobs: one swing in it after G ticks of charge, the rest quick. */
    public record Cycle(int swings, int chargeTicks, double rate) {}

    // ---- the game's numbers ----

    /** Player.getAttackStrengthScale(0.5), in float as the game does it. */
    public static float charge(int ticks, float delay) {
        float c = (ticks + 0.5F) / delay;
        return c < 0.0F ? 0.0F : Math.min(c, 1.0F);
    }

    /** Player.baseDamageScaleFactor. */
    static float scale(float c) {
        return 0.2F + c * c * 0.8F;
    }

    /** CombatRules.getDamageAfterAbsorb (armour effectiveness enchantments left out). */
    public static double afterArmour(double damage, double armour, double toughness) {
        if (armour <= 0) return damage;
        double t = 2.0 + toughness / 4.0;
        double real = Math.min(20.0, Math.max(armour * 0.2, armour - damage / t));
        return damage * (1.0 - real / 25.0);
    }

    /** What a hit takes off a mob: all of a fresh one's, the excess over the window's biggest hit otherwise. */
    static double through(Mob m, double damage) {
        double raw = m.fresh() ? damage : damage - m.lastHurt;
        return raw <= 0 ? 0.0 : Math.min(m.health, afterArmour(raw, m.armour, m.toughness));
    }

    /** The raw damage of a hit on a mob with this bonus (Player.attack): the base scaled by the charge, x1.5 on a crit,
     *  plus the bonus scaled by the charge. */
    public static double hitDamage(Weapon w, double bonus, float c, boolean crit) {
        double base = w.damage * scale(c);
        if (crit) base *= 1.5;
        return base + c * bonus;
    }

    /** The raw sweep damage on a neighbour with this bonus, from a hit at charge c (Player.doSweepAttack). */
    public static double sweepDamage(Weapon w, double neighbourBonus, float c) {
        return (1.0 + w.sweepRatio * w.damage * scale(c) + neighbourBonus) * c;
    }

    /**
     * Health a swing at mob i with k ticks of charge takes off, the sweep included.
     * @param canSweep the sweep's other conditions hold (a sword, on the ground, not sprinting, not moving fast)
     * @param canCrit  a full-strength hit now would crit (falling, not on the ground, not sprinting, ...)
     */
    public static double value(Weapon w, int k, boolean canSweep, boolean canCrit, List<Mob> mobs, int i) {
        Mob m = mobs.get(i);
        float c = charge(k, w.delay);
        boolean full = c > 0.9F;
        boolean crit = canCrit && full;
        double v = through(m, hitDamage(w, m.bonus, c, crit));
        if (v <= 0) return 0.0;   // a hit that hurts nothing doesn't sweep either
        if (w.sword && canSweep && full && !crit) {
            for (int j : m.swept) {
                Mob o = mobs.get(j);
                v += through(o, sweepDamage(w, o.bonus, c));
            }
        }
        return v;
    }

    /** The damage of one hit on a fresh, endless copy of this mob (no health cap), for the rate formulas. */
    static double typicalHit(Weapon w, Mob m, int k) {
        float c = charge(k, w.delay);
        return afterArmour(hitDamage(w, m.bonus, c, false), m.armour, m.toughness);
    }

    /**
     * The best cycle against n mobs like m: s - 1 quick swings and one after G ticks of charge, each swing in it on a
     * different mob, the cycle long enough that the swings, spread over the n mobs, come at least WINDOW ticks apart
     * on each. With no limit on turning the quick swings come a tick apart (k = 1), and this is the dynamic program's
     * optimum for still mobs (checked against it for 1 to 9 mobs, sword and axe, with and without Sharpness).
     */
    public static Cycle bestCycle(Weapon w, Mob m, int n) {
        return bestCycle(w, m, n, 1);
    }

    /** bestCycle when the turn from one mob to the next takes gap ticks: the quick swings come gap ticks apart, and the
     *  charged one at least that long after the last. */
    public static Cycle bestCycle(Weapon w, Mob m, int n, int gap) {
        int full = w.fullTicks();
        gap = Math.max(1, Math.min(gap, full));
        double hq = typicalHit(w, m, gap);
        Cycle best = new Cycle(1, full, typicalHit(w, m, full) / full);
        for (int s = 1; s <= Math.max(1, n); s++) {
            for (int g = gap; g <= full; g++) {   // even a lone swing a cycle turns to another mob unless g >= WINDOW
                int len = (s - 1) * gap + g;
                if ((long) len * n < (long) WINDOW * s) continue;
                double r = ((s - 1) * hq + typicalHit(w, m, g)) / len;
                if (r > best.rate + 1e-12) best = new Cycle(s, g, r);
            }
        }
        return best;
    }

    /** Ticks of turning between two mobs in turn, at maxTurn degrees a tick: the typical gap in yaw between neighbouring
     *  mobs (a tour round them takes about that each step), less the half width of the next one (the crosshair need
     *  only reach its edge); 1 when turning is free or the mobs are close together. */
    static int quickGap(List<Mob> mobs, double maxTurn) {
        if (Double.isInfinite(maxTurn)) return 1;
        double[] ys = mobs.stream().filter(m -> m.inPlay).mapToDouble(m -> m.yaw).sorted().toArray();
        if (ys.length < 2) return 1;
        double hw = mobs.stream().filter(m -> m.inPlay).mapToDouble(m -> m.halfWidth).average().orElse(0.0);
        double[] gaps = new double[ys.length];
        for (int i = 0; i + 1 < ys.length; i++) gaps[i] = ys[i + 1] - ys[i];
        gaps[ys.length - 1] = ys[0] + 360.0 - ys[ys.length - 1];
        java.util.Arrays.sort(gaps);
        double sep = gaps.length > 2 ? gaps[gaps.length / 2 - 1] : gaps[0];
        return Math.max(1, (int) Math.ceil((sep - hw) / maxTurn - 1e-9));
    }

    /** The smallest arc of yaw that holds every mob in play, in degrees. */
    static double spread(List<Mob> mobs) {
        double[] ys = mobs.stream().filter(m -> m.inPlay).mapToDouble(m -> m.yaw).sorted().toArray();
        if (ys.length < 2) return 0.0;
        double widest = ys[0] + 360.0 - ys[ys.length - 1];
        for (int i = 0; i + 1 < ys.length; i++) widest = Math.max(widest, ys[i + 1] - ys[i]);
        return 360.0 - widest;
    }

    /** Damage a tick of full-charge hits on mob i every full charge, its sweep catching its neighbours as they are now,
     *  all fresh (a full charge takes 12 ticks with a sword, past the 10-tick window), no health caps. */
    public static double sweepRate(Weapon w, List<Mob> mobs, int i) {
        Mob hub = mobs.get(i);
        double v = typicalHit(w, hub, w.fullTicks());
        for (int j : hub.swept) {
            Mob o = mobs.get(j);
            v += afterArmour(sweepDamage(w, o.bonus, 1.0F), o.armour, o.toughness);
        }
        return v / w.fullTicks();
    }

    /** The damage a tick this weapon can sustain against these mobs: the better of the best cycle and sweeping
     *  (auto weapon's score). */
    public static double rate(Weapon w, List<Mob> mobs, boolean canSweep) {
        int n = inPlay(mobs);
        if (n == 0) return 0.0;
        double r = bestCycle(w, typical(mobs), n).rate;
        if (w.sword && canSweep) for (int i = 0; i < mobs.size(); i++) if (mobs.get(i).inPlay) r = Math.max(r, sweepRate(w, mobs, i));
        return r;
    }

    /** The mob the rate formulas take as typical: the one in play the weapon's enchantments hurt most. */
    static Mob typical(List<Mob> mobs) {
        Mob t = null;
        for (Mob m : mobs) if (m.inPlay && (t == null || m.bonus > t.bonus)) t = m;
        return t != null ? t : mobs.get(0);
    }

    static int inPlay(List<Mob> mobs) {
        int n = 0;
        for (Mob m : mobs) if (m.inPlay) n++;
        return n;
    }

    /** The decision for this tick, turning as fast as need be, no jump under way. */
    public static Swing decide(Weapon w, int k, boolean canSweep, boolean canCrit, List<Mob> mobs) {
        return decide(w, k, canSweep, canCrit, -1, mobs, Double.POSITIVE_INFINITY);
    }

    /**
     * The decision for this tick.
     * @param k       ticks since the last swing, as the server will count them when this tick's attack arrives
     * @param critIn  in a jump, the ticks until a hit would crit (the server reads you falling), else -1
     * @param maxTurn the most the rotation sent may turn in a tick, in degrees; the mobs' yaw and turnTicks say where
     *                they are from it
     */
    public static Swing decide(Weapon w, int k, boolean canSweep, boolean canCrit, int critIn, List<Mob> mobs,
                               double maxTurn) {
        int n = mobs.size();
        int playing = inPlay(mobs);
        if (playing == 0) return Swing.NONE;
        k = Math.max(1, k);
        // While it charges between bursts against a crowd on one side, the aim waits at the near end of it, so the next
        // burst sweeps across it one way, a short turn from each mob to the next. A crowd all round, or ten or more
        // mobs (a swing every tick, no waiting), goes in list order.
        boolean tour = !Double.isInfinite(maxTurn) && playing < WINDOW && spread(mobs) < 180.0;

        // the rate the fight can sustain, and when the charged swing is due
        Cycle cycle = bestCycle(w, typical(mobs), playing, quickGap(mobs, maxTurn));
        double rho = cycle.rate;
        int due = cycle.chargeTicks;
        int hub = -1;
        if (w.sword && canSweep) {
            boolean anyFresh = false;
            for (Mob m : mobs) anyFresh |= m.inPlay && m.fresh();
            for (int i = 0; i < n; i++) {
                if (!mobs.get(i).inPlay || anyFresh && !mobs.get(i).fresh()) continue;
                double r = sweepRate(w, mobs, i);
                if (r > rho + 1e-9 || hub >= 0 && r > rho - 1e-9 && mobs.get(i).turnTicks < mobs.get(hub).turnTicks) {
                    rho = Math.max(rho, r);
                    hub = i;   // the best hub, the one the turn reaches soonest among equals
                }
            }
            if (hub >= 0) due = w.fullTicks();
        }

        // 1. threats
        int threat = -1;
        for (int i = 0; i < n; i++) {
            Mob m = mobs.get(i);
            if (!m.reachable || !m.fresh() || m.contact > THREAT_TICKS) continue;
            if (threat < 0 || m.contact < mobs.get(threat).contact
                || m.contact == mobs.get(threat).contact && m.health < mobs.get(threat).health) threat = i;
        }
        if (threat >= 0) return new Swing(threat, threat);

        // 2. and 3. the best swing now, taken if it pays for its charge or the charged swing is due; between equals
        // the nearest in a tour, else the first in the list, then the one with least health
        int best = -1;
        double bestV = 0.0;
        for (int i = 0; i < n; i++) {
            Mob m = mobs.get(i);
            if (!m.reachable || !m.fresh()) continue;
            double v = value(w, k, canSweep, canCrit, mobs, i);
            boolean better = best < 0 || v > bestV + 1e-9;
            if (!better && Math.abs(v - bestV) <= 1e-9) {
                Mob b = mobs.get(best);
                double dm = tour ? Math.abs(m.yaw) : 0.0, db = tour ? Math.abs(b.yaw) : 0.0;
                better = dm < db - 1e-9 || Math.abs(dm - db) <= 1e-9 && m.health < b.health;
            }
            if (better) { best = i; bestV = v; }
        }
        if (best >= 0 && bestV > 0 && (bestV >= rho * k - 1e-9 || k >= due)) {
            // in a jump, a crit (x1.5 on the damage) a few ticks on can be worth the wait: more than the rate those
            // ticks would bring
            if (!canCrit && critIn > 0) {
                double later = value(w, k + critIn, false, true, mobs, best);
                if (later - bestV > rho * critIn + 1e-9) return new Swing(-1, best);
            }
            return new Swing(best, best);
        }

        // waiting: where to turn
        return new Swing(-1, aim(w, k, due, hub, tour, canSweep, canCrit, mobs));
    }

    /** While waiting: the mob nearest to reaching you; else the hub of the sweep; else, in a tour, the near end of the
     *  mobs fresh by the time the charged swing is due; else the fresh mob the turn reaches soonest, worth most at the
     *  charge it will have; else the mob that can be hit soonest. */
    static int aim(Weapon w, int k, int due, int hub, boolean tour, boolean canSweep, boolean canCrit, List<Mob> mobs) {
        int n = mobs.size();
        int near = -1;
        for (int i = 0; i < n; i++) {
            Mob m = mobs.get(i);
            if (m.inPlay && m.fresh() && m.contact < AIM_THREAT_TICKS && (near < 0 || m.contact < mobs.get(near).contact)) near = i;
        }
        if (near >= 0) return near;
        if (hub >= 0) return hub;
        if (tour) {
            int lo = -1, hi = -1;
            int by = Math.max(0, due - k);
            for (int pass = 0; pass < 2 && lo < 0; pass++) {
                for (int i = 0; i < n; i++) {
                    Mob m = mobs.get(i);
                    if (!m.inPlay || pass == 0 && m.freshIn > by) continue;
                    if (lo < 0 || m.yaw < mobs.get(lo).yaw) lo = i;
                    if (hi < 0 || m.yaw > mobs.get(hi).yaw) hi = i;
                }
            }
            if (lo >= 0) return Math.abs(mobs.get(lo).yaw) <= Math.abs(mobs.get(hi).yaw) ? lo : hi;
        }
        int best = -1;
        double bestV = -1;
        int kk = Math.max(k, due);
        for (int i = 0; i < n; i++) {
            Mob m = mobs.get(i);
            if (!m.inPlay || !m.fresh()) continue;
            double v = value(w, kk, canSweep, canCrit, mobs, i);
            if (best < 0 || m.turnTicks < mobs.get(best).turnTicks || m.turnTicks == mobs.get(best).turnTicks && v > bestV) {
                bestV = v;
                best = i;
            }
        }
        if (best >= 0) return best;
        int soon = -1;
        for (int i = 0; i < n; i++) {
            Mob m = mobs.get(i);
            if (!m.inPlay) continue;
            int when = Math.max(m.freshIn, m.turnTicks);
            if (soon < 0) { soon = i; continue; }
            Mob s = mobs.get(soon);
            int sw = Math.max(s.freshIn, s.turnTicks);
            if (when < sw || when == sw && m.turnTicks < s.turnTicks) soon = i;
        }
        return soon;
    }
}
