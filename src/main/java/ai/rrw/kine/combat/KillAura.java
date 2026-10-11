package ai.rrw.kine.combat;

import ai.rrw.kine.Settings;
import ai.rrw.kine.mixin.KeyMappingAccessor;
import ai.rrw.kine.mixin.MinecraftInvoker;
import ai.rrw.kine.util.Enchant;
import ai.rrw.kine.util.MouseTurn;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Melee kill aura against hostile mobs and neutral mobs that have hit you, toggled in the settings
 * screen. It attacks mobs in reach with line of sight, as a vanilla client would: just before the game's
 * keybind step it turns toward the target by whole mouse counts, has the game pick what that rotation's
 * crosshair is on, and attacks through the game's own startAttack only if that is a target; the player's
 * move packet then carries that rotation, and once it has gone out the view is turned back by whole counts
 * to where it was, so your view never moves (the server sees a flick there and back). For that one tick
 * your walking follows the turned rotation, as it would for a vanilla client.
 *
 * <p>From the 26.1.2 combat code: a hit's damage is scaled by {@code 0.2 + 0.8*charge^2}, the charge
 * resets on every swing, and a struck mob takes full damage again only after 10 ticks. So the aura
 * picks a mode from the number of targets and the weapon:
 * <ul>
 *   <li><b>Sweep</b>: a Sweeping Edge sword on the ground against a cluster, when the hit will sweep (not a
 *       sprint hit, not moving fast). Wait for a full charge and hit the mob with the most neighbours in its
 *       sweep box.</li>
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
    private static Item lastHeld = null;        // the main-hand item when last checked, to see a switch

    private static MouseTurn.Turn flick = MouseTurn.Turn.NONE;   // this tick's turn toward a target, undone after it is sent
    private static int attacksSent = 0;   // attacks the game has sent (MultiPlayerGameModeMixin)

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(KillAura::onTick);   // toggled from the settings menu (K)
    }

    /** The game sent an attack or a spear's stab (MultiPlayerGameModeMixin). */
    public static void onAttackSent() {
        attacksSent++;
    }

    private static void onTick(Minecraft mc) {
        tick++;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { lastHit.clear(); angryUntil.clear(); return; }
        trackAnger(p);   // learn which neutral mobs are angry at us, even when the aura is off
    }

    /** Fights, just before the game's keybind step (VanillaActions); no screen is open here. */
    public static void beforeKeybinds(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!Settings.killAura || p == null || mc.level == null || mc.gameMode == null || !p.isAlive() || p.isUsingItem()) return;
        if (p.isSpectator()) return;   // a spectator's startAttack spectates the mob: the server moves the camera into it
        // A new item in the main hand (auto weapon's, or yours): the game sends the slot change at the start of the
        // tick after the switch, this one, and the server takes the item's attack damage and speed only when it next
        // ticks the player, so a swing now would hit with the old item's. Swing from the next tick.
        Item heldItem = p.getMainHandItem().getItem();
        if (heldItem != lastHeld) { lastHeld = heldItem; return; }
        // The game's startAttack refuses while missTime is up: after a click in the air, and after an inventory
        // close (InventoryClicks.closeAsVanilla) until the tick after next, as for a vanilla client.
        if (mc.missTime > 0) return;
        // Your own right-click goes in this tick's keybind step, along whatever the crosshair is on then: with a
        // swing's turn on, toward the aura's target (a pearl, a potion, a crossbow bolt). No swing in that tick.
        if (useClicked(mc)) return;

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
                if (WeaponSelect.choose(mc, p, nearestTarget, inReach.size())) return;   // switched: no swing until the server has it (lastHeld)
            }
        }

        ItemStack held = p.getMainHandItem();
        // A spear stabs along the rotation the server already has (the last move packet's), not this tick's turn,
        // so a flick would stab whatever is in your view: no swing with one.
        if (held.has(DataComponents.PIERCING_WEAPON)) return;
        boolean sweepSword = held.is(h -> h.is(ItemTags.SWORDS)) && Enchant.level(held, Enchantments.SWEEPING_EDGE) > 0;
        float charge = p.getAttackStrengthScale(0.5f);

        // Sweep: a Sweeping Edge sword, fully charged, against a cluster, when the hit will sweep.
        if (sweepSword && sweeps(p) && charge >= FULL_CHARGE) {
            LivingEntity hub = bestSweepTarget(p, inReach);
            if (hub != null) {
                LivingEntity hit = attack(mc, p, hub);
                if (hit != null) {
                    for (LivingEntity o : inReach) if (swept(p, hit, o)) lastHit.put(o.getId(), tick);   // the sweep caught these too
                    return;
                }
                // the crosshair couldn't be put on it: on to the others below
            }
        }

        // Otherwise the mobs that take full damage again (not hit in the last 10 ticks), nearest first.
        List<LivingEntity> fresh = new ArrayList<>();
        for (LivingEntity t : inReach) if (!refractory(t)) fresh.add(t);
        if (fresh.isEmpty()) return;   // every target was hit too recently: a swing would do nothing
        fresh.sort(Comparator.comparingDouble(t -> eye.distanceToSqr(t.getBoundingBox().getCenter())));

        // Spam or wait: a spammed hit does the 20% floor, one per fresh mob, and a mob is fresh every 10
        // ticks, so spam deals about 0.02 * N * base per tick. Waiting deals one full hit per attack delay D
        // (D >= 12.5 for a sword or axe), base / D per tick. Spam wins past N = 50 / D fresh mobs: 5 for a
        // sword, 3 for a netherite axe. Enchantments add little to the floor hit, so they raise this a bit.
        int spamMin = spamThreshold(p.getCurrentItemAttackStrengthDelay());

        if (fresh.size() >= spamMin) {
            attackFirst(mc, p, fresh);                       // crowd: swing every tick
        } else if (charge >= FULL_CHARGE) {
            attackFirst(mc, p, fresh);                       // few: only on a full charge
        }
    }

    /** Attack the first of these the crosshair can be put on: a block in the way of one (tall grass, a cobweb)
     *  stops the game's pick, so the next is tried rather than none. Returns the entity hit, or null. */
    private static LivingEntity attackFirst(Minecraft mc, LocalPlayer p, List<LivingEntity> targets) {
        for (LivingEntity t : targets) {
            LivingEntity hit = attack(mc, p, t);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Whether the game's keybind step will use an item for you this tick (Minecraft.handleKeybinds): a right-click
     *  waiting, or right-click held with the delay between uses run out. */
    private static boolean useClicked(Minecraft mc) {
        KeyMapping use = mc.options.keyUse;
        return ((KeyMappingAccessor) use).kine$getClickCount() > 0
            || use.isDown() && ((MinecraftInvoker) mc).kine$getRightClickDelay() == 0;
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

    /** Turn toward the target by whole mouse counts and attack what that rotation's crosshair is on, through
     *  the game's own pick and startAttack, if it is a target in reach; else turn straight back, before the
     *  rotation is sent. Returns the entity hit, or null. */
    private static LivingEntity attack(Minecraft mc, LocalPlayer p, LivingEntity target) {
        Vec3 eye = p.getEyePosition();
        Vec3 aim = aimPoint(eye, target.getBoundingBox());
        Vec3 d = aim.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        MouseTurn.Turn turn = MouseTurn.toward(p, yaw, pitch);
        MinecraftInvoker game = (MinecraftInvoker) mc;
        game.invokePick(1.0F);
        double reach = p.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity hit
            && isTarget(hit, p) && eyeToBox(eye, hit.getBoundingBox()) <= reach) {
            // startAttack returns false after hitting an entity too, and the charge can't tell (it is already 0 the
            // tick after the held item changes), so see whether the game sent the attack
            int sent = attacksSent;
            game.invokeStartAttack();
            if (attacksSent != sent) {
                flick = turn;   // the attack went out with this turn: keep it for this tick's move packet
                lastHit.put(hit.getId(), tick);
                return hit;
            }
        }
        MouseTurn.undo(p, turn);   // nothing to hit there: no turn goes out
        game.invokePick(1.0F);
        return null;
    }

    /** Undo this tick's turn once the player has sent it (VanillaActions, after the entities tick). */
    public static void afterEntities(Minecraft mc) {
        if (flick == MouseTurn.Turn.NONE || mc.player == null) { flick = MouseTurn.Turn.NONE; return; }
        MouseTurn.undo(mc.player, flick);
        flick = MouseTurn.Turn.NONE;
        ((MinecraftInvoker) mc).invokePick(1.0F);
    }

    /** Where to aim: the point of the slightly shrunken box nearest the eye, so the crosshair ray enters the
     *  box about as near as the box comes, and so within reach whenever the box is. */
    private static Vec3 aimPoint(Vec3 eye, AABB bb) {
        AABB in = bb.deflate(Math.min(0.1, Math.min(bb.getXsize(), Math.min(bb.getYsize(), bb.getZsize())) * 0.25));
        return new Vec3(Mth.clamp(eye.x, in.minX, in.maxX), Mth.clamp(eye.y, in.minY, in.maxY), Mth.clamp(eye.z, in.minZ, in.maxZ));
    }

    /** The fresh target whose sweep catches the most others (at least one, else null). */
    private static LivingEntity bestSweepTarget(LocalPlayer p, List<LivingEntity> targets) {
        LivingEntity best = null;
        int bestN = 0;
        for (LivingEntity t : targets) {
            if (refractory(t)) continue;
            int n = 0;
            for (LivingEntity o : targets) if (swept(p, t, o)) n++;
            if (n > bestN) { bestN = n; best = t; }
        }
        return best;
    }

    /** Whether a full-charge sword hit now sweeps (Player.isSweepAttack): on the ground, not sprinting (a sprint
     *  hit knocks back instead), and moving less than 2.5 times your speed, which the server reads from your last
     *  move. */
    private static boolean sweeps(LocalPlayer p) {
        double dx = p.getX() - p.xo, dz = p.getZ() - p.zo;
        double max = p.getSpeed() * 2.5;
        return p.onGround() && !p.isSprinting() && dx * dx + dz * dz < max * max;
    }

    /** Whether a sweep around a hit on `hit` reaches `o` (Player.doSweepAttack): o's box meets the hit mob's box
     *  grown by the sweep inflation, and o is within 3 blocks of the player. */
    private static boolean swept(LocalPlayer p, LivingEntity hit, LivingEntity o) {
        return o != hit && hit.getBoundingBox().inflate(SWEEP_X, SWEEP_Y, SWEEP_Z).intersects(o.getBoundingBox())
            && p.distanceToSqr(o) < 9.0;
    }

    /** Distance from the eye to the nearest point of a bounding box (how vanilla measures melee reach). */
    private static double eyeToBox(Vec3 eye, AABB bb) {
        double dx = Math.max(Math.max(bb.minX - eye.x, 0.0), eye.x - bb.maxX);
        double dy = Math.max(Math.max(bb.minY - eye.y, 0.0), eye.y - bb.maxY);
        double dz = Math.max(Math.max(bb.minZ - eye.z, 0.0), eye.z - bb.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // Against outline shapes, as the game's crosshair pick (Entity.pick) is: tall grass, cobwebs and sugar cane
    // have no collision but stop the pick.
    private static boolean lineOfSight(LocalPlayer p, Vec3 eye, LivingEntity t) {
        Vec3 aim = t.getBoundingBox().getCenter();
        BlockHitResult hit = p.level().clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.MISS
            || hit.getLocation().distanceToSqr(eye) >= aim.distanceToSqr(eye) - 1.0e-3;
    }

}
