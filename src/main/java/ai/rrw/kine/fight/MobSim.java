package ai.rrw.kine.fight;

import java.util.SplittableRandom;

/**
 * One mob in the fight simulation, from its 26.1.2 goal code, simplified on the pessimistic side where the client can't
 * know the state. Every kind shares the body (position, ground physics on flat ground, knockback, the 10-tick hurt
 * window, what the client shows of it) and differs in its goal ({@link Kind}):
 * <ul>
 *   <li>{@link Kind#MELEE}: MeleeAttackGoal (zombies and their variants, wither skeletons and skeletons without a bow,
 *       and the other walkers that hit in reach). Its target is the player. It re-aims its path at the centre of the
 *       player's block every 4 to 10 ticks (random, MeleeAttackGoal.tick) and walks straight there (no terrain for
 *       mobs). When it gets within its node distance of that point on x and z the path is done (GroundPathNavigation);
 *       a goal that doesn't follow an unseen target (followingTargetEvenIfNotSeen false) then stops and starts again at
 *       its next check, at most every 20 ticks (canUse), with its attack ready (start): the real 11-tick second hit on
 *       a player standing still. One that does follow (spiders) stands until its next re-aim.</li>
 *   <li>{@link Kind#LEAPER}: the melee goal (following) and LeapAtTargetGoal (spiders and cave spiders): on the
 *       ground with the player 2 to 4 blocks away it leaps (a third of its checks, every other tick), 0.4 toward the
 *       player plus a fifth of its speed and 0.4 up; the leap stops its melee goal, which restarts on landing at its
 *       next check with the attack ready.</li>
 *   <li>{@link Kind#BOW}: RangedBowAttackGoal (skeletons, strays, bogged, parched holding a bow): within 15 blocks and
 *       seen for 20 ticks it stops and strafes (0.5 forward or back and 0.5 sideways at a quarter of its speed, facing
 *       the player, turning at most 30 degrees a tick; 30% chances to flip each way every 20 ticks; backing off within
 *       7.5 blocks, never past 13), else it walks at the player. It draws for 20 ticks, shoots an arrow (ArrowSim) at
 *       the player's position then, and waits its interval before drawing again (40 ticks, 20 on hard; 70 and 50 for
 *       the bogged and parched).</li>
 *   <li>{@link Kind#CREEPER}: SwellGoal over the melee goal's walking: with the player within 3 blocks it stops and
 *       swells, a tick each, back down while the player is more than 7 away, and explodes at 30 (Creeper.tick runs the
 *       swell before the goals). Its melee goal's hit does nothing (Creeper.doHurtTarget).</li>
 * </ul>
 * Walking: MoveControl calls Mob.setSpeed, which sets zza to the speed (0.23 for a zombie, 0.345 a baby) after
 * LivingEntity.aiStep's applyInput has scaled the last tick's by 0.98, so the 0.98 never reaches the move; pushed by
 * zza * speed * 0.216 / f^3 on the ground (f = 0.6), 0.02 * zza in the air; drag f * 0.91 after a move that started on
 * the ground, 0.91 in the air, (vy - 0.08) * 0.98 vertically (LivingEntity.travelInAir). A zombie walks 0.1165 blocks a
 * tick (as the recorded real ones do).
 * Hit by the player: knocked back (LivingEntity.knockback: half its velocity plus the push, 0.3608 up from the ground),
 * knockback resistance at its worst case, a full hit again only 10 ticks later (LivingEntity.hurtServer).
 * What the client shows of it (its position a few ticks ago, {@link #seen}) is kept apart: the kill aura aims by that.
 */
public final class MobSim {
    /** The goal that drives it. */
    public enum Kind { MELEE, LEAPER, BOW, CREEPER, CROSSBOW, WITCH, SLIME, BLAZE, TRIDENT, PHANTOM, BREEZE, EVOKER, VEX }

    public Kind kind = Kind.MELEE;
    public double x, y, z, vx, vy = -0.0784000015258789, vz;
    public boolean onGround = true;
    /** The ground height it walks on (no terrain for mobs). */
    public double floorY;
    public double width = 0.6, height = 1.95, eyeHeight = 1.74;
    /** Movement speed attribute (0.23 adult zombie, 0.345 baby, 0.25 skeleton, 0.3 spider), and the goal's modifier
     *  (MeleeAttackGoal's speedModifier: 1.0, 1.2 for a skeleton's melee goal). */
    public double speed = 0.23, speedModifier = 1.0;
    /** Whether its melee goal keeps on after its path is done (followingTargetEvenIfNotSeen). */
    public boolean follows = false;
    public double health = 20, maxHealth = 20, armour = 2, toughness = 0;
    /** The held weapon's bonus damage against it (Smite on undead and the like), at full charge. */
    public double bonus = 0;
    /** Damage of its hit before the player's armour, difficulty applied. */
    public double attackDamage = 3.0;
    /** What a hit of its costs beyond the damage, in health: effects (wither, poison, hunger). */
    public double hitExtra = 0;
    public double knockbackResistance = 0.05;   // zombies spawn with up to 0.05 (Zombie.finalizeSpawn); the worst case
    /** Its facing (Minecraft yaw), for strafing. */
    public float yRot;

