package ai.rrw.kine.combat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The bow aura's rule for when the melee kill aura goes first (BowAura.meleeFirst, mayLoose). */
class BowAuraMeleeTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();   // BowAura's statics hold items
    }

    @Test
    void aTargetInReachAlwaysGoesToTheMeleeAura() {
        for (int drawn = 0; drawn <= 40; drawn++) assertTrue(BowAura.meleeFirst(0, drawn));
        for (int drawn = 0; drawn <= 40; drawn++) assertTrue(BowAura.meleeFirst(1, drawn));
    }

    @Test
    void noDrawStartsThatAMobWouldCutShort() {
        // idle: a full draw (20) and the release (2) must fit before the mob is in reach
        assertTrue(BowAura.meleeFirst(22, 0));
        assertFalse(BowAura.meleeFirst(23, 0));
        assertFalse(BowAura.meleeFirst(Double.POSITIVE_INFINITY, 0));
    }

    @Test
    void theHorizonShrinksAsTheDrawGoesOn() {
        // 15 ticks drawn: 5 more to full power, 2 for the release
        assertTrue(BowAura.meleeFirst(7, 15));
        assertFalse(BowAura.meleeFirst(8, 15));
        // at full draw: only the release's 2 ticks
        assertTrue(BowAura.meleeFirst(2, 25));
        assertFalse(BowAura.meleeFirst(3, 25));
    }

    @Test
    void aDrawCutShortIsLoosedOnlyWhileTheMobIsStillOutOfReach() {
        assertFalse(BowAura.mayLoose(0));
        assertFalse(BowAura.mayLoose(1));
        assertTrue(BowAura.mayLoose(2));
        assertTrue(BowAura.mayLoose(10));
    }
}
