package ai.rrw.kine.view;

import ai.rrw.kine.Settings;
import ai.rrw.kine.combat.KillAura;
import ai.rrw.kine.util.MouseTurn;
import ai.rrw.kine.walk.Walk;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * The walk's camera lock (Walking, "Camera follows the walk"; RW 2026-10-15 23:07: "toggleing walk locks camera
 * direction (e.g. useful for recording a video)"): while a walk takes you, the first-person camera shows the facing
 * the walk sends to the server, eased, instead of your own view. Camera only (RW 2026-10-17 04:57: "it shouldn't break
 * anything since it's purely visual"): the searches, the keys and the rotation sent are the same with it on or off.
 * The camera is set after the game has aligned it with your player (FreecamCameraMixin, each release's); your
 * player's own rotation is not touched while the walk walks.
 *
 * <p>The mouse (RW 04:59, option 1): moving it turns your view as always, and the camera shows your view; after
 * FREE_LOOK_MS with no mouse movement the camera eases back to the walk's facing. At the first movement your view is
 * first turned, by whole mouse counts as the mouse itself would, to where the camera points, so the mouse turns on from
 * what you see. A view turned so moves nothing the walk sends: the kill aura turns the rotation sent from the last one
 * it sent toward the walk's facing, in whole counts from the rotation now, so a whole-count change of the view (as any
 * mouse movement) leaves what it sends the same; only the yaw is turned (on land the walk sends your view's pitch).
 * When the walk ends with the camera on its facing, your view is turned there the same way, so the camera doesn't
 * swing back to where you looked before the walk.
 *
 * <p>While the walk stands planning, the camera holds its last facing (the rotation sent rests on your view then).
 * Third person and freecam: the game's own camera. Off by default (free look stays the default, the pathfinder's
 * ruling of 2026-10-12).
 */
public final class CameraLock {
    private CameraLock() {}

    /** The pause with no mouse movement after which the camera eases back to the walk's facing. */
    static final long FREE_LOOK_MS = 1000;
    /** The share of the way to the facing the camera closes each tick (eased, exponential): 0.3 closes half the way
     *  in two ticks, nine tenths in six and a half. */
    static final double EASE_PER_TICK = 0.3;
    /** The most the camera turns in a tick (degrees): the walk's own turn rate (the kill aura's 29.5). */
    static final float MAX_TURN_PER_TICK = 30f;

    /** A walk is on with the setting: the lock is in force (the camera shows the facing unless you look round). */
    private static boolean engaged = false;
    /** The camera shows the lock's rotation (not your view) as of the last frame. */
    private static boolean showing = false;
    private static long lastMouseNanos = Long.MIN_VALUE / 2, lastFrameNanos = 0;
    /** The camera's rotation while showing the lock. */
    private static float camYaw, camPitch;
    /** The walk's facing (the rotation sent) at the last two ticks, the yaw kept continuous. */
    private static float prevYaw, curYaw, prevPitch, curPitch;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(CameraLock::endTick);
    }

    /** End of a client tick (after the rotation was sent): engage or end the lock, and take this tick's facing. */
    private static void endTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        boolean on = Settings.walkCameraLock && p != null && mc.level != null && Walk.active() && !Freecam.active()
            && p.isAlive();
        if (on && !engaged) {
            engaged = true;
            showing = false;
            lastMouseNanos = Long.MIN_VALUE / 2;
            camYaw = p.getYRot();
            camPitch = p.getXRot();
            prevYaw = curYaw = KillAura.lastSentYaw(p);
            prevPitch = curPitch = KillAura.lastSentPitch(p);
        } else if (!on && engaged) {
            engaged = false;
            if (showing && p != null && p.isAlive()) turnViewTo(mc, p, camYaw);
            showing = false;
            return;
        }
        if (!engaged) return;
        prevYaw = curYaw;
        prevPitch = curPitch;
        // walking: the rotation it sent this tick; standing while it plans, the last one held
        if (Walk.steeringNow()) {
            curYaw = prevYaw + Mth.wrapDegrees(KillAura.lastSentYaw(p) - prevYaw);
            curPitch = KillAura.lastSentPitch(p);
        }
    }

    /** Each frame, from the camera mixin (freecam off; detached: third person): whether the camera shows the lock's
     *  rotation this frame (yaw(), pitch()). */
    public static boolean frame(float partialTicks, boolean detached) {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0.0 : Math.min(2.0, Math.max(0.0, (now - lastFrameNanos) / 5.0e7));
        lastFrameNanos = now;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!engaged || p == null || detached) { showing = false; return false; }
        if (now - lastMouseNanos < FREE_LOOK_MS * 1_000_000L) {
            // looking round: your view, and the ease back starts from it
            camYaw = p.getViewYRot(partialTicks);
            camPitch = p.getViewXRot(partialTicks);
            showing = false;
            return false;
        }
        float ty = prevYaw + (curYaw - prevYaw) * partialTicks;
        float tp = prevPitch + (curPitch - prevPitch) * partialTicks;
        double f = 1.0 - Math.pow(1.0 - EASE_PER_TICK, dt);
        float max = (float) (MAX_TURN_PER_TICK * dt);
        float dy = Mth.wrapDegrees(ty - camYaw), dp = tp - camPitch;
        camYaw += Mth.clamp((float) (dy * f), -max, max);
        camPitch = Mth.clamp(camPitch + Mth.clamp((float) (dp * f), -max, max), -90f, 90f);
        showing = true;
        return true;
    }

    public static float yaw() { return camYaw; }
    public static float pitch() { return camPitch; }

    /** The mouse turned your view (MouseHandler.turnPlayer, before the turn): while the camera showed the lock, your
     *  view is first turned to where it pointed; then looking round for FREE_LOOK_MS after the last movement. */
    public static void mouse(LocalPlayer p, double dx, double dy) {
        if (!engaged || (dx == 0 && dy == 0)) return;
        if (showing) turnViewTo(Minecraft.getInstance(), p, camYaw);
        showing = false;
        lastMouseNanos = System.nanoTime();
    }

    /** Turn your view's yaw to `yaw` by whole mouse counts, as the mouse would (MouseTurn.by). */
    private static void turnViewTo(Minecraft mc, LocalPlayer p, float yaw) {
        double deg = MouseTurn.sens(mc, p) * 0.15;
        if (deg <= 0) return;
        int k = (int) Math.round(Mth.wrapDegrees(yaw - p.getYRot()) / deg);
        if (k != 0) MouseTurn.by(p, k, 0);
    }
}
