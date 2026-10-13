package ai.rrw.kine.fight;

import ai.rrw.kine.combat.SwingPlan;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;

import java.util.SplittableRandom;

/**
 * The simulated fight: the player (its exact movement state, health, armour, its own hurt window), the mobs, the
 * projectiles in flight, the kill aura when it is on, and the server's copy of the player's velocity (a mob's
 * knockback is computed from it). One {@link #tick} plays a client tick and the server tick after it, in the game's
 * order:
 * <ol>
 *   <li>a knockback or blast push that has reached the client takes effect;</li>
 *   <li>the kill aura swings or turns (before handleKeybinds), setting the rotation sent;</li>
 *   <li>mobs touching the player push it (their client copies tick before or with it);</li>
 *   <li>the player moves under the keys, by the exact copy (GroundPhysics), at the rotation sent;</li>
 *   <li>the server: the player's hurt window counts down; each mob's goal runs (a melee hit hurts the player through
 *       armour and its window and sets its velocity by knockback, as the server's motion packet does before the next
 *       client tick; a skeleton's shot makes an arrow; a creeper's swell runs first and may end in a blast), then it
 *       moves; then each projectile flies and may hit the player; the charge counts on.</li>
 * </ol>
 */
public final class FightWorld {
    public MoveState player;
    public double health = 20, absorption = 0;
    /** The player's max health (the health weight grows as health drops toward 0). */
    public double maxHealth = 20;
    public double armour = 0, toughness = 0;
    /** Damage taken is multiplied by this after armour (Resistance: 1 - 0.2 per level). */
    public double resistance = 1.0;
    /** The game's difficulty id (0 peaceful, 1 easy, 2 normal, 3 hard): arrows' spread and the damage scaling. */
    public int difficulty = 2;
    /** The player's own hurt window (invulnerableTime; full hits only at 10 and below) and the biggest hit in it. */
    public int invulnerable = 0;
    public double lastHurt = 0;
    /** A knockback on its way: the velocity the server's motion packet will set, and the ticks until the client's
     *  tick starts with it (measured in the lockstep zombie test: the client tick after next). */
    public double kbx, kby, kbz;
    public int kbIn = 0;
    /** A blast's push on its way (the explode packet adds it to the client's velocity), and the ticks until it does. */
    public double pushX, pushY, pushZ;
    public int pushIn = 0;
    /** The player's last move, as the client sees it (x - xo). */
    public double pdx, pdz;
    public MobSim[] mobs;
    public ArrowSim[] arrows = new ArrowSim[0];
    /** Evoker fangs: where each stands and the ticks until it bites (EvokerFangs: 8 after its warmup), -1 once bitten. */
    public double[][] fangs = new double[0][];
    public AuraSim aura;
    /** Health taken off mobs and off the player so far (the player's in cost: effects of hits included), and ticks. */
    public double dealt, taken;
    public int ticks;
    /** What hits this tick cost beyond their damage (effects), in health: read by the model after each tick. */
    public double extraNow;
    /** Ticks since a mob in the fight last lost health, at the snapshot (the idle cost grows with it). */
    public int lull;
    /** The ticks of a creeper's fuse a plan wants left over when it has walked out of it (FightModel.copy sets it from the
     *  slider and the lag; FightModel.creepersDanger). */
    public int fuseMargin;
    /** How far the player must be from a lit creeper for its fuse to wind down, in a plan (FightModel.copy): the
     *  game's 7, taken further by MobSim.SWELL_BACK and the plan's own margin. */
    public double swellBack = MobSim.SWELL_BACK;
    /** Creeper blasts in the last tick (wherever they happen: RW wants none at survive, "no exp[losions"). */
    public int blastsNow;
    /** One-way network lag in ticks (the snapshot's): the margin a plan keeps on timings it can't see exactly. */
    public double lag;
    /** The heading a situational action settled on at the start of its segment (yaw degrees). */
    public float segmentYaw;
    /** The worst safety mark so far: a fall, a hazard touched, the edge of the known world. */
    public double unsafe;

