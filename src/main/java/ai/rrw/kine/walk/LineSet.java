package ai.rrw.kine.walk;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * A set of line segments built once into a GPU vertex buffer and drawn every frame with one draw call, of all of them
 * or of the first n (a replay grows by drawing a longer prefix). Lines are the game's own (the lines pipeline: a width
 * in screen pixels, each segment a quad turned to face the camera). Positions are kept relative to an origin near them,
 * so the floats stay exact far from the world's centre; a frame's draw moves them into place with the model-view
 * matrix, and fades them with the colour modulator.
 *
 * <p>The geometry a layer draws (the search tree, the grid and tightened paths, and for layer 2 later the simulated
 * runs and jump arcs as polylines) is all segments, so any layer is one or more LineSets.
 */
final class LineSet implements AutoCloseable {
    /** Segments being collected: x0, y0, z0, x1, y1, z1 (world), colour, width. */
    static final class Builder {
        float[] p = new float[6 * 256];
        int[] color = new int[256];
        float[] width = new float[256];
        int n = 0;
        final double ox, oy, oz;

        Builder(double ox, double oy, double oz) {
            this.ox = ox; this.oy = oy; this.oz = oz;
        }

        void add(double x0, double y0, double z0, double x1, double y1, double z1, int argb, float w) {
            if (n == color.length) {
                p = java.util.Arrays.copyOf(p, n * 12);
                color = java.util.Arrays.copyOf(color, n * 2);
                width = java.util.Arrays.copyOf(width, n * 2);
            }
            int i = n * 6;
            p[i] = (float) (x0 - ox); p[i + 1] = (float) (y0 - oy); p[i + 2] = (float) (z0 - oz);
            p[i + 3] = (float) (x1 - ox); p[i + 4] = (float) (y1 - oy); p[i + 5] = (float) (z1 - oz);
            color[n] = argb;
            width[n] = w;
            n++;
        }

        /** A polyline through the points (x, y, z triples). */
        void polyline(double[] xyz, int count, int argb, float w) {
            for (int i = 1; i < count; i++)
                add(xyz[3 * i - 3], xyz[3 * i - 2], xyz[3 * i - 1], xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2], argb, w);
        }

        /** Upload to the GPU (render thread). Null when empty. */
        LineSet build(String label) {
            if (n == 0) return null;
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
                    return new LineSet(buf, n, ox, oy, oz);
                }
            }
        }
    }

    final GpuBuffer buffer;
    final int lines;
    final double ox, oy, oz;
    private boolean closed = false;

    private LineSet(GpuBuffer buffer, int lines, double ox, double oy, double oz) {
        this.buffer = buffer;
        this.lines = lines;
        this.ox = ox; this.oy = oy; this.oz = oz;
    }

    /** Draw the first `count` segments with this pipeline, faded by alpha (render thread, during the level render). */
    void draw(RenderPipeline pipeline, Matrix4f pose, Vec3 cam, int count, float alpha) {
        drawRange(pipeline, pose, cam, 0, count, alpha);
    }

    /** Draw `count` segments from segment `first` (a step of a replay kept in one buffer). */
    void drawRange(RenderPipeline pipeline, Matrix4f pose, Vec3 cam, int first, int count, float alpha) {
        if (closed || count <= 0 || alpha <= 0.003F || first < 0 || first >= lines) return;
        count = Math.min(count, lines - first);
        Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose)
            .translate((float) (ox - cam.x), (float) (oy - cam.y), (float) (oz - cam.z));
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
