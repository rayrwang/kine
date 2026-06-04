package ai.rrw.kine.combat;

import ai.rrw.kine.Settings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
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
 * Toggleable melee kill aura against hostile mobs and angry neutral mobs.
 *
 * <p>The 26.1.2 combat numbers drive the whole design (all read from the decompiled jar):
 * an attack's base damage is scaled by {@code 0.2 + 0.8*charge^2} (a quadratic curve with a hard 20%
 * floor), the charge ticker resets on every swing, and a struck mob is invulnerable to our follow-ups
 * for the first 10 ticks (it only re-takes full damage once its i-frame window drops to <=10, i.e.
 * ~0.5 s later). Those three facts mean there is no single best behaviour, so the aura switches mode by
 * how many targets are in reach and what's in hand:
 * <ul>
 *   <li><b>Sweep</b> &mdash; a Sweeping-Edge sword on the ground against a cluster: wait for a full
 *       charge and strike the mob with the most neighbours in its sweep box, so one swing AoEs the group.</li>
 *   <li><b>Spam</b> &mdash; a real crowd of fresh targets: swing every tick at the nearest non-i-framed
 *       mob. Each hit is only the 20% floor, but resetting the cooldown to land many hits on many fresh
 *       mobs out-aggregates waiting once there are enough of them.</li>
 *   <li><b>Wait</b> &mdash; one or a few targets: hold until a full charge, then strike, timed to the
 *       0.5 s i-frame window so every hit is a real hit.</li>
 * </ul>
 * Per-mob i-frame tracking skips mobs we hit less than 10 ticks ago so swings are never wasted.
 *
 * <p>Targets are mobs only (never players, armour stands or animals): hostiles ({@link Enemy}) always,
 * and neutral mobs only while angry at us &mdash; detected client-side from {@code getLastDamageSource},
 * since a mob's AI anger isn't synced. Toggled from the mod's settings menu (K). It attacks entities directly in reach without turning the view,
 * with a line-of-sight check so it won't swing through walls. This is a combat cheat: expect a ban
 * anywhere that doesn't allow client mods.
 */
public final class KillAura {
    private KillAura() {}

    // --- tuning ---------------------------------------------------------------------------------
    private static final int   HIT_REFRACTORY = 10;   // a struck mob sets invulnerableTime=20 and is re-hittable for full damage only at <=10 -- i.e. exactly 10 ticks later
    private static final float FULL_CHARGE    = 1.0f;  // wait/sweep gate. Vanilla enables sweep/crit at scale>0.9, but a sword/axe's charge time (>=12.5t) outlasts the 10t i-frame, so you're charge-limited: hitting at 0.9 instead of full just resets the ticker ~1.5t early for ~5% less per hit. A full charge is the DPS-optimal point (and also maximises sweep base damage).
    private static final int   ANGRY_TICKS    = 600;   // a neutral mob that hits you stays a target this long (~30 s)
    private static final int   WEAPON_PERIOD  = 10;    // re-evaluate the best weapon at most this often (~0.5 s) while engaging
    private static final double REACH_PAD     = 0.0;   // attack at exactly the vanilla entity-interaction range
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

