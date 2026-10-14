package ai.rrw.kine.combat;

import java.util.List;

/**
 * Which mob the bow aura shoots, free of the game so it can be tested on its own. BowAura gives each mob it could shoot
 * as a {@link Cand}: what kind of fighter it is, how far, its health and armour, the chance a full-power arrow aimed at it
 * now hits (HitChance, blocks counted), how fast it hurts you, and how soon a melee mob reaches you. A {@link Policy}
 * ranks them; mobs whose chance is below {@link #VIABLE} are never picked, so every policy only orders shots that can
 * land.
 *
 * <p>The policies are those RW named (2026-10-14: the nearest, the one doing the most damage, ranged mobs first, the
 * one with the least health) and three more. THREAT: Smith's rule for a single shooter, the threat a mob poses (health a
 * tick it takes off you, {@link #threat}) times the chance a shot hits, over the arrows it takes to kill. Shooting in
 * that order makes the total of each mob's threat times the time until it dies smallest when the mobs stand still and
 * keep their threat (a classic scheduling result: weighted shortest processing time first); the threat of a melee mob
 * grows as it comes, so the order is worked out again every few ticks. SHARED_THREAT, the default: the same rule with
 * the threat the bow takes off rather than the whole of it, since the kill aura deals with a creeper or a melee mob
 * when it arrives and never reaches a ranged one ({@link #sharedThreat}). RANGED_THEN_THREAT: ranged mobs first, each
 * group by THREAT. In the model-world study (BowStudy: 300 fights a scene and policy, six scenes) SHARED_THREAT and
 * RANGED_THEN_THREAT lost the least health (12.6 and 12.7 a fight on average), then RANGED_FIRST 13.2, THREAT 14.2, MOST_DAMAGE
 * 14.8, LEAST_HEALTH 15.8, NEAREST 17.2.
 */
public final class BowTargets {
    private BowTargets() {}

    /** Mobs whose shot hits less often than this are left out of the choice: nothing would come of aiming at them. */
    public static final double VIABLE = 0.3;
    /** A mob already aimed at keeps the bow unless another scores this many times better (a switch costs the turn). */
    public static final double KEEP = 1.3;

    /** How a mob hurts you: from range (skeletons, pillagers, witches, blazes, ghasts...), by blowing up (creepers),
     *  or by coming to you (the rest; phantoms and vexes fly to you). */
    public enum Kind { RANGED, CREEPER, MELEE }

    /** The ranking rules compared in the target-choice study. */
    public enum Policy {
        NEAREST, RANGED_FIRST, LEAST_HEALTH, MOST_DAMAGE, THREAT, RANGED_THEN_THREAT, SHARED_THREAT;

        public static Policy parse(String s, Policy fallback) {
            if (s == null) return fallback;
            for (Policy p : values()) if (p.name().equalsIgnoreCase(s.trim())) return p;
            return fallback;
        }
    }

    /**
     * One mob the bow could shoot.
     * @param dist        eye to box centre, blocks
     * @param chance      the chance a full-power arrow aimed now hits it, 0 to 1
     * @param dps         health a tick it takes off you while it attacks (in its range, or in contact)
     * @param range       a ranged mob's attack range, blocks (it attacks from within it)
     * @param sees        it has a clear line to you
     * @param arrive      ticks until a melee mob or creeper is on you at its present closing speed (infinite if not closing)
     * @param arrowDamage the expected damage of one full-power arrow on it, after its armour
     */
    public record Cand(int id, Kind kind, double dist, double health, double chance, double dps, double range,
                       boolean sees, double arrive, double arrowDamage) {}

    /** A creeper's blast near you on Normal, as the threat counts it (the game's goes to 43 point blank, less with
     *  distance and armour). */
    static final double BLAST = 25.0;
    /** A lit creeper's fuse, ticks (Creeper.maxSwell 30). */
    static final double FUSE = 30.0;
    /** A melee mob's threat counts in full once it is this many ticks from you, half at twice that. */
    static final double SOON = 20.0;
    /** A ranged mob out of its range or out of sight still comes on: its threat counts this much. */
    static final double OUT_OF_RANGE = 0.3;

