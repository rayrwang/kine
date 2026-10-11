package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.fireworks.FireworkFuel;
import ai.rrw.kine.fireworks.RocketBurn;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudLayout;
import ai.rrw.kine.mixin.MinecraftInvoker;
import ai.rrw.kine.util.MouseTurn;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Rocket takeoff from the ground to the autopilot's cruise. Standing with an elytra on and rockets in hand, the
 * warning row offers it in green (LEFT CLICK TO TAKE OFF), or says in red why it can't. A left click looks
 * straight up, jumps, opens the elytra on the third tick (jump, release, press, as a player opens it), fires a
 * rocket the next tick, climbs straight up until a leaning climb would clear the terrain, leans to 70 degrees,
 * and at the end of each rocket fires another unless the autopilot, taking over now in altitude hold, would hold
 * the cruise; then it engages the autopilot. {@link TakeoffModel} holds the physics and the rules.
 *
 * <p>The server sees only what a vanilla client sends: the jump through the keys ({@link #adjustInput}), turns by
 * whole mouse counts, and rockets used through the game's own startUseItem just before handleKeybinds, so each
 * use carries the rotation of its tick. Any mouse movement or movement key from the pilot stops the takeoff.
 */
public final class Takeoff {
    private Takeoff() {}

    /** What the warning row says while standing with rockets in hand. */
    public enum Status { NONE, READY, BLOCKED, ROCKETS, WORN, STARS, WATER, AUTOPILOT }

    private enum Phase { IDLE, LOOK_UP, JUMP, CLIMB }

    private static final float LOOK_DPS = 240f;                                    // looking up from the ground, deg/s
    private static final float LEAN_DPS = (float) (TakeoffModel.PITCH_RATE * 20);  // leaning over, as the model leans
    private static final float MOUSE_EPS = 0.15f;      // per-tick rotation drift (deg) that counts as the pilot's mouse
    private static final float MAX_FRAME_DT = 0.25f;
    private static final int   USE_WAIT = 20;          // ticks to wait for a fired rocket to show before using again
    private static final int   EMPTY_TICKS = 2;        // ticks with no rocket pushing before the next is fired
    private static final int   MAX_FAILED_USES = 3;
    private static final double CRUISE_MIN_AGL = 60;   // the cruise is at least this far above the takeoff spot
    private static final int   PATH_TICKS = 421;       // rolled out for the rails (they draw 420 ticks)
    private static final int   PLAN_EVERY = 4;         // ticks between re-plans while standing still
    private static final int   STOP_MESSAGE_TICKS = 60;
    private static final int   ARM_WAIT = 20;          // ticks to wait at the cruise for the autopilot to arm

    private static Phase phase = Phase.IDLE;
    private static Status status = Status.NONE;
    private static int needed = 0;                     // rockets needed if each burns its shortest
    private static float yaw, cmdPitch, setPitch, setYaw;
    private static long lastFrameNanos = 0L;
    private static int jumpTick, pending, failedUses, fired, stopTicks, armWait, emptyTicks;
    private static boolean lean;
    private static double groundY, cruise;

    // planning while standing: re-planned every PLAN_EVERY ticks or when the player moves or turns
    private static int planAge = 0;
    private static double planX = Double.NaN, planY, planZ;
    private static float planYaw;

    // the planned path for the rails, relative to the player
    private static final double[] px = new double[PATH_TICKS], py = new double[PATH_TICKS], pz = new double[PATH_TICKS];
    private static int pathCount = 0;

    public static boolean active() { return phase != Phase.IDLE; }
    /** What the warning row offers while standing. */
    public static Status status() { return status; }
    /** Rockets the takeoff from here needs if each burns its shortest. */
    public static int needed() { return needed; }
    /** Whether the takeoff has the warning row (its prompt, its status or its stop message). */
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
        if (stopTicks > 0) stopTicks--;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { reset(); status = Status.NONE; return; }
        if (!Settings.takeoff) { if (active()) stop(); status = Status.NONE; pathCount = 0; return; }
        switch (phase) {
            case IDLE -> idle(mc, p);
            case LOOK_UP -> {
                if (pilotKeys(mc) || !p.onGround() || Autopilot.isEngaged() || !holding(p)) { stop(); return; }
                if (p.getXRot() <= -89.5f) { phase = Phase.JUMP; jumpTick = 0; }
            }
            case JUMP -> {
                if (pilotKeys(mc)) { stop(); return; }
                if (++jumpTick >= 3) {
                    if (p.isFallFlying()) { phase = Phase.CLIMB; pending = 0; failedUses = 0; fired = 0; }
                    else stop();
                }
            }
            case CLIMB -> climbTick(mc, p);
        }
    }

    private static void idle(Minecraft mc, LocalPlayer p) {
        status = check(mc, p);
        if (status == Status.NONE || status == Status.BLOCKED || status == Status.WORN || status == Status.STARS
            || status == Status.WATER || status == Status.AUTOPILOT) {
            pathCount = 0; planX = Double.NaN;
            return;
        }
        // READY or not enough rockets: plan, at once the first time, then every PLAN_EVERY ticks (a plan is about 1 ms)
        if (Double.isNaN(planX) || ++planAge >= PLAN_EVERY) {
            planAge = 0;
            planX = p.getX(); planY = p.getY(); planZ = p.getZ(); planYaw = p.getYRot();
            double target = cruiseFor(p.getY());
            int d = heldDuration(p);
            Terrain2D terrain = TurnGuard.terrainOf(mc.level);
            double top = mc.level.getMaxY();
            TakeoffModel.Plan worst = TakeoffModel.fromGround(p.getX(), p.getY(), p.getZ(), planYaw, target,
                RocketBurn.minTicks(d), terrain, top, 1);
            needed = worst.reachesCruise() ? worst.rockets() : TakeoffModel.MAX_ROCKETS + 1;
            TakeoffModel.Plan mean = TakeoffModel.fromGround(p.getX(), p.getY(), p.getZ(), planYaw, target,
                (int) Math.round(RocketBurn.meanTicks(d)), terrain, top, PATH_TICKS);
            keepPath(mean, p);
        }
        if (heldRockets(p) < needed) { status = Status.ROCKETS; pathCount = 0; }
    }

    /** What the row should say while standing; NONE when nothing to offer (not on the ground, no elytra, no rocket). */
    private static Status check(Minecraft mc, LocalPlayer p) {
        if (!p.onGround() || p.isPassenger() || p.isFallFlying()) return Status.NONE;
        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.has(DataComponents.GLIDER) || !holding(p)) return Status.NONE;
        if (!Settings.autopilot) return Status.AUTOPILOT;
        if (!LivingEntity.canGlideUsing(chest, EquipmentSlot.CHEST)) return Status.WORN;
        if (heldHasStars(p)) return Status.STARS;
        if (p.isInWater() || p.isInLava()) return Status.WATER;
        if (blockedAbove(mc.level, p)) return Status.BLOCKED;
        return Status.READY;
    }

    // The cruise the takeoff climbs to: the autopilot's, but at least CRUISE_MIN_AGL above the takeoff spot.
    private static double cruiseFor(double ground) {
        return Math.max(FlightDirector.targetAltitude(), ground + CRUISE_MIN_AGL);
    }

    // Anything that stops motion or holds fluid at head height or above, in any column the player stands in.
    private static boolean blockedAbove(ClientLevel level, LocalPlayer p) {
        AABB b = p.getBoundingBox();
        int feet = Mth.floor(p.getY());
        for (int x = Mth.floor(b.minX); x <= Mth.floor(b.maxX - 1.0e-7); x++)
            for (int z = Mth.floor(b.minZ); z <= Mth.floor(b.maxZ - 1.0e-7); z++)
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) > feet + 1) return true;
        return false;
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
            if (status != Status.READY || !mc.options.keyAttack.consumeClick()) return false;
            while (mc.options.keyAttack.consumeClick()) { }
            mc.options.keyAttack.setDown(false);     // a held click would go on to break the block in view
            start(p);
            return true;
        }
        while (mc.options.keyAttack.consumeClick()) { }   // no swings or block breaking during the takeoff
        if (phase != Phase.CLIMB) return true;

        if (attachedRocket(mc, p) != null) { pending = 0; failedUses = 0; emptyTicks = 0; return true; }   // one is burning
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
        if (heldRockets(p) == 0 || fired >= TakeoffModel.MAX_ROCKETS) {
            // out of rockets: the autopilot climbs the rest of the way on its own, if it can arm
            if (FlightDirector.isActive()) handOver(p);
            else stop();
            return true;
        }
        if (!p.isFallFlying()) { stop(); return true; }   // used on the ground it would launch the rocket off a block
        ((MinecraftInvoker) mc).invokeStartUseItem();
        fired++;
        pending = USE_WAIT;
        Kine.LOGGER.info("Kine takeoff: rocket {} at y {} (rockets in hand {}, failed uses {})",
            fired, String.format("%.1f", p.getY()), heldRockets(p), failedUses);
        return true;
    }

    // ---- the keys, read in aiStep ----

    /** Jump, release, jump: the elytra opens on the second press, in the air. */
    public static Input adjustInput(LocalPlayer p, Input keys) {
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
        MouseTurn.toward(p, yaw, cmdPitch);
        setPitch = p.getXRot();
        setYaw = p.getYRot();
    }

    // ---- start, stop, hand over ----

    private static void start(LocalPlayer p) {
        phase = Phase.LOOK_UP;
        yaw = p.getYRot();
        cmdPitch = setPitch = p.getXRot();
        setYaw = yaw;
        lastFrameNanos = 0L;
        lean = false;
        groundY = p.getY();
        cruise = cruiseFor(groundY);
        fired = 0; pending = 0; failedUses = 0; armWait = 0; emptyTicks = 0;
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
        lastFrameNanos = 0L;
        pathCount = 0;
        planX = Double.NaN;
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
        else if (stopTicks > 0) { s = "TAKEOFF STOPPED"; color = HudColors.RED; }
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
                case STARS -> { s = "ROCKETS WITH STARS WOULD HURT YOU"; color = HudColors.RED; }
                case WATER -> { s = "CAN'T TAKE OFF FROM WATER"; color = HudColors.RED; }
                case AUTOPILOT -> { s = "TURN ON THE AUTOPILOT TO TAKE OFF"; color = HudColors.RED; }
                default -> { return; }
            }
        }
        int W = mc.getWindow().getGuiScaledWidth(), H = mc.getWindow().getGuiScaledHeight();
        g.text(mc.font, s, W / 2 - mc.font.width(s) / 2, HudLayout.warningRow(H, mc.font.lineHeight), color, true);
    }
}
