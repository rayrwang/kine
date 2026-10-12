package ai.rrw.kine.view;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.mixin.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Freecam (F4): the camera leaves your player and flies on its own, through blocks, while your player stays where it
 * is and is drawn there. Your movement keys move the camera (W, A, S, D along its heading, jump up, sneak down, sprint
 * faster) and your mouse turns it; your player gets none of them, so the server sees it stand still and keep its
 * facing, as a vanilla client whose player you left alone. Attack, use and pick are swallowed meanwhile (the game
 * would aim them from your player, not from the camera). Kine's own features keep acting on your player: a walk, the
 * autopilot, combat movement and the rest go on, and your camera keys don't count as you taking over.
 *
 * <p>How: at the start of each tick, before any feature reads the keys, the movement keys are read as physical
 * presses for the camera and then let go of in the game's key mappings; the camera mixin (FreecamCameraMixin) puts
 * the view at the freecam's place and facing after the game has aligned it with your player, and draws your player
 * (the camera counts as detached); the hand mixin (FreecamHandMixin) leaves out the first-person hand; the mouse mixin
 * (FreecamMouseMixin) turns the freecam instead of your player.
 * Off when you press F4 again, die, change dimension or leave the world, or switch the setting off.
 */
public final class Freecam {
    private Freecam() {}

    static final double SPEED = 0.6;        // blocks a tick (12 m/s)
    static final double FAST  = 1.8;        // with sprint held (36 m/s)

    private static KeyMapping toggleKey;
    private static boolean active = false, input = false;
    private static double x, y, z, xo, yo, zo;
    private static float yaw, pitch;
    private static ClientLevel level = null;

    public static void register() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.kine.freecam", GLFW.GLFW_KEY_F4, Kine.KEY_CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(Freecam::endTick);
    }

    public static boolean active() { return active; }

    /** Whether you moved or turned the freecam this tick (the AFK guard counts it as you being there). */
    public static boolean inputThisTick() { return active && input; }

    public static float yaw() { return yaw; }
    public static float pitch() { return pitch; }
    public static double x(float pt) { return Mth.lerp(pt, xo, x); }
    public static double y(float pt) { return Mth.lerp(pt, yo, y); }
    public static double z(float pt) { return Mth.lerp(pt, zo, z); }

    /** The mouse, in place of turning your player (Entity.turn's 0.15 degrees a count, pitch held to +-90). */
    public static void turn(double dx, double dy) {
        yaw += (float) dx * 0.15F;
        pitch = Mth.clamp(pitch + (float) dy * 0.15F, -90.0F, 90.0F);
        if (dx != 0 || dy != 0) input = true;
    }

    private static void endTick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            if (active) { off(); continue; }
            if (mc.player == null || mc.level == null) continue;
            if (!Settings.freecam) {
                mc.gui.setOverlayMessage(Component.literal("Freecam is off: switch it on in kine's settings (K), Visibility"), false);
                continue;
            }
            on(mc);
        }
    }

    private static void on(Minecraft mc) {
        Vec3 c = mc.gameRenderer.getMainCamera().position();
        x = xo = c.x; y = yo = c.y; z = zo = c.z;
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        level = mc.level;
        active = true;
    }

    public static void off() {
        active = false;
        level = null;
    }

    /** The start of every client tick (MinecraftTickMixin, before anything reads the keys): the camera keys read and
     *  taken away from your player, and this tick's camera move. */
    public static void onTickStart(Minecraft mc) {
        input = false;
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.level != level || !Settings.freecam || p.isDeadOrDying()) { off(); return; }
        Options o = mc.options;
        boolean f = false, b = false, l = false, r = false, up = false, dn = false, fast = false;
        if (mc.screen == null) {
            f = held(mc, o.keyUp); b = held(mc, o.keyDown); l = held(mc, o.keyLeft); r = held(mc, o.keyRight);
            up = held(mc, o.keyJump); dn = held(mc, o.keyShift); fast = held(mc, o.keySprint);
            // your keys move the camera, not your player: let go of them in the game, and swallow clicks the game
            // would aim from your player
            for (KeyMapping k : new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint})
                k.setDown(false);
            for (KeyMapping k : new KeyMapping[]{o.keyAttack, o.keyUse, o.keyPickItem}) {
                while (k.consumeClick()) {}
                k.setDown(false);
            }
        }
        xo = x; yo = y; zo = z;
        double fwd = (f ? 1 : 0) - (b ? 1 : 0), side = (l ? 1 : 0) - (r ? 1 : 0), vert = (up ? 1 : 0) - (dn ? 1 : 0);
        double len = Math.hypot(fwd, side);
        if (len > 1) { fwd /= len; side /= len; }
        double speed = fast ? FAST : SPEED;
        double s = Math.sin(Math.toRadians(yaw)), c = Math.cos(Math.toRadians(yaw));
        // the game's frame (Entity.moveRelative): forward is (-sin yaw, cos yaw), left is (cos yaw, sin yaw)
        x += (side * c - fwd * s) * speed;
        z += (fwd * c + side * s) * speed;
        y += vert * speed;
        if (fwd != 0 || side != 0 || vert != 0) input = true;
    }

    /** A key held on the keyboard, read from the keyboard itself (the game's mapping is let go of meanwhile). */
    private static boolean held(Minecraft mc, KeyMapping km) {
        InputConstants.Key key = ((KeyMappingAccessor) km).kine$getKey();
        if (key.getType() == InputConstants.Type.KEYSYM) return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
        if (key.getType() == InputConstants.Type.MOUSE)
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        return false;
    }
}
