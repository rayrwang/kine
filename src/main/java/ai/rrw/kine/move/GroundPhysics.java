package ai.rrw.kine.move;

/**
 * One client tick of the local player's movement on foot, copied from the 26.1.2 code so it gives the same doubles:
 * walking, sprinting (with the double-tap window and the stops), jumping and sprint-jumping, sneaking (the slowdown a
 * tick late, the edge stop, the lower box), stepping up, ground friction and the speed and jump factors of the blocks,
 * gravity and air drag, and collision against the blocks' own collision shapes. No Minecraft class is used: the
 * blocks come from a {@link BlockGrid} snapshot and the player's numbers from a {@link Body}.
 *
 * <p>Since 2026-10-12 also water and climbing: the fluid the box is in and its height (Entity.baseTick's
 * updateFluidInteraction, the eyes, swimming started and stopped), the crouch and sprint rules that read water,
 * sneaking down in water, the jump in water (LivingEntity.jumpInLiquid), the swim pitch (Player.travel),
 * LivingEntity.travelInWater (water drag, Depth Strider, Dolphin's Grace, the ladder in water, the fluid's falling
 * adjustment and the jump out of water against a block), and on land ladders and vines (handleOnClimbable, the climb
 * up when pressed against one or jumping, the sneak hold), and the swim pose's box. Checked against recorded ticks
 * (WaterPhysicsTest). A current (flowing water) is not copied: touching it marks MoveState.touched SPECIAL; lava marks
 * it HAZARD.
 *
 * <p>The order is the game's, for a tick in which the player is in survival, on foot, not using an item and not
 * riding:
 * <ol>
 *   <li>Entity.baseTick: the fluid, the eyes, swimming.</li>
 *   <li>LocalPlayer.aiStep: the sprint timer, the crouch state from last tick's sneak key and the room above, this
 *       tick's keys (KeyboardInput.tick), the sprint start and stop rules, sneaking down in water.</li>
 *   <li>LivingEntity.aiStep: the jump delay, tiny velocities zeroed, the input scaled (LocalPlayer.modifyInput), the
 *       jump (from the ground or in a fluid).</li>
 *   <li>Player.travel: the swim pitch; then LivingEntity.travelInWater or travelInAir: the push from the keys
 *       (Entity.moveRelative), climbing, Entity.move (the sneak edge stop, collision with step-up, ground and
 *       support, fall distance, the collided axes zeroed, the landing, the speed factor), then gravity and drag.</li>
 *   <li>Player.tick: the pose for the next tick.</li>
 * </ol>
 * Left out (a plan that meets them is marked through MoveState.touched or wouldGlide): currents, lava's own movement,
 * bouncy and sticky blocks, cobwebs, scaffolding, powder snow, bubble columns, entities with collision boxes (boats,
 * shulkers), the world border, auto-jump (off by default), a fall of a block or more in one tick (its fall-distance
 * reset by a ray).
 */
public final class GroundPhysics {
    private final BlockGrid grid;
    private final Body body;

    // scratch, so a step allocates nothing
    private final BlockGrid.Colliders cols = new BlockGrid.Colliders();
    private final BlockGrid.Colliders cols2 = new BlockGrid.Colliders();
    private final double[] box = new double[6], q = new double[6], mv = new double[3], out = new double[3];
    private final float[] stepHeights = new float[32];

    // this tick's input after LocalPlayer.applyInput (xxa, zza), for isHorizontalCollisionMinor
    private float xxa, zza;
    private float yRot, xRot;

    public GroundPhysics(BlockGrid grid, Body body) {
        this.grid = grid;
        this.body = body;
    }

    public BlockGrid grid() { return grid; }
    public Body body() { return body; }

    /** One tick with these keys (MoveState bits) and this yaw sent, pitch level (it matters only while swimming). */
    public void step(MoveState s, int keys, float yaw) {
        step(s, keys, yaw, 0.0F);
    }

