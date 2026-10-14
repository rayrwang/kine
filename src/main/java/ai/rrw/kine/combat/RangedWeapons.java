package ai.rrw.kine.combat;

/**
 * The ranged weapons the bow aura shoots with, by the 26.1.2 rules, free of the game so they can be tested: a bow, a
 * crossbow, a trident with Loyalty. Their flights (Ballistics specs), how long each keeps you busy for a shot, and how
 * much damage a tick each gives on a mob ({@link #rate}), by which "On" picks the weapon for the next shot (RW
 * 2026-10-14: "auto select most powerful bow", and "calculate in terms of dps ... the charge time, any enchantments like
 * power, also the trident return time ... infinity ... durability").
 *
 * <p>The next shot goes to the weapon with the most damage for the ticks it keeps you busy, times its hit chance on the
 * mob (the caller's: speed and gravity differ, the spread doesn't, every one of them shoots with uncertainty 1). A
 * thrown trident keeps you busy only for its throw: while it flies back by Loyalty the bow or crossbow shoots, and with
 * several Loyalty tridents they go one after another, each waiting only for its own throw; a loaded crossbow fires at
 * once, so it goes first (a crossbow stays loaded in the inventory). Its sustained rate alone ({@link #cycle}) counts
 * the return, for the case where it is the only weapon. Busy ticks and damage:
 * <ul>
 *   <li>bow: a full draw (20 ticks) and the release, then the wait for the server's word that the use has ended (2): 23
 *       ticks. The arrow (AbstractArrow.onHitEntity): ceil(speed at impact x base damage), base 2 plus Power's 0.5 a
 *       level and 0.5; a full draw is a crit, nextInt(damage / 2 + 2) more. Flame sets it alight for 5 s, 1 a second
 *       while it burns, about 1 more a shot at one shot a second. Punch knocks it back (no damage); Infinity saves the
 *       arrow (no damage; preferred on a tie).</li>
 *   <li>crossbow: loading (CrossbowItem.getChargeDuration: 1.25 s less 0.25 s a level of Quick Charge), the release,
 *       the press that fires (on its own tick, after the server's word), and the 4 ticks the game makes a held use wait
 *       after a use: the charge and 6. Its arrow leaves at 3.15 and is always a crit. Multishot's two side arrows go 10
 *       degrees off (no more damage on one mob); Piercing passes through mobs and shields.</li>
 *   <li>trident (Loyalty only: without it a throw would be lost; never Riptide, which can't be thrown): 10 ticks of
 *       draw (TridentItem.THROW_THRESHOLD_TIME), the release, its flight out at 2.5, 4 ticks before Loyalty turns it
 *       back after a hit, the flight back (ThrownTrident: it speeds up by 0.05 x Loyalty a tick toward you, keeping 0.95
 *       of its speed, so it comes back at up to Loyalty blocks a tick), and the server's word: 8 damage a hit (no crit,
 *       whatever its speed), Impaling 2.5 a level more on the mobs in the sensitive_to_impaling tag (the aquatic ones; in
 *       Java not on a mob just because it is wet).</li>
 * </ul>
 */
public final class RangedWeapons {
    private RangedWeapons() {}

    public enum Kind { BOW, CROSSBOW, TRIDENT }

    public static final int BOW_DRAW = 20, TRIDENT_DRAW = 10;
    static final int SERVER_WORD = 2;   // ticks for the server's "no longer using" to come back (lockstep: 1 to 2)

    /** The projectile's flight at full draw: gravity, drag, launch speed, whether it takes on the shooter's motion. */
    public static Ballistics.Spec spec(Kind k) {
        return switch (k) {
            case BOW -> new Ballistics.Spec(0.05, 0.99F, 3.0, 0.0, false, true, true);
            case CROSSBOW -> new Ballistics.Spec(0.05, 0.99F, 3.15, 0.0, false, true, false);   // CrossbowItem.shootProjectile
            case TRIDENT -> new Ballistics.Spec(0.05, 0.99F, 2.5, 0.0, false, true, true);
        };
    }

    /** The bow's flight at a draw of `power` (BowItem.getPowerForTime). */
    public static Ballistics.Spec bowSpec(float power) {
        return new Ballistics.Spec(0.05, 0.99F, power * 3.0, 0.0, false, true, true);
    }

