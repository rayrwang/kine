package ai.rrw.kine.fight;

import java.util.SplittableRandom;

/**
 * One mob in the fight simulation. Version 1 models the zombie and its plain variants (husk, zombie villager, drowned
 * without a trident, babies), from their 26.1.2 goal code, simplified and on the pessimistic side where the client
 * can't know the state:
 * <ul>
 *   <li>Its target is the player (ZombieAttackGoal, MeleeAttackGoal with speed modifier 1.0). It re-aims its path at the
 *       centre of the player's block every 4 to 10 ticks (random, MeleeAttackGoal.tick) and walks straight there on flat
 *       ground (no terrain for mobs in version 1). When it gets within 0.45 of that point on x and z the path is done
 *       (GroundPathNavigation) and the goal stops (canContinueToUse with followingTargetEvenIfNotSeen false); it starts
 *       again at its next check, at most every 20 ticks (canUse), with its attack ready (start). That is the real
 *       11-tick second hit on a player standing still.</li>
 *   <li>Walking: Mob.setSpeed sets zza to the speed (0.23, babies 0.345), scaled by 0.98 (LivingEntity.applyInput),
 *       pushed by zza * speed * 0.216 / f^3 on the ground (f = 0.6), 0.02 * zza in the air; drag f * 0.91 after a move
 *       that started on the ground, 0.91 in the air, (vy - 0.08) * 0.98 vertically (LivingEntity.travelInAir). About
 *       0.114 blocks a tick.</li>
 *   <li>Attack: when its cooldown is out and the player's box meets its own box grown by sqrt(2.04) - 0.6 sideways
 *       (Mob.isWithinMeleeAttackRange), it hits and waits 20 ticks (MeleeAttackGoal.checkAndPerformAttack). The
 *       cooldown is read from the swing the client sees (the server sends the swing), else assumed ready.</li>
 *   <li>Hit by the player: knocked back (LivingEntity.knockback: half its velocity plus the push, 0.3608 up from the
 *       ground), knockback resistance taken at its worst case 0.05, and a full hit again only 10 ticks later
 *       (LivingEntity.hurtServer).</li>
 *   <li>What the client shows of it (its position a few ticks ago, {@link #seen}) is kept apart: the kill aura aims by
 *       that.</li>
 * </ul>
 * Checked against real zombies in a lockstep test (ZombieModelTest).
 */
public final class MobSim {
    public double x, y, z, vx, vy = -0.0784000015258789, vz;
    public boolean onGround = true;
    /** The ground height it walks on (no terrain for mobs in version 1). */
    public double floorY;
    public double width = 0.6, height = 1.95;
    /** Movement speed attribute (0.23 adult, 0.345 baby). */
    public double speed = 0.23;
    public double health = 20, armour = 2, toughness = 0;
    /** The held weapon's bonus damage against it (Smite on undead and the like), at full charge. */
    public double bonus = 0;
    /** Damage of its hit before the player's armour, difficulty applied. */
    public double attackDamage = 3.0;
    public double knockbackResistance = 0.05;   // zombies spawn with up to 0.05 (Zombie.finalizeSpawn); the worst case
    /** Ticks until it may attack again (MeleeAttackGoal.ticksUntilNextAttack). */
    public int attackCooldown = 0;
    /** Whether its attack goal runs; ticks since the goal last checked whether it can start (it checks at most every
     *  20 ticks, MeleeAttackGoal.canUse). The goal stops when its path is done (it reached the point it walked to,
     *  canContinueToUse with followingTargetEvenIfNotSeen false) and starts again with its attack ready. */
    public boolean active = true;
    public int sinceCheck = 20;
    /** Ticks until its path is re-aimed, and the point it walks to. */
    public int pathIn = 0;
    public double pathX, pathZ;
    /** Where the player was when the path was last aimed (MeleeAttackGoal.pathedTarget). */
    public double pathTargetX, pathTargetY, pathTargetZ;
    public boolean hasPath = false;
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

    public MobSim copy() {
        MobSim m = new MobSim();
        m.x = x; m.y = y; m.z = z; m.vx = vx; m.vy = vy; m.vz = vz; m.onGround = onGround; m.floorY = floorY;
        m.width = width; m.height = height; m.speed = speed; m.health = health; m.armour = armour; m.toughness = toughness;
        m.bonus = bonus; m.attackDamage = attackDamage; m.knockbackResistance = knockbackResistance;
        m.attackCooldown = attackCooldown; m.active = active; m.sinceCheck = sinceCheck; m.pathIn = pathIn; m.pathX = pathX; m.pathZ = pathZ; m.pathTargetX = pathTargetX; m.pathTargetY = pathTargetY; m.pathTargetZ = pathTargetZ; m.hasPath = hasPath;
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

    /**
     * The mob's AI for one server tick (MeleeAttackGoal.tick): its cooldown, an attack if it can, and its path toward
     * the player. Returns true if it attacks now. Runs before it moves, after the player's move of the same tick.
     */
    public boolean think(double px, double py, double pz, double[] playerBox, SplittableRandom rnd) {
        if (!alive) return false;
        sinceCheck++;
        if (!active) {
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
            pathIn = 4 + rnd.nextInt(7);
        }
        if (attackCooldown > 0) attackCooldown--;
        if (attackCooldown <= 0 && reaches(playerBox)) {
            attackCooldown = ATTACK_INTERVAL;
            return true;
        }
        return false;
    }

    /** One tick of its movement (MoveControl toward the path point, then LivingEntity.travelInAir on flat ground). */
    public void move() {
        if (!alive) { record(); return; }
        double zza = 0;
        double dirX = 0, dirZ = 0;
        if (active && hasPath) {
            double dx = pathX - x, dz = pathZ - z;
            double stop = 0.75 - width / 2;   // GroundPathNavigation: a node is reached within this on x and on z
            if (Math.abs(dx) < stop && Math.abs(dz) < stop) {
                active = false;                // the path is done: the goal stops (it starts again on its next check)
            } else {
                double d = Math.sqrt(dx * dx + dz * dz);
                zza = speed * 0.98F;
                dirX = dx / d; dirZ = dz / d;
            }
        }
        if (Math.abs(vx) < 0.003) vx = 0;
        if (Math.abs(vz) < 0.003) vz = 0;
        if (Math.abs(vy) < 0.003) vy = 0;
        // friction and push are the ground's when it stood on the ground before this move (LivingEntity.travelInAir)
        boolean wasOnGround = onGround;
        double push = wasOnGround ? zza * speed * (0.21600002F / (0.6F * 0.6F * 0.6F)) : zza * 0.02F;
        vx += dirX * push;
        vz += dirZ * push;
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

    static double sq(double v) { return v * v; }
}