    /** One tick with these keys (MoveState bits) and this rotation sent. */
    public void step(MoveState s, int keys, float yaw, float pitch) {
        yRot = yaw;
        xRot = pitch;
        // a landed glide ends between ticks: the server, on its tick after the landing, clears the flag and stands the
        // pose up, and both come to the client together in its entity data (measured in a lockstep test: the client
        // glides on the ground for 3 ticks, then the 4th is on foot)
        if (s.fallFlying && s.glideGroundTicks >= body.glideLandTicks) {
            s.fallFlying = false;
            s.glideGroundTicks = 0;
            if (s.glidePose) {
                s.glidePose = false;
                if (!canFit(s, POSE_STAND)) s.crouchPose = true;
            }
        }
        // ---- Entity.baseTick (from LivingEntity.tick, before aiStep): the eyes as they were, the water now, swimming
        boolean wasEyeInWater = s.eyesInWater;
        updateFluid(s);
        air(s, wasEyeInWater);
        if (s.swimming) s.swimming = s.sprinting && s.inWater;
        else s.swimming = s.sprinting && wasEyeInWater && s.inWater
            && grid.get(GameMath.floor(s.x), GameMath.floor(s.y), GameMath.floor(s.z)).fluid == BlockInfo.WATER_FLUID;
        boolean underWater = wasEyeInWater && s.inWater;            // Entity.isUnderWater
        boolean shallow = s.inWater && !underWater;                 // Entity.isInShallowWater

        // ---- LocalPlayer.aiStep
        if (s.sprintTriggerTime > 0) s.sprintTriggerTime--;
        boolean wasJumping = (s.lastKeys & MoveState.JUMP) != 0;
        boolean wasShiftKeyDown = (s.lastKeys & MoveState.SHIFT) != 0;
        boolean hadForwardImpulse = s.lastMoveForward > 1.0E-5F;
        s.crouching = !s.swimming && canFit(s, POSE_CROUCH) && (wasShiftKeyDown || !canFit(s, POSE_STAND));
        // KeyboardInput.tick: the move vector from the keys
        float left = impulse((keys & MoveState.LEFT) != 0, (keys & MoveState.RIGHT) != 0);
        float fwd = impulse((keys & MoveState.FORWARD) != 0, (keys & MoveState.BACKWARD) != 0);
        float dist = (float) Math.sqrt(left * left + fwd * fwd);   // Vec2.normalized
        float mx = 0.0F, my = 0.0F;
        if (!(dist < 1.0E-4F)) { mx = left / dist; my = fwd / dist; }
        boolean hasForwardImpulse = my > 1.0E-5F;
        if (wasShiftKeyDown || (keys & MoveState.BACKWARD) != 0) s.sprintTriggerTime = 0;
        // isMovingSlowly: crouching or crawling (the swim pose, or a glide's pose left after it ended, out of water)
        boolean movingSlowly = s.crouching || (s.swimPose || s.glidePose && !s.fallFlying) && !s.inWater;
        // canStartSprinting: food, not at the top of water (shallow), not slowed unless under water
        if (!s.sprinting && hasForwardImpulse && body.canSprint && !shallow && (!s.fallFlying || underWater)
            && (!movingSlowly || underWater)) {
            if (!hadForwardImpulse) {
                if (s.sprintTriggerTime > 0) s.sprinting = true;
                else s.sprintTriggerTime = body.sprintWindow;
            }
            if ((keys & MoveState.SPRINT) != 0) s.sprinting = true;
        }
        if (s.sprinting) {
            if (s.swimming) {   // shouldStopSwimSprinting
                if (!body.canSprint || !s.inWater || !hasForwardImpulse && !s.onGround && (keys & MoveState.SHIFT) == 0)
                    s.sprinting = false;
            } else if (!body.canSprint || shallow || !hasForwardImpulse || s.horizontalCollision && !s.minorHorizontalCollision) {
                s.sprinting = false;   // shouldStopRunSprinting
            }
        }
        if ((keys & MoveState.JUMP) != 0 && !wasJumping && body.canGlide && !s.onGround && !onClimbable(s)) {
            if (!body.glide) s.wouldGlide = true;                          // marked: the copy may leave gliding out
            else if (!s.fallFlying && !s.inWater) {                         // tryToStartFallFlying
                s.fallFlying = true;
                s.glideGroundTicks = 0;
            }
        }
        if (s.inWater && (keys & MoveState.SHIFT) != 0) s.vy = s.vy + -0.04F;   // goDownInWater

        // ---- LivingEntity.aiStep
        if (s.noJumpDelay > 0) s.noJumpDelay--;
        if (s.vx * s.vx + s.vz * s.vz < 9.0E-6) { s.vx = 0.0; s.vz = 0.0; }
        if (Math.abs(s.vy) < 0.003) s.vy = 0.0;
        // LocalPlayer.applyInput / modifyInput
        xxa = 0.0F; zza = 0.0F;
        if (mx * mx + my * my != 0.0F) {
            float ix = mx * 0.98F, iy = my * 0.98F;
            if (movingSlowly) { ix = ix * body.sneakSpeed; iy = iy * body.sneakSpeed; }
            float length = (float) Math.sqrt(ix * ix + iy * iy);   // modifyInputSpeedForSquareMovement
            if (length > 0.0F) {
                float inv = 1.0F / length;
                float dx = ix * inv, dy = iy * inv;
                float ax = Math.abs(dx), ay = Math.abs(dy);
                float tan = ay > ax ? ax / ay : ay / ax;
                float toSquare = (float) Math.sqrt(1.0F + tan * tan);
                float len = Math.min(length * toSquare, 1.0F);
                ix = dx * len; iy = dy * len;
            }
            xxa = ix; zza = iy;
        }
        boolean jumping = (keys & MoveState.JUMP) != 0;
        if (jumping) {
            double fluidHeight = s.waterHeight;
            boolean inWaterAndHasFluidHeight = s.inWater && fluidHeight > 0.0;
            double threshold = eyeHeight(s) < 0.4 ? 0.0 : 0.4;   // getFluidJumpThreshold
            if (!inWaterAndHasFluidHeight || s.onGround && !(fluidHeight > threshold)) {
                if ((s.onGround || inWaterAndHasFluidHeight && fluidHeight <= threshold) && s.noJumpDelay == 0) {
                    jumpFromGround(s);
                    s.noJumpDelay = 10;
                }
            } else {
                s.vy = s.vy + 0.04F;   // jumpInLiquid
            }
        } else {
            s.noJumpDelay = 0;
        }

        // ---- Player.travel: swimming follows the look up or down
        if (s.swimming) {
            double lookY = -GameMath.sin(xRot * (float) (Math.PI / 180.0));   // Entity.calculateViewVector's y
            double multiplier = lookY < -0.2 ? 0.085 : 0.06;
            if (lookY <= 0.0 || jumping
                || grid.get(GameMath.floor(s.x), GameMath.floor(s.y + 1.0 - 0.1), GameMath.floor(s.z)).fluid != BlockInfo.NO_FLUID)
                s.vy = s.vy + (lookY - s.vy) * multiplier;
        }
        // ---- LivingEntity.aiStep: updateFallFlying's checkFallDistanceAccumulation (the rest is the server's)
        if (s.fallFlying && s.vy > -0.5 && s.fallDistance > 1.0) s.fallDistance = 1.0;
        // ---- LivingEntity.travel
        if (s.inWater) travelInWater(s, keys);
        else if (s.fallFlying) travelFallFlying(s, keys, jumping);
        else travelInAir(s, keys, jumping);
        // ---- LivingEntity.aiStep: applyEffectsFromBlocks, the block under the feet's stepOn (SlimeBlock: a step on it
        // slower than 0.1 down, unless sneaking, keeps 0.4 + |vy| * 0.2 of the horizontal speed)
        if (s.onGround && (keys & MoveState.SHIFT) == 0 && blockAt(onPos(s, 0.2F)).is(BlockInfo.SLIME)) {
            double ay = Math.abs(s.vy);
            if (ay < 0.1) {
                double scale = 0.4 + ay * 0.2;
                s.vx = s.vx * scale;
                s.vz = s.vz * scale;
            }
        }
        touchedBlocks(s);

        // ---- Player.tick: updatePlayerPose (the box for the next tick)
        if (canFit(s, POSE_SWIM)) {
            int desired = s.fallFlying ? POSE_GLIDE : s.swimming ? POSE_SWIM : (keys & MoveState.SHIFT) != 0 ? POSE_CROUCH : POSE_STAND;
            int actual = canFit(s, desired) ? desired : canFit(s, POSE_CROUCH) ? POSE_CROUCH : POSE_SWIM;
            s.crouchPose = actual == POSE_CROUCH;
            s.swimPose = actual == POSE_SWIM;
            s.glidePose = actual == POSE_GLIDE;
        }
        // the server ends a glide on the ground (LivingEntity.updateFallFlying: canGlide needs the air); the client hears
        // of it some ticks later (at the start of a tick, above)
        if (s.fallFlying && s.onGround) s.glideGroundTicks++;
        else s.glideGroundTicks = 0;

        s.lastKeys = keys;
        s.lastMoveForward = my;
    }

