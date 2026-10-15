package ai.rrw.kine.compat;

/**
 * The game's own rules that kine's physics and combat copies follow, where they differ between releases
 * (versions/README.md; the comparison is in the porting folder's physics/FINDINGS.md). Each is fixed for the running
 * release when kine starts; in unit tests the release is the system property kine.minecraft, so the copies can be
 * replayed against any release's recorded traces. A copy branches on these and nothing else, so every per-release
 * rule is listed here.
 *
 * <p>The copies are written against 26.1.2. A flag that is true on older releases names what those releases do (the
 * copy keeps 26.1.2's code in its other branch); a flag that is true on newer ones names what changed there.
 */
public final class Rules {
    private Rules() {}

    // ---------------------------------------------------------------- the player on foot (GroundPhysics)

    /** Up to 1.21.4: LivingEntity.aiStep zeroes each horizontal speed of every entity, the player's too, on its own
     *  when under 0.003 (`|v.x| < 0.003`, `|v.z| < 0.003`). From 1.21.5 the player zeroes both together, only when
     *  `v.horizontalDistanceSqr() < 9.0E-6` (other entities as before). */
    public static final boolean PLAYER_SPEED_ZEROED_PER_AXIS = !GameVersion.atLeast("1.21.5");

    /** Up to 1.21.4: the move input is the key impulses themselves, -1, 0 or 1 a side (KeyboardInput.tick), cut to the
     *  sneak speed while moving slowly (in KeyboardInput.tick to 1.21.3, in LocalPlayer.aiStep in 1.21.4) and to 0.98
     *  in LivingEntity.aiStep, never normalised (Entity.moveRelative still normalises a vector longer than 1); the
     *  forward impulse kept for the next tick is the cut one. The sprint rules in LocalPlayer.aiStep read those
     *  impulses: a sprint starts (hasEnoughImpulseToStartSprinting: forward impulse at least 0.8, or above 1e-5 under
     *  water) on a double tap only on the ground or under water, the tap window reset only by last tick's shift key,
     *  or with the sprint key unless at the top of water; it stops on no forward impulse, too little food, a head-on
     *  wall, or the top of water (swimming: as from 1.21.5), and blindness only blocks a start. From 1.21.5:
     *  `moveVector = new Vec2(left, forward).normalized()`, LocalPlayer.modifyInput's 0.98, sneak speed and square
     *  movement, and the sprint rules of canStartSprinting, shouldStopRunSprinting and shouldStopSwimSprinting. */
    public static final boolean KEY_IMPULSES = !GameVersion.atLeast("1.21.5");

    /** 1.21.4 only: LocalPlayer.shouldStopSprinting, right after KeyboardInput.tick, ends a sprint while gliding, blind
     *  or moving slowly (sneaking or crawling), and canStartSprinting needs `!isMovingSlowly() || isUnderWater()`.
     *  Before 1.21.4 neither (a sneaking sprinter keeps the sprint); from 1.21.5 the rules of KEY_IMPULSES's end. */
    public static final boolean SNEAK_STOPS_SPRINT = GameVersion.atLeast("1.21.4") && !GameVersion.atLeast("1.21.5");

    /** Up to 1.21.1: LivingEntity.jumpFromGround sets `v.y = jumpPower`. From 1.21.2 `v.y = max(jumpPower, v.y)`, so a
     *  jump that starts rising faster (off a slime bounce) keeps the faster rise. */
    public static final boolean JUMP_SETS_POWER = !GameVersion.atLeast("1.21.2");

    /** Up to 1.21.1: Entity.move moves the entity only when the collided movement's squared length is over 1.0E-7.
     *  From 1.21.2 also when the whole wanted movement was under that and not cut by a collision
     *  (`len2 > 1.0E-7 || movement.lengthSqr() - len2 < 1.0E-7`). */
    public static final boolean MOVE_ONLY_IF_LONG = !GameVersion.atLeast("1.21.2");

    /** Up to 1.21.1: Entity.move calls Block.stepOn of the block under the feet (getOnPosLegacy) while on the ground,
     *  in every move, right after the landing (updateEntityAfterFallOn) and before the block speed factor; slime's
     *  stepOn then reads the bounced v.y. From 1.21.2 LivingEntity.aiStep calls it once, after travel
     *  (applyEffectsFromBlocks), on the v.y after gravity and drag. */
    public static final boolean STEP_ON_IN_MOVE = !GameVersion.atLeast("1.21.2");

