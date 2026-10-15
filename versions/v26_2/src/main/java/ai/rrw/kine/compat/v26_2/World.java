package ai.rrw.kine.compat.v26_2;

import ai.rrw.kine.Kine;
import ai.rrw.kine.compat.WorldDraw;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
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
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Drawing in the world on 26.2. The level render context no longer hands out a buffer source, so quads are built here
 * and drawn in their own render pass, as 26.1's RenderType.draw did (the debug quads sorted back to front, as their
 * render type did). 26.2 also reversed the depth buffer (near is 1, far 0): every depth test that was "less" is
 * "greater" here and the depth bias goes the other way, so the pipelines below are 26.1's with those turned round.
 */
final class World implements WorldDraw {
    // the game's own debug quads and water mask (26.1's RenderTypes.debugQuads() and waterMask())
    private static final RenderPipeline TESTED = RenderPipelines.DEBUG_QUADS;
    private static final RenderPipeline DEPTH_ONLY = RenderPipelines.WATER_MASK;

    // debug quads with no depth test, for faint copies (the game has no position-colour quads without one)
    private static final RenderPipeline SEE_THROUGH = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/see_through_quads"))
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .build());

    // strips on the ground: depth-tested with a bias toward the camera, no depth write, both faces
    private static final RenderPipeline GROUND = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_rails"))
        .withCull(false)
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, 1.0F, 1.0F))
        .build());

    // lines: the game's (tested, written), strict (only where no line is at the same depth), and see-through
    private static final RenderPipeline LINES = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines")).build());
    private static final RenderPipeline LINES_STRICT = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines_strict"))
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, true)).build());
    private static final RenderPipeline LINES_THROUGH = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines_through"))
        .withDepthStencilState(Optional.empty()).build());

    private final ByteBufferBuilder vertices = new ByteBufferBuilder(1 << 16);
    private final ByteBufferBuilder indices = new ByteBufferBuilder(1 << 12);

    @Override
    public void afterTranslucentTerrain(Pass pass) {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(c -> pass.draw(c.poseStack().last().pose()));
    }

    @Override
    public Quads quads(QuadStyle style) {
        RenderPipeline pipeline = switch (style) {
            case TESTED -> TESTED;
            case DEPTH_ONLY -> DEPTH_ONLY;
            case SEE_THROUGH -> SEE_THROUGH;
            case GROUND -> GROUND;
        };
        boolean colour = style != QuadStyle.DEPTH_ONLY;
        boolean sorted = style == QuadStyle.TESTED || style == QuadStyle.SEE_THROUGH;   // as their render types sorted
        BufferBuilder b = new BufferBuilder(vertices, PrimitiveTopology.QUADS,
            colour ? DefaultVertexFormat.POSITION_COLOR : DefaultVertexFormat.POSITION);
        return new Quads() {
            @Override
            public Quads vertex(Matrix4f pose, float x, float y, float z, int argb) {
                if (colour) b.addVertex(pose, x, y, z).setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
                else b.addVertex(pose, x, y, z);
                return this;
            }

            @Override
            public void end() {
                MeshData mesh = b.build();
                if (mesh == null) return;
                try (mesh) {
                    if (sorted) mesh.sortQuads(indices, RenderSystem.getProjectionType().vertexSorting());
                    draw(pipeline, mesh);
                }
            }
        };
    }

    /** One mesh in its own render pass on the main target, with the level's model-view (26.1's RenderType.draw). */
    private static void draw(RenderPipeline pipeline, MeshData mesh) {
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
        GpuBuffer vb = RenderSystem.getDevice().createBuffer(() -> "kine quads", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
        GpuBuffer ib;
        IndexType indexType;
        boolean ownIndices = mesh.indexBuffer() != null;
        if (ownIndices) {
            ib = RenderSystem.getDevice().createBuffer(() -> "kine quad indices", GpuBuffer.USAGE_INDEX, mesh.indexBuffer());
            indexType = mesh.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer auto = RenderSystem.getSequentialBuffer(mesh.drawState().primitiveTopology());
            ib = auto.getBuffer(mesh.drawState().indexCount());
            indexType = auto.type();
        }
        try {
            RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
            GpuTextureView depth = target.useDepth
                ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView())
                : null;
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "kine quads", color, Optional.empty(), depth, OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, vb.slice());
                pass.setIndexBuffer(ib, indexType);
                pass.drawIndexed(mesh.drawState().indexCount(), 1, 0, 0, 0);
            }
        } finally {
            vb.close();
            if (ownIndices) ib.close();
        }
    }

    @Override
    public Lines upload(String label, float[] p, int[] color, float[] width, int n) {
        VertexFormat fmt = DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH;
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(n * 4 * fmt.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.LINES, fmt);
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
            Matrix4f mv = RenderSystem.getModelViewMatrixCopy().mul(pose).translate(dx, dy, dz);
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                .writeTransform(mv, new Vector4f(1.0F, 1.0F, 1.0F, alpha), new Vector3f(), new Matrix4f());
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.LINES);
            int indexCount = count * 6;
            GpuBuffer ib = indices.getBuffer(indexCount);
            RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
            GpuTextureView depth = target.useDepth
                ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView())
                : null;
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "kine walk lines", color, Optional.empty(), depth, OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, buffer.slice());
                pass.setIndexBuffer(ib, indices.type());
                pass.drawIndexed(indexCount, 1, 0, first * 4, 0);   // four vertices a segment
            }
        }

        @Override
        public void close() {
            if (!closed) { closed = true; buffer.close(); }
        }
    }
}