    static final int POSE_STAND = 0, POSE_CROUCH = 1, POSE_SWIM = 2, POSE_GLIDE = 3;

    /**
     * LivingEntity.baseTick's air, the server's (it runs there, on the position the client sent, a tick behind the
     * client's own; the client hears the value in its entity data): with the eyes in water and no water breathing a
     * point less a tick (Respiration keeps it with chance 1 - 1 / (bonus + 1), counted here as its expected drain), at
     * -20 drowning damage 2 and the air back to 0; with water breathing, or the eyes out, 4 more a tick up to the most.
     * A turtle helmet gives 10 s of water breathing whenever the eyes are out (Player.tick's turtleHelmetTick). The
     * server sees the eyes Body.airEyeLag ticks after the client does (measured: 2), and a breathing effect a tick
     * longer than the client's count of it (so it lasts while the count is 0 too; none is -1).
     */
    private void air(MoveState s, boolean wasEyeInWater) {
        if (s.eyesHistory == MoveState.UNSET) s.eyesHistory = wasEyeInWater ? 0x3FFFFFFF : 0;
        s.eyesHistory = ((s.eyesHistory << 1) | (s.eyesInWater ? 1 : 0)) & 0x3FFFFFFF;
        boolean eyes = ((s.eyesHistory >> body.airEyeLag) & 1) != 0;   // the eyes as the server sees them
        boolean breathing = s.waterBreathingTicks >= 0;
        if (eyes && !breathing) {
            s.air -= body.oxygenBonus > 0.0 ? 1.0 / (body.oxygenBonus + 1.0) : 1.0;
            if (s.air <= -20.0) {
                s.air = 0.0;
                s.drownDamage += 2.0;
            }
        } else if (s.air < body.maxAir) {
            s.air = Math.min(s.air + 4.0, body.maxAir);
        }
        if (s.waterBreathingTicks >= 0) s.waterBreathingTicks--;
        // turtleHelmetTick: Water Breathing for 200 ticks from the last tick the eyes were out (as the server sees them;
        // the count runs out at -1 here, so 199)
        if (!eyes && body.turtleHelmet) s.waterBreathingTicks = 199;
    }

    /** LivingEntity.travelFallFlying (the server's damage for flying into a wall marked as a hazard). */
    private void travelFallFlying(MoveState s, int keys, boolean jumping) {
        if (onClimbable(s)) {
            travelInAir(s, keys, jumping);
            s.fallFlying = false;   // stopFallFlying
            return;
        }
        double lastSpeed = Math.sqrt(s.vx * s.vx + s.vz * s.vz);
        // updateFallFlyingMovement, with getLookAngle = calculateViewVector(xRot, yRot)
        float realXRot = xRot * (float) (Math.PI / 180.0);
        float realYRot = -yRot * (float) (Math.PI / 180.0);
        float yCos = GameMath.cos(realYRot), ySin = GameMath.sin(realYRot);
        float xCos = GameMath.cos(realXRot), xSin = GameMath.sin(realXRot);
        double lx = ySin * xCos, lz = yCos * xCos;
        float leanAngle = xRot * (float) (Math.PI / 180.0);
        double lookHorLength = Math.sqrt(lx * lx + lz * lz);
        double moveHorLength = Math.sqrt(s.vx * s.vx + s.vz * s.vz);
        double gravity = body.gravity;
        double c = Math.cos(leanAngle), liftForce = c * c;
        double mx = s.vx, my = s.vy + gravity * (-1.0 + liftForce * 0.75), mz = s.vz;
        if (my < 0.0 && lookHorLength > 0.0) {
            double convert = my * -0.1 * liftForce;
            mx = mx + lx * convert / lookHorLength; my = my + convert; mz = mz + lz * convert / lookHorLength;
        }
        if (leanAngle < 0.0F && lookHorLength > 0.0) {
            double convert = moveHorLength * -GameMath.sin(leanAngle) * 0.04;
            mx = mx + -lx * convert / lookHorLength; my = my + convert * 3.2; mz = mz + -lz * convert / lookHorLength;
        }
        if (lookHorLength > 0.0) {
            mx = mx + (lx / lookHorLength * moveHorLength - mx) * 0.1;
            mz = mz + (lz / lookHorLength * moveHorLength - mz) * 0.1;
        }
        s.vx = mx * 0.99F; s.vy = my * 0.98F; s.vz = mz * 0.99F;
        move(s, keys);
        if (s.horizontalCollision && (lastSpeed - Math.sqrt(s.vx * s.vx + s.vz * s.vz)) * 10.0 - 3.0 > 0.0)
            s.touched |= BlockInfo.HAZARD;   // handleFallFlyingCollisions: flyIntoWall
    }

