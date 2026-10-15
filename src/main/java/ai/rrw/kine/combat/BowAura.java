package ai.rrw.kine.combat;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.autoflight.Takeoff;
import ai.rrw.kine.combat.Ballistics.Spec;
import ai.rrw.kine.combat.RangedWeapons.Arms;
import ai.rrw.kine.combat.RangedWeapons.Kind;
import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.fight.CombatMovement;
import ai.rrw.kine.mixin.KeyMappingAccessor;
import ai.rrw.kine.mixin.MinecraftInvoker;
import ai.rrw.kine.util.Enchant;
import ai.rrw.kine.util.GameModes;
import ai.rrw.kine.util.Hotbar;
import ai.rrw.kine.util.InventoryClicks;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.monster.illager.Illusioner;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bow aura (RW 2026-10-14): shoots hostile mobs with a bow, a crossbow or a trident with Loyalty, its own setting beside
 * the melee kill aura. Three modes (Settings.bowAuraMode, RW: "off, only when bow slot selected, and on"): off; held
 * only, which shoots only with the ranged weapon already in your selected slot and never changes your slot (but for a
 * cancel's scroll there and back); on, which picks the strongest weapon it carries (RangedWeapons.rate) and selects it,
 * moving it up from the inventory if needed, and gives your slot back when it is done. Its range is a setting
 * (Settings.bowAuraRange, RW: "make the 48 blocks a slider").
 *
 * <p>Melee first (RW: "when melee kill aura is active bow stands down, the inverse is not true"). The melee aura is
 * active when it is on and one of its targets is in reach with a clear line from your eye, or will be before the shot
 * under way is off (KillAura.meleeTicks: a mob within reach and half a block, or one that can walk into reach in the
 * ticks the shot still needs and two more, at its closing speed or a walking pace, whichever is faster). Then no shot: a
 * bow's or trident's draw under way is loosed at once if the shot as aimed would still hit and the mob isn't in reach
 * yet, else cancelled by a scroll of the hotbar and back; a crossbow being loaded is let go (a load half done is lost,
 * one done stays); your slot comes back so the kill aura (and auto weapon) can swing, and the rotation sent turns
 * toward the mob coming, ready for the first swing.
 *
 * <p>It also stands down (RW's rulings, 2026-10-14) while the pathfinder walks you, while combat movement is in a fight
 * it moves you in, and in elytra flight (gliding, a rocket takeoff, the autopilot), and whenever the melee aura would:
 * off your feet (water, ladders, riding, a fall), with a screen open, while you click attack or use or hold them or use
 * an item yourself, for a second after your last click, and while auto eat eats. It takes up again as soon as none of
 * that holds.
 *
 * <p>How it shoots, the way a player does. The rotation the server is sent is the kill aura's own, turned at most 30
 * degrees a tick in whole mouse counts and undone before each frame, so your view stays put. Every use goes through the
 * game's own key handling (the use key held and let go before handleKeybinds), only when the crosshair at the rotation
 * sent rests on nothing or a hostile, so no block, door, chest, villager or pet is used instead:
 * <ul>
 *   <li>bow: a full draw (20 ticks, full power and a crit), then the release, which carries no rotation (the server
 *       shoots along the last move packet's), so the rotation is turned toward the shot a release next tick fires and
 *       the release goes once that rotation, sent last tick, hits at least MIN_CHANCE of the time;</li>
 *   <li>trident: the same with 10 ticks of draw and its 2.5 a tick throw; one in the air at a time (it comes back by
 *       Loyalty); never one without Loyalty, never Riptide, and not a Channeling one in a thunderstorm (its lightning
 *       turns a creeper charged and hits what stands near);</li>
 *   <li>crossbow: loading by holding the use key for its charge (25 ticks, 5 less a level of Quick Charge) until the
 *       game says it is charged, the release (no shot), then a press that fires at once along this tick's rotation
 *       (the use packet carries it); a loaded crossbow stays loaded. Arrows only: never one loaded with a firework
 *       rocket, nor one whose next load would take one (rockets in your offhand go first), since a rocket's blast
 *       hurts you and whatever stands near. Multishot's side arrows and Piercing's way past the mob are kept clear of
 *       bystanders too.</li>
 * </ul>
 * A new use waits for the server's word that the last one has ended, or that word, arriving after the new use began,
 * would stop it on the client. Nothing that isn't a target (a pet, a villager, a player, an animal) may stand near the
 * shot's path before the mob (spread-grown boxes).
 *
 * <p>Which mob: BowTargets' policy over the mobs it could hit (hostiles, and neutral mobs that hit you, as the kill aura
 * picks them; none a projectile can't hurt, NoDamage; never your pets), within range, in sight and in reach of the shot,
 * with a hit chance of at least BowTargets.VIABLE.
 */
public final class BowAura {
    private BowAura() {}

    /** A shot goes only when it hits at least this often. */
    public static final double MIN_CHANCE = 0.5;
    /** Ticks of draw for a bow's full power (and a crit): BowItem.getPowerForTime reaches 1 at 20. */
    public static final int FULL_DRAW = BowItem.MAX_DRAW_DURATION;
    /** After your own attack or use click, ticks before the bow aura takes your hand (switches your slot or draws). */
    static final int QUIET = 20;
    /** Ticks with nothing to shoot before the slot you had comes back, and before a draw with no target is cancelled. */
    static final int IDLE_RESTORE = 20, IDLE_CANCEL = 10;
    /** Ticks between choices of target. */
    static final int RECHOOSE = 4;
    /** Ticks ready without a shot good enough before the mob is left for SNUB_TICKS and another chosen. */
    static final int GIVE_UP = 40, SNUB_TICKS = 60;
    /** Ticks ready after which a shot at BowTargets.VIABLE goes rather than MIN_CHANCE. */
    static final int PATIENCE = 20;
    /** A mob within the melee reach and this much more is the melee aura's (KillAura.meleeTicks). */
    public static final double REACH_MARGIN = 0.5;
    /** Ticks the client draws other entities behind the server (ProjectileTargeting.INTERP_TICKS). */
    static final double INTERP_TICKS = 2.0;
    /** Spread rolls for the chance that ranks targets, and for the one that lets the shot go. */
    static final int CHOOSE_ROLLS = 256, RELEASE_ROLLS = 1024;
    /** A bystander (not a target) this far from the shot's path, beyond the spread's reach, keeps the shot back. */
    static final double BYSTANDER_PAD = 0.3;
    /** In "on" mode another weapon takes over from the one in hand only when it sustains this many times the damage. */
    static final double SWITCH_GAIN = 1.15;
    /** The angle Multishot's side arrows leave at (CrossbowItem.shoot: 10 degrees either way). */
    static final float MULTISHOT_DEG = 10.0F;
    /** The policy unless -Dkine.bowTarget names another (the target-choice study switches it at run time). */
    public static final BowTargets.Policy DEFAULT_POLICY = BowTargets.Policy.SHARED_THREAT;

    /** Farthest mob shot at, eye to box centre (blocks): the setting. */
    static double range() {
        return Math.max(Settings.BOW_RANGE_MIN, Settings.bowAuraRange);
    }

    // ---------------------------------------------------------------- state

    private static int tick = 0;
    private static boolean holding = false;     // we hold the use key down
    private static boolean ownUse = false;      // the use in progress is ours (we pressed, and it started)
    private static int pressTick = -100;        // when we pressed it
    private static boolean releasing = false;   // we let go this tick (the release packet goes in this tick's keybinds)
    private static boolean cancelHold = false;  // a cancel's slot change: the key stays down this tick so nothing fires
    private static int scrollBack = -1;         // after a cancel by scrolling away: the slot to scroll back to next tick
    private static int restoreSlot = -1;        // the slot you had before the bow aura selected a weapon
    private static int weaponSlot = -1;         // the slot it selected
    private static int lastSlot = -1;           // the selected slot as it left it, to see a change made by someone else
    private static int fetchFrom = -1;          // a weapon to move up from the inventory at the open tick's end
    private static ItemStack fetchStack = ItemStack.EMPTY;
    private static int fetchedTo = -1;          // where it went: selected the tick after
    private static int targetId = -1;
    private static double targetUp = 0.0;       // the height above its box centre the choice aimed at (its head when half hidden)
    private static int lastChoose = -100;
    private static int readySince = -1;         // the tick the shot was ready (full draw, or a loaded crossbow aimed)
    private static int idleSince = -1;          // since when there has been nothing to shoot
    private static int lastClick = -1000;       // your last attack or use click
    private static int tridentOut = -1;         // the tick a trident of ours was thrown, while it is still out
    private static boolean blocked = true;      // a stand-down this tick (set by guard)
    private static boolean meleeFirstNow = false;   // and it is the melee aura's
    private static float[] want = null;         // the rotation it asked the kill aura for this tick
    private static final Map<Integer, Integer> snubbed = new HashMap<>();   // entity id -> tick it may be chosen again
    /** A shot of ours on its way: its mob, the tick it should land, and the damage it should do (times its chance). */
    record InFlight(int target, int lands, double damage) {}
    private static final List<InFlight> inFlight = new ArrayList<>();
    /** A mob hurt takes only what a second hit exceeds the first by for this many ticks (LivingEntity.hurtServer: a hit
     *  sets invulnerableTime 20, and a hit while it is above 10 counts only its excess). */
    static final int HURT_WINDOW = 10;
    private static LocalPlayer lastPlayer = null;

    // read by tests and the reticle
    public static String standDown = "";        // why it stands down this tick, "" when it doesn't
    public static int shots = 0, earlyShots = 0, cancels = 0, meleeStandDowns = 0, fetches = 0, loads = 0;
    public static final int[] shotsBy = new int[Kind.values().length];
    public static long planNanos = 0, maxPlanNanos = 0, planTicks = 0;
    public static double lastReleaseChance = Double.NaN;
    /** Ticks the melee aura had a target in reach while the bow aura's use was in progress (it should stay 0 but for
     *  the one tick a mob may come on unforeseen). */
    public static int meleeBlockedTicks = 0;

    /** Whether it stands down this tick for the melee aura (a mob in reach or about to be). */
    public static boolean standingDownForMelee() { return blocked && meleeFirstNow; }

    /** Whether it holds the use key (the kill aura lets it through while the rotation sent is turned). */
    public static boolean holdingUse() { return holding; }

    /** Whether the item use in progress is the bow aura's, not yours. */
    public static boolean ownsUse(LocalPlayer p) { return ownUse && p.isUsingItem(); }

    /** Whether it is aiming its own shot now (a draw, a load, or a loaded crossbow turned to a mob): the reticle and
     *  auto aim leave the shot alone then. */
    public static boolean aiming(LocalPlayer p) {
        return ownsUse(p) || !blocked && targetId >= 0 && want != null;
    }

    /** Busy with a draw, a fetch, or a cancel: auto eat waits. */
    public static boolean busy() { return holding || ownUse || fetchFrom >= 0 || fetchedTo >= 0 || scrollBack >= 0; }

    /** The mob it is aiming at, or -1. */
    public static int targetId() { return ownUse || holding || want != null ? targetId : -1; }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BowAura::endTick);
    }

    private static BowTargets.Policy policy() {
        return BowTargets.Policy.parse(System.getProperty("kine.bowTarget"), DEFAULT_POLICY);
    }

    // ---------------------------------------------------------------- the tick: guard, plan, act

    /**
     * First in the tick's actions (VanillaActions, before the inventory's open tick and before anything that returns
     * early): notes what happened since the last tick, and lets go when something makes it stand down, the melee aura
     * included. Sets {@link #blocked} for plan and act.
     */
    public static void guard(Minecraft mc) {
        tick++;
        want = null;
        releasing = false;
        meleeFirstNow = false;
        LocalPlayer p = mc.player;
        if (p == null || p != lastPlayer) { reset(); lastPlayer = p; blocked = true; standDown = "no player"; return; }

        // the use: it started after our press, or ended (our release, a cancel, an inventory open's release of every key)
        if (holding && p.isUsingItem() && kindOf(p.getUseItem()) != null) ownUse = true;
        if (ownUse && !p.isUsingItem()) { ownUse = false; readySince = -1; }
        if (holding && !p.isUsingItem() && tick - pressTick >= 1) letGo(mc);   // no use started, it ended, or a press fired
        cancelHold = false;
        if (scrollBack >= 0) {   // the second half of a cancel's scroll
            if (Compat.selectedSlot(p.getInventory()) == lastSlot) { select(p, scrollBack); lastSlot = scrollBack; }
            scrollBack = -1;
        }
        if (tridentOut >= 0 && (tick - tridentOut > 400 || tick - tridentOut > 5 && !tridentInAir(mc, p))) tridentOut = -1;
        Inventory inv = p.getInventory();
        if (lastSlot >= 0 && Compat.selectedSlot(inv) != lastSlot) { restoreSlot = -1; weaponSlot = -1; }   // someone else picked a slot
        if (yourClick(mc)) lastClick = tick;

        String why = hardStandDown(mc, p);
        boolean meleeFirst = false, mayLoose = true;
        if (why == null && Settings.killAura) {
            // melee first: a target in reach, or one that will be before this shot is off (or the shot it would start)
            double meleeTicks = KillAura.meleeTicks(mc, p);
            if (ownUse && meleeTicks <= 0) meleeBlockedTicks++;
            if (meleeTicks <= ticksToShot(p) + 2) {
                why = "the kill aura fighting";
                meleeFirst = true;
                mayLoose = mayLoose(meleeTicks);
            }
        }
        standDown = why == null ? "" : why;
        blocked = why != null;
        meleeFirstNow = meleeFirst;
        if (blocked) {
            if (meleeFirst && (ownUse || holding)) meleeStandDowns++;
            if (ownUse) endDraw(mc, p, why, mayLoose);
            else if (holding && !cancelHold) letGo(mc);
            restore(p);
            targetId = -1;
            fetchFrom = -1;
        }
        lastSlot = Compat.selectedSlot(inv);
    }

    /** Ticks until the shot under way, or the one the weapon in hand would start now, is off. */
    private static int ticksToShot(LocalPlayer p) {
        ItemStack main = p.getMainHandItem();
        Kind k = kindOf(main);
        int used = ownUse ? p.getTicksUsingItem() : 0;
        if (k == null) return FULL_DRAW;   // nothing in hand yet: a bow's draw
        return switch (k) {
            case BOW -> Math.max(0, FULL_DRAW - used);
            case TRIDENT -> Math.max(0, RangedWeapons.TRIDENT_DRAW - used);
            case CROSSBOW -> charged(main) && !ownUse ? 0
                : Math.max(0, RangedWeapons.crossbowCharge(Enchant.level(main, Enchantments.QUICK_CHARGE)) - used) + 2;
        };
    }

    /** Whether the melee aura goes first for a bow: it has a target in reach, or will have one before the draw under way
     *  (or about to start) and the release are off. */
    static boolean meleeFirst(double meleeTicks, int drawn) {
        return meleeTicks <= Math.max(0, FULL_DRAW - drawn) + 2;
    }

    /** Whether a draw the melee aura cuts short may still be loosed: loosing holds the melee aura's swing back a tick
     *  more than a cancel (the slot can't come back in the tick of the release, or the server would stop the use
     *  first), so only while the mob is not yet in reach or about to be. */
    static boolean mayLoose(double meleeTicks) {
        return meleeTicks >= 2;
    }

    /** Why it must stand down now, whatever the melee aura does; null if it may act. */
    private static String hardStandDown(Minecraft mc, LocalPlayer p) {
        if (!Settings.bowAura) return "off";
        if (!p.isAlive() || !GameModes.survivalLike(mc)) return "not alive in survival or adventure";
        if (!Compat.noScreen(mc)) return "a screen open";
        if (ai.rrw.kine.walk.Walk.active()) return "the pathfinder walking";
        if (p.isFallFlying() || Takeoff.active() || Autopilot.isEngaged()) return "elytra flight";
        // airborne with an elytra on: a glide may open any tick, and the rotation sent would then steer it while it
        // turns back to your view, so it turns back now, before the glide (RW: "that would make you fly wildly off course")
        if (!p.onGround() && Compat.isGlider(p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)))
            return "elytra flight";
        if (!onFoot(p)) return "not on foot";
        if (CombatMovement.engagedFight(mc)) return "combat movement fighting";
        if (p.isUsingItem() && !ownUse && !holding) return "an item in use";
        if (AutoEat.busy() && !ownUse) return "auto eat eating";
        if (yourClick(mc) || clicksPending(mc) || KillAura.clicksHeld()) return "you clicking";
        if (tick - lastClick < QUIET && !ownUse) return "you clicked lately";
        if (Settings.bowAuraMode == Settings.BOW_HELD && !ownUse && !holding && !usable(mc, p, p.getMainHandItem()))
            return "no ranged weapon in hand";
        return null;
    }

    /**
     * Where the rotation sent should go for the shot this tick, or null for none (KillAura.beforeKeybinds, when it isn't
     * swinging; the guard has already stood it down if the melee aura is active). Also decides a release, which act
     * makes. {@code viewYaw}: the view's yaw, which the rotation wanted is kept within half a turn of.
     */
    public static float[] plan(Minecraft mc, LocalPlayer p, float viewYaw) {
        if (blocked) return null;
        long t0 = System.nanoTime();
        try {
            return planInner(mc, p, viewYaw);
        } finally {
            long dt = System.nanoTime() - t0;
            planNanos += dt; planTicks++;
            maxPlanNanos = Math.max(maxPlanNanos, dt);
        }
    }

    private static float[] planInner(Minecraft mc, LocalPlayer p, float viewYaw) {
        ClientLevel level = mc.level;
        if (fetchFrom >= 0 || fetchedTo >= 0) return null;   // a weapon on its way up from the inventory
        ItemStack main = p.getMainHandItem();
        Kind kind = kindOf(main);
        int used = ownUse ? p.getTicksUsingItem() : 0;
        Vec3 motion = new Vec3(p.getX() - p.xOld, p.getY() - p.yOld, p.getZ() - p.zOld);
        double ping = pingTicks(mc, p);
        Holder<DamageType> arrow = Compat.damageType(level, DamageTypes.ARROW);

        // a bow or a trident drawn full: the release, when the shot the server would fire now (along the rotation sent
        // last tick, from where the last move packet put you) hits often enough with nothing else in its way
        if (ownUse && (kind == Kind.BOW && used >= FULL_DRAW || kind == Kind.TRIDENT && used >= RangedWeapons.TRIDENT_DRAW)) {
            if (readySince < 0) readySince = tick;
            Entity t = level.getEntity(targetId);
            if (t instanceof LivingEntity le && le.isAlive()) {
                double c = releaseChance(mc, p, le, kind, used, motion, ping);
                double need = tick - readySince >= PATIENCE ? BowTargets.VIABLE : MIN_CHANCE;
                if (c >= need) {
                    releasing = true;
                    lastReleaseChance = c;
                    shots++; shotsBy[kind.ordinal()]++;
                    noteShot(p, le, kind, main, c, 1);
                    if (kind == Kind.TRIDENT) tridentOut = tick;
                    Kine.LOGGER.info("Kine bow aura: {} shot at {} {} ({}% chance, drawn {} ticks, {})", kind.name().toLowerCase(),
                                     le.getType().toShortString(), le.getId(), Math.round(c * 100), used, arms(main));
                    targetId = -1;   // choose afresh for the next shot
                    lastChoose = -100;
                }
            }
            if (!releasing && tick - readySince > GIVE_UP && targetId >= 0) {   // no good shot at it for 2 s: try another
                snubbed.put(targetId, tick + SNUB_TICKS);
                targetId = -1;
            }
        }
        // a crossbow being loaded: let go once the game says it is loaded (or it surely is), with no shot
        if (ownUse && kind == Kind.CROSSBOW) {
            int charge = RangedWeapons.crossbowCharge(Enchant.level(main, Enchantments.QUICK_CHARGE));
            if (CrossbowItem.isCharged(main) || used >= charge + 2 + (int) Math.ceil(ping)) {
                releasing = true;
                loads++;
            }
        }

        // the target, chosen every few ticks (at once when there is none or it has gone), for the weapon in hand (a bow's
        // flight when none is in hand yet)
        Vec3 eye = p.getEyePosition();
        Entity cur = targetId >= 0 ? level.getEntity(targetId) : null;
        boolean curOk = cur instanceof LivingEntity cl && cl.isAlive() && !cl.isRemoved();
        if (!curOk || tick - lastChoose >= RECHOOSE) {
            lastChoose = tick;
            Kind k = kind != null ? kind : Kind.BOW;
            targetId = choose(mc, p, level, eye, shotSpawn(eye, motion, k), shotMotion(motion, k), shotLead(k, ping), arrow, k);
            cur = targetId >= 0 ? level.getEntity(targetId) : null;
        }
        if (!(cur instanceof LivingEntity target)) {
            if (idleSince < 0) idleSince = tick;
            if (ownUse && !releasing && tick - idleSince >= IDLE_CANCEL) endDraw(mc, p, "nothing to shoot", false);
            if (!ownUse && tick - idleSince >= IDLE_RESTORE) restore(p);
            return null;
        }
        idleSince = -1;

        // a weapon in hand before the shot: in "on", the strongest one it carries
        if (!ownUse && !holding) {
            if (!weaponReady(mc, p, target)) return null;
            main = p.getMainHandItem();
            kind = kindOf(main);
            if (kind == null) return null;
        }
        if (kind == null) return null;

        // the aim at the best point on the mob: for a bow or trident the shot a release next tick fires, for a loaded
        // crossbow the one a press this tick fires (and for one being loaded the same, so it is aimed once loaded)
        Spec spec = RangedWeapons.spec(kind);
        Vec3 spawn = shotSpawn(eye, motion, kind), sm = shotMotion(motion, kind);
        double lead = shotLead(kind, ping);
        Ballistics.Course course = ProjectileTargeting.courseOf(level, target);
        Vec3 best = AimAdvisor.aimOffset(target, spawn, Compat.gameTime(level));
        if (best.equals(Vec3.ZERO)) best = new Vec3(0, targetUp, 0);   // no search result for it yet: the choice's point
        Vec3 center = target.getBoundingBox().getCenter().add(course.at(lead)).add(best);
        Ballistics.Solution sol = Ballistics.solve(spawn, center, course, lead, spec, sm, p.onGround());
        boolean loaded = kind == Kind.CROSSBOW && charged(main) && !ownUse;
        if (ownUse || loaded) {   // the best point and the chance of the shot as sent, on the advisor's workers
            float sy = KillAura.lastSentYaw(p), sp = KillAura.lastSentPitch(p);
            AimAdvisor.update(level, spawn, Ballistics.launchDir(sy, sp, spec), spec, spec, sm, p.onGround(), target,
                              course, true, lead);
        }
        if (loaded && readySince < 0) readySince = tick;
        if (loaded && tick - readySince > GIVE_UP) {
            snubbed.put(target.getId(), tick + SNUB_TICKS);
            targetId = -1;
            readySince = -1;
            return null;
        }
        float yaw = viewYaw + Mth.wrapDegrees((float) sol.yaw() - viewYaw);
        want = new float[]{yaw, Mth.clamp((float) sol.pitch(), -90f, 90f)};
        return want;
    }

    /** Where the server spawns the shot: a release next tick shoots from the next move packet's place (a tick of your
     *  motion on); a crossbow's press this tick from the last one's. Eye height less 0.1 either way. */
    private static Vec3 shotSpawn(Vec3 eye, Vec3 motion, Kind k) {
        return Ballistics.spawnPoint(eye, motion, k != Kind.CROSSBOW);
    }

    /** The shooter's motion the shot takes on (none for a crossbow's). */
    private static Vec3 shotMotion(Vec3 motion, Kind k) {
        return RangedWeapons.spec(k).inherits() ? motion : Vec3.ZERO;
    }

    /** Ticks from the client's view of a mob now to the shot's spawn: the drawing lag, the release's tick or two, and the
     *  round trip. */
    private static double shotLead(Kind k, double ping) {
        return INTERP_TICKS + (k == Kind.CROSSBOW ? 1 : Ballistics.ticksToShot(true)) + ping;
    }

    /**
     * After the kill aura's turn and pick (VanillaActions): the use key for this tick. Pressed to start a draw or a load
     * (the game's keybind step then starts it with this tick's rotation) or to fire a loaded crossbow (along this tick's
     * rotation, which the use packet carries), held while drawing or loading, let go to loose.
     */
    public static void act(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || blocked) return;
        KeyMapping use = mc.options.keyUse;
        if (releasing) { use.setDown(false); holding = false; return; }
        if (ownUse || cancelHold) { use.setDown(true); holding = true; return; }
        if (want == null || holding) return;
        ItemStack main = p.getMainHandItem();
        Kind kind = kindOf(main);
        if (kind == null || !usable(mc, p, main)) return;
        // a new use only standing on the ground: not in a jump or a fall (an elytra may open, and the rotation sent
        // would steer the glide while it turns back; measured in a lockstep test, a draw begun in a fall)
        if (!p.onGround()) return;
        if (((MinecraftInvoker) mc).kine$getRightClickDelay() > 0 || AutoEat.busy() || InventoryClicks.isOpenTick()) return;
        // the server's word that the last use has ended must have come first: arriving after a new use has started, it
        // would stop that use on the client (LocalPlayer.onSyncedDataUpdated) and the server's start would then restart
        // it out of step (measured in the first lockstep run: every draw right after a release was lost so)
        if (serverUsing(p)) return;
        // the crosshair at the rotation sent on nothing or a hostile (the use would go to a block, a villager or a pet)
        HitResult h = mc.hitResult;
        boolean clear = h == null || h.getType() == HitResult.Type.MISS
            || h instanceof EntityHitResult eh && KillAura.targets(eh.getEntity(), p);
        if (!clear) return;
        if (kind == Kind.CROSSBOW && charged(main)) {
            // a loaded crossbow fires on this press, along this tick's rotation (now turned): only if that shot is good
            Entity t = mc.level.getEntity(targetId);
            if (!(t instanceof LivingEntity le) || !le.isAlive()) return;
            double c = crossbowChance(mc, p, le, main);
            double need = readySince >= 0 && tick - readySince >= PATIENCE ? BowTargets.VIABLE : MIN_CHANCE;
            if (c < need) return;
            shots++; shotsBy[Kind.CROSSBOW.ordinal()]++;
            noteShot(p, le, Kind.CROSSBOW, main, c, 0);
            lastReleaseChance = c;
            readySince = -1;
            Kine.LOGGER.info("Kine bow aura: crossbow shot at {} {} ({}% chance, {})", le.getType().toShortString(), le.getId(),
                             Math.round(c * 100), arms(main));
            targetId = -1;
            lastChoose = -100;
        }
        use.setDown(true);
        holding = true;
        pressTick = tick;
    }

    /** End of the tick: the inventory's open tick plays out a fetch (the swap, then the close). */
    private static void endTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) { fetchFrom = -1; fetchedTo = -1; return; }
        if (fetchedTo >= 0 && fetchFrom < 0) {   // the tick after the swap: select it
            if (kindOf(Compat.mainItems(p.getInventory()).get(fetchedTo)) != null) {
                if (restoreSlot < 0) restoreSlot = Compat.selectedSlot(p.getInventory());
                select(p, fetchedTo);
                weaponSlot = fetchedTo;
                lastSlot = fetchedTo;
            }
            fetchedTo = -1;
            return;
        }
        if (fetchFrom < 0 || !InventoryClicks.isOpenTick()) return;
        int from = fetchFrom;
        ItemStack want = fetchStack;
        fetchFrom = -1; fetchStack = ItemStack.EMPTY;
        if (!InventoryClicks.available(mc, p) || !InventoryClicks.claimBatch()) return;
        AbstractContainerMenu menu = p.inventoryMenu;
        if (ItemStack.matches(menu.getSlot(from).getItem(), want)) {
            int to = Hotbar.slotToDisplace(Compat.mainItems(p.getInventory())::get);
            mc.gameMode.handleContainerInput(menu.containerId, from, to, ContainerInput.SWAP, p);   // number-key swap
            fetchedTo = to;
            fetches++;
        }
        InventoryClicks.closeAsVanilla(mc, p);
    }

    // ---------------------------------------------------------------- the choice

    /** The mob to shoot (an entity id), or -1: BowTargets' policy over the mobs it could hit with this weapon. */
    private static int choose(Minecraft mc, LocalPlayer p, ClientLevel level, Vec3 eye, Vec3 spawn, Vec3 motion,
                              double lead, Holder<DamageType> arrow, Kind kind) {
        snubbed.values().removeIf(until -> until <= tick);
        inFlight.removeIf(f -> f.lands() < tick - HURT_WINDOW);
        double reach = p.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        double range = range();
        boolean melee = Settings.killAura;
        Spec spec = RangedWeapons.spec(kind);
        ItemStack main = p.getMainHandItem();
        Arms arms = kindOf(main) == kind ? arms(main) : new Arms(kind, 0, 0, 0, 3, 0);
        boolean piercing = kind == Kind.CROSSBOW && Enchant.level(main, Enchantments.PIERCING) > 0;
        int multishot = kind == Kind.CROSSBOW ? Enchant.level(main, Enchantments.MULTISHOT) : 0;
        Holder<DamageType> type = kind == Kind.TRIDENT
            ? Compat.damageType(level, DamageTypes.TRIDENT) : arrow;
        List<BowTargets.Cand> cands = new ArrayList<>();
        List<Double> ups = new ArrayList<>();
        List<Entity> others = bystanders(mc, p);
        boolean debug = Boolean.getBoolean("kine.bowDebug");
        StringBuilder dbg = debug ? new StringBuilder("Kine bow aura choice t=" + tick + " " + kind + ":") : null;
        for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(range), x -> x instanceof LivingEntity)) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || snubbed.containsKey(e.getId())) continue;
            if (!KillAura.targets(e, p) || ProjectileTargeting.ownPet(p, e)) continue;
            Vec3 c = e.getBoundingBox().getCenter();
            double dist = c.distanceTo(eye);
            String why = dist > range || dist < 1e-3 ? "range" : NoDamage.reason(e, spawn, type, piercing) != null ? "no damage"
                : melee && KillAura.eyeToBox(eye, e.getBoundingBox()) <= reach + REACH_MARGIN ? "melee's"
                : !ProjectileTargeting.visible(level, p, eye, e) ? "hidden" : null;
            if (debug && why != null) dbg.append(String.format(java.util.Locale.ROOT, " %s#%d %.1f %s;", e.getType().toShortString(), e.getId(), dist, why));
            if (why != null) continue;
            Ballistics.Course course = ProjectileTargeting.courseOf(level, e);
            Vec3 center = c.add(course.at(lead));
            Ballistics.Solution sol = Ballistics.solve(spawn, center, course, lead, spec, motion, p.onGround());
            if (Math.abs(sol.miss()) > 0.5) {   // out of the weapon's reach
                if (debug) dbg.append(String.format(java.util.Locale.ROOT, " %s#%d %.1f miss %.2f;", e.getType().toShortString(), e.getId(), dist, sol.miss()));
                continue;
            }
            double chance = AimAdvisor.chanceNow(level, spawn, Ballistics.launchDir(sol.yaw(), sol.pitch(), spec), spec,
                                                 motion, p.onGround(), e, course, lead, CHOOSE_ROLLS);
            if (Double.isNaN(chance)) {
                if (debug) dbg.append(String.format(java.util.Locale.ROOT, " %s#%d %.1f no chance;", e.getType().toShortString(), e.getId(), dist));
                continue;
            }
            double up = 0.0;
            if (chance < MIN_CHANCE) {
                // half hidden (a low wall, a slab, a fence): its head may show over it. AimAdvisor's search finds the best
                // point once a shot is aimed at it; until then, the head when that is the better shot
                double head = e.getEyeY() - c.y;
                Ballistics.Solution hs = Ballistics.solve(spawn, center.add(0, head, 0), course, lead, spec, motion, p.onGround());
                double hc = Math.abs(hs.miss()) > 0.5 ? 0.0 : AimAdvisor.chanceNow(level, spawn,
                    Ballistics.launchDir(hs.yaw(), hs.pitch(), spec), spec, motion, p.onGround(), e, course, lead, CHOOSE_ROLLS);
                if (hc > chance) { chance = hc; up = head; sol = hs; }
            }
            // a pet, a villager or anything else that isn't a target near the path (and its side arrows' and, piercing,
            // its way past the mob): no shot from here
            if (chance > 0 && !clearOf(others, le, spawn, sol.yaw(), sol.pitch(), spec, motion, p.onGround(), lead, multishot, piercing))
                chance = 0.0;
            double dmg = RangedWeapons.hit(arms, dist, le.getArmorValue(), le.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                                           e.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_IMPALING));
            // our shots already on their way: the health they should leave, and a hit landing in the window of one of
            // them counts only its excess; a mob they should kill is left to them
            int lands = tick + (int) RangedWeapons.busy(arms) + (int) Math.ceil(dist / (spec.speed() * 0.97));
            double health = le.getHealth() + le.getAbsorptionAmount() - pending(e.getId(), tick, inFlight);
            dmg = afterHurtWindow(e.getId(), lands, dmg, inFlight);
            if (health <= 0.0) chance = 0.0;
            BowTargets.Cand cand = new BowTargets.Cand(e.getId(), kindOf(le), dist, Math.max(0.5, health),
                chance, dps(le), attackRange(le), true, arriveTicks(p, le, reach), Math.max(0.5, dmg));
            cands.add(cand);
            ups.add(up);
            if (debug) dbg.append(String.format(java.util.Locale.ROOT, " %s#%d %.1f chance %.2f miss %.2f threat %.4f score %.4g;",
                e.getType().toShortString(), e.getId(), dist, chance, sol.miss(), BowTargets.threat(cand), BowTargets.score(cand, policy())));
        }
        int i = BowTargets.pick(cands, policy(), targetId);
        if (debug) Kine.LOGGER.info("{} -> {}", dbg, i < 0 ? "none" : "#" + cands.get(i).id());
        targetUp = i < 0 ? 0.0 : ups.get(i);
        return i < 0 ? -1 : cands.get(i).id();
    }

    /** A shot gone: when it should land (the server shoots a tick or so on, then the flight) and what it should do. */
    private static void noteShot(LocalPlayer p, LivingEntity t, Kind kind, ItemStack weapon, double chance, int delay) {
        double dist = t.getBoundingBox().getCenter().distanceTo(p.getEyePosition());
        int flight = (int) Math.ceil(dist / (RangedWeapons.spec(kind).speed() * 0.97));
        double dmg = RangedWeapons.hit(arms(weapon), dist, t.getArmorValue(), t.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                                       t.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_IMPALING));
        inFlight.add(new InFlight(t.getId(), tick + delay + 1 + flight, dmg * chance));
    }

    /** The damage of our shots still on their way to this mob (expected: each times its chance). */
    static double pending(int id, int now, List<InFlight> shots) {
        double sum = 0;
        for (InFlight f : shots) if (f.target() == id && f.lands() >= now) sum += f.damage();
        return sum;
    }

    /** What a hit landing at `lands` adds over our hits landing within HURT_WINDOW ticks before or after it (the game
     *  counts only the excess of the bigger). */
    static double afterHurtWindow(int id, int lands, double dmg, List<InFlight> shots) {
        double near = 0;
        for (InFlight f : shots) if (f.target() == id && Math.abs(f.lands() - lands) < HURT_WINDOW) near = Math.max(near, f.damage());
        return Math.max(0.0, dmg - near);
    }

    /** How the mob hurts you, for the target choice. */
    static BowTargets.Kind kindOf(LivingEntity e) {
        if (e instanceof Creeper) return BowTargets.Kind.CREEPER;
        return ranged(e) ? BowTargets.Kind.RANGED : BowTargets.Kind.MELEE;
    }

    /** Mobs that attack from range: the skeleton kin with a bow, pillagers and piglins with a crossbow, illusioners,
     *  witches, blazes, ghasts, drowned with a trident, guardians, shulkers, evokers, the wither. */
    static boolean ranged(LivingEntity e) {
        if (e instanceof AbstractSkeleton s) return s.isHolding(Items.BOW);
        if (e instanceof Pillager || e instanceof Piglin pg && pg.isHolding(Items.CROSSBOW)) return true;
        if (e instanceof Drowned d) return d.isHolding(Items.TRIDENT);
        return e instanceof Illusioner || e instanceof Witch || e instanceof Blaze || e instanceof Ghast
            || e instanceof Guardian || e instanceof Shulker || e instanceof Evoker || e instanceof WitherBoss;
    }

    /** Health a tick the mob takes off you while it attacks, on Normal (approximate: a shot's or a hit's damage over the
     *  time between them). Ranged mobs by kind; melee mobs their attack damage over the 20 ticks of a melee cooldown. */
    static double dps(LivingEntity e) {
        if (!ranged(e)) {
            double atk = e.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE) ? e.getAttributeValue(Attributes.ATTACK_DAMAGE) : 2.0;
            return atk / 20.0;
        }
        if (e instanceof AbstractSkeleton) return 3.5 * 0.7 / 40.0;   // 2 to 5 an arrow, a shot every 40 ticks on Normal
        if (e instanceof Pillager || e instanceof Piglin) return 4.0 * 0.7 / 55.0;   // a crossbow: charge 25, wait 20 to 40
        if (e instanceof Witch) return 6.0 / 60.0;                    // harming, every 60 ticks
        if (e instanceof Blaze) return 15.0 * 0.5 / 100.0;            // three fireballs of 5
        if (e instanceof Ghast) return 6.0 / 60.0;
        if (e instanceof Drowned) return 8.0 / 40.0;                  // a trident
        if (e instanceof Guardian) return 6.0 / 80.0;
        if (e instanceof Shulker) return 4.0 / 60.0;
        if (e instanceof Evoker) return 6.0 / 40.0;
        if (e instanceof Illusioner) return 3.5 * 0.7 / 40.0;
        return 8.0 / 40.0;   // the wither
    }

    /** The range a ranged mob attacks from (blocks). */
    static double attackRange(LivingEntity e) {
        if (e instanceof AbstractSkeleton || e instanceof Illusioner) return 15.0;
        if (e instanceof Pillager || e instanceof Piglin) return 8.0;
        if (e instanceof Witch || e instanceof Drowned) return 10.0;
        if (e instanceof Shulker || e instanceof Guardian) return 16.0;
        if (e instanceof Evoker) return 12.0;
        return 48.0;   // blazes, ghasts, the wither: far
    }

    /** Ticks until the mob is on you at its present closing speed (its melee reach, a creeper's 3-block fuse start),
     *  infinite when it isn't closing. */
    static double arriveTicks(LocalPlayer p, LivingEntity e, double reach) {
        double gap = Math.sqrt(p.distanceToSqr(e)) - (e instanceof Creeper ? 3.0 : 1.5);
        if (gap <= 0) return 0.0;
        double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ(), d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-6) return 0.0;
        double vx = (e.getX() - e.xo) - (p.getX() - p.xo), vz = (e.getZ() - e.zo) - (p.getZ() - p.zo);
        double closing = -(vx * dx + vz * dz) / d;
        return closing > 1e-3 ? gap / closing : Double.POSITIVE_INFINITY;
    }

    // ---------------------------------------------------------------- the shot

    /** The chance the shot a release now fires hits (a bow at the draw's power, a trident at its throw): along the
     *  rotation sent last tick, from where the last move packet put you; 0 if something that isn't a target stands near
     *  its path first. */
    private static double releaseChance(Minecraft mc, LocalPlayer p, LivingEntity t, Kind kind, int used, Vec3 motion, double ping) {
        Spec spec = kind == Kind.BOW ? RangedWeapons.bowSpec(BowItem.getPowerForTime(used)) : RangedWeapons.spec(kind);
        float yaw = KillAura.lastSentYaw(p), pitch = KillAura.lastSentPitch(p);
        Vec3 spawn = Ballistics.spawnPoint(p.getEyePosition(), motion, false);   // the server has the last move packet's place
        double lead = INTERP_TICKS + 1 + ping;
        return shotChance(mc, p, t, spec, yaw, pitch, spawn, motion, lead, 0, false);
    }

    /** The chance a loaded crossbow's shot fired by a press now hits: along this tick's rotation (turned already), from
     *  where the last move packet put you, without your motion. */
    private static double crossbowChance(Minecraft mc, LocalPlayer p, LivingEntity t, ItemStack xbow) {
        Spec spec = RangedWeapons.spec(Kind.CROSSBOW);
        Vec3 spawn = Ballistics.spawnPoint(p.getEyePosition(), Vec3.ZERO, false);
        double lead = INTERP_TICKS + 1 + pingTicks(mc, p);
        return shotChance(mc, p, t, spec, p.getYRot(), p.getXRot(), spawn, Vec3.ZERO, lead,
                          Enchant.level(xbow, Enchantments.MULTISHOT), Enchant.level(xbow, Enchantments.PIERCING) > 0);
    }

    private static double shotChance(Minecraft mc, LocalPlayer p, LivingEntity t, Spec spec, float yaw, float pitch,
                                     Vec3 spawn, Vec3 motion, double lead, int multishot, boolean piercing) {
        Ballistics.Course course = ProjectileTargeting.courseOf(mc.level, t);
        Vec3 dir = Ballistics.launchDir(yaw, pitch, spec);
        double c = AimAdvisor.chanceNow(mc.level, spawn, dir, spec, motion, p.onGround(), t, course, lead, RELEASE_ROLLS);
        if (Double.isNaN(c) || c <= 0) return 0.0;
        return clearOf(bystanders(mc, p), t, spawn, yaw, pitch, spec, motion, p.onGround(), lead, multishot, piercing) ? c : 0.0;
    }

    /** Everything a projectile can hit within reach that isn't a target (nor your mount or those riding with you). */
    private static List<Entity> bystanders(Minecraft mc, LocalPlayer p) {
        double r = range() + 8, reachSq = r * r;
        List<Entity> others = new ArrayList<>();
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(r), Entity::canBeHitByProjectile))
            if (!KillAura.targets(e, p) && e.distanceToSqr(p) <= reachSq && !p.isPassengerOfSameVehicle(e)) others.add(e);
        return others;
    }

    /**
     * Whether the shot's path (without spread) passes clear of everything a projectile can hit that isn't a target:
     * your pets, villagers, players, animals, armour stands. Each is met where it will be along its motion, its box grown
     * by how far the spread can carry the shot by then (the triangle's full width on both axes) and BYSTANDER_PAD.
     * Another hostile in the way is fine. Up to the mob, or past it for Piercing; Multishot's side arrows too.
     */
    private static boolean clearOf(List<Entity> others, LivingEntity t, Vec3 spawn, double yaw, double pitch, Spec spec,
                                   Vec3 motion, boolean onGround, double lead, int multishot, boolean piercing) {
        if (others.isEmpty()) return true;
        double reach = t.getBoundingBox().getCenter().distanceTo(spawn) + (piercing ? 24.0 : 1.0);
        double[] yaws = multishot > 0 ? new double[]{yaw, yaw - MULTISHOT_DEG, yaw + MULTISHOT_DEG} : new double[]{yaw};
        for (double y : yaws) {
            Vec3 vel = Ballistics.launchVel(y, pitch, spec, motion, onGround);
            if (!pathClear(others, t, spawn, vel, spec, lead, reach)) return false;
        }
        return true;
    }

    private static boolean pathClear(List<Entity> others, LivingEntity t, Vec3 spawn, Vec3 vel, Spec spec, double lead, double reach) {
        double spread = HitChance.SPREAD * Math.sqrt(2.0);
        final boolean[] blocked = {false};
        final double[] flown = {0.0};
        Ballistics.simulate(spawn, vel, spec, (from, to, age) -> {
            flown[0] += to.subtract(from).length();
            AABB seg = new AABB(from, to);
            for (Entity e : others) {
                if (e == t) continue;
                Vec3 m = new Vec3(e.getX() - e.xOld, e.getY() - e.yOld, e.getZ() - e.zOld).scale(lead + age);
                AABB b = e.getBoundingBox().move(m).inflate(spread * flown[0] + HitChance.margin(age) + BYSTANDER_PAD);
                if (b.intersects(seg.inflate(0.01)) && b.clip(from, to).isPresent() || b.contains(from)) {
                    blocked[0] = true;
                    return new Ballistics.Hit<>(to, null, e);
                }
            }
            return from.distanceTo(spawn) > reach ? new Ballistics.Hit<>(to, null, null) : null;
        }, 200);
        return !blocked[0];
    }

    /** Let go of a use under way: a crossbow's load is simply let go (no shot); a bow's or trident's draw is loosed if
     *  allowed and the shot as aimed would still hit, else cancelled by scrolling the hotbar away. */
    private static void endDraw(Minecraft mc, LocalPlayer p, String why, boolean mayLoose) {
        if (!ownUse) { if (holding && !cancelHold) letGo(mc); return; }
        ItemStack main = p.getMainHandItem();
        Kind kind = kindOf(main);
        if (kind == Kind.CROSSBOW) {   // a load let go: the release fires nothing (CrossbowItem.releaseUsing)
            releasing = true;
            mc.options.keyUse.setDown(false);
            holding = false;
            return;
        }
        int used = p.getTicksUsingItem();
        Entity t = targetId >= 0 && mc.level != null ? mc.level.getEntity(targetId) : null;
        double c = 0.0;
        boolean ready = kind == Kind.BOW ? BowItem.getPowerForTime(used) >= 0.1F : used >= RangedWeapons.TRIDENT_DRAW;
        if (mayLoose && kind != null && t instanceof LivingEntity le && le.isAlive() && ready && mc.level != null) {
            Vec3 motion = new Vec3(p.getX() - p.xOld, p.getY() - p.yOld, p.getZ() - p.zOld);
            c = releaseChance(mc, p, le, kind, used, motion, pingTicks(mc, p));
        }
        if (c >= MIN_CHANCE) {
            releasing = true;
            mc.options.keyUse.setDown(false);
            holding = false;
            shots++; shotsBy[kind.ordinal()]++;
            noteShot(p, (LivingEntity) t, kind, main, c, 1);
            if (kind == Kind.TRIDENT) tridentOut = tick;
            if (kind == Kind.BOW && used < FULL_DRAW) earlyShots++;
            lastReleaseChance = c;
            Kine.LOGGER.info("Kine bow aura: {} shot early at {} ({}% chance, drawn {} ticks): {}", kind.name().toLowerCase(),
                             t.getType().toShortString(), Math.round(c * 100), used, why);
            return;
        }
        // a vanilla player cancels a draw by scrolling to another slot: the server stops the use on the slot change. The
        // key stays down this tick so the keybind step doesn't loose the shot first; let go once the use has ended.
        Inventory inv = p.getInventory();
        int now = Compat.selectedSlot(inv);
        int to = restoreSlot >= 0 && restoreSlot != now ? restoreSlot : (now + 1) % Hotbar.SIZE;
        if (to != restoreSlot) scrollBack = now;   // your own weapon: scroll back to it next tick
        else { restoreSlot = -1; weaponSlot = -1; }
        select(p, to);
        lastSlot = to;
        cancelHold = true;
        holding = true;
        pressTick = tick;
        mc.options.keyUse.setDown(true);
        cancels++;
        Kine.LOGGER.info("Kine bow aura: draw cancelled ({}): {}", used, why);
    }

    private static void letGo(Minecraft mc) {
        if (!holding) return;
        mc.options.keyUse.setDown(physical(mc, mc.options.keyUse));
        holding = false;
    }

    // ---------------------------------------------------------------- the weapons

    /** The kind of ranged weapon a stack is, or null (anything else). */
    static Kind kindOf(ItemStack st) {
        if (st.is(Items.BOW)) return Kind.BOW;
        if (st.is(Items.CROSSBOW)) return Kind.CROSSBOW;
        if (st.is(Items.TRIDENT)) return Kind.TRIDENT;
        return null;
    }

    /** A weapon's enchantments as the rate reads them. */
    static Arms arms(ItemStack st) {
        Kind k = kindOf(st);
        return new Arms(k == null ? Kind.BOW : k, Enchant.level(st, Enchantments.POWER), Enchant.level(st, Enchantments.FLAME),
                        Enchant.level(st, Enchantments.QUICK_CHARGE), Enchant.level(st, Enchantments.LOYALTY),
                        Enchant.level(st, Enchantments.IMPALING), charged(st));
    }

    /** A loaded crossbow (with arrows, not a rocket). */
    static boolean charged(ItemStack st) {
        ChargedProjectiles cp = Compat.component(st, DataComponents.CHARGED_PROJECTILES);
        return st.is(Items.CROSSBOW) && cp != null && !cp.isEmpty() && !cp.contains(Items.FIREWORK_ROCKET);
    }

    /**
     * A weapon that can shoot now, and may: never one about to break (the game's own rule for a trident; for the rest so
     * the aura never breaks your weapon); a bow with arrows to hand (or infinite materials); a crossbow loaded with
     * arrows, or empty with arrows to load and no rocket that would go in first; a trident with Loyalty (no Riptide,
     * and not a Channeling one in a thunderstorm; one thrown is out of the inventory until it comes back).
     */
    static boolean usable(Minecraft mc, LocalPlayer p, ItemStack st) {
        Kind k = kindOf(st);
        if (k == null || Compat.nextDamageWillBreak(st)) return false;
        return switch (k) {
            case BOW -> p.hasInfiniteMaterials() || !p.getProjectile(st).isEmpty();
            case CROSSBOW -> {
                ChargedProjectiles cp = Compat.component(st, DataComponents.CHARGED_PROJECTILES);
                if (cp != null && !cp.isEmpty()) yield !cp.contains(Items.FIREWORK_ROCKET);
                ItemStack ammo = p.getProjectile(st);
                yield ammo.isEmpty() ? p.hasInfiniteMaterials() : !ammo.is(Items.FIREWORK_ROCKET);
            }
            case TRIDENT -> Enchant.level(st, Enchantments.LOYALTY) > 0 && Enchant.level(st, Enchantments.RIPTIDE) == 0
                && !(Enchant.level(st, Enchantments.CHANNELING) > 0 && mc.level != null && mc.level.isThundering());
        };
    }

    /** Whether a trident of ours is still in the air (or stuck, before Loyalty brings it back). */
    private static boolean tridentInAir(Minecraft mc, LocalPlayer p) {
        return mc.level != null && !mc.level.getEntitiesOfClass(ThrownTrident.class, p.getBoundingBox().inflate(128.0),
            t -> t.isAlive() && t.getOwner() == p).isEmpty();
    }

    /**
     * The weapon to shoot with in hand. Held only: the one in your selected slot, if it can shoot. On: the strongest one
     * carried (RangedWeapons.rate on this mob), the one in hand kept unless another sustains SWITCH_GAIN times its
     * damage; from the hotbar by a number key, from the inventory moved up through an inventory opening (into an empty
     * hotbar slot, else in place of the largest stack). False until one is in hand.
     */
    private static boolean weaponReady(Minecraft mc, LocalPlayer p, LivingEntity target) {
        Inventory inv = p.getInventory();
        ItemStack main = p.getMainHandItem();
        boolean mainOk = usable(mc, p, main);
        if (Settings.bowAuraMode != Settings.BOW_ON) return mainOk;
        if (tick - lastClick < QUIET) return mainOk;   // your hand: not while you work with it
        double dist = target.getBoundingBox().getCenter().distanceTo(p.getEyePosition());
        double armour = target.getArmorValue(), tough = target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        boolean impalable = target.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_IMPALING);
        // the hit chance on this mob by kind (their speeds and drops differ; the spread is the same): one solve each
        double[] chance = new double[Kind.values().length];
        java.util.Arrays.fill(chance, Double.NaN);
        int best = -1;
        double bestRate = -1, mainRate = mainOk ? weaponRate(main, dist, armour, tough, impalable) * kindChance(mc, p, target, kindOf(main), chance) : -1;
        for (int i = 0; i < 36; i++) {
            ItemStack st = Compat.mainItems(inv).get(i);
            if (!usable(mc, p, st)) continue;
            double r = weaponRate(st, dist, armour, tough, impalable) * kindChance(mc, p, target, kindOf(st), chance);
            if (r > bestRate + 1e-9 || Math.abs(r - bestRate) <= 1e-9 && best >= Hotbar.SIZE && i < Hotbar.SIZE) {
                best = i; bestRate = r;
            }
        }
        if (best < 0) return false;
        if (mainOk && (best == Compat.selectedSlot(inv) || bestRate < mainRate * SWITCH_GAIN)) return true;
        if (best < Hotbar.SIZE) {
            if (restoreSlot < 0) restoreSlot = Compat.selectedSlot(inv);
            select(p, best);
            weaponSlot = best;
            lastSlot = best;
            return true;
        }
        if (InventoryClicks.available(mc, p) && !InventoryClicks.isOpenTick() && !AutoEat.busy()) {
            fetchFrom = best;
            fetchStack = Compat.mainItems(inv).get(best);
            InventoryClicks.requestOpen();
        }
        return false;
    }

    /** The chance a shot of this kind aimed now hits the mob, worked out once a choice (kept in `memo`). */
    private static double kindChance(Minecraft mc, LocalPlayer p, LivingEntity t, Kind k, double[] memo) {
        if (!Double.isNaN(memo[k.ordinal()])) return memo[k.ordinal()];
        Vec3 eye = p.getEyePosition(), motion = new Vec3(p.getX() - p.xOld, p.getY() - p.yOld, p.getZ() - p.zOld);
        Spec spec = RangedWeapons.spec(k);
        Vec3 spawn = shotSpawn(eye, motion, k), sm = shotMotion(motion, k);
        double lead = shotLead(k, pingTicks(mc, p));
        Ballistics.Course course = ProjectileTargeting.courseOf(mc.level, t);
        Ballistics.Solution sol = Ballistics.solve(spawn, t.getBoundingBox().getCenter().add(course.at(lead)), course, lead, spec, sm, p.onGround());
        double c = Math.abs(sol.miss()) > 0.5 ? 0.0 : AimAdvisor.chanceNow(mc.level, spawn, Ballistics.launchDir(sol.yaw(), sol.pitch(), spec),
            spec, sm, p.onGround(), t, course, lead, CHOOSE_ROLLS);
        memo[k.ordinal()] = Double.isNaN(c) ? 0.0 : Math.max(0.05, c);   // a floor: a kind that misses now still counts a little
        return memo[k.ordinal()];
    }

    /** RangedWeapons.rate for a stack, with what a rate can't see: Infinity saves arrows (worth 2% of the rate: a near
     *  tie goes to it, the long run being better), and more durability left wears out later (up to 0.1%). */
    static double weaponRate(ItemStack st, double dist, double armour, double tough, boolean impalable) {
        double r = RangedWeapons.rate(arms(st), dist, armour, tough, impalable);
        double tie = (Enchant.level(st, Enchantments.INFINITY) > 0 ? 0.02 : 0)
            + 0.001 * Math.min(1.0, (st.getMaxDamage() - st.getDamageValue()) / 500.0);
        return r * (1.0 + tie);
    }

    /** Your slot back, if the bow aura changed it and it is still on the weapon it chose. */
    private static void restore(LocalPlayer p) {
        if (restoreSlot >= 0 && weaponSlot >= 0 && Compat.selectedSlot(p.getInventory()) == weaponSlot && !ownUse) {
            select(p, restoreSlot);
            lastSlot = restoreSlot;
        }
        if (!ownUse) { restoreSlot = -1; weaponSlot = -1; }
    }

    /** A number key: the game sends the slot change itself before the next use or attack, or at the next tick. */
    private static void select(LocalPlayer p, int slot) {
        Compat.setSelectedSlot(p.getInventory(), slot);
    }

    /** Whether the server's last word on the player says it is using an item (the synced flag). */
    static boolean serverUsing(LocalPlayer p) {
        return (p.getEntityData().get(ai.rrw.kine.mixin.LivingFlagsAccessor.kine$livingFlags()) & 1) != 0;
    }

    // ---------------------------------------------------------------- conditions

    /** On foot: as the kill aura's turn can stay away from the view only then. */
    static boolean onFoot(LocalPlayer p) {
        return !p.isFallFlying() && !p.isInWater() && !p.isInLava() && !p.isPassenger() && !p.onClimbable()
            && !p.getAbilities().flying && Compat.fallDistance(p) < 2.0;
    }

    /** Your own attack or use, read from the mouse or keyboard itself (the use key may be held by this feature). */
    private static boolean yourClick(Minecraft mc) {
        return physical(mc, mc.options.keyAttack) || physical(mc, mc.options.keyUse);
    }

    /** Clicks of yours the game hasn't taken yet (or the kill aura holds back). */
    private static boolean clicksPending(Minecraft mc) {
        return ((KeyMappingAccessor) mc.options.keyAttack).kine$getClickCount() > 0
            || ((KeyMappingAccessor) mc.options.keyUse).kine$getClickCount() > 0;
    }

    private static boolean physical(Minecraft mc, KeyMapping km) {
        return Compat.held(mc, km);
    }

    /** The round trip in ticks: the ping (a lockstep test sets kine.pingMs, its injected lag being invisible to the game). */
    static double pingTicks(Minecraft mc, LocalPlayer p) {
        String prop = System.getProperty("kine.pingMs");
        double ms = 0;
        if (prop != null) {
            try { ms = Double.parseDouble(prop); } catch (NumberFormatException ignored) { }
        } else if (mc.getConnection() != null && mc.getConnection().getPlayerInfo(p.getUUID()) != null) {
            ms = mc.getConnection().getPlayerInfo(p.getUUID()).getLatency();
        }
        return Math.max(0.0, ms / 50.0);
    }

    private static void reset() {
        targetUp = 0.0;
        holding = false; ownUse = false; releasing = false; cancelHold = false;
        scrollBack = -1; restoreSlot = -1; weaponSlot = -1; lastSlot = -1;
        fetchFrom = -1; fetchStack = ItemStack.EMPTY; fetchedTo = -1;
        targetId = -1; readySince = -1; idleSince = -1; want = null; tridentOut = -1;
        snubbed.clear();
        inFlight.clear();
    }
}
