package ai.rrw.kine.autoflight;

import ai.rrw.kine.Settings;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Two rails along the autopilot's predicted flight path ({@link PathPrediction}), drawn as world geometry
 * in the level render pass (thin debugQuads strips), so they are perspective-correct and hidden behind
 * terrain, and they curve where the autopilot will turn.
 *
 * The rails sit a standing eye height below the camera, where the ground would be if you stood on the
 * path: they are dropped along the path's downward normal by (standing eye height - current eye
 * height), since the gliding pose lowers the eye. The path starts a few blocks behind the aircraft (a
 * straight extrapolation at the current slope, so the rails pass under you). Points are stored relative
 * to the aircraft and re-anchored to the interpolated position every frame, so they move smoothly
 * between ticks. Drawn while engaged, and for a rocket takeoff, offered or flying, along its planned path
 * (straight up from where you stand, then the lean and the cruise).
 */
public final class FlightPathRails {
    private FlightPathRails() {}

    private static final int    RAIL_TICKS    = 420;    // how far ahead to draw (ticks)
    private static final int    DECIMATE      = 3;      // ticks per rail span
    private static final int    MAX_PTS       = RAIL_TICKS / DECIMATE + 8;
    private static final double BEHIND_BLOCKS = 6.0;    // extend the rails this far behind the aircraft
    private static final double BEHIND_STEP   = 3.0;    // spacing of the behind extrapolation points
    private static final double RAIL_HALF     = 3.7;    // half the rail separation (blocks)
    private static final double RAIL_W         = 0.60;  // rail strip width in blocks
    private static final double STAND_EYE     = 1.62;   // standing eye height; the rails sit this far below the camera

    private static final int RAIL_TOP    = (0xE0 << 24) | 0x2864FF;   // seen from above: blue
    private static final int RAIL_BOTTOM = (0xE0 << 24) | 0xFF7A1A;   // seen from below (underside): orange

