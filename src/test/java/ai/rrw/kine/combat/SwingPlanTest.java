package ai.rrw.kine.combat;

import ai.rrw.kine.combat.SwingPlan.Mob;
import ai.rrw.kine.combat.SwingPlan.Swing;
import ai.rrw.kine.combat.SwingPlan.Weapon;
import ai.rrw.kine.compat.Input;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The kill aura's swing plan against the 26.1.2 melee rules, and the dynamic program's optimum
 *  (~/_/kine_killaura_2026-10-11/sim/out/dp_still.txt). */
class SwingPlanTest {
    static final Weapon SWORD = new Weapon(8, 12.5F, true, 0.0);              // netherite sword
    static final Weapon AXE = new Weapon(10, 20.0F, false, 0.0);              // netherite axe
    static final Weapon SWORD_SE3 = new Weapon(8, 12.5F, true, 0.75);         // netherite sword, Sweeping Edge III

    // the dynamic program's best damage a tick for 1 to 9 still mobs (no armour, no sweep, endless health)
    static final double[] DP_SWORD = {0.6667, 0.7456, 0.8132, 0.8980, 1.0099, 1.1300, 1.2582, 1.3947, 1.5393};
    static final double[] DP_AXE = {0.5055, 0.5850, 0.7535, 0.9260, 1.1025, 1.2830, 1.4675, 1.6560, 1.8485};

    static List<Mob> still(int n, double health, double armour) {
        List<Mob> ms = new ArrayList<>();
        for (int i = 0; i < n; i++) ms.add(new Mob(health, armour));
        return ms;
    }

    /** A tick simulation on the game's rules (LivingEntity.hurtServer windows, the charge), driven by the plan;
     *  returns the damage a tick over `ticks` after `warm`. No sweeps (the mobs have no neighbours). */
    static double simulate(Weapon w, int n, int ticks, int warm) {
        List<Mob> ms = still(n, 1e9, 0.0);
        int[] inv = new int[n];
        double[] last = new double[n];
        int k = 1000;
        double dealt = 0;
        for (int t = 0; t < warm + ticks; t++) {
            for (int i = 0; i < n; i++) {
                Mob m = ms.get(i);
                m.freshIn = Math.max(0, inv[i] - SwingPlan.WINDOW);
                m.lastHurt = last[i];
            }
            Swing s = SwingPlan.decide(w, k, false, false, ms);
            if (s.hit() >= 0) {
                int i = s.hit();
                double d = SwingPlan.hitDamage(w, 0, SwingPlan.charge(k, w.delay()), false);
                double through;
                if (inv[i] > SwingPlan.WINDOW) { through = Math.max(0, d - last[i]); if (d > last[i]) last[i] = d; }
                else { through = d; last[i] = d; inv[i] = 20; }
                if (t >= warm) dealt += through;
                k = 0;
            }
            for (int i = 0; i < n; i++) if (inv[i] > 0) inv[i]--;   // the mobs tick
            k++;                                                     // the player ticks
        }
        return dealt / ticks;
    }

    @Test
    void theBestCycleIsTheDynamicProgramsOptimum() {
        for (int n = 1; n <= 9; n++) {
            assertEquals(DP_SWORD[n - 1], SwingPlan.bestCycle(SWORD, new Mob(1e9, 0), n).rate(), 5e-4, "sword, " + n + " mobs");
            assertEquals(DP_AXE[n - 1], SwingPlan.bestCycle(AXE, new Mob(1e9, 0), n).rate(), 5e-4, "axe, " + n + " mobs");
        }
        // ten or more: a quick hit every tick, 0.2 + 0.8 * 0.12^2 of the damage
        assertEquals(8 * (0.2 + 0.8 * 0.12 * 0.12), SwingPlan.bestCycle(SWORD, new Mob(1e9, 0), 15).rate(), 1e-4);
    }