    // ---- the melee goal
    /** Ticks until it may attack again (MeleeAttackGoal.ticksUntilNextAttack), and between its attacks (20; the
     *  brain-driven melee mobs' MeleeAttack cooldown: 40 for an adult hoglin or zoglin, 15 a baby). */
    public int attackCooldown = 0, attackInterval = ATTACK_INTERVAL;
    /** A hoglin's or zoglin's throw on a hit (HoglinBase.throwTarget): its attack knockback, 0 for none. */
    public double throwPower = 0;
    /** Whether its attack goal runs; ticks since the goal last checked whether it can start (it checks at most every
     *  20 ticks, MeleeAttackGoal.canUse). */
    public boolean active = true;
    public int sinceCheck = 20;
    /** Ticks until its path is re-aimed, and the point it walks to. */
    public int pathIn = 0;
    public double pathX, pathZ;
    /** Where the player was when the path was last aimed (MeleeAttackGoal.pathedTarget). */
    public double pathTargetX, pathTargetY, pathTargetZ;
    /** It has a path point (has aimed once since the goal started), and it is walking to it (not done). */
    public boolean hasPath = false, walking = false;

    // ---- the leap (spiders)
    /** In the air from a leap (LeapAtTargetGoal runs until it lands). */
    public boolean leaping = false;

    // ---- the bow (skeleton kin)
    /** Ticks it has drawn its bow (getTicksUsingItem), -1 when not drawing. */
    public int drawTicks = -1;
    /** RangedBowAttackGoal.attackTime: counts down while not drawing; it draws when it reaches 0. */
    public int attackTime = -1;
    /** Ticks between a shot and the next draw (40, 20 on hard; 70 and 50 for the bogged and parched). */
    public int bowInterval = 40;
    /** RangedBowAttackGoal.seeTime, strafingTime, strafingClockwise, strafingBackwards. */
    public int seeTime = 20, strafeTime = -1;
    public boolean strafeCW, strafeBack;
    /** Its arrows' base damage (pessimistic: the top of the game's random range) and what a hit costs beyond the
     *  damage (the stray's slowness, the bogged's poison, the parched's weakness, a wither skeleton's fire). */
    public double arrowBase = 2.794, arrowExtra = 0;

    // ---- the crossbow (pillagers): RangedCrossbowAttackGoal's state (0 uncharged, 1 charging, 2 charged, 3 ready),
    // its delay after charging (20 to 39 ticks, random), and the charge's length (25 ticks)
    public int xbow = 0, attackDelay = 0, chargeTicks = 25;

    // ---- slimes and magma cubes: ticks until the next hop (SlimeMoveControl.jumpDelay), the hop's speed up, whether
    // it hurts on touch (not a tiny slime), a magma cube's longer waits, and this tick's hop
    public int jumpDelay = 0;
    public double jumpPower = 0.42F;
    public boolean touchDamage = true, magma = false;
    boolean hopNow = false;
    /** Whether its next server tick is a full goal tick (Mob.serverAiStep: every other tick, by tick count and id). */
    public boolean fullTick = true;
    double hopSpeed = -1;
    public int slimeSize = 1;

    /** RangedAttackGoal's interval (its min and max are equal for both users): a witch's 60, a drowned's trident 40. */
    public int rangedInterval = 60;

    // ---- the phantom: whether it swoops at the player (else it circles its anchor high above) and its move control's
    // speed (0.1 to 1.8)
    public boolean swoop = true;
    public double flySpeed = 0.1;

    /** An evoker: ticks until the fangs of the spell it is casting, -1 for none. */
    public int castIn = -1;

    /** A breeze: ticks until its wind charge (its charge of 15 ticks in the SHOOTING pose), -1 for none in sight. */
    public int shootIn = -1;

    // ---- the blaze: BlazeAttackGoal's attackStep (1 charging, 2 to 4 a fireball each, then a rest; attackTime holds
    // the ticks to the next step) and whether it is charged (its synced flag)
    public int blazeStep = 0;
    public boolean charged = false;

    // ---- the creeper
    /** Creeper.swell (0 to maxSwell), swellDir (+1 or -1), whether SwellGoal runs, the blast radius (3, 6 powered). */
    public int swell = 0, swellDir = -1, maxSwell = 30;
    public boolean swelling = false;
    public double blastRadius = 3.0;
    /** It exploded (it is dead then). */
    public boolean exploded = false;

    // ---- this tick's movement (MoveControl): none, toward the path point, or strafing
    static final int OP_NONE = 0, OP_MOVE = 1, OP_STRAFE = 2, OP_HOP = 3;
    int op = OP_NONE;
    float strafeF, strafeR;

    /** The 10-tick window after a full hit: ticks until it takes a full hit again, and the biggest hit in it. */
    public int freshIn = 0;
    public double lastHurt = 0;
    public boolean alive = true;
    /** Its last tick's move (what the client sees as x - xo). */
    public double lastDx, lastDz;
    /** Client entity id, for matching (not used by the simulation). */
    public int id;

    // What the client shows of it, which the kill aura aims by: its position `delay` ticks ago (the drawn position
    // trails the server by about 3.7 ticks in a lockstep test, plus the network's lag). Before the snapshot that is the
    // drawn position then, carried on at the velocity seen; after it, the simulation's own past positions.
    public double drawnX, drawnY, drawnZ, seenVx, seenVz;
    public int delay = 0;
    private int age = 0;
    private double[] hist = new double[48];   // x, y, z for each tick since the snapshot (16 ticks)

