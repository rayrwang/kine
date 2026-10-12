package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudDraw;
import ai.rrw.kine.hud.HudLayout;
import ai.rrw.kine.util.Compass;
import ai.rrw.kine.util.Disconnect;
import ai.rrw.kine.util.KineTime;
import ai.rrw.kine.util.Motion;
import ai.rrw.kine.util.MouseTurn;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

public class Autopilot {

    // Turn rates, per second. The steering is planned once a tick (pitch by FlightModel3D.ease, up to 15 deg a tick for
    // the porpoise law, or easeLanding, 0.15 of the gap capped at 7 deg, for the landing law: what the rollouts fly), and
    // each frame turns the view its share of the way there.
    private static final float TURN_DPS  = 30f;   // heading change per second while A/D is held (deg)
    static final float NAV_TURN_DPS = 35f;        // max heading change per second while a nav mode steers
    private static final float EMERGENCY_FLICK_DPS = 450f;// imminent-wall turn-around: brisk human mouse-flick (~180 deg in 0.4s)
    static final float LANDING_TURN_DPS = (float) (LandingModel.YAW_RATE * 20);   // the landing law's turn rate, 300 deg/s
    private static final float LANDING_BUMP_DEG = 10f; // while landing, this much mouse movement within about half a second is the pilot; less is a bump taken back
    private static final float LANDING_BUMP_DECAY = 0.5f;   // seconds: how fast the landing's tally of mouse movement fades
    private static final float MOUSE_EPS = 0.15f; // per-tick rotation drift (deg) that counts as a manual override
    private static final int   TRIP_DELAY  = 5;   // ticks an engage survives while it can't hold (visible, then trips)
    private static final int   KICK_TICKS  = 30; // 1.5 s after an unattended disengage before we disconnect
    private static final float TAKEOVER_EPS = 3f; // look change (deg) that counts as the pilot taking control
    private static final float MAX_FRAME_DT = 0.25f; // longest frame step (s), so a stall doesn't swing the view; low frame rates still steer at full rate

    private static KeyMapping toggleKey;
    private static boolean engaged = false;
    private static float cmdPitch, cmdYaw;          // the command for the coming tick, planned once a tick
    private static float basePitch, baseYaw;        // the view at the last tick boundary: frames turn from here to the command
    private static boolean planned = false;         // the command has been planned since the last tick boundary
    private static float setPitch, setYaw;          // the view after our last turn (whole mouse counts from the command)
    private static long  lastFrameNanos = 0L;       // for real per-frame dt
    private static int   tripTicks = 0;             // counts down a too-low engage before it trips off
    private static float landingMouse = 0f;         // while landing: the pilot's recent mouse movement (deg), fading

    // "AUTOPILOT OFF" warning and dead-man disconnect after a disengage in flight
    private static boolean warnActive = false;
    private static int     kickTicks = 0;
    private static float   warnBaseYaw, warnBasePitch;

    public static boolean isEngaged() { return engaged; }