    // predicted path relative to the aircraft, re-anchored to the interpolated position each frame
    private static final double[] rx = new double[MAX_PTS], ry = new double[MAX_PTS], rz = new double[MAX_PTS];
    private static int count = 0;
    private static double headX = 0.0, headZ = 1.0;   // horizontal heading the across-track direction starts from

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FlightPathRails::tick);          // registered after PathPrediction, so it runs after it
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(FlightPathRails::renderLevel);
    }

    private static void tick(Minecraft mc) {
        count = 0;
        // a rocket takeoff, offered or flying: its planned path, which starts straight up
        if (Settings.shows(Settings.flightRibbon) && Takeoff.pathCount() > 1) {
            double yr = Math.toRadians(Takeoff.heading());
            headX = -Math.sin(yr); headZ = Math.cos(yr);
            int n = 0;
            for (int k = 0; k < Takeoff.pathCount() && k <= RAIL_TICKS && n < MAX_PTS; k += DECIMATE) {
                rx[n] = Takeoff.pathX(k); ry[n] = Takeoff.pathY(k); rz[n] = Takeoff.pathZ(k); n++;
            }
            count = n;
            return;
        }
        if (!Settings.shows(Settings.flightRibbon) || !Autopilot.isEngaged() || !FlightDirector.isActive()) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isFallFlying() || PathPrediction.count() == 0) return;

        double dx = p.getX() - p.xOld, dz = p.getZ() - p.zOld;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-4) return;                       // no ground track (also guards the slope divide below)
        double trackX = dx / len, trackZ = dz / len;
        headX = trackX; headZ = trackZ;

        int n = 0;
        // extend behind: straight extrapolation back along the current ground track and flight slope
        double slope = (p.getY() - p.yOld) / len;       // vertical blocks per along-track block
        for (double d = -BEHIND_BLOCKS; d <= 1.0e-9 && n < MAX_PTS; d += BEHIND_STEP) {
            rx[n] = trackX * d; ry[n] = slope * d; rz[n] = trackZ * d; n++;
        }
        // forward: the predicted path
        for (int k = DECIMATE; k <= RAIL_TICKS && k < PathPrediction.count() && n < MAX_PTS; k += DECIMATE) {
            rx[n] = PathPrediction.x(k); ry[n] = PathPrediction.y(k); rz[n] = PathPrediction.z(k); n++;
        }
        count = n;
    }

    private static void renderLevel(LevelRenderContext ctx) {
        if (count < 2) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        double t = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double ax = p.xOld + (p.getX() - p.xOld) * t;     // interpolated player pos -> smooth flow
        double ay = p.yOld + (p.getY() - p.yOld) * t;
        double az = p.zOld + (p.getZ() - p.zOld) * t;
        double drop = Math.max(0.0, STAND_EYE - p.getEyeHeight());   // puts the rails STAND_EYE below the eye

        // Per point: the horizontal direction of travel, the across-track direction the rails are spaced
        // along, and the point offset by `drop` along the downward path normal.
        double[] ox = new double[count], oy = new double[count], oz = new double[count];
        double[] acx = new double[count], acz = new double[count];
        double ux = headX, uz = headZ;                     // last known horizontal direction (the heading on vertical spans)
        for (int i = 0; i < count; i++) {
            int j = (i + 1 < count) ? i + 1 : i;
            int h = (i + 1 < count) ? i : i - 1;
            double hx = rx[j] - rx[h], hz = rz[j] - rz[h];
            double ta = Math.sqrt(hx * hx + hz * hz);     // tangent: horizontal component
            if (ta > 1.0e-9) { ux = hx / ta; uz = hz / ta; }
            double tu = ry[j] - ry[h];                     // tangent: vertical component
            double tl = Math.sqrt(ta * ta + tu * tu);
            double na, nu;                                 // downward path normal (along, up)
            if (tl < 1.0e-6) { na = 0.0; nu = -1.0; }      // fallback: straight down
            else { na = tu / tl; nu = -ta / tl; }          // tangent turned -90 degrees: points below the path
            acx[i] = -uz; acz[i] = ux;
            ox[i] = ax + rx[i] + ux * na * drop;
            oy[i] = ay + ry[i] + nu * drop;
            oz[i] = az + rz[i] + uz * na * drop;
        }

        Matrix4f mat = ctx.poseStack().last().pose();

        // Pass 1, the visible rails: one zero-thickness quad per span, coloured by the face the camera
        // sees (blue top, orange underside). The face is found from the strip's normal (span tangent
        // crossed with the across-track direction, pointing up), not from camera height, which mislabels
        // the tilted climb and dive limbs. debugQuads tests depth against terrain but doesn't write it.
        // This pass comes before the depth pass below; the other order would test against the rails' own
        // nearly equal depth and z-fight to white.
        VertexConsumer vc = ctx.bufferSource().getBuffer(RenderTypes.debugQuads());
        for (int i = 1; i < count; i++) {
            int a = i - 1;
            double tx = ox[i] - ox[a], ty = oy[i] - oy[a], tz = oz[i] - oz[a];   // span tangent
            double nx = ty * acz[a];                                             // normal = tangent x across
            double ny = tz * acx[a] - tx * acz[a];
            double nz = -ty * acx[a];
            if (ny < 0.0) { nx = -nx; ny = -ny; nz = -nz; }                      // orient the normal upward
            double side = (cam.x - ox[a]) * nx + (cam.y - oy[a]) * ny + (cam.z - oz[a]) * nz;
            int color = side >= 0.0 ? RAIL_TOP : RAIL_BOTTOM;                    // above surface -> top (blue)
            rail(vc, mat, cam, ox, oy, oz, acx, acz, color, i, +1.0);   // left rail
            rail(vc, mat, cam, ox, oy, oz, acx, acz, color, i, -1.0);   // right rail
        }
        ctx.bufferSource().endBatch(RenderTypes.debugQuads());

        // Pass 2, depth only. Without it clouds, drawn later against the depth buffer, paint over the rails
        // even when behind them, because debugQuads writes no depth. waterMask writes depth with no colour
        // under the same LEQUAL test.
        VertexConsumer mvc = ctx.bufferSource().getBuffer(RenderTypes.waterMask());
        for (int i = 1; i < count; i++) {
            railMask(mvc, mat, cam, ox, oy, oz, acx, acz, i, +1.0);
            railMask(mvc, mat, cam, ox, oy, oz, acx, acz, i, -1.0);
        }
        ctx.bufferSource().endBatch(RenderTypes.waterMask());
    }

    /** Same quad as {@link #rail} but position-only, for the depth pass. waterMask culls back faces and a
     *  nearly flat rail can face either way, so both windings are emitted and one survives the cull. */
    private static void railMask(VertexConsumer vc, Matrix4f mat, Vec3 cam, double[] ox, double[] oy, double[] oz,
                                 double[] acx, double[] acz, int i, double sign) {
        int a = i - 1, b = i;
        double e1 = sign * RAIL_HALF - RAIL_W * 0.5;
        double e2 = sign * RAIL_HALF + RAIL_W * 0.5;
        double a1x = ox[a] + acx[a] * e1, a1z = oz[a] + acz[a] * e1, ay = oy[a];
        double b1x = ox[b] + acx[b] * e1, b1z = oz[b] + acz[b] * e1, by = oy[b];
        double b2x = ox[b] + acx[b] * e2, b2z = oz[b] + acz[b] * e2;
        double a2x = ox[a] + acx[a] * e2, a2z = oz[a] + acz[a] * e2;
        posVertex(vc, mat, cam, a1x, ay, a1z);   // forward winding
        posVertex(vc, mat, cam, b1x, by, b1z);
        posVertex(vc, mat, cam, b2x, by, b2z);
        posVertex(vc, mat, cam, a2x, ay, a2z);
        posVertex(vc, mat, cam, a2x, ay, a2z);   // reverse winding (other face)
        posVertex(vc, mat, cam, b2x, by, b2z);
        posVertex(vc, mat, cam, b1x, by, b1z);
        posVertex(vc, mat, cam, a1x, ay, a1z);
    }

    private static void posVertex(VertexConsumer vc, Matrix4f mat, Vec3 cam,
                                  double wxx, double wyy, double wzz) {
        vc.addVertex(mat, (float) (wxx - cam.x), (float) (wyy - cam.y), (float) (wzz - cam.z));
    }

    /** Emit one quad: the span of one rail (a thin strip at sign*RAIL_HALF across the path) from i-1 to i. */
    private static void rail(VertexConsumer vc, Matrix4f mat, Vec3 cam, double[] ox, double[] oy, double[] oz,
                             double[] acx, double[] acz, int color, int i, double sign) {
        int a = i - 1, b = i;
        double e1 = sign * RAIL_HALF - RAIL_W * 0.5;
        double e2 = sign * RAIL_HALF + RAIL_W * 0.5;
        vertex(vc, mat, cam, ox[a] + acx[a] * e1, oy[a], oz[a] + acz[a] * e1, color);
        vertex(vc, mat, cam, ox[b] + acx[b] * e1, oy[b], oz[b] + acz[b] * e1, color);
        vertex(vc, mat, cam, ox[b] + acx[b] * e2, oy[b], oz[b] + acz[b] * e2, color);
        vertex(vc, mat, cam, ox[a] + acx[a] * e2, oy[a], oz[a] + acz[a] * e2, color);
    }

    private static void vertex(VertexConsumer vc, Matrix4f mat, Vec3 cam,
                               double wxx, double wyy, double wzz, int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF, a = (argb >>> 24) & 0xFF;
        vc.addVertex(mat, (float) (wxx - cam.x), (float) (wyy - cam.y), (float) (wzz - cam.z))
          .setColor(r, g, b, a);
    }
}