    /** LivingEntity.travelInAir, with LivingEntity.handleRelativeFrictionAndCalculateMovement's climbing. */
    private void travelInAir(MoveState s, int keys, boolean jumping) {
        float blockFriction = s.onGround ? belowAffecting(s).friction : 1.0F;
        float friction = blockFriction * 0.91F;
        float speed = s.onGround
            ? (s.sprinting ? body.sprintSpeed : body.walkSpeed) * (0.21600002F / (blockFriction * blockFriction * blockFriction))
            : (s.sprinting ? 0.025999999F : 0.02F);
        moveRelative(s, speed);
        if (onClimbable(s)) {   // handleOnClimbable
            s.fallDistance = 0.0;
            s.vx = GameMath.clamp(s.vx, -0.15F, 0.15F);
            s.vz = GameMath.clamp(s.vz, -0.15F, 0.15F);
            s.vy = Math.max(s.vy, -0.15F);
            if (s.vy < 0.0 && (keys & MoveState.SHIFT) != 0) s.vy = 0.0;   // sneaking holds you on the ladder
        }
        move(s, keys);
        if ((s.horizontalCollision || jumping) && onClimbable(s)) s.vy = 0.2;
        double movementY = s.vy - body.gravity;
        s.vx = s.vx * friction;
        s.vy = movementY * 0.98F;
        s.vz = s.vz * friction;
    }

    /** LivingEntity.travelInFluid and travelInWater, getFluidFallingAdjustedMovement and jumpOutOfFluid. */
    private void travelInWater(MoveState s, int keys) {
        boolean isFalling = s.vy <= 0.0;
        double oldY = s.y;
        float slowDown = s.sprinting ? 0.9F : 0.8F;
        float speed = 0.02F;
        float waterWalker = body.waterMovementEfficiency;
        if (!s.onGround) waterWalker *= 0.5F;
        if (waterWalker > 0.0F) {
            slowDown += (0.54600006F - slowDown) * waterWalker;
            speed += ((s.sprinting ? body.sprintSpeed : body.walkSpeed) - speed) * waterWalker;
        }
        if (body.dolphinsGrace) slowDown = 0.96F;
        moveRelative(s, speed);
        move(s, keys);
        if (s.horizontalCollision && onClimbable(s)) s.vy = 0.2;
        s.vx = s.vx * slowDown;
        s.vy = s.vy * 0.8F;
        s.vz = s.vz * slowDown;
        if (body.gravity != 0.0 && !s.sprinting) {
            if (isFalling && Math.abs(s.vy - 0.005) >= 0.003 && Math.abs(s.vy - body.gravity / 16.0) < 0.003) s.vy = -0.003;
            else s.vy = s.vy - body.gravity / 16.0;
        }
        if (s.horizontalCollision && isFree(s, s.vx, s.vy + 0.6F - s.y + oldY, s.vz)) s.vy = 0.3F;
    }

    /** LivingEntity.onClimbable: the block at the feet is climbable (scaffolding left out). */
    public boolean onClimbable(MoveState s) {
        return grid.get(GameMath.floor(s.x), GameMath.floor(s.y), GameMath.floor(s.z)).is(BlockInfo.CLIMBABLE);
    }

    /** Entity.getEyeHeight in the pose now. */
    private float eyeHeight(MoveState s) {
        return s.swimPose || s.glidePose ? body.swimEyeHeight : s.crouchPose ? body.crouchEyeHeight : body.eyeHeight;
    }

