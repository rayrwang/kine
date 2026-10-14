package ai.rrw.kine.combat;

import ai.rrw.kine.Settings;
import ai.rrw.kine.fight.CombatMovement;
import ai.rrw.kine.mixin.KeyMappingAccessor;
import ai.rrw.kine.mixin.MinecraftInvoker;
import ai.rrw.kine.mixin.PlayerAccessor;
import ai.rrw.kine.util.GameModes;
import ai.rrw.kine.util.MouseTurn;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
import java.util.Optional;

/**
 * Melee kill aura against hostile mobs and neutral mobs that have hit you, toggled in the settings screen. When to
 * swing and at which mob is SwingPlan's (bursts of quick hits and a charged hit, sweeps when they pay more, and
 * threats first); this class finds the mobs and makes the swings the vanilla way.
 *
 * <p>The rotation the server gets is the aura's own, kept apart from your view: the aura turns the player by whole
 * mouse counts at the start of each tick to the rotation it holds, so the game's own pick, its startAttack and the
 * move packet all have it, and turns back once the move packet has gone out, so your view never moves. That rotation
 * turns at most MAX_TURN degrees a tick (RW's rule: no faster than a fast human flick), toward the mob it means to hit
 * next, and back to your view when there is nothing to hit. Only on foot: gliding, swimming, riding or climbing, it
 * turns out for the one tick of a swing and back the next, both within MAX_TURN of your view, so the flight or the
 * vehicle follows the turn for one tick at most.
 *
 * <p>While the rotation sent is turned away from your view your walking would follow it, so the movement keys are
 * swapped for the ones that walk where you meant to go (to the nearest of the eight directions). And the aura stands
 * down while you use an item, click or hold attack or use: its rotation comes back to your view first, and the
 * clicks you made meanwhile wait for it (at most a few ticks), so they go where you look.
 *
 * <p>Targets are mobs only (never players, armour stands or animals): hostiles ({@link Enemy}) always, neutral mobs
 * only for a while after they damage you, read from {@code getLastDamageSource} because mob anger isn't synced to the
 * client. Neutral: the NeutralMob ones (endermen and zombified piglins included, though the game counts them as
 * hostile), piglins while you wear gold, the warden (it fights only what it hears or smells; a hit would anger it),
 * and spiders where it is bright (they pick no target at light value 0.5 and up, Spider.SpiderTargetGoal). A creaking
 * bound to its heart takes no damage (Creaking.hurtServer), so it is left alone.
 */
public final class KillAura {
    private KillAura() {}

    private static final int    ANGRY_TICKS   = 600;   // a neutral mob that hits you stays a target this long (30 s)
    private static final int    WEAPON_PERIOD = 10;    // re-pick the best weapon at most this often while fighting
    private static final double SWEEP_X = 1.0, SWEEP_Y = 0.25, SWEEP_Z = 1.0;   // vanilla sweep-box inflation
    /** The most the rotation sent may turn in one tick, as mouse travel sqrt(yaw^2 + pitch^2): a fast human flick (RW,
     *  June: "just don't make it so fast that it looksk like cheating"; the fast-pitch study's estimate). */
    public static final double MAX_TURN = 30.0;
    // what the aura plans with, a count's width under MAX_TURN, so rounding to whole counts stays inside it
    private static final double TURN_BUDGET = MAX_TURN - 0.5;
    private static final int    CLICK_HOLD_TICKS = 20;   // your clicks held back longer than this (1 s) are dropped
    private static final double MOB_REACH = Math.sqrt(2.04F) - 0.6F;   // a mob's melee reach past its box (Mob.DEFAULT_ATTACK_REACH)
    private static final double CREEPER_SWELL = 3.0;   // a creeper starts its fuse within 3 blocks (Creeper's SwellGoal)

    /** A hit of ours on a mob: the tick the window it opened began, and the biggest hit in that window. */
    private record Hit(int tick, double damage) {}

    private static final Map<Integer, Hit> hits = new HashMap<>();
    private static boolean aimedAtMob = false;   // this tick's fight turns toward a mob in reach (the bow aura's rotation waits)
    private static final Map<Integer, Integer> angryUntil = new HashMap<>();   // neutral entityId -> tick its anger lapses
    private static int tick = 0;
    private static int lastHurtTime = 0;        // player's hurtTime last tick, to spot a fresh incoming hit
    private static int lastWeaponTick = -100;   // last tick auto weapon selection ran
    private static Item lastHeld = null;        // the main-hand item when last checked, to see a switch
    private static int attacksSent = 0;         // attacks the game has sent (MultiPlayerGameModeMixin)
    private static LocalPlayer lastPlayer = null;

    // The rotation sent, as an offset from your view in mouse counts, held from tick to tick; and this tick's turn,
    // undone after the move packet. sentYaw/sentPitch: the rotation the last move packet carried.
    private static int heldX = 0, heldY = 0;
    private static float appliedYaw = 0f, appliedPitch = 0f;
    private static float sentYaw, sentPitch;
    private static boolean sentKnown = false;
    private static int turnDir = 0;   // the way the rotation sent last turned in yaw: 1, -1, or 0 for not yet

