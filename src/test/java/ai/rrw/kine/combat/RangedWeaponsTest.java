package ai.rrw.kine.combat;

import ai.rrw.kine.combat.RangedWeapons.Arms;
import ai.rrw.kine.combat.RangedWeapons.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The bow aura's weapon choice by damage per busy tick (RangedWeapons), against the 26.1.2 rules. */
class RangedWeaponsTest {
    static double rate(Arms a, double dist) { return RangedWeapons.rate(a, dist, 0, 0, false); }

    @Test
    void crossbowChargeFollowsQuickCharge() {
        // CrossbowItem.getChargeDuration: floor((1.25 - 0.25 x level) x 20)
        assertEquals(25, RangedWeapons.crossbowCharge(0));
        assertEquals(20, RangedWeapons.crossbowCharge(1));
        assertEquals(15, RangedWeapons.crossbowCharge(2));
        assertEquals(10, RangedWeapons.crossbowCharge(3));
        assertEquals(0, RangedWeapons.crossbowCharge(5));
    }

    @Test
    void specsAreTheGames() {
        assertEquals(3.0, RangedWeapons.spec(Kind.BOW).speed());
        assertEquals(3.15, RangedWeapons.spec(Kind.CROSSBOW).speed());
        assertEquals(2.5, RangedWeapons.spec(Kind.TRIDENT).speed());
        assertFalse(RangedWeapons.spec(Kind.CROSSBOW).inherits(), "a crossbow's shot takes none of your motion");
        assertTrue(RangedWeapons.spec(Kind.BOW).inherits());
    }

    @Test
    void powerRanksBows() {
        double prev = 0;
        for (int power = 0; power <= 5; power++) {
            double r = rate(Arms.bow(power, 0), 16);
            assertTrue(r > prev, "Power " + power);
            prev = r;
        }
        assertTrue(rate(Arms.bow(1, 1), 16) > rate(Arms.bow(1, 0), 16), "Flame adds a little");
        assertTrue(rate(Arms.bow(2, 1), 16) < rate(Arms.bow(3, 0), 16), "a Power level is worth more than Flame");
    }

    @Test
    void aLoadedCrossbowGoesFirst() {
        assertTrue(rate(Arms.crossbow(0, true), 16) > rate(Arms.bow(5, 1), 16));
        // empty: 25 ticks of load and 6 for 9 damage, against the bow's 23 for 8
        assertTrue(rate(Arms.crossbow(0, false), 16) < rate(Arms.bow(0, 0), 16));
        // Quick Charge III: 10 and 6
        assertTrue(rate(Arms.crossbow(3, false), 16) > rate(Arms.bow(0, 0), 16));
    }

    @Test
    void aTridentThrowKeepsYouBusyOnlyForItsDraw() {
        // 8 damage for 13 busy ticks beats a plain bow's 8 for 23, loses to Power V's 19
        assertTrue(rate(Arms.trident(3, 0), 16) > rate(Arms.bow(0, 0), 16));
        assertTrue(rate(Arms.trident(3, 0), 16) < rate(Arms.bow(5, 0), 16));
        // alone, its return counts: longer at range and with less Loyalty
        Arms t1 = Arms.trident(1, 0), t3 = Arms.trident(3, 0);
        assertTrue(RangedWeapons.sustained(t3, 20, 0, 0, false) > RangedWeapons.sustained(t1, 20, 0, 0, false));
        assertTrue(RangedWeapons.sustained(t3, 10, 0, 0, false) > RangedWeapons.sustained(t3, 30, 0, 0, false));
        // Impaling only on the mobs it is for
        assertEquals(8.0 + 12.5, RangedWeapons.hit(Arms.trident(3, 5), 16, 0, 0, true), 1e-9);
        assertEquals(8.0, RangedWeapons.hit(Arms.trident(3, 5), 16, 0, 0, false), 1e-9);
    }

    @Test
    void arrowsSlowWithDistance() {
        assertTrue(RangedWeapons.hit(Arms.bow(0, 0), 5, 0, 0, false) >= RangedWeapons.hit(Arms.bow(0, 0), 60, 0, 0, false));
    }
}