    /** CrossbowItem.getChargeDuration: floor((1.25 - 0.25 x Quick Charge) x 20) ticks, at least 0. */
    public static int crossbowCharge(int quickCharge) {
        return Math.max(0, (int) Math.floor((1.25F - 0.25F * quickCharge) * 20.0F));
    }

    /** Speed left after flying `dist` blocks from a launch at v0 (drag 0.99 a tick takes about 0.01 a block). */
    static double speedAt(double v0, double dist) {
        return Math.max(0.5, v0 - 0.01 * dist);
    }

    /** CombatRules.getDamageAfterAbsorb for one hit. */
    static double afterArmour(double dmg, double armour, double toughness) {
        double tough = 2.0 + toughness / 4.0;
        double cut = Math.max(0.0, Math.min(20.0, Math.max(armour / 5.0, armour - dmg / tough)));
        return dmg * (1.0 - cut / 25.0);
    }

    /** One weapon as the rate sees it: its enchantment levels (Power, Flame, Quick Charge, Loyalty, Impaling), and
     *  for a crossbow whether it is loaded. */
    public record Arms(Kind kind, int power, int flame, int quickCharge, int loyalty, int impaling, boolean loaded) {
        public Arms(Kind kind, int power, int flame, int quickCharge, int loyalty, int impaling) {
            this(kind, power, flame, quickCharge, loyalty, impaling, false);
        }
        public static Arms bow(int power, int flame) { return new Arms(Kind.BOW, power, flame, 0, 0, 0); }
        public static Arms crossbow(int quickCharge, boolean loaded) { return new Arms(Kind.CROSSBOW, 0, 0, quickCharge, 0, 0, loaded); }
        public static Arms trident(int loyalty, int impaling) { return new Arms(Kind.TRIDENT, 0, 0, 0, loyalty, impaling); }
    }

    /** Ticks a shot keeps you busy: a bow's draw, the release and the server's word (23); a crossbow's load, release,
     *  the press and the game's 4 ticks before a held use goes again (the charge and 6), or 2 when it is loaded already;
     *  a trident's throw, the release and the server's word (13; its flight back takes none of your time). */
    public static double busy(Arms a) {
        return switch (a.kind()) {
            case BOW -> BOW_DRAW + 1 + SERVER_WORD;
            case CROSSBOW -> a.loaded() ? 2 : crossbowCharge(a.quickCharge()) + 6;
            case TRIDENT -> TRIDENT_DRAW + 1 + SERVER_WORD;
        };
    }

    /** Ticks one shot takes with this weapon alone at this distance, start to the next start (a trident's return
     *  included). */
    public static double cycle(Arms a, double dist) {
        return switch (a.kind()) {
            case BOW -> BOW_DRAW + 1 + SERVER_WORD;
            case CROSSBOW -> crossbowCharge(a.quickCharge()) + 6;
            case TRIDENT -> {
                // ThrownTrident.tick: the return speeds up by 0.05 x Loyalty a tick, keeping 0.95: up to Loyalty a tick
                double out = dist / 2.4, back = dist / Math.max(1, a.loyalty()) + 4.0;   // its speed builds over a few ticks
                yield TRIDENT_DRAW + 1 + out + 4 + back + SERVER_WORD;
            }
        };
    }

    /** Damage of one hit after armour; `impalable`: the mob is one Impaling hurts more (EntityTypeTags.SENSITIVE_TO_IMPALING). */
    public static double hit(Arms a, double dist, double armour, double toughness, boolean impalable) {
        return switch (a.kind()) {
            case BOW -> BowTargets.arrowDamage(speedAt(3.0, dist), a.power(), armour, toughness) + (a.flame() > 0 ? 1.0 : 0.0);
            case CROSSBOW -> BowTargets.arrowDamage(speedAt(3.15, dist), 0, armour, toughness);
            case TRIDENT -> afterArmour(8.0 + (impalable ? 2.5 * a.impaling() : 0.0), armour, toughness);
        };
    }

    /** Damage a busy tick of the next shot with this weapon gives on a mob at `dist` with this armour. */
    public static double rate(Arms a, double dist, double armour, double toughness, boolean impalable) {
        return hit(a, dist, armour, toughness, impalable) / busy(a);
    }

    /** Damage a tick this weapon sustains alone (a trident's return counted). */
    public static double sustained(Arms a, double dist, double armour, double toughness, boolean impalable) {
        return hit(a, dist, armour, toughness, impalable) / cycle(a, dist);
    }
}
