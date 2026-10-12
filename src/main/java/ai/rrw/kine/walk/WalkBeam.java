package ai.rrw.kine.walk;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Optional;

/**
 * The walk destination's pillar, green like the elytra destination's (RW: the search display's colours start at cyan),
 * drawn the way the elytra one is (hud/NavBeam): a strip turned to face the camera from the ground up to at least 45
 * degrees above the eye, as wide on screen as that beam, the column's top face outlined, depth-tested with a faint copy
 * through terrain. Shown while this dimension has a walk destination and the path display is on, until a Cancel in the
 * Walk tab (back with a new destination or Go). WalkRender calls it from its own pass.
 */
public final class WalkBeam {
    private WalkBeam() {}

    private static final int BODY       = 0x5044FF44;   // translucent green beam
    private static final int CORE       = 0xB044FF44;   // brighter centre line
    private static final int FACE       = 0xFF44FF44;   // the landing block's top face edges
    private static final int GHOST_BODY = 0x1444FF44;   // the faint copy through terrain
    private static final int GHOST_CORE = 0x3044FF44;
    private static final int GHOST_FACE = 0x5044FF44;

    private static final double HALF_W      = 0.5;      // the beam's half width up close: the block column itself
    private static final double CORE_HALF_W = 0.06;
    private static final double EDGE_HALF_W = 0.04;     // the face's edge strips
    private static final double FACE_LIFT   = 0.01;     // the face's edges sit this far above the block, clear of its surface
    private static final double TOP_DEG     = 45;       // the top's least angle above the eye: past the screen's top edge
                                                        // when looking level at the default field of view (70)
    private static final double TOP_TAN     = Math.tan(Math.toRadians(TOP_DEG));

