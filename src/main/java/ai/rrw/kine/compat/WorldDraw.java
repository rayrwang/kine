package ai.rrw.kine.compat;

import org.joml.Matrix4f;

/**
 * Drawing in the world, the same on every release (versions/README.md): each epoch draws these with its own release's
 * rendering code. Positions are relative to the camera; a pass's pose is the level renderer's (its camera rotation).
 */
public interface WorldDraw {
    /** How a batch of quads meets the depth buffer. */
    enum QuadStyle {
        /** Tested against the depth buffer (less or equal), not written to it; translucent (the game's debug quads). */
        TESTED,
        /** Written to the depth buffer only, no colour, back faces culled (the game's water mask): after TESTED quads,
         *  so what the game draws later behind them (clouds) stays behind. */
        DEPTH_ONLY,
        /** Drawn over everything: no depth test, no culling. */
        SEE_THROUGH,
        /** Tested with a bias toward the camera and no culling, not written: strips lying on the ground. */
        GROUND
    }

    /** How a set of lines meets the depth buffer. */
    enum LineStyle {
        /** Tested (less or equal), written: the game's lines. */
        TESTED,
        /** Tested (strictly less), written: passes only where no line already lies at the same depth. */
        STRICT,
        /** Drawn over everything. */
        SEE_THROUGH
    }

    /** Something drawn every frame after the translucent terrain, in order of registration. */
    interface Pass {
        void draw(Matrix4f pose);
    }

    /** Register a pass (at start-up). */
    void afterTranslucentTerrain(Pass pass);

    /** A batch of quads being drawn (during a pass): four vertices a quad. */
    interface Quads {
        /** A vertex at a camera-relative position through the pass's pose; DEPTH_ONLY ignores the colour. */
        Quads vertex(Matrix4f pose, float x, float y, float z, int argb);
        /** Draw the batch. */
        void end();
    }

    /** Start a batch of quads (during a pass). */
    Quads quads(QuadStyle style);

    /** Line segments kept on the GPU, drawn whole or a run of them at a time. */
    interface Lines extends AutoCloseable {
        /** Draw count segments from first, moved by (dx, dy, dz) from their origin to the camera, faded by alpha
         *  (during a pass). */
        void draw(LineStyle style, Matrix4f pose, float dx, float dy, float dz, int first, int count, float alpha);
        @Override
        void close();
    }

    /** Upload n segments (render thread): xyz holds x0, y0, z0, x1, y1, z1 per segment, relative to the origin the
     *  draw moves them from; one colour and one width (screen pixels) per segment. */
    Lines upload(String label, float[] xyz, int[] argb, float[] width, int n);
}
