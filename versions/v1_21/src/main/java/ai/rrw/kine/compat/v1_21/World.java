package ai.rrw.kine.compat.v1_21;

import ai.rrw.kine.compat.WorldDraw;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/**
 * Drawing in the world on 1.21 and 1.21.1, before the game had render pipelines: quads through render types made
 * like the game's own, lines from vertex buffers kept on the GPU, drawn with the game's line shader. Each style has
 * the depth test, depth writes, depth bias, blending and culling of 26.1's pipeline for it (WorldDraw's styles).
 *
 * <p>The passes run at Fabric's AFTER_TRANSLUCENT (LevelRenderer.renderLevel, after the translucent terrain, the
 * tripwires and the particles, before the clouds and the weather). There the game's model-view matrix is the camera
 * rotation and Fabric's pose stack is empty; on 26.1 the model-view matrix is the identity and the pass's pose is the
 * camera rotation, so the pass is given that pose and runs with an identity model-view, and every draw meets the
 * same matrices as there.
 */
final class World implements WorldDraw {
    // 26.1's DEBUG_FILLED_SNIPPET: position and colour, translucent blending (RenderStateShard.TRANSLUCENT_TRANSPARENCY
    // is 26.1's BlendFunction.TRANSLUCENT), depth tested (less or equal) and not written

    // TESTED: the debug quads of 26.1 (both faces). 1.21.1's own debug quads write depth, so these are kine's.
    private static final RenderType TESTED = RenderType.create("kine_tested_quads", DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS, 1536, false, true, RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .createCompositeState(false));

    // SEE_THROUGH: no depth test (the game's "always" shard leaves the test as it finds it, so this one turns it off;
    // the pass puts it back), both faces, sorted as 26.1's
    private static final RenderStateShard.LayeringStateShard DEPTH_TEST_OFF = new RenderStateShard.LayeringStateShard(
        "kine_depth_test_off", RenderSystem::disableDepthTest, () -> {});
    private static final RenderType SEE_THROUGH = RenderType.create("kine_see_through_quads", DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS, 1536, false, true, RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setLayeringState(DEPTH_TEST_OFF)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .createCompositeState(false));

    // GROUND: strips on the ground, depth-tested against the terrain with a bias toward the camera (26.1's depth bias
    // -1, -1 is glPolygonOffset(-1, -1)), so the ground they lie on doesn't break them up far away; no depth write,
    // both faces
    private static final RenderStateShard.LayeringStateShard GROUND_BIAS = new RenderStateShard.LayeringStateShard(
        "kine_ground_bias", () -> {
            RenderSystem.polygonOffset(-1.0F, -1.0F);
            RenderSystem.enablePolygonOffset();
        }, () -> {
            RenderSystem.polygonOffset(0.0F, 0.0F);
            RenderSystem.disablePolygonOffset();
        });
    private static final RenderType GROUND = RenderType.create("kine_walk_rails", DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS, 1536, false, false, RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setLayeringState(GROUND_BIAS)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .createCompositeState(false));

    // DEPTH_ONLY: the game's water mask, as on 26.1 (depth written, no colour, back faces culled)

    @Override
    public void afterTranslucentTerrain(Pass pass) {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(c -> {
            boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.identity();
            RenderSystem.applyModelViewMatrix();
            try {
                pass.draw(new Matrix4f(c.positionMatrix()));
            } finally {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
                if (depthTest) RenderSystem.enableDepthTest();
                else RenderSystem.disableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
            }
        });
    }

    @Override
    public Quads quads(QuadStyle style) {
        RenderType type = switch (style) {
            case TESTED -> TESTED;
            case DEPTH_ONLY -> RenderType.waterMask();
            case SEE_THROUGH -> SEE_THROUGH;
            case GROUND -> GROUND;
        };
        // the main buffer source (the one the render context hands out as its consumers)
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
        // a segment is four vertices (BufferBuilder doubles each line vertex; the line shader moves the two copies to
        // either side) and six indices, as the game's sequential line indices have them
        VertexFormat.IndexType indexType = VertexFormat.IndexType.least(n * 4);
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(Math.max(1, n * 4 * fmt.getVertexSize()))) {
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
            MeshData mesh = b.build();
            if (mesh != null) buffer.upload(mesh);   // closes the mesh
        }
        // its own index buffer, so the type and the offsets of a run's draw stay this buffer's (the game's shared
        // sequential one may grow to wider indices later)
        try (ByteBufferBuilder indices = new ByteBufferBuilder(Math.max(1, n * 6 * indexType.bytes))) {
            for (int k = 0; k < n; k++) {
                int v = k * 4;
                int[] six = {v, v + 1, v + 2, v + 3, v + 2, v + 1};
                long at = indices.reserve(6 * indexType.bytes);
                for (int j = 0; j < 6; j++) {
                    if (indexType == VertexFormat.IndexType.SHORT) MemoryUtil.memPutShort(at + 2L * j, (short) six[j]);
                    else MemoryUtil.memPutInt(at + 4L * j, six[j]);
                }
            }
            ByteBufferBuilder.Result result = indices.build();
            if (result != null) buffer.uploadIndexBuffer(result);   // closes the result
        }
        VertexBuffer.unbind();
        return new GpuLines(buffer, n, width.clone(), indexType);
    }

    private static final class GpuLines implements Lines {
        private final VertexBuffer buffer;
        private final int lines;
        private final float[] width;
        private final VertexFormat.IndexType indexType;
        private boolean closed = false;

        GpuLines(VertexBuffer buffer, int lines, float[] width, VertexFormat.IndexType indexType) {
            this.buffer = buffer;
            this.lines = lines;
            this.width = width;
            this.indexType = indexType;
        }

        @Override
        public void draw(LineStyle style, Matrix4f pose, float dx, float dy, float dz, int first, int count, float alpha) {
            if (closed) return;
            int end = Math.min(lines, first + count);
            if (first < 0 || first >= end) return;
            ShaderInstance shader = GameRenderer.getRendertypeLinesShader();
            if (shader == null) return;
            Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose).translate(dx, dy, dz);
            // 26.1's LINES_SNIPPET: translucent blending, both faces; the depth by style
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.disableCull();
            switch (style) {
                case TESTED -> {   // less or equal, written (the game's lines)
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(GL11.GL_LEQUAL);
                    RenderSystem.depthMask(true);
                }
                case STRICT -> {   // strictly less, written: only where no line already lies at that depth
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(GL11.GL_LESS);
                    RenderSystem.depthMask(true);
                }
                case SEE_THROUGH -> RenderSystem.disableDepthTest();
            }
            buffer.bind();
            shader.setDefaultUniforms(VertexFormat.Mode.LINES, mv, RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
            if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(1.0F, 1.0F, 1.0F, alpha);
            // the line width is a uniform here (a vertex attribute on 26.1): one draw per run of equal widths
            for (int k = first; k < end; ) {
                float w = width[k];
                int j = k + 1;
                while (j < end && width[j] == w) j++;
                if (shader.LINE_WIDTH != null) shader.LINE_WIDTH.set(w);
                shader.apply();
                GlStateManager._drawElements(VertexFormat.Mode.LINES.asGLMode, (j - k) * 6, indexType.asGLType, (long) k * 6 * indexType.bytes);
                k = j;
            }
            shader.clear();
            VertexBuffer.unbind();
            // back to the state the game's render types leave behind
            RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }

        @Override
        public void close() {
            if (!closed) { closed = true; buffer.close(); }
        }
    }
}
