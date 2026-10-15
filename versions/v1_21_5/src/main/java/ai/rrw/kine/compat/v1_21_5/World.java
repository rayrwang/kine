package ai.rrw.kine.compat.v1_21_5;

import ai.rrw.kine.Kine;
import ai.rrw.kine.compat.WorldDraw;
import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Drawing in the world on 1.21.5: quads through the main pass's buffer source, lines from GPU buffers in their own
 * render pass, as on 26.1. The pipelines are 1.21.5's, with the depth tests of 26.1's: its debug quads write depth
 * where 26.1's don't, so the tested quads have a pipeline of their own. 1.21.5 fills a pass's model-view matrix,
 * colour modulator and line width from RenderSystem's global state as each draw is made (GlProgram.setDefaultUniforms),
 * and has no per-vertex line width: the lines move the model-view stack, set the colour and the width there, and draw
 * each run of segments of one width with its own draw call.
 */
final class World implements WorldDraw {
    // debug quads that don't write depth, tested less or equal and sorted, as 26.1's debug quads
    private static final RenderPipeline TESTED_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(ResourceLocation.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/tested_quads"))
        .withCull(false)
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false)
        .build());
    private static final RenderType TESTED = RenderType.create(
        "kine_tested_quads", 1536, false, true, TESTED_PIPELINE, RenderType.CompositeState.builder().createCompositeState(false));

    // debug quads with no depth test, for faint copies (the game has no position-colour quads without one)
    private static final RenderPipeline SEE_THROUGH_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(ResourceLocation.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/see_through_quads"))
        .withCull(false)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)
        .build());
    private static final RenderType SEE_THROUGH = RenderType.create(
        "kine_see_through_quads", 1536, false, true, SEE_THROUGH_PIPELINE, RenderType.CompositeState.builder().createCompositeState(false));

    // strips on the ground: depth-tested against the terrain with a bias toward the camera (as the game's own lines
    // with depth bias), so the ground they lie on doesn't break them up far away; no depth write, both faces
    private static final RenderPipeline GROUND_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(ResourceLocation.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_rails"))
        .withCull(false)
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false).withDepthBias(-1.0F, -1.0F)
        .build());
    private static final RenderType GROUND = RenderType.create(
        "kine_walk_rails", 1536, false, false, GROUND_PIPELINE, RenderType.CompositeState.builder().createCompositeState(false));

    // depth-tested lines; strict ones pass only where no line is already at the same depth; see-through ones untested
    private static final RenderPipeline LINES = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(ResourceLocation.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines")).build());
    private static final RenderPipeline LINES_STRICT = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(ResourceLocation.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines_strict"))
        .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST).withDepthWrite(true).build());
    private static final RenderPipeline LINES_THROUGH = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(ResourceLocation.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines_through"))
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).build());

    @Override
    public void afterTranslucentTerrain(Pass pass) {
        // the end of the main pass (Fabric's LAST before 1.21.9): right after the translucent terrain, the tripwires and
        // the block outline; its pose stack is the main pass's (the camera's rotation is on RenderSystem's model-view
        // stack)
        WorldRenderEvents.LAST.register(c -> pass.draw(c.matrixStack().last().pose()));
    }

    @Override
    public Quads quads(QuadStyle style) {
        RenderType type = switch (style) {
            case TESTED -> TESTED;
            case DEPTH_ONLY -> RenderType.waterMask();
            case SEE_THROUGH -> SEE_THROUGH;
            case GROUND -> GROUND;
        };
        // the main pass's buffer source (the one the render context hands out as its consumers)
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
        // BufferBuilder writes each vertex of a line twice (the two corners of the quad the lines shader widens it to)
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(n * 4 * fmt.getVertexSize())) {
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
                GpuBuffer buf = RenderSystem.getDevice().createBuffer(() -> "kine walk " + label, BufferType.VERTICES,
                    BufferUsage.STATIC_WRITE, mesh.vertexBuffer());
                return new GpuLines(buf, java.util.Arrays.copyOf(width, n));
            }
        }
    }

    private static final class GpuLines implements Lines {
        private final GpuBuffer buffer;
        private final float[] width;   // each segment's, in screen pixels
        private boolean closed = false;

        GpuLines(GpuBuffer buffer, float[] width) {
            this.buffer = buffer;
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
            int end = first + count;
            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            float[] c = RenderSystem.getShaderColor();
            float r = c[0], g = c[1], b = c[2], a = c[3];
            float lineWidth = RenderSystem.getShaderLineWidth();
            modelView.pushMatrix();
            try {
                modelView.mul(pose).translate(dx, dy, dz);
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
                RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.LINES);
                GpuBuffer ib = indices.getBuffer(end * 6);   // six indices a segment, from the start of the buffer
                RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        target.getColorTexture(), OptionalInt.empty(), target.useDepth ? target.getDepthTexture() : null, OptionalDouble.empty())) {
                    pass.setPipeline(pipeline);
                    pass.setVertexBuffer(0, buffer);
                    pass.setIndexBuffer(ib, indices.type());
                    for (int s = first; s < end; ) {
                        int e = s + 1;
                        while (e < end && width[e] == width[s]) e++;
                        RenderSystem.lineWidth(width[s]);
                        pass.drawIndexed(s * 6, (e - s) * 6);
                        s = e;
                    }
                }
            } finally {
                RenderSystem.lineWidth(lineWidth);
                RenderSystem.setShaderColor(r, g, b, a);
                modelView.popMatrix();
            }
        }

        @Override
        public void close() {
            if (!closed) { closed = true; buffer.close(); }
        }
    }
}