    public static final double REACH = Math.sqrt(2.04F) - 0.6F;   // Mob.DEFAULT_ATTACK_REACH
    public static final int ATTACK_INTERVAL = 20;
    static final double GROUND_FRICTION = 0.6F * 0.91F, AIR_FRICTION = 0.91F;
    /** How far the player must be for a swelling creeper to turn back: 7 blocks (SwellGoal) and a margin for the
     *  copy's earlier stop. */
    public static final double SWELL_BACK = 7.15;
    /** RangedBowAttackGoal's radius for skeletons (15 blocks), squared. */
    static final double BOW_RADIUS_SQR = 15.0F * 15.0F;

    public MobSim copy() {
        MobSim m = new MobSim();
        m.kind = kind;
        m.x = x; m.y = y; m.z = z; m.vx = vx; m.vy = vy; m.vz = vz; m.onGround = onGround; m.floorY = floorY;
        m.width = width; m.height = height; m.eyeHeight = eyeHeight; m.speed = speed; m.speedModifier = speedModifier;
        m.follows = follows; m.health = health; m.maxHealth = maxHealth; m.armour = armour; m.toughness = toughness;
        m.bonus = bonus; m.attackDamage = attackDamage; m.hitExtra = hitExtra; m.knockbackResistance = knockbackResistance;
        m.yRot = yRot;
        m.attackCooldown = attackCooldown; m.attackInterval = attackInterval; m.throwPower = throwPower; m.active = active; m.sinceCheck = sinceCheck; m.pathIn = pathIn;
        m.pathX = pathX; m.pathZ = pathZ; m.pathTargetX = pathTargetX; m.pathTargetY = pathTargetY;
        m.pathTargetZ = pathTargetZ; m.hasPath = hasPath; m.walking = walking;
        m.leaping = leaping;
        m.drawTicks = drawTicks; m.attackTime = attackTime; m.bowInterval = bowInterval; m.seeTime = seeTime;
        m.strafeTime = strafeTime; m.strafeCW = strafeCW; m.strafeBack = strafeBack; m.arrowBase = arrowBase;
        m.arrowExtra = arrowExtra;
        m.xbow = xbow; m.attackDelay = attackDelay; m.chargeTicks = chargeTicks;
        m.jumpDelay = jumpDelay; m.jumpPower = jumpPower; m.touchDamage = touchDamage; m.magma = magma; m.hopNow = hopNow; m.hopSpeed = hopSpeed; m.slimeSize = slimeSize; m.fullTick = fullTick;
        m.blazeStep = blazeStep; m.charged = charged; m.rangedInterval = rangedInterval;
        m.swoop = swoop; m.flySpeed = flySpeed; m.shootIn = shootIn; m.castIn = castIn;
        m.swell = swell; m.swellDir = swellDir; m.maxSwell = maxSwell; m.swelling = swelling; m.blastRadius = blastRadius;
        m.exploded = exploded;
        m.op = op; m.strafeF = strafeF; m.strafeR = strafeR;
        m.freshIn = freshIn; m.lastHurt = lastHurt; m.alive = alive; m.id = id; m.lastDx = lastDx; m.lastDz = lastDz;
        m.drawnX = drawnX; m.drawnY = drawnY; m.drawnZ = drawnZ; m.seenVx = seenVx; m.seenVz = seenVz; m.delay = delay;
        m.age = age; m.hist = hist.clone();
        return m;
    }

    /** Where the client shows it now, {x, y, z}: what the kill aura sees. */
    public double[] seen() {
        if (delay <= 0) return new double[]{x, y, z};
        int back = age - delay;   // the simulation tick whose position shows now (0 = the snapshot's)
        if (back <= 0) return new double[]{drawnX + seenVx * age, drawnY, drawnZ + seenVz * age};
        int i = ((back - 1) % 16) * 3;
        return new double[]{hist[i], hist[i + 1], hist[i + 2]};
    }

    /** How far the shown position moved in the last tick, {dx, dz} (the client's x - xo). */
    public double[] seenStep() {
        if (delay <= 0) return new double[]{lastDx, lastDz};
        int back = age - delay;
        if (back <= 0) return new double[]{seenVx, seenVz};
        int i = ((back - 1) % 16) * 3;
        double px, pz;
        if (back == 1) { px = drawnX + seenVx * delay; pz = drawnZ + seenVz * delay; }
        else { int j = ((back - 2) % 16) * 3; px = hist[j]; pz = hist[j + 2]; }
        return new double[]{hist[i] - px, hist[i + 2] - pz};
    }

    /** Whether its melee reaches a player box (minX, minY, minZ, maxX, maxY, maxZ): its own box grown sideways by the
     *  reach meets the player's hitbox (AABB.intersects, strict). */
    public boolean reaches(double[] pb) {
        double hw = width / 2 + REACH;
        return x - hw < pb[3] && x + hw > pb[0] && y < pb[4] && y + height > pb[1] && z - hw < pb[5] && z + hw > pb[2];
    }

    /** Whether it hurts in melee at all (a creeper's hit does nothing; a skeleton with a bow doesn't melee). */
    public boolean melee() {
        return kind == Kind.MELEE || kind == Kind.LEAPER || kind == Kind.SLIME && touchDamage;
    }