    /** Up to 1.21.1: SlimeBlock.fallOn hands a landing with the shift key down to Block.fallOn, full fall damage.
     *  From 1.21.2 a landing on slime takes no fall damage, sneaking or not. */
    public static final boolean SNEAK_LANDING_ON_SLIME_HURTS = !GameVersion.atLeast("1.21.2");

    /** Up to 1.21.3: LivingEntity.baseTick leaves the air as it is while the eyes are in water and the entity can
     *  breathe there (water breathing, conduit power). From 1.21.4 it refills it, 4 a tick, as with the eyes out. */
    public static final boolean NO_AIR_REFILL_IN_WATER = !GameVersion.atLeast("1.21.4");

    /** Up to 1.21.4: LivingEntity.baseTick's air rule runs on the client too, for its own player on its own eyes, and
     *  the server's value (entity data, sent whenever it changes) replaces the client's before the client's own step:
     *  going under, the client's air drops in the tick its eyes go in, two ticks before the server's comes, and then
     *  shows the server's less the client's own point. From 1.21.5 the rule runs on the server only (`isAlive() &&
     *  level() instanceof ServerLevel`) and the client shows the server's value. */
    public static final boolean CLIENT_TICKS_AIR = !GameVersion.atLeast("1.21.5");

    /** 1.21.4 only: Entity.move calls checkFallDamage only `if (... && !isControlledByClient())`, which a player is,
     *  so the client neither counts the local player's fall distance (it stays 0: Player.isAboveGround, the sneak
     *  edge stop, reads 0) nor looks at the water again after the move (LivingEntity.checkFallDamage). The server
     *  still counts the fall from the move packets (ServerPlayer.doCheckFallDamage), by the same rule. Before and
     *  after 1.21.4 the client runs checkFallDamage for its own player. */
    public static final boolean CLIENT_SKIPS_FALL_CHECK = GameVersion.atLeast("1.21.4") && !GameVersion.atLeast("1.21.5");

    /** Up to 1.21.4: Entity.checkFallDamage adds a tick's drop to the fall distance only when the move didn't land
     *  (`if (onGround) { fallOn; reset } else if (dy < 0) fallDistance -= dy`), in water too, and the fall distance is a
     *  float. From 1.21.5 it adds the drop first, out of water only (`if (!isInWater() && dy < 0)`), then lands, in
     *  double. */
    public static final boolean FALL_LANDING_TICK_UNCOUNTED = !GameVersion.atLeast("1.21.5");

    /** Up to 1.21.4: LivingEntity.calculateFallDamage is `ceil((fallDistance - safeFallDistance) * multiplier)` in
     *  float. From 1.21.5 `floor((fallDistance + 1.0E-6 - safeFallDistance) * multiplier)` (calculateFallPower). */
    public static final boolean FALL_DAMAGE_ROUNDS_UP = !GameVersion.atLeast("1.21.5");

    /** Up to 1.21.4: Player.canFallAtLeast tests the box moved by (dx, dz), from minY - minHeight - 1.0E-5F to minY,
     *  its own width. From 1.21.5 the box is shrunk by 1.0E-7 on each side and goes 1.0E-7 below the height. */
    public static final boolean EDGE_TEST_UNSHRUNK = !GameVersion.atLeast("1.21.5");

    /** Up to 1.21.4: LivingEntity.travel's glide (travelFallFlying from 1.21.2) goes on in a climbable block. From
     *  1.21.5 travelFallFlying, onClimbable, flies the air's way (travelInAir) and ends the glide. */
    public static final boolean GLIDE_THROUGH_CLIMBABLE = !GameVersion.atLeast("1.21.5");

    /** Up to 1.21.11: Entity.updateFluidHeightAndDoFluidPushing measures the water from the bottom of the box shrunk by
     *  0.001 (`height = max(top - shrunkMinY)`, in water when some block's top reaches shrunkMinY, height 0 included),
     *  and updateFluidOnEyes, from Entity.baseTick only, tests the eyes against the one block holding them, strictly
     *  (`y + height > eyeY`). LivingEntity.checkFallDamage's look at the water after a move updates the water, not the
     *  eyes. From 26.1 EntityFluidInteraction.update measures from the box's own bottom (0.001 more), in water when the
     *  height is above 0, the eyes in any water block of the box's eye column up to its top inclusive, and the look
     *  after a move updates the eyes too. */
    public static final boolean FLUID_HEIGHT_FROM_SHRUNK_BOX = !GameVersion.atLeast("26.1");

