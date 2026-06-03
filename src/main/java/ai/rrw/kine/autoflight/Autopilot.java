package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.util.KineTime;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

public class Autopilot {

    // --- tuning (rates are per SECOND so motion is smooth at any framerate) ---
    private static final float SMOOTH    = 0.15f; // pitch ease fraction per TICK toward the director
    private static final float MAX_DPS   = 140f;  // cap on pitch change per second (deg) = old 7/tick
    private static final float TURN_DPS  = 30f;   // heading change per second while A/D held (deg) = old 1.5/tick
    private static final float NAV_TURN_DPS = 35f;// max heading change per second while a nav mode steers
    private static final float LANDING_TURN_DPS = 80f;// sharper turn authority while landing, for a tight circle
    private static final float MOUSE_EPS = 0.15f; // per-tick rotation drift (deg) that counts as a manual override
    private static final int   TRIP_DELAY  = 5;   // ticks an engage survives while it can't hold (visible, then trips)
    private static final int   KICK_TICKS  = 60; // 3 s after an unattended disengage before we disconnect
    private static final float TAKEOVER_EPS = 3f; // look change (deg) that counts as the pilot taking control

    private static KeyMapping toggleKey;
    private static boolean engaged = false;
    private static float cmdPitch, cmdYaw;          // what we're commanding, advanced per frame
    private static long  lastFrameNanos = 0L;       // for real per-frame dt
    private static int   tripTicks = 0;             // counts down a too-low engage before it trips off

    // "AUTOPILOT OFF" warning + dead-man kick after an in-flight disengage
    private static boolean warnActive = false;
    private static int     kickTicks = 0;
    private static float   warnBaseYaw, warnBasePitch;

    public static boolean isEngaged() { return engaged; }