    // debugQuads with no depth test, for the faint copy (the game has no position-colour quads without one)
    private static final RenderPipeline SEE_THROUGH_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_beam_see_through"))
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .build());
    private static final RenderType SEE_THROUGH = RenderType.create(
        "kine_walk_beam_see_through", RenderSetup.builder(SEE_THROUGH_PIPELINE).sortOnUpload().createRenderSetup());

    /** Both passes, faint first (from WalkRender's level render pass). */
    static void render(LevelRenderContext ctx) {
        draw(ctx, true);
        draw(ctx, false);
    }

    private static void draw(LevelRenderContext ctx, boolean faint) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.options.hideGui) return;
        int[] dest = Walk.destination();
        if (!Settings.walkPathDisplay || !Settings.walkPathfinder || dest == null || Walk.dismissed()) return;
        int bx = dest[0], bz = dest[1];
        double ground = columnTop(level, bx, bz);
        double baseY = Double.isNaN(ground) ? level.getMinY() : ground;
        double wx = bx + 0.5, wz = bz + 0.5;

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        Matrix4f mat = ctx.poseStack().last().pose();
        double dx = wx - cam.x, dz = wz - cam.z, dist = Math.sqrt(dx * dx + dz * dz);
        // the top at least TOP_DEG above the eye at any distance, so a far pillar looks as tall as a near one (RW: "it
        // shoudl amingain same ange not smae world wehgiht"); never below the top of the world
        double topY = Math.max(level.getMaxY() + 1, cam.y + dist * TOP_TAN);
        // across the strip, square to the line of sight; straight above the column, any direction will do
        double ax = dist > 1.0e-3 ? -dz / dist : 1.0, az = dist > 1.0e-3 ? dx / dist : 0.0;
        // one gui pixel at the beam's distance, in blocks: the old HUD beam's widths were in gui pixels
        double gpx = 2.0 * Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0) / mc.getWindow().getGuiScaledHeight();
        double bodyPx = Math.max(2.0, Math.min(16.0, 600.0 / Math.max(1.0, dist)));
        double body = Math.max(HALF_W, dist * gpx * bodyPx / 2.0), core = Math.max(CORE_HALF_W, dist * gpx);
        double edge = Math.max(EDGE_HALF_W, Math.hypot(dist, ground - cam.y) * gpx);
        // Past the projection's far plane (four render distances, Camera.depthFar) the pillar would be cut off, so
        // beyond two render distances it is drawn scaled toward the camera: the same place and size on screen (RW).
        double near = mc.options.getEffectiveRenderDistance() * 32.0;
        boolean scaled = dist > near;
        if (scaled) {
            double k = near / dist;
            wx = cam.x + (wx - cam.x) * k; wz = cam.z + (wz - cam.z) * k;
            baseY = cam.y + (baseY - cam.y) * k; topY = cam.y + (topY - cam.y) * k;
            body *= k; core *= k;
        }

        if (faint) {
            VertexConsumer vc = ctx.bufferSource().getBuffer(SEE_THROUGH);
            strip(vc, mat, cam, wx, wz, ax, az, body, baseY, topY, GHOST_BODY);
            strip(vc, mat, cam, wx, wz, ax, az, core, baseY, topY, GHOST_CORE);
            if (!Double.isNaN(ground) && !scaled) face(vc, mat, cam, bx, ground + FACE_LIFT, bz, edge, GHOST_FACE);
            ctx.bufferSource().endBatch(SEE_THROUGH);
            return;
        }
        VertexConsumer vc = ctx.bufferSource().getBuffer(RenderTypes.debugQuads());
        strip(vc, mat, cam, wx, wz, ax, az, body, baseY, topY, BODY);
        strip(vc, mat, cam, wx, wz, ax, az, core, baseY, topY, CORE);
        if (!Double.isNaN(ground) && !scaled) face(vc, mat, cam, bx, ground + FACE_LIFT, bz, edge, FACE);
        ctx.bufferSource().endBatch(RenderTypes.debugQuads());

        // depth for the core and the face's edges (both windings: waterMask culls back faces)
        VertexConsumer mvc = ctx.bufferSource().getBuffer(RenderTypes.waterMask());
        stripMask(mvc, mat, cam, wx, wz, ax, az, core, baseY, topY);
        if (!Double.isNaN(ground) && !scaled) faceMask(mvc, mat, cam, bx, ground + FACE_LIFT, bz, edge);
        ctx.bufferSource().endBatch(RenderTypes.waterMask());
    }

    // The top of a column where the client has its chunk (the MOTION_BLOCKING heightmap, as the landing spot search
    // reads it), else NaN.
    private static double columnTop(ClientLevel level, int x, int z) {
        if (!level.hasChunkAt(new BlockPos(x, level.getMinY(), z))) return Double.NaN;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    // A vertical strip centred on (cx, cz), half width h along (ax, az), from y0 to y1.
    private static void strip(VertexConsumer vc, Matrix4f mat, Vec3 cam, double cx, double cz, double ax, double az,
                              double h, double y0, double y1, int argb) {
        vertex(vc, mat, cam, cx - ax * h, y0, cz - az * h, argb);
        vertex(vc, mat, cam, cx + ax * h, y0, cz + az * h, argb);
        vertex(vc, mat, cam, cx + ax * h, y1, cz + az * h, argb);
        vertex(vc, mat, cam, cx - ax * h, y1, cz - az * h, argb);
    }

    private static void stripMask(VertexConsumer vc, Matrix4f mat, Vec3 cam, double cx, double cz, double ax, double az,
                                  double h, double y0, double y1) {
        double[][] q = {{cx - ax * h, y0, cz - az * h}, {cx + ax * h, y0, cz + az * h},
                        {cx + ax * h, y1, cz + az * h}, {cx - ax * h, y1, cz - az * h}};
        quadMask(vc, mat, cam, q);
    }

    // The four edges of the block's top face at height y, each a flat strip of half width e along the edge.
    private static void face(VertexConsumer vc, Matrix4f mat, Vec3 cam, int x, double y, int z, double e, int argb) {
        for (double[][] q : faceEdges(x, y, z, e)) for (double[] v : q) vertex(vc, mat, cam, v[0], v[1], v[2], argb);
    }

    private static void faceMask(VertexConsumer vc, Matrix4f mat, Vec3 cam, int x, double y, int z, double e) {
        for (double[][] q : faceEdges(x, y, z, e)) quadMask(vc, mat, cam, q);
    }

    private static double[][][] faceEdges(int x, double y, int z, double e) {
        double x0 = x, x1 = x + 1, z0 = z, z1 = z + 1;
        return new double[][][]{
            {{x0 - e, y, z0 - e}, {x1 + e, y, z0 - e}, {x1 + e, y, z0 + e}, {x0 - e, y, z0 + e}},   // north edge
            {{x0 - e, y, z1 - e}, {x1 + e, y, z1 - e}, {x1 + e, y, z1 + e}, {x0 - e, y, z1 + e}},   // south edge
            {{x0 - e, y, z0 + e}, {x0 + e, y, z0 + e}, {x0 + e, y, z1 - e}, {x0 - e, y, z1 - e}},   // west edge
            {{x1 - e, y, z0 + e}, {x1 + e, y, z0 + e}, {x1 + e, y, z1 - e}, {x1 - e, y, z1 - e}}};  // east edge
    }

    private static void quadMask(VertexConsumer vc, Matrix4f mat, Vec3 cam, double[][] q) {
        for (int i = 0; i < 4; i++) posVertex(vc, mat, cam, q[i]);        // one winding
        for (int i = 3; i >= 0; i--) posVertex(vc, mat, cam, q[i]);       // and the other
    }

    private static void posVertex(VertexConsumer vc, Matrix4f mat, Vec3 cam, double[] v) {
        vc.addVertex(mat, (float) (v[0] - cam.x), (float) (v[1] - cam.y), (float) (v[2] - cam.z));
    }

    private static void vertex(VertexConsumer vc, Matrix4f mat, Vec3 cam, double x, double y, double z, int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF, a = (argb >>> 24) & 0xFF;
        vc.addVertex(mat, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).setColor(r, g, b, a);
    }
}
