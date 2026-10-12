package ai.rrw.kine.fight;

import ai.rrw.kine.Settings;
import ai.rrw.kine.combat.AuraState;
import ai.rrw.kine.combat.KillAura;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Drowned;
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
 * chasing is taken as chasing (the pessimistic side).
 */
public final class FightSnapshot {
    private FightSnapshot() {}

    /** Ticks a mob's last received position is carried forward (measured trail of the interpolation target in a
     *  lockstep test, no network), and how far its drawn position trails the server (what the kill aura aims by). Both
     *  grow by the network's lag, taken from the player's ping (the tab list's latency; a test can set kine.pingMs,
     *  as a lockstep test's injected lag is invisible to the game). */
    public static final double LEAD_TICKS = 2.6, DRAWN_TRAIL_TICKS = 3.7;
    /** Mobs within this of the player are simulated. */
    public static final double RANGE = 12.0;
    /** The block snapshot's reach around the player: sideways, down and up. */
    public static final int GRID_R = 12, GRID_DOWN = 6, GRID_UP = 4;

    /** What the combat movement needs from a snapshot. */
    public record Shot(FightWorld world, BlockGrid grid, Body body, int zombies, int unsupported, double nearest) {}

    // per mob: the last received positions (tick, x, y, z) and the tick its last swing was seen
    private static final class Track {
        int tick0 = -1; double x0, y0, z0;     // the latest received position and when it changed
        int tick1 = -1; double x1, y1, z1;     // the one before
        int swungAt = Integer.MIN_VALUE / 2;
        boolean wasSwinging;
        int seen;
    }
    private static final Map<Integer, Track> tracks = new HashMap<>();
    private static int clientTick = 0;

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
            t.wasSwinging = m.swinging;
        }
        if ((clientTick & 63) == 0) tracks.values().removeIf(t -> clientTick - t.seen > 100);
    }

    /** The zombie family the model covers: zombies, husks, zombie villagers, drowned without a trident (not zombified
     *  piglins, which are neutral and handled by the aura's anger rules, not modelled). */
    static boolean modelled(Entity e) {
        if (!(e instanceof Zombie) || e instanceof ZombifiedPiglin) return false;
        return !(e instanceof Drowned d) || !d.getMainHandItem().is(Items.TRIDENT) && !d.getOffhandItem().is(Items.TRIDENT);
    }

    /** The whole snapshot, or null if the player can't be simulated now. */
    public static Shot take(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return null;
        List<LivingEntity> zs = new ArrayList<>();
        int unsupported = 0;
        double nearest = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(RANGE))) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || !KillAura.targets(e, p)) continue;
            double d = e.distanceTo(p);
            if (modelled(e)) { zs.add(le); nearest = Math.min(nearest, d); }
            else if (d <= 8.0) unsupported++;
        }
        BlockGrid grid = WorldSnapshot.around(mc.level, p, GRID_R, GRID_DOWN, GRID_UP);
        Body body = WorldSnapshot.body(p);
        FightWorld w = new FightWorld();
        w.player = WorldSnapshot.state(p);
        w.health = p.getHealth();
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
        double lag = lagTicks(mc, p);
        w.mobs = new MobSim[zs.size()];
        for (int i = 0; i < zs.size(); i++) w.mobs[i] = mob(zs.get(i), p, grid, difficulty, lag);
        w.segmentYaw = Float.NaN;
        return new Shot(w, grid, body, zs.size(), unsupported, nearest);
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

    static MobSim mob(LivingEntity e, LocalPlayer p, BlockGrid grid, double difficulty, double lag) {
        MobSim m = new MobSim();
        m.id = e.getId();
        Track t = tracks.get(e.getId());
        Vec3 at = e.getInterpolation() != null ? e.getInterpolation().position() : e.position();
        double vx = 0, vy = 0, vz = 0;
        if (t != null && t.tick1 >= 0 && t.tick0 > t.tick1) {
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
        m.width = e.getBbWidth(); m.height = e.getBbHeight();
        m.speed = e.getAttributeValue(Attributes.MOVEMENT_SPEED);
        m.health = e.getHealth();
        m.armour = e.getArmorValue();
        m.toughness = e.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        m.bonus = AuraState.bonus(p.getMainHandItem(), e);
        double base = 3.0;   // Zombie.createAttributes, plus a held weapon's damage
        double[] add = {0};
        e.getItemBySlot(EquipmentSlot.MAINHAND).forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.value() == Attributes.ATTACK_DAMAGE.value()) add[0] += mod.amount();
        });
        double dmg = base + add[0];
        m.attackDamage = difficulty < 0 ? Math.min(dmg / 2 + 1, dmg) : dmg * difficulty;
        // its cooldown from the last swing seen (the swing reaches the client about 2 ticks after the server's attack)
        int since = t == null ? 1000 : clientTick - t.swungAt + 2;
        m.attackCooldown = Math.max(0, MobSim.ATTACK_INTERVAL - since);
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
        m.pathIn = 0;
        return m;
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