    /** Blocks a tick it walks at full speed on the ground (push / (1 - drag)). */
    public double walkSpeed() {
        if (kind == Kind.VEX) return 0.55;   // 0.05 a tick against x 0.91: about 0.55 at full speed
        if (kind == Kind.PHANTOM) return Math.max(0.3, flySpeed * 0.67);   // its velocity settles at 0.67 of the wanted
        if (kind == Kind.SLIME) {
            if (hopSpeed < 0) hopSpeed = hopSpeed(speed, jumpPower, minJumpDelay());
            return hopSpeed;
        }
        double sp = speed * speedModifier;
        return sp * sp / (1.0 - GROUND_FRICTION);
    }

    /**
     * The melee goal for one server tick (MeleeAttackGoal.tick), and a spider's leap: its cooldown, an attack if it
     * can, and its path toward the player. Returns true if it attacks now. Runs before it moves, after the player's
     * move of the same tick.
     */
    public boolean think(double px, double py, double pz, double[] playerBox, SplittableRandom rnd) {
        if (!alive) return false;
        sinceCheck++;
        if (kind == Kind.LEAPER && leap(px, py, pz, rnd)) return false;
        if (!active) {
            op = OP_NONE;
            if (sinceCheck < 20) return false;   // MeleeAttackGoal.canUse runs at most every 20 ticks
            sinceCheck = 0;
            active = true;                       // start(): the path at once, the attack ready
            attackCooldown = 0;
            pathIn = 0;
            hasPath = false;
        }
        if (pathIn > 0) pathIn--;
        if (pathIn <= 0 && (!hasPath || sq(px - pathTargetX) + sq(py - pathTargetY) + sq(pz - pathTargetZ) >= 1.0
                            || rnd.nextFloat() < 0.05F)) {
            pathTargetX = px; pathTargetY = py; pathTargetZ = pz;
            pathX = Math.floor(px) + 0.5; pathZ = Math.floor(pz) + 0.5;   // the path ends at the centre of the player's block
            hasPath = true;
            walking = true;
            pathIn = 4 + rnd.nextInt(7);
        }
        op = walking ? OP_MOVE : OP_NONE;
        if (attackCooldown > 0) attackCooldown--;
        if (attackCooldown <= 0 && reaches(playerBox)) {
            attackCooldown = attackInterval;
            return true;
        }
        return false;
    }

    /** LeapAtTargetGoal: in the air from a leap the goal runs on (no other goal moves it); on the ground with the
     *  player 2 to 4 blocks away (distanceToSqr 4 to 16) it starts on a third of its checks, which come every other
     *  tick: a sixth of the ticks. Returns true while it leaps. */
    private boolean leap(double px, double py, double pz, SplittableRandom rnd) {
        if (leaping) {
            if (!onGround) { op = walking ? OP_MOVE : OP_NONE; return true; }
            leaping = false;   // landed: the melee goal starts again at its next check
            walking = false;
        }
        double d2 = sq(px - x) + sq(py - y) + sq(pz - z);
        if (!onGround || d2 < 4.0 || d2 > 16.0 || rnd.nextInt(6) != 0) return false;
        double dx = px - x, dz = pz - z, d = Math.sqrt(dx * dx + dz * dz);
        if (d * d > 1.0E-7) {
            vx = dx / d * 0.4 + vx * 0.2;
            vz = dz / d * 0.4 + vz * 0.2;
        } else {
            vx = 0; vz = 0;
        }
        vy = 0.4F;
        leaping = true;
        active = false;   // the leap took the MOVE flag: the melee goal stopped
        // Yet real spiders often keep their walking input through the leap: in the lockstep trace the leap tick's move
        // held a full ground push (0.09) beyond the goal's velocity in two of three leaps, and 0.02 x speed a tick in
        // the air after. Kept (the pessimistic side: the leap carries further): walking on at the player.
        pathX = px; pathZ = pz;
        walking = true;
        op = OP_MOVE;
        return true;
    }

    /**
     * RangedBowAttackGoal for one server tick, after the draw counted on (LivingEntity.tick runs updatingUsingItem
     * before the goals): where it moves this tick, and whether it shoots now. The arrow is the world's to make.
     */
    public boolean thinkBow(double px, double py, double pz, SplittableRandom rnd) {
        if (!alive) return false;
        if (drawTicks >= 0) drawTicks++;
        double d2 = sq(px - x) + sq(py - y) + sq(pz - z);
        seeTime = Math.max(seeTime, 0) + 1;   // in sight (no terrain for mobs)
        if (d2 <= BOW_RADIUS_SQR && seeTime >= 20) {
            op = OP_NONE;
            strafeTime++;
        } else {
            pathX = px; pathZ = pz;   // navigation.moveTo(target): straight at the player
            walking = true;
            op = OP_MOVE;
            strafeTime = -1;
        }
        if (strafeTime >= 20) {
            if (rnd.nextFloat() < 0.3F) strafeCW = !strafeCW;
            if (rnd.nextFloat() < 0.3F) strafeBack = !strafeBack;
            strafeTime = 0;
        }
        if (strafeTime > -1) {
            if (d2 > BOW_RADIUS_SQR * 0.75F) strafeBack = false;
            else if (d2 < BOW_RADIUS_SQR * 0.25F) strafeBack = true;
            op = OP_STRAFE;
            strafeF = strafeBack ? -0.5F : 0.5F;
            strafeR = strafeCW ? 0.5F : -0.5F;
            yRot = rotlerp(yRot, (float) AuraSim.yawTo(x, z, px, pz), 30.0F);   // lookAt(target, 30, 30)
        }
        if (drawTicks >= 0) {
            if (drawTicks >= 20) {
                drawTicks = -1;
                attackTime = bowInterval;
                return true;
            }
        } else if (--attackTime <= 0 && seeTime >= -60) {
            drawTicks = 0;
        }
        return false;
    }

