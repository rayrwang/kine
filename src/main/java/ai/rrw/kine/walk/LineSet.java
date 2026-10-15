package ai.rrw.kine.walk;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.WorldDraw;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

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
            return new LineSet(Compat.world().upload(label, p, color, width, n), n, ox, oy, oz);
        }
    }

    final WorldDraw.Lines buffer;
    final int lines;
    final double ox, oy, oz;
    private boolean closed = false;

    private LineSet(WorldDraw.Lines buffer, int lines, double ox, double oy, double oz) {
        this.buffer = buffer;
        this.lines = lines;
        this.ox = ox; this.oy = oy; this.oz = oz;
    }

    /** Draw the first `count` segments in this style, faded by alpha (render thread, during the level render). */
    void draw(WorldDraw.LineStyle style, Matrix4f pose, Vec3 cam, int count, float alpha) {
        drawRange(style, pose, cam, 0, count, alpha);
    }

    /** Draw `count` segments from segment `first` (a step of a replay kept in one buffer). */
    void drawRange(WorldDraw.LineStyle style, Matrix4f pose, Vec3 cam, int first, int count, float alpha) {
        if (closed || count <= 0 || alpha <= 0.003F || first < 0 || first >= lines) return;
        count = Math.min(count, lines - first);
        buffer.draw(style, pose, (float) (ox - cam.x), (float) (oy - cam.y), (float) (oz - cam.z), first, count, alpha);
    }

    @Override
    public void close() {
        if (!closed) { closed = true; buffer.close(); }
    }
}
