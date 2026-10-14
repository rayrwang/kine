package ai.rrw.kine.combat;

import ai.rrw.kine.Settings;
import ai.rrw.kine.util.GameModes;
import ai.rrw.kine.walk.Walk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps you from walking off a drop of more than three blocks by sneaking at the edge, so the game's own
 * sneak edge stop (Player.maybeBackOffFromEdge) holds you and the server sees an ordinary sneak. It presses
 * sneak only in a tick whose step would leave you over such a drop, and lets go once you turn away. Jumping
 * gets past it, as before: it never sneaks while you jump or are off the ground. Any drop of more than 3
 * blocks counts, water and hay included, except a shaft you can climb down: a ladder, vine or other climbable
 * block in the column you step into catches you (LivingEntity.onClimbable). The walking pathfinder's own checked steps
 * pass (Walk.ownStepSafe): with layer 2 any step its plan checked lands without hurting, with layer 1's follower its
 * step into water, whose drop it measures to the water where this one measures to the ground under it.
 */
public final class FallPrevention {
    private FallPrevention() {}

    private static final double DROP   = 3.0;   // drops of more than this are dangerous
    private static final double MARGIN = 1.5;   // look this much further than the predicted step, to sneak in time

    /** Press sneak when this tick's step, with these keys, would go over a dangerous drop. */
    public static Input adjustInput(LocalPlayer p, Input keys) {
        if (!Settings.fallPrevention || keys.shift() || keys.jump()) return keys;
        if (Walk.ownStepSafe(p)) return keys;   // the walk's own checked step (RW)
        if (!GameModes.survivalLike(Minecraft.getInstance())) return keys;   // creative and spectator take no fall damage
        if (!p.onGround() || p.getAbilities().flying || p.isFallFlying() || p.isPassenger() || p.isInWater()) return keys;
        Vec3 step = predictedStep(p, keys);
        if (step.horizontalDistanceSqr() < 1.0e-8) return keys;
        boolean over = drop(p, step.x, step.z) || drop(p, step.x * MARGIN, step.z * MARGIN);
        if (!over) return keys;
        return new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), true, keys.sprint());
    }

    /** This tick's horizontal step on the ground with these keys, before any edge stop: last tick's velocity
     *  plus the push from the keys (LivingEntity.travel: moveRelative with getFrictionInfluencedSpeed, and
     *  Entity.getInputVector), ignoring the slowdowns from sneaking or using an item, so it errs long. */
    static Vec3 predictedStep(LocalPlayer p, Input keys) {
        float left = impulse(keys.left(), keys.right()), fwd = impulse(keys.forward(), keys.backward());
        double len = Math.sqrt(left * left + fwd * fwd);
        Vec3 v = p.getDeltaMovement();
        if (len < 1.0e-7) return new Vec3(v.x, 0, v.z);
        float friction = p.level().getBlockState(p.getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction();
        double speed = p.getSpeed() * (0.21600002F / (friction * friction * friction));
        double x = left / len * speed, z = fwd / len * speed;
        float sin = Mth.sin(p.getYRot() * (float) (Math.PI / 180.0)), cos = Mth.cos(p.getYRot() * (float) (Math.PI / 180.0));
        return new Vec3(v.x + x * cos - z * sin, 0, v.z + z * cos + x * sin);
    }

    // A dangerous drop under the box moved by (dx, dz): nothing to stand on within DROP below, and no climbable
    // block right under its centre to catch the fall.
    private static boolean drop(LocalPlayer p, double dx, double dz) {
        return canFallAtLeast(p, dx, dz, DROP) && !climbableAtFeet(p, p.getX() + dx, p.getZ() + dz);
    }

    // A ladder, vine or other climbable block in the column at (x, z), at the feet or the block below: the feet are
    // in it within the first tick of falling, which holds the player there. Deeper, a step off an open ledge could
    // drift out of its column first.
    private static boolean climbableAtFeet(LocalPlayer p, double x, double z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bx = Mth.floor(x), by = Mth.floor(p.getY()), bz = Mth.floor(z);
        return p.level().getBlockState(pos.set(bx, by, bz)).is(BlockTags.CLIMBABLE)
            || p.level().getBlockState(pos.set(bx, by - 1, bz)).is(BlockTags.CLIMBABLE);
    }

    // Player.canFallAtLeast: nothing to stand on within minHeight below the box moved by (dx, dz)
    static boolean canFallAtLeast(LocalPlayer p, double dx, double dz, double minHeight) {
        AABB b = p.getBoundingBox();
        return p.level().noCollision(p, new AABB(
            b.minX + 1.0E-7 + dx, b.minY - minHeight - 1.0E-7, b.minZ + 1.0E-7 + dz,
            b.maxX - 1.0E-7 + dx, b.minY, b.maxZ - 1.0E-7 + dz));
    }

    private static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
    }
}
