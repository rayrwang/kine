package ai.rrw.kine.guard;

import ai.rrw.kine.autoflight.TakeoffModel;
import ai.rrw.kine.compat.Rules;
import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;

/**
 * One tick of gliding in the walk's movement copy ({@link GroundPhysics}: LivingEntity.travelFallFlying line by line,
 * real block shapes), with the game's damage rules for a glide, each from the 26.1.2 code:
 * Each errs a little toward damage ({@link #WALL_MARGIN}, {@link #FALL_MARGIN}: the game dealt a little more than the
 * copy in a recorded test).
 * <ul>
 *   <li>a wall: LivingEntity.handleFallFlyingCollisions, after a move that collided horizontally while gliding, deals
 *       the horizontal speed lost times 10, less 3 (blocks a tick);</li>
 *   <li>a landing: Entity.checkFallDamage hands the fall distance to the block's fallOn, LivingEntity.causeFallDamage
 *       deals floor(fall + 1e-6 - safe fall distance) times the block's factor (hay and honey 0.2, a bed taken as 0.5,
 *       slime and powder snow 0; up to 1.21.4 rounded up, {@link #fallDamage}); the fall distance is counted as the copy counts it: the drop of each tick out of
 *       water, cut to 1 while gliding and sinking slower than 0.5 a tick (checkFallDistanceAccumulation), cleared in
 *       water;</li>
 *   <li>blocks that hurt on touch or underfoot (lava, fire, magma, cactus, berry bushes, campfires, wither roses,
 *       powder snow, pointed dripstone): a point a tick touched;</li>
 *   <li>the void, 64 below the world's bottom.</li>
 * </ul>
 * A rocket's push (FireworkRocketEntity on a gliding player) can be added after the move, as {@link TakeoffModel#boost}.
 * Not here (each errs toward damage): the ray that clears the fall distance of a move of a block or more through
 * climbable blocks, cobwebs or berry bushes, a cobweb's stop, a slime's bounce, armour and enchantments.
 */
public final class GlideSim {
    public final GroundPhysics phys;
    private final int minY;
    private final double safeFall;

    /** Damage kinds, for the report. */
    public static final int WALL = 1, FALL = 2, HAZARD = 4, VOID = 8;

    /** Margins toward damage, from the damage recorder (clienttest DamageTest, 2026-10-14): against the copy's numbers the
     *  game dealt more in every case that differed: four wall hits 0.02 to 0.39 more (7.08 copy, 7.47 game; 11.61, 11.66;
     *  16.34, 16.40; 2.50, 2.52), one of three hard landings a point more (94, 95; 96, 96; hay 11, 11). So a wall hit
     *  counts from 0.5 below the game's threshold, and a fall half a block longer. */
    public static final double WALL_MARGIN = 0.5, FALL_MARGIN = 0.5;

    /** What the last step did: the damage and its kinds. */
    public double lastDamage;
    public int lastKinds;

    public GlideSim(BlockGrid grid, Body body, int minY, double safeFall) {
        body.glide = true;
        this.phys = new GroundPhysics(grid, body);
        this.minY = minY;
        this.safeFall = safeFall;
    }

    public BlockGrid grid() { return phys.grid(); }

    /** LivingEntity.calculateFallDamage for a fall onto a block of this factor (FALL_DAMAGE_MULTIPLIER 1):
     *  floor(fall + 1e-6 - safe fall) times the factor; up to 1.21.4 ceil(fall - safe fall) times the factor, in float
     *  (Rules.FALL_DAMAGE_ROUNDS_UP). */
    public static double fallDamage(double fall, double safeFall, float factor) {
        if (Rules.FALL_DAMAGE_ROUNDS_UP) return Math.ceil(((float) fall - (float) safeFall) * factor * 1.0);
        return Math.floor((fall + 1.0E-6 - safeFall) * factor);
    }

    /** One tick with this look and no keys, then a rocket's push if `boost`; returns this tick's damage (0 for none). */
    public double step(MoveState s, float yaw, float pitch, boolean boost) {
        boolean flying = s.fallFlying;
        double lastSpeed = Math.sqrt(s.vx * s.vx + s.vz * s.vz);
        s.touched = 0;
        s.landedFall = 0.0;
        phys.step(s, 0, yaw, pitch);
        double dmg = 0.0;
        int kinds = 0;
        if (flying && s.fallFlying && !s.inWater && s.horizontalCollision) {
            double wall = (lastSpeed - Math.sqrt(s.vx * s.vx + s.vz * s.vz)) * 10.0 - 3.0 + WALL_MARGIN;
            if (wall > 0.0) { dmg += wall; kinds |= WALL; }
        }
        if (s.landedFall > 0.0) {
            double fall = fallDamage(s.landedFall + FALL_MARGIN, safeFall, s.landedFallFactor);
            if (fall > 0.0) { dmg += fall; kinds |= FALL; }
        }
        // the copy marks a damaging wall hit as a hazard too (GroundPhysics.travelFallFlying): counted once, as the wall
        if ((s.touched & BlockInfo.HAZARD) != 0 && (kinds & WALL) == 0) { dmg += 1.0; kinds |= HAZARD; }
        if (s.y < minY - 64) { dmg += 4.0; kinds |= VOID; }
        if (boost && s.fallFlying) {
            double[] v = { s.vx, s.vy, s.vz };
            TakeoffModel.boost(yaw, pitch, v);
            s.vx = v[0]; s.vy = v[1]; s.vz = v[2];
        }
        lastDamage = dmg;
        lastKinds = kinds;
        return dmg;
    }
}
