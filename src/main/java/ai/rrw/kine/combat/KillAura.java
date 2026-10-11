package ai.rrw.kine.combat;

import ai.rrw.kine.Settings;
import ai.rrw.kine.util.Enchant;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Melee kill aura against hostile mobs and neutral mobs that have hit you, toggled in the settings
 * screen. It attacks mobs in reach with line of sight, without turning the view.
 *
 * <p>From the 26.1.2 combat code: a hit's damage is scaled by {@code 0.2 + 0.8*charge^2}, the charge
 * resets on every swing, and a struck mob takes full damage again only after 10 ticks. So the aura
 * picks a mode from the number of targets and the weapon:
 * <ul>
 *   <li><b>Sweep</b>: a Sweeping Edge sword on the ground against a cluster. Wait for a full charge and
 *       hit the mob with the most neighbours in its sweep box.</li>
 *   <li><b>Spam</b>: a crowd of fresh targets. Swing every tick at the nearest mob not hit in the last
 *       10 ticks. Each hit does only the 20% floor, but many hits on many mobs beat waiting.</li>
 *   <li><b>Wait</b>: one or a few targets. Strike on a full charge, so every hit lands full damage.</li>
 * </ul>
 *
 * <p>Targets are mobs only (never players, armour stands or animals): hostiles ({@link Enemy}) always,
 * neutral mobs (endermen and zombified piglins included, though the game counts them as hostile) only
 * for a while after they damage you, read client-side from {@code getLastDamageSource} because mob
 * anger isn't synced to the client. Adult piglins attack a player without gold armour, so they are
 * targets then; while you wear gold they are neutral.
 */
public final class KillAura {
    private KillAura() {}

    // A struck mob sets invulnerableTime to 20 and takes full damage again once it is at or below 10,
    // that is 10 ticks later.
    private static final int   HIT_REFRACTORY = 10;
    // Charge needed to swing in the wait and sweep modes. Vanilla allows sweeps and crits above 0.9, but a
    // sword or axe takes at least 12.5 ticks to charge, longer than the 10-tick window, so swinging at 0.9
    // only restarts the charge 1.5 ticks early for 5% less damage. A full charge gives the most damage per
    // second and the largest sweep.
    private static final float FULL_CHARGE    = 1.0f;
    private static final int   ANGRY_TICKS    = 600;   // a neutral mob that hits you stays a target this long (30 s)
    private static final int   WEAPON_PERIOD  = 10;    // re-pick the best weapon at most this often while fighting
    private static final double SWEEP_X = 1.0, SWEEP_Y = 0.25, SWEEP_Z = 1.0;   // vanilla sweep-box inflation

