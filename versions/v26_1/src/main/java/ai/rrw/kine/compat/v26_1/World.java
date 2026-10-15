package ai.rrw.kine.compat.v26_1;

import ai.rrw.kine.Kine;
import ai.rrw.kine.compat.WorldDraw;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Drawing in the world on 26.1 to 26.1.2: quads through the level render context's buffer source, lines from GPU
 *  buffers in their own render pass. */
final class World implements WorldDraw {
    // debug quads with no depth test, for faint copies (the game has no position-colour quads without one)
    private static final RenderPipeline SEE_THROUGH_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/see_through_quads"))
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .build());
    private static final RenderType SEE_THROUGH = RenderType.create(
        "kine_see_through_quads", RenderSetup.builder(SEE_THROUGH_PIPELINE).sortOnUpload().createRenderSetup());

    // strips on the ground: depth-tested against the terrain with a bias toward the camera (as the game's own lines
    // with depth bias), so the ground they lie on doesn't break them up far away; no depth write, both faces
    private static final RenderPipeline GROUND_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_rails"))
        .withCull(false)
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false, -1.0F, -1.0F))
        .build());
    private static final RenderType GROUND = RenderType.create("kine_walk_rails", RenderSetup.builder(GROUND_PIPELINE).createRenderSetup());

    // depth-tested lines; strict ones pass only where no line is already at the same depth; see-through ones untested
    private static final RenderPipeline LINES = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines")).build());
    private static final RenderPipeline LINES_STRICT = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines_strict"))
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN, true)).build());
    private static final RenderPipeline LINES_THROUGH = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines_through"))
        .withDepthStencilState(Optional.empty()).build());

    /** The pass running now (its buffer source draws the quads). */
    private LevelRenderContext ctx;

    @Override
    public void afterTranslucentTerrain(Pass pass) {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(c -> {
            ctx = c;
            try {
                pass.draw(c.poseStack().last().pose());
            } finally {
                ctx = null;
            }
        });
    }

    @Override
    public Quads quads(QuadStyle style) {
        RenderType type = switch (style) {
            case TESTED -> RenderTypes.debugQuads();
            case DEPTH_ONLY -> RenderTypes.waterMask();
            case SEE_THROUGH -> SEE_THROUGH;
            case GROUND -> GROUND;
        };
        LevelRenderContext c = ctx;
        VertexConsumer vc = c.bufferSource().getBuffer(type);
        boolean colour = style != QuadStyle.DEPTH_ONLY;
        return new Quads() {
            @Override
            public Quads vertex(Matrix4f pose, float x, float y, float z, int argb) {
                if (colour) vc.addVertex(pose, x, y, z).setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
                else vc.addVertex(pose, x, y, z);
                return this;
            }

            @Override
            public void end() { c.bufferSource().endBatch(type); }
        };
    }

    @Override
    public Lines upload(String label, float[] p, int[] color, float[] width, int n) {
        VertexFormat fmt = DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH;
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(n * 4 * fmt.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.LINES, fmt);
            for (int k = 0; k < n; k++) {
                int i = k * 6;
                float dx = p[i + 3] - p[i], dy = p[i + 4] - p[i + 1], dz = p[i + 5] - p[i + 2];
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-6F) { dx = 0; dy = 1e-3F; dz = 0; len = 1e-3F; }
                dx /= len; dy /= len; dz /= len;
                b.addVertex(p[i], p[i + 1], p[i + 2]).setColor(color[k]).setNormal(dx, dy, dz).setLineWidth(width[k]);
                b.addVertex(p[i + 3], p[i + 4], p[i + 5]).setColor(color[k]).setNormal(dx, dy, dz).setLineWidth(width[k]);
            }
            try (MeshData mesh = b.buildOrThrow()) {
                GpuBuffer buf = RenderSystem.getDevice().createBuffer(() -> "kine walk " + label, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                return new GpuLines(buf, n);
            }
        }
    }

    private static final class GpuLines implements Lines {
        private final GpuBuffer buffer;
        private final int lines;
        private boolean closed = false;

        GpuLines(GpuBuffer buffer, int lines) {
            this.buffer = buffer;
            this.lines = lines;
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
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                .writeTransform(mv, new Vector4f(1.0F, 1.0F, 1.0F, alpha), new Vector3f(), new Matrix4f());
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.LINES);
            int indexCount = count * 6;
            GpuBuffer ib = indices.getBuffer(indexCount);
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
            GpuTextureView depth = target.useDepth
                ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView())
                : null;
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "kine walk lines", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, buffer);
                pass.setIndexBuffer(ib, indices.type());
                pass.drawIndexed(first * 4, 0, indexCount, 1);   // four vertices a segment
            }
        }

        @Override
        public void close() {
            if (!closed) { closed = true; buffer.close(); }
        }
    }
}
