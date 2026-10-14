package ai.rrw.kine.combat;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.autoflight.Takeoff;
import ai.rrw.kine.combat.Ballistics.Spec;
import ai.rrw.kine.fight.CombatMovement;
import ai.rrw.kine.mixin.KeyMappingAccessor;
import ai.rrw.kine.mixin.MinecraftInvoker;
import ai.rrw.kine.util.Enchant;
import ai.rrw.kine.util.GameModes;
import ai.rrw.kine.util.Hotbar;
import ai.rrw.kine.util.InventoryClicks;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bow aura (RW 2026-10-14): shoots hostile mobs with a bow, its own setting beside the melee kill aura.
 *
 * <p>Melee first (RW: "when melee kill aura is active bow stands down, the inverse is not true"). The melee aura is
 * active when it is on and one of its targets is in reach with a clear line from your eye, or will be before the bow's
 * shot is off: within reach and half a block, or closing on you fast enough to be in reach within the ticks the draw
 * still needs and two more for the release (KillAura.meleeTicks). Then the bow takes no shot: a draw under way is loosed
 * at once if the shot as aimed would still hit (its chance at the power reached so far), else cancelled by a scroll of
 * the hotbar and back, and your slot comes back so the kill aura (and auto weapon) can swing from the next tick. A
 * vanilla client can't attack in the tick it lets go of a bow, so that one tick is the most the bow ever costs the melee
 * aura, and only when a mob comes on faster than it was coming.
 *
 * <p>It also stands down (RW's rulings, 2026-10-14) while the pathfinder walks you, while combat movement is in a fight
 * it moves you in (it fights at melee range, so the melee aura does the work there), and in elytra flight (gliding, a
 * rocket takeoff, the autopilot), and whenever the melee aura would: off your feet (water, ladders, riding, a fall),
 * with a screen open, while you click attack or use or hold them or use an item yourself, for a second after your last
 * click, and while auto eat eats. It takes up again as soon as none of that holds.
 *
 * <p>How it shoots, the way a player does. The bow: the one in your hand, else the best in the hotbar (a number key),
 * else one from the inventory moved into the hotbar (an empty slot, else in place of the largest stack, RW's rule;
 * through an inventory opening like auto weapon's), the highest Power first, never one about to break, and only with
 * arrows to shoot. Your slot comes back when it is done. The aim: the rotation the server is sent is the kill aura's own,
 * turned at most 30 degrees a tick in whole mouse counts and undone before each frame, so your view stays put; while
 * drawing it is turned toward the full-power shot that a release next tick would fire (the release carries no rotation:
 * the server shoots along the last move packet's), with Ballistics' solve and lead and AimAdvisor's best point on the
 * mob. The draw starts by holding the use key in the game's own key handling, only when the crosshair at the rotation
 * sent rests on nothing or on a hostile (so no block, door, chest, villager or pet is used instead), and the release is
 * letting go of it: so the use packet carries the tick's rotation and both come from the game. It looses once the draw
 * is full (20 ticks: full power, a crit) and the shot as sent last tick hits at least MIN_CHANCE of the time (HitChance:
 * the game's spread, blocks counted), and nothing that isn't a target (a pet, a villager, a player, an animal) stands
 * near the arrow's path before the mob.
 *
 * <p>Which mob: BowTargets' policy over the mobs it could hit (hostiles, and neutral mobs that hit you, as the kill aura
 * picks them; none a projectile can't hurt, NoDamage; never your pets), within RANGE blocks, in sight and in reach of a
 * full-power shot, with a hit chance of at least BowTargets.VIABLE.
 */
public final class BowAura {
    private BowAura() {}

    /** Farthest mob shot at, eye to box centre (blocks). */
    public static final double RANGE = 48.0;
    /** A shot goes only when the arrow as sent hits at least this often. */
    public static final double MIN_CHANCE = 0.5;
    /** Ticks of draw for full power (and a crit): BowItem.getPowerForTime reaches 1 at 20. */
    public static final int FULL_DRAW = BowItem.MAX_DRAW_DURATION;
    /** After your own attack or use click, ticks before the bow aura takes your hand (switches your slot or draws). */
    static final int QUIET = 20;
    /** Ticks with nothing to shoot before the slot you had comes back, and before a draw with no target is cancelled. */
    static final int IDLE_RESTORE = 20, IDLE_CANCEL = 10;
    /** Ticks between choices of target. */
    static final int RECHOOSE = 4;
    /** Ticks at full draw without a shot good enough before the mob is left for SNUB_TICKS and another chosen. */
    static final int GIVE_UP = 40, SNUB_TICKS = 60;
    /** Ticks at full draw after which a shot at BowTargets.VIABLE goes rather than MIN_CHANCE. */
    static final int PATIENCE = 20;
    /** A mob within the melee reach and this much more is the melee aura's (KillAura.meleeTicks). */
    public static final double REACH_MARGIN = 0.5;
    /** Ticks the client draws other entities behind the server (ProjectileTargeting.INTERP_TICKS). */
    static final double INTERP_TICKS = 2.0;
    /** Spread rolls for the chance that ranks targets, and for the one that lets the arrow go. */
    static final int CHOOSE_ROLLS = 256, RELEASE_ROLLS = 1024;
    /** A bystander (not a target) this far from the arrow's path, beyond the spread's reach, keeps the bow from loosing. */
    static final double BYSTANDER_PAD = 0.3;
    /** The policy unless -Dkine.bowTarget names another (the target-choice study switches it at run time). */
    public static final BowTargets.Policy DEFAULT_POLICY = BowTargets.Policy.THREAT;

    private static final Spec FULL = new Spec(0.05, 0.99F, 3.0, 0.0, false, true);   // an arrow at full power

    // ---------------------------------------------------------------- state

    private static int tick = 0;
    private static boolean holding = false;     // we hold the use key down
    private static boolean ownUse = false;      // the use in progress is ours (we pressed, and it started)
    private static int pressTick = -100;        // when we pressed it
    private static boolean releasing = false;   // we let go this tick (the release packet goes in this tick's keybinds)
    private static boolean cancelHold = false;  // a cancel's slot change: the key stays down this tick so nothing fires
    private static int scrollBack = -1;         // after a cancel by scrolling away: the slot to scroll back to next tick
    private static int restoreSlot = -1;        // the slot you had before the bow aura selected a bow
    private static int bowSlot = -1;            // the slot it selected
    private static int lastSlot = -1;           // the selected slot as it left it, to see a change made by someone else
    private static int fetchFrom = -1;          // a bow to move up from the inventory at the open tick's end
    private static ItemStack fetchStack = ItemStack.EMPTY;
    private static int fetchedTo = -1;          // where it went: selected the tick after
    private static int targetId = -1;
    private static double targetUp = 0.0;       // the height above its box centre the choice aimed at (its head when half hidden)
    private static int lastChoose = -100;
    private static int fullSince = -1;          // the tick the draw reached full power
    private static int idleSince = -1;          // since when there has been nothing to shoot
    private static int lastClick = -1000;       // your last attack or use click
    private static boolean blocked = true;      // a stand-down this tick (set by guard)
    private static float[] want = null;         // the rotation it asked the kill aura for this tick
    private static final Map<Integer, Integer> snubbed = new HashMap<>();   // entity id -> tick it may be chosen again
    private static LocalPlayer lastPlayer = null;

    // read by tests and the reticle
    public static String standDown = "";        // why it stands down this tick, "" when it doesn't
    public static int shots = 0, earlyShots = 0, cancels = 0, meleeStandDowns = 0, fetches = 0;
    public static long planNanos = 0, maxPlanNanos = 0, planTicks = 0;
    public static double lastReleaseChance = Double.NaN;
    /** Ticks the melee aura had a target in reach while the bow aura's use was in progress (it should stay 0 but for
     *  the one tick a mob may come on unforeseen). */
    public static int meleeBlockedTicks = 0;

    /** Whether it holds the use key (the kill aura lets it through while the rotation sent is turned). */
    public static boolean holdingUse() { return holding; }

    /** Whether the item use in progress is the bow aura's draw, not yours. */
    public static boolean ownsUse(LocalPlayer p) { return ownUse && p.isUsingItem(); }

    /** Busy with a draw, a fetch, or a cancel: auto eat waits. */
    public static boolean busy() { return holding || ownUse || fetchFrom >= 0 || fetchedTo >= 0 || scrollBack >= 0; }

    /** The mob it is aiming at, or -1. */
    public static int targetId() { return ownUse || holding ? targetId : -1; }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BowAura::endTick);
    }

    private static BowTargets.Policy policy() {
        return BowTargets.Policy.parse(System.getProperty("kine.bowTarget"), DEFAULT_POLICY);
    }

    // ---------------------------------------------------------------- the tick: guard, plan, act

    /**
     * First in the tick's actions (VanillaActions, before the inventory's open tick and before anything that returns
     * early): notes what happened since the last tick, and lets go of the bow when something other than the melee aura
     * makes it stand down. Sets {@link #blocked} for plan and act.
     */
    public static void guard(Minecraft mc) {
        tick++;
        want = null;
        releasing = false;
        LocalPlayer p = mc.player;
        if (p == null || p != lastPlayer) { reset(); lastPlayer = p; blocked = true; standDown = "no player"; return; }

        // the use: it started after our press, or ended (our release, a cancel, an inventory open's release of every key)
        if (holding && p.isUsingItem() && p.getUseItem().is(Items.BOW)) ownUse = true;
        if (ownUse && !p.isUsingItem()) { ownUse = false; fullSince = -1; }
        if (holding && !p.isUsingItem() && tick - pressTick >= 1) letGo(mc);   // no draw started, or it ended
        cancelHold = false;
        if (scrollBack >= 0) {   // the second half of a cancel's scroll
            if (p.getInventory().getSelectedSlot() == lastSlot) { select(p, scrollBack); lastSlot = scrollBack; }
            scrollBack = -1;
        }
        Inventory inv = p.getInventory();
        if (lastSlot >= 0 && inv.getSelectedSlot() != lastSlot) { restoreSlot = -1; bowSlot = -1; }   // someone else picked a slot
        if (yourClick(mc)) lastClick = tick;

        String why = hardStandDown(mc, p);
        boolean meleeFirst = false, mayLoose = true;
        if (why == null && Settings.killAura) {
            // melee first: a target in reach, or one that will be before this shot is off (or the draw it would start)
            double meleeTicks = KillAura.meleeTicks(mc, p);
            if (ownUse && meleeTicks <= 0) meleeBlockedTicks++;
            if (meleeFirst(meleeTicks, ownUse ? p.getTicksUsingItem() : 0)) {
                why = "the kill aura fighting";
                meleeFirst = true;
                mayLoose = mayLoose(meleeTicks);
            }
        }
        standDown = why == null ? "" : why;
        blocked = why != null;
        if (blocked) {
            if (meleeFirst && (ownUse || holding)) meleeStandDowns++;
            if (ownUse) endDraw(mc, p, why, mayLoose);
            else if (holding && !cancelHold) letGo(mc);
            restore(p);
            targetId = -1;
            fetchFrom = -1;
        }
        lastSlot = inv.getSelectedSlot();
    }

    /** Whether the melee aura goes first: it has a target in reach, or will have one before the shot under way (or the
     *  draw about to start) is off: the ticks the draw still needs to full power, and two more for the release to go
     *  out and the slot to come back. */
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
        if (mc.screen != null || mc.getOverlay() != null) return "a screen open";
        if (ai.rrw.kine.walk.Walk.active()) return "the pathfinder walking";
        if (p.isFallFlying() || Takeoff.active() || Autopilot.isEngaged()) return "elytra flight";
        if (!onFoot(p)) return "not on foot";
        if (CombatMovement.engagedFight(mc)) return "combat movement fighting";
        if (p.isUsingItem() && !ownUse && !holding) return "an item in use";
        if (AutoEat.busy() && !ownUse) return "auto eat eating";
        if (yourClick(mc) || clicksPending(mc) || KillAura.clicksHeld()) return "you clicking";
        if (tick - lastClick < QUIET && !ownUse) return "you clicked lately";
        return null;
    }

    /**
     * Where the rotation sent should go for the bow this tick, or null for none (KillAura.beforeKeybinds, when it isn't
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
        int used = ownUse ? p.getTicksUsingItem() : 0;
        if (fetchFrom >= 0 || fetchedTo >= 0) return null;   // a bow on its way up from the inventory

        Vec3 motion = new Vec3(p.getX() - p.xOld, p.getY() - p.yOld, p.getZ() - p.zOld);
        double ping = pingTicks(mc, p);
        Holder<DamageType> arrow = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.ARROW);

        // the release: the draw is full and the shot the server would fire now, along the rotation sent last tick from
        // where the last move packet put you, hits often enough with nothing else in its way
        if (ownUse && used >= FULL_DRAW) {
            if (fullSince < 0) fullSince = tick;
            Entity t = level.getEntity(targetId);
            if (t instanceof LivingEntity le && le.isAlive()) {
                double c = releaseChance(mc, p, le, used, motion, ping);
                // a shot that waiting doesn't make likelier (a mob moving across, partly hidden): after a second at full
                // draw, any shot good enough to have chosen it goes
                double need = tick - fullSince >= PATIENCE ? BowTargets.VIABLE : MIN_CHANCE;
                if (c >= need) {
                    releasing = true;
                    lastReleaseChance = c;
                    shots++;
                    Kine.LOGGER.info("Kine bow aura: shot at {} {} ({}% chance, drawn {} ticks)", le.getType().toShortString(),
                                     le.getId(), Math.round(c * 100), used);
                    targetId = -1;   // choose afresh for the next draw
                    lastChoose = -100;
                }
            }
            if (!releasing && tick - fullSince > GIVE_UP && targetId >= 0) {   // no good shot at it for 2 s: try another
                snubbed.put(targetId, tick + SNUB_TICKS);
                targetId = -1;
            }
        }

        // the target, chosen every few ticks (at once when there is none or it has gone)
        Vec3 eye = p.getEyePosition();
        Vec3 spawn = Ballistics.spawnPoint(eye, motion, true);   // where a release next tick shoots from
        double lead = INTERP_TICKS + Ballistics.ticksToShot(true) + ping;
        Entity cur = targetId >= 0 ? level.getEntity(targetId) : null;
        boolean curOk = cur instanceof LivingEntity cl && cl.isAlive() && !cl.isRemoved();
        if (!curOk || tick - lastChoose >= RECHOOSE) {
            lastChoose = tick;
            targetId = choose(mc, p, level, eye, spawn, motion, lead, arrow);
            cur = targetId >= 0 ? level.getEntity(targetId) : null;
        }
        if (!(cur instanceof LivingEntity target)) {
            if (idleSince < 0) idleSince = tick;
            if (ownUse && !releasing && tick - idleSince >= IDLE_CANCEL) endDraw(mc, p, "nothing to shoot", false);
            if (!ownUse && tick - idleSince >= IDLE_RESTORE) restore(p);
            return null;
        }
        idleSince = -1;

        // a bow in hand, before the draw
        if (!ownUse && !holding && !bowReady(mc, p)) return null;

        // the aim for a release next tick, at the best point on the mob
        Ballistics.Course course = ProjectileTargeting.courseOf(level, target);
        Vec3 best = AimAdvisor.aimOffset(target, spawn, level.getGameTime());
        if (best.equals(Vec3.ZERO)) best = new Vec3(0, targetUp, 0);   // no search result for it yet: the choice's point
        Vec3 center = target.getBoundingBox().getCenter().add(course.at(lead)).add(best);
        Ballistics.Solution sol = Ballistics.solve(spawn, center, course, lead, FULL, motion, p.onGround());
        if (ownUse) {   // the best point and the chance of the shot as sent, on the advisor's workers
            float sy = KillAura.lastSentYaw(p), sp = KillAura.lastSentPitch(p);
            AimAdvisor.update(level, spawn, Ballistics.launchDir(sy, sp, FULL), FULL, FULL, motion, p.onGround(), target,
                              course, true, lead);
        }
        float yaw = viewYaw + Mth.wrapDegrees((float) sol.yaw() - viewYaw);
        want = new float[]{yaw, Mth.clamp((float) sol.pitch(), -90f, 90f)};
        return want;
    }

    /**
     * After the kill aura's turn and pick (VanillaActions): the use key for this tick. Pressed to start a draw (the
     * game's keybind step then starts it with this tick's rotation), held while drawing, let go to loose.
     */
    public static void act(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || blocked) return;
        KeyMapping use = mc.options.keyUse;
        if (releasing) { use.setDown(false); holding = false; return; }
        if (ownUse || cancelHold) { use.setDown(true); holding = true; return; }
        if (want == null || holding) return;
        // start a draw: a bow in the main hand with arrows, the use free, no meal under way, and the crosshair at the
        // rotation sent on nothing or a hostile (the use would go to a block, a villager or a pet instead)
        ItemStack main = p.getMainHandItem();
        if (!usable(p, main)) return;
        if (((MinecraftInvoker) mc).kine$getRightClickDelay() > 0 || AutoEat.busy() || InventoryClicks.isOpenTick()) return;
        // the server's word that the last use has ended must have come first: arriving after a new draw has started, it
        // would stop that draw on the client (LocalPlayer.onSyncedDataUpdated) and the server's start would then restart
        // it out of step (measured in the first lockstep run: every draw right after a release was lost so)
        if (serverUsing(p)) return;
        HitResult h = mc.hitResult;
        boolean clear = h == null || h.getType() == HitResult.Type.MISS
            || h instanceof EntityHitResult eh && KillAura.targets(eh.getEntity(), p);
        if (!clear) return;
        use.setDown(true);
        holding = true;
        pressTick = tick;
    }

    /** End of the tick: the inventory's open tick plays out a fetch (the swap, then the close). */
    private static void endTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) { fetchFrom = -1; fetchedTo = -1; return; }
        if (fetchedTo >= 0 && fetchFrom < 0) {   // the tick after the swap: select it
            if (p.getInventory().getNonEquipmentItems().get(fetchedTo).is(Items.BOW)) {
                if (restoreSlot < 0) restoreSlot = p.getInventory().getSelectedSlot();
                select(p, fetchedTo);
                bowSlot = fetchedTo;
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
            int to = Hotbar.slotToDisplace(p.getInventory().getNonEquipmentItems()::get);
            mc.gameMode.handleContainerInput(menu.containerId, from, to, ContainerInput.SWAP, p);   // number-key swap
            fetchedTo = to;
            fetches++;
        }
        InventoryClicks.closeAsVanilla(mc, p);
    }

    // ---------------------------------------------------------------- the choice

    /** The mob to shoot (an entity id), or -1: BowTargets' policy over the mobs it could hit. */
    private static int choose(Minecraft mc, LocalPlayer p, ClientLevel level, Vec3 eye, Vec3 spawn, Vec3 motion,
                              double lead, Holder<DamageType> arrow) {
        snubbed.values().removeIf(until -> until <= tick);
        double reach = p.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        boolean melee = Settings.killAura;
        List<BowTargets.Cand> cands = new ArrayList<>();
        List<Double> ups = new ArrayList<>();
        int power = bestPower(p);
        List<Entity> others = bystanders(mc, p);
        boolean debug = Boolean.getBoolean("kine.bowDebug");
        StringBuilder dbg = debug ? new StringBuilder("Kine bow aura choice t=" + tick + ":") : null;
        for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(RANGE), x -> x instanceof LivingEntity)) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || snubbed.containsKey(e.getId())) continue;
            if (!KillAura.targets(e, p) || ProjectileTargeting.ownPet(p, e)) continue;
            Vec3 c = e.getBoundingBox().getCenter();
            double dist = c.distanceTo(eye);
            String why = dist > RANGE || dist < 1e-3 ? "range" : NoDamage.reason(e, spawn, arrow, false) != null ? "no damage"
                : melee && KillAura.eyeToBox(eye, e.getBoundingBox()) <= reach + REACH_MARGIN ? "melee's"
                : !ProjectileTargeting.visible(level, p, eye, e) ? "hidden" : null;
            if (debug && why != null) dbg.append(String.format(java.util.Locale.ROOT, " %s#%d %.1f %s;", e.getType().toShortString(), e.getId(), dist, why));
            if (why != null) continue;
            Ballistics.Course course = ProjectileTargeting.courseOf(level, e);
            Vec3 center = c.add(course.at(lead));
            Ballistics.Solution sol = Ballistics.solve(spawn, center, course, lead, FULL, motion, p.onGround());
            if (Math.abs(sol.miss()) > 0.5) {   // out of the bow's reach
                if (debug) dbg.append(String.format(java.util.Locale.ROOT, " %s#%d %.1f miss %.2f;", e.getType().toShortString(), e.getId(), dist, sol.miss()));
                continue;
            }
            double chance = AimAdvisor.chanceNow(level, spawn, Ballistics.launchDir(sol.yaw(), sol.pitch(), FULL), FULL,
                                                 motion, p.onGround(), e, course, lead, CHOOSE_ROLLS);
            if (Double.isNaN(chance)) {
                if (debug) dbg.append(String.format(java.util.Locale.ROOT, " %s#%d %.1f no chance;", e.getType().toShortString(), e.getId(), dist));
                continue;
            }
            double up = 0.0;
            if (chance < MIN_CHANCE) {
                // half hidden (a low wall, a slab, a fence): its head may show over it. AimAdvisor's search finds the best
                // point once a draw is aimed at it; until then, the head when that is the better shot
                double head = e.getEyeY() - c.y;
                Ballistics.Solution hs = Ballistics.solve(spawn, center.add(0, head, 0), course, lead, FULL, motion, p.onGround());
                double hc = Math.abs(hs.miss()) > 0.5 ? 0.0 : AimAdvisor.chanceNow(level, spawn,
                    Ballistics.launchDir(hs.yaw(), hs.pitch(), FULL), FULL, motion, p.onGround(), e, course, lead, CHOOSE_ROLLS);
                if (hc > chance) { chance = hc; up = head; sol = hs; }
            }
            // a pet, a villager or anything else that isn't a target near the path: no shot from here
            if (chance > 0 && !clearOf(others, le, spawn, Ballistics.launchVel(sol.yaw(), sol.pitch(), FULL, motion, p.onGround()), FULL, lead))
                chance = 0.0;
            double speedAt = impactSpeed(spawn, sol, center);
            double dmg = BowTargets.arrowDamage(speedAt, power, le.getArmorValue(), le.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
            BowTargets.Cand cand = new BowTargets.Cand(e.getId(), kindOf(le), dist, le.getHealth() + le.getAbsorptionAmount(),
                chance, dps(le), attackRange(le), true, arriveTicks(p, le, reach), dmg);
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

    /** The arrow's speed (blocks a tick) where the full-power shot reaches the mob: drag 0.99 a tick over the flight. */
    private static double impactSpeed(Vec3 spawn, Ballistics.Solution sol, Vec3 center) {
        Vec3 v = Ballistics.launchVel(sol.yaw(), sol.pitch(), FULL, Vec3.ZERO, true);
        double horiz = Math.hypot(center.x - spawn.x, center.z - spawn.z), h = 0;
        for (int i = 0; i < 400 && h < horiz; i++) {
            h += Math.hypot(v.x, v.z);
            v = new Vec3(v.x * 0.99F, (v.y) * 0.99F - 0.05, v.z * 0.99F);
        }
        return v.length();
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

    /** The chance the arrow a release now fires hits: along the rotation sent last tick, from where the last move
     *  packet put you, at the draw's power; 0 if something that isn't a target stands near its path first. */
    private static double releaseChance(Minecraft mc, LocalPlayer p, LivingEntity t, int used, Vec3 motion, double ping) {
        float pow = BowItem.getPowerForTime(used);
        Spec spec = new Spec(0.05, 0.99F, pow * 3.0, 0.0, false, true);
        float yaw = KillAura.lastSentYaw(p), pitch = KillAura.lastSentPitch(p);
        Vec3 spawn = Ballistics.spawnPoint(p.getEyePosition(), motion, false);   // the server has the last move packet's place
        double lead = INTERP_TICKS + 1 + ping;
        Ballistics.Course course = ProjectileTargeting.courseOf(mc.level, t);
        Vec3 dir = Ballistics.launchDir(yaw, pitch, spec);
        double c = AimAdvisor.chanceNow(mc.level, spawn, dir, spec, motion, p.onGround(), t, course, lead, RELEASE_ROLLS);
        if (Double.isNaN(c) || c <= 0) return 0.0;
        return clearOfBystanders(mc, p, t, spawn, Ballistics.launchVel(yaw, pitch, spec, motion, p.onGround()), spec, lead) ? c : 0.0;
    }

    /**
     * Whether the arrow's path (without spread) up to the mob passes clear of everything a projectile can hit that
     * isn't a target: your pets, villagers, players, animals, armour stands. Each is met where it will be along its
     * motion, its box grown by how far the spread can carry the arrow by then (the triangle's full width on both axes)
     * and BYSTANDER_PAD. Another hostile in the way is fine.
     */
    private static boolean clearOfBystanders(Minecraft mc, LocalPlayer p, LivingEntity t, Vec3 spawn, Vec3 vel, Spec spec, double lead) {
        return clearOf(bystanders(mc, p), t, spawn, vel, spec, lead);
    }

    /** Everything a projectile can hit within reach that isn't a target (nor your mount or those riding with you). */
    private static List<Entity> bystanders(Minecraft mc, LocalPlayer p) {
        double reachSq = (RANGE + 8) * (RANGE + 8);
        List<Entity> others = new ArrayList<>();
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(RANGE + 8), Entity::canBeHitByProjectile))
            if (!KillAura.targets(e, p) && e.distanceToSqr(p) <= reachSq && !p.isPassengerOfSameVehicle(e)) others.add(e);
        return others;
    }

    private static boolean clearOf(List<Entity> others, LivingEntity t, Vec3 spawn, Vec3 vel, Spec spec, double lead) {
        if (others.isEmpty()) return true;
        double targetDist = t.getBoundingBox().getCenter().distanceTo(spawn);
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
            return from.distanceTo(spawn) > targetDist + 1.0 ? new Ballistics.Hit<>(to, null, null) : null;
        }, 200);
        return !blocked[0];
    }

    /** Let go of the bow: loose if allowed and the shot as aimed would still hit, else cancel by scrolling the hotbar
     *  away. */
    private static void endDraw(Minecraft mc, LocalPlayer p, String why, boolean mayLoose) {
        if (!ownUse) { if (holding && !cancelHold) letGo(mc); return; }
        int used = p.getTicksUsingItem();
        Entity t = targetId >= 0 && mc.level != null ? mc.level.getEntity(targetId) : null;
        double c = 0.0;
        if (mayLoose && t instanceof LivingEntity le && le.isAlive() && BowItem.getPowerForTime(used) >= 0.1F && mc.level != null) {
            Vec3 motion = new Vec3(p.getX() - p.xOld, p.getY() - p.yOld, p.getZ() - p.zOld);
            c = releaseChance(mc, p, le, used, motion, pingTicks(mc, p));
        }
        if (c >= MIN_CHANCE) {
            releasing = true;
            mc.options.keyUse.setDown(false);
            holding = false;
            shots++;
            if (used < FULL_DRAW) earlyShots++;
            lastReleaseChance = c;
            Kine.LOGGER.info("Kine bow aura: shot early at {} ({}% chance, drawn {} ticks): {}", t.getType().toShortString(),
                             Math.round(c * 100), used, why);
            return;
        }
        // a vanilla player cancels a draw by scrolling to another slot: the server stops the use on the slot change. The
        // key stays down this tick so the keybind step doesn't loose the arrow first; let go once the use has ended.
        Inventory inv = p.getInventory();
        int now = inv.getSelectedSlot();
        int to = restoreSlot >= 0 && restoreSlot != now ? restoreSlot : (now + 1) % Inventory.SELECTION_SIZE;
        if (to != restoreSlot) scrollBack = now;   // your own bow: scroll back to it next tick
        else { restoreSlot = -1; bowSlot = -1; }
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

    // ---------------------------------------------------------------- the bow

    /** A bow in the main hand ready to shoot, else one selected from the hotbar or asked for from the inventory. */
    private static boolean bowReady(Minecraft mc, LocalPlayer p) {
        Inventory inv = p.getInventory();
        if (usable(p, p.getMainHandItem())) return true;
        if (tick - lastClick < QUIET) return false;   // your hand: not while you work with it
        int best = -1, bestPower = -1, bestLeft = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getNonEquipmentItems().get(i);
            if (!usable(p, st)) continue;
            int pw = Enchant.level(st, Enchantments.POWER), left = st.getMaxDamage() - st.getDamageValue();
            boolean better = best < 0 || (best >= Inventory.SELECTION_SIZE && i < Inventory.SELECTION_SIZE)
                || (best < Inventory.SELECTION_SIZE) == (i < Inventory.SELECTION_SIZE) && (pw > bestPower || pw == bestPower && left > bestLeft);
            if (better) { best = i; bestPower = pw; bestLeft = left; }
        }
        if (best < 0) return false;
        if (best < Inventory.SELECTION_SIZE) {
            if (restoreSlot < 0) restoreSlot = inv.getSelectedSlot();
            select(p, best);
            bowSlot = best;
            lastSlot = best;
            return true;
        }
        if (InventoryClicks.available(mc, p) && !InventoryClicks.isOpenTick() && !AutoEat.busy()) {
            fetchFrom = best;
            fetchStack = inv.getNonEquipmentItems().get(best);
            InventoryClicks.requestOpen();
        }
        return false;
    }

    /** Whether the server's last word on the player says it is using an item (the synced flag). */
    static boolean serverUsing(LocalPlayer p) {
        return (p.getEntityData().get(ai.rrw.kine.mixin.LivingFlagsAccessor.kine$livingFlags()) & 1) != 0;
    }

    /** A bow that can shoot now: arrows to hand (or infinite materials), and not about to break. */
    static boolean usable(LocalPlayer p, ItemStack st) {
        if (!st.is(Items.BOW) || st.nextDamageWillBreak()) return false;
        return p.hasInfiniteMaterials() || !p.getProjectile(st).isEmpty();
    }

    private static int bestPower(LocalPlayer p) {
        ItemStack main = p.getMainHandItem();
        return main.is(Items.BOW) ? Enchant.level(main, Enchantments.POWER) : 0;
    }

    /** Your slot back, if the bow aura changed it and it is still on the bow it chose. */
    private static void restore(LocalPlayer p) {
        if (restoreSlot >= 0 && bowSlot >= 0 && p.getInventory().getSelectedSlot() == bowSlot && !ownUse) {
            select(p, restoreSlot);
            lastSlot = restoreSlot;
        }
        if (!ownUse) { restoreSlot = -1; bowSlot = -1; }
    }

    /** A number key: the game sends the slot change itself before the next use or attack, or at the next tick. */
    private static void select(LocalPlayer p, int slot) {
        p.getInventory().setSelectedSlot(slot);
    }

    // ---------------------------------------------------------------- conditions

    /** On foot: as the kill aura's turn can stay away from the view only then. */
    static boolean onFoot(LocalPlayer p) {
        return !p.isFallFlying() && !p.isInWater() && !p.isInLava() && !p.isPassenger() && !p.onClimbable()
            && !p.getAbilities().flying && p.fallDistance < 2.0;
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
        InputConstants.Key key = ((KeyMappingAccessor) km).kine$getKey();
        if (key.getType() == InputConstants.Type.KEYSYM) return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
        if (key.getType() == InputConstants.Type.MOUSE)
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        return false;
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
        scrollBack = -1; restoreSlot = -1; bowSlot = -1; lastSlot = -1;
        fetchFrom = -1; fetchStack = ItemStack.EMPTY; fetchedTo = -1;
        targetId = -1; fullSince = -1; idleSince = -1; want = null;
        snubbed.clear();
    }
}
