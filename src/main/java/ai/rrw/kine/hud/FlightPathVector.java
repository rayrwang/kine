package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.util.Smoothing;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Flight path vector, the velocity symbol from an aircraft HUD: the winged ring marks the direction you
 * are moving, projected onto the screen, rather than where you are looking. Below the centre means you
 * are descending; off to one side means you are drifting that way. Instrument only.
 *
 * <p>Shown while gliding, or once a fall passes FALL_SHOW blocks, when moving fast enough for the
 * direction to mean something. A light frame-rate-independent low-pass keeps it smooth between ticks,
 * and it is held dimmed at the screen edge when the velocity points outside the view.
 */
public final class FlightPathVector {
    private FlightPathVector() {}

    private static final int    COLOR      = 0xFF30FF30;  // green marker
    private static final int    SHADOW     = 0xB0000000;  // 1 px drop shadow, so it reads against sky or ground
    private static final int    DIM        = 0x80FFFFFF;  // marker held at the screen edge
    private static final int    DIM_SHADOW = 0x60000000;
    private static final double MIN_SPEED  = 0.08;        // blocks/tick; below this the direction is just jitter
    private static final double FALL_SHOW  = 3.0;         // also show in a free fall once it's a real one (blocks)
    private static final float  NEAR_W     = 0.05f;       // clip-space w below which the direction is behind the view
    private static final float  SMOOTH_TC  = 0.10f;       // s; light low-pass time constant
    private static final float  RAY        = 32.0f;       // arbitrary distance along the velocity ray to project
    private static final int    RADIUS     = 4;
    private static final int    WING       = 6;
    private static final int    TAIL       = 5;
    private static final int    MARGIN     = 10;          // keep an edge-held marker this far inside the edge

    private static double vx, vy, vz;       // smoothed velocity (world space)
    private static boolean have = false;
    private static long lastNanos = 0L;

    public static void register() {
        HudElementRegistry.attachElementAfter(
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "flightdir"),
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "fpv"),
            FlightPathVector::render);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!Settings.shows(Settings.displayFlightPathVector)) { have = false; return; }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { have = false; return; }
        if (!(p.isFallFlying() || p.fallDistance > FALL_SHOW)) { have = false; return; }   // flight or a real fall only

        Vec3 vel = p.getDeltaMovement();
        if (vel.lengthSqr() < MIN_SPEED * MIN_SPEED) { have = false; return; }

        // light, frame-rate-independent low-pass (velocity only changes at the 20 Hz tick)
        long now = System.nanoTime();
        double dt = (lastNanos == 0L) ? 0.0 : (now - lastNanos) / 1.0e9;
        lastNanos = now;
        if (!have || dt <= 0 || dt > 0.5) {
            vx = vel.x; vy = vel.y; vz = vel.z; have = true;
        } else {
            vx = Smoothing.toward(vx, vel.x, dt, SMOOTH_TC);
            vy = Smoothing.toward(vy, vel.y, dt, SMOOTH_TC);
            vz = Smoothing.toward(vz, vel.z, dt, SMOOTH_TC);
        }
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 1.0e-6) return;

        // project the velocity direction, a camera-relative offset, through the view-projection matrix
        Camera camera = mc.gameRenderer.getMainCamera();
        Matrix4f vp = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Vector4f v = new Vector4f((float)(vx / len) * RAY, (float)(vy / len) * RAY, (float)(vz / len) * RAY, 1.0f);
        vp.transform(v);
        if (v.w <= NEAR_W) return;                          // moving away from the view direction: nothing to mark

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        float[] s = ScreenProjection.toScreen(v, W, H);
        float sx = s[0], sy = s[1];

        boolean caged = sx < MARGIN || sx > W - MARGIN || sy < MARGIN || sy > H - MARGIN;
        int cx = (int) Math.max(MARGIN, Math.min(W - MARGIN, sx));
        int cy = (int) Math.max(MARGIN, Math.min(H - MARGIN, sy));

        symbol(g, cx + 1, cy + 1, caged ? DIM_SHADOW : SHADOW);   // drop shadow first
        symbol(g, cx, cy, caged ? DIM : COLOR);
    }

    /** The winged-ring velocity symbol, screen-aligned, centred on (cx, cy). Drawn with fill only. */
    private static void symbol(GuiGraphicsExtractor g, int cx, int cy, int color) {
        circle(g, cx, cy, RADIUS, color);
        g.fill(cx - RADIUS - WING, cy, cx - RADIUS, cy + 1, color);   // left wing
        g.fill(cx + RADIUS, cy, cx + RADIUS + WING, cy + 1, color);   // right wing
        g.fill(cx, cy - RADIUS - TAIL, cx + 1, cy - RADIUS, color);   // tail (up)
    }

    /** 1px circle outline via the midpoint algorithm (8-way symmetry). */
    private static void circle(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        int x = r, y = 0, err = 1 - r;
        while (x >= y) {
            px(g, cx + x, cy + y, color); px(g, cx + y, cy + x, color);
            px(g, cx - y, cy + x, color); px(g, cx - x, cy + y, color);
            px(g, cx - x, cy - y, color); px(g, cx - y, cy - x, color);
            px(g, cx + y, cy - x, color); px(g, cx + x, cy - y, color);
            y++;
            if (err < 0) { err += 2 * y + 1; }
            else { x--; err += 2 * (y - x) + 1; }
        }
    }

    private static void px(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x, y, x + 1, y + 1, color);
    }
}
