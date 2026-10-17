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
 * come out at one offset. Shapes that depend on who touches them come out as for no one: powder snow is marked to be
 * avoided anyway; scaffolding is a block whose top is a floor (its shape for no one: the top slab and the legs).
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

    private static final java.util.concurrent.ConcurrentHashMap<Integer, Integer> CLIMBABLE_BY_STATE = new java.util.concurrent.ConcurrentHashMap<>();

    /** The kind id of st made climbable: an open trapdoor over a ladder facing the same way (WorldSnapshot.trapdoorLadder,
     *  as the movement copy has it since 5784759; the walk table had it as a plain trapdoor, so a walk landing on
     *  trapdoor_ladder's trapdoor had no climb up). */
    public static int climbableId(BlockState st) {
        Integer have = CLIMBABLE_BY_STATE.get(Block.getId(st));
        if (have != null) return have;
        synchronized (LOCK) {
            have = CLIMBABLE_BY_STATE.get(Block.getId(st));
            if (have != null) return have;
            int id = make(st, true).id;
            CLIMBABLE_BY_STATE.put(Block.getId(st), id);
            return id;
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Long, Integer> BY_OFFSET = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The kind id of st at (x, y, z), for a block whose shape the game offsets by its position (st.hasOffsetFunction:
     * bamboo, pointed dripstone, some plants): its collision shape where it stands, one kind per state and offset. At one
     * fixed offset (id) a bamboo stalk's thin box was in the same place in every column, so most stalks' tops were no
     * floor at all or a floor in the wrong place (the Parkour Spiral's bamboo levels, 2026-10-15).
     */
    public static int idAt(BlockState st, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        VoxelShape vs = st.getCollisionShape(EmptyBlockGetter.INSTANCE, pos, CollisionContext.empty());
        if (vs.isEmpty()) return id(st);   // a plant without collision: wherever it is drawn, nothing to stand on
        AABB bb = vs.bounds();
        long key = (long) Block.getId(st) * 1_000_003L ^ Math.round(bb.minX * 1000) * 31_337L ^ Math.round(bb.minZ * 1000) * 7_919L
            ^ Math.round(bb.minY * 1000);
        Integer have = BY_OFFSET.get(key);
        if (have != null) return have;
        synchronized (LOCK) {
            have = BY_OFFSET.get(key);
            if (have != null) return have;
            int id = st.isAir() ? Palette.AIR.id : make(st, false, pos).id;
            BY_OFFSET.put(key, id);
            return id;
        }
    }

    private static Kind make(BlockState st) {
        return make(st, false);
    }

    private static Kind make(BlockState st, boolean climb) {
        return make(st, climb, BlockPos.ZERO);
    }

    private static Kind make(BlockState st, boolean climb, BlockPos at) {
        VoxelShape vs = st.getCollisionShape(EmptyBlockGetter.INSTANCE, at, CollisionContext.empty());
        BlockInfo info = WorldSnapshot.info(st, vs);
        if (climb) info = WorldSnapshot.climbable(info);
        List<double[]> boxes = new ArrayList<>();
        for (AABB b : vs.toAabbs()) boxes.add(new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ});
        int flags = 0;
        if (st.getFluidState().is(FluidTags.WATER) || st.is(Blocks.BUBBLE_COLUMN)) flags |= Kind.WATER;
        if (st.getFluidState().is(FluidTags.LAVA) || st.is(Blocks.LAVA)) flags |= Kind.LAVA;
        if (st.is(BlockTags.FIRE) || st.is(Blocks.CACTUS) || st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.WITHER_ROSE)
            || st.is(Blocks.CAMPFIRE) || st.is(Blocks.SOUL_CAMPFIRE) || st.is(Blocks.POWDER_SNOW) || st.is(Blocks.POINTED_DRIPSTONE))
            flags |= Kind.HAZARD;
        if (st.is(Blocks.CACTUS)) flags |= Kind.HARMFUL;   // (not berry bushes: WorldSnapshot)
        if (st.is(Blocks.MAGMA_BLOCK)) flags |= Kind.FLOOR_HAZARD;
        if (climb || st.is(BlockTags.CLIMBABLE) && !st.is(Blocks.SCAFFOLDING)) flags |= Kind.CLIMB;
        // slime is a floor (no longer avoided: layer 2's copy bounces on it, 0be5aef, and its friction is the block's):
        // a slime runway had no floor at all, so a walk standing on one ended at once (the course's slime_friction_run)
        // scaffolding is no longer avoided either (2026-10-15: the copy has it): the table takes its shape for no one in
        // particular (SHAPE_STABLE: the top a floor, the rest a wall), so a column's top is a floor the walk can aim at
        // (the course's scaffolding_jump); going into or through it is layer 2's and the jump search's, from the copy
        if (st.is(Blocks.COBWEB) || st.is(Blocks.NETHER_PORTAL) || st.is(Blocks.END_PORTAL)
            || st.is(Blocks.END_GATEWAY) || st.is(Blocks.HONEY_BLOCK) || st.is(Blocks.BUBBLE_COLUMN))
            flags |= Kind.AVOID;
        Block b = st.getBlock();
        if (b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock) flags |= Kind.DOOR;
        if (b instanceof DoorBlock && st.is(BlockTags.WOODEN_DOORS) && !st.getValue(DoorBlock.OPEN)) flags |= Kind.CLOSED_DOOR;
        // slime too: its stepOn takes 60 % of the speed a tick off a player on it who isn't sneaking (SlimeBlock), so a
        // run-up on it gets nowhere (the Parkour Spiral's leg 35: a gap jump planned from a slime patch at a pit's edge,
        // the walk jumping in place on it until stuck)
        if (b.getSpeedFactor() < 1.0F || st.is(Blocks.SLIME_BLOCK)) flags |= Kind.SLOW;
        // what is drawn above the collision: a snow layer is drawn a layer higher than you stand (one layer: no
        // collision at all), soul sand and mud a full block; plants have no collision and stay out, so the rails go
        // under grass and flowers, which really are drawn over them
        float drawnTop = Float.NaN;
        VoxelShape drawn = st.getShape(EmptyBlockGetter.INSTANCE, at, CollisionContext.empty());
        if (!drawn.isEmpty() && (st.is(Blocks.SNOW) || !vs.isEmpty())) {
            double top = drawn.max(Direction.Axis.Y);
            if (vs.isEmpty() || top > vs.max(Direction.Axis.Y) + 1e-6) drawnTop = (float) top;
        }
        String name = climb ? st + "[climbable]" : at.equals(BlockPos.ZERO) || vs.isEmpty() ? st.toString()
            : st + String.format(java.util.Locale.ROOT, "[at %.3f %.3f]", vs.bounds().minX, vs.bounds().minZ);
        return Palette.add(name, info, boxes, flags, drawnTop);
    }
}