    public static void register() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.kine.autopilot", GLFW.GLFW_KEY_J, Kine.KEY_CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(Autopilot::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "autopilot"),
            Autopilot::render);
    }

    // Per-tick: only the toggle and the engage/disengage gating. All rotation is driven per frame in
    // control(), so it tracks at the frame rate instead of stepping 20 times a second.
    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        // left the world, or died (a body keeps gliding until it lands): never keep the lock or the dead-man
        if (p == null || p.isDeadOrDying()) { disengage(); resetWarn(); return; }
        if (mc.isPaused()) return;   // singleplayer paused: the world stands still, so the trip delay and the dead-man wait
        if (!Settings.autopilot) {
            // switched off in the settings: let go quietly, without the AUTOPILOT OFF warning and its disconnect
            while (toggleKey.consumeClick()) { }
            engaged = false; lastFrameNanos = 0L; tripTicks = 0;
            resetWarn();
            return;
        }
        while (toggleKey.consumeClick()) {
            if (engaged) disengage();
            else if (p.isFallFlying()) engage(p);   // engage even if too low; it trips off below, visibly
        }
        // too low / not gliding: let the engage stand briefly (so it's visibly acknowledged) then trip it
        if (engaged && !FlightDirector.isActive() && !Nav.landing()) {
            if (++tripTicks >= TRIP_DELAY) disengage();
        } else {
            tripTicks = 0;
        }
        if (warnActive) updateWarn(mc, p);
    }

    /** Once per frame, after mouse input and before drawing (MinecraftFrameMixin). Nothing moves while the
     *  game is paused; the frame clock restarts afterwards, so resuming doesn't jump. */
    public static void onFrame(Minecraft mc) {
        if (mc.isPaused()) { lastFrameNanos = 0L; return; }
        control();
    }

    /**
     * At the start of each client tick, before the crosshair pick, the keybinds and the player's move (MinecraftTickMixin),
     * whatever screen is open: the view goes the rest of the way to this tick's command, so the server gets the command
     * as planned, one step a tick, at any frame rate; the frames until the next tick turn from here toward the next.
     */
    public static void onTickStart(Minecraft mc) {
        if (!engaged || mc.isPaused()) return;
        LocalPlayer p = mc.player;
        if (p == null || p.isDeadOrDying() || (!FlightDirector.isActive() && !Nav.landing())) return;
        if (lastFrameNanos == 0L) return;     // no frame yet since engaging or a pause: the first frame takes the view
        if (!planned) plan(mc, p);            // no frame since the last tick (under 20 fps): plan now
        turnTo(p, cmdYaw, cmdPitch);
        baseYaw = p.getYRot();
        basePitch = p.getXRot();
        planned = false;
    }

    // Each frame: hands control back on the pilot's mouse, moves the A/D heading bug, and turns the view its share of
    // the way from where it was at the last tick to this tick's command (the partial tick), so the view moves evenly
    // through the tick instead of arriving early and waiting (the 20 Hz stop-start a per-frame chase of a per-tick
    // target gives). A manual mouse input (rotation drifting away from what was set) hands control straight back;
    // checked here, on the same cadence as the writes, so the autopilot's own writes never read as the pilot.
    private static void control() {
        if (!engaged) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || p.isDeadOrDying() || (!FlightDirector.isActive() && !Nav.landing())) return;   // tick() performs the actual disengage

        long now = System.nanoTime();
        if (lastFrameNanos == 0L) { lastFrameNanos = now; holdView(p); return; }
        float dt = (float) ((now - lastFrameNanos) / 1.0e9);
        lastFrameNanos = now;
        if (dt <= 0f) return;
        if (dt > MAX_FRAME_DT) dt = MAX_FRAME_DT;

        // a pilot's mouse disengages. While landing only a deliberate move does: the frames' mouse movement is tallied
        // and fades over about half a second, so a small accidental bump near the ground is turned back below (letting
        // go there could cost the landing), while any real movement passes LANDING_BUMP_DEG quickly. The tally, not one
        // frame's movement: the view is turned back every frame, so a frame's movement never grows.
        float drift = MOUSE_EPS * 20f * dt;                    // per-tick sensitivity, scaled to this frame
        float moved = Math.max(Math.abs(p.getXRot() - setPitch), Math.abs(Mth.wrapDegrees(p.getYRot() - setYaw)));
        if (Nav.landing()) {
            landingMouse = landingMouse * (float) Math.exp(-dt / LANDING_BUMP_DECAY) + (moved > drift ? moved : 0f);
            if (landingMouse > LANDING_BUMP_DEG) { disengage(); return; }
        } else if (moved > drift) { disengage(); return; }

        // A/D moves the heading bug: it nudges the selected heading and enters SELECTED, so it steers
        // whether terrain avoidance or the nav law is flying, since both fly toward that heading.
        boolean apLeft = mc.options.keyLeft.isDown(), apRight = mc.options.keyRight.isDown();
        if ((apLeft ^ apRight) && Nav.mode() != Nav.Mode.MANAGED) {   // don't fight the coordinate nav while it's flying
            // from the selected heading only in SELECTED: with nav off a remembered heading is hidden and stale
            float base = (Nav.mode() == Nav.Mode.SELECTED && Nav.hasHeading()) ? Nav.selectedHeading()
                                          : (float) Compass.heading(cmdYaw);   // current heading, as compass
            Nav.setHeading(base + (apRight ? 1f : -1f) * TURN_DPS * dt);
        }

        if (!planned) plan(mc, p);
        float a = Mth.clamp(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true), 0f, 1f);
        turnTo(p, baseYaw + a * Mth.wrapDegrees(cmdYaw - baseYaw), basePitch + a * (cmdPitch - basePitch));
    }

    // The command for the coming tick, one tick's step from the last: pitch eased toward the landing law's or the
    // director's pitch as the flight model eases it; yaw by the landing law's turn rate while landing, else terrain
    // avoidance's look placement (or its emergency bank), else the nav law's turn rate. Planned once a tick, at the
    // first frame after it (the targets are set at the end of the tick) or at the next tick's start.
    private static void plan(Minecraft mc, LocalPlayer p) {
        float target = Nav.landing() ? Nav.landingPitch() : FlightDirector.commandedPitch();
        cmdPitch = Mth.clamp((float) (Nav.landing() ? FlightModel3D.easeLanding(cmdPitch, target)
                                                    : FlightModel3D.ease(cmdPitch, target)), -90f, 90f);

        if (Nav.landing()) {
            float err = Mth.wrapDegrees(Nav.desiredYaw(p) - cmdYaw);
            float maxTurn = (float) LandingModel.YAW_RATE;
            cmdYaw += Math.max(-maxTurn, Math.min(maxTurn, err));
        } else if (Settings.terrainAvoidance && TurnGuard.steering()) {
            if (TurnGuard.action() == TurnPlanner.EMERGENCY) {
                // imminent wall: hard bank toward the open side at elevated authority, the look slewed
                // at a brisk human mouse-flick rate (not instant). A banked turn, not a stalling reverse.
                // The side is the one planned, not re-read from the heading 179 deg away.
                float velYaw = (float) Motion.trackYaw(p, cmdYaw);
                float bankTarget = velYaw + (float) TurnController.bankOffset(TurnGuard.desiredHeading(), velYaw,
                    TurnGuard.emergencySide(), FlightModel3D.EMERGENCY_DELTA);
                float maxStep = EMERGENCY_FLICK_DPS / 20f;
                cmdYaw += Math.max(-maxStep, Math.min(maxStep, Mth.wrapDegrees(bankTarget - cmdYaw)));
            } else {
                // place the look at velYaw + clamp(heading - velYaw, +-DELTA_MAX), exactly as
                // FlightModel3D.step simulates, so the planner's rollouts predict the flown path
                float velYaw = (float) Motion.trackYaw(p, cmdYaw);
                float raw  = Mth.wrapDegrees((float) TurnGuard.desiredHeading() - velYaw);
                float dmax = (float) FlightModel3D.DELTA_MAX;
                cmdYaw = velYaw + Math.max(-dmax, Math.min(dmax, raw));
            }
        } else if (Nav.steering()) {
            float err = Mth.wrapDegrees(Nav.desiredYaw(p) - cmdYaw);
            float maxTurn = NAV_TURN_DPS / 20f;
            cmdYaw += Math.max(-maxTurn, Math.min(maxTurn, err));
        }
        // else nothing is commanded: hold the current heading
        planned = true;
    }

    // Turn the view as a mouse would: by whole counts toward (yaw, pitch), so every rotation sent is one a vanilla
    // client could send (at most half a count, 0.075 deg at the default sensitivity, off the command).
    private static void turnTo(LocalPlayer p, float yaw, float pitch) {
        MouseTurn.toward(p, yaw, Mth.clamp(pitch, -90f, 90f));
        setPitch = p.getXRot();
        setYaw   = p.getYRot();
    }

    // Take the current view as both the command and what we last set.
    private static void holdView(LocalPlayer p) {
        cmdPitch = setPitch = basePitch = p.getXRot();
        cmdYaw   = setYaw   = baseYaw   = p.getYRot();
        planned = false;
    }

    /** Engage for an aimed landing (Landing), even too low to arm: the landing law needs no flight director. */
    public static void engageForLanding(LocalPlayer p) {
        if (Settings.autopilot && p.isFallFlying()) engage(p);
    }

    /** Engage at the end of a rocket takeoff, as J would. */
    public static void engageFromTakeoff(LocalPlayer p) {
        if (Settings.autopilot && p.isFallFlying()) engage(p);
    }

    private static void engage(LocalPlayer p) {
        engaged = true;
        landingMouse = 0f;
        warnActive = false;   // engaging clears any pending "off" warning
        tripTicks = 0;
        holdView(p);
        lastFrameNanos = System.nanoTime();
    }

    /** Let go after a landing ended on water, where the glide goes on (LivingEntity.canGlide doesn't check for
     *  water): as after a touchdown on the ground, with no AUTOPILOT OFF warning or disconnect. */
    public static void releaseAfterLanding() {
        engaged = false; lastFrameNanos = 0L; tripTicks = 0;
        resetWarn();
    }

    public static void disengage() {
        if (engaged) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null && p.isFallFlying()) startWarn(p);   // dropped out mid-glide: warn and arm the kick
        }
        engaged = false; lastFrameNanos = 0L; tripTicks = 0;
    }

    private static void startWarn(LocalPlayer p) {
        warnActive = true; kickTicks = 0;
        warnBaseYaw = p.getYRot(); warnBasePitch = p.getXRot();
    }

    private static void resetWarn() { warnActive = false; kickTicks = 0; }

    // While the warning is up: clear it the moment the pilot takes control; otherwise disconnect at 1.5 s.
    private static void updateWarn(Minecraft mc, LocalPlayer p) {
        if (engaged || !p.isFallFlying()) { resetWarn(); return; }   // re-engaged, or no longer airborne
        boolean keys = mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
                    || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown()
                    || mc.options.keyJump.isDown() || mc.options.keyShift.isDown();
        boolean look = Math.abs(Mth.wrapDegrees(p.getYRot() - warnBaseYaw)) > TAKEOVER_EPS
                    || Math.abs(p.getXRot() - warnBasePitch) > TAKEOVER_EPS;
        if (keys || look) { resetWarn(); return; }                  // pilot took control
        if (++kickTicks >= KICK_TICKS) { resetWarn(); kick(mc); }
    }

    private static void kick(Minecraft mc) {
        Kine.LOGGER.warn("Kine: autopilot disengaged with no pilot input, disconnecting");
        Disconnect.withMessage(mc, Component.literal("Kine: autopilot disengaged with no pilot input"));
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        if (warnActive) {
            Font font = mc.font;
            int color = HudColors.warningFlash();
            int wy = HudLayout.bigWarningTop(H);
            int big = (int) (font.lineHeight * 2.0f);
            HudDraw.scaledText(g, font, "AUTOPILOT OFF", W / 2, wy, color, 2.0f, true);
            int secs = Math.max(0, (KICK_TICKS - kickTicks + 19) / 20);
            HudDraw.scaledText(g, font, "disconnect in " + KineTime.format(secs) + ", take control",
                W / 2, wy + big + 6, HudColors.WHITE, 1.0f, true);
        }
    }

}