    /**
     * RangedCrossbowAttackGoal for one server tick (pillagers), after the charge counted on: it walks at the player
     * (half speed once it has begun to charge) until within 8 blocks and seen for 5 ticks, then stands; charges for
     * 25 ticks, waits 20 to 39 (random), and shoots. Returns true if it shoots now.
     */
    public boolean thinkCrossbow(double px, double py, double pz, SplittableRandom rnd) {
        if (!alive) return false;
        if (xbow == 1) drawTicks++;
        seeTime = Math.max(seeTime, 0) + 1;
        double d2 = sq(px - x) + sq(py - y) + sq(pz - z);
        boolean needsToMove = (d2 > 64.0 || seeTime < 5) && attackDelay == 0;
        if (needsToMove) {
            pathX = px; pathZ = pz;
            walking = true;
            op = OP_MOVE;
            speedModifier = xbow == 0 ? 1.0 : 0.5;   // canRun: only while uncharged
        } else {
            walking = false;
            op = OP_NONE;
        }
        switch (xbow) {
            case 0 -> { if (!needsToMove) { xbow = 1; drawTicks = 0; } }
            case 1 -> {
                if (drawTicks >= chargeTicks) { xbow = 2; drawTicks = -1; attackDelay = 20 + rnd.nextInt(20); }
            }
            case 2 -> { if (--attackDelay <= 0) { attackDelay = 0; xbow = 3; } }
            default -> { xbow = 0; return true; }   // ready, in sight: performCrossbowAttack
        }
        return false;
    }

    /**
     * A witch's RangedAttackGoal(1.0, 60, 10) for one server tick: it walks at the player until within 10 blocks and
     * seen for 5 ticks, then stands; it throws a splash potion every 60 ticks, the first 60 after its goal starts.
     * Returns true if it throws now.
     */
    public boolean thinkWitch(double px, double py, double pz) {
        if (!alive) return false;
        seeTime = Math.max(seeTime, 0) + 1;
        double d2 = sq(px - x) + sq(py - y) + sq(pz - z);
        if (d2 <= 100.0 && seeTime >= 5) {
            walking = false;
            op = OP_NONE;
        } else {
            pathX = px; pathZ = pz;
            walking = true;
            op = OP_MOVE;
        }
        if (--attackTime == 0) { attackTime = rangedInterval; return true; }
        if (attackTime < 0) attackTime = rangedInterval;
        return false;
    }

    /**
     * A phantom for one server tick: PhantomSweepAttackGoal aims it at the player's middle and hits when its box grown
     * by 0.2 meets the player's, after which it goes back to circling (as when it is hurt); circling, it heads up to
     * its anchor, 20 or more above the player. PhantomMoveControl: the yaw turns at most 4 degrees a tick toward the
     * target, the speed rises toward 1.8 while the turn is under 3 degrees and falls toward 0.2 otherwise, and the
     * velocity goes a fifth of the way to the wanted one. The goal's check runs every other tick in the game; the
     * copy checks every tick (the worse). Returns true for a hit.
     */
    public boolean thinkPhantom(double px, double py, double pz, double[] pb) {
        if (!alive) return false;
        if (Math.abs(vx) < 0.003) vx = 0;   // LivingEntity.aiStep, before the goals and the move control
        if (Math.abs(vy) < 0.003) vy = 0;
        if (Math.abs(vz) < 0.003) vz = 0;
        boolean hit = false;
        if (swoop) {
            double hw = width / 2 + 0.2;
            if (x - hw < pb[3] && x + hw > pb[0] && y - 0.2 < pb[4] && y + height + 0.2 > pb[1] && z - hw < pb[5] && z + hw > pb[2]) {
                hit = true;
                swoop = false;
            }
        }
        double tx = px, ty = swoop ? py + 0.9 : py + 25.0, tz = pz;
        if (!swoop) { tx = x + (x - px); tz = z + (z - pz); }   // away and up, toward its anchor
        double tdx = tx - x, tdy = ty - y, tdz = tz - z;
        double sd = Math.sqrt(tdx * tdx + tdz * tdz);
        op = OP_NONE;
        if (sd > 1.0E-5F) {
            double scale = 1.0 - Math.abs(tdy * 0.7F) / sd;
            tdx *= scale; tdz *= scale;
            sd = Math.sqrt(tdx * tdx + tdz * tdz);
            double sd2 = Math.sqrt(tdx * tdx + tdz * tdz + tdy * tdy);
            float prev = yRot;
            float angle = (float) Math.atan2(tdz, tdx);
            float a = ai.rrw.kine.move.GameMath.wrapDegrees(yRot + 90.0F);
            float b = ai.rrw.kine.move.GameMath.wrapDegrees(angle * (180.0F / (float) Math.PI));
            yRot = approach(a, a + ai.rrw.kine.move.GameMath.wrapDegrees(b - a), 4.0F) - 90.0F;
            if (Math.abs(ai.rrw.kine.move.GameMath.wrapDegrees(yRot - prev)) < 3.0F)
                flySpeed = approach((float) flySpeed, 1.8F, 0.005F * (1.8F / (float) flySpeed));
            else
                flySpeed = approach((float) flySpeed, 0.2F, 0.025F);
            float xRot = (float) (-(Math.atan2(-tdy, sd) * 180.0F / (float) Math.PI));
            double move = Math.toRadians(yRot + 90.0F);
            double wx = flySpeed * Math.cos(move) * Math.abs(tdx / sd2);
            double wz = flySpeed * Math.sin(move) * Math.abs(tdz / sd2);
            double wy = flySpeed * Math.sin(Math.toRadians(xRot)) * Math.abs(tdy / sd2);
            vx += (wx - vx) * 0.2; vy += (wy - vy) * 0.2; vz += (wz - vz) * 0.2;
        }
        return hit;
    }

