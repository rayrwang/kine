package ai.rrw.kine.compat.v1_21_9;

import ai.rrw.kine.Kine;
import ai.rrw.kine.compat.WorldDraw;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Drawing in the world on 1.21.9 and 1.21.10: quads through the main pass's buffer source, lines from GPU buffers in
 *  their own render pass, as on 26.1, with these releases' render types, depth-test settings and line width (one per
 *  draw, a uniform; 26.1 and 1.21.11 take it per vertex). The passes run at the end of the level's main pass
 *  (ai.rrw.kine.mixin.v1_21_9.MainPassEndMixin): 1.21.9's Fabric API has no world render events. */
public final class World implements WorldDraw {
    // translucent position-colour quads tested against the depth buffer (less or equal) and not written to it, both
    // faces, sorted: 26.1's debug quads (1.21.10's own write depth)
    private static final RenderPipeline TESTED_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(ResourceLocation.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/tested_quads"))
            .withCull(false)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false)
            .build());
    private static final RenderType TESTED = RenderType.create("kine_tested_quads", 1536, false, true, TESTED_PIPELINE,
        RenderType.CompositeState.builder().createCompositeState(false));

    // debug quads with no depth test, for faint copies (the game has no position-colour quads without one)
    private static final RenderPipeline SEE_THROUGH_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(ResourceLocation.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/see_through_quads"))
            .withCull(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)
            .build());
    private static final RenderType SEE_THROUGH = RenderType.create("kine_see_through_quads", 1536, false, true,
        SEE_THROUGH_PIPELINE, RenderType.CompositeState.builder().createCompositeState(false));

    // strips on the ground: depth-tested against the terrain with a bias toward the camera (as the game's own lines
    // with depth bias), so the ground they lie on doesn't break them up far away; no depth write, both faces
    private static final RenderPipeline GROUND_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(ResourceLocation.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_rails"))
        .withCull(false)
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false).withDepthBias(-1.0F, -1.0F)
        .build());
    private static final RenderType GROUND = RenderType.create("kine_walk_rails", 1536, false, false, GROUND_PIPELINE,
        RenderType.CompositeState.builder().createCompositeState(false));

    // depth-tested lines; strict ones pass only where no line is already at the same depth; see-through ones untested
    private static final RenderPipeline LINES = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(ResourceLocation.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines")).build());
    private static final RenderPipeline LINES_STRICT = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(ResourceLocation.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines_strict"))
        .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST).withDepthWrite(true).build());
    private static final RenderPipeline LINES_THROUGH = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(ResourceLocation.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines_through"))
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).build());

    /** The registered passes, in order. */
    private static final List<Pass> PASSES = new ArrayList<>();

    @Override
    public void afterTranslucentTerrain(Pass pass) { PASSES.add(pass); }

    /** The end of the main pass (MainPassEndMixin): right after the translucent terrain, the tripwires and the block
     *  outline, where Fabric's WorldRenderEvents.END_MAIN runs on 1.21.10 and 1.21.11. The pose is the main pass's
     *  (its pose stack, empty there); the camera rotation is in the model-view matrix. */
    public static void endMain() {
        for (Pass pass : PASSES) pass.draw(new Matrix4f());
    }

    @Override
    public Quads quads(QuadStyle style) {
        RenderType type = switch (style) {
            case TESTED -> TESTED;
            case DEPTH_ONLY -> RenderType.waterMask();
            case SEE_THROUGH -> SEE_THROUGH;
            case GROUND -> GROUND;
        };
        // the main pass's buffer source
        MultiBufferSource.BufferSource source = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer vc = source.getBuffer(type);
        boolean colour = style != QuadStyle.DEPTH_ONLY;
        return new Quads() {
            @Override
            public Quads vertex(Matrix4f pose, float x, float y, float z, int argb) {
                if (colour) vc.addVertex(pose, x, y, z).setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
                else vc.addVertex(pose, x, y, z);
                return this;
            }

            @Override
            public void end() { source.endBatch(type); }
        };
    }

    @Override
    public Lines upload(String label, float[] p, int[] color, float[] width, int n) {
        VertexFormat fmt = DefaultVertexFormat.POSITION_COLOR_NORMAL;
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(n * 4 * fmt.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.LINES, fmt);
            for (int k = 0; k < n; k++) {
                int i = k * 6;
                float dx = p[i + 3] - p[i], dy = p[i + 4] - p[i + 1], dz = p[i + 5] - p[i + 2];
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-6F) { dx = 0; dy = 1e-3F; dz = 0; len = 1e-3F; }
                dx /= len; dy /= len; dz /= len;
                b.addVertex(p[i], p[i + 1], p[i + 2]).setColor(color[k]).setNormal(dx, dy, dz);
                b.addVertex(p[i + 3], p[i + 4], p[i + 5]).setColor(color[k]).setNormal(dx, dy, dz);
            }
            try (MeshData mesh = b.buildOrThrow()) {
                GpuBuffer buf = RenderSystem.getDevice().createBuffer(() -> "kine walk " + label, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                return new GpuLines(buf, n, Arrays.copyOf(width, n));
            }
        }
    }

    private static final class GpuLines implements Lines {
        private final GpuBuffer buffer;
        private final int lines;
        private final float[] width;   // each segment's, screen pixels
        private boolean closed = false;

        GpuLines(GpuBuffer buffer, int lines, float[] width) {
            this.buffer = buffer;
            this.lines = lines;
            this.width = width;
        }

        @Override
        public void draw(LineStyle style, Matrix4f pose, float dx, float dy, float dz, int first, int count, float alpha) {
            if (closed) return;
            RenderPipeline pipeline = switch (style) {
                case TESTED -> LINES;
                case STRICT -> LINES_STRICT;
                case SEE_THROUGH -> LINES_THROUGH;
            };
            Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose).translate(dx, dy, dz);
            Vector4f fade = new Vector4f(1.0F, 1.0F, 1.0F, alpha);
            // one draw for each run of segments of the same width, each with its own transforms (the line width is
            // in them); all written before the render pass opens, since writing maps a buffer
            int end = first + count;
            int runs = 0;
            for (int k = first; k < end; k++) if (k == first || width[k] != width[k - 1]) runs++;
            int[] start = new int[runs + 1];
            DynamicUniforms.Transform[] t = new DynamicUniforms.Transform[runs];
            for (int k = first, r = 0; k < end; k++) {
                if (k != first && width[k] == width[k - 1]) continue;
                start[r] = k;
                t[r++] = new DynamicUniforms.Transform(mv, fade, new Vector3f(), new Matrix4f(), width[k]);
            }
            start[runs] = end;
            GpuBufferSlice[] transforms = RenderSystem.getDynamicUniforms().writeTransforms(t);
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.LINES);
            GpuBuffer ib = indices.getBuffer(count * 6);
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
            GpuTextureView depth = target.useDepth
                ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView())
                : null;
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "kine walk lines", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setVertexBuffer(0, buffer);
                pass.setIndexBuffer(ib, indices.type());
                for (int r = 0; r < runs; r++) {
                    pass.setUniform("DynamicTransforms", transforms[r]);
                    pass.drawIndexed(start[r] * 4, 0, (start[r + 1] - start[r]) * 6, 1);   // four vertices a segment
                }
            }
        }

        @Override
        public void close() {
            if (!closed) { closed = true; buffer.close(); }
        }
    }
}