    /** 26.2+: a collision restitutes the movement (Entity.restituteMovementAfterCollisions): a fall of at least one
     *  tick's gravity onto a block bounces by the block's restitution (slime 1, beds 0.75), adding back the gravity
     *  of the part of the tick after the hit and its drag; honey (suppresses_bounce) and the shift key stop it.
     *  Before: slime returns -v.y, a bed -0.66 v.y, anything else 0 (SlimeBlock, BedBlock.bounceUp). */
    public static final boolean BOUNCE_RESTITUTION = GameVersion.atLeast("26.2");

    /** 26.3+: the eyes are in water up to FluidState.getHeightForCamera: 1 for a source block under a block whose
     *  bottom face is sturdy, else the fluid's height. Before: the fluid's height. */
    public static final boolean EYES_CAMERA_HEIGHT = GameVersion.atLeast("26.3");

    /** 26.3+: no glide starts in lava (Player.tryToStartFallFlying tests isInLiquid). Before: water only. */
    public static final boolean NO_GLIDE_START_IN_LAVA = GameVersion.atLeast("26.3");

    // ---------------------------------------------------------------- angles

    /** Up to 1.21.10: Mth.sin and cos take a float and index the table with float arithmetic, (int) (f * 10430.378F)
     *  (cos adding 16384.0F); from 1.21.11 a double, (int) (long) (d * 10430.378350470453). The tables are the same; the
     *  index differs for about 0.1% of angles (physics/results/mth_sin_cos.txt). */
    public static final boolean MTH_FLOAT_INDEX = !GameVersion.atLeast("1.21.11");

    // ---------------------------------------------------------------- projectiles

    /** Up to 1.21.1: ThrowableProjectile.tick (snowballs, eggs, pearls, potions, bottles) and
     *  AbstractHurtingProjectile.tick (fireballs, wind charges) test the hit and move on the velocity they have, then
     *  change it: a thrown item `v *= 0.99F`, then gravity (as an arrow does); a fireball `v = (v + v.normalize() *
     *  accelerationPower) * inertia`. A hit is handled where the projectile was before the move, so a potion splashes
     *  and a fireball or a wind charge hitting an entity bursts from there (a wind charge on a block bursts at the hit
     *  point). From 1.21.2 the change comes first (gravity, then drag; the fireball's inertia), then the hit test and
     *  the move, and the projectile is at the hit point when the hit is handled. */
    public static final boolean PROJECTILES_MOVE_FIRST = !GameVersion.atLeast("1.21.2");

    /** Up to 1.21.5: ProjectileUtil grows the entities' boxes for a projectile's hit test by a fixed 0.3F
     *  (getHitResultOnMoveVector, getEntityHitResult). From 1.21.6 by computeMargin: `max(0, min(0.3F, (tickCount - 2) /
     *  20F))`, nothing for the first two ticks. */
    public static final boolean FIXED_HIT_MARGIN = !GameVersion.atLeast("1.21.6");

    /** Up to 1.21.5: a splash potion (ThrownPotion.applySplash, ThrownSplashPotion.onHitAsPotion from 1.21.5) reaches the
     *  living entities whose boxes meet its own box grown by (4, 2, 4), with intensity 1 for the one hit, else
     *  `1 - sqrt(d2) / 4` with d2 the squared distance from the potion's position to the entity's (its feet), under 16.
     *  From 1.21.6 d2 is from the potion's box at the hit point to the entity's box grown by computeMargin, for all. */
    public static final boolean SPLASH_FROM_FEET = !GameVersion.atLeast("1.21.6");

    // ---------------------------------------------------------------- melee

    /** Up to 1.21.1: Player.attack's sweep needs `walkDist - walkDistO < getSpeed()` (walkDist grows by 0.6 times the
     *  horizontal distance of each move, in float) and the held item a SwordItem. On the server walkDistO is set by
     *  its player's own tick (Entity.baseTick), after the move packets it has had, and a hit is sent before the client's
     *  move of that tick (handleKeybinds comes before the player's tick), so the server normally finds no move since
     *  and the test is `0 < getSpeed()`: a walking player sweeps too (three walking hits of three in a 1.21.1 client
     *  test; only moves bunched by the network between two server ticks count). The copies take it so. From 1.21.2
     *  `getKnownMovement().horizontalDistanceSqr() < (getSpeed() * 2.5)^2` (the last move the server had) and the item
     *  in #swords. */
    public static final boolean SWEEP_BY_WALK_DIST = !GameVersion.atLeast("1.21.2");