    public FightWorld copy() {
        FightWorld w = new FightWorld();
        w.player = player.copy();
        w.health = health; w.maxHealth = maxHealth; w.absorption = absorption; w.armour = armour; w.toughness = toughness;
        w.resistance = resistance; w.difficulty = difficulty; w.slowed = slowed; w.poisoned = poisoned;
        w.invulnerable = invulnerable; w.lastHurt = lastHurt;
        w.kbx = kbx; w.kby = kby; w.kbz = kbz; w.kbIn = kbIn; w.pdx = pdx; w.pdz = pdz;
        w.pushX = pushX; w.pushY = pushY; w.pushZ = pushZ; w.pushIn = pushIn;
        w.mobs = new MobSim[mobs.length];
        for (int i = 0; i < mobs.length; i++) w.mobs[i] = mobs[i].copy();
        w.arrows = new ArrowSim[arrows.length];
        for (int i = 0; i < arrows.length; i++) w.arrows[i] = arrows[i].copy();
        w.fangs = new double[fangs.length][];
        for (int i = 0; i < fangs.length; i++) w.fangs[i] = fangs[i].clone();
        w.aura = aura.copy();
        w.dealt = dealt; w.taken = taken; w.ticks = ticks; w.lull = lull; w.lag = lag; w.fuseMargin = fuseMargin; w.swellBack = swellBack; w.segmentYaw = segmentYaw; w.unsafe = unsafe;
        return w;
    }

    public int alive() {
        int n = 0;
        for (MobSim m : mobs) if (m.alive) n++;
        return n;
    }

    /** The keys for a tick, chosen once the rotation sent this tick is known (the kill aura sets it first). */
    public interface Keys {
        int at(float sentYaw);

        /** The rotation the player moves by this tick: the one sent (an "ideal" chooser, for measuring what tilting
         *  the rotation toward the heading could gain, moves by its own). */
        default float moveYaw(float sentYaw) { return sentYaw; }
    }

    /** One tick with fixed keys. */
    public double[] tick(GroundPhysics phys, int keys, SplittableRandom rnd) {
        return tick(phys, yaw -> keys, rnd);
    }