    public static void register() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.kine.autopilot", GLFW.GLFW_KEY_P, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(Autopilot::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "autopilot"),
            Autopilot::render);   // render runs per-frame; we also drive smooth control from it
    }

    // Per-tick: only the toggle and the engage/disengage gating. All rotation is driven per-frame in
    // control() so it tracks at the framerate instead of stepping 20 times a second.
    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) { disengage(); resetWarn(); return; }   // left the world — never persist the lock
        while (toggleKey.consumeClick()) {
            if (engaged) disengage();
            else if (p.isFallFlying()) engage(p);   // engage even if too low — it trips off below, not silently
        }
        // too low / not gliding: let the engage stand briefly (so it's visibly acknowledged) then trip it
        if (engaged && !FlightDirector.isActive() && !Nav.landing()) {
            if (++tripTicks >= TRIP_DELAY) disengage();
        } else {
            tripTicks = 0;
        }
        if (warnActive) updateWarn(mc, p);
    }

    // Driven per-frame from the HUD render callback. Eases pitch toward the flight director and turns
    // with A/D, both scaled by real elapsed time. A manual mouse input (rotation drifting away from
    // what we commanded) hands control straight back; checked here, on the same cadence we write, so
    // our own per-frame writes are never mistaken for the pilot.
    private static void control() {
        if (!engaged) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || (!FlightDirector.isActive() && !Nav.landing())) return;   // tick() performs the actual disengage

        long now = System.nanoTime();
        if (lastFrameNanos == 0L) { lastFrameNanos = now; cmdPitch = p.getXRot(); cmdYaw = p.getYRot(); return; }
        float dt = (float) ((now - lastFrameNanos) / 1.0e9);
        lastFrameNanos = now;
        if (dt <= 0f) return;
        if (dt > 0.05f) dt = 0.05f;                            // clamp after a hitch/pause

        float drift = MOUSE_EPS * 20f * dt;                    // per-tick sensitivity, scaled to this frame
        if (Math.abs(p.getXRot() - cmdPitch) > drift
            || Math.abs(Mth.wrapDegrees(p.getYRot() - cmdYaw)) > drift) { disengage(); return; }

        // pitch: during the landing program follow the gentle descent; otherwise follow the director
        float target = Nav.landing() ? Nav.landingPitch() : FlightDirector.commandedPitch();
        float factor = (float) (1.0 - Math.pow(1.0 - SMOOTH, dt * 20.0));
        float step   = (target - cmdPitch) * factor;
        float cap    = MAX_DPS * dt;
        cmdPitch += Math.max(-cap, Math.min(cap, step));

        // yaw: a nav mode steers toward its heading/bearing; otherwise manual A/D
        if (Nav.steering()) {
            float err = Mth.wrapDegrees(Nav.desiredYaw(p) - cmdYaw);
            float maxTurn = (Nav.landing() ? LANDING_TURN_DPS : NAV_TURN_DPS) * dt;
            cmdYaw += Math.max(-maxTurn, Math.min(maxTurn, err));
        } else {
            if (mc.options.keyLeft.isDown())  cmdYaw -= TURN_DPS * dt;
            if (mc.options.keyRight.isDown()) cmdYaw += TURN_DPS * dt;
        }

        p.setXRot(cmdPitch);
        p.setYRot(cmdYaw);
        cmdPitch = p.getXRot();   // re-read post-clamp ([-90, 90])
        cmdYaw   = p.getYRot();
    }

    private static void engage(LocalPlayer p) {
        engaged = true;
        warnActive = false;   // engaging clears any pending "off" warning
        tripTicks = 0;
        cmdPitch = p.getXRot();
        cmdYaw   = p.getYRot();
        lastFrameNanos = System.nanoTime();
    }

    public static void disengage() {
        if (engaged) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null && p.isFallFlying()) startWarn(p);   // dropped out mid-glide — warn + arm the kick
        }
        engaged = false; lastFrameNanos = 0L; tripTicks = 0;
    }

    private static void startWarn(LocalPlayer p) {
        warnActive = true; kickTicks = 0;
        warnBaseYaw = p.getYRot(); warnBasePitch = p.getXRot();
    }

    private static void resetWarn() { warnActive = false; kickTicks = 0; }

    // While the warning is up: clear it the moment the pilot takes control; otherwise disconnect at 3 s.
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
        Kine.LOGGER.warn("kine: autopilot disengaged with no pilot input — disconnecting");
        ClientPacketListener cpl = mc.getConnection();
        if (cpl != null) cpl.getConnection().disconnect(Component.literal("Kine: autopilot disengaged — no pilot input"));
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        control();   // HUD render is per-frame — drive smooth pitch + yaw here
        Minecraft mc = Minecraft.getInstance();
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int boxX = W / 2 + 91 + 4; // mirror of the offhand slot, right of the hotbar
        int boxY = H - 20;
        g.fill(boxX, boxY, boxX + 18, boxY + 18, 0x90000000); // slot-like backdrop
        String s = "AP";
        int tx = boxX + (20 - mc.font.width(s)) / 2;
        int ty = boxY + (20 - mc.font.lineHeight) / 2 + 1;
        if (engaged) {
            g.text(mc.font, s, tx, ty, 0xFF55FF55, true); // green
        } else {
            g.text(mc.font, s, tx, ty, 0xFFFF0000, true); // red
        }

        if (warnActive) {
            Font font = mc.font;
            boolean flash = (System.currentTimeMillis() / 400) % 2 == 0;
            int color = flash ? 0xFFFF2020 : 0xFFFFD000;
            int wy = H / 3;
            int big = (int) (font.lineHeight * 2.0f);
            drawCentered(g, font, "AUTOPILOT OFF", W / 2, wy, color, 2.0f);
            int secs = Math.max(0, (KICK_TICKS - kickTicks + 19) / 20);
            drawCentered(g, font, "disconnect in " + KineTime.format(secs) + " \u2014 take control",
                W / 2, wy + big + 6, 0xFFFFFFFF, 1.0f);
        }
    }

    private static void drawCentered(GuiGraphicsExtractor g, Font font, String text,
                                     int cx, int cy, int color, float scale) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        pose.scale(scale, scale);
        g.text(font, text, -font.width(text) / 2, -font.lineHeight / 2, color, true);
        pose.popMatrix();
    }
}