    /**
     * Entity.updateFluidInteraction for water (EntityFluidInteraction.update over the box shrunk by 0.001): how far
     * water reaches above the feet, whether the eyes are in it, in water at all; the fall distance reset in water.
     * Lava is marked as a hazard and flowing water as a current not copied (still water has none).
     */
    void updateFluid(MoveState s) {
        makeBox(s, box);
        double minX = box[0] + 0.001, minY = box[1] + 0.001, minZ = box[2] + 0.001;
        double maxX = box[3] - 0.001, maxY = box[4] - 0.001, maxZ = box[5] - 0.001;
        int x0 = GameMath.floor(minX), y0 = GameMath.floor(minY), z0 = GameMath.floor(minZ);
        int x1 = (int) Math.ceil(maxX) - 1, y1 = (int) Math.ceil(maxY) - 1, z1 = (int) Math.ceil(maxZ) - 1;
        double entityY = box[1];
        int eyeX = GameMath.floor(s.x), eyeZ = GameMath.floor(s.z);
        double eyeY = s.y + eyeHeight(s);
        double height = 0.0;
        boolean eyes = false;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    BlockInfo b = grid.get(x, y, z);
                    if (b.fluid == BlockInfo.NO_FLUID) continue;
                    double bottom = y;
                    double top = bottom + fluidHeightAt(x, y, z, b);
                    if (top < minY) continue;
                    if (b.fluid == BlockInfo.LAVA_FLUID) { s.touched |= BlockInfo.HAZARD; continue; }
                    if (x == eyeX && z == eyeZ && eyeY >= bottom && eyeY <= top) eyes = true;
                    height = Math.max(top - entityY, height);
                    if (b.is(BlockInfo.CURRENT)) s.touched |= BlockInfo.SPECIAL | BlockInfo.UNCOPIED;
                }
        s.waterHeight = height;
        s.eyesInWater = eyes;
        s.inWater = height > 0.0;
        if (s.inWater) s.fallDistance = 0.0;
    }

    /** FlowingFluid.getHeight: 1 where the same fluid is above, else its own height. */
    private float fluidHeightAt(int x, int y, int z, BlockInfo b) {
        return grid.get(x, y + 1, z).fluid == b.fluid ? 1.0F : b.fluidHeight;
    }

    /** Entity.isFree: the box moved by (dx, dy, dz) meets no block and no fluid (LevelReader.containsAnyLiquid). */
    private boolean isFree(MoveState s, double dx, double dy, double dz) {
        makeBox(s, box);
        double[] b = {box[0] + dx, box[1] + dy, box[2] + dz, box[3] + dx, box[4] + dy, box[5] + dz};
        grid.collect(b, cols2);
        if (cols2.n != 0) return false;
        int x0 = GameMath.floor(b[0]), x1 = (int) Math.ceil(b[3]), y0 = GameMath.floor(b[1]), y1 = (int) Math.ceil(b[4]);
        int z0 = GameMath.floor(b[2]), z1 = (int) Math.ceil(b[5]);
        for (int x = x0; x < x1; x++)
            for (int y = y0; y < y1; y++)
                for (int z = z0; z < z1; z++)
                    if (grid.get(x, y, z).fluid != BlockInfo.NO_FLUID) return false;
        return true;
    }

    private static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
    }

    /** A velocity set from outside (the server's knockback, a push by a mob) before the next step. */
    public static void addVelocity(MoveState s, double dx, double dy, double dz) {
        s.vx += dx; s.vy += dy; s.vz += dz;
    }

    // ---------------------------------------------------------------- pieces of the tick

    /** LivingEntity.jumpFromGround (Player's adds only stats and food). */
    private void jumpFromGround(MoveState s) {
        float jumpPower = body.jumpStrength * 1.0F * jumpFactor(s) + body.jumpBoost;
        if (!(jumpPower <= 1.0E-5F)) {
            s.vy = Math.max(jumpPower, s.vy);
            if (s.sprinting) {
                float angle = yRot * (float) (Math.PI / 180.0);
                s.vx += -GameMath.sin(angle) * 0.2;
                s.vz += GameMath.cos(angle) * 0.2;
            }
        }
    }

    /** Entity.moveRelative with the input (xxa, 0, zza) (Entity.getInputVector). */
    private void moveRelative(MoveState s, float speed) {
        double ix = xxa, iz = zza;
        double length = ix * ix + 0.0 * 0.0 + iz * iz;
        if (length < 1.0E-7) return;
        double mx = ix, mz = iz;
        if (length > 1.0) {   // Vec3.normalize
            double d = Math.sqrt(ix * ix + 0.0 * 0.0 + iz * iz);
            if (d < 1.0E-5F) return;
            mx = ix / d; mz = iz / d;
        }
        mx = mx * speed; mz = mz * speed;
        float sin = GameMath.sin(yRot * (float) (Math.PI / 180.0));
        float cos = GameMath.cos(yRot * (float) (Math.PI / 180.0));
        s.vx += mx * cos - mz * sin;
        s.vz += mz * cos + mx * sin;
    }

    /** Entity.move(MoverType.SELF, deltaMovement) for the local player. */
    private void move(MoveState s, int keys) {
        double dx = s.vx, dy = s.vy, dz = s.vz;
        if ((keys & MoveState.SHIFT) != 0 && !(dy > 0.0) && isAboveGround(s)) {   // Player.maybeBackOffFromEdge
            float maxDownStep = body.stepHeight;
            double stepX = Math.signum(dx) * 0.05, stepZ = Math.signum(dz) * 0.05;
            for (; dx != 0.0 && canFallAtLeast(s, dx, 0.0, maxDownStep); dx -= stepX) {
                if (Math.abs(dx) <= 0.05) { dx = 0.0; break; }
            }
            while (dz != 0.0 && canFallAtLeast(s, 0.0, dz, maxDownStep)) {
                if (Math.abs(dz) <= 0.05) { dz = 0.0; break; }
                dz -= stepZ;
            }
            while (dx != 0.0 && dz != 0.0 && canFallAtLeast(s, dx, dz, maxDownStep)) {
                if (Math.abs(dx) <= 0.05) dx = 0.0; else dx -= stepX;
                if (Math.abs(dz) <= 0.05) dz = 0.0; else dz -= stepZ;
            }
        }
        collide(s, dx, dy, dz, out);
        double mxv = out[0], myv = out[1], mzv = out[2];
        double movementLength = mxv * mxv + myv * myv + mzv * mzv;
        if (movementLength > 1.0E-7 || dx * dx + dy * dy + dz * dz - movementLength < 1.0E-7) {
            s.x = s.x + mxv; s.y = s.y + myv; s.z = s.z + mzv;
        }
        boolean xCollision = !GameMath.equal(dx, mxv);
        boolean zCollision = !GameMath.equal(dz, mzv);
        s.horizontalCollision = xCollision || zCollision;
        boolean verticalCollision = dy != myv;
        boolean below = verticalCollision && dy < 0.0;
        s.onGround = below;
        checkSupportingBlock(s, below, mxv, mzv);
        s.minorHorizontalCollision = s.horizontalCollision && isHorizontalCollisionMinor(mxv, mzv);
        // checkFallDamage: LivingEntity's looks at the water again first when not in it, then Entity's counts the fall
        if (!s.inWater) updateFluid(s);
        if (!s.inWater && myv < 0.0) s.fallDistance -= (float) myv;
        if (s.onGround) {
            if (s.fallDistance > 0.0) {   // Block.fallOn on the block under the feet (getOnPosLegacy), then the reset
                s.landedFall = s.fallDistance;
                s.landedFallFactor = blockAt(onPos(s, 0.2F)).fallFactor;
            }
            s.fallDistance = 0.0;
        }
        if (s.horizontalCollision) {
            if (xCollision) s.vx = 0.0;
            if (zCollision) s.vz = 0.0;
        }
        if (dy != myv) {   // Block.updateEntityMovementAfterFallOn on the block under the feet (getOnPosLegacy)
            BlockInfo on = blockAt(onPos(s, 0.2F));
            boolean shift = (keys & MoveState.SHIFT) != 0;   // LocalPlayer.isSuppressingBounce: the shift key this tick
            // a landing on slime (unless sneaking) costs no fall damage, on a bed half (fallOn): marked for the planner
            if (s.onGround && on.is(BlockInfo.SLIME) && !shift) s.touched |= BlockInfo.SLIME;
            if (s.onGround && on.is(BlockInfo.BED)) s.touched |= BlockInfo.BED;
            if (on.is(BlockInfo.SLIME) && !shift) {
                if (s.vy < 0.0) s.vy = -s.vy;                                 // SlimeBlock.bounceUp
            } else if (on.is(BlockInfo.BED) && !shift) {
                if (s.vy < 0.0) s.vy = -s.vy * (double) 0.66F;               // BedBlock.bounceUp
            } else {
                if (on.is(BlockInfo.SPECIAL)) s.touched |= uncopied(on.flags) & (BlockInfo.SPECIAL | BlockInfo.UNCOPIED);
                s.vy = s.vy * 0.0;
            }
        }
        float f = blockSpeedFactor(s);
        s.vx = s.vx * f;
        s.vz = s.vz * f;
    }

    /** Entity.collide: the move cut short by the blocks, stepping up onto what the step height allows. */
    private void collide(MoveState s, double mx, double my, double mz, double[] res) {
        makeBox(s, box);
        double sx, sy, sz;
        if (mx * mx + my * my + mz * mz == 0.0) {
            sx = mx; sy = my; sz = mz;
        } else {
            expandTowards(box, mx, my, mz, q);
            grid.collect(q, cols);
            collideWithShapes(mx, my, mz, box, cols, mv);
            sx = mv[0]; sy = mv[1]; sz = mv[2];
        }
        boolean xc = mx != sx, yc = my != sy, zc = mz != sz;
        boolean onGroundAfter = yc && my < 0.0;
        float maxUp = body.stepHeight;
        if (maxUp > 0.0F && (onGroundAfter || s.onGround) && (xc || zc)) {
            double[] grounded = onGroundAfter ? new double[]{box[0], box[1] + sy, box[2], box[3], box[4] + sy, box[5]} : box.clone();
            double[] up = new double[6];
            expandTowards(grounded, mx, maxUp, mz, up);
            if (!onGroundAfter) expandTowards(up.clone(), 0.0, -1.0E-5F, 0.0, up);
            grid.collect(up, cols2);
            float skip = (float) sy;
            int n = candidateStepUpHeights(grounded, cols2, maxUp, skip);
            for (int i = 0; i < n; i++) {
                collideWithShapes(mx, stepHeights[i], mz, grounded, cols2, mv);
                if (mv[0] * mv[0] + mv[2] * mv[2] > sx * sx + sz * sz) {
                    double toGround = box[1] - grounded[1];
                    res[0] = mv[0]; res[1] = mv[1] - toGround; res[2] = mv[2];
                    return;
                }
            }
        }
        res[0] = sx; res[1] = sy; res[2] = sz;
    }

    /** Entity.collectCandidateStepUpHeights, sorted; returns how many. */
    private int candidateStepUpHeights(double[] b, BlockGrid.Colliders c, float maxStep, float skip) {
        int n = 0;
        for (int i = 0; i < c.n; i++) {
            Shape sh = c.shape[i];
            for (int k = 0; k < sh.coordCountY(); k++) {
                float rel = (float) (sh.coordY(k, c.y[i]) - b[1]);
                if (!(rel < 0.0F) && rel != skip) {
                    if (rel > maxStep) break;
                    boolean dup = false;
                    for (int j = 0; j < n; j++) if (Float.floatToIntBits(stepHeights[j]) == Float.floatToIntBits(rel)) { dup = true; break; }
                    if (!dup && n < stepHeights.length) stepHeights[n++] = rel;
                }
            }
        }
        java.util.Arrays.sort(stepHeights, 0, n);
        return n;
    }

    /** Entity.collideWithShapes: y first, then the larger of x and z. */
    private static void collideWithShapes(double mx, double my, double mz, double[] b, BlockGrid.Colliders c, double[] res) {
        if (c.n == 0) { res[0] = mx; res[1] = my; res[2] = mz; return; }
        double rx = 0.0, ry = 0.0, rz = 0.0;
        boolean zFirst = Math.abs(mx) < Math.abs(mz);   // Direction.axisStepOrder: YZX, else YXZ
        double[] moved = new double[6];
        for (int step = 0; step < 3; step++) {
            int axis = step == 0 ? 1 : (step == 1) == zFirst ? 2 : 0;
            double amount = axis == 0 ? mx : axis == 1 ? my : mz;
            if (amount == 0.0) continue;
            moved[0] = b[0] + rx; moved[1] = b[1] + ry; moved[2] = b[2] + rz;
            moved[3] = b[3] + rx; moved[4] = b[4] + ry; moved[5] = b[5] + rz;
            double d = amount;   // Shapes.collide over the list
            for (int i = 0; i < c.n; i++) {
                if (Math.abs(d) < 1.0E-7) { d = 0.0; break; }
                d = c.shape[i].collide(axis, moved, d, c.x[i], c.y[i], c.z[i]);
            }
            if (axis == 0) rx = d; else if (axis == 1) ry = d; else rz = d;
        }
        res[0] = rx; res[1] = ry; res[2] = rz;
    }

    /** Entity.checkSupportingBlock(onGround, movement). */
    private void checkSupportingBlock(MoveState s, boolean onGround, double mx, double mz) {
        if (onGround) {
            makeBox(s, box);
            double[] area = {box[0], box[1] - 1.0E-6, box[2], box[3], box[1], box[5]};
            boolean found = findSupportingBlock(s, area);
            if (found || s.onGroundNoBlocks) {
                // mainSupportingBlockPos = what was found (or empty)
                if (!found) s.hasSupport = false;
            } else {
                area[0] -= mx; area[3] -= mx; area[2] -= mz; area[5] -= mz;
                found = findSupportingBlock(s, area);
                if (!found) s.hasSupport = false;
            }
            s.onGroundNoBlocks = !found;
        } else {
            s.onGroundNoBlocks = false;
            s.hasSupport = false;
        }
    }

    /** CollisionGetter.findSupportingBlock: the colliding block nearest the player's position, the greater on a tie
     *  (BlockPos.compareTo: y, then z, then x). Sets the support in s when found. */
    private boolean findSupportingBlock(MoveState s, double[] area) {
        grid.collect(area, cols);
        boolean found = false;
        int bx = 0, by = 0, bz = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < cols.n; i++) {
            int x = cols.x[i], y = cols.y[i], z = cols.z[i];
            double ddx = x + 0.5 - s.x, ddy = y + 0.5 - s.y, ddz = z + 0.5 - s.z;
            double d = ddx * ddx + ddy * ddy + ddz * ddz;
            if (d < best || d == best && (!found || compare(bx, by, bz, x, y, z) < 0)) {
                bx = x; by = y; bz = z; best = d; found = true;
            }
        }
        if (found) { s.hasSupport = true; s.supX = bx; s.supY = by; s.supZ = bz; }
        return found;
    }

    private static int compare(int ax, int ay, int az, int bx, int by, int bz) {
        if (ay == by) return az == bz ? ax - bx : az - bz;
        return ay - by;
    }

    /** LocalPlayer.isHorizontalCollisionMinor: the move went within 8 degrees of where the keys push. */
    private boolean isHorizontalCollisionMinor(double mx, double mz) {
        float r = yRot * (float) (Math.PI / 180.0);
        double sin = GameMath.sin(r), cos = GameMath.cos(r);
        double gx = xxa * cos - zza * sin;
        double gz = zza * cos + xxa * sin;
        double a2 = gx * gx + gz * gz;
        double m2 = mx * mx + mz * mz;
        if (!(a2 < 1.0E-5F) && !(m2 < 1.0E-5F)) {
            double dot = gx * mx + gz * mz;
            double angle = Math.acos(dot / Math.sqrt(a2 * m2));
            return angle < 0.13962634F;
        }
        return false;
    }

    /** Player.isAboveGround(maxDownStep), for the sneak edge stop. */
    private boolean isAboveGround(MoveState s) {
        float maxDownStep = body.stepHeight;
        return s.onGround || s.fallDistance < maxDownStep && !canFallAtLeast(s, 0.0, 0.0, maxDownStep - s.fallDistance);
    }

    /** Player.canFallAtLeast: nothing under the box moved by (dx, dz) within minHeight. */
    private boolean canFallAtLeast(MoveState s, double dx, double dz, double minHeight) {
        makeBox(s, box);
        double[] b = {box[0] + 1.0E-7 + dx, box[1] - minHeight - 1.0E-7, box[2] + 1.0E-7 + dz,
                      box[3] - 1.0E-7 + dx, box[1], box[5] - 1.0E-7 + dz};
        grid.collect(b, cols2);
        return cols2.n == 0;
    }

    /** Player.canPlayerFitWithinBlocksAndEntitiesWhen(pose): no block in the pose's box, shrunk by 1e-7. */
    private boolean canFit(MoveState s, int pose) {
        float w = body.width / 2.0F, h = pose == POSE_SWIM || pose == POSE_GLIDE ? body.swimHeight : pose == POSE_CROUCH ? body.crouchHeight : body.height;
        double[] b = {s.x - w + 1.0E-7, s.y + 1.0E-7, s.z - w + 1.0E-7, s.x + w - 1.0E-7, s.y + h - 1.0E-7, s.z + w - 1.0E-7};
        grid.collect(b, cols2);
        return cols2.n == 0;
    }

    // ---------------------------------------------------------------- the blocks under and around

    /** Entity.getOnPos(offset): the supporting block, at the height offset below the feet unless it is a fence, wall
     *  or gate (offset 0.2: any of them; above 0.5: walls and gates only). Packed as {x, y, z}. */
    private int[] onPos(MoveState s, float offset) {
        if (s.hasSupport) {
            if (!(offset > 1.0E-5F)) return new int[]{s.supX, s.supY, s.supZ};
            BlockInfo b = grid.get(s.supX, s.supY, s.supZ);
            boolean keep = (offset <= 0.5 && b.is(BlockInfo.FENCE)) || b.is(BlockInfo.WALL) || b.is(BlockInfo.GATE);
            return keep ? new int[]{s.supX, s.supY, s.supZ} : new int[]{s.supX, GameMath.floor(s.y - offset), s.supZ};
        }
        return new int[]{GameMath.floor(s.x), GameMath.floor(s.y - offset), GameMath.floor(s.z)};
    }

    private BlockInfo blockAt(int[] p) {
        return grid.get(p[0], p[1], p[2]);
    }

    /** The block getBlockPosBelowThatAffectsMyMovement points at (getOnPos(0.500001F)). */
    private BlockInfo belowAffecting(MoveState s) {
        return blockAt(onPos(s, 0.500001F));
    }

    /** Entity.getBlockJumpFactor. */
    private float jumpFactor(MoveState s) {
        float here = grid.get(GameMath.floor(s.x), GameMath.floor(s.y), GameMath.floor(s.z)).jumpFactor;
        float below = belowAffecting(s).jumpFactor;
        return here == 1.0 ? below : here;
    }

    /** LivingEntity.getBlockSpeedFactor (lerped by movement efficiency) over Entity.getBlockSpeedFactor. */
    private float blockSpeedFactor(MoveState s) {
        BlockInfo here = grid.get(GameMath.floor(s.x), GameMath.floor(s.y), GameMath.floor(s.z));
        float f;
        if (!here.is(BlockInfo.WATER)) f = here.speedFactor == 1.0 ? belowAffecting(s).speedFactor : here.speedFactor;
        else f = here.speedFactor;
        float e = body.movementEfficiency;
        return f + e * (1.0F - f);   // Mth.lerp(e, f, 1)
    }

    /** Flags of the blocks the box is in or stands on, for the planner. */
    /** A block's flags, with UNCOPIED added for movement the copy leaves out (SPECIAL but not a ladder, a vine, slime
     *  or a bed, which are copied now; the combat model still reads SPECIAL as before). */
    private static int uncopied(int f) {
        return (f & BlockInfo.SPECIAL) != 0 && (f & (BlockInfo.CLIMBABLE | BlockInfo.SLIME | BlockInfo.BED)) == 0 ? f | BlockInfo.UNCOPIED : f;
    }

    private void touchedBlocks(MoveState s) {
        makeBox(s, box);
        int xa = GameMath.floor(box[0] + 1.0E-5), xb = GameMath.floor(box[3] - 1.0E-5);
        int ya = GameMath.floor(box[1] + 1.0E-5), yb = GameMath.floor(box[4] - 1.0E-5);
        int za = GameMath.floor(box[2] + 1.0E-5), zb = GameMath.floor(box[5] - 1.0E-5);
        int t = 0;
        for (int x = xa; x <= xb; x++)
            for (int y = ya; y <= yb; y++)
                for (int z = za; z <= zb; z++) t |= uncopied(grid.get(x, y, z).flags);
        if (s.onGround) t |= uncopied(blockAt(onPos(s, 0.2F)).flags) & (BlockInfo.HAZARD | BlockInfo.SPECIAL | BlockInfo.UNCOPIED);
        s.touched |= t & (BlockInfo.HAZARD | BlockInfo.SPECIAL | BlockInfo.FLUID | BlockInfo.UNCOPIED);
    }

    /**
     * Whether standing with the box moved by (dx, dz) would be unsafe: nothing to stand on within `depth` below (a fall
     * of more than the safe 3 blocks hurts), or a hazard or fluid under or in the box there. For the planner: a hit's
     * knockback moves a standing player about 0.9 blocks.
     */
    public boolean unsafeAt(MoveState s, double dx, double dz, double depth) {
        makeBox(s, box);
        double[] b = {box[0] + 1.0E-7 + dx, box[1] - depth - 1.0E-7, box[2] + 1.0E-7 + dz,
                      box[3] - 1.0E-7 + dx, box[1], box[5] - 1.0E-7 + dz};
        grid.collect(b, cols2);
        if (cols2.n == 0) return true;
        int xa = GameMath.floor(b[0]), xb = GameMath.floor(b[3]), za = GameMath.floor(b[2]), zb = GameMath.floor(b[5]);
        int ya = GameMath.floor(box[1] - 1.0), yb = GameMath.floor(box[4] - 1.0E-5);
        for (int x = xa; x <= xb; x++)
            for (int z = za; z <= zb; z++)
                for (int y = ya; y <= yb; y++)
                    if (grid.get(x, y, z).is(BlockInfo.HAZARD | BlockInfo.FLUID)) return true;
        return false;
    }

    /** EntityDimensions.makeBoundingBox at the state's position and pose. */
    void makeBox(MoveState s, double[] b) {
        float w = body.width / 2.0F, h = s.swimPose || s.glidePose ? body.swimHeight : s.crouchPose ? body.crouchHeight : body.height;
        b[0] = s.x - w; b[1] = s.y; b[2] = s.z - w;
        b[3] = s.x + w; b[4] = s.y + h; b[5] = s.z + w;
    }

    /** AABB.expandTowards. */
    private static void expandTowards(double[] b, double xa, double ya, double za, double[] o) {
        double minX = b[0], minY = b[1], minZ = b[2], maxX = b[3], maxY = b[4], maxZ = b[5];
        if (xa < 0.0) minX += xa; else if (xa > 0.0) maxX += xa;
        if (ya < 0.0) minY += ya; else if (ya > 0.0) maxY += ya;
        if (za < 0.0) minZ += za; else if (za > 0.0) maxZ += za;
        o[0] = minX; o[1] = minY; o[2] = minZ; o[3] = maxX; o[4] = maxY; o[5] = maxZ;
    }
}