    /** One tick; the keys are asked for at the rotation sent. Returns {health taken off mobs, health the player lost}. */
    public double[] tick(GroundPhysics phys, Keys chooser, SplittableRandom rnd) {
        if (kbIn > 0 && --kbIn == 0) { player.vx = kbx; player.vy = kby; player.vz = kbz; }
        if (pushIn > 0 && --pushIn == 0) { player.vx += pushX; player.vy += pushY; player.vz += pushZ; }
        double dealtNow = aura.tick(player, pdx, pdz, phys.body().walkSpeed, mobs);
        int keys = chooser.at(aura.sentYaw);
        // pushes: each mob whose box meets the player's pushes it away (Entity.push, by the mob's client copy)
        for (MobSim m : mobs) {
            if (!m.alive) continue;
            double hw = m.width / 2, pw = phys.body().width / 2;
            if (Math.abs(m.x - player.x) < hw + pw && Math.abs(m.z - player.z) < hw + pw
                && m.y < player.y + phys.body().height && m.y + m.height > player.y) {
                double xa = player.x - m.x, za = player.z - m.z;
                double dd = Math.max(Math.abs(xa), Math.abs(za));
                if (dd >= 0.01F) {
                    dd = Math.sqrt(dd);
                    xa /= dd; za /= dd;
                    double pow = Math.min(1.0, 1.0 / dd);
                    player.vx += xa * pow * 0.05F;
                    player.vz += za * pow * 0.05F;
                }
            }
        }
        double x0 = player.x, z0 = player.z;
        phys.step(player, keys, chooser.moveYaw(aura.sentYaw));
        pdx = player.x - x0; pdz = player.z - z0;
        // the server tick
        if (invulnerable > 0) invulnerable--;
        double[] pb = playerBox(phys);
        double lost = 0;
        extraNow = 0;
        blastsNow = 0;
        for (MobSim m : mobs) {
            if (!m.alive) continue;
            m.tickWindow();
            switch (m.kind) {
                case MELEE, LEAPER -> {
                    if (m.think(player.x, player.y, player.z, pb, rnd)) lost += hurtByMob(m, phys);
                }
                case BOW -> {
                    if (m.thinkBow(player.x, player.y, player.z, rnd)) shoot(m, rnd);
                }
                case CROSSBOW -> {
                    if (m.thinkCrossbow(player.x, player.y, player.z, rnd)) shoot(m, rnd);
                }
                case WITCH -> {
                    if (m.thinkWitch(player.x, player.y, player.z)) throwPotion(m, rnd);
                }
                case TRIDENT -> {   // a drowned with a trident: the same goal as the witch's, every 40 ticks
                    if (m.thinkWitch(player.x, player.y, player.z)) shoot(m, rnd);
                }
                case SLIME -> m.thinkSlime(player.x, player.z, rnd);
                case PHANTOM -> {
                    if (m.thinkPhantom(player.x, player.y, player.z, pb)) lost += hurtByMob(m, phys);
                }
                case EVOKER -> {
                    if (m.thinkEvoker()) castFangs(m);
                }
                case VEX -> {
                    if (m.thinkVex(player.x, player.y, player.z, pb)) lost += hurtByMob(m, phys);
                }
                case BREEZE -> {
                    if (m.thinkBreeze()) windCharge(m, rnd);
                }
                case BLAZE -> {
                    int act = m.thinkBlaze(player.x, player.y, player.z);
                    if (act == 1) fireball(m);
                    else if (act == 2) lost += hurtByMob(m, phys);
                }
                case CREEPER -> {
                    if (m.swellTick()) { lost += blast(m, phys); blastsNow++; continue; }
                    m.thinkCreeper(player.x, player.y, player.z, pb, rnd, swellBack);
                }
            }
            m.move();
            if (m.kind == MobSim.Kind.SLIME && m.touchDamage && m.alive && m.touches(pb)) lost += hurtByMob(m, phys);   // every tick it touches
        }
        for (double[] f : fangs) {   // {x, y, z, ticks to the bite}: the bite meets what is in its box grown 0.2 sideways
            if (f[3] < 0) continue;
            if (f[3]-- > 0.5) continue;
            f[3] = -1;
            if (f[0] - 0.45 < pb[3] && f[0] + 0.45 > pb[0] && f[1] < pb[4] && f[1] + 0.8 > pb[1] && f[2] - 0.45 < pb[5] && f[2] + 0.45 > pb[2])
                lost += hurt(scale(6.0), Double.NaN, Double.NaN, phys, false);   // indirect magic: no armour
        }
        for (ArrowSim a : arrows) {
            if (!a.alive) continue;
            if (a.fresh) { a.fresh = false; continue; }   // made this tick: it flies from the next
            double dmg = a.tick(pb, phys.grid());
            if (a.burst > 0 && !a.alive && !Double.isNaN(a.endX)) {   // a wind charge's burst where it stopped
                double[] b = Blast.onPlayer(a.endX, a.endY, a.endZ, a.burst, pb, player.y + 1.62, phys.grid());
                if (b != null) {
                    if (pushIn == 0) { pushX = 0; pushY = 0; pushZ = 0; }   // a push not yet given adds up
                    pushX += b[1]; pushY += b[2]; pushZ += b[3]; pushIn = 1;
                }
            }
            if (a.splash) {   // a splash potion: on the player, or near it where it broke
                double in = dmg >= 0 ? 1.0 : a.alive ? 0.0 : a.splashOn(pb);
                if (in > 0) {
                    if (a.splashDamage > 0) lost += hurt((int) (in * a.splashDamage + 0.5), Double.NaN, Double.NaN, phys, false);
                    extraNow += in * a.splashCost;
                }
                continue;
            }
            if (dmg >= 0) {
                double hx = a.vx, hz = a.vz, h = Math.sqrt(hx * hx + hz * hz);
                double scaled = a.scales ? scale(dmg) : dmg;
                double got = hurt(scaled, h < 1e-9 ? 0 : hx / h, h < 1e-9 ? 0 : hz / h, phys);
                lost += got;
                if (got > 0) extraNow += a.extra;
            }
        }
        aura.endTick();
        ticks++;
        dealt += dealtNow;
        taken += lost;
        return new double[]{dealtNow, lost};
    }

    /** Player.hurtServer's difficulty scaling for damage that scales: none on peaceful, min(d / 2 + 1, d) on easy, d on
     *  normal, 1.5 d on hard. */
    double scale(double d) {
        return switch (difficulty) {
            case 0 -> 0.0;
            case 1 -> Math.min(d / 2.0 + 1.0, d);
            case 3 -> d * 1.5;
            default -> d;
        };
    }

