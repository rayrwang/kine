package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.WorldSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Block states to the table's kinds. A state's kind is made once (the game's collision shape with an empty collision
 * context, the movement copy's BlockInfo from WorldSnapshot, and the walking flags) and then found by the state's
 * global id in an array, without a lock, so the table builder can call it for every block of a chunk on its worker.
 *
 * <p>Shapes are taken at a fixed position: blocks whose shape moves with their position (bamboo, pointed dripstone)
 * come out at one offset. Shapes that depend on who touches them come out as for no one: scaffolding and powder snow
 * are marked to be avoided anyway.
 */
public final class McKinds {
    private McKinds() {}

    private static final Object LOCK = new Object();
    private static volatile int[] byState = new int[0];   // global state id -> kind id + 1, 0 for not yet made

    /** The kind id of a block state (thread safe). */
    public static int id(BlockState st) {
        int sid = Block.getId(st);
        int[] a = byState;
        if (sid >= 0 && sid < a.length && a[sid] != 0) return a[sid] - 1;
        synchronized (LOCK) {
            a = byState;
            if (sid >= 0 && sid < a.length && a[sid] != 0) return a[sid] - 1;
            int id = st.isAir() ? Palette.AIR.id : make(st).id;
            if (sid >= 0) {
                int[] next = a.length > sid ? a.clone() : java.util.Arrays.copyOf(a, Math.max(sid + 1, a.length + 4096));
                next[sid] = id + 1;
                byState = next;
            }
            return id;
        }
    }

    private static Kind make(BlockState st) {
        VoxelShape vs = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        BlockInfo info = WorldSnapshot.info(st, vs);
        List<double[]> boxes = new ArrayList<>();
        for (AABB b : vs.toAabbs()) boxes.add(new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ});
        int flags = 0;
        if (st.getFluidState().is(FluidTags.WATER) || st.is(Blocks.BUBBLE_COLUMN)) flags |= Kind.WATER;
        if (st.getFluidState().is(FluidTags.LAVA) || st.is(Blocks.LAVA)) flags |= Kind.LAVA;
        if (st.is(BlockTags.FIRE) || st.is(Blocks.CACTUS) || st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.WITHER_ROSE)
            || st.is(Blocks.CAMPFIRE) || st.is(Blocks.SOUL_CAMPFIRE) || st.is(Blocks.POWDER_SNOW) || st.is(Blocks.POINTED_DRIPSTONE))
            flags |= Kind.HAZARD;
        if (st.is(Blocks.MAGMA_BLOCK)) flags |= Kind.FLOOR_HAZARD;
        if (st.is(BlockTags.CLIMBABLE) && !st.is(Blocks.SCAFFOLDING)) flags |= Kind.CLIMB;
        if (st.is(Blocks.COBWEB) || st.is(Blocks.SCAFFOLDING) || st.is(Blocks.NETHER_PORTAL) || st.is(Blocks.END_PORTAL)
            || st.is(Blocks.END_GATEWAY) || st.is(Blocks.SLIME_BLOCK) || st.is(Blocks.HONEY_BLOCK) || st.is(Blocks.BUBBLE_COLUMN))
            flags |= Kind.AVOID;
        Block b = st.getBlock();
        if (b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock) flags |= Kind.DOOR;
        if (b.getSpeedFactor() < 1.0F) flags |= Kind.SLOW;
        // what is drawn above the collision: a snow layer is drawn a layer higher than you stand (one layer: no
        // collision at all), soul sand and mud a full block; plants have no collision and stay out, so the rails go
        // under grass and flowers, which really are drawn over them
        float drawnTop = Float.NaN;
        VoxelShape drawn = st.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        if (!drawn.isEmpty() && (st.is(Blocks.SNOW) || !vs.isEmpty())) {
            double top = drawn.max(Direction.Axis.Y);
            if (vs.isEmpty() || top > vs.max(Direction.Axis.Y) + 1e-6) drawnTop = (float) top;
        }
        return Palette.add(st.toString(), info, boxes, flags, drawnTop);
    }
}
