package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
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
 * Two rails laid along the autopilot's predicted flight path, drawn as TRUE world geometry: each
 * rail is a thin {@code debugQuads} (POSITION_COLOR) strip emitted in the level render pass, so the
 * rails are perspective-correct, depth-sorted against terrain, and converge with distance on their
 * own. The middle is left clear (rails, not a ribbon).
 *
 * The rails are dropped below the predicted path along the path normal (straight down as a
 * fallback) by (standing eye height - current eye height), so they sit exactly the standing
 * eye height below the camera -- i.e. where the ground would be if you were standing there. The
 * gliding pose lowers the eye over the feet, so a fixed drop below the feet path would read too low;
 * anchoring to the eye keeps it looking like you're riding along the ground at any pose.
 *
 * The path is extended a little BEHIND the aircraft (a straight extrapolation at the current flight
 * slope) so the rails pass under you, then rolls the flight model forward. It uses the SAME seed the
 * planner uses (see {@link TerrainGuard#seedFromPlayer}), so the rails trace the committed
 * trajectory -- a continuous predicted-vs-flown drift check. Stored relative to the aircraft and
 * re-anchored each frame to the interpolated player position so it flows rather than stepping at the
 * 20 Hz tick rate. Shown only while the autopilot is engaged.
 */
public final class FlightRibbon {
    private FlightRibbon() {}

    private static final int    RIBBON_TICKS  = 420;    // how far ahead to predict (matches the planner horizon)
    private static final int    DECIMATE      = 3;      // one rail span per this many ticks
    private static final int    MAX_PTS       = RIBBON_TICKS / DECIMATE + 8;
    private static final double SKIP_BLOCKS   = 0.0;    // forward start (behind-extension covers under the player)
    private static final double BEHIND_BLOCKS = 6.0;    // extend the rails this far behind the aircraft
    private static final double BEHIND_STEP   = 3.0;    // spacing of the behind extrapolation points
    private static final double RAIL_HALF     = 3.7;    // half the rail separation in blocks (rails ~7.4 apart)
    private static final double RAIL_W         = 0.60;  // rail strip width in blocks
    private static final double STAND_EYE     = 1.62;   // standing eye height; the rails sit this far below the camera

    private static final int RAIL_COLOR = (0xE0 << 24) | 0x2864FF;   // solid blue (no gradient)

    // cached predicted path, stored RELATIVE to the aircraft (re-anchored to interpolated pos each frame)
    private static final double[] dAlong = new double[MAX_PTS];   // along-track distance (blocks; negative = behind)
    private static final double[] yRel   = new double[MAX_PTS];   // predicted altitude minus the player's tick altitude
    private static final double[] wp     = new double[MAX_PTS];   // commanded pitch at that point
    private static double trackX, trackZ, perpX, perpZ;           // ground-track unit vector + its perpendicular
    private static int count = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FlightRibbon::tick);          // after FlightDirector's tick
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(FlightRibbon::renderLevel);
    }

    private static void tick(Minecraft mc) {
        count = 0;
        if (!Settings.flightRibbon || !Autopilot.isEngaged() || !FlightDirector.isActive()) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isFallFlying()) return;

        double dx = p.getX() - p.xOld, dz = p.getZ() - p.zOld;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-4) return;                       // no ground track (also guards the slope divide below)
        trackX = dx / len; trackZ = dz / len;
        perpX = -trackZ; perpZ = trackX;
        double py = p.getY();

        int n = 0;
        // extend behind: straight extrapolation back along the current flight slope (len > 0 here)
        double slope = (py - p.yOld) / len;             // vertical blocks per along-track block
        float curPitch = p.getXRot();
        for (double d = -BEHIND_BLOCKS; d <= 1.0e-9 && n < MAX_PTS; d += BEHIND_STEP) {
            dAlong[n] = d; yRel[n] = slope * d; wp[n] = curPitch; n++;
        }
        // forward: roll the flight model out
        FlightModel.State s = TerrainGuard.seedFromPlayer(p, FlightDirector.floorAltitude());
        for (int k = 1; k <= RIBBON_TICKS && n < MAX_PTS; k++) {
            double pitch = FlightModel.step(s);
            if (k % DECIMATE == 0 && s.x >= SKIP_BLOCKS) {
                dAlong[n] = s.x; yRel[n] = s.y - py; wp[n] = pitch; n++;
            }
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
        double drop = Math.max(0.0, STAND_EYE - p.getEyeHeight());   // land the rails STAND_EYE below the eye

        // per-point: offset each path point below itself by `drop`, along the (down) path normal
        double[] ox = new double[count], oy = new double[count], oz = new double[count];
        int[] col = new int[count];
        for (int i = 0; i < count; i++) {
            int j = (i + 1 < count) ? i + 1 : i;
            int h = (i + 1 < count) ? i : i - 1;
            double ta = dAlong[j] - dAlong[h];            // tangent: along-track component
            double tu = yRel[j] - yRel[h];                // tangent: vertical component
            double tl = Math.sqrt(ta * ta + tu * tu);
            double na, nu;                                 // downward path normal (along, up)
            if (tl < 1.0e-6) { na = 0.0; nu = -1.0; }      // fallback: straight down
            else { ta /= tl; tu /= tl; na = tu; nu = -ta; } // rotate tangent -90deg -> points below the path
            double offA = na * drop, offY = nu * drop;
            ox[i] = ax + trackX * (dAlong[i] + offA);
            oy[i] = ay + yRel[i] + offY;
            oz[i] = az + trackZ * (dAlong[i] + offA);
            col[i] = RAIL_COLOR;
        }

        Matrix4f mat = ctx.poseStack().last().pose();
        // Pass 1 -- the visible translucent rails. debugQuads depth-TESTS against terrain (so terrain
        // occludes them) but does not WRITE depth; drawing this FIRST means it tests against the far terrain
        // depth and reliably passes, so the rails draw solid.
        VertexConsumer vc = ctx.bufferSource().getBuffer(RenderTypes.debugQuads());
        for (int i = 1; i < count; i++) {
            rail(vc, mat, cam, ox, oy, oz, col, i, +1.0);   // left rail
            rail(vc, mat, cam, ox, oy, oz, col, i, -1.0);   // right rail
        }
        ctx.bufferSource().endBatch(RenderTypes.debugQuads());

        // Pass 2 -- depth only. Without this, clouds (a later frame-graph pass composited against the main
        // depth buffer) painted over the rails even when behind them, because debugQuads never wrote depth.
        // waterMask writes depth with no color (write mask 0) under the same LEQUAL test, laying the rails
        // into the depth buffer exactly where they're visible, so the clouds pass is correctly occluded.
        // It runs AFTER the color pass on purpose: drawing it first would make the color pass test LEQUAL
        // against this coincident-but-not-bit-identical depth (different vertex shader) and z-fight to white.
        VertexConsumer mvc = ctx.bufferSource().getBuffer(RenderTypes.waterMask());
        for (int i = 1; i < count; i++) {
            railMask(mvc, mat, cam, ox, oy, oz, i, +1.0);
            railMask(mvc, mat, cam, ox, oy, oz, i, -1.0);
        }
        ctx.bufferSource().endBatch(RenderTypes.waterMask());
    }

    /** Same quad as {@link #rail} but position-only, for the depth-write (waterMask) pass. waterMask
     *  back-face culls, and a near-horizontal rail quad faces away from the camera as readily as toward it,
     *  so emit BOTH windings -- one survives the cull and lays down depth regardless of orientation. */
    private static void railMask(VertexConsumer vc, Matrix4f mat, Vec3 cam,
                                 double[] ox, double[] oy, double[] oz, int i, double sign) {
        int a = i - 1, b = i;
        double e1 = sign * RAIL_HALF - RAIL_W * 0.5;
        double e2 = sign * RAIL_HALF + RAIL_W * 0.5;
        double a1x = ox[a] + perpX * e1, a1z = oz[a] + perpZ * e1, ay = oy[a];
        double b1x = ox[b] + perpX * e1, b1z = oz[b] + perpZ * e1, by = oy[b];
        double b2x = ox[b] + perpX * e2, b2z = oz[b] + perpZ * e2;
        double a2x = ox[a] + perpX * e2, a2z = oz[a] + perpZ * e2;
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
    private static void rail(VertexConsumer vc, Matrix4f mat, Vec3 cam,
                             double[] ox, double[] oy, double[] oz, int[] col, int i, double sign) {
        int a = i - 1, b = i;
        double e1 = sign * RAIL_HALF - RAIL_W * 0.5;
        double e2 = sign * RAIL_HALF + RAIL_W * 0.5;
        vertex(vc, mat, cam, ox[a] + perpX * e1, oy[a], oz[a] + perpZ * e1, col[a]);
        vertex(vc, mat, cam, ox[b] + perpX * e1, oy[b], oz[b] + perpZ * e1, col[b]);
        vertex(vc, mat, cam, ox[b] + perpX * e2, oy[b], oz[b] + perpZ * e2, col[b]);
        vertex(vc, mat, cam, ox[a] + perpX * e2, oy[a], oz[a] + perpZ * e2, col[a]);
    }

    private static void vertex(VertexConsumer vc, Matrix4f mat, Vec3 cam,
                               double wxx, double wyy, double wzz, int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF, a = (argb >>> 24) & 0xFF;
        vc.addVertex(mat, (float) (wxx - cam.x), (float) (wyy - cam.y), (float) (wzz - cam.z))
          .setColor(r, g, b, a);
    }
}