    /** A skeleton's shot (AbstractSkeleton.performRangedAttack) or a pillager's (CrossbowItem.shootProjectile, the
     *  same aim): the arrow at its eyes less 0.1, aimed at the player's position a third of the way up its box, raised
     *  by a fifth of the horizontal distance, speed 1.6, each axis off by triangle(0, 0.0172275 * (14 - 4 *
     *  difficulty)) (Projectile.getMovementToShoot). */
    void shoot(MobSim m, SplittableRandom rnd) {
        ArrowSim a = new ArrowSim();
        a.x = m.x; a.y = m.y + m.eyeHeight - 0.1F; a.z = m.z;
        double xd = player.x - m.x, yd = player.y + 1.8F / 3.0 - a.y, zd = player.z - m.z;
        double dist = Math.sqrt(xd * xd + zd * zd);
        yd += dist * 0.2F;
        double len = Math.sqrt(xd * xd + yd * yd + zd * zd);
        double dev = 0.0172275 * (14 - difficulty * 4);
        double dx = xd / len + dev * (rnd.nextDouble() - rnd.nextDouble());
        double dy = yd / len + dev * (rnd.nextDouble() - rnd.nextDouble());
        double dz = zd / len + dev * (rnd.nextDouble() - rnd.nextDouble());
        a.vx = dx * 1.6F; a.vy = dy * 1.6F; a.vz = dz * 1.6F;
        a.base = m.arrowBase;
        a.extra = m.arrowExtra;
        if (m.kind == MobSim.Kind.TRIDENT) { a.fixed = 8.0; a.extra = 0; }   // Drowned.performRangedAttack: the same throw, 8 damage
        a.fresh = true;
        ArrowSim[] n = java.util.Arrays.copyOf(arrows, arrows.length + 1);
        n[arrows.length] = a;
        arrows = n;
    }

    /** An evoker's fangs (EvokerAttackSpellGoal.performSpellCasting), on the player's floor: within 3 blocks, five at
     *  1.5 around it (no warmup) and eight at 2.5 (3); else sixteen in a line toward the player, 1.25 apart, the n-th
     *  with a warmup of n. Each bites its warmup plus 8 ticks after it is made. */
    void castFangs(MobSim m) {
        double angle = Math.atan2(player.z - m.z, player.x - m.x), fy = Math.min(player.y, m.y);
        java.util.List<double[]> add = new java.util.ArrayList<>();
        double d2 = (player.x - m.x) * (player.x - m.x) + (player.y - m.y) * (player.y - m.y) + (player.z - m.z) * (player.z - m.z);
        if (d2 < 9.0) {
            for (int i = 0; i < 5; i++) {
                double a = angle + i * Math.PI * 0.4;
                add.add(new double[]{m.x + Math.cos(a) * 1.5, fy, m.z + Math.sin(a) * 1.5, 0 + 8});
            }
            for (int i = 0; i < 8; i++) {
                double a = angle + i * Math.PI * 2.0 / 8.0 + Math.PI * 2.0 / 5.0;
                add.add(new double[]{m.x + Math.cos(a) * 2.5, fy, m.z + Math.sin(a) * 2.5, 3 + 8});
            }
        } else {
            for (int i = 0; i < 16; i++) {
                double r = 1.25 * (i + 1);
                add.add(new double[]{m.x + Math.cos(angle) * r, fy, m.z + Math.sin(angle) * r, i + 8});
            }
        }
        double[][] n = java.util.Arrays.copyOf(fangs, fangs.length + add.size());
        for (int i = 0; i < add.size(); i++) n[fangs.length + i] = add.get(i);
        fangs = n;
    }