    /** Mth.approach(float): from a toward b by at most step. */
    static float approach(float a, float b, float step) {
        step = Math.abs(step);
        return a < b ? Math.min(a + step, b) : Math.max(a - step, b);
    }

    /** An evoker for one server tick: the fangs come when the spell's warmup runs out. It stays where it is (it backs
     *  away from players within 8 blocks in the game). Returns true for the fangs now. */
    public boolean thinkEvoker() {
        if (!alive) return false;
        op = OP_NONE;
        if (castIn < 0) return false;
        if (castIn-- == 0) { castIn = -1; return true; }
        return false;
    }

    /** A vex for one server tick (VexChargeAttackGoal, VexMoveControl), always charging (the worst case): a hit when its
     *  box meets the player's; it speeds up by 0.05 a tick toward the player's eyes, or halves its speed within its
     *  box's size of them. Returns true for a hit. */
    public boolean thinkVex(double px, double py, double pz, double[] pb) {
        if (!alive) return false;
        if (Math.abs(vx) < 0.003) vx = 0;
        if (Math.abs(vy) < 0.003) vy = 0;
        if (Math.abs(vz) < 0.003) vz = 0;
        double hw = width / 2;
        boolean hit = x - hw < pb[3] && x + hw > pb[0] && y < pb[4] && y + height > pb[1] && z - hw < pb[5] && z + hw > pb[2];
        double dx = px - x, dy = py + 1.62 - y, dz = pz - z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double size = (width + height + width) / 3.0;
        op = OP_NONE;
        if (d < size) { vx *= 0.5; vy *= 0.5; vz *= 0.5; }
        else { vx += dx / d * 0.05; vy += dy / d * 0.05; vz += dz / d * 0.05; }
        return hit;
    }

    /** A breeze for one server tick (its brain's Shoot): the wind charge goes when the charge runs out. Its slides and
     *  long jumps are left out: it stays where it is. Returns true for a shot now. */
    public boolean thinkBreeze() {
        if (!alive) return false;
        op = OP_NONE;
        if (shootIn < 0) return false;
        if (shootIn-- == 0) { shootIn = -1; return true; }
        return false;
    }

    /**
     * A blaze's attack goal for one server tick (BlazeAttackGoal.tick), taking it to see the player: within 2 blocks
     * (feet to feet) a hit every 20 ticks; else, within 48, attackTime counts down to each step: charge for 60 ticks,
     * then three fireballs 6 ticks apart, then 100 ticks of rest. Returns 1 for a fireball now, 2 for a hit, else 0.
     * It hovers where it is (its flight, a lift toward the player's height and a slowed fall, is left out).
     */
    public int thinkBlaze(double px, double py, double pz) {
        if (!alive) return 0;
        attackTime--;
        double d2 = sq(px - x) + sq(py - y) + sq(pz - z);
        op = OP_NONE;
        if (d2 < 4.0) {
            if (attackTime <= 0) { attackTime = 20; return 2; }
        } else if (d2 < 48.0 * 48.0 && attackTime <= 0) {
            blazeStep++;
            if (blazeStep == 1) { attackTime = 60; charged = true; }
            else if (blazeStep <= 4) attackTime = 6;
            else { attackTime = 100; blazeStep = 0; charged = false; }
            if (blazeStep > 1) return 1;
        }
        return 0;
    }

    /**
     * A slime's or magma cube's goals for one server tick (SlimeAttackGoal, SlimeMoveControl): it turns toward the
     * player at most 10 degrees a tick (lookAt 10); on the ground it waits out its jump delay, then hops, with a
     * ground push of its speed squared along its facing in the hop tick and a little steering in the air; the delay is
     * 10 to 29 ticks (four times that for a magma cube), a third of it when it hurts on touch.
     */
    public void thinkSlime(double px, double pz, SplittableRandom rnd) {
        if (!alive) return;
        yRot = rotlerp(yRot, (float) AuraSim.yawTo(x, z, px, pz), 10.0F);
        hopNow = false;
        boolean full = fullTick;
        fullTick = !fullTick;
        op = OP_NONE;
        // SlimeKeepOnJumpingGoal asks for the move only on full goal ticks (every other tick, Mob.serverAiStep); on the
        // others the move control sets no input: no push in the air, and the wait on the ground doesn't count down
        if (!full) return;
        op = OP_HOP;
        if (onGround) {
            if (jumpDelay-- <= 0) {
                jumpDelay = (10 + rnd.nextInt(20)) * (magma ? 4 : 1);
                if (touchDamage) jumpDelay /= 3;
                hopNow = true;
            } else {
                op = OP_NONE;   // waiting on the ground: no input
            }
        }
    }

