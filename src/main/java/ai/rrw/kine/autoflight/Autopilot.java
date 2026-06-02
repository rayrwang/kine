package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

public class Autopilot {

    // --- tuning (rates are per SECOND so motion is smooth at any framerate) ---
    private static final float SMOOTH    = 0.15f; // pitch ease fraction per TICK toward the director
    private static final float MAX_DPS   = 140f;  // cap on pitch change per second (deg) = old 7/tick
    private static final float TURN_DPS  = 30f;   // heading change per second while A/D held (deg) = old 1.5/tick
    private static final float MOUSE_EPS = 0.15f; // per-tick rotation drift (deg) that counts as a manual override

    private static KeyMapping toggleKey;
    private static boolean engaged = false;
    private static float cmdPitch, cmdYaw;          // what we're commanding, advanced per frame
    private static long  lastFrameNanos = 0L;       // for real per-frame dt

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
        if (p == null) { disengage(); return; }   // left the world — never persist the lock
        while (toggleKey.consumeClick()) {
            if (engaged) disengage();
            else if (FlightDirector.isActive()) engage(p);
        }
        if (engaged && !FlightDirector.isActive()) disengage();   // too low / not gliding
    }

    // Driven per-frame from the HUD render callback. Eases pitch toward the flight director and turns
    // with A/D, both scaled by real elapsed time. A manual mouse input (rotation drifting away from
    // what we commanded) hands control straight back; checked here, on the same cadence we write, so
    // our own per-frame writes are never mistaken for the pilot.
    private static void control() {
        if (!engaged) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || !FlightDirector.isActive()) return;   // tick() performs the actual disengage

        long now = System.nanoTime();
        if (lastFrameNanos == 0L) { lastFrameNanos = now; cmdPitch = p.getXRot(); cmdYaw = p.getYRot(); return; }
        float dt = (float) ((now - lastFrameNanos) / 1.0e9);
        lastFrameNanos = now;
        if (dt <= 0f) return;
        if (dt > 0.05f) dt = 0.05f;                            // clamp after a hitch/pause

        float drift = MOUSE_EPS * 20f * dt;                    // per-tick sensitivity, scaled to this frame
        if (Math.abs(p.getXRot() - cmdPitch) > drift
            || Math.abs(Mth.wrapDegrees(p.getYRot() - cmdYaw)) > drift) { disengage(); return; }

        // pitch: ease toward the director (per-tick fraction converted to continuous), rate-capped
        float target = FlightDirector.commandedPitch();
        float factor = (float) (1.0 - Math.pow(1.0 - SMOOTH, dt * 20.0));
        float step   = (target - cmdPitch) * factor;
        float cap    = MAX_DPS * dt;
        cmdPitch += Math.max(-cap, Math.min(cap, step));

        // yaw: A/D nudge
        if (mc.options.keyLeft.isDown())  cmdYaw -= TURN_DPS * dt;
        if (mc.options.keyRight.isDown()) cmdYaw += TURN_DPS * dt;

        p.setXRot(cmdPitch);
        p.setYRot(cmdYaw);
        cmdPitch = p.getXRot();   // re-read post-clamp ([-90, 90])
        cmdYaw   = p.getYRot();
    }

    private static void engage(LocalPlayer p) {
        engaged = true;
        cmdPitch = p.getXRot();
        cmdYaw   = p.getYRot();
        lastFrameNanos = System.nanoTime();
    }

    public static void disengage() { engaged = false; lastFrameNanos = 0L; }

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
    }
}