    @Test
    void playingThePlanTickByTickGetsThatRate() {
        for (int n = 1; n <= 9; n++) {
            assertEquals(DP_SWORD[n - 1], simulate(SWORD, n, 3000, 300), 0.02 * DP_SWORD[n - 1], "sword, " + n + " mobs");
            assertEquals(DP_AXE[n - 1], simulate(AXE, n, 3000, 300), 0.02 * DP_AXE[n - 1], "axe, " + n + " mobs");
        }
        assertEquals(8 * (0.2 + 0.8 * 0.12 * 0.12), simulate(SWORD, 14, 3000, 300), 1e-3, "a big crowd: every tick");
    }

    @Test
    void aSwordIsFullyChargedAfterTwelveTicksNotThirteen() {
        // the game's sword: attack speed 4 + (double) -2.4F = 1.5999999046, delay (float) (1 / speed * 20) = 12.500001
        float delay = (float) (1.0 / (4.0 + (double) -2.4F) * 20.0);
        assertTrue(delay > 12.5F);
        assertTrue(SwingPlan.charge(12, delay) < 1.0F, "a full 1.0 only after 13 ticks");
        assertEquals(12, new Weapon(8, delay, true, 0).fullTicks());
        assertEquals(0, SwingPlan.decide(new Weapon(8, delay, true, 0), 12, true, false, still(1, 1e9, 0)).hit());
    }

    @Test
    void oneMobWaitsForTheBestCharge() {
        List<Mob> one = still(1, 1e9, 0);
        // fresh with little charge built up, a hit now costs less charge than it is worth (the next full hit comes at
        // most 3 ticks later than it would): from 4 ticks on it waits for the full charge
        for (int k = 1; k <= 3; k++) assertEquals(0, SwingPlan.decide(SWORD, k, true, false, one).hit(), "sword at " + k);
        for (int k = 4; k < 12; k++) assertEquals(-1, SwingPlan.decide(SWORD, k, true, false, one).hit(), "sword at " + k);
        assertEquals(0, SwingPlan.decide(SWORD, 12, true, false, one).hit());
        // an axe does best a tick early: 9.6 in 19 ticks beats 10 in 20
        assertEquals(-1, SwingPlan.decide(AXE, 18, true, false, one).hit());
        assertEquals(0, SwingPlan.decide(AXE, 19, true, false, one).hit());
    }

    @Test
    void twoMobsTakeAQuickHitRightAfterAFullOne() {
        List<Mob> two = still(2, 1e9, 0);
        two.get(0).freshIn = 9;   // just hit at full charge
        two.get(0).lastHurt = 8;
        assertEquals(1, SwingPlan.decide(SWORD, 1, true, false, two).hit(), "the other mob, at once");
        two.get(1).freshIn = 10; two.get(1).lastHurt = 1.69;
        two.get(0).freshIn = 0;   // fresh again 10 ticks later, charge 9 ticks: wait for the full charge
        assertEquals(-1, SwingPlan.decide(SWORD, 9, true, false, two).hit());
        assertEquals(0, SwingPlan.decide(SWORD, 12, true, false, two).hit());
    }

    @Test
    void aQuickHitInsideTheWindowThatIsNotBiggerDoesNothing() {
        List<Mob> ms = still(1, 1e9, 0);
        ms.get(0).freshIn = 5;
        ms.get(0).lastHurt = 8;
        assertEquals(0.0, SwingPlan.value(SWORD, 3, true, false, ms, 0), 1e-12);
        ms.get(0).lastHurt = 1.0;   // a small sweep's damage: the quick hit adds only its excess
        double d = SwingPlan.hitDamage(SWORD, 0, SwingPlan.charge(1, 12.5F), false);
        assertEquals(d - 1.0, SwingPlan.value(SWORD, 1, true, false, ms, 0), 1e-9);
    }

    @Test
    void threatsComeFirstWhateverTheCharge() {
        List<Mob> ms = still(3, 20, 2);
        ms.get(2).contact = 2.0;   // its attack reaches you in 2 ticks
        assertEquals(2, SwingPlan.decide(SWORD, 3, true, false, ms).hit());
        ms.get(2).contact = 5.0;   // further off: no hurry, but the aim goes there
        Swing s = SwingPlan.decide(SWORD, 3, true, false, ms);
        assertEquals(-1, s.hit());
        assertEquals(2, s.aim());
    }

