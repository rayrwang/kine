package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.fireworks.FireworkFuel;
import ai.rrw.kine.fireworks.RocketBurn;
import ai.rrw.kine.fireworks.RocketHit;
import ai.rrw.kine.util.InventoryClicks;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudLayout;
import ai.rrw.kine.mixin.MinecraftInvoker;
import ai.rrw.kine.util.ElytraLife;
import ai.rrw.kine.util.MouseTurn;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Rocket takeoff from the ground to the autopilot's cruise. Standing with an elytra on and rockets in hand, the
 * warning row offers it in green (LEFT CLICK TO TAKE OFF), or says in red why it can't. A left click looks
 * straight up, jumps, opens the elytra on the third tick (jump, release, press, as a player opens it), fires a
 * rocket the next tick, climbs straight up until a leaning climb would clear the terrain, leans to 70 degrees,
 * and at the end of each rocket fires another unless the autopilot, taking over now in altitude hold, would hold
 * the cruise; then it engages the autopilot. The rockets counted are all those carried, and when the hand's stack runs
 * out mid-climb the hand is refilled from another (refill). {@link TakeoffModel} holds the physics and the rules.
 *
 * <p>The server sees only what a vanilla client sends: the jump through the keys ({@link #adjustInput}), turns by
 * whole mouse counts, and rockets used through the game's own startUseItem just before handleKeybinds, so each
 * use carries the rotation of its tick. Any mouse movement or movement key from the pilot stops the takeoff.
 */
public final class Takeoff {
    private Takeoff() {}

    /** What the warning row says while standing with rockets in hand. */
    public enum Status { NONE, READY, BLOCKED, ROCKETS, WORN, WORN_CRUISE, STARS, LAVA, AUTOPILOT }

    // SWIM: from water, swimming up until a bob at the surface leaves the water, then the elytra opened there
    private enum Phase { IDLE, LOOK_UP, JUMP, SWIM, CLIMB }

    private static final float LOOK_DPS = 240f;                                    // looking up from the ground, deg/s
    private static final float LEAN_DPS = (float) (TakeoffModel.PITCH_RATE * 20);  // leaning over, as the model leans
    private static final float MOUSE_EPS = 0.15f;      // per-tick rotation drift (deg) that counts as the pilot's mouse
    private static final float MAX_FRAME_DT = 0.25f;
    private static final int   USE_WAIT = 20;          // ticks to wait for a fired rocket to show before using again
    private static final int   EMPTY_TICKS = 2;        // ticks with no rocket pushing before the next is fired
    private static final int   MAX_FAILED_USES = 3;
    private static final double CRUISE_MIN_AGL = 60;   // the cruise is at least this far above the takeoff spot
    // Without terrain avoidance the autopilot arms only with 140 clear blocks below (FlightDirector's ENGAGE_AGL),
    // so the cruise is at least that far up, plus a margin for ground that rises along the climb.
    private static final double CRUISE_MIN_AGL_NO_AVOIDANCE = 140 + 10;
    private static final int   PATH_TICKS = 421;       // rolled out for the rails (they draw 420 ticks)
    private static final int   PLAN_EVERY = 4;         // ticks between re-plans while standing still
    private static final int   STOP_MESSAGE_TICKS = 60;
    private static final int   ARM_WAIT = 20;          // ticks to wait at the cruise for the autopilot to arm
    private static final int   SWIM_TICKS = 400;       // give up a takeoff from water that hasn't opened the elytra by then

    private static Phase phase = Phase.IDLE;
    private static Status status = Status.NONE;
    private static int needed = 0;                     // rockets needed if each burns its shortest
    private static int climbTicks = 0;                 // the planned takeoff's ticks to the hand-over
    private static double cruiseY = 0;                 // the planned cruise's height (y)
    private static float yaw, cmdYaw, cmdPitch, setPitch, setYaw;
    private static long lastFrameNanos = 0L;
    private static int jumpTick, pending, failedUses, fired, stopTicks, armWait, emptyTicks;
    private static int swimTick, swimStep;   // SWIM: ticks so far; 0 swimming up, 1 letting go of jump, 2 pressing it again
    private static int sinceUse, shortestBurn, seenId = -1, seenTicks;   // the rocket last seen pushing, for the log
    private static boolean loggedDetached = false;
    private static boolean lean;
    private static boolean refilling = false;          // an inventory opening asked for, to bring rockets into the hand
    private static double groundY, cruise;

    // planning while standing: re-planned every PLAN_EVERY ticks (planX is NaN until the first plan)
    private static int planAge = 0;
    private static double planX = Double.NaN, planY, planZ;
    private static float planYaw;

    // A rocket used while already fast can hit you on the server and burst a few ticks into its burn, at some headings
    // for the arm holding it (fireworks/RocketHit; RW's takeoff spam of 2026-10-11, 30 and more rockets a climb), so
    // the climb leans along the nearest heading where it doesn't, at the pitches the climb fires at.
    private static final double[] CLIMB_PITCHES = { -85.0, -80.0, -75.0, TakeoffModel.CLIMB_PITCH, TakeoffModel.CLIMB_PITCH + 5.0 };
    private static float safeFor = Float.NaN, safeYaw;
    private static boolean safeRight;

    // the planned path for the rails, relative to the player
    private static final double[] px = new double[PATH_TICKS], py = new double[PATH_TICKS], pz = new double[PATH_TICKS];
    private static int pathCount = 0;

    public static boolean active() { return phase != Phase.IDLE; }
    /** What the warning row offers while standing. */
    public static Status status() { return status; }
    /** Rockets the takeoff from here needs if each burns its shortest. */
    public static int needed() { return needed; }
    /** Whether the takeoff has the warning row (its prompt, its status or its stop message). */
    /** The attitude the takeoff is flying the view to, for the flight director bars: straight up, then the lean. */
    public static float commandedPitch() { return phase == Phase.CLIMB && lean ? (float) TakeoffModel.CLIMB_PITCH : -90f; }
    public static float commandedYaw()   { return yaw; }

    public static boolean usesWarningRow() { return Settings.takeoff && (active() || stopTicks > 0 || status != Status.NONE); }
    /** The planned path for the rails, relative to the player: points 0 to pathCount() - 1, one per tick. */
    public static int pathCount() { return pathCount; }
    public static double pathX(int i) { return px[i]; }
    public static double pathY(int i) { return py[i]; }
    public static double pathZ(int i) { return pz[i]; }
    /** The heading the takeoff climbs along (yaw, degrees). */
    public static float heading() { return active() ? yaw : planYaw; }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Takeoff::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "takeoff"),
            Takeoff::render);
    }

    // ---- per tick, at the end ----

    private static void tick(Minecraft mc) {
        if (mc.isPaused()) return;   // singleplayer paused: the game's ticks still come, but the world stands still
        if (stopTicks > 0) stopTicks--;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { reset(); status = Status.NONE; return; }
        if (!Settings.takeoff) { if (active()) stop(); status = Status.NONE; pathCount = 0; return; }
        switch (phase) {
            case IDLE -> idle(mc, p);
            case LOOK_UP -> {
                if (pilotKeys(mc) || !p.onGround() && !p.isInWater() || Autopilot.isEngaged() || !holding(p)) { stop(); return; }
                if (TakeoffModel.lookedUp(p.getXRot(), MouseTurn.sens(mc, p) * 0.15)) {
                    if (p.isInWater()) { phase = Phase.SWIM; swimTick = 0; swimStep = 0; }
                    else { phase = Phase.JUMP; jumpTick = 0; }
                }
            }
            case SWIM -> swimTick(p);
            case JUMP -> {
                if (pilotKeys(mc)) { stop(); return; }
                if (++jumpTick >= 3) {
                    if (p.isFallFlying()) { phase = Phase.CLIMB; pending = 0; failedUses = 0; fired = 0; }
                    else stop();
                }
            }
            case CLIMB -> {
                climbTick(mc, p);
                if (phase == Phase.CLIMB) refill(mc, p);
            }
        }
    }

    private static void idle(Minecraft mc, LocalPlayer p) {
        status = check(mc, p);
        if (status == Status.NONE || status == Status.BLOCKED || status == Status.WORN || status == Status.STARS
            || status == Status.LAVA || status == Status.AUTOPILOT) {
            pathCount = 0; planX = Double.NaN;
            return;
        }
        // READY or not enough rockets: plan, at once the first time, then every PLAN_EVERY ticks (a plan is about 1 ms)
        if (Double.isNaN(planX) || ++planAge >= PLAN_EVERY) {
            planAge = 0;
            planX = p.getX(); planY = p.getY(); planZ = p.getZ(); planYaw = climbYaw(p);
            double target = cruiseFor(p.getY());
            int d = carriedDuration(p);
            Terrain2D terrain = TurnGuard.terrainOf(mc.level);
            double top = mc.level.getMaxY();
            TakeoffModel.Plan worst = TakeoffModel.fromGround(p.getX(), p.getY(), p.getZ(), planYaw, target,
                RocketBurn.minTicks(d), terrain, top, 1);
            needed = worst.reachesCruise() ? worst.rockets() : TakeoffModel.MAX_ROCKETS + 1;
            TakeoffModel.Plan mean = TakeoffModel.fromGround(p.getX(), p.getY(), p.getZ(), planYaw, target,
                (int) Math.round(RocketBurn.meanTicks(d)), terrain, top, PATH_TICKS);
            keepPath(mean, p);
            climbTicks = Math.max(worst.ticks(), mean.ticks());   // the slower of the two
            cruiseY = target;
        }
        if (needed <= TakeoffModel.MAX_ROCKETS && !elytraLasts(p, mc.level.getMinY())) { status = Status.WORN_CRUISE; pathCount = 0; }
        else if (carriedRockets(p) < needed) { status = Status.ROCKETS; pathCount = 0; }
    }

    // Whether END would still be at least a minute after the climb to the cruise (RW): END from the elytra carried,
    // with the landing reserve of the cruise height, less the planned climb's time. Only an elytra that wears out is
    // judged. Spares count when the failsafe is on, since it swaps them in; with it off, the worn wing alone, as before.
    private static boolean elytraLasts(LocalPlayer p, int minY) {
        Durability.Wing worn = ElytraLife.wing(p.getItemBySlot(EquipmentSlot.CHEST));
        if (worn == null || worn.unbreakable()) return true;
        List<Durability.Wing> spares = Settings.elytraDuraFailsafe ? ElytraLife.spares(p.inventoryMenu, null) : List.of();
        int reserve = Durability.reservePoints(LandingTime.seconds(cruiseY - minY));
        return TakeoffModel.elytraLasts(Durability.endurance(Durability.order(worn, spares), reserve), climbTicks);
    }

    /** What the row should say while standing or in water; NONE when nothing to offer (in the air, no elytra, no
     *  rocket). */
    private static Status check(Minecraft mc, LocalPlayer p) {
        if (!p.onGround() && !p.isInWater() || p.isPassenger() || p.isFallFlying()) return Status.NONE;
        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.has(DataComponents.GLIDER) || !holding(p)) return Status.NONE;
        if (!Settings.autopilot) return Status.AUTOPILOT;
        if (!LivingEntity.canGlideUsing(chest, EquipmentSlot.CHEST)) return Status.WORN;
        if (heldHasStars(p)) return Status.STARS;
        if (p.isInLava()) return Status.LAVA;
        if (blockedAbove(mc.level, p)) return Status.BLOCKED;
        return Status.READY;
    }

    private static double cruiseFor(double ground) {
        return cruiseFor(FlightDirector.targetAltitude(), ground, Settings.terrainAvoidance);
    }

    // The cruise the takeoff climbs to: the autopilot's, but at least CRUISE_MIN_AGL above the takeoff spot, or
    // CRUISE_MIN_AGL_NO_AVOIDANCE without terrain avoidance, so the autopilot can arm at the hand-over.
    static double cruiseFor(double target, double ground, boolean terrainAvoidance) {
        return Math.max(target, ground + (terrainAvoidance ? CRUISE_MIN_AGL : CRUISE_MIN_AGL_NO_AVOIDANCE));
    }

    // Anything that stops motion at head height or above, in any column the player is in. The MOTION_BLOCKING
    // heightmap also counts fluids, so where it is higher a column is scanned for a block that stops motion: water
    // above a swimmer is no ceiling (RW: "it is in fact possible to take off from water").
    private static boolean blockedAbove(ClientLevel level, LocalPlayer p) {
        AABB b = p.getBoundingBox();
        int feet = Mth.floor(p.getY());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(b.minX); x <= Mth.floor(b.maxX - 1.0e-7); x++)
            for (int z = Mth.floor(b.minZ); z <= Mth.floor(b.maxZ - 1.0e-7); z++) {
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                for (int y = feet + 1; y < top; y++)
                    if (level.getBlockState(pos.set(x, y, z)).blocksMotion()) return true;
            }
        return false;
    }

    // From water: hold jump to swim up; a bob at the surface leaves the water for about 4 ticks (measured in game,
    // 2026-10-11), and the elytra opens only on a fresh press in a tick that starts out of the water and off the ground
    // (Player.tryToStartFallFlying, LocalPlayer.aiStep). So once a tick ends out of the water, jump is let go for a
    // tick and pressed again; if the elytra didn't open, back to swimming for the next bob.
    private static void swimTick(LocalPlayer p) {
        if (pilotKeys(Minecraft.getInstance()) || Autopilot.isEngaged() || !holding(p) || ++swimTick > SWIM_TICKS) { stop(); return; }
        if (p.isFallFlying()) { phase = Phase.CLIMB; pending = 0; failedUses = 0; fired = 0; return; }
        if (swimStep == 2) swimStep = 0;                               // pressed and didn't open: try the next bob
        else if (swimStep == 1) swimStep = 2;                          // let go for a tick: press next
        else if (!p.isInWater() && !p.onGround()) swimStep = 1;        // out of the water: let go next
    }

    private static void climbTick(Minecraft mc, LocalPlayer p) {
        if (pilotKeys(mc) || p.onGround() || !p.isFallFlying() || Autopilot.isEngaged()) { stop(); return; }
        Vec3 v = p.getDeltaMovement();
        Terrain2D terrain = TurnGuard.terrainOf(mc.level);
        double top = mc.level.getMaxY();
        if (!lean && p.getY() - groundY > 2.0
            && TakeoffModel.leanClear(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.getXRot(), yaw, terrain, top)) lean = true;
        // the rest of the takeoff for the rails, the burning rocket taken at its mean
        FireworkRocketEntity r = attachedRocket(mc, p);
        int d = heldDuration(p);
        int burnLeft = r == null ? 0 : Math.max(1, (int) Math.round(RocketBurn.meanTicks(rocketDuration(r))) - r.tickCount);
        int untilPush = (r == null && pending > 0) ? 1 : -1;
        TakeoffModel.Flight f = new TakeoffModel.Flight(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.getXRot(), lean,
            groundY, burnLeft, untilPush);
        boolean fireNow = r == null && pending == 0;
        keepPath(TakeoffModel.fly(f, fireNow, yaw, cruise, (int) Math.round(RocketBurn.meanTicks(d)), terrain, top, PATH_TICKS), p);
    }

    private static void keepPath(TakeoffModel.Plan plan, LocalPlayer p) {
        int n = Math.min(plan.count(), PATH_TICKS);
        for (int i = 0; i < n; i++) {
            px[i] = plan.xs()[i] - p.getX(); py[i] = plan.ys()[i] - p.getY(); pz[i] = plan.zs()[i] - p.getZ();
        }
        pathCount = n;
    }

    // ---- just before handleKeybinds ----

    /** The click that starts it, and the rockets. Returns true while the takeoff has this tick, so no other
     *  feature attacks or uses an item in it. */
    public static boolean beforeKeybinds(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || !Settings.takeoff) return false;
        if (phase == Phase.IDLE) {
            if (status != Status.READY) return false;
            if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) return false;   // that click attacks
            if (!mc.options.keyAttack.consumeClick()) return false;
            while (mc.options.keyAttack.consumeClick()) { }
            mc.options.keyAttack.setDown(false);     // a held click would go on to break the block in view
            start(p);
            return true;
        }
        while (mc.options.keyAttack.consumeClick()) { }   // no swings or block breaking during the takeoff
        if (phase != Phase.CLIMB) return true;

        sinceUse++;
        FireworkRocketEntity burning = attachedRocket(mc, p);
        if (burning != null) {
            if (burning.getId() != seenId) { seenId = burning.getId(); seenTicks = 0; loggedDetached = false; }
            seenTicks++;
            pending = 0; failedUses = 0; emptyTicks = 0; return true;   // one is burning
        }
        if (seenId != -1) {
            // The rocket seen pushing no longer reads as attached. While it is still in the client's world it is
            // still burning (and boosting: the client applies the boost from its own copy), so it isn't doubled.
            Entity still = mc.level.getEntity(seenId);
            if (still instanceof FireworkRocketEntity && !still.isRemoved()) {
                if (!loggedDetached) Kine.LOGGER.info("Kine takeoff: rocket {} still in the world but not attached, {} ticks after its use", seenId, sinceUse);
                loggedDetached = true;
                emptyTicks = 0;
                return true;
            }
            // RW saw rockets 5 ticks apart in his game (2026-10-11 17:53): each seen, then gone. A rocket burns at least
            // 10 * (1 + its flight duration) ticks, so one gone sooner was removed by the server; logged to find out why.
            if (sinceUse < shortestBurn)
                Kine.LOGGER.info("Kine takeoff: rocket {} removed after {} ticks seen, {} ticks after its use (shortest burn {})",
                    seenId, seenTicks, sinceUse, shortestBurn);
            seenId = -1;
        }
        // none seen: wait a tick more before taking it as burnt out, so a rocket missed for one tick is never doubled
        if (++emptyTicks < EMPTY_TICKS && fired > 0) return true;
        if (pending > 0) {
            if (--pending > 0) return true;                        // still waiting for the one just used
            if (++failedUses >= MAX_FAILED_USES) { stop(); return true; }
        }
        // none burning or coming: hand over if the autopilot can hold the cruise from here
        if (fired > 0) {
            Vec3 v = p.getDeltaMovement();
            if (TakeoffModel.holdsCruise(p.getY(), v.x, v.y, v.z, p.getXRot(), yaw, cruise)) {
                if (FlightDirector.isActive()) {
                    FlightDirector.startHold();
                    handOver(p);
                } else if (++armWait > ARM_WAIT) {
                    stop();   // high enough, but the autopilot can't arm (no flyable path seen): the pilot flies
                }
                return true;  // no more rockets: they would only climb past the cruise
            }
        }
        if (carriedRockets(p) == 0 || fired >= TakeoffModel.MAX_ROCKETS) {
            // out of rockets: the autopilot climbs the rest of the way on its own, if it can arm
            if (FlightDirector.isActive()) handOver(p);
            else stop();
            return true;
        }
        if (heldRockets(p) == 0) return true;   // the hand is being refilled from another stack (refill), a tick or two
        if (!p.isFallFlying()) { stop(); return true; }   // used on the ground it would launch the rocket off a block
        ((MinecraftInvoker) mc).invokeStartUseItem();
        fired++;
        pending = USE_WAIT;
        sinceUse = 0; shortestBurn = 10 * (1 + heldDuration(p)); seenId = -1;
        Kine.LOGGER.info("Kine takeoff: rocket {} at y {} (rockets in hand {}, carried {}, failed uses {})",
            fired, String.format("%.1f", p.getY()), heldRockets(p), carriedRockets(p), failedUses);
        return true;
    }

    // ---- the keys, read in aiStep ----

    /** Jump, release, jump: the elytra opens on the second press, in the air. From water, jump held to swim up,
     *  let go for one tick once out of the water, then pressed again. */
    public static Input adjustInput(LocalPlayer p, Input keys) {
        if (phase == Phase.SWIM) return new Input(false, false, false, false, swimStep != 1, false, false);
        if (phase != Phase.JUMP) return keys;
        boolean jump = jumpTick != 1;
        return new Input(false, false, false, false, jump, false, false);
    }

    // ---- per frame, after the mouse ----

    /** Turn the view per frame, so it moves smoothly: up from the ground, then over into the leaning climb. */
    public static void onFrame(Minecraft mc) {
        if (phase == Phase.IDLE) return;
        if (mc.isPaused()) { lastFrameNanos = 0L; return; }
        LocalPlayer p = mc.player;
        if (p == null) return;
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            setPitch = p.getXRot(); setYaw = p.getYRot();
            return;
        }
        float dt = Math.min(MAX_FRAME_DT, (now - lastFrameNanos) / 1.0e9f);
        lastFrameNanos = now;
        if (dt <= 0f) return;
        float drift = MOUSE_EPS * 20f * dt;
        if (Math.abs(p.getXRot() - setPitch) > drift || Math.abs(Mth.wrapDegrees(p.getYRot() - setYaw)) > drift) {
            stop();   // the pilot moved the mouse
            return;
        }
        float target = -90f, rate = LOOK_DPS;
        if (phase == Phase.CLIMB && lean) { target = (float) TakeoffModel.CLIMB_PITCH; rate = LEAN_DPS; }
        cmdPitch += Mth.clamp(target - cmdPitch, -rate * dt, rate * dt);
        cmdYaw += Mth.clamp(Mth.wrapDegrees(yaw - cmdYaw), -LOOK_DPS * dt, LOOK_DPS * dt);   // onto the climb's heading
        MouseTurn.toward(p, cmdYaw, cmdPitch);
        setPitch = p.getXRot();
        setYaw = p.getYRot();
    }

    // ---- start, stop, hand over ----

    private static void start(LocalPlayer p) {
        phase = Phase.LOOK_UP;
        yaw = climbYaw(p);
        cmdYaw = p.getYRot();
        cmdPitch = setPitch = p.getXRot();
        setYaw = yaw;
        lastFrameNanos = 0L;
        lean = false;
        groundY = p.getY();
        cruise = cruiseFor(groundY);
        fired = 0; pending = 0; failedUses = 0; armWait = 0; emptyTicks = 0; sinceUse = 0; shortestBurn = 0; seenId = -1;
        stopTicks = 0;
    }

    private static void handOver(LocalPlayer p) {
        Kine.LOGGER.info("Kine takeoff: hand-over at y {} after {} rockets", String.format("%.1f", p.getY()), fired);
        reset();
        Autopilot.engageFromTakeoff(p);
    }

    /** Stop and leave the pilot flying, with a short message. */
    private static void stop() {
        if (active()) Kine.LOGGER.info("Kine takeoff: stopped in {} after {} rockets", phase, fired);
        reset();
        stopTicks = STOP_MESSAGE_TICKS;
    }

    private static void reset() {
        phase = Phase.IDLE;
        refilling = false;
        lastFrameNanos = 0L;
        pathCount = 0;
        planX = Double.NaN;
    }

    /** The heading to climb along: the nearest to where you face at which the rockets can't hit you (RocketHit). */
    private static float climbYaw(LocalPlayer p) {
        float wanted = p.getYRot();
        boolean right = rocketArm(p) == HumanoidArm.RIGHT;
        if (wanted != safeFor || right != safeRight) {
            safeFor = wanted; safeRight = right;
            safeYaw = RocketHit.safeYaw(wanted, CLIMB_PITCHES, right);
        }
        return safeYaw;
    }

    // The arm the server puts a used rocket in, as Entity.getHandHoldingItemAngle picks it.
    private static HumanoidArm rocketArm(LocalPlayer p) {
        boolean onlyOffhand = p.getOffhandItem().is(Items.FIREWORK_ROCKET) && !p.getMainHandItem().is(Items.FIREWORK_ROCKET);
        return onlyOffhand ? p.getMainArm().getOpposite() : p.getMainArm();
    }

    private static boolean pilotKeys(Minecraft mc) {
        var o = mc.options;
        return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
            || o.keyJump.isDown() || o.keyShift.isDown();
    }

    // ---- rockets ----

    // The rockets startUseItem would fire: the main hand's, then the offhand's once the main hand is empty.
    private static boolean holding(LocalPlayer p) {
        ItemStack main = p.getMainHandItem();
        return main.is(Items.FIREWORK_ROCKET) || main.isEmpty() && p.getOffhandItem().is(Items.FIREWORK_ROCKET);
    }

    private static int heldRockets(LocalPlayer p) {
        ItemStack main = p.getMainHandItem(), off = p.getOffhandItem();
        int n = 0;
        if (main.is(Items.FIREWORK_ROCKET) && !hasStars(main)) n += main.getCount();
        else if (!main.isEmpty()) return 0;
        if (off.is(Items.FIREWORK_ROCKET) && !hasStars(off)) n += off.getCount();
        return n;
    }

    // Every rocket without stars carried (the hotbar, the main inventory, the offhand): the count a takeoff may plan on,
    // since the hand is refilled from the other stacks as it runs out (refill). Offered only with rockets in hand.
    private static int carriedRockets(LocalPlayer p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) if (usable(s)) n += s.getCount();
        if (usable(p.getOffhandItem())) n += p.getOffhandItem().getCount();
        return n;
    }

    // the shortest flight duration among the rockets carried, for the rocket count
    private static int carriedDuration(LocalPlayer p) {
        int d = Integer.MAX_VALUE;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) if (usable(s)) d = Math.min(d, duration(s));
        if (usable(p.getOffhandItem())) d = Math.min(d, duration(p.getOffhandItem()));
        return d == Integer.MAX_VALUE ? heldDuration(p) : d;
    }

    private static boolean usable(ItemStack s) {
        return s.is(Items.FIREWORK_ROCKET) && !hasStars(s);
    }

    // When the hand's rockets run out mid-climb and more are carried, as a player would: a hotbar stack is selected
    // (a number key: the game sends the change before the next use); else a main-inventory stack is swapped into the
    // selected hotbar slot in an inventory opening (InventoryClicks: the open tick, the click at its end, the close).
    // Run at the end of each climb tick, so a stack used up by the last rocket is refilled while that rocket burns.
    private static void refill(Minecraft mc, LocalPlayer p) {
        if (refilling) {
            if (InventoryClicks.isOpenTick()) { refilling = false; swapIntoHand(mc, p); }
            return;
        }
        if (heldRockets(p) > 0 || carriedRockets(p) == 0) return;
        var items = p.getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            if (usable(items.get(i))) { p.getInventory().setSelectedSlot(i); return; }
        }
        if (InventoryClicks.available(mc, p) && !InventoryClicks.isOpenTick()) { InventoryClicks.requestOpen(); refilling = true; }
    }

    // At the end of the open tick: the first main-inventory stack of rockets into the selected hotbar slot, which the
    // last rocket left empty (a number-key swap), then the close a vanilla client sends.
    private static void swapIntoHand(Minecraft mc, LocalPlayer p) {
        AbstractContainerMenu menu = p.inventoryMenu;
        if (mc.gameMode == null || !InventoryClicks.available(mc, p)) return;   // a screen or container is open, or the cursor is busy
        int src = -1;                                          // inventory menu slots 9 to 35: the main inventory
        for (int i = 9; i <= 35; i++) if (usable(menu.slots.get(i).getItem())) { src = i; break; }
        if (src < 0 || !InventoryClicks.claimBatch()) return;  // none left, or another feature clicked in this opening
        mc.gameMode.handleContainerInput(menu.containerId, src, p.getInventory().getSelectedSlot(), ContainerInput.SWAP, p);
        InventoryClicks.closeAsVanilla(mc, p);
        Kine.LOGGER.info("Kine takeoff: rockets from the inventory into the hand ({} now)", heldRockets(p));
    }

    private static boolean heldHasStars(LocalPlayer p) {
        ItemStack main = p.getMainHandItem();
        return main.is(Items.FIREWORK_ROCKET) ? hasStars(main) : hasStars(p.getOffhandItem());
    }

    // the shortest flight duration among the rockets that would be fired, for the rocket count
    private static int heldDuration(LocalPlayer p) {
        ItemStack main = p.getMainHandItem(), off = p.getOffhandItem();
        int d = Integer.MAX_VALUE;
        if (main.is(Items.FIREWORK_ROCKET)) d = duration(main);
        if (off.is(Items.FIREWORK_ROCKET) && !hasStars(off)) d = Math.min(d, duration(off));
        return d == Integer.MAX_VALUE ? 1 : d;
    }

    private static boolean hasStars(ItemStack s) {
        Fireworks fw = s.get(DataComponents.FIREWORKS);
        return fw != null && !fw.explosions().isEmpty();
    }

    private static int duration(ItemStack s) {
        Fireworks fw = s.get(DataComponents.FIREWORKS);
        return fw == null ? 0 : fw.flightDuration();
    }

    private static int rocketDuration(FireworkRocketEntity r) { return duration(r.getItem()); }

    // a rocket pushing the player, if any
    private static FireworkRocketEntity attachedRocket(Minecraft mc, LocalPlayer p) {
        List<FireworkRocketEntity> rs = FireworkFuel.boosting(mc, p);
        return rs.isEmpty() ? null : rs.get(0);
    }

    // ---- the warning row ----

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!Settings.takeoff) return;
        Minecraft mc = Minecraft.getInstance();
        String s;
        int color;
        if (active()) { s = "TAKEOFF"; color = HudColors.GREEN; }
        else if (stopTicks > 0) {
            if (Landing.valid()) return;   // the row is LEFT CLICK TO LAND's, the one to act on
            s = "TAKEOFF STOPPED"; color = HudColors.RED;
        }
        else {
            switch (status) {
                case READY -> { s = "LEFT CLICK TO TAKE OFF"; color = HudColors.GREEN; }
                case BLOCKED -> { s = "BLOCKED ABOVE, CAN'T TAKE OFF"; color = HudColors.RED; }
                case ROCKETS -> {
                    s = needed > TakeoffModel.MAX_ROCKETS ? "NOT ENOUGH ROCKETS TO TAKE OFF"
                                                          : "NOT ENOUGH ROCKETS TO TAKE OFF (NEED " + needed + ")";
                    color = HudColors.RED;
                }
                case WORN -> { s = "ELYTRA TOO WORN TO TAKE OFF"; color = HudColors.RED; }
                case WORN_CRUISE -> { s = "NOT ENOUGH ELYTRA DURABILITY TO TAKE OFF"; color = HudColors.RED; }
                case STARS -> { s = "ROCKETS WITH STARS WOULD HURT YOU"; color = HudColors.RED; }
                case LAVA -> { s = "CAN'T TAKE OFF FROM LAVA"; color = HudColors.RED; }
                case AUTOPILOT -> { s = "TURN ON THE AUTOPILOT TO TAKE OFF"; color = HudColors.RED; }
                default -> { return; }
            }
        }
        int W = mc.getWindow().getGuiScaledWidth(), H = mc.getWindow().getGuiScaledHeight();
        g.text(mc.font, s, W / 2 - mc.font.width(s) / 2, HudLayout.warningRow(H, mc.font.lineHeight), color, true);
    }
}
