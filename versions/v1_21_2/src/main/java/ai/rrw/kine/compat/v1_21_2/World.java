package ai.rrw.kine.compat.v1_21_2;

import ai.rrw.kine.compat.WorldDraw;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * Drawing in the world on 1.21.2 to 1.21.4, before render pipelines: quads through the main buffer source with render
 * types of kine's own where the game's differ from 26.1's, lines from vertex buffers kept on the GPU, drawn with the
 * game's line shader. Each style sets the GL state 26.1's pipeline of the same name has (depth test and write,
 * depth bias, blending, culling); the game's render state convention (RenderStateShard) is put back after each draw:
 * no depth test, depth function less or equal, depth writes on, no blending, back faces culled.
 */
final class World implements WorldDraw {
    private static final int GL_LESS = 513, GL_LEQUAL = 515, NO_DEPTH_TEST = 0;

    // 26.1's debug quads (RenderTypes.debugQuads): position and colour, translucent, both faces, tested less or equal,
    // not written to the depth buffer, sorted far to near. 1.21.4's own debug quads write depth, so kine has its own.
    private static final RenderType TESTED = quadType("kine_tested_quads", true, GL_LEQUAL, false);

    // debug quads with no depth test, for faint copies
    private static final RenderType SEE_THROUGH = quadType("kine_see_through_quads", true, NO_DEPTH_TEST, false);

    // strips on the ground: depth-tested against the terrain with a bias toward the camera (26.1's depth bias -1, -1
    // is glPolygonOffset(-1, -1)), so the ground they lie on doesn't break them up far away; no depth write, both faces
    private static final RenderType GROUND = quadType("kine_walk_rails", false, GL_LEQUAL, true);

    /** A position-colour quad type: translucent, no culling, no depth write; tested with this depth function (or not
     *  at all), with or without the bias. */
    private static RenderType quadType(String name, boolean sort, int depthFunc, boolean bias) {
        return new RenderType(name, DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 1536, false, sort, () -> {
            RenderSystem.setShader(CoreShaders.POSITION_COLOR);
            translucent();
            RenderSystem.disableCull();
            if (depthFunc == NO_DEPTH_TEST) RenderSystem.disableDepthTest();
            else { RenderSystem.enableDepthTest(); RenderSystem.depthFunc(depthFunc); }
            RenderSystem.depthMask(false);
            if (bias) { RenderSystem.polygonOffset(-1.0F, -1.0F); RenderSystem.enablePolygonOffset(); }
        }, () -> {
            if (bias) { RenderSystem.polygonOffset(0.0F, 0.0F); RenderSystem.disablePolygonOffset(); }
            putBack();
        }) {};
    }

    /** 26.1's translucent blend (BlendFunction.TRANSLUCENT, the game's TRANSLUCENT_TRANSPARENCY). */
    private static void translucent() {
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }

    /** The state the game's render types leave behind. */
    private static void putBack() {
        RenderSystem.depthMask(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthFunc(GL_LEQUAL);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    @Override
    public void afterTranslucentTerrain(Pass pass) {
        // Fabric's LAST on these releases: the end of the level renderer's main pass, right after the translucent
        // terrain, the tripwires and the block outline (where 26.1's AFTER_TRANSLUCENT_TERRAIN and 1.21.11's END_MAIN
        // draw). The pose is the main pass's own stack, the identity; the camera's rotation is in the model-view.
        WorldRenderEvents.LAST.register(c -> {
            PoseStack stack = c.matrixStack();
            pass.draw(stack != null ? stack.last().pose() : new Matrix4f());
        });
    }

    @Override
    public Quads quads(QuadStyle style) {
        RenderType type = switch (style) {
            case TESTED -> TESTED;
            case DEPTH_ONLY -> RenderType.waterMask();   // depth only, back faces culled, as 26.1's water mask
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

    /** The segments as the game's lines: two vertices each with the direction as the normal, which the vertex buffer
     *  makes four (the line shader widens each segment into a quad facing the camera). Before 1.21.5 the width is the
     *  shader's LineWidth uniform, not a vertex attribute, so it is kept here and set per run of equal widths. */
    @Override
    public Lines upload(String label, float[] p, int[] color, float[] width, int n) {
        VertexFormat fmt = DefaultVertexFormat.POSITION_COLOR_NORMAL;
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
            MeshData mesh = b.buildOrThrow();
            VertexBuffer buf = new VertexBuffer(BufferUsage.STATIC_WRITE);
            buf.bind();
            buf.upload(mesh);   // closes the mesh; binds the shared sequential index buffer to this buffer's array
            VertexBuffer.unbind();
            return new GpuLines(buf, n, java.util.Arrays.copyOf(width, n));
        }
    }

    private static final class GpuLines implements Lines {
        private final VertexBuffer buffer;
        private final int lines;
        private final float[] width;
        private boolean closed = false;

        GpuLines(VertexBuffer buffer, int lines, float[] width) {
            this.buffer = buffer;
            this.lines = lines;
            this.width = width;
        }

        @Override
        public void draw(LineStyle style, Matrix4f pose, float dx, float dy, float dz, int first, int count, float alpha) {
            if (closed || count <= 0) return;
            int end = Math.min(first + count, lines);
            CompiledShaderProgram shader = RenderSystem.setShader(CoreShaders.RENDERTYPE_LINES);
            if (shader == null) return;
            Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose).translate(dx, dy, dz);
            // 26.1's lines pipelines: translucent, both faces; tested (less or equal) and written, strictly less and
            // written, or untested
            translucent();
            RenderSystem.disableCull();
            switch (style) {
                case TESTED -> { RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL_LEQUAL); }
                case STRICT -> { RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL_LESS); }
                case SEE_THROUGH -> RenderSystem.disableDepthTest();
            }
            RenderSystem.depthMask(true);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
            // the index type of the shared sequential buffer this array uses (it grows to INT past 65536 vertices)
            VertexFormat.IndexType indexType = RenderSystem.getSequentialBuffer(VertexFormat.Mode.LINES).type();
            buffer.bind();
            for (int i = first; i < end; ) {
                float w = width[i];
                int j = i + 1;
                while (j < end && width[j] == w) j++;
                RenderSystem.lineWidth(w);
                shader.setDefaultUniforms(VertexFormat.Mode.LINES, mv, RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
                shader.apply();
                // six indices a segment (its quad's two triangles), from segment i
                GlStateManager._drawElements(VertexFormat.Mode.LINES.asGLMode, (j - i) * 6, indexType.asGLType, (long) i * 6 * indexType.bytes);
                shader.clear();
                i = j;
            }
            VertexBuffer.unbind();
            RenderSystem.lineWidth(1.0F);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            putBack();
        }

        @Override
        public void close() {
            if (!closed) { closed = true; buffer.close(); }
        }
    }
}