    /** Up to 1.21.4: Player.attack's sweep knocks each swept mob back (0.4 along the facing) before hurting it, whatever
     *  the hurt does. From 1.21.5 it hurts first and knocks back only a mob the hurt took (hurtServer true). */
    public static final boolean SWEEP_KNOCKS_FIRST = !GameVersion.atLeast("1.21.5");

    // ---------------------------------------------------------------- mobs

    /** Up to 1.21.4: LivingEntity.aiStep cuts xxa and zza to 0.98F after serverAiStep, so a mob moves at 0.98 of the
     *  input its MoveControl set (walking, strafing, a slime's hop). From 1.21.5 applyInput cuts them before
     *  serverAiStep, whose MoveControl sets them again (Mob.setSpeed), so the cut never reaches a mob's move. (The local
     *  player keeps it on every release: LocalPlayer's own input, GroundPhysics.) Measured in 1.21.4 lockstep client
     *  tests: a zombie villager walks 0.11419 a tick against 26.1.2's 0.11652, a vindicator 0.26443 against 0.26982. */
    public static final boolean MOB_INPUT_CUT = !GameVersion.atLeast("1.21.5");

    /** Up to 1.21.1: the breeze's Shoot starts only with the target more than 2 blocks away (isTargetWithinRange:
     *  `4.0 < d2 < 256.0`, feet to feet; once started the shot goes). From 1.21.2 `d2 < 256.0`. The facing test it also
     *  had then (`view . toTarget > 0.5`, right after looking at the target's feet) always passes beyond 2 blocks, since
     *  the eyes are 1.35 above the feet: the two directions differ by asin(1.35 / 2) = 42 degrees at most. */
    public static final boolean BREEZE_NO_SHOT_WITHIN_2 = !GameVersion.atLeast("1.21.2");

    /** Up to 1.21.1: the breeze's wind charge starts at its snout (Breeze.getSnoutYPosition: the eyes less 0.4) and is
     *  aimed from its middle (`target.getY(0.3) - breeze.getY(0.5)`). From 1.21.2 both from getFiringYPosition (the
     *  middle plus 0.3). */
    public static final boolean BREEZE_SHOOTS_FROM_SNOUT = !GameVersion.atLeast("1.21.2");

    /** 26.1+: a witch throws at 0.45F within 2 blocks (horizontally), else 0.75F (Witch.performRangedAttack). Before:
     *  always 0.75F. */
    public static final boolean WITCH_THROWS_SLOWER_NEAR = GameVersion.atLeast("26.1");

    /** 26.3+: a projectile makes an enderman teleport only when it isn't riding (EnderMan.hurtServer). Before: always. */
    public static final boolean ENDERMAN_RIDING_TAKES_PROJECTILES = GameVersion.atLeast("26.3");

    // ---------------------------------------------------------------- the world

    /** Up to 1.21.10: Level.updateSkyBrightness darkens the sky by `(int) ((1 - (0.5 + 2 * clamp(cos(timeOfDay * 2 pi),
     *  -0.25, 0.25)) * (1 - rain * 5 / 16) * (1 - thunder * 5 / 16)) * 11)`, from the sun's angle (DimensionType
     *  .timeOfDay): it falls to 7 at dawn at tick 22813 of the day in clear weather, 23032 in rain. From 1.21.11
     *  `(int) (15 - SKY_LIGHT_LEVEL)`, the environment attribute's timeline: 22822 and 23046 (HoldModel). */
    public static final boolean SKY_DARKEN_FROM_SUN_ANGLE = !GameVersion.atLeast("1.21.11");

    /** Up to 1.21.11: ClientLevel.tick works the sky's darkening out (updateSkyBrightness) before tickTime moves the day
     *  time on, so at the end of a client tick the darkening is the time of day before's, a tick behind the time kine
     *  reads (Compat.overworldTime: level.getDayTime). From 26.1 the day is a world clock that ClientPacketListener.tick
     *  moves on (from MultiPlayerGameMode.tick) before the level's tick, and the darkening is the same tick's time. (1.21
     *  and 1.21.1 work the client's darkening out only when the level loads: ClientLevel.tick calls updateSkyBrightness
     *  from 1.21.2 on.) */
    public static final boolean SKY_DARKEN_A_TICK_LATE = !GameVersion.atLeast("26.1");
}