    /** A breeze's wind charge (Shoot, BreezeWindCharge): from its middle 0.3 up toward 0.3 of the player's height,
     *  speed 0.7, straight, spread 5 - 4 x difficulty; 1 damage on a hit, and a burst of radius 3 where it stops. */
    void windCharge(MobSim m, SplittableRandom rnd) {
        ArrowSim a = new ArrowSim();
        a.x = m.x; a.y = m.y + m.height / 2 + 0.3; a.z = m.z;
        double xd = player.x - m.x, yd = player.y + 1.8 * 0.3 - a.y, zd = player.z - m.z;
        double len = Math.sqrt(xd * xd + yd * yd + zd * zd);
        if (len < 1e-6) return;
        double dev = 0.0172275 * Math.max(0, 5 - difficulty * 4);
        a.vx = (xd / len + dev * (rnd.nextDouble() - rnd.nextDouble())) * 0.7;
        a.vy = (yd / len + dev * (rnd.nextDouble() - rnd.nextDouble())) * 0.7;
        a.vz = (zd / len + dev * (rnd.nextDouble() - rnd.nextDouble())) * 0.7;
        a.gravity = 0; a.drag = 1; a.gravityFirst = true;
        a.fixed = 1.0;
        a.burst = 3.0;
        a.fresh = true;
        ArrowSim[] n = java.util.Arrays.copyOf(arrows, arrows.length + 1);
        n[arrows.length] = a;
        arrows = n;
    }

    /** What a fireball's fire costs beyond its 5 damage: 5 seconds alight, a health each second, less the first. */
    static final double FIRE_EXTRA = 4.0;

    /** A blaze's fireball (BlazeAttackGoal, SmallFireball): from its middle, 0.5 up, toward the player's middle,
     *  speed 0.1 and speeding up; the game spreads it sideways by a triangle of 2.297 x sqrt(distance) / 2 on x and
     *  on z, which the copy leaves out (aimed straight at the player: the worst case). */
    void fireball(MobSim m) {
        ArrowSim a = new ArrowSim();
        double cy = m.y + m.height / 2;
        a.x = m.x; a.y = cy + 0.5; a.z = m.z;
        double xd = player.x - m.x, yd = player.y + 0.9 - cy, zd = player.z - m.z;
        double len = Math.sqrt(xd * xd + yd * yd + zd * zd);
        if (len < 1e-6) return;
        a.vx = xd / len * 0.1; a.vy = yd / len * 0.1; a.vz = zd / len * 0.1;
        a.gravity = 0; a.drag = 1; a.accel = 0.1;
        a.fixed = 5.0;
        a.extra = FIRE_EXTRA;
        a.fresh = true;
        ArrowSim[] n = java.util.Arrays.copyOf(arrows, arrows.length + 1);
        n[arrows.length] = a;
        arrows = n;
    }

    /** The witch's potion this throw (Witch.performRangedAttack): slowness if the player is 8 or more away and not
     *  slowed, else poison if it has 8 health or more and isn't poisoned, else harming (weakness a quarter of the time
     *  within 3, taken as harming: the worse); thrown at the player's position one tick on (its velocity), at its eyes
     *  less 1.1, raised by a fifth of the distance, speed 0.75 (0.45 within 2), spread 8. The player's effects are the
     *  snapshot's. */
    public boolean slowed, poisoned;

    void throwPotion(MobSim m, SplittableRandom rnd) {
        ArrowSim a = new ArrowSim();
        a.x = m.x; a.y = m.y + m.eyeHeight - 0.1F; a.z = m.z;
        double xd = player.x + player.vx - m.x, yd = player.y + 1.62 - 1.1F - m.y, zd = player.z + player.vz - m.z;
        double dist = Math.sqrt(xd * xd + zd * zd);
        yd += dist * 0.2;
        double len = Math.sqrt(xd * xd + yd * yd + zd * zd);
        double dev = 0.0172275 * 8.0, speed = dist <= 2.0 ? 0.45F : 0.75F;
        a.vx = (xd / len + dev * (rnd.nextDouble() - rnd.nextDouble())) * speed;
        a.vy = (yd / len + dev * (rnd.nextDouble() - rnd.nextDouble())) * speed;
        a.vz = (zd / len + dev * (rnd.nextDouble() - rnd.nextDouble())) * speed;
        a.gravity = 0.05; a.drag = 0.99; a.gravityFirst = true;
        a.splash = true;
        if (dist >= 8.0 && !slowed) { a.splashCost = 3.0; slowed = true; }
        else if (health >= 8.0 && !poisoned) { a.splashCost = 8.0; poisoned = true; }   // 45 s of poison, never the last health
        else a.splashDamage = 6.0;
        a.fresh = true;
        ArrowSim[] n = java.util.Arrays.copyOf(arrows, arrows.length + 1);
        n[arrows.length] = a;
        arrows = n;
    }

