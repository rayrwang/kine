package ai.rrw.kine.move;

import ai.rrw.kine.compat.UnderRelease;
import ai.rrw.kine.guard.GlideSim;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The movement copy on releases whose rules differ from 26.1.2's (compat/Rules), each case run on the development
 * release and under an older one (UnderRelease), with what each release's code gives worked out by hand.
 */
public class OlderRulesTest {
    static final BlockInfo STONE = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
    static final BlockInfo WATER = new BlockInfo(Shape.EMPTY, 0.6F, 1.0F, 1.0F, BlockInfo.WATER | BlockInfo.FLUID,
        BlockInfo.WATER_FLUID, 8.0F / 9.0F);

    /** A stone floor at y 0 from -8 to 8 on x and -8 to 40 on z. */
    static BlockGrid floor() {
        BlockGrid g = new BlockGrid(-16, -16, -16, 32, 64, 64);
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 40; z++) g.set(x, 0, z, STONE);
        return g;
    }

    static MoveState standing(double x, double y, double z) {
        MoveState s = new MoveState();
        s.x = x; s.y = y; s.z = z;
        s.vy = -0.0784000015258789;
        s.onGround = true;
        s.hasSupport = true; s.supX = (int) Math.floor(x); s.supY = (int) Math.floor(y) - 1; s.supZ = (int) Math.floor(z);
        return s;
    }

    /** A jump from the ground while already rising at 0.5 (pushed up): the speed after the tick. */
    public static final class RisingJump implements Supplier<Double> {
        public Double get() {
            GroundPhysics phys = new GroundPhysics(floor(), new Body());
            MoveState s = standing(0.5, 1.0, 0.5);
            s.vy = 0.5;
            phys.step(s, MoveState.JUMP, 0f);
            return s.vy;
        }
    }

    /** Rules.JUMP_SETS_POWER: up to 1.21.1 the jump sets the rise to its 0.42, from 1.21.2 it keeps a faster one. */
    @Test
    void aJumpKeepsAFasterRiseFrom1212() {
        assertEquals((0.5 - 0.08) * 0.98F, new RisingJump().get(), 1e-12);
        assertEquals((0.5 - 0.08) * 0.98F, UnderRelease.get("1.21.2", RisingJump.class), 1e-12);
        assertEquals(((double) 0.42F - 0.08) * 0.98F, UnderRelease.get("1.21.1", RisingJump.class), 1e-12);
    }

    /** Coasting on the ground with no keys at 0.002 a tick across and 0.01 along: how far it moved on x and z. */
    public static final class SlowCoast implements Supplier<double[]> {
        public double[] get() {
            GroundPhysics phys = new GroundPhysics(floor(), new Body());
            MoveState s = standing(0.5, 1.0, 0.5);
            s.vx = 0.002; s.vz = 0.01;
            phys.step(s, 0, 0f);
            return new double[]{s.x - 0.5, s.z - 0.5};
        }
    }

    /** Rules.PLAYER_SPEED_ZEROED_PER_AXIS: up to 1.21.4 the slow axis alone is zeroed; from 1.21.5 the player's speeds
     *  go to 0 together only when both are small. */
    @Test
    void aSlowAxisIsZeroedOnItsOwnUpTo1214() {
        double[] now = new SlowCoast().get(), old = UnderRelease.get("1.21.4", SlowCoast.class);
        assertEquals(0.002, now[0], 1e-12);
        assertEquals(0.0, old[0], 0.0);
        assertEquals(0.01, now[1], 1e-12);
        assertEquals(0.01, old[1], 1e-12);
        assertEquals(0.002, UnderRelease.get("1.21.5", SlowCoast.class)[0], 1e-12);
    }

    /** A double tap of W in mid-air (W, nothing, W) while falling: sprinting after it? */
    public static final class AirDoubleTap implements Supplier<Boolean> {
        public Boolean get() {
            GroundPhysics phys = new GroundPhysics(new BlockGrid(-16, -16, -16, 32, 64, 32), new Body());
            MoveState s = new MoveState();
            s.x = 0.5; s.y = 30.0; s.z = 0.5;
            phys.step(s, MoveState.FORWARD, 0f);
            phys.step(s, 0, 0f);
            phys.step(s, MoveState.FORWARD, 0f);
            return s.sprinting;
        }
    }

    /** Rules.KEY_IMPULSES: up to 1.21.4 the double tap counts only on the ground or under water. */
    @Test
    void aDoubleTapInTheAirSprintsFrom1215() {
        assertTrue(new AirDoubleTap().get());
        assertTrue(UnderRelease.get("1.21.5", AirDoubleTap.class));
        assertFalse(UnderRelease.get("1.21.4", AirDoubleTap.class));
        assertFalse(UnderRelease.get("1.21", AirDoubleTap.class));
    }

    /** Sprinting along the floor, then two ticks of W with the shift key (the crouch comes a tick late): still
     *  sprinting? */
    public static final class SneakWhileSprinting implements Supplier<Boolean> {
        public Boolean get() {
            GroundPhysics phys = new GroundPhysics(floor(), new Body());
            MoveState s = standing(0.5, 1.0, 0.5);
            s.sprinting = true;
            s.lastKeys = MoveState.FORWARD;
            s.lastMoveForward = 1.0F;
            phys.step(s, MoveState.FORWARD | MoveState.SHIFT, 0f);
            phys.step(s, MoveState.FORWARD | MoveState.SHIFT, 0f);
            return s.sprinting;
        }
    }

    /** Rules.SNEAK_STOPS_SPRINT: only 1.21.4 ends a sprint when the player crouches; before it the cut impulse still
     *  counts as forward, and from 1.21.5 crouching blocks only a new sprint. */
    @Test
    void sneakingEndsASprintOnlyIn1214() {
        assertTrue(new SneakWhileSprinting().get());
        assertTrue(UnderRelease.get("1.21.2", SneakWhileSprinting.class));
        assertFalse(UnderRelease.get("1.21.4", SneakWhileSprinting.class));
        assertTrue(UnderRelease.get("1.21.5", SneakWhileSprinting.class));
    }

    /** Standing on the floor of a water source block (8/9 high, air above): the water's height over the feet and
     *  whether in water, after the tracker's update. */
    public static final class WaterAtTheFeet implements Supplier<double[]> {
        public double[] get() {
            BlockGrid g = new BlockGrid(-16, -16, -16, 32, 64, 32);
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) { g.set(x, 0, z, STONE); g.set(x, 1, z, WATER); }
            GroundPhysics phys = new GroundPhysics(g, new Body());
            MoveState s = standing(0.5, 1.0, 0.5);
            phys.updateFluid(s, true);
            return new double[]{s.waterHeight, s.inWater ? 1 : 0};
        }
    }

    /** Rules.FLUID_HEIGHT_FROM_SHRUNK_BOX: up to 1.21.11 the height is measured from the box's bottom raised by 0.001,
     *  the water's top a float sum. */
    @Test
    void theWaterIsMeasuredFromTheShrunkBoxUpTo12111() {
        double[] now = new WaterAtTheFeet().get(), old = UnderRelease.get("1.21.11", WaterAtTheFeet.class);
        assertEquals(1.0 + 8.0F / 9.0F - 1.0, now[0], 1e-12);
        assertEquals((double) (1 + 8.0F / 9.0F) - 1.001, old[0], 1e-12);
        assertEquals(1.0, now[1]);
        assertEquals(1.0, old[1]);
    }

    /** A pool three deep (water from y 0 to 2 on a stone floor) and a player put on its floor, eyes under, from the
     *  air (its eyes out as the tick starts): the air after each of 4 ticks with no keys. */
    public static final class GoingUnder implements Supplier<double[]> {
        public double[] get() {
            BlockGrid g = new BlockGrid(-16, -16, -16, 32, 64, 32);
            for (int x = -3; x <= 3; x++)
                for (int z = -3; z <= 3; z++) {
                    g.set(x, -1, z, STONE);
                    for (int y = 0; y <= 2; y++) g.set(x, y, z, WATER);
                }
            GroundPhysics phys = new GroundPhysics(g, new Body());
            MoveState s = standing(0.5, 0.0, 0.5);
            double[] air = new double[4];
            for (int i = 0; i < 4; i++) {
                phys.step(s, 0, 0f);
                air[i] = s.air;
            }
            return air;
        }
    }

    /** Rules.CLIENT_TICKS_AIR: up to 1.21.4 the client takes its own point in the tick its eyes go in, then shows the
     *  server's (two ticks behind) less its own point; from 1.21.5 it shows the server's only. */
    @Test
    void theClientTakesItsOwnAirUpTo1214() {
        assertArrayEquals(new double[]{300, 300, 299, 298}, new GoingUnder().get());
        assertArrayEquals(new double[]{300, 300, 299, 298}, UnderRelease.get("1.21.5", GoingUnder.class));
        assertArrayEquals(new double[]{299, 298, 298, 297}, UnderRelease.get("1.21.4", GoingUnder.class));
        assertArrayEquals(new double[]{299, 298, 298, 297}, UnderRelease.get("1.21.1", GoingUnder.class));
    }

    /** A fall from 6 above into a pool three deep (water from y 0 to 2, its top at 2 + 8/9): the first tick the copy has
     *  the player in water at the tick's end. */
    public static final class FallIntoWater implements Supplier<Integer> {
        public Integer get() {
            BlockGrid g = new BlockGrid(-16, -16, -16, 32, 64, 32);
            for (int x = -3; x <= 3; x++)
                for (int z = -3; z <= 3; z++) {
                    g.set(x, -1, z, STONE);
                    for (int y = 0; y <= 2; y++) g.set(x, y, z, WATER);
                }
            GroundPhysics phys = new GroundPhysics(g, new Body());
            MoveState s = new MoveState();
            s.x = 0.5; s.y = 9.0; s.z = 0.5;
            for (int i = 0; i < 40; i++) {
                phys.step(s, 0, 0f);
                if (s.inWater) return i;
            }
            return -1;
        }
    }

    /** Rules.CLIENT_SKIPS_FALL_CHECK: on 1.21.4 the client doesn't look at the water after its own move, so it is in
     *  water a tick later (at the next tick's baseTick). */
    @Test
    void theClientIsInWaterATickLateOn1214() {
        int now = new FallIntoWater().get();
        assertTrue(now > 0);
        assertEquals(now, UnderRelease.get("1.21.5", FallIntoWater.class));
        assertEquals(now, UnderRelease.get("1.21.2", FallIntoWater.class));
        assertEquals(now + 1, UnderRelease.get("1.21.4", FallIntoWater.class));
    }

    /** A 3.5-block fall onto stone (safe fall 3): the damage, and a fall factor of 1. */
    public static final class HalfBlockPastSafe implements Supplier<Double> {
        public Double get() {
            return GlideSim.fallDamage(3.5, 3.0, 1.0F);
        }
    }

    /** Rules.FALL_DAMAGE_ROUNDS_UP: up to 1.21.4 the damage rounds up, so half a block past the safe fall hurts 1. */
    @Test
    void fallDamageRoundsUpTo1214() {
        assertEquals(0.0, new HalfBlockPastSafe().get());
        assertEquals(1.0, UnderRelease.get("1.21.4", HalfBlockPastSafe.class));
        assertEquals(0.0, UnderRelease.get("1.21.5", HalfBlockPastSafe.class));
    }
}
