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

    // --- tuning ---
    private static final float SMOOTH    = 0.15f; // ease pitch toward target per tick (lower = smoother)
    private static final float MAX_STEP  = 7f;    // hard cap on pitch change per tick (deg) — realism/anticheat
    private static final float TURN_DPS  = 30f;   // heading change per SECOND while A/D held (deg) = old 1.5/tick
    private static final float MOUSE_EPS = 0.15f; // rotation drift per tick (deg) that counts as a manual override

    private static KeyMapping toggleKey;
    private static boolean engaged = false;
    private static float lastSetPitch;             // pitch we last commanded, to spot a manual (mouse) override
    private static float cmdYaw;                   // heading we're holding, advanced per frame
    private static long  lastFrameNanos = 0L;      // for real per-frame dt

    public static boolean isEngaged() { return engaged; }

    public static void register() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.kine.autopilot", GLFW.GLFW_KEY_P, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(Autopilot::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "autopilot"),
            Autopilot::render);   // render runs per-frame; we also drive smooth yaw from it
    }

    // Per-tick: toggle, engage/disengage gating, and the PITCH hold (yaw is handled per-frame below).
    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) { disengage(); return; }   // left the world (e.g. disconnect) — never persist the lock
        while (toggleKey.consumeClick()) {
            if (engaged) disengage();
            else if (FlightDirector.isActive()) engage(p);
        }
        if (!engaged) return;

        if (!FlightDirector.isActive()) { disengage(); return; }       // too low / not gliding
        if (Math.abs(p.getXRot() - lastSetPitch) > MOUSE_EPS) { disengage(); return; }  // mouse pitched

        p.setXRot(ease(p.getXRot(), FlightDirector.commandedPitch())); // follow the director, smoothed + capped
        lastSetPitch = p.getXRot();                                    // store post-clamp value
    }

    // Driven per-frame from the HUD render callback: advance the held heading by the real elapsed
    // time so A/D turning is smooth at the framerate instead of stepping once per tick. Yaw override
    // (mouse) is detected here, on the same cadence we write it, so the per-frame writes are never
    // mistaken for the pilot taking over.
    private static void frameYaw() {
        if (!engaged) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || !FlightDirector.isActive()) return;           // tick() handles the actual disengage

        long now = System.nanoTime();
        if (lastFrameNanos == 0L) { lastFrameNanos = now; cmdYaw = p.getYRot(); return; }
        float dt = (float) ((now - lastFrameNanos) / 1.0e9);
        lastFrameNanos = now;
        if (dt <= 0f) return;
        if (dt > 0.05f) dt = 0.05f;                                    // clamp after a hitch/pause

        // anything that moved the heading since our last write (i.e. the mouse) hands control back
        if (Math.abs(Mth.wrapDegrees(p.getYRot() - cmdYaw)) > MOUSE_EPS * 20f * dt) { disengage(); return; }

        float yaw = cmdYaw;
        if (mc.options.keyLeft.isDown())  yaw -= TURN_DPS * dt;
        if (mc.options.keyRight.isDown()) yaw += TURN_DPS * dt;
        p.setYRot(yaw);
        cmdYaw = p.getYRot();
    }

    private static void engage(LocalPlayer p) {
        engaged = true;
        lastSetPitch   = p.getXRot();
        cmdYaw         = p.getYRot();
        lastFrameNanos = System.nanoTime();
    }

    public static void disengage() { engaged = false; lastFrameNanos = 0L; }

    private static float ease(float cur, float target) {
        float step = (target - cur) * SMOOTH;
        return cur + Math.max(-MAX_STEP, Math.min(MAX_STEP, step));
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        frameYaw();   // HUD render is per-frame — use it to advance yaw smoothly
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
