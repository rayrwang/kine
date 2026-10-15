package ai.rrw.kine.move;

import ai.rrw.kine.mixin.MoveEntityAccessor;
import ai.rrw.kine.mixin.MoveLivingAccessor;
import ai.rrw.kine.mixin.MoveLocalPlayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the live game into the movement copy's terms: the blocks around the player (their collision shapes for the
 * player, friction, speed and jump factors, flags), the player's numbers (Body) and its movement state (MoveState).
 * The only place in move/ that touches Minecraft classes.
 */
public final class WorldSnapshot {
    private WorldSnapshot() {}

    private static final Map<VoxelShape, Shape> SHAPES = new IdentityHashMap<>();
    private static final Map<Key, BlockInfo> INFOS = new HashMap<>();
    private record Key(BlockState state, Shape shape) {}

    /** The blocks in the box from (x0, y0, z0) of size (nx, ny, nz), with the player's collision context. */
    public static BlockGrid blocks(Level level, LivingEntity player, int x0, int y0, int z0, int nx, int ny, int nz) {
        BlockGrid g = new BlockGrid(x0, y0, z0, nx, ny, nz);
        CollisionContext ctx = CollisionContext.of(player);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x0; x < x0 + nx; x++)
            for (int z = z0; z < z0 + nz; z++) {
                if (!level.hasChunkAt(x, z)) {   // unloaded: unknown (solid, flagged)
                    for (int y = y0; y < y0 + ny; y++) g.set(x, y, z, BlockInfo.UNKNOWN);
                    continue;
                }
                for (int y = y0; y < y0 + ny; y++) {
                    pos.set(x, y, z);
                    BlockState st = level.getBlockState(pos);
                    if (st.isAir()) continue;
                    // scaffolding's shape depends on where the player is at each collision query: the snapshot keeps
                    // the shape for no one in particular (SHAPE_STABLE) and flags it (BlockInfo.SCAFFOLD); the copy
                    // picks the player's tick by tick
                    BlockInfo bi = info(st, st.is(Blocks.SCAFFOLDING)
                        ? st.getCollisionShape(level, pos, CollisionContext.empty()) : ctx.getCollisionShape(st, level, pos));
                    // an open trapdoor over a ladder facing the same way is climbable (LivingEntity.onClimbable,
                    // trapdoorUsableAsLadder): the course's trapdoor_ladder
                    if (st.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
                        && st.getValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN)) {
                        BlockState below = level.getBlockState(pos.below());
                        if (below.is(Blocks.LADDER) && below.getValue(net.minecraft.world.level.block.LadderBlock.FACING)
                            == st.getValue(net.minecraft.world.level.block.TrapDoorBlock.FACING)) bi = climbable(bi);
                    }
                    g.set(x, y, z, bi);
                }
            }
        return g;
    }

    /** Whether st, with below under it, is an open trapdoor over a ladder facing the same way (climbable:
     *  LivingEntity.trapdoorUsableAsLadder). */
    public static boolean trapdoorLadder(BlockState st, BlockState below) {
        return st.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
            && st.getValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN)
            && below.is(Blocks.LADDER) && below.getValue(net.minecraft.world.level.block.LadderBlock.FACING)
            == st.getValue(net.minecraft.world.level.block.TrapDoorBlock.FACING);
    }

    private static final Map<BlockInfo, BlockInfo> CLIMBABLE_OF = new IdentityHashMap<>();

    /** A block's info climbable (CLIMBABLE and SPECIAL added, as a ladder's). */
    public static synchronized BlockInfo climbable(BlockInfo b) {
        return CLIMBABLE_OF.computeIfAbsent(b, k -> new BlockInfo(k.shape, k.friction, k.speedFactor, k.jumpFactor,
            k.flags | BlockInfo.CLIMBABLE | BlockInfo.SPECIAL, k.fluid, k.fluidHeight, k.fallFactor));
    }

    /** The blocks within r horizontally and from down below to up above the player's feet. */
    public static BlockGrid around(Level level, LivingEntity player, int r, int down, int up) {
        int bx = (int) Math.floor(player.getX()), by = (int) Math.floor(player.getY()), bz = (int) Math.floor(player.getZ());
        return blocks(level, player, bx - r, by - down, bz - r, 2 * r + 1, down + up + 1, 2 * r + 1);
    }

    /** Synchronized: the pathfinder's table builder (walk/) calls it from its worker thread. */
    public static synchronized BlockInfo info(BlockState st, VoxelShape vs) {
        Shape shape = shape(vs);
        return INFOS.computeIfAbsent(new Key(st, shape), k -> {
            Block b = st.getBlock();
            int flags = 0;
            if (st.is(BlockTags.FENCES)) flags |= BlockInfo.FENCE;
            if (st.is(BlockTags.WALLS)) flags |= BlockInfo.WALL;
            if (b instanceof FenceGateBlock) flags |= BlockInfo.GATE;
            if (st.hasLargeCollisionShape()) flags |= BlockInfo.LARGE;
            if (st.is(Blocks.WATER) || st.is(Blocks.BUBBLE_COLUMN)) flags |= BlockInfo.WATER;
            if (!st.getFluidState().isEmpty()) flags |= BlockInfo.FLUID;
            if (st.is(Blocks.LAVA) || st.is(BlockTags.FIRE) || st.is(Blocks.MAGMA_BLOCK) || st.is(Blocks.CACTUS)
                || st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.WITHER_ROSE) || st.is(Blocks.CAMPFIRE)
                || st.is(Blocks.SOUL_CAMPFIRE) || st.is(Blocks.POWDER_SNOW) || st.is(Blocks.POINTED_DRIPSTONE)
                || st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA))
                flags |= BlockInfo.HAZARD;
            if (st.is(BlockTags.CLIMBABLE) || st.is(Blocks.SLIME_BLOCK) || st.is(Blocks.HONEY_BLOCK) || st.is(BlockTags.BEDS)
                || st.is(Blocks.COBWEB) || st.is(Blocks.SCAFFOLDING) || st.is(Blocks.POWDER_SNOW) || st.is(Blocks.BUBBLE_COLUMN)
                || st.is(Blocks.NETHER_PORTAL) || st.is(Blocks.END_PORTAL) || st.is(Blocks.END_GATEWAY))
                flags |= BlockInfo.SPECIAL;
            if (st.is(BlockTags.CLIMBABLE)) flags |= BlockInfo.CLIMBABLE;   // scaffolding too, since 2026-10-15
            if (st.is(Blocks.SCAFFOLDING)) {
                flags |= BlockInfo.SCAFFOLD;
                if (st.getValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE) != 0
                    && st.getValue(net.minecraft.world.level.block.ScaffoldingBlock.BOTTOM)) flags |= BlockInfo.SCAFFOLD_BOTTOM;
            }
            if (st.is(Blocks.SLIME_BLOCK)) flags |= BlockInfo.SLIME;
            if (st.is(BlockTags.BEDS)) flags |= BlockInfo.BED;
            var fs = st.getFluidState();
            int fluid = fs.isEmpty() ? BlockInfo.NO_FLUID : fs.is(net.minecraft.tags.FluidTags.WATER) ? BlockInfo.WATER_FLUID
                : fs.is(net.minecraft.tags.FluidTags.LAVA) ? BlockInfo.LAVA_FLUID : BlockInfo.NO_FLUID;
            if (fluid != BlockInfo.NO_FLUID && !fs.isSource()) flags |= BlockInfo.CURRENT;
            return new BlockInfo(shape, b.getFriction(), b.getSpeedFactor(), b.getJumpFactor(), flags, fluid,
                fluid == BlockInfo.NO_FLUID ? 0.0F : fs.getOwnHeight(), fallFactor(st));
        });
    }

    /** BlockInfo.fallFactor: the fallOn overrides that cut a landing's damage (HayBlock, HoneyBlock 0.2; BedBlock halves
     *  the distance, taken as 0.5 of the damage; SlimeBlock and PowderSnowBlock none, slime as when not sneaking). */
    static float fallFactor(BlockState st) {
        if (st.is(Blocks.SLIME_BLOCK) || st.is(Blocks.POWDER_SNOW)) return 0.0F;
        if (st.is(Blocks.HAY_BLOCK) || st.is(Blocks.HONEY_BLOCK)) return 0.2F;
        if (st.is(BlockTags.BEDS)) return 0.5F;
        return 1.0F;
    }

    /** The game's shape in the copy's form: its boundaries per axis and its full cells (a cell is full when its centre
     *  is inside one of the shape's boxes, which are unions of full cells). */
    static synchronized Shape shape(VoxelShape vs) {
        if (vs == Shapes.block()) return Shape.BLOCK;
        if (vs.isEmpty()) return Shape.EMPTY;
        if (SHAPES.size() > 4096) { SHAPES.clear(); INFOS.clear(); }   // shapes made per call (offset plants) would pile up
        return SHAPES.computeIfAbsent(vs, v -> {
            double[][] c = new double[3][];
            Direction.Axis[] axes = {Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z};
            for (int a = 0; a < 3; a++) c[a] = v.getCoords(axes[a]).toDoubleArray();
            int sx = c[0].length - 1, sy = c[1].length - 1, sz = c[2].length - 1;
            boolean[] full = new boolean[sx * sy * sz];
            List<AABB> boxes = v.toAabbs();
            for (int i = 0; i < sx; i++)
                for (int j = 0; j < sy; j++)
                    for (int k = 0; k < sz; k++) {
                        double px = (c[0][i] + c[0][i + 1]) / 2, py = (c[1][j] + c[1][j + 1]) / 2, pz = (c[2][k] + c[2][k + 1]) / 2;
                        for (AABB bb : boxes)
                            if (px > bb.minX && px < bb.maxX && py > bb.minY && py < bb.maxY && pz > bb.minZ && pz < bb.maxZ) {
                                full[(i * sy + j) * sz + k] = true;
                                break;
                            }
                    }
            return new Shape(c, full, false);
        });
    }

    private static final Identifier SPRINTING = Identifier.withDefaultNamespace("sprinting");

    /** The player's movement numbers now. */
    public static Body body(LocalPlayer p) {
        Body b = new Body();
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance copy = new AttributeInstance(speed.getAttribute(), x -> {});
        copy.replaceFrom(speed);
        copy.removeModifier(SPRINTING);
        b.walkSpeed = (float) copy.getValue();
        copy.addTransientModifier(new AttributeModifier(SPRINTING, 0.3F, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        b.sprintSpeed = (float) copy.getValue();
        b.sneakSpeed = (float) p.getAttributeValue(Attributes.SNEAKING_SPEED);
        b.jumpStrength = (float) p.getAttributeValue(Attributes.JUMP_STRENGTH);
        b.jumpBoost = p.getJumpBoostPower();
        b.gravity = p.getGravity();
        b.stepHeight = p.maxUpStep();
        b.movementEfficiency = (float) p.getAttributeValue(Attributes.MOVEMENT_EFFICIENCY);
        b.width = p.getDimensions(Pose.STANDING).width();
        b.height = p.getDimensions(Pose.STANDING).height();
        b.crouchHeight = p.getDimensions(Pose.CROUCHING).height();
        b.canSprint = !p.hasEffect(MobEffects.BLINDNESS) && (p.getFoodData().hasEnoughFood() || p.getAbilities().mayfly);
        b.sprintWindow = Minecraft.getInstance().options.sprintWindow().get();
        boolean glider = false;
        for (EquipmentSlot slot : EquipmentSlot.VALUES) glider |= LivingEntity.canGlideUsing(p.getItemBySlot(slot), slot);
        b.canGlide = glider;
        b.knockbackResistance = p.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        b.swimHeight = p.getDimensions(Pose.SWIMMING).height();
        b.eyeHeight = p.getDimensions(Pose.STANDING).eyeHeight();
        b.crouchEyeHeight = p.getDimensions(Pose.CROUCHING).eyeHeight();
        b.swimEyeHeight = p.getDimensions(Pose.SWIMMING).eyeHeight();
        b.waterMovementEfficiency = (float) p.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY);
        b.dolphinsGrace = p.hasEffect(MobEffects.DOLPHINS_GRACE);
        b.maxAir = p.getMaxAirSupply();
        b.oxygenBonus = p.getAttributeValue(Attributes.OXYGEN_BONUS);
        b.turtleHelmet = p.getItemBySlot(EquipmentSlot.HEAD).is(net.minecraft.world.item.Items.TURTLE_HELMET);
        return b;
    }

    /** Ticks of water breathing left: the longest of Water Breathing and Conduit Power (and the Breath of the
     *  Nautilus), endless counting as a day. */
    static int breathingTicks(LocalPlayer p) {
        int best = -1;   // none (the client's duration; the server's runs a tick longer: breathing while 0 too)
        for (var e : new net.minecraft.core.Holder[]{MobEffects.WATER_BREATHING, MobEffects.CONDUIT_POWER, MobEffects.BREATH_OF_THE_NAUTILUS}) {
            @SuppressWarnings("unchecked")
            var inst = p.getEffect((net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>) e);
            if (inst != null) best = Math.max(best, inst.isInfiniteDuration() ? 24000 : inst.getDuration());
        }
        return best;
    }

    /** The player's movement state now (between ticks). */
    public static MoveState state(LocalPlayer p) {
        MoveState s = new MoveState();
        s.x = p.getX(); s.y = p.getY(); s.z = p.getZ();
        var v = p.getDeltaMovement();
        s.vx = v.x; s.vy = v.y; s.vz = v.z;
        s.onGround = p.onGround();
        s.horizontalCollision = p.horizontalCollision;
        s.minorHorizontalCollision = p.minorHorizontalCollision;
        s.sprinting = p.isSprinting();
        s.crouching = p.isCrouching();
        s.crouchPose = p.getPose() == Pose.CROUCHING;
        s.swimPose = p.getPose() == Pose.SWIMMING;
        s.swimming = p.isSwimming();
        s.inWater = p.isInWater();
        s.eyesInWater = p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
        s.waterHeight = p.getFluidHeight(net.minecraft.tags.FluidTags.WATER);
        s.fallFlying = p.isFallFlying();
        s.air = p.getAirSupply();
        s.waterBreathingTicks = breathingTicks(p);
        s.glidePose = p.getPose() == Pose.FALL_FLYING;
        s.sprintTriggerTime = ((MoveLocalPlayerAccessor) p).kine$sprintTriggerTime();
        s.noJumpDelay = ((MoveLivingAccessor) p).kine$noJumpDelay();
        s.fallDistance = p.fallDistance;
        s.onGroundNoBlocks = ((MoveEntityAccessor) p).kine$onGroundNoBlocks();
        if (p.mainSupportingBlockPos.isPresent()) {
            BlockPos b = p.mainSupportingBlockPos.get();
            s.hasSupport = true; s.supX = b.getX(); s.supY = b.getY(); s.supZ = b.getZ();
        }
        s.lastKeys = keys(p.input.keyPresses);
        s.lastMoveForward = p.input.getMoveVector().y;
        return s;
    }

    /** Input as MoveState key bits. */
    public static int keys(Input i) {
        return (i.forward() ? MoveState.FORWARD : 0) | (i.backward() ? MoveState.BACKWARD : 0) | (i.left() ? MoveState.LEFT : 0)
            | (i.right() ? MoveState.RIGHT : 0) | (i.jump() ? MoveState.JUMP : 0) | (i.shift() ? MoveState.SHIFT : 0)
            | (i.sprint() ? MoveState.SPRINT : 0);
    }

    /** MoveState key bits as Input. */
    public static Input input(int k) {
        return new Input((k & MoveState.FORWARD) != 0, (k & MoveState.BACKWARD) != 0, (k & MoveState.LEFT) != 0,
            (k & MoveState.RIGHT) != 0, (k & MoveState.JUMP) != 0, (k & MoveState.SHIFT) != 0, (k & MoveState.SPRINT) != 0);
    }
}