    // your clicks (attack, use, pick block) held back while the rotation sent is away from your view
    private static final int[] heldClicks = new int[3];
    private static int heldClicksSince = 0;

    /** The game sent an attack or a spear's stab (MultiPlayerGameModeMixin). */
    public static void onAttackSent() {
        attacksSent++;
    }

    /** The server set the player's rotation (ClientPacketListenerMixin: a teleport, or a rotation alone), and the
     *  client answered with it, so that is now the rotation last sent: turn on from it within MAX_TURN, and drop the
     *  offset held from the old view, which would otherwise be put back in one tick. Packets are handled between
     *  ticks, when no turn of this tick is applied. */
    public static void onRotationSet(LocalPlayer p) {
        heldX = heldY = 0;
        sentYaw = p.getYRot(); sentPitch = p.getXRot(); sentKnown = true;
    }

    /** Whether the rotation sent is turned away from your view this tick. */
    public static boolean turned() {
        return heldX != 0 || heldY != 0;
    }

    // ---------------------------------------------------------------- the tick

    /** The start of every tick, whatever screen is open (MinecraftTickMixin, after the autopilot's and the clutch's
     *  turns): turn to the rotation held, so this tick's pick and move packet have it. With a screen open a vanilla
     *  client can't turn, and neither the view nor the rotation sent changes. */
    public static void onTickStart(Minecraft mc) {
        tick++;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || p != lastPlayer) {
            hits.clear(); angryUntil.clear();
            heldX = heldY = 0; appliedYaw = appliedPitch = 0f; sentKnown = false;
            lastPlayer = p;
            return;
        }
        trackAnger(p);   // learn which neutral mobs are angry at us, even when the aura is off
        if (appliedYaw != 0f || appliedPitch != 0f) {   // a tick that never reached the entities (paused): undo it first
            MouseTurn.undo(p, new MouseTurn.Turn(0, 0, appliedYaw, appliedPitch));
            appliedYaw = appliedPitch = 0f;
        }
        if (turned() && !mc.isPaused()) apply(MouseTurn.by(p, heldX, heldY));
    }

    /** Just before the game's keybind step (VanillaActions); no screen is open here. Fights, then turns the rotation
     *  sent toward where it should be, within MAX_TURN of the last one sent. */
    public static void beforeKeybinds(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) return;
        if (!sentKnown) { sentYaw = p.getYRot(); sentPitch = p.getXRot(); sentKnown = true; }
        boolean you = yourInput(mc, p);
        boolean on = Settings.killAura && p.isAlive() && GameModes.survivalLike(mc) && !you;   // not in creative or spectator
        boolean onFoot = onFoot(p);

        float viewYaw = p.getYRot() - appliedYaw, viewPitch = p.getXRot() - appliedPitch;
        // where the rotation sent rests when not fighting: your view, or while the pathfinder walks you its facing
        float[] rest = ai.rrw.kine.walk.Walk.restRotation(p, viewYaw, viewPitch);
        float[] want = rest;   // where the rotation sent should go: the rest unless fighting
        aimedAtMob = false;
        if (on) {
            float[] aim = fight(mc, p, onFoot, rest[0], rest[1]);
            if (aim == null) { holdClicks(mc); return; }   // it swung: the rotation is set for this tick
            if (onFoot) want = aim;
        }
        // the bow aura, when the melee aura has no mob to turn to (it stands down by itself when the melee aura is
        // active, BowAura.guard): the full-power shot it is drawing for
        if (!aimedAtMob && onFoot) {
            float[] bow = BowAura.plan(mc, p, viewYaw);
            if (bow != null) want = bow;
        }
        if (!turned() && want[0] == viewYaw && want[1] == viewPitch) { holdClicks(mc); return; }   // idle, on your view: hands off
        float[] next = MouseTurn.limit(sentYaw, sentPitch, want[0], want[1], TURN_BUDGET);
        int[] c = MouseTurn.countsTo(p, next[0], next[1]);
        if (c[0] != 0 || c[1] != 0) {
            apply(MouseTurn.by(p, c[0], c[1]));
            ((MinecraftInvoker) mc).invokePick(1.0F);
        }
        holdClicks(mc);
    }

    /** Once the player has sent this tick's rotation (VanillaActions, after the entities tick): note it and turn back
     *  to your view. */
    public static void afterEntities(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        double turned = sentKnown ? Mth.wrapDegrees(p.getYRot() - sentYaw) : 0.0;
        if (Math.abs(turned) > 0.5) turnDir = turned > 0 ? 1 : -1;
        sentYaw = p.getYRot(); sentPitch = p.getXRot(); sentKnown = true;
        if (appliedYaw == 0f && appliedPitch == 0f) { heldX = heldY = 0; return; }
        double deg = MouseTurn.sens(mc, p) * 0.15;
        MouseTurn.undo(p, new MouseTurn.Turn(0, 0, appliedYaw, appliedPitch));
        // Held for the next tick within half a turn of the view, so whole turns round a crowd don't pile up. The whole
        // turns taken off the offset go onto the view's own yaw (the same direction, so nothing on screen moves), so
        // the yaw sent next tick goes on from this one's. Was: taken off the offset alone ("the server wraps the yaw,
        // so a turn of 360 is no turn to it"), so the yaw sent jumped by 360 each time the offset passed half a turn;
        // a vanilla client's yaw only moves by its mouse turns, and an anticheat reads the raw yaw: against Grim
        // 2.3.74 on a local Paper 26.1.2 server (2026-10-12) each such jump was an AimModulo360 flag.
        float whole = MouseTurn.wholeTurns(appliedYaw);
        if (whole != 0f) shiftYaw(p, whole);
        heldX = (int) Math.round((appliedYaw - whole) / deg);
        heldY = (int) Math.round(appliedPitch / deg);
        appliedYaw = appliedPitch = 0f;
        ((MinecraftInvoker) mc).invokePick(1.0F);
    }

    /** Every yaw the client keeps for the player moved by whole turns: the same direction, drawn the same. */
    private static void shiftYaw(LocalPlayer p, float d) {
        p.setYRot(p.getYRot() + d);
        p.yRotO += d;
        p.yHeadRot += d; p.yHeadRotO += d;
        p.yBodyRot += d; p.yBodyRotO += d;
        p.yBob += d; p.yBobO += d;
    }

    private static void apply(MouseTurn.Turn t) {
        appliedYaw += t.movedYaw();
        appliedPitch += t.movedPitch();
    }

    /** On foot: the rotation sent can stay turned from the view from tick to tick. Gliding, in water, riding,
     *  climbing, flying or falling, the turn would steer you, so it lasts only the tick of a swing. */
    private static boolean onFoot(LocalPlayer p) {
        return !p.isFallFlying() && !p.isInWater() && !p.isPassenger() && !p.onClimbable() && !p.getAbilities().flying
            && p.fallDistance < 2.0;
    }

    // ---------------------------------------------------------------- your input

    /** Whether you are acting yourself: using an item, or attack, use or pick block clicked or held. The bow aura's
     *  draw and its hold of the use key aren't yours: the melee aura goes first, and the bow lets go (BowAura.guard). */
    private static boolean yourInput(Minecraft mc, LocalPlayer p) {
        if (p.isUsingItem() && !BowAura.ownsUse(p)) return true;
        KeyMapping[] keys = {mc.options.keyAttack, mc.options.keyUse, mc.options.keyPickItem};
        for (KeyMapping k : keys) {
            boolean down = k.isDown() && !(k == mc.options.keyUse && BowAura.holdingUse());
            if (((KeyMappingAccessor) k).kine$getClickCount() > 0 || down) return true;
        }
        return heldClicks[0] + heldClicks[1] + heldClicks[2] > 0;
    }

    /** Whether clicks of yours are being held back for the rotation to come back to your view. */
    public static boolean clicksHeld() {
        return heldClicks[0] + heldClicks[1] + heldClicks[2] > 0;
    }

    /** With the rotation sent away from your view, your clicks wait (and a held use doesn't repeat) so they don't go
     *  where the aura aims; once it is back on your view they go in this tick's keybind step. */
    private static void holdClicks(Minecraft mc) {
        KeyMapping[] keys = {mc.options.keyAttack, mc.options.keyUse, mc.options.keyPickItem};
        boolean away = appliedYaw != 0f || appliedPitch != 0f;
        if (away) {
            for (int i = 0; i < 3; i++) {
                KeyMappingAccessor k = (KeyMappingAccessor) keys[i];
                if (k.kine$getClickCount() > 0) {
                    if (heldClicks[0] + heldClicks[1] + heldClicks[2] == 0) heldClicksSince = tick;
                    heldClicks[i] += k.kine$getClickCount();
                    k.kine$setClickCount(0);
                }
            }
            MinecraftInvoker game = (MinecraftInvoker) mc;
            if (mc.options.keyUse.isDown() && game.kine$getRightClickDelay() == 0 && !AutoEat.holdingUse()
                && !BowAura.holdingUse())
                game.kine$setRightClickDelay(1);   // auto eat's use isn't aimed, the bow aura's is: let them go at the rotation sent
            if (tick - heldClicksSince > CLICK_HOLD_TICKS) java.util.Arrays.fill(heldClicks, 0);
        } else if (heldClicks[0] + heldClicks[1] + heldClicks[2] > 0) {
            for (int i = 0; i < 3; i++) {
                KeyMappingAccessor k = (KeyMappingAccessor) keys[i];
                k.kine$setClickCount(k.kine$getClickCount() + heldClicks[i]);
            }
            java.util.Arrays.fill(heldClicks, 0);
        }
    }

    /** The movement keys for this tick: while the rotation sent is turned yaw degrees from your view your walking would
     *  follow it, so the keys are swapped for the ones that walk nearest where you meant (VanillaActions, first). */
    public static Input adjustInput(LocalPlayer p, Input keys) {
        if (Math.abs(appliedYaw) < 1e-4f) return keys;
        return walkKeys(keys, appliedYaw);
    }

    /** The keys that walk, at a rotation turned yaw degrees from the view, nearest the way these keys walk at the
     *  view (Entity.getInputVector turns the input by the yaw): one of the eight directions, or none. */
    static Input walkKeys(Input keys, double yaw) {
        double x = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
        double z = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
        if (x == 0 && z == 0) return keys;
        double n = Math.hypot(x, z);
        x /= n; z /= n;
        double r = Math.toRadians(yaw), cos = Math.cos(r), sin = Math.sin(r);
        double x1 = x * cos + z * sin, z1 = z * cos - x * sin;   // the same direction, in the turned rotation's frame
        double s = Math.sin(Math.toRadians(22.5));
        return new Input(z1 > s, z1 < -s, x1 > s, x1 < -s, keys.jump(), keys.shift(), keys.sprint());
    }

    // ---------------------------------------------------------------- the fight

    /** One mob in reach, what the plan knows of it, and the rotation that puts the crosshair on it (null if none). */
    private record Target(LivingEntity e, SwingPlan.Mob m, float[] rot) {}

    /**
     * Picks the swing for this tick and makes it if there is one. Returns null after a swing (this tick's rotation is
     * then set), else the rotation to turn toward: the mob the plan will hit next, or your view.
     */
    private static float[] fight(Minecraft mc, LocalPlayer p, boolean onFoot, float viewYaw, float viewPitch) {
        float[] view = {viewYaw, viewPitch};
        double reach = p.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        Vec3 eye = p.getEyePosition();
        AABB area = p.getBoundingBox().inflate(reach + 1.0);

        // combat movement walking you out of lit creepers (RW's sprint out): not targets, and the rotation goes toward
        // the way out instead of your view, so the walk out can be a sprint
        CombatMovement.Escape esc = CombatMovement.escape(p);
        if (esc != null) view = new float[]{esc.yaw(), viewPitch};
        List<LivingEntity> found = new ArrayList<>();
        for (Entity e : mc.level.getEntities(p, area, x -> isTarget(x, p)))
            if (eyeToBox(eye, e.getBoundingBox()) <= reach && (esc == null || !esc.flees(e))) found.add((LivingEntity) e);
        if (found.isEmpty()) return view;
        found.sort(Comparator.comparingDouble(t -> eye.distanceToSqr(t.getBoundingBox().getCenter())));   // the plan's list order

        ItemStack held = p.getMainHandItem();
        boolean canSweep = p.onGround() && !p.isSprinting() && slow(p);
        List<SwingPlan.Mob> mobs = mobsFor(p, found, held);

        // Auto weapon: the sword or axe that sustains the most damage against these mobs. Switching waits a tick.
        boolean noSwing = false;
        // not in the tick the bow aura lets go of its draw: a slot change before the release would stop the use on the
        // server first and lose the arrow (the switch comes next tick)
        if (Settings.autoWeapon && !p.isUsingItem()) {
            boolean holdingWeapon = held.is(ItemTags.SWORDS) || held.is(ItemTags.AXES);
            if (!holdingWeapon || tick - lastWeaponTick >= WEAPON_PERIOD) {
                lastWeaponTick = tick;
                if (WeaponSelect.choose(mc, p, found, mobs, canSweep)) noSwing = true;
            }
        }
        // A new item in the main hand: the game sends the slot change at the start of the tick after the switch, this
        // one, and the server takes the item's attack damage and speed only when it next ticks the player, so a swing
        // now would hit with the old item's. Swing from the next tick.
        Item heldItem = p.getMainHandItem().getItem();
        if (heldItem != lastHeld) { lastHeld = heldItem; noSwing = true; }
        // The game's startAttack refuses while missTime is up: after a click in the air, and after an inventory close
        // until the tick after next, as for a vanilla client.
        if (mc.missTime > 0) noSwing = true;
        // A spear stabs along the rotation the server already has (the last move packet's), not this tick's, so a
        // swing could stab whatever that points at: none with one.
        if (held.has(DataComponents.PIERCING_WEAPON)) noSwing = true;
        // The bow aura's draw, which it lets go of in this tick's keybind step now the melee aura is active: a vanilla
        // client doesn't attack while an item is in use (handleKeybinds), so the swing waits a tick.
        if (p.isUsingItem()) noSwing = true;

        // where each mob can be hit from, and whether this tick's turn can get there
        List<Target> ts = new ArrayList<>();
        Vec3 look = Vec3.directionFromRotation(p.getXRot(), p.getYRot());
        List<Entity> pickable = mc.level.getEntities(p, area, e -> !e.isSpectator() && e.isPickable());
        for (int i = 0; i < found.size(); i++) {
            LivingEntity t = found.get(i);
            SwingPlan.Mob m = mobs.get(i);
            float[] r = aimAt(p, eye, look, t, reach, pickable);
            m.inPlay = r != null;   // out of reach along every ray, or something in the way: it can still take sweeps
            if (r != null) {
                double travel = MouseTurn.travel(sentYaw, sentPitch, r[0], r[1]);
                Vec3 c = t.getBoundingBox().getCenter();
                double flat = Math.max(0.3, Math.hypot(c.x - eye.x, c.z - eye.z));
                m.yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-(c.x - eye.x), c.z - eye.z)) - sentYaw);
                m.halfWidth = Math.toDegrees(Math.atan2(t.getBbWidth() / 2, flat));
                m.turnTicks = travel <= TURN_BUDGET ? 0 : (int) Math.ceil((travel - TURN_BUDGET) / TURN_BUDGET);
                m.reachable = !noSwing && travel <= TURN_BUDGET
                    && (onFoot || MouseTurn.travel(viewYaw, viewPitch, r[0], r[1]) <= TURN_BUDGET);
            } else {
                m.reachable = false;
            }
            ts.add(new Target(t, m, r));
        }

        SwingPlan.Weapon w = weapon(p, held);
        int k = ((PlayerAccessor) p).kine$getAttackStrengthTicker();
        boolean critable = !p.onClimbable() && !p.isInWater() && !p.hasEffect(MobEffects.BLINDNESS) && !p.isPassenger()
            && !p.isSprinting() && !p.isFallFlying();
        boolean canCrit = critable && p.fallDistance > 0 && !p.onGround();
        int critIn = critable && !canCrit && !p.onGround() ? critIn(p.getDeltaMovement().y) : -1;
        for (int attempt = 0; attempt < 3; attempt++) {
            SwingPlan.Swing s = SwingPlan.decide(w, k, canSweep && w.sword(), canCrit, critIn, mobs, TURN_BUDGET);
            if (s.hit() < 0) {
                float[] r = s.aim() >= 0 && onFoot ? ts.get(s.aim()).rot() : null;
                aimedAtMob = r != null;
                return r != null ? r : view;
            }
            Target t = ts.get(s.hit());
            float[] r = onFoot ? lead(p, eye, t.e(), reach, pickable, t.rot()) : t.rot();
            if (attack(mc, p, t.e(), r[0], r[1], w, k, canSweep && w.sword(), canCrit, found, mobs)) return null;
            t.m().reachable = false;   // the crosshair couldn't be put on it: plan again without it
        }
        return view;
    }

    /** In a jump, rising: the ticks until a swing would crit, -1 if more than 4. Player.canCriticalAttack reads the state
     *  the last move packet gave (falling, not on the ground); this tick's move goes by vy, then each tick
     *  vy = (vy - 0.08) * 0.98 (LivingEntity.travel). A swing crits the tick after the first move down. */
    static int critIn(double vy) {
        for (int j = 1; j <= 4; j++) {
            if (vy < 0) return j;
            vy = (vy - 0.08) * 0.98;
        }
        return -1;
    }

    /** The plan's view of each mob: health, armour, the weapon's bonus against it, its window, its sweep neighbours,
     *  and how soon its attack would reach you. */
    private static List<SwingPlan.Mob> mobsFor(LocalPlayer p, List<LivingEntity> found, ItemStack held) {
        List<SwingPlan.Mob> mobs = new ArrayList<>();
        for (LivingEntity t : found) {
            SwingPlan.Mob m = new SwingPlan.Mob(t.getHealth(), t.getArmorValue());
            m.toughness = t.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
            m.bonus = WeaponSelect.bonus(held, t);
            Hit h = hits.get(t.getId());
            int own = h != null && tick - h.tick() < SwingPlan.WINDOW ? SwingPlan.WINDOW - (tick - h.tick()) : 0;
            // the client sets invulnerableTime 20 when the server reports a full hit from anything, a tick after the
            // server did; a full hit again once the server's is at 10
            int seen = Math.max(0, t.invulnerableTime - (SwingPlan.WINDOW + 1));
            m.freshIn = Math.max(own, seen);
            m.lastHurt = own > 0 && own >= seen ? h.damage() : Double.MAX_VALUE;   // another's hit: size unknown
            m.contact = contact(p, t);
            mobs.add(m);
        }
        for (int i = 0; i < found.size(); i++) {
            List<Integer> sw = new ArrayList<>();
            for (int j = 0; j < found.size(); j++) if (swept(p, found.get(i), found.get(j))) sw.add(j);
            mobs.get(i).swept = sw.stream().mapToInt(Integer::intValue).toArray();
        }
        return mobs;
    }

    /** The held weapon as the plan sees it: the attack damage the server will use (the item's, the player's 1, and
     *  Strength or Weakness), the charge delay, a sword or not, and the sweep ratio. */
    private static SwingPlan.Weapon weapon(LocalPlayer p, ItemStack held) {
        SwingPlan.Weapon w = WeaponSelect.weaponOf(p, held);
        return new SwingPlan.Weapon(w.damage(), p.getCurrentItemAttackStrengthDelay(), w.sword(), w.sweepRatio());
    }

    /** Whether the player moves less than 2.5 times its speed a tick, as Player.isSweepAttack asks of the movement the
     *  server knows (the last move's). */
    private static boolean slow(LocalPlayer p) {
        double dx = p.getX() - p.xo, dz = p.getZ() - p.zo;
        double max = p.getSpeed() * 2.5;
        return dx * dx + dz * dz < max * max;
    }

    /** Turn to (yaw, pitch) and attack what that rotation's crosshair is on, through the game's own pick and
     *  startAttack, if it is a target in reach; else turn back. Records the hit and its sweep. */
    private static boolean attack(Minecraft mc, LocalPlayer p, LivingEntity planned, float yaw, float pitch, SwingPlan.Weapon w,
                                  int k, boolean canSweep, boolean canCrit, List<LivingEntity> found, List<SwingPlan.Mob> mobs) {
        int[] c = MouseTurn.countsTo(p, yaw, pitch);
        MouseTurn.Turn turn = MouseTurn.by(p, c[0], c[1]);
        MinecraftInvoker game = (MinecraftInvoker) mc;
        game.invokePick(1.0F);
        double reach = p.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        if (MouseTurn.travel(sentYaw, sentPitch, p.getYRot(), p.getXRot()) <= MAX_TURN
            && mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity hit
            && isTarget(hit, p) && eyeToBox(p.getEyePosition(), hit.getBoundingBox()) <= reach
            && (hit == planned || fresh(hit))) {   // another mob in the way: only if a hit on it isn't wasted
            // startAttack returns false after hitting an entity too, and the charge can't tell (it is already 0 the
            // tick after the held item changes), so see whether the game sent the attack
            int before = attacksSent;
            game.invokeStartAttack();
            if (attacksSent != before) {
                apply(turn);
                record(p, hit, w, k, canSweep, canCrit, found, mobs);
                return true;
            }
        }
        MouseTurn.undo(p, turn);   // nothing to hit there
        game.invokePick(1.0F);
        return false;
    }

    /** Whether a mob takes a full hit now: not hit by us in the last 10 ticks, and not hurt by anything else lately
     *  (the client marks a full hit from any source with invulnerableTime 20). */
    private static boolean fresh(LivingEntity e) {
        Hit h = hits.get(e.getId());
        return (h == null || tick - h.tick() >= SwingPlan.WINDOW) && e.invulnerableTime <= SwingPlan.WINDOW + 1;
    }

    /** Note a hit and what its sweep caught: when their windows began and the biggest hit in each. */
    private static void record(LocalPlayer p, LivingEntity hit, SwingPlan.Weapon w, int k, boolean canSweep,
                               boolean canCrit, List<LivingEntity> found, List<SwingPlan.Mob> mobs) {
        float c = SwingPlan.charge(k, w.delay());
        boolean crit = canCrit && c > 0.9F;
        int i = found.indexOf(hit);
        double bonus = i >= 0 ? mobs.get(i).bonus : WeaponSelect.bonus(p.getMainHandItem(), hit);
        note(hit, SwingPlan.hitDamage(w, bonus, c, crit));
        if (w.sword() && canSweep && c > 0.9F && !crit) {
            for (int j = 0; j < found.size(); j++) {
                LivingEntity o = found.get(j);
                if (swept(p, hit, o)) note(o, SwingPlan.sweepDamage(w, mobs.get(j).bonus, c));
            }
        }
    }

    private static void note(LivingEntity e, double damage) {
        Hit h = hits.get(e.getId());
        if (h != null && tick - h.tick() < SwingPlan.WINDOW) {
            if (damage > h.damage()) hits.put(e.getId(), new Hit(h.tick(), damage));   // the excess over the last
        } else {
            hits.put(e.getId(), new Hit(tick, damage));
        }
    }

    /** Ticks until the mob's attack reaches you, from its distance and its speed toward you: for a creeper the 3
     *  blocks its fuse starts at (and 0 once lit), for the rest its melee reach past its box. */
    static double contact(LocalPlayer p, LivingEntity t) {
        double gap;
        if (t instanceof Creeper c) {
            if (c.getSwellDir() > 0 || c.isIgnited()) return 0.0;
            gap = Math.sqrt(p.distanceToSqr(t)) - CREEPER_SWELL;
        } else {
            AABB pb = p.getBoundingBox(), tb = t.getBoundingBox();
            if (tb.minY >= pb.maxY || tb.maxY <= pb.minY) return Double.POSITIVE_INFINITY;   // its attack box doesn't reach up or down
            double half = (pb.getXsize() + tb.getXsize()) / 2 + MOB_REACH;
            gap = Math.max(Math.abs(t.getX() - p.getX()), Math.abs(t.getZ() - p.getZ())) - half;
        }
        if (gap <= 0) return 0.0;
        double dx = t.getX() - p.getX(), dz = t.getZ() - p.getZ(), d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-6) return 0.0;
        double vx = (t.getX() - t.xo) - (p.getX() - p.xo), vz = (t.getZ() - t.zo) - (p.getZ() - p.zo);
        double closing = -(vx * dx + vz * dz) / d;
        return closing > 1e-3 ? gap / closing : Double.POSITIVE_INFINITY;
    }

    /**
     * A rotation that puts the crosshair on the mob within reach with nothing in the way (no block, and no other entity
     * the game's pick would take first: ProjectileUtil.getEntityHitResult takes the nearest box along the ray, grown by
     * its pick radius), turning as little as can be from the rotation now: aiming where the look ray would meet the
     * mob's (slightly shrunken) box at its depth, else at the box point nearest the eye, its centre, the top of its
     * head, or either side. Null if none of them reach it: in a packed crowd, the mobs behind the first row.
     */
    private static float[] aimAt(LocalPlayer p, Vec3 eye, Vec3 look, LivingEntity t, double reach, List<Entity> others) {
        AABB bb = t.getBoundingBox();
        AABB in = bb.deflate(Math.min(0.1, Math.min(bb.getXsize(), Math.min(bb.getYsize(), bb.getZsize())) * 0.25));
        Vec3 c = bb.getCenter();
        double depth = eye.distanceTo(c);
        Vec3 onRay = eye.add(look.scale(depth));
        Vec3 side = new Vec3(c.z - eye.z, 0, eye.x - c.x);   // across the line of sight
        side = side.lengthSqr() < 1e-9 ? new Vec3(1, 0, 0) : side.normalize().scale(in.getXsize() / 2);
        Vec3[] points = {clampInto(onRay, in), clampInto(eye, in), c, new Vec3(c.x, in.maxY, c.z),
                         clampInto(c.add(side), in), clampInto(c.subtract(side), in)};
        for (Vec3 q : points) {
            Vec3 d = q.subtract(eye);
            if (d.lengthSqr() < 1e-9) continue;
            Vec3 dir = d.normalize();
            if (!clear(p, eye, dir, t, reach, others)) continue;
            float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
            float pitch = (float) Math.toDegrees(Math.atan2(-dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z)));
            return new float[]{p.getYRot() + Mth.wrapDegrees(yaw - p.getYRot()), pitch};
        }
        return null;
    }

    /** Whether the game's pick along this direction from the eye takes this mob: its box within reach, no block and no
     *  other entity (its box grown by its pick radius) nearer, and the eye not inside another's box. */
    private static boolean clear(LocalPlayer p, Vec3 eye, Vec3 dir, LivingEntity t, double reach, List<Entity> others) {
        Vec3 end = eye.add(dir.scale(reach));
        Optional<Vec3> entry = t.getBoundingBox().clip(eye, end);
        if (entry.isEmpty()) return false;   // beyond reach along this ray
        double at = entry.get().distanceToSqr(eye);
        BlockHitResult block = p.level().clip(new ClipContext(eye, entry.get(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (block.getType() != HitResult.Type.MISS && block.getLocation().distanceToSqr(eye) < at - 1.0e-6) return false;
        for (Entity o : others) {
            if (o == t) continue;
            AABB ob = o.getBoundingBox().inflate(o.getPickRadius());
            if (ob.contains(eye)) return false;
            Optional<Vec3> oe = ob.clip(eye, end);
            if (oe.isPresent() && oe.get().distanceToSqr(eye) < at - 1.0e-6) return false;
        }
        return true;
    }

    /**
     * Where on the mob to aim a swing, turning in the way the rotation has been turning: the point of its box, at the
     * aim's pitch, furthest that way within this tick's turn. Going round a crowd, each swing then ends as far on as
     * it can, and the next mob is a shorter turn away (with the nearest point of each, mobs 30 degrees apart took two
     * ticks each other time). The aim as given if that point isn't clear.
     */
    private static float[] lead(LocalPlayer p, Vec3 eye, LivingEntity t, double reach, List<Entity> others, float[] r) {
        if (turnDir == 0) return r;
        AABB bb = t.getBoundingBox();
        AABB in = bb.deflate(Math.min(0.1, Math.min(bb.getXsize(), Math.min(bb.getYsize(), bb.getZsize())) * 0.25));
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;   // the box's yaw from the eye, from r
        for (double x : new double[]{in.minX, in.maxX})
            for (double z : new double[]{in.minZ, in.maxZ}) {
                double y = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-(x - eye.x), z - eye.z)) - r[0]);
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
        double dp = r[1] - sentPitch;
        double maxDy = Math.sqrt(Math.max(0.0, TURN_BUDGET * TURN_BUDGET - dp * dp));
        double fromSent = Mth.wrapDegrees(r[0] - sentYaw);
        double a = Math.max(lo, -maxDy - fromSent), b = Math.min(hi, maxDy - fromSent);   // reachable and on the box
        if (a > b) return r;
        float yaw = (float) (r[0] + (turnDir > 0 ? b : a));
        if (clear(p, eye, Vec3.directionFromRotation(r[1], yaw), t, reach, others)) return new float[]{yaw, r[1]};
        return r;
    }

    private static Vec3 clampInto(Vec3 v, AABB b) {
        return new Vec3(Mth.clamp(v.x, b.minX, b.maxX), Mth.clamp(v.y, b.minY, b.maxY), Mth.clamp(v.z, b.minZ, b.maxZ));
    }

    // ---------------------------------------------------------------- targets

    /** Mobs only: hostiles always, neutral mobs only for a while after they hit you. */
    private static boolean isTarget(Entity e, LivingEntity player) {
        if (!(e instanceof Mob mob) || !mob.isAlive() || mob.isSpectator()) return false;
        if (mob instanceof Creaking c && c.isHeartBound()) return false;   // only flinches (Creaking.hurtServer)
        Integer until = angryUntil.get(mob.getId());
        if (until != null && until > tick) return true;
        if (neutral(mob)) return piglinHostile(mob, player);
        return mob instanceof Enemy;
    }

    /** Mobs that leave the player alone until provoked, though some count as hostile (Enemy) in the game: the
     *  NeutralMob ones (endermen, zombified piglins, wolves, bees, iron golems, polar bears), piglins (they ignore
     *  players wearing gold, see piglinHostile), the warden (it goes for what it hears or smells; a hit would anger
     *  it), and spiders and cave spiders where it is bright: at a light value of 0.5 and up they pick no target
     *  (Spider.SpiderTargetGoal; one already chasing you may keep on, and is a target once it hits you). Piglin
     *  brutes attack anyone and stay hostile. */
    static boolean neutral(Mob mob) {
        return mob instanceof NeutralMob || mob instanceof Piglin || mob instanceof Warden
            || mob instanceof Spider s && s.getLightLevelDependentMagicValue() >= 0.5F;
    }

    /** An adult piglin attacks a player who wears no gold armour (the game's own test), so it is a target then,
     *  before it hits. Baby piglins never attack. */
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
            hits.values().removeIf(h -> h.tick() + 2 * SwingPlan.WINDOW <= tick);
        }
    }

    /** Whether a sweep around a hit on `hit` reaches `o` (Player.doSweepAttack): o's box meets the hit mob's box
     *  grown by the sweep inflation, and o is within 3 blocks of the player. */
    private static boolean swept(LocalPlayer p, LivingEntity hit, LivingEntity o) {
        return o != hit && hit.getBoundingBox().inflate(SWEEP_X, SWEEP_Y, SWEEP_Z).intersects(o.getBoundingBox())
            && p.distanceToSqr(o) < 9.0;
    }

    /** Distance from the eye to the nearest point of a bounding box (how vanilla measures melee reach). */
    static double eyeToBox(Vec3 eye, AABB bb) {
        double dx = Math.max(Math.max(bb.minX - eye.x, 0.0), eye.x - bb.maxX);
        double dy = Math.max(Math.max(bb.minY - eye.y, 0.0), eye.y - bb.maxY);
        double dz = Math.max(Math.max(bb.minZ - eye.z, 0.0), eye.z - bb.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---------------------------------------------------------------- the melee aura's priority over the bow aura

    /**
     * Ticks until the melee aura has a target to swing at (BowAura.guard: the bow aura stands down when that comes before
     * its shot is off). 0: a target is in reach with the crosshair able to reach it, as fight() finds them. 1: one is
     * within reach and BowAura.REACH_MARGIN, in sight. Else the soonest a target in sight, closing on you at its present
     * speed (its motion toward you less yours, per tick, as contact() reads it), comes within that; infinite if none.
     * Whether the melee aura is on is the caller's to check.
     */
    public static double meleeTicks(Minecraft mc, LocalPlayer p) {
        if (mc.level == null) return Double.POSITIVE_INFINITY;
        double reach = p.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        Vec3 eye = p.getEyePosition();
        double best = Double.POSITIVE_INFINITY;
        List<Entity> pickable = null;
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(reach + 16.0), x -> isTarget(x, p))) {
            LivingEntity t = (LivingEntity) e;
            double gap = eyeToBox(eye, t.getBoundingBox()) - reach;
            if (gap <= 0) {
                if (pickable == null)
                    pickable = mc.level.getEntities(p, p.getBoundingBox().inflate(reach + 1.0), x -> !x.isSpectator() && x.isPickable());
                if (aimAt(p, eye, p.getLookAngle(), t, reach, pickable) != null) return 0.0;
                continue;   // in reach but out of the crosshair's way (a block between): not the melee aura's yet
            }
            Vec3 c = t.getBoundingBox().getCenter();
            if (p.level().clip(new ClipContext(eye, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType()
                != HitResult.Type.MISS) continue;
            if (gap <= BowAura.REACH_MARGIN) { best = Math.min(best, 1.0); continue; }
            double dx = t.getX() - p.getX(), dz = t.getZ() - p.getZ(), d = Math.sqrt(dx * dx + dz * dz);
            if (d < 1e-6) { best = Math.min(best, 1.0); continue; }
            double vx = (t.getX() - t.xo) - (p.getX() - p.xo), vz = (t.getZ() - t.zo) - (p.getZ() - p.zo);
            double closing = -(vx * dx + vz * dz) / d;
            if (closing > 1e-3) best = Math.min(best, Math.max(1.0, gap / closing));
        }
        return best;
    }

    // ---------------------------------------------------------------- read by the combat movement's simulation (fight/)

    /** The yaw the server got last, or the view's before the first. */
    public static float lastSentYaw(LocalPlayer p) {
        return sentKnown ? sentYaw : p.getYRot();
    }

    /** The pitch the server got last, or the view's before the first (the walk's layer 2 turns on from it). */
    public static float lastSentPitch(LocalPlayer p) {
        return sentKnown ? sentPitch : p.getXRot();
    }

    /** Whether the aura counts this entity as a target (hostile, or neutral and angry at you). */
    public static boolean targets(Entity e, LivingEntity player) {
        return isTarget(e, player);
    }

    /** Ticks until a mob takes a full hit from us again and the biggest hit of ours in its window, {0, 0} if none. */
    public static double[] window(LivingEntity e) {
        Hit h = hits.get(e.getId());
        if (h == null || tick - h.tick() >= SwingPlan.WINDOW) return new double[]{0, 0};
        return new double[]{SwingPlan.WINDOW - (tick - h.tick()), h.damage()};
    }
}
