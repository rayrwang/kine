package ai.rrw.kine.move;

/**
 * One client tick of the local player's movement on foot, copied from the 26.1.2 code so it gives the same doubles:
 * walking, sprinting (with the double-tap window and the stops), jumping and sprint-jumping, sneaking (the slowdown a
 * tick late, the edge stop, the lower box), stepping up, ground friction and the speed and jump factors of the blocks,
 * gravity and air drag, and collision against the blocks' own collision shapes. No Minecraft class is used: the
 * blocks come from a {@link BlockGrid} snapshot and the player's numbers from a {@link Body}.
 *
 * <p>The order is the game's, for a tick in which the player is in survival, on foot, out of water, not using an item
 * and not riding:
 * <ol>
 *   <li>LocalPlayer.aiStep: the sprint timer, the crouch state from last tick's sneak key and the room above, this
 *       tick's keys (KeyboardInput.tick), the sprint start and stop rules.</li>
 *   <li>LivingEntity.aiStep: the jump delay, tiny velocities zeroed, the input scaled (LocalPlayer.modifyInput), the
 *       jump.</li>
 *   <li>LivingEntity.travelInAir: the push from the keys (Entity.moveRelative with getFrictionInfluencedSpeed),
 *       Entity.move (the sneak edge stop, collision with step-up, ground and support, fall distance, the collided axes
 *       zeroed, the landing, the speed factor), then gravity and friction.</li>
 *   <li>Player.tick: the pose for the next tick.</li>
 * </ol>
 * Left out (a plan that meets them is marked through MoveState.touched or wouldGlide): fluids, climbing, bouncy and
 * sticky blocks, cobwebs, scaffolding, powder snow, entities with collision boxes (boats, shulkers), the world border,
 * auto-jump (off by default), a fall of a block or more in one tick (its fall-distance reset by a ray).
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
    private float yRot;

    public GroundPhysics(BlockGrid grid, Body body) {
        this.grid = grid;
        this.body = body;
    }

    public BlockGrid grid() { return grid; }
    public Body body() { return body; }

    /** One tick with these keys (MoveState bits) and this rotation sent. */
    public void step(MoveState s, int keys, float yaw) {
        yRot = yaw;
        // ---- LocalPlayer.aiStep
        if (s.sprintTriggerTime > 0) s.sprintTriggerTime--;
        boolean wasJumping = (s.lastKeys & MoveState.JUMP) != 0;
        boolean wasShiftKeyDown = (s.lastKeys & MoveState.SHIFT) != 0;
        boolean hadForwardImpulse = s.lastMoveForward > 1.0E-5F;
        s.crouching = canFit(s, true) && (wasShiftKeyDown || !canFit(s, false));
        // KeyboardInput.tick: the move vector from the keys
        float left = impulse((keys & MoveState.LEFT) != 0, (keys & MoveState.RIGHT) != 0);
        float fwd = impulse((keys & MoveState.FORWARD) != 0, (keys & MoveState.BACKWARD) != 0);
        float dist = (float) Math.sqrt(left * left + fwd * fwd);   // Vec2.normalized
        float mx = 0.0F, my = 0.0F;
        if (!(dist < 1.0E-4F)) { mx = left / dist; my = fwd / dist; }
        boolean hasForwardImpulse = my > 1.0E-5F;
        if (wasShiftKeyDown || (keys & MoveState.BACKWARD) != 0) s.sprintTriggerTime = 0;
        boolean movingSlowly = s.crouching;
        if (!s.sprinting && hasForwardImpulse && body.canSprint && !movingSlowly) {   // canStartSprinting
            if (!hadForwardImpulse) {
                if (s.sprintTriggerTime > 0) s.sprinting = true;
                else s.sprintTriggerTime = body.sprintWindow;
            }
            if ((keys & MoveState.SPRINT) != 0) s.sprinting = true;
        }
        if (s.sprinting && (!body.canSprint || !hasForwardImpulse || s.horizontalCollision && !s.minorHorizontalCollision))
            s.sprinting = false;   // shouldStopRunSprinting
        if ((keys & MoveState.JUMP) != 0 && !wasJumping && body.canGlide && !s.onGround) s.wouldGlide = true;   // tryToStartFallFlying

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
            if (s.onGround && s.noJumpDelay == 0) {
                jumpFromGround(s);
                s.noJumpDelay = 10;
            }
        } else {
            s.noJumpDelay = 0;
        }

        // ---- LivingEntity.travelInAir
        float blockFriction = s.onGround ? belowAffecting(s).friction : 1.0F;
        float friction = blockFriction * 0.91F;
        float speed = s.onGround
            ? (s.sprinting ? body.sprintSpeed : body.walkSpeed) * (0.21600002F / (blockFriction * blockFriction * blockFriction))
            : (s.sprinting ? 0.025999999F : 0.02F);
        moveRelative(s, speed);
        move(s, keys);
        double movementY = s.vy - body.gravity;
        s.vx = s.vx * friction;
        s.vy = movementY * 0.98F;
        s.vz = s.vz * friction;
        touchedBlocks(s);

        // ---- Player.tick: updatePlayerPose (the box for the next tick)
        boolean wantCrouch = (keys & MoveState.SHIFT) != 0;
        if (canFit(s, wantCrouch)) s.crouchPose = wantCrouch;
        else if (canFit(s, true)) s.crouchPose = true;
        else s.touched |= BlockInfo.SPECIAL;   // the game would make you swim (crawl): not copied

        s.lastKeys = keys;
        s.lastMoveForward = my;
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
        // checkFallDamage (Entity's; not in water)
        if (myv < 0.0) s.fallDistance -= (float) myv;
        if (s.onGround) s.fallDistance = 0.0;
        if (s.horizontalCollision) {
            if (xCollision) s.vx = 0.0;
            if (zCollision) s.vz = 0.0;
        }
        if (dy != myv) {   // Block.updateEntityMovementAfterFallOn on the block under the feet (getOnPosLegacy)
            BlockInfo on = blockAt(onPos(s, 0.2F));
            if (on.is(BlockInfo.SPECIAL)) s.touched |= BlockInfo.SPECIAL;   // slime and beds bounce
            s.vy = s.vy * 0.0;
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
    private boolean canFit(MoveState s, boolean crouch) {
        float w = body.width / 2.0F, h = crouch ? body.crouchHeight : body.height;
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
    private void touchedBlocks(MoveState s) {
        makeBox(s, box);
        int xa = GameMath.floor(box[0] + 1.0E-5), xb = GameMath.floor(box[3] - 1.0E-5);
        int ya = GameMath.floor(box[1] + 1.0E-5), yb = GameMath.floor(box[4] - 1.0E-5);
        int za = GameMath.floor(box[2] + 1.0E-5), zb = GameMath.floor(box[5] - 1.0E-5);
        int t = 0;
        for (int x = xa; x <= xb; x++)
            for (int y = ya; y <= yb; y++)
                for (int z = za; z <= zb; z++) t |= grid.get(x, y, z).flags;
        if (s.onGround) t |= blockAt(onPos(s, 0.2F)).flags & (BlockInfo.HAZARD | BlockInfo.SPECIAL);
        s.touched |= t & (BlockInfo.HAZARD | BlockInfo.SPECIAL | BlockInfo.FLUID);
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
        float w = body.width / 2.0F, h = s.crouchPose ? body.crouchHeight : body.height;
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
