package ai.rrw.kine.combat;

import ai.rrw.kine.combat.BowTargets.Cand;
import ai.rrw.kine.combat.BowTargets.Kind;
import ai.rrw.kine.combat.BowTargets.Policy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The bow aura's target choice (BowTargets): the policies' orders, the viability cut, the hysteresis, and the arrow
 *  damage against the 26.1.2 rules. */
class BowTargetsTest {
    static final double SKEL = 3.5 * 0.7 / 40.0, ZOMBIE = 3.0 / 20.0;

    static Cand skeleton(int id, double dist, double health, double chance) {
        return new Cand(id, Kind.RANGED, dist, health, chance, SKEL, 15.0, true, Double.POSITIVE_INFINITY, 8.0);
    }

    static Cand zombie(int id, double dist, double health, double chance, double arrive) {
        return new Cand(id, Kind.MELEE, dist, health, chance, ZOMBIE, 0.0, true, arrive, 7.9);
    }

    static Cand creeper(int id, double dist, double chance, double arrive) {
        return new Cand(id, Kind.CREEPER, dist, 20.0, chance, 0.0, 0.0, true, arrive, 8.0);
    }

    @Test
    void nearestTakesTheNearest() {
        List<Cand> cs = List.of(skeleton(1, 20, 20, 0.9), zombie(2, 12, 20, 0.9, 80), creeper(3, 16, 0.9, 100));
        assertEquals(1, BowTargets.pick(cs, Policy.NEAREST, -1));
    }

    @Test
    void rangedFirstTakesTheSkeletonOverANearerZombie() {
        List<Cand> cs = List.of(zombie(1, 10, 20, 0.9, 60), skeleton(2, 22, 20, 0.8), skeleton(3, 18, 20, 0.8));
        assertEquals(2, BowTargets.pick(cs, Policy.RANGED_FIRST, -1));
    }

    @Test
    void leastHealthTakesTheWounded() {
        List<Cand> cs = List.of(skeleton(1, 15, 20, 0.9), skeleton(2, 25, 6, 0.7), zombie(3, 12, 20, 0.9, 50));
        assertEquals(1, BowTargets.pick(cs, Policy.LEAST_HEALTH, -1));
    }

    @Test
    void threatPutsAClosingCreeperFirst() {
        // a creeper 40 ticks out against a skeleton shooting from in range and a zombie further off
        List<Cand> cs = List.of(skeleton(1, 14, 20, 0.9), creeper(2, 9, 0.9, 40), zombie(3, 20, 20, 0.9, 140));
        assertEquals(1, BowTargets.pick(cs, Policy.THREAT, -1));
        assertEquals(1, BowTargets.pick(cs, Policy.MOST_DAMAGE, -1));
    }

    @Test
    void threatPrefersTheSkeletonInRangeOverAFarZombie() {
        List<Cand> cs = List.of(zombie(1, 20, 20, 0.9, 150), skeleton(2, 14, 20, 0.9));
        assertEquals(1, BowTargets.pick(cs, Policy.THREAT, -1));
    }

    @Test
    void threatWeighsTheHitChance() {
        // two skeletons alike but for the chance: the surer shot first
        List<Cand> cs = List.of(skeleton(1, 14, 20, 0.45), skeleton(2, 14, 20, 0.95));
        assertEquals(1, BowTargets.pick(cs, Policy.THREAT, -1));
    }

    @Test
    void sharedThreatPutsRangedMobsBeforeMeleeOnesTheKillAuraWillMeet() {
        // a skeleton out of its range (it comes in) against a zombie 60 ticks out: the bow takes the skeleton, the kill
        // aura will have the zombie
        List<Cand> cs = List.of(zombie(1, 10, 20, 0.9, 60), skeleton(2, 24, 20, 0.8));
        assertEquals(1, BowTargets.pick(cs, Policy.SHARED_THREAT, -1));
        assertEquals(0, BowTargets.pick(cs, Policy.THREAT, -1));
        // a creeper about to arrive still goes first
        List<Cand> ds = List.of(skeleton(1, 24, 20, 0.8), creeper(2, 5, 0.9, 5));
        assertEquals(1, BowTargets.pick(ds, Policy.SHARED_THREAT, -1));
    }

    @Test
    void unviableMobsAreNeverPicked() {
        List<Cand> cs = List.of(skeleton(1, 5, 20, 0.1), zombie(2, 8, 20, 0.29, 10));
        for (Policy p : Policy.values()) assertEquals(-1, BowTargets.pick(cs, p, -1), p.name());
    }

    @Test
    void theMobAimedAtIsKeptUnlessAnotherIsClearlyBetter() {
        List<Cand> cs = List.of(skeleton(1, 14, 20, 0.80), skeleton(2, 14, 20, 0.90));
        assertEquals(0, BowTargets.pick(cs, Policy.THREAT, 1));   // 0.90 is not 1.3 times 0.80
        List<Cand> ds = List.of(skeleton(1, 14, 20, 0.50), skeleton(2, 14, 20, 0.90));
        assertEquals(1, BowTargets.pick(ds, Policy.THREAT, 1));
        // nearest: scores are negative distances; kept unless the other is nearer by more than 30% of its distance
        List<Cand> es = List.of(skeleton(1, 20, 20, 0.9), skeleton(2, 17, 20, 0.9));
        assertEquals(0, BowTargets.pick(es, Policy.NEAREST, 1));
        List<Cand> fs = List.of(skeleton(1, 20, 20, 0.9), skeleton(2, 10, 20, 0.9));
        assertEquals(1, BowTargets.pick(fs, Policy.NEAREST, 1));
    }

    @Test
    void arrowDamageFollowsTheGame() {
        // a full-power arrow still at about 3 blocks a tick: ceil(3 * 2) = 6, crit adds nextInt(5), mean 2
        assertEquals(8.0, BowTargets.arrowDamage(3.0, 0, 0, 0), 1e-9);
        // slowed to 2.4: ceil(4.8) = 5, crit nextInt(4) mean 1.5
        assertEquals(6.5, BowTargets.arrowDamage(2.4, 0, 0, 0), 1e-9);
        // Power V: base 2 + 3 = 5; ceil(15) = 15, crit nextInt(9) mean 4
        assertEquals(19.0, BowTargets.arrowDamage(3.0, 5, 0, 0), 1e-9);
        // a zombie's 2 armour: max(0.4, 2 - 8 / 2) = 0.4 armour points of cut, 1.6%
        assertEquals(8.0 * (1 - 0.4 / 25.0), BowTargets.arrowDamage(3.0, 0, 2, 0), 1e-9);
        assertEquals(3.0, BowTargets.arrows(skeleton(1, 10, 20, 0.9)));
        assertEquals(1.0, BowTargets.arrows(skeleton(1, 10, 6, 0.9)));
    }

    @Test
    void policiesParse() {
        assertEquals(Policy.NEAREST, Policy.parse("nearest", Policy.THREAT));
        assertEquals(Policy.RANGED_FIRST, Policy.parse("ranged_first", Policy.THREAT));
        assertEquals(Policy.THREAT, Policy.parse(null, Policy.THREAT));
        assertEquals(Policy.THREAT, Policy.parse("nonsense", Policy.THREAT));
    }
}