    /** Health a tick the mob takes off you as things stand: a ranged mob at its rate when it is in range and sees
     *  you; a creeper its blast spread over the time until it could go off; a melee mob its rate, weighted by how
     *  soon it reaches you. */
    public static double threat(Cand c) {
        return switch (c.kind()) {
            case RANGED -> c.dps() * (c.sees() && c.dist() <= c.range() ? 1.0 : OUT_OF_RANGE);
            case CREEPER -> BLAST / (Math.max(0.0, c.arrive()) + FUSE);
            case MELEE -> c.dps() * SOON / (SOON + Math.max(0.0, c.arrive()));
        };
    }

    /** The share of a creeper's or melee mob's threat the bow takes off, the kill aura taking the rest when it arrives. */
    static final double MELEE_SHARE = Double.parseDouble(System.getProperty("kine.meleeShare", "0.25"));   // the study varies it

    static double sharedThreat(Cand c) {
        return switch (c.kind()) {
            case RANGED -> c.dps();
            case CREEPER -> MELEE_SHARE * BLAST / (Math.max(0.0, c.arrive()) + FUSE);
            case MELEE -> MELEE_SHARE * c.dps() * SOON / (SOON + Math.max(0.0, c.arrive()));
        };
    }

    /** Full-power arrows it takes to kill the mob, on average. */
    public static double arrows(Cand c) {
        return Math.max(1.0, Math.ceil(c.health() / Math.max(0.5, c.arrowDamage())));
    }

    /** How the policy rates a mob; higher goes first. */
    public static double score(Cand c, Policy p) {
        return switch (p) {
            case NEAREST -> -c.dist();
            // ranged mobs first (they never come to the melee aura), nearest first within each group
            case RANGED_FIRST -> (c.kind() == Kind.RANGED ? 1e6 : 0.0) - c.dist();
            // fewest arrows to kill, then the surer shot, then the nearer
            case LEAST_HEALTH -> -arrows(c) * 1e4 + c.chance() * 100.0 - c.dist() * 1e-3;
            case MOST_DAMAGE -> threat(c) * 1e3 - c.dist() * 1e-6;
            case THREAT -> threat(c) * c.chance() / arrows(c);
            // ranged mobs first (the melee aura never reaches them), each group by THREAT
            case RANGED_THEN_THREAT -> (c.kind() == Kind.RANGED ? 1e6 : 0.0) + threat(c) * c.chance() / arrows(c);
            // THREAT with the share of a mob's threat the bow takes off: a ranged mob's all of it, wherever it stands (it
            // comes into its range); a creeper's and a melee mob's only MELEE_SHARE, the kill aura dealing with them when
            // they arrive
            case SHARED_THREAT -> sharedThreat(c) * c.chance() / arrows(c);
        };
    }

    /**
     * The index of the mob to shoot, or -1 if none is viable. {@code current}: the id of the mob aimed at now (-1 for
     * none), kept unless another scores KEEP times better (for the scores that can be negative, better by more than the
     * mob's own size of score).
     */
    public static int pick(List<Cand> cs, Policy p, int current) {
        int best = -1, cur = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < cs.size(); i++) {
            Cand c = cs.get(i);
            if (c.chance() < VIABLE) continue;
            if (c.id() == current) cur = i;
            double s = score(c, p);
            if (s > bestScore) { bestScore = s; best = i; }
        }
        if (cur < 0 || best == cur) return best;
        double sc = score(cs.get(cur), p);
        boolean better = sc > 0 ? bestScore > sc * KEEP : bestScore > sc + Math.abs(sc) * (KEEP - 1.0) + 1e-9;
        return better ? best : cur;
    }

    /**
     * Expected damage of one full-power arrow (bow, power 1) that has slowed to {@code speed} blocks a tick, after
     * armour: AbstractArrow.onHitEntity's ceil(speed * baseDamage), base 2 plus Power's 0.5 a level and 0.5, the
     * crit's extra nextInt(damage / 2 + 2) (every full-power bow arrow is a crit), then CombatRules.getDamageAfterAbsorb.
     */
    public static double arrowDamage(double speed, int powerLevel, double armour, double toughness) {
        double base = 2.0 + (powerLevel > 0 ? 0.5 * powerLevel + 0.5 : 0.0);
        int d = (int) Math.ceil(Math.min(speed * base, Integer.MAX_VALUE));
        double crit = (d / 2 + 1) / 2.0;   // mean of nextInt(d / 2 + 2)
        double dmg = d + crit;
        double tough = 2.0 + toughness / 4.0;
        double cut = Math.max(0.0, Math.min(20.0, Math.max(armour / 5.0, armour - dmg / tough)));
        return dmg * (1.0 - cut / 25.0);
    }
}
