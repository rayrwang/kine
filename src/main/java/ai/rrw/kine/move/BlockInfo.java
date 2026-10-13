package ai.rrw.kine.move;

/**
 * What the player's ground movement needs to know of one block state: its collision shape (for the player, taken
 * with the player's collision context when the snapshot was made), the block's friction, speed and jump factors, and
 * flags for the blocks whose effect on movement the simulation does not copy (it marks a plan that touches them
 * instead of trusting its own numbers) or that hurt.
 */
public final class BlockInfo {
    public final Shape shape;
    public final float friction, speedFactor, jumpFactor;
    public final int flags;

    /** BlockTags.FENCES, BlockTags.WALLS, a FenceGateBlock: Entity.getOnPos keeps the supporting block for these. */
    public static final int FENCE = 1, WALL = 2, GATE = 4;
    /** BlockState.hasLargeCollisionShape: BlockCollisions looks at it from the ring of blocks just outside the box. */
    public static final int LARGE = 8;
    /** Water or a bubble column (Entity.getBlockSpeedFactor reads only this block then). */
    public static final int WATER = 16;
    /** Any fluid: the player would swim or wade, which the ground copy does not do. */
    public static final int FLUID = 32;
    /** Hurts on touch or stepping on it: lava, fire, magma, cactus, berry bushes, wither roses, campfires,
     *  powder snow, pointed dripstone. */
    public static final int HAZARD = 64;
    /** Movement the copy leaves out: climbable blocks, slime and beds (bounce), honey (wall slide), cobwebs,
     *  scaffolding and powder snow (shape depends on the player), bubble columns, portals. */
    public static final int SPECIAL = 128;

    /** A climbable block (BlockTags.CLIMBABLE, scaffolding aside): LivingEntity.onClimbable. Also SPECIAL, as before,
     *  for the combat model, which leaves climbing out. */
    public static final int CLIMBABLE = 256;
    /** Water that may flow (not a source, or falling): its current is not copied, a plan touching it is marked. */
    public static final int CURRENT = 512;
    /** Only in MoveState.touched: the plan met movement the copy leaves out (a current, slime, honey, cobwebs,
     *  scaffolding, the edge of the snapshot), as SPECIAL but without ladders and vines, which are copied now. */
    public static final int UNCOPIED = 1024;

    /** The fluid in the block (FluidState): none, water or lava; and its own height (FluidState.getOwnHeight: amount /
     *  9, a source 8/9). The height the player meets is 1 where the same fluid is above (FlowingFluid.getHeight). */
    public static final int NO_FLUID = 0, WATER_FLUID = 1, LAVA_FLUID = 2;
    public final int fluid;
    public final float fluidHeight;

    public static final BlockInfo AIR = new BlockInfo(Shape.EMPTY, 0.6F, 1.0F, 1.0F, 0);
    /** Outside the snapshot: solid, so nothing walks off the known world, and flagged so a plan that reaches it is
     *  marked. */
    public static final BlockInfo UNKNOWN = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, SPECIAL);

    public BlockInfo(Shape shape, float friction, float speedFactor, float jumpFactor, int flags) {
        this(shape, friction, speedFactor, jumpFactor, flags, NO_FLUID, 0.0F);
    }

    public BlockInfo(Shape shape, float friction, float speedFactor, float jumpFactor, int flags, int fluid, float fluidHeight) {
        this.shape = shape;
        this.friction = friction;
        this.speedFactor = speedFactor;
        this.jumpFactor = jumpFactor;
        this.flags = flags;
        this.fluid = fluid;
        this.fluidHeight = fluidHeight;
    }

    /** Whether it has any of these flags. */
    public boolean is(int flag) {
        return (flags & flag) != 0;
    }
}
