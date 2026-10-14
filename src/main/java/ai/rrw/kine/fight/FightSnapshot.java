package ai.rrw.kine.fight;

import ai.rrw.kine.Settings;
import ai.rrw.kine.combat.AuraState;
import ai.rrw.kine.combat.Ballistics;
import ai.rrw.kine.combat.KillAura;
import ai.rrw.kine.combat.ProjectileDodge;
import ai.rrw.kine.mixin.PlayerAccessor;
import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.WorldSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.Bogged;
import net.minecraft.world.entity.monster.skeleton.Parched;
import net.minecraft.world.entity.monster.skeleton.Stray;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.spider.CaveSpider;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The live game read into the fight simulation's terms, on the client thread at the end of a tick: the blocks around
 * the player, the player's movement state, health and armour, the kill aura's state, and the mobs as well as the
 * client can know them. A mob's position is the last one the server sent (the interpolation target, which trails the
 * server by about 2.6 ticks in a lockstep test) carried forward along its recent motion; its attack cooldown is read
 * from the last swing the client saw (the server sends the swing), else taken as ready; a mob that strolled before
 * chasing is taken as chasing (the pessimistic side). A skeleton's draw is read from its using-item flag (synced; the
 * client counts the ticks since it arrived) and the time since its last shot (the flag dropping), else it draws at once;
 * a creeper's fuse from the client's own count of it and its synced direction, carried on by the lag. Projectiles in
 * flight (all the projectile dodge would consider) come in from where the client has them, flown on by the lag.
 */
public final class FightSnapshot {
    private FightSnapshot() {}

    /** Ticks a mob's last received position is carried forward (measured trail of the interpolation target in a
     *  lockstep test, no network), and how far its drawn position trails the server (what the kill aura aims by). Both
     *  grow by the network's lag, taken from the player's ping (the tab list's latency; a test can set kine.pingMs,
     *  as a lockstep test's injected lag is invisible to the game). */
    public static final double LEAD_TICKS = 2.6, DRAWN_TRAIL_TICKS = 3.7;
    /** Mobs within this of the player are simulated (a skeleton shoots from 15 blocks). */
    public static final double RANGE = 16.0;
    /** A modelled mob this near engages the combat movement: a skeleton within its bow's reach, the rest within 8. */
    public static final double ENGAGE = 8.0, ENGAGE_BOW = 16.0;
    /** The block snapshot's reach around the player: sideways, down and up. */
    public static final int GRID_R = 12, GRID_DOWN = 6, GRID_UP = 4;

    /** What the combat movement needs from a snapshot. */
    public record Shot(FightWorld world, BlockGrid grid, Body body, int mobs, int unsupported, double nearest, boolean engage) {}

    // per mob: the last received positions (tick, x, y, z) and the tick its last swing was seen
    private static final class Track {
        int tick0 = -1; double x0, y0, z0;     // the latest received position and when it changed
        int tick1 = -1; double x1, y1, z1;     // the one before
        int swungAt = Integer.MIN_VALUE / 2;
        float health = -1;
        boolean wasSwinging;
        int shotAt = Integer.MIN_VALUE / 2;   // the tick its using-item flag dropped (a skeleton's shot)
        int thrownAt = Integer.MIN_VALUE / 2; // the tick a projectile of its was made (a witch's throw)
        int landedAt = Integer.MIN_VALUE / 2; // the tick its on-ground flag came back (a slime's landing)
        boolean wasOnGround;
        int shots, lastShotId = -1;           // projectiles of its seen new, and the last one's id
        int chargedAt = Integer.MIN_VALUE / 2, unchargedAt = Integer.MIN_VALUE / 2, shotsAtCharge;   // a blaze's charge
        boolean wasCharged;
        int poseAt = Integer.MIN_VALUE / 2, shotsAtPose;   // a breeze's SHOOTING pose: when it was seen to start
        boolean wasShooting;
        int castAt = Integer.MIN_VALUE / 2;   // an evoker's spell: when it was seen to start
        boolean wasCasting;
        boolean wasUsing;
        int seen;
    }
    private static final Map<Integer, Track> tracks = new HashMap<>();
    private static int clientTick = 0;
    /** The last tick a modelled mob near lost health; the time since is the fight's lull (FightWorld.lull). */
    private static int lastProgress = 0;

