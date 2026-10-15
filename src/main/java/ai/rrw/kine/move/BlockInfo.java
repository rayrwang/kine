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
    /** Movement the copy leaves out: climbable blocks, slime and beds (bounce; these three copied now), honey (wall slide), cobwebs,
     *  scaffolding (copied now) and powder snow (shape depends on the player), bubble columns, portals. */
    public static final int SPECIAL = 128;

    /** A climbable block (BlockTags.CLIMBABLE, scaffolding too since 2026-10-15): LivingEntity.onClimbable. Also
     *  SPECIAL, as before, for the combat model, which leaves climbing out. */
    public static final int CLIMBABLE = 256;
    /** Water that may flow (not a source, or falling): its current is not copied, a plan touching it is marked. */
    public static final int CURRENT = 512;
    /** Only in MoveState.touched: the plan met movement the copy leaves out (a current, honey, cobwebs, the edge of the
     *  snapshot), as SPECIAL but without ladders, vines, scaffolding, slime and beds, which are copied now. */
    public static final int UNCOPIED = 1024;
    /** A slime block and a bed: a fall onto them bounces (SlimeBlock, BedBlock), copied now. Also SPECIAL, as before,
     *  for the combat model. In MoveState.touched: a landing on slime without the shift key (no fall damage), on a bed
     *  (half). */
    public static final int SLIME = 2048, BED = 4096;
    /**
     * Scaffolding (2026-10-15): its collision shape depends on who touches it (ScaffoldingBlock.getCollisionShape with
     * the entity's collision context), so {@link #shape} holds the one the game gives no one in particular
     * (CollisionContext.empty(): SHAPE_STABLE, the top slab and the four corner legs), and BlockGrid.collect picks the
     * player's from where the player's feet are and the shift key at that moment (see {@link #scaffoldShape}).
     * SCAFFOLD_BOTTOM: a piece with distance above 0 and bottom set (held from the side, nothing of its kind under it),
     * which has a floor in its own bottom eighth for a player above its bottom or sneaking (SHAPE_UNSTABLE_BOTTOM).
     * Scaffolding is CLIMBABLE too (BlockTags.CLIMBABLE).
     */
    public static final int SCAFFOLD = 8192, SCAFFOLD_BOTTOM = 16384;

    /**
     * ScaffoldingBlock.getCollisionShape for an entity whose feet were at entityBottom when its collision context was
     * made, with the shift key down (Entity.isDescending) or not, for the scaffolding block b at height y:
     * SHAPE_STABLE (b.shape) when the entity is above it (EntityCollisionContext.isAbove: entityBottom &gt; y + 1 -
     * 1.0E-5F) and not descending; else, for a bottom piece, SHAPE_UNSTABLE_BOTTOM when the entity is above the block
     * under it (entityBottom &gt; y + 0 - 1.0E-5F, SHAPE_BELOW_BLOCK's top); else nothing. The sums in the game's order
     * and types (int plus double, less the float widened).
     */
    public static Shape scaffoldShape(BlockInfo b, int y, double entityBottom, boolean descending) {
        if (entityBottom > y + 1.0 - (double) 1.0E-5F && !descending) return b.shape;
        if ((b.flags & SCAFFOLD_BOTTOM) != 0 && entityBottom > y + 0.0 - (double) 1.0E-5F) return Shape.SCAFFOLD_UNSTABLE_BOTTOM;
        return Shape.EMPTY;
    }

    /** The fluid in the block (FluidState): none, water or lava; and its own height (FluidState.getOwnHeight: amount /
     *  9, a source 8/9). The height the player meets is 1 where the same fluid is above (FlowingFluid.getHeight). */
    public static final int NO_FLUID = 0, WATER_FLUID = 1, LAVA_FLUID = 2;
    public final int fluid;
    public final float fluidHeight;
    /** What a landing on this block does to the fall damage (its Block.fallOn), as a multiplier of the damage: 1 for
     *  most blocks, 0.2 hay and honey, 0 slime (unless sneaking) and powder snow, 0.5 a bed (whose fallOn halves the
     *  fall distance instead, which takes off more: the multiplier errs high). Read by the elytra guardian's damage
     *  rules; the walk ignores it. */
    public final float fallFactor;

    public static final BlockInfo AIR = new BlockInfo(Shape.EMPTY, 0.6F, 1.0F, 1.0F, 0);
    /** Outside the snapshot: solid, so nothing walks off the known world, and flagged so a plan that reaches it is
     *  marked. */
    public static final BlockInfo UNKNOWN = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, SPECIAL);

    public BlockInfo(Shape shape, float friction, float speedFactor, float jumpFactor, int flags) {
        this(shape, friction, speedFactor, jumpFactor, flags, NO_FLUID, 0.0F);
    }

    public BlockInfo(Shape shape, float friction, float speedFactor, float jumpFactor, int flags, int fluid, float fluidHeight) {
        this(shape, friction, speedFactor, jumpFactor, flags, fluid, fluidHeight, 1.0F);
    }

    public BlockInfo(Shape shape, float friction, float speedFactor, float jumpFactor, int flags, int fluid, float fluidHeight,
                     float fallFactor) {
        this.shape = shape;
        this.friction = friction;
        this.speedFactor = speedFactor;
        this.jumpFactor = jumpFactor;
        this.flags = flags;
        this.fluid = fluid;
        this.fluidHeight = fluidHeight;
        this.fallFactor = fallFactor;
    }

    /** Whether it has any of these flags. */
    public boolean is(int flag) {
        return (flags & flag) != 0;
    }
}