    @Test
    void aMobOutOfThisTicksTurnIsAimedAtNotHit() {
        List<Mob> one = still(1, 20, 2);
        one.get(0).reachable = false;
        Swing s = SwingPlan.decide(SWORD, 20, true, false, one);
        assertEquals(-1, s.hit());
        assertEquals(0, s.aim());
    }

    @Test
    void aHitIsWorthNoMoreThanTheHealthLeft() {
        List<Mob> ms = still(1, 1.0, 0);
        assertEquals(1.0, SwingPlan.value(SWORD, 12, true, false, ms, 0), 1e-12);
    }

    @Test
    void aPackedCrowdWithSweepingEdgeIsSweptAtFullCharge() {
        // five mobs, the middle one with the four others in its sweep box: full-charge sweeps on it beat the cycle
        List<Mob> ms = still(5, 1e9, 0);
        ms.get(2).swept = new int[]{0, 1, 3, 4};
        ms.get(0).swept = new int[]{1, 2};
        for (int k = 1; k < 12; k++) assertEquals(-1, SwingPlan.decide(SWORD_SE3, k, true, false, ms).hit(), "k " + k);
        Swing s = SwingPlan.decide(SWORD_SE3, 12, true, false, ms);
        assertEquals(2, s.hit(), "the hub");
        // its value: 8 on it and (1 + 0.75 * 8) on each of the four
        assertEquals(8 + 4 * 7.0, SwingPlan.value(SWORD_SE3, 12, true, false, ms, 2), 1e-9);
        // off the ground (no sweep) the same crowd is a burst cycle again: a quick hit at once
        assertTrue(SwingPlan.decide(SWORD_SE3, 1, false, false, ms).hit() >= 0);
    }

    @Test
    void aCritIsWorthHalfAgainAndStopsTheSweep() {
        List<Mob> ms = still(2, 1e9, 0);
        ms.get(0).swept = new int[]{1};
        assertEquals(12.0, SwingPlan.value(SWORD_SE3, 12, true, true, ms, 0), 1e-9);
        assertEquals(8.0 + 7.0, SwingPlan.value(SWORD_SE3, 12, true, false, ms, 0), 1e-9);
    }

    @Test
    void armourAsTheGameTakesIt() {
        // a zombie's 2 armour: at least a fifth of it counts, 0.4 / 25 off a big hit
        assertEquals(8 * (1 - 0.4 / 25), SwingPlan.afterArmour(8, 2, 0), 1e-12);
        // and more off a small one: 2 - 1.6 / 2 = 1.2 armour counts
        assertEquals(1.6 * (1 - 1.2 / 25), SwingPlan.afterArmour(1.6, 2, 0), 1e-12);
    }

    @Test
    void weaponScoresPreferAnAxeInACrowdAndASwordAlone() {
        List<Mob> one = still(1, 20, 2), crowd = still(12, 20, 2);
        assertTrue(SwingPlan.rate(SWORD, one, true) > SwingPlan.rate(AXE, one, true), "one zombie: the sword");
        assertTrue(SwingPlan.rate(AXE, crowd, true) > SwingPlan.rate(SWORD, crowd, true), "a crowd, no sweeps: the axe's quick hits are bigger");
        assertEquals(3.0, WeaponSelect.bonus(5, 0, 0), 1e-12);    // Sharpness V
        assertEquals(12.5, WeaponSelect.bonus(0, 5, 0), 1e-12);   // Smite V on the undead
    }