    /** Every tick (END_CLIENT_TICK), whatever the setting: follow the mobs near the player (their received positions
     *  and swings). Cheap: a few entities. */
    public static void follow(Minecraft mc) {
        clientTick++;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { tracks.clear(); return; }
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(RANGE + 4))) {
            if (!(e instanceof Mob m)) continue;
            Track t = tracks.computeIfAbsent(e.getId(), k -> new Track());
            t.seen = clientTick;
            Vec3 at = m.getInterpolation() != null ? m.getInterpolation().position() : m.position();
            if (t.tick0 < 0 || at.x != t.x0 || at.y != t.y0 || at.z != t.z0) {
                t.tick1 = t.tick0; t.x1 = t.x0; t.y1 = t.y0; t.z1 = t.z0;
                t.tick0 = clientTick; t.x0 = at.x; t.y0 = at.y; t.z0 = at.z;
            }
            if (m.swinging && !t.wasSwinging) t.swungAt = clientTick;
            // the fight's progress: a modelled mob near losing health (or dying), or none near
            if (t.health >= 0 && m.getHealth() < t.health && modelled(m)) lastProgress = clientTick;
            t.health = m.getHealth();
            t.wasSwinging = m.swinging;
            if (m.onGround() && !t.wasOnGround) t.landedAt = clientTick;
            t.wasOnGround = m.onGround();
            if (!m.isUsingItem() && t.wasUsing) t.shotAt = clientTick;
            t.wasUsing = m.isUsingItem();
            if (m instanceof Evoker ev) {
                boolean c = ev.isCastingSpell();
                if (c && !t.wasCasting) t.castAt = clientTick;
                t.wasCasting = c;
            }
            if (m instanceof Breeze) {
                boolean sh = m.getPose() == Pose.SHOOTING;
                if (sh && !t.wasShooting) { t.poseAt = clientTick; t.shotsAtPose = t.shots; }
                t.wasShooting = sh;
            }
            if (m instanceof Blaze) {   // its isOnFire is its charged flag
                boolean ch = m.isOnFire();
                if (ch && !t.wasCharged) { t.chargedAt = clientTick; t.shotsAtCharge = t.shots; }
                if (!ch && t.wasCharged) t.unchargedAt = clientTick;
                t.wasCharged = ch;
            }
        }
        // a new projectile from a mob near: its shot or throw, a tick for each tick it has flown
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(RANGE + 4))) {
            if (!(e instanceof Projectile proj) || e.tickCount > 3 || !(proj.getOwner() instanceof Mob owner)) continue;
            Track t = tracks.get(owner.getId());
            if (t != null) t.thrownAt = Math.max(t.thrownAt, clientTick - e.tickCount);
            if (t != null && e.getId() > t.lastShotId) { t.lastShotId = e.getId(); t.shots++; }
        }
        if ((clientTick & 63) == 0) tracks.values().removeIf(t -> clientTick - t.seen > 100);
    }

    /** The kind of model a mob gets, or null if it has none (the combat movement stands down near it): the zombie
     *  family (zombies, husks, zombie villagers, babies, drowned; a drowned with a trident throws it, the witch's goal
     *  every 40 ticks; not zombified piglins, which are
     *  neutral and handled by the aura's anger rules), skeletons and their kin (with a bow the bow goal, else the melee
     *  goal: wither skeletons), creepers, spiders and cave spiders, pillagers and piglins with a crossbow, witches, the other
     *  walkers (walker), slimes and magma cubes, blazes, phantoms, breezes, evokers and vexes. */
    static MobSim.Kind kindOf(Entity e) {
        if (walker(e)) return MobSim.Kind.MELEE;
        if (e instanceof Zombie && !(e instanceof ZombifiedPiglin)) {
            boolean trident = e instanceof Drowned d && (d.getMainHandItem().is(Items.TRIDENT) || d.getOffhandItem().is(Items.TRIDENT));
            return trident ? MobSim.Kind.TRIDENT : MobSim.Kind.MELEE;
        }
        if (e instanceof AbstractSkeleton s) return s.isHolding(Items.BOW) ? MobSim.Kind.BOW : MobSim.Kind.MELEE;
        if (e instanceof Creeper) return MobSim.Kind.CREEPER;
        if (e instanceof Pillager pl) return pl.isHolding(Items.CROSSBOW) ? MobSim.Kind.CROSSBOW : null;
        // a piglin with a crossbow: its brain's CrossbowAttack runs the pillager goal's states (charge 25 ticks, wait 20
        // to 39, shoot) from within 8 blocks, with the same arrow
        if (e instanceof Piglin pg && pg.isHolding(Items.CROSSBOW)) return MobSim.Kind.CROSSBOW;
        if (e instanceof Witch) return MobSim.Kind.WITCH;
        if (e instanceof Spider) return MobSim.Kind.LEAPER;
        if (e instanceof Slime) return MobSim.Kind.SLIME;   // magma cubes too
        if (e instanceof Blaze) return MobSim.Kind.BLAZE;
        if (e instanceof Phantom) return MobSim.Kind.PHANTOM;
        if (e instanceof Breeze) return MobSim.Kind.BREEZE;
        if (e instanceof Evoker) return MobSim.Kind.EVOKER;
        if (e instanceof Vex) return MobSim.Kind.VEX;
        return null;
    }

    static boolean modelled(Entity e) {
        return kindOf(e) != null;
    }

    /** Whether a mob combat movement models is within its engage distance (a ranged one within ENGAGE_BOW, the rest
     *  within ENGAGE): the engage test of take(), without the snapshot (the bow aura asks every tick). */
    public static boolean engages(Minecraft mc, LocalPlayer p) {
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(RANGE))) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || !KillAura.targets(e, p)) continue;
            MobSim.Kind k = kindOf(e);
            if (k != null && e.distanceTo(p) <= engageDistance(k)) return true;
        }
        return false;
    }

    /** How near a mob of this kind engages the combat movement. */
    static double engageDistance(MobSim.Kind k) {
        return k == MobSim.Kind.BOW || k == MobSim.Kind.CROSSBOW || k == MobSim.Kind.WITCH || k == MobSim.Kind.BLAZE
            || k == MobSim.Kind.TRIDENT || k == MobSim.Kind.PHANTOM || k == MobSim.Kind.BREEZE
            || k == MobSim.Kind.EVOKER || k == MobSim.Kind.VEX ? ENGAGE_BOW : ENGAGE;
    }

    /** The whole snapshot, or null if the player can't be simulated now. */
    public static Shot take(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return null;
        List<LivingEntity> zs = new ArrayList<>();
        int unsupported = 0;
        double nearest = Double.MAX_VALUE;
        boolean engage = false;
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(RANGE))) {
            // a warden near: no movement helps (30 a hit through armour, its sonic boom through blocks); stand down
            if (e instanceof Warden wd && wd.isAlive() && e.distanceTo(p) <= RANGE) { unsupported++; continue; }
            if (!(e instanceof LivingEntity le) || !le.isAlive() || !KillAura.targets(e, p)) continue;
            double d = e.distanceTo(p);
            MobSim.Kind k = kindOf(e);
            if (k != null) {
                zs.add(le);
                nearest = Math.min(nearest, d);
                engage |= d <= engageDistance(k);
            } else if (d <= ENGAGE) unsupported++;
        }
        BlockGrid grid = WorldSnapshot.around(mc.level, p, GRID_R, GRID_DOWN, GRID_UP);
        Body body = WorldSnapshot.body(p);
        FightWorld w = new FightWorld();
        w.player = WorldSnapshot.state(p);
        w.health = p.getHealth();
        w.maxHealth = p.getMaxHealth();
        w.absorption = p.getAbsorptionAmount();
        w.armour = p.getArmorValue();
        w.toughness = p.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        MobEffectInstance res = p.getEffect(MobEffects.RESISTANCE);
        w.resistance = res == null ? 1.0 : Math.max(0.0, 1.0 - 0.2 * (res.getAmplifier() + 1));
        w.invulnerable = p.hurtTime > 0 ? p.hurtTime + 10 : 0;   // the client's hurtTime is 10 when the server's window is 20
        w.lastHurt = w.invulnerable > 0 ? 3.0 : 0.0;
        w.pdx = p.getX() - p.xo; w.pdz = p.getZ() - p.zo;
        AuraSim a = new AuraSim();
        a.on = Settings.killAura;
        a.weapon = AuraState.weapon(p);
        a.k = ((PlayerAccessor) p).kine$getAttackStrengthTicker();
        a.viewYaw = p.getYRot();
        a.sentYaw = a.on ? KillAura.lastSentYaw(p) : p.getYRot();
        a.reach = p.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        a.eyeHeight = p.getEyeHeight();
        w.aura = a;
        double difficulty = switch (mc.level.getDifficulty()) {
            case PEACEFUL -> 0.0;
            case EASY -> -1.0;   // min(d / 2 + 1, d), below
            case NORMAL -> 1.0;
            case HARD -> 1.5;
        };
        w.difficulty = mc.level.getDifficulty().getId();
        w.slowed = p.hasEffect(MobEffects.SLOWNESS);
        w.poisoned = p.hasEffect(MobEffects.POISON);
        double lag = lagTicks(mc, p);
        w.mobs = new MobSim[zs.size()];
        for (int i = 0; i < zs.size(); i++) w.mobs[i] = mob(zs.get(i), p, grid, difficulty, lag, mc.level.getDifficulty());
        w.arrows = projectiles(mc, p, lag);
        w.fangs = fangs(mc, p, lag);
        w.segmentYaw = Float.NaN;
        if (zs.isEmpty()) lastProgress = clientTick;
        w.lull = Math.min(1000, clientTick - lastProgress);
        w.lag = lag;
        return new Shot(w, grid, body, zs.size(), unsupported, nearest, engage);
    }

    /** One-way network lag in ticks: half the ping. */
    static double lagTicks(Minecraft mc, LocalPlayer p) {
        String prop = System.getProperty("kine.pingMs");
        double ms = 0;
        if (prop != null) {
            try { ms = Double.parseDouble(prop); } catch (NumberFormatException ignored) { }
        } else if (mc.getConnection() != null && mc.getConnection().getPlayerInfo(p.getUUID()) != null) {
            ms = mc.getConnection().getPlayerInfo(p.getUUID()).getLatency();
        }
        return Math.max(0.0, ms / 2.0 / 50.0);
    }

    /** Client ticks without a new position after which a mob counts as standing still. */
    static final int STILL_AFTER = 4;

    static MobSim mob(LivingEntity e, LocalPlayer p, BlockGrid grid, double difficulty, double lag, Difficulty level) {
        MobSim m = new MobSim();
        m.kind = kindOf(e);
        m.id = e.getId();
        Track t = tracks.get(e.getId());
        Vec3 at = e.getInterpolation() != null ? e.getInterpolation().position() : e.position();
        double vx = 0, vy = 0, vz = 0;
        // its velocity from the last two positions received, unless none has come for a while: then it stands (the
        // server sends a mob's moves every tick while it moves; under lag they come a few ticks at a time). Carried on
        // from an old move, a slime waiting to hop or a creeper swelling in place would be put blocks ahead
        if (t != null && t.tick1 >= 0 && t.tick0 > t.tick1 && clientTick - t.tick0 <= STILL_AFTER) {
            double dt = t.tick0 - t.tick1;
            vx = (t.x0 - t.x1) / dt; vy = (t.y0 - t.y1) / dt; vz = (t.z0 - t.z1) / dt;
        }
        double lead = LEAD_TICKS + lag + (t != null && t.tick0 >= 0 ? clientTick - t.tick0 : 0);
        m.x = at.x + vx * lead; m.z = at.z + vz * lead;
        m.y = at.y;
        m.floorY = floorBelow(grid, m.x, at.y, m.z);
        m.onGround = e.onGround() || Math.abs(at.y - m.floorY) < 1e-3;
        if (m.onGround) m.y = m.floorY;
        m.vx = vx * (m.onGround ? MobSim.GROUND_FRICTION : MobSim.AIR_FRICTION);
        m.vz = vz * (m.onGround ? MobSim.GROUND_FRICTION : MobSim.AIR_FRICTION);
        m.vy = m.onGround ? -0.0784000015258789 : vy;
        m.lastDx = vx; m.lastDz = vz;
        m.drawnX = e.getX(); m.drawnY = e.getY(); m.drawnZ = e.getZ();
        m.seenVx = e.getX() - e.xo; m.seenVz = e.getZ() - e.zo;
        m.delay = (int) Math.min(15, Math.round(DRAWN_TRAIL_TICKS + lag));
        m.width = e.getBbWidth(); m.height = e.getBbHeight(); m.eyeHeight = e.getEyeHeight();
        m.yRot = e.getYRot();
        m.speed = e.getAttributeValue(Attributes.MOVEMENT_SPEED);
        m.health = e.getHealth();
        m.maxHealth = Math.max(1.0, e.getMaxHealth());
        m.armour = e.getArmorValue();
        m.toughness = e.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        m.bonus = AuraState.bonus(p.getMainHandItem(), e);
        double base = baseDamage(e);   // the attribute's base (not synced: from the code), plus a held weapon's damage
        double[] add = {0};
        e.getItemBySlot(EquipmentSlot.MAINHAND).forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.value() == Attributes.ATTACK_DAMAGE.value()) add[0] += mod.amount();
        });
        double dmg = base + add[0];
        m.attackDamage = difficulty < 0 ? Math.min(dmg / 2 + 1, dmg) : dmg * difficulty;
        // its cooldown from the last swing seen (the swing reaches the client about 2 ticks after the server's attack)
        int since = t == null ? 1000 : clientTick - t.swungAt + 2;
        m.attackInterval = attackInterval(e);
        m.attackCooldown = Math.max(0, m.attackInterval - since);
        m.active = true;
        m.sinceCheck = 20;
        double[] win = KillAura.window(e);
        int seen = Math.max(0, e.invulnerableTime - 11);   // a full hit from anything, as the client marks it
        m.freshIn = Math.max((int) win[0], seen);
        m.lastHurt = win[0] >= seen && win[0] > 0 ? win[1] : Double.MAX_VALUE;
        if (m.freshIn == 0) m.lastHurt = 0;
        m.pathX = Math.floor(p.getX()) + 0.5; m.pathZ = Math.floor(p.getZ()) + 0.5;
        m.pathTargetX = p.getX(); m.pathTargetY = p.getY(); m.pathTargetZ = p.getZ();
        m.hasPath = true;
        m.walking = true;
        m.pathIn = 0;
        int ahead = (int) Math.round(1.0 + lag);   // a synced flag reaches the client about a tick after the server set it
        switch (m.kind) {
            case MELEE -> {
                m.follows = follows(e);
                if ((e instanceof Hoglin || e instanceof Zoglin) && !e.isBaby()) m.throwPower = 1.0;   // ATTACK_KNOCKBACK 1
                if (e instanceof Hoglin || e instanceof Zoglin) m.knockbackResistance = 0.6;
                if (e instanceof Ravager) { m.throwPower = 1.0; m.knockbackResistance = 0.75; }   // ATTACK_KNOCKBACK 1.5: a push of 0.75
                if (e instanceof AbstractSkeleton) m.speedModifier = 1.2;   // AbstractSkeleton's melee goal
                if (e instanceof WitherSkeleton) m.hitExtra = 4.0;          // Wither I for 10 s (5 health over it)
                if (e instanceof Husk) m.hitExtra = 0.5;                    // Hunger
            }
            case LEAPER -> {
                m.follows = true;
                m.leaping = !e.onGround();
                m.active = !m.leaping;
                if (e instanceof CaveSpider) m.hitExtra = switch (level) {   // poison: a health each 25 ticks
                    case NORMAL -> 5.0;
                    case HARD -> 12.0;
                    default -> 0.0;
                };
            }
            case BOW -> {
                boolean hard = level == Difficulty.HARD;
                boolean slow = e instanceof Bogged || e instanceof Parched;
                m.bowInterval = slow ? (hard ? 50 : 70) : (hard ? 20 : 40);
                m.arrowBase = 2.0 + level.getId() * 0.11 + 0.57425;   // power 1 x 2 plus the top of triangle(diff x 0.11, 0.57425)
                m.arrowExtra = e instanceof Stray ? 2.0 : e instanceof Bogged ? 4.0 : e instanceof Parched ? 3.0
                    : e instanceof WitherSkeleton ? 4.0 : 0.0;   // slowness, poison, weakness, fire
                if (e.isUsingItem()) {
                    m.drawTicks = e.getTicksUsingItem() + ahead;
                    m.attackTime = 0;
                } else {
                    m.drawTicks = -1;
                    int sinceShot = t == null ? 1000 : clientTick - t.shotAt + ahead;
                    m.attackTime = Math.max(0, m.bowInterval - sinceShot);   // never seen to shoot: draws at once
                    if (m.attackTime <= ahead) {
                        // its draw may have begun in the last `ahead` ticks, the flag not here yet: take it as begun then
                        m.drawTicks = Math.min(19, ahead - m.attackTime);
                        m.attackTime = 0;
                    }
                }
                m.seeTime = 20;
                m.strafeTime = 0;
                // which way it strafes, from how it moved relative to the player (it faces the player)
                double f = Math.toRadians(AuraSim.yawTo(at.x, at.z, p.getX(), p.getZ()));
                double right = vx * Math.cos(f) + vz * Math.sin(f), fwd = -vx * Math.sin(f) + vz * Math.cos(f);
                m.strafeCW = right > 0;
                m.strafeBack = Math.hypot(vx, vz) > 0.02 ? fwd < 0 : e.distanceToSqr(p) < MobSim.BOW_RADIUS_SQR * 0.25F;
                m.yRot = (float) AuraSim.yawTo(at.x, at.z, p.getX(), p.getZ());
            }
            case CROSSBOW -> {
                m.arrowBase = 2.0;   // a crossbow's arrow keeps the default base (no setBaseDamageFromMob), no crit for a mob
                m.seeTime = 20;
                if (e.isUsingItem()) {
                    m.xbow = 1;
                    m.drawTicks = e.getTicksUsingItem() + ahead;
                } else if (CrossbowItem.isCharged(e.getMainHandItem())) {
                    int sinceCharged = t == null ? 1000 : clientTick - t.shotAt + ahead;
                    m.xbow = 2;
                    m.attackDelay = Math.max(1, 20 - sinceCharged);   // its random delay at its shortest
                } else {
                    m.xbow = 0;
                }
                m.walking = false;
            }
            case WITCH -> {
                m.seeTime = 20;
                m.walking = false;
                int sinceThrow = t == null ? 1000 : clientTick - t.thrownAt + ahead;
                m.attackTime = Math.max(1, 60 - sinceThrow);   // never seen to throw: it throws next
            }
            case TRIDENT -> {   // RangedAttackGoal(40): the throw it was seen to make, else one next
                m.rangedInterval = 40;
                m.seeTime = 20;
                m.walking = false;
                int sinceThrow = t == null ? 1000 : clientTick - t.thrownAt + ahead;
                m.attackTime = Math.max(1, 40 - sinceThrow);
            }
            case SLIME -> {
                // Slime.setSize: speed 0.2 + 0.1 x size; it hurts on touch unless tiny (a magma cube always); its hop
                // 0.42 up, a magma cube's 0.1 x size more. The wait before its next hop isn't sent: taken at its
                // shortest, counted from the landing seen (the flag comes with its moves)
                Slime sl = (Slime) e;
                int size = sl.getSize();
                m.magma = e instanceof MagmaCube;
                m.slimeSize = size;
                m.touchDamage = m.magma || size > 1;
                if (!m.touchDamage) m.attackDamage = 0;
                m.speed = 0.2F + 0.1F * size;
                m.jumpPower = 0.42F + (m.magma ? size * 0.1F : 0.0F);
                if (m.magma) m.armour = Math.max(m.armour, size * 3);
                // the wait counts down on full goal ticks only, every other tick: half the ticks on the ground, rounded
                // up; which ticks are full isn't seen, so the next is taken as full
                int wait = m.minJumpDelay();
                int onGround = t == null ? 1000 : clientTick - t.landedAt + ahead;
                m.jumpDelay = m.onGround ? Math.max(0, wait - (onGround + 1) / 2) : wait;
                m.fullTick = true;
                // it turns toward the player 10 degrees a tick: its facing seen is `ahead` ticks old
                m.yRot = MobSim.rotlerp(e.getYRot(), (float) AuraSim.yawTo(at.x, at.z, p.getX(), p.getZ()), 10.0F * ahead);
                m.walking = false;
                m.attackCooldown = 0;
            }
            case EVOKER -> {
                // casting (synced): the fangs come 18 ticks after the cast began (its warmup of 10 goal ticks, the first
                // in the tick it starts: in a lockstep recording, 18 both times); a cast
                // of its other spell is taken as fangs too (the worse). Not casting: none sooner than 28 ticks
                m.walking = false;
                int sinceCast = t == null ? 1000 : clientTick - t.castAt + ahead;
                m.castIn = ((Evoker) e).isCastingSpell() && sinceCast <= 18 ? Math.max(0, 18 - sinceCast) : -1;
            }
            case VEX -> {   // flying through blocks: carried on in height too
                m.y = at.y + vy * lead;
                m.onGround = false;
                m.vx = vx * MobSim.AIR_FRICTION; m.vy = vy * 0.98F; m.vz = vz * MobSim.AIR_FRICTION;
                m.walking = false;
                m.attackCooldown = 0;
            }
            case BREEZE -> {
                // its Shoot: the SHOOTING pose (synced) for 15 ticks of charge, then the wind charge. Not in the pose,
                // it may take it the next tick: a shot 15 on. In the pose with its charge already seen: none more
                m.walking = false;
                if (e.getPose() == Pose.SHOOTING && t != null) {
                    boolean shot = t.shots > t.shotsAtPose;
                    m.shootIn = shot ? -1 : Math.max(0, 15 - (clientTick - t.poseAt + ahead));
                } else {
                    m.shootIn = 15;
                }
            }
            case PHANTOM -> {
                // flying: carried on in height too; its move control's speed from its velocity (which settles at 0.67
                // of the wanted one); taken as swooping (a circling one keeps 20 or more above the player, out of the
                // snapshot's reach)
                m.y = at.y + vy * lead;
                m.onGround = false;
                m.vx = vx * MobSim.AIR_FRICTION; m.vy = vy * MobSim.AIR_FRICTION; m.vz = vz * MobSim.AIR_FRICTION;
                m.flySpeed = Math.max(0.1, Math.min(1.8, Math.sqrt(vx * vx + vy * vy + vz * vz) / 0.669));
                m.swoop = true;
                m.walking = false;
                m.attackCooldown = 0;
            }
            case BLAZE -> {
                // its attack step from what the client sees: the charged flag (BlazeAttackGoal sets it for the 60-tick
                // charge and the three fireballs after) and the fireballs of its seen since; each wait at its shortest
                m.walking = false;
                m.charged = e.isOnFire();
                if (m.charged) {
                    int fired = t == null ? 0 : t.shots - t.shotsAtCharge;
                    if (fired <= 0) {
                        m.blazeStep = 1;
                        // the flag reaches the client 3 to 5 ticks after the server set it under lag 2 (trace5_blaze_lag2):
                        // two more than `ahead`, so the first fireball is never taken as later than it is
                        m.attackTime = Math.max(0, 60 - (t == null ? 1000 : clientTick - t.chargedAt + ahead + 2));
                    } else {
                        m.blazeStep = 1 + Math.min(3, fired);
                        m.attackTime = Math.max(0, 6 - (clientTick - t.thrownAt + ahead));
                    }
                } else {
                    m.blazeStep = 0;
                    m.attackTime = Math.max(0, 100 - (t == null ? 1000 : clientTick - t.unchargedAt + ahead));
                }
            }
            case CREEPER -> {
                Creeper c = (Creeper) e;
                m.swellDir = c.isIgnited() ? 1 : c.getSwellDir();
                m.swell = Math.max(0, Math.min(29, Math.round(c.getSwelling(1.0F) * 28.0F) + m.swellDir * ahead));
                m.swelling = m.swellDir > 0;
                m.blastRadius = c.isPowered() ? 6.0 : 3.0;
                if (m.swelling) { m.active = false; m.walking = false; }
            }
        }
        return m;
    }

    /** The other mobs that walk at you and hit in reach, modelled with the melee goal: vindicators, enderman (when
     *  the aura counts them angry), silverfish, endermites (MeleeAttackGoal); piglin brutes, piglins with a sword or
     *  nothing, hoglins and zoglins (their brains' MeleeAttack, which never stops on a finished path: modelled as a
     *  following goal, with their cooldown); ravagers (MeleeAttackGoal(1.0, true), 12 damage and a strong push; the
     *  10 ticks it stands after a hit and its roar when a shield blocks it are left out). Zombified piglins are in the
     *  zombie family (ZombieAttackGoal). */
    static boolean walker(Entity e) {
        return e instanceof Vindicator || e instanceof EnderMan || e instanceof Silverfish || e instanceof Endermite
            || e instanceof PiglinBrute || e instanceof Piglin pg && !pg.isHolding(Items.CROSSBOW)
            || e instanceof Hoglin || e instanceof Zoglin || e instanceof ZombifiedPiglin || e instanceof Ravager;
    }

    /** Whether a walker's goal keeps on after its path is done (the brain-driven ones and the spiders). */
    static boolean follows(Entity e) {
        return e instanceof PiglinBrute || e instanceof Piglin || e instanceof Hoglin || e instanceof Zoglin
            || e instanceof Ravager;   // MeleeAttackGoal(1.0, true)
    }

    /** Ticks between a mob's attacks: 20 (MeleeAttackGoal; piglins' MeleeAttack.create(20)), 40 for an adult hoglin
     *  or zoglin and 15 a baby (HoglinAi, Zoglin). */
    static int attackInterval(Entity e) {
        if (e instanceof Hoglin || e instanceof Zoglin) return ((LivingEntity) e).isBaby() ? 15 : 40;
        return MobSim.ATTACK_INTERVAL;
    }

    /** Evoker fangs near the player: where each stands and the ticks until it bites. A fang bites 7 ticks after the
     *  client hears it start (its attack event; EvokerFangs.getAnimationProgress counts from it); one not started yet
     *  may start the next tick: 8. */
    static double[][] fangs(Minecraft mc, LocalPlayer p, double lag) {
        List<double[]> out = new ArrayList<>();
        int ahead = (int) Math.round(1.0 + lag);
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(8))) {
            if (!(e instanceof EvokerFangs f)) continue;
            float prog = f.getAnimationProgress(0.0F);
            int bite;
            if (prog <= 0.0F) bite = 8;
            else {
                int since = Math.round(prog * 20.0F) + ahead;
                if (since > 7) continue;   // bitten already
                bite = 7 - since;
            }
            out.add(new double[]{e.getX(), e.getY(), e.getZ(), bite});
        }
        return out.toArray(new double[0][]);
    }

    /** ATTACK_DAMAGE's base for a mob (the attribute isn't synced, so from each type's code). */
    static double baseDamage(LivingEntity e) {
        if (e instanceof WitherSkeleton) return 4.0;   // set in WitherSkeleton.finalizeSpawn
        if (e instanceof ZombifiedPiglin || e instanceof Vindicator || e instanceof Piglin) return 5.0;
        if (e instanceof Zombie) return 3.0;           // Zombie.createAttributes
        if (e instanceof EnderMan || e instanceof PiglinBrute) return 7.0;
        if (e instanceof Hoglin || e instanceof Zoglin) return e.isBaby() ? 0.5 : 8.0;   // 6 / 2 + nextInt(6): at most 8
        if (e instanceof Silverfish) return 1.0;
        if (e instanceof Ravager) return 12.0;         // Ravager.createAttributes
        if (e instanceof Vex) return 4.0;              // Vex.createAttributes (and the sword it holds, added below)
        if (e instanceof Blaze) return 6.0;            // Blaze.createAttributes
        if (e instanceof Phantom ph) return 6.0 + ph.getPhantomSize();   // Phantom.updatePhantomSizeInfo
        if (e instanceof Slime sl) return sl.getSize() + (e instanceof MagmaCube ? 2.0 : 0.0);   // setSize; MagmaCube.getAttackDamage
        return 2.0;                                    // Monster.createMonsterAttributes: skeletons, spiders, creepers
    }

    /**
     * The projectiles in flight near the player that aren't its own, as the projectile dodge sees them (moving faster
     * than 0.1 a tick), carried on by the lag: arrows (ceil(speed x base), base at a mob's top, 2.794 on normal),
     * tridents (8), thrown items (no damage: their knockback), the rest flying straight (5).
     */
    static ArrowSim[] projectiles(Minecraft mc, LocalPlayer p, double lag) {
        List<ArrowSim> out = new ArrayList<>();
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(RANGE + 16))) {
            if (!(e instanceof Projectile proj) || proj.getOwner() == p) continue;
            Vec3 v = new Vec3(e.getX() - e.xOld, e.getY() - e.yOld, e.getZ() - e.zOld);
            if (v.lengthSqr() < 0.01) v = e.getDeltaMovement();
            if (v.lengthSqr() < 0.01) continue;
            Ballistics.Spec spec = ProjectileDodge.flightOf(proj);
            ArrowSim a = new ArrowSim();
            a.id = e.getId();
            a.x = e.getX(); a.y = e.getY(); a.z = e.getZ();
            // the last tick's move is the velocity before this tick's drag and gravity for an arrow (ProjectileDodge)
            if (spec.gravityFirst()) { a.vx = v.x; a.vy = v.y; a.vz = v.z; }
            else { a.vx = v.x * spec.drag(); a.vy = v.y * spec.drag() - spec.gravity(); a.vz = v.z * spec.drag(); }
            a.gravity = spec.gravity(); a.drag = spec.drag(); a.gravityFirst = spec.gravityFirst();
            a.age = Math.max(1, e.tickCount);
            if (e instanceof ThrownTrident) a.fixed = 8.0;
            else if (e instanceof AbstractArrow) a.base = 2.0 + mc.level.getDifficulty().getId() * 0.11 + 0.57425;
            else if (e instanceof AbstractThrownPotion pot) {   // a splash: what the witch threw, by its contents
                a.splash = true;
                PotionContents pc = pot.getItem().getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
                if (pc.is(Potions.HARMING)) a.splashDamage = 6.0;
                else if (pc.is(Potions.STRONG_HARMING)) a.splashDamage = 12.0;
                else if (pc.is(Potions.POISON) || pc.is(Potions.STRONG_POISON) || pc.is(Potions.LONG_POISON)) a.splashCost = 8.0;
                else if (pc.potion().isPresent()) a.splashCost = 3.0;
            }
            else if (e instanceof ThrowableProjectile) a.fixed = 1.0E-6;   // snowballs, eggs, pearls: the knockback only
            else a.fixed = 5.0;
            if (e instanceof AbstractHurtingProjectile hp) {   // fireballs speed up (applyInertia)
                a.accel = hp.accelerationPower;
                a.inertia = e.isInWater() ? 0.8 : 0.95;
                if (e instanceof SmallFireball) a.extra = FightWorld.FIRE_EXTRA;
                if (e instanceof AbstractWindCharge) { a.fixed = 1.0; a.burst = 3.0; }   // straight (no speed-up)
                if (e instanceof LargeFireball) {   // a ghast's: 6 on a hit and a blast of power 1 where it stops (about
                    a.splash = true;                // 6 more at the body, less 1 to 2 blocks off): taken as a splash,
                    a.splashDamage = 12.0;          // 12 on a hit, falling off over 4 blocks (the worse)
                    a.fixed = 0;
                }
            }
            a.scales = !(proj.getOwner() instanceof net.minecraft.world.entity.player.Player);
            // flown on to where the server may have it now: the client's copy trails it by 1 to 4 ticks under lag
            // (measured, trace2_lag2), and a copy carried too far looks already past the player while the real one
            // is still coming (a lockstep debug run: the plan relaxed and the arrow hit 2 ticks later). So one copy
            // for each tick it may trail by, 1 to 1 + lag: the plan scores a hit by any of them (the hurt window lets
            // only one count).
            int most = Math.max(1, (int) Math.round(1.0 + lag));
            for (int k = 1; k <= most; k++) {
                ArrowSim c = k == most ? a : a.copy();
                for (int i = 0; i < k; i++) {
                    if (c.accel > 0) c.inertia();
                    else if (c.gravityFirst) { c.vy -= c.gravity; c.vx *= c.drag; c.vy *= c.drag; c.vz *= c.drag; }
                    c.x += c.vx; c.y += c.vy; c.z += c.vz;
                    if (!c.gravityFirst && c.accel <= 0) { c.vx *= c.drag; c.vy *= c.drag; c.vz *= c.drag; c.vy -= c.gravity; }
                    c.age++;
                }
                out.add(c);
            }
        }
        return out.toArray(new ArrowSim[0]);
    }

    /** The top of the first collision shape at or below (x, y, z) in the snapshot, else y. */
    static double floorBelow(BlockGrid g, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y + 1e-6); by >= g.y0; by--) {
            BlockInfo b = g.get(bx, by, bz);
            double top = b.shape.top();
            if (!b.shape.empty && by + top <= y + 1e-6) return by + top;
        }
        return y;
    }
}