    private static final Map<Integer, Integer> lastHit    = new HashMap<>();   // entityId -> tick we last hit it
    private static final Map<Integer, Integer> angryUntil = new HashMap<>();   // neutral entityId -> tick its anger lapses
    private static int tick = 0;
    private static int lastHurtTime = 0;   // player's hurtTime last tick, to spot a fresh incoming hit
    private static int lastWeaponTick = -100;   // last tick auto weapon selection ran

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(KillAura::onTick);   // toggled from the settings menu (K)
    }

    private static void onTick(Minecraft mc) {
        tick++;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { lastHit.clear(); angryUntil.clear(); return; }

        trackAnger(p);   // learn which neutral mobs are angry at us, even when the aura is off

        if (!Settings.killAura || mc.gameMode == null || mc.screen != null || !p.isAlive() || p.isUsingItem()) return;

        double reach = p.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        Vec3 eye = p.getEyePosition();
        AABB area = p.getBoundingBox().inflate(reach + 1.0);

        List<LivingEntity> inReach = new ArrayList<>();
        LivingEntity nearestTarget = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p, area, x -> isTarget(x, p))) {
            LivingEntity t = (LivingEntity) e;
            if (eyeToBox(eye, t.getBoundingBox()) > reach) continue;
            if (!lineOfSight(p, eye, t)) continue;
            inReach.add(t);
            double d = eye.distanceToSqr(t.getBoundingBox().getCenter());
            if (d < nearestDist) { nearestDist = d; nearestTarget = t; }
        }
        if (inReach.isEmpty()) return;

        // Auto weapon: equip the best sword or axe for this target before swinging. Re-picked every
        // WEAPON_PERIOD ticks (the target can change), and at once when not holding a weapon.
        if (Settings.autoWeapon) {
            ItemStack cur = p.getMainHandItem();
            boolean holdingWeapon = cur.is(h -> h.is(ItemTags.SWORDS)) || cur.is(h -> h.is(ItemTags.AXES));
            if (!holdingWeapon || tick - lastWeaponTick >= WEAPON_PERIOD) {
                lastWeaponTick = tick;
                if (WeaponSelect.choose(mc, p, nearestTarget, inReach.size())) return;   // switched: swing next tick, once the server has it
            }
        }

        ItemStack held = p.getMainHandItem();
        boolean sweepSword = held.is(h -> h.is(ItemTags.SWORDS)) && Enchant.level(held, Enchantments.SWEEPING_EDGE) > 0;
        float charge = p.getAttackStrengthScale(0.5f);

        // Sweep: a Sweeping Edge sword, on the ground, fully charged, against a cluster.
        if (sweepSword && p.onGround() && charge >= FULL_CHARGE) {
            LivingEntity hub = bestSweepTarget(inReach);
            if (hub != null) {
                attack(mc, p, hub);
                AABB box = hub.getBoundingBox().inflate(SWEEP_X, SWEEP_Y, SWEEP_Z);   // the sweep caught these too
                for (LivingEntity o : inReach) if (o != hub && box.intersects(o.getBoundingBox())) lastHit.put(o.getId(), tick);
                return;
            }
        }

        // Otherwise the nearest mob that takes full damage again (not hit in the last 10 ticks).
        int fresh = 0;
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity t : inReach) {
            if (refractory(t)) continue;
            fresh++;
            double d = eye.distanceToSqr(t.getBoundingBox().getCenter());
            if (d < best) { best = d; nearest = t; }
        }
        if (nearest == null) return;   // every target was hit too recently: a swing would do nothing

        // Spam or wait: a spammed hit does the 20% floor, one per fresh mob, and a mob is fresh every 10
        // ticks, so spam deals about 0.02 * N * base per tick. Waiting deals one full hit per attack delay D
        // (D >= 12.5 for a sword or axe), base / D per tick. Spam wins past N = 50 / D fresh mobs: 5 for a
        // sword, 3 for a netherite axe. Enchantments add little to the floor hit, so they raise this a bit.
        int spamMin = spamThreshold(p.getCurrentItemAttackStrengthDelay());

        if (fresh >= spamMin) {
            attack(mc, p, nearest);                          // crowd: swing every tick
        } else if (charge >= FULL_CHARGE) {
            attack(mc, p, nearest);                          // few: only on a full charge
        }
    }

    /** Fresh targets in reach from which spamming beats waiting, for a weapon with this attack delay
     *  (ticks to a full charge): more than 50 / delay. */
    static int spamThreshold(float attackDelay) {
        return (int) Math.floor(50.0 / attackDelay) + 1;
    }

    /** Mobs only: hostiles always, neutral mobs only for a while after they hit you. */
    private static boolean isTarget(Entity e, LivingEntity player) {
        if (!(e instanceof Mob mob) || !mob.isAlive() || mob.isSpectator()) return false;
        if (mob instanceof Enemy && !neutral(mob)) return true;
        if (piglinHostile(mob, player)) return true;
        Integer until = angryUntil.get(mob.getId());
        return until != null && until > tick;
    }

    /** Mobs that leave the player alone until provoked, though some count as hostile (Enemy) in the game:
     *  the NeutralMob ones (endermen, zombified piglins, wolves, bees, iron golems, polar bears) and
     *  piglins, which ignore players wearing gold (see piglinHostile). Piglin brutes attack anyone and
     *  stay hostile. */
    private static boolean neutral(Mob mob) {
        return mob instanceof NeutralMob || mob instanceof Piglin;
    }

    /** An adult piglin attacks a player who wears no gold armour (the game's own test), so it is a
     *  target then, before it hits. Baby piglins never attack. */
    private static boolean piglinHostile(Mob mob, LivingEntity player) {
        return mob instanceof Piglin piglin && !piglin.isBaby() && !PiglinAi.isWearingSafeArmor(player);
    }

    /** A neutral mob that freshly damaged the player is treated as a target for a while. */
    private static void trackAnger(LocalPlayer p) {
        if (p.hurtTime > lastHurtTime) {
            DamageSource src = p.getLastDamageSource();
            if (src != null && src.getEntity() instanceof Mob mob && (neutral(mob) || !(mob instanceof Enemy))) {
                angryUntil.put(mob.getId(), tick + ANGRY_TICKS);
            }
        }
        lastHurtTime = p.hurtTime;
        if ((tick & 63) == 0) {   // prune now and then so the maps don't grow without bound
            angryUntil.values().removeIf(v -> v <= tick);
            lastHit.values().removeIf(v -> v + HIT_REFRACTORY <= tick);
        }
    }

    private static boolean refractory(Entity e) {
        Integer t = lastHit.get(e.getId());
        return t != null && tick - t < HIT_REFRACTORY;
    }

    private static void attack(Minecraft mc, LocalPlayer p, LivingEntity target) {
        mc.gameMode.attack(p, target);
        p.swing(InteractionHand.MAIN_HAND);
        lastHit.put(target.getId(), tick);
    }

    /** The fresh target whose sweep box catches the most others (at least one, else null). */
    private static LivingEntity bestSweepTarget(List<LivingEntity> targets) {
        LivingEntity best = null;
        int bestN = 0;
        for (LivingEntity t : targets) {
            if (refractory(t)) continue;
            AABB box = t.getBoundingBox().inflate(SWEEP_X, SWEEP_Y, SWEEP_Z);
            int n = 0;
            for (LivingEntity o : targets) if (o != t && box.intersects(o.getBoundingBox())) n++;
            if (n > bestN) { bestN = n; best = t; }
        }
        return best;
    }

    /** Distance from the eye to the nearest point of a bounding box (how vanilla measures melee reach). */
    private static double eyeToBox(Vec3 eye, AABB bb) {
        double dx = Math.max(Math.max(bb.minX - eye.x, 0.0), eye.x - bb.maxX);
        double dy = Math.max(Math.max(bb.minY - eye.y, 0.0), eye.y - bb.maxY);
        double dz = Math.max(Math.max(bb.minZ - eye.z, 0.0), eye.z - bb.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean lineOfSight(LocalPlayer p, Vec3 eye, LivingEntity t) {
        Vec3 aim = t.getBoundingBox().getCenter();
        BlockHitResult hit = p.level().clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.MISS
            || hit.getLocation().distanceToSqr(eye) >= aim.distanceToSqr(eye) - 1.0e-3;
    }

}