        double reach = p.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) + REACH_PAD;
        Vec3 eye = p.getEyePosition();
        AABB area = p.getBoundingBox().inflate(reach + 1.0);

        List<LivingEntity> inReach = new ArrayList<>();
        LivingEntity nearestTarget = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p, area, x -> isTarget(x))) {
            LivingEntity t = (LivingEntity) e;
            if (eyeToBox(eye, t.getBoundingBox()) > reach) continue;
            if (!lineOfSight(p, eye, t)) continue;
            inReach.add(t);
            double d = eye.distanceToSqr(t.getBoundingBox().getCenter());
            if (d < nearestDist) { nearestDist = d; nearestTarget = t; }
        }
        if (inReach.isEmpty()) return;

        // Auto weapon selection: equip the best sword/axe for this target before swinging. Re-evaluated
        // periodically (the target type can change) and immediately when not already holding a weapon.
        if (Settings.autoWeapon) {
            ItemStack cur = p.getMainHandItem();
            boolean holdingWeapon = cur.is(h -> h.is(ItemTags.SWORDS)) || cur.is(h -> h.is(ItemTags.AXES));
            if (!holdingWeapon || tick - lastWeaponTick >= WEAPON_PERIOD) {
                lastWeaponTick = tick;
                if (WeaponSelect.choose(mc, p, nearestTarget, inReach.size())) return;   // switched; swing next tick once it syncs
            }
        }

        ItemStack held = p.getMainHandItem();
        boolean sweepSword = held.is(h -> h.is(ItemTags.SWORDS)) && enchantLevel(held, Enchantments.SWEEPING_EDGE) > 0;
        float charge = p.getAttackStrengthScale(0.5f);

        // Sweep: a Sweeping-Edge sword, on the ground, fully charged, against a cluster -> one swing hits the group.
        if (sweepSword && p.onGround() && charge >= FULL_CHARGE) {
            LivingEntity hub = bestSweepTarget(inReach);
            if (hub != null) {
                attack(mc, p, hub);
                AABB box = hub.getBoundingBox().inflate(SWEEP_X, SWEEP_Y, SWEEP_Z);   // the sweep caught these too
                for (LivingEntity o : inReach) if (o != hub && box.intersects(o.getBoundingBox())) lastHit.put(o.getId(), tick);
                return;
            }
        }

        // Otherwise pick the nearest mob we can actually re-hit (not still in our i-frame window).
        int fresh = 0;
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity t : inReach) {
            if (refractory(t)) continue;
            fresh++;
            double d = eye.distanceToSqr(t.getBoundingBox().getCenter());
            if (d < best) { best = d; nearest = t; }
        }
        if (nearest == null) return;   // everything we can see is still i-framed -- nothing to gain by swinging

        // Spam-vs-wait breakeven, from the real combat maths: every swing resets the charge ticker, so a
        // spammed hit is the 20% floor (~0.2*base); you can land one per fresh mob and a mob is fresh every
        // 10 ticks, giving spam aggregate ~= 0.02*N*base per tick. Waiting lands one full hit (base) per
        // attack delay D (a sword/axe is charge-limited since D >= 12.5 > 10), i.e. base/D per tick. Spam
        // wins past N = 50/D fresh mobs -- ~5 for a sword, ~3 for a netherite axe. (Enchantments barely
        // touch the floor-damage spam hit but fully count toward the full hit, so they nudge this up a little.)
        int spamMin = (int) Math.floor(50.0 / p.getCurrentItemAttackStrengthDelay()) + 1;

        if (fresh >= spamMin) {
            attack(mc, p, nearest);                          // crowd: swing every tick at a fresh mob
        } else if (charge >= FULL_CHARGE) {
            attack(mc, p, nearest);                          // few: only on a full charge, timed to the i-frame window
        }
    }

    /** Mobs only; hostiles always, neutral mobs only while angry at us. Used as the gather predicate. */
    private static boolean isTarget(Entity e) {
        if (!(e instanceof Mob mob) || !mob.isAlive() || mob.isSpectator()) return false;
        if (mob instanceof Enemy) return true;
        Integer until = angryUntil.get(mob.getId());
        return until != null && until > tick;
    }

    /** A neutral mob that freshly damaged the player is treated as a target for a while. */
    private static void trackAnger(LocalPlayer p) {
        if (p.hurtTime > lastHurtTime) {
            DamageSource src = p.getLastDamageSource();
            if (src != null && src.getEntity() instanceof Mob mob && !(mob instanceof Enemy)) {
                angryUntil.put(mob.getId(), tick + ANGRY_TICKS);
            }
        }
        lastHurtTime = p.hurtTime;
        if ((tick & 63) == 0) {   // occasional prune so the maps don't grow without bound
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

    /** The reachable, fresh target whose sweep box catches the most others (>=1, else not worth a sweep). */
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

    private static int enchantLevel(ItemStack s, ResourceKey<Enchantment> key) {
        ItemEnchantments ench = s.getEnchantments();
        for (Holder<Enchantment> h : ench.keySet()) if (h.is(key)) return ench.getLevel(h);
        return 0;
    }
}