    @Test
    void walkingKeysFollowTheViewWhileTheRotationSentIsTurned() {
        Input w = new Input(true, false, false, false, false, false, true);
        assertEquals(w, KillAura.walkKeys(w, 0.0));
        // the rotation sent 90 degrees round from the view (yaw up): walking where the view faces is "left" for it
        Input left = KillAura.walkKeys(w, 90.0);
        assertTrue(left.left() && !left.forward() && !left.backward() && !left.right());
        assertTrue(left.sprint(), "the other keys pass through");
        // 180 round: backwards
        Input back = KillAura.walkKeys(w, 180.0);
        assertTrue(back.backward() && !back.forward() && !back.left() && !back.right());
        // 40 round: the nearest of the eight directions, forward and left
        Input diag = KillAura.walkKeys(w, 40.0);
        assertTrue(diag.forward() && diag.left() && !diag.right());
        // no keys, no change
        Input none = new Input(false, false, false, false, true, false, false);
        assertEquals(none, KillAura.walkKeys(none, 77.0));
    }

    static List<Mob> atYaws(double... yaws) {
        List<Mob> ms = new ArrayList<>();
        for (double y : yaws) { Mob m = new Mob(1e9, 0); m.yaw = y; ms.add(m); }
        return ms;
    }

    @Test
    void withTheTurnLimitTheCycleAllowsForTheTurnsBetweenMobs() {
        List<Mob> ring = atYaws(0, 72, 144, -144, -72);   // five mobs all round
        assertEquals(3, SwingPlan.quickGap(ring, 29.5), "72 degrees apart: 3 ticks of turning each");
        assertEquals(1, SwingPlan.quickGap(ring, Double.POSITIVE_INFINITY));
        assertEquals(288.0, SwingPlan.spread(ring), 1e-9);
        // quick swings 3 ticks apart are worth little more than waiting for full charges
        double h3 = SwingPlan.typicalHit(SWORD, ring.get(0), 3);
        SwingPlan.Cycle c = SwingPlan.bestCycle(SWORD, ring.get(0), 5, 3);
        assertTrue(c.rate() < 0.75 && c.rate() >= 8.0 / 12, "rate " + c.rate());
        assertTrue(c.rate() >= (4 * h3 + h3) / 15 - 1e-9);
    }

    @Test
    void aCrowdOnOneSideIsToured() {
        // five mobs across 90 degrees in front, none fresh until the charged swing is due: wait at the near end
        List<Mob> front = atYaws(-45, -20, 0, 20, 40);
        for (Mob m : front) { m.freshIn = 2; m.lastHurt = 8; m.reachable = false; }
        assertEquals(85.0, SwingPlan.spread(front), 1e-9);
        Swing s = SwingPlan.decide(SWORD, 4, false, false, -1, front, 29.5);
        assertEquals(-1, s.hit());
        assertEquals(4, s.aim(), "the end at 40 degrees is nearer than the one at -45");
        // fresh and within this tick's turn, a burst goes to the nearest next
        for (Mob m : front) { m.freshIn = 0; m.reachable = Math.abs(m.yaw) <= 29.5; }
        assertEquals(2, SwingPlan.decide(SWORD, 1, false, false, -1, front, 29.5).hit());
    }

    @Test
    void inAJumpTheFullHitWaitsForTheCritWhenItIsWorthIt() {
        List<Mob> one = still(1, 1e9, 0);
        // full charge, the fall two ticks away: 12 then beats 8 now plus two ticks of the rate
        assertEquals(-1, SwingPlan.decide(SWORD, 12, false, false, 2, one, Double.POSITIVE_INFINITY).hit());
        assertEquals(0, SwingPlan.decide(SWORD, 12, false, true, -1, one, Double.POSITIVE_INFINITY).hit(), "falling: now");
        // six ticks away it isn't
        assertEquals(0, SwingPlan.decide(SWORD, 12, false, false, 6, one, Double.POSITIVE_INFINITY).hit());
        // the crit window: a jump (0.42 up) is first seen falling after its sixth move, so from the seventh tick
        assertEquals(-1, KillAura.critIn(0.42));
        double vy = 0.42;
        for (int t = 0; t < 4; t++) vy = (vy - 0.08) * 0.98;
        assertEquals(3, KillAura.critIn(vy));
    }
}