    /** The shortest wait between a slime's hops, in full goal ticks (getJumpDelay at its least, a third when it
     *  hurts); about twice that in ticks on the ground. */
    public int minJumpDelay() {
        return 10 * (magma ? 4 : 1) / (touchDamage ? 3 : 1);
    }

    /** A slime's average speed toward its facing over one hop and its shortest wait, on flat ground (move's physics):
     *  blocks a tick. Size 4: 0.6 speed, about 0.13. */
    static double hopSpeed(double speed, double jumpPower, int wait) {
        MobSim m = new MobSim();
        m.kind = Kind.SLIME; m.speed = speed; m.jumpPower = jumpPower; m.yRot = 0;
        m.onGround = true; m.floorY = 0; m.y = 0; m.vy = -0.0784000015258789;
        int t = 0, delay = 0;
        boolean full = true, hopped = false;
        while (t < 400) {   // thinkSlime's rules, the wait at `wait`, until the second hop
            m.hopNow = false;
            m.op = OP_NONE;
            if (full) {
                m.op = OP_HOP;
                if (m.onGround) {
                    if (delay-- <= 0) {
                        if (hopped) break;
                        delay = wait; m.hopNow = true; hopped = true;
                    } else {
                        m.op = OP_NONE;
                    }
                }
            }
            full = !full;
            m.move();
            t++;
        }
        return m.z / t;
    }

    /** Whether the player's box (minX, minY, minZ, maxX, maxY, maxZ) touches it as Player.touch does (the player's box
     *  grown by 1, 0.5, 1 meets the slime's) and its attack box meets the player's (Slime.dealDamage). */
    public boolean touches(double[] pb) {
        double hw = width / 2;
        boolean touch = x - hw < pb[3] + 1.0 && x + hw > pb[0] - 1.0 && y < pb[4] + 0.5 && y + height > pb[1] - 0.5
            && z - hw < pb[5] + 1.0 && z + hw > pb[2] - 1.0;
        return touch && reaches(pb);
    }

    /** Creeper.tick before its goals: the swell counts on, and at its top the creeper explodes. Returns true if it
     *  explodes now (it is dead then; the world makes the blast). */
    public boolean swellTick() {
        return swellTick(0);
    }

    /** swellTick with the fuse taken as `margin` ticks shorter (a plan's caution). */
    public boolean swellTick(int margin) {
        if (!alive) return false;
        swell += swellDir;
        if (swell < 0) swell = 0;
        if (swell >= maxSwell - margin) {
            swell = maxSwell;
            alive = false;
            exploded = true;
            return true;
        }
        return false;
    }

    /** The creeper's goals after the swell: SwellGoal (priority 2, the MOVE flag) over the melee goal's walking. */
    public void thinkCreeper(double px, double py, double pz, double[] playerBox, SplittableRandom rnd) {
        thinkCreeper(px, py, pz, playerBox, rnd, SWELL_BACK);
    }

    /** thinkCreeper with the distance its fuse turns back beyond taken as `back` (a plan's caution). */
    public void thinkCreeper(double px, double py, double pz, double[] playerBox, SplittableRandom rnd, double back) {
        if (!alive) return;
        double d2 = sq(px - x) + sq(py - y) + sq(pz - z);
        if (swelling && !(swellDir > 0 || d2 < 9.0)) swelling = false;   // canContinueToUse: its canUse
        if (!swelling && (swellDir > 0 || d2 < 9.0)) {
            swelling = true;      // start(): navigation stops; the melee goal loses the MOVE flag and stops
            active = false;
            walking = false;
        }
        if (swelling) {
            // tick(): back down beyond 7 blocks (in sight: no terrain for mobs). Taken 0.15 further (SWELL_BACK): the
            // real creeper starts up to a tick later than the copy (its goals pick on alternate ticks), so it stands up
            // to a tick's walk (0.14) nearer the player; in the lockstep trace a copy that turned back at 7 missed a
            // real blast with the player 6.96 away
            swellDir = d2 > back * back ? -1 : 1;
            op = OP_NONE;
            sinceCheck++;
            return;
        }
        think(px, py, pz, playerBox, rnd);   // walks at the player; its hit does nothing (Creeper.doHurtTarget)
    }

