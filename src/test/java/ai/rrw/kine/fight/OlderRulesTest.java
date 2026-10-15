package ai.rrw.kine.fight;

import ai.rrw.kine.autoflight.HoldModel;
import ai.rrw.kine.combat.HitChance;
import ai.rrw.kine.compat.UnderRelease;
import ai.rrw.kine.move.BlockGrid;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The combat copies on releases whose rules differ from 26.1.2's (compat/Rules), each case run on the development
 * release and under an older one (UnderRelease), with what each release's code gives worked out by hand.
 */
public class OlderRulesTest {
    /** A fireball at 0.1 a tick speeding up by 0.1 with inertia 0.95, one tick in open air: where it is and how fast. */
    public static final class FireballTick implements Supplier<double[]> {
        public double[] get() {
            ArrowSim a = new ArrowSim();
            a.x = 0; a.y = 10; a.z = 0; a.vx = 0.1;
            a.gravity = 0; a.drag = 1; a.accel = 0.1; a.inertia = 0.95;
            double[] far = {100, 100, 100, 101, 102, 101};   // the player's box, out of the way
            a.tick(far, new BlockGrid(-16, -16, -16, 64, 64, 64));
            return new double[]{a.x, a.vx};
        }
    }

    /** Rules.PROJECTILES_MOVE_FIRST: up to 1.21.1 a fireball moves on the speed it has and speeds up after. */
    @Test
    void aFireballSpeedsUpBeforeItsMoveFrom1212() {
        double[] now = new FireballTick().get(), old = UnderRelease.get("1.21.1", FireballTick.class);
        assertEquals(0.19, now[0], 1e-12);
        assertEquals(0.1, old[0], 1e-12);
        assertEquals(0.19, now[1], 1e-12);
        assertEquals(0.19, old[1], 1e-12);
    }

    /** The sweep's movement test for a player that moved 0.2 in its last tick, at speed 0.1. */
    public static final class SweepAfterAWalk implements Supplier<Boolean> {
        public Boolean get() {
            return AuraSim.slowEnoughToSweep(0.2, 0.0, 0.1);
        }
    }

    /** The same after a sprint's move, 0.3 a tick. */
    public static final class SweepAfterASprint implements Supplier<Boolean> {
        public Boolean get() {
            return AuraSim.slowEnoughToSweep(0.3, 0.0, 0.1);
        }
    }

    /** Rules.SWEEP_BY_WALK_DIST: from 1.21.2 a walking player can sweep (under 2.5 times its speed) and a sprinting one's
     *  move is too long; up to 1.21.1 the server compares walkDist with the walkDistO its own player tick set, after
     *  the moves it had, so a hit sent before the tick's move finds none since: any move passes (the sprint itself is
     *  another test of Player.attack's). */
    @Test
    void theServerFindsNoMoveSinceItsTickUpTo1211() {
        assertTrue(new SweepAfterAWalk().get());
        assertTrue(UnderRelease.get("1.21.2", SweepAfterAWalk.class));
        assertTrue(UnderRelease.get("1.21.1", SweepAfterAWalk.class));
        assertFalse(new SweepAfterASprint().get());
        assertFalse(UnderRelease.get("1.21.2", SweepAfterASprint.class));
        assertTrue(UnderRelease.get("1.21.1", SweepAfterASprint.class));
    }

    /** A breeze about to shoot (2 ticks of charge left, its charge not yet started), the player 1.5 blocks off for 5
     *  ticks, then 3: the tick it shoots. */
    public static final class BreezeUpClose implements Supplier<Integer> {
        public Integer get() {
            MobSim b = new MobSim();
            b.kind = MobSim.Kind.BREEZE;
            b.shootIn = 2;
            b.walking = false;
            for (int t = 0; t < 20; t++) if (b.thinkBreeze(t < 5 ? 1.5 : 3.0, 0.0, 0.0)) return t;
            return -1;
        }
    }

    /** Rules.BREEZE_NO_SHOT_WITHIN_2: up to 1.21.1 the breeze's shot starts only beyond 2 blocks. */
    @Test
    void aBreezeWaitsForRoomUpTo1211() {
        assertEquals(2, new BreezeUpClose().get());
        assertEquals(7, UnderRelease.get("1.21.1", BreezeUpClose.class));
        assertEquals(2, UnderRelease.get("1.21.2", BreezeUpClose.class));
    }

    /** The hit margin of a projectile on its first tick. */
    public static final class FirstTickMargin implements Supplier<Double> {
        public Double get() {
            return HitChance.margin(1);
        }
    }

    /** Rules.FIXED_HIT_MARGIN: up to 1.21.5 the boxes grow by 0.3 from a projectile's first tick. */
    @Test
    void theHitMarginGrowsWithAgeFrom1216() {
        assertEquals(0.0, new FirstTickMargin().get());
        assertEquals((double) 0.3F, UnderRelease.get("1.21.5", FirstTickMargin.class));
        assertEquals(0.0, UnderRelease.get("1.21.6", FirstTickMargin.class));
    }

    /** The hold readout's countdown at midnight in clear weather. */
    public static final class MidnightToDawn implements Supplier<Integer> {
        public Integer get() {
            return HoldModel.ticksToDawn(18000, 0f);
        }
    }

    /** Rules.SKY_DARKEN_FROM_SUN_ANGLE: up to 1.21.10 the sky's darkening falls to 7 at 22813, from 1.21.11 at 22822;
     *  and up to 1.21.11 the client's darkening is a tick behind the time of day kine reads (Rules.SKY_DARKEN_A_TICK_LATE),
     *  so there it reads 7 from 22814 and 22823 (measured in lockstep client tests, 2026-10-15). */
    @Test
    void dawnComesEarlierUpTo12110() {
        assertEquals(22822 - 18000, new MidnightToDawn().get());
        assertEquals(22822 - 18000, UnderRelease.get("26.1", MidnightToDawn.class));
        assertEquals(22814 - 18000, UnderRelease.get("1.21.10", MidnightToDawn.class));
        assertEquals(22823 - 18000, UnderRelease.get("1.21.11", MidnightToDawn.class));
    }
}