    /** A creeper's blast on the player (Blast): its damage through the window and armour, and its push, which reaches
     *  the client at the start of its next tick. Returns the health lost. */
    double blast(MobSim c, GroundPhysics phys) {
        double[] pb = playerBox(phys);
        double[] b = Blast.onPlayer(c.x, c.y, c.z, c.blastRadius, pb, player.y + 1.62, phys.grid());
        if (b == null) return 0.0;
        double got = hurt(scale(b[0]), Double.NaN, Double.NaN, phys);   // NO_KNOCKBACK: no hurt knockback
        pushX = b[1]; pushY = b[2]; pushZ = b[3]; pushIn = 1;
        return got;
    }

    /** A mob's melee hit on the player: its damage (difficulty applied in the snapshot), knockback away from it, and its
     *  hit's extra cost (effects). Returns the health lost. */
    double hurtByMob(MobSim m, GroundPhysics phys) {
        double xd = player.x - m.x, zd = player.z - m.z, d = Math.sqrt(xd * xd + zd * zd);
        if (d < 1e-4) { xd = -0.01; zd = 0; d = 0.01; }
        boolean full = invulnerable <= 10;
        double got = hurt(m.attackDamage, xd / d, zd / d, phys);
        if (got > 0) extraNow += m.hitExtra;
        if (full && m.throwPower > 0 && kbIn > 0) {
            // HoglinBase.throwTarget: a push of up to 0.7 x the power sideways (within 10 degrees) and 0.5 x up; the
            // worst case, straight on, with the knockback's
            double t = Math.max(0.0, m.throwPower - phys.body().knockbackResistance);
            kbx += xd / d * 0.7 * t; kbz += zd / d * 0.7 * t; kby += 0.5 * t;
        }
        return got;
    }

    /**
     * A hit on the player (Player.hurtServer, LivingEntity.hurtServer and actuallyHurt): the hurt window, armour,
     * Resistance, absorption, and, on a full hit, the knockback along (dirX, dirZ) (none if NaN), which becomes the
     * player's velocity: LivingEntity.knockback(0.4) on the server's copy of the player, whose velocity there is that of
     * a player at rest ((0, -0.0784, 0) on the ground), then the server's own tick of physics on it before the motion
     * packet goes (horizontal x friction, vertical (vy - 0.08) x 0.98). Measured in the lockstep zombie test:
     * (0.2184 x the push's direction, 0.2752), at the start of the client tick after next. Returns the health lost.
     */
    double hurt(double dmg, double dirX, double dirZ, GroundPhysics phys) {
        return hurt(dmg, dirX, dirZ, phys, true);
    }

    /** hurt, with armour applying or not (a potion's magic damage bypasses it). */
    double hurt(double dmg, double dirX, double dirZ, GroundPhysics phys, boolean armourApplies) {
        double through;
        if (invulnerable > 10) {
            if (dmg <= lastHurt) return 0.0;
            through = dmg - lastHurt;
            lastHurt = dmg;
        } else {
            lastHurt = dmg;
            invulnerable = 20;
            through = dmg;
            if (!Double.isNaN(dirX)) {
                double power = 0.4F * (1.0 - phys.body().knockbackResistance);
                double ky = player.onGround ? Math.min(0.4, -0.0784000015258789 / 2.0 + power) : player.vy;
                float f = player.onGround
                    ? phys.grid().get((int) Math.floor(player.x), (int) Math.floor(player.y - 0.5), (int) Math.floor(player.z)).friction * 0.91F
                    : 0.91F;
                kbx = dirX * power * f;
                kbz = dirZ * power * f;
                kby = (ky - 0.08) * 0.98F;
                kbIn = 2;
            }
        }
        double after = (armourApplies ? SwingPlan.afterArmour(through, armour, toughness) : through) * resistance;
        double fromAbsorption = Math.min(absorption, after);
        absorption -= fromAbsorption;
        double lost = after - fromAbsorption;
        health -= lost;
        return after;
    }

    public double[] playerBox(GroundPhysics phys) {
        float w = phys.body().width / 2.0F, h = player.crouchPose ? phys.body().crouchHeight : phys.body().height;
        return new double[]{player.x - w, player.y, player.z - w, player.x + w, player.y + h, player.z + w};
    }
}
