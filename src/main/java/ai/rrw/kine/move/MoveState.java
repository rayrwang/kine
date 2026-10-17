package ai.rrw.kine.move;

/**
 * The local player's movement state between two client ticks, as far as walking, sprinting, jumping and sneaking on
 * the ground depend on it: where it is and how fast it moves, what it touched in its last move, the sprint and
 * crouch state, the timers, and the keys of the last tick (the game reads some of them a tick late). Copied by value
 * for the search ({@link #copy}).
 */
public final class MoveState {
    // Keys, as bits (ai.rrw.kine.compat.Input's fields, which are the game's)
    public static final int FORWARD = 1, BACKWARD = 2, LEFT = 4, RIGHT = 8, JUMP = 16, SHIFT = 32, SPRINT = 64;
    public static final int MOVE_KEYS = FORWARD | BACKWARD | LEFT | RIGHT;

    public double x, y, z;
    public double vx, vy, vz;
    public boolean onGround, horizontalCollision, minorHorizontalCollision;
    public boolean sprinting;
    /** LocalPlayer.crouching: the sneak slowdown, set from the previous tick's sneak key. */
    public boolean crouching;
    /** The pose is CROUCHING (a 1.5-high box) rather than STANDING. */
    public boolean crouchPose;
    /** The pose is SWIMMING (a 0.6-high box), and the swimming flag (Entity.isSwimming). */
    public boolean swimPose, swimming;
    /** The fluid tracker after the last tick (EntityFluidInteraction for water): in water (wasTouchingWater), how far
     *  the water reaches above the feet, and the eyes in water. */
    public boolean inWater, eyesInWater;
    /** In lava (Entity.isInLava): read only by the 26.3+ glide start (Rules.NO_GLIDE_START_IN_LAVA). */
    public boolean inLava;
    public double waterHeight;
    /** Gliding (Entity shared flag 7, LivingEntity.isFallFlying), the FALL_FLYING pose (a 0.6-high box), and the ticks
     *  on the ground since the glide touched down (the server ends it; the client hears a tick or more later). */
    public boolean fallFlying, glidePose;
    public int glideGroundTicks;
    /** The air supply (Entity.getAirSupply: 300 full, down a point a tick with the eyes in water, drowning at -20), a
     *  double so Respiration's chance to keep a point can be counted as its expected drain; the ticks of water breathing
     *  left (an effect, or a turtle helmet's 10 s); and the drowning damage taken so far (for the planner). */
    public double air = 300.0;
    public int waterBreathingTicks = -1;
    public double drownDamage;
    /** Up to 1.21.4 the client runs the air rule on its own too (Rules.CLIENT_TICKS_AIR): the server's air as the client
     *  hears it, kept apart from the air the client shows; NaN until the first tick works it out from the start. */
    public double serverAir = Double.NaN;
    /** The eyes in water over the last ticks (bit 0 the newest), for the server's air rule, which sees them late;
     *  UNSET until the first tick fills it from the start. */
    public int eyesHistory = UNSET;
    public static final int UNSET = Integer.MIN_VALUE;
    public int sprintTriggerTime, noJumpDelay;
    public double fallDistance;
    /** Entity.mainSupportingBlockPos, and onGroundNoBlocks. */
    public boolean hasSupport, onGroundNoBlocks;
    public int supX, supY, supZ;
    /** The keys of the last tick and its move vector's forward part (hasForwardImpulse reads it a tick late; up to
     *  1.21.4 the forward impulse cut by the sneak speed, Rules.KEY_IMPULSES). */
    public int lastKeys;
    public float lastMoveForward;

    // What the last steps touched, for the planner (not game state)
    /** The player's box touched or stood on a block the copy leaves out or that hurts (BlockInfo flags). */
    public int touched;
    /** Ticks the box has touched a HARMFUL block (BlockInfo.HARMFUL: a cactus), for its damage. */
    public int harmTicks;
    /** A jump pressed in the air with a glider worn would open it: the copy stops being right. */
    public boolean wouldGlide;
    /** The last landing's fall (for the elytra guardian's damage rules, not game state): the fall distance the landing
     *  ended (Entity.checkFallDamage hands it to the block's fallOn before resetting it), and that block's fall factor
     *  (BlockInfo.fallFactor). Left as they were by steps that don't land; the guardian clears them each tick. */
    public double landedFall;
    public float landedFallFactor = 1.0F;

    public MoveState copy() {
        MoveState s = new MoveState();
        s.set(this);
        return s;
    }

    public void set(MoveState o) {
        x = o.x; y = o.y; z = o.z;
        vx = o.vx; vy = o.vy; vz = o.vz;
        onGround = o.onGround; horizontalCollision = o.horizontalCollision; minorHorizontalCollision = o.minorHorizontalCollision;
        sprinting = o.sprinting; crouching = o.crouching; crouchPose = o.crouchPose;
        swimPose = o.swimPose; swimming = o.swimming; inWater = o.inWater; eyesInWater = o.eyesInWater; inLava = o.inLava; waterHeight = o.waterHeight;
        fallFlying = o.fallFlying; glidePose = o.glidePose; glideGroundTicks = o.glideGroundTicks;
        air = o.air; waterBreathingTicks = o.waterBreathingTicks; drownDamage = o.drownDamage; eyesHistory = o.eyesHistory;
        serverAir = o.serverAir;
        sprintTriggerTime = o.sprintTriggerTime; noJumpDelay = o.noJumpDelay;
        fallDistance = o.fallDistance;
        hasSupport = o.hasSupport; onGroundNoBlocks = o.onGroundNoBlocks;
        supX = o.supX; supY = o.supY; supZ = o.supZ;
        lastKeys = o.lastKeys; lastMoveForward = o.lastMoveForward;
        touched = o.touched; harmTicks = o.harmTicks; wouldGlide = o.wouldGlide;
        landedFall = o.landedFall; landedFallFactor = o.landedFallFactor;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "pos %.6f %.6f %.6f v %.6f %.6f %.6f %s%s", x, y, z, vx, vy, vz,
            onGround ? "ground" : "air", sprinting ? " sprint" : "");
    }
}
