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
    private static final float SMOOTH    = 0.15f; // ease toward target per tick (lower = smoother)
    private static final float MAX_STEP  = 7f;    // hard cap on rotation change per tick (deg) — realism/anticheat
    private static final float TURN_RATE = 1.5f;  // heading change per tick while A/D held (deg)
    private static final float MOUSE_EPS = 0.15f; // rotation drift (deg) that counts as a manual mouse override

    private static KeyMapping toggleKey;
    private static boolean engaged = false;
    private static float lastSetPitch, lastSetYaw;  // what we last commanded, to spot manual override

    public static boolean isEngaged() { return engaged; }

    public static void register() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.kine.autopilot", GLFW.GLFW_KEY_P, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(Autopilot::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "autopilot"),
            Autopilot::render);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) { disengage(); return; }   // left the world (e.g. disconnect) — never persist the lock
        while (toggleKey.consumeClick()) {
            if (engaged) disengage();
            else if (FlightDirector.isActive()) engage(p);
        }
        if (!engaged) return;

        if (!FlightDirector.isActive()) { disengage(); return; }   // too low / not gliding
        if (pilotOverride(mc, p))      { disengage(); return; }    // any mouse input

        // pitch: follow the flight director, smoothed + capped
        p.setXRot(ease(p.getXRot(), FlightDirector.commandedPitch()));

        // heading: A/D nudge
        float yaw = p.getYRot();
        if (mc.options.keyLeft.isDown())  yaw -= TURN_RATE;
        if (mc.options.keyRight.isDown()) yaw += TURN_RATE;
        p.setYRot(yaw);

        lastSetPitch = p.getXRot();    // store post-clamp values
        lastSetYaw   = p.getYRot();
    }

    private static void engage(LocalPlayer p) {
        engaged = true;
        lastSetPitch = p.getXRot();
        lastSetYaw   = p.getYRot();
    }

    public static void disengage() { engaged = false; }

    private static boolean pilotOverride(Minecraft mc, LocalPlayer p) {
        float dPitch = Math.abs(p.getXRot() - lastSetPitch);
        float dYaw   = Math.abs(Mth.wrapDegrees(p.getYRot() - lastSetYaw));
        if (dPitch > MOUSE_EPS || dYaw > MOUSE_EPS) return true;       // mouse look
        return false; // ignore mouse buttons
    }

    private static float ease(float cur, float target) {
        float step = (target - cur) * SMOOTH;
        return cur + Math.max(-MAX_STEP, Math.min(MAX_STEP, step));
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
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