    /** One tick of its movement (MoveControl, then LivingEntity.travelInAir on flat ground). */
    public void move() {
        if (!alive) { record(); return; }
        if (kind == Kind.VEX) {   // no gravity, through blocks; x 0.91 sideways, x 0.98 up and down (travelInAir)
            x += vx; y += vy; z += vz;
            lastDx = vx; lastDz = vz;
            vx *= AIR_FRICTION; vy *= 0.98F; vz *= AIR_FRICTION;
            record();
            return;
        }
        if (kind == Kind.PHANTOM) {   // travelFlying: the move, then x 0.91 on every axis; no gravity
            x += vx; y += vy; z += vz;
            if (y < floorY) { y = floorY; vy = 0; }
            lastDx = vx; lastDz = vz;
            vx *= AIR_FRICTION; vy *= AIR_FRICTION; vz *= AIR_FRICTION;
            record();
            return;
        }
        if (kind == Kind.BLAZE || kind == Kind.BREEZE || kind == Kind.EVOKER) {   // hovering, or standing: only a knockback carries it
            x += vx; z += vz;
            lastDx = vx; lastDz = vz;
            vx *= AIR_FRICTION; vz *= AIR_FRICTION;
            record();
            return;
        }
        double ax = 0, az = 0;   // this tick's push, before the friction factor
        boolean wasOnGround = onGround;
        if (op == OP_MOVE && walking) {
            double dx = pathX - x, dz = pathZ - z;
            // GroundPathNavigation: a node is reached within this on x and on z
            double stop = width > 0.75F ? width / 2 : 0.75F - width / 2;
            if (Math.abs(dx) < stop && Math.abs(dz) < stop && kind != Kind.CROSSBOW && kind != Kind.BOW && kind != Kind.WITCH && kind != Kind.TRIDENT) {
                walking = false;                                         // the path is done
                if (melee() || kind == Kind.CREEPER) { if (!follows) active = false; }   // the goal stops (starts again on its next check)
            } else {
                double d = Math.sqrt(dx * dx + dz * dz);
                double sp = speed * speedModifier;
                double push = wasOnGround ? sp * sp * (0.21600002F / (0.6F * 0.6F * 0.6F)) : sp * 0.02F;
                ax = dx / d * push; az = dz / d * push;
                yRot = (float) AuraSim.yawTo(x, z, pathX, pathZ);
            }
        } else if (op == OP_HOP) {
            // SlimeMoveControl: setSpeed(speed) (zza = speed) in the hop tick on the ground and while in the air
            double scale = wasOnGround ? speed * (0.21600002F / (0.6F * 0.6F * 0.6F)) : 0.02F;
            double r = Math.toRadians(yRot);
            ax = -Math.sin(r) * speed * scale;
            az = Math.cos(r) * speed * scale;
            if (hopNow) { vy = jumpPower; }
        } else if (op == OP_STRAFE) {
            // MoveControl.strafe: setSpeed(0.25 * speed), zza and xxa the strafe's; the input (xxa, 0, zza), under
            // length 1 so not normalised, scaled by the speed and turned by the facing (Entity.getInputVector)
            double sp = 0.25 * speed;
            double scale = wasOnGround ? sp * (0.21600002F / (0.6F * 0.6F * 0.6F)) : 0.02F;
            double r = Math.toRadians(yRot), sin = Math.sin(r), cos = Math.cos(r);
            double xa = strafeR * scale, za = strafeF * scale;
            ax = xa * cos - za * sin;
            az = za * cos + xa * sin;
        }
        if (Math.abs(vx) < 0.003) vx = 0;
        if (Math.abs(vz) < 0.003) vz = 0;
        if (Math.abs(vy) < 0.003) vy = 0;
        // friction and push are the ground's when it stood on the ground before this move (LivingEntity.travelInAir)
        vx += ax;
        vz += az;
        x += vx;
        z += vz;
        lastDx = vx; lastDz = vz;
        if (y + vy <= floorY) {   // lands (or stays) on its floor: the fall is cut and vy zeroed (updateEntityMovementAfterFallOn)
            y = floorY;
            onGround = true;
            vy = 0;
        } else {
            y += vy;
            onGround = false;
        }
        double f = wasOnGround ? GROUND_FRICTION : AIR_FRICTION;
        vx *= f;
        vz *= f;
        vy = (vy - 0.08) * 0.98F;
        record();
    }

    /** Note this tick's position for what the client will show later. */
    private void record() {
        int i = (age % 16) * 3;
        hist[i] = x; hist[i + 1] = y; hist[i + 2] = z;
        age++;
    }

    /** LivingEntity.knockback(power, xd, zd), with (xd, zd) pointing from the mob toward the attacker. */
    public void knockback(double power, double xd, double zd) {
        power *= 1.0 - knockbackResistance;
        if (power <= 0) return;
        double d = Math.sqrt(xd * xd + zd * zd);
        if (d < 1e-4) { xd = 0.01; zd = 0; d = 0.01; }
        double kx = xd / d * power, kz = zd / d * power;
        vx = vx / 2.0 - kx;
        vz = vz / 2.0 - kz;
        if (onGround) vy = Math.min(0.4, vy / 2.0 + power);
    }

    /** A tick passes for its hurt window. */
    public void tickWindow() {
        if (freshIn > 0) freshIn--;
    }

    /** MoveControl.rotlerp: turn from a toward b by at most max degrees. */
    static float rotlerp(float a, float b, float max) {
        float diff = ai.rrw.kine.move.GameMath.wrapDegrees(b - a);
        if (diff > max) diff = max;
        if (diff < -max) diff = -max;
        return a + diff;
    }

    static double sq(double v) { return v * v; }
}
