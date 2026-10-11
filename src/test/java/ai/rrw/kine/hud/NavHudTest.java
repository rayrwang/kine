package ai.rrw.kine.hud;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The nav target beam is cut to the part in view before the perspective divide. */
class NavHudTest {
    static final int W = 480, H = 270;
    static final double BASE = -64, TOP = 320;   // the overworld beam

    // a view-rotation-projection like the camera's, looking along (fx, fy, fz) from the camera
    static Matrix4f view(double fx, double fy, double fz) {
        return new Matrix4f().perspective((float) Math.toRadians(70), W / (float) H, 0.05f, 1024f)
            .lookAt(0, 0, 0, (float) fx, (float) fy, (float) fz, 0, 1, 0);
    }

    // the screen ends of the beam over (tx, tz) seen from cam, or null; and whether the old check drew it
    static float[][] beam(Matrix4f vp, Vec3 cam, double tx, double tz) {
        Vector4f[] seg = NavHud.clipSegment(ScreenProjection.clip(vp, cam, tx, BASE, tz), ScreenProjection.clip(vp, cam, tx, TOP, tz));
        return seg == null ? null : new float[][]{ ScreenProjection.toScreen(seg[0], W, H), ScreenProjection.toScreen(seg[1], W, H) };
    }

    static boolean oldCheckDraws(Matrix4f vp, Vec3 cam, double tx, double tz) {
        return ScreenProjection.project(vp, cam, tx, BASE, tz, W, H, 0f) != null
            && ScreenProjection.project(vp, cam, tx, TOP, tz, W, H, 0f) != null;
    }

    static void assertOnScreen(float[][] s) {
        assertNotNull(s);
        for (float[] e : s) {   // the ends stay within the margin, not at huge coordinates
            assertTrue(e[0] >= -0.5f * W - 1 && e[0] <= 1.5f * W + 1 && e[1] >= -0.5f * H - 1 && e[1] <= 1.5f * H + 1,
                "end at " + e[0] + ", " + e[1]);
        }
        boolean crosses = false;
        for (int k = 0; k <= 100; k++) {
            float x = s[0][0] + (s[1][0] - s[0][0]) * k / 100f, y = s[0][1] + (s[1][1] - s[0][1]) * k / 100f;
            crosses |= x >= 0 && x <= W && y >= 0 && y <= H;
        }
        assertTrue(crosses, "the beam crosses the screen");
    }

    @Test
    void theBeamShowsInTheLandingSpiral() {
        // 2 blocks from the target column, 60 degrees nose down toward it, at y 300 and 100
        for (double y : new double[]{ 300, 100 }) {
            Vec3 cam = new Vec3(0, y, 0);
            Matrix4f vp = view(Math.cos(Math.toRadians(60)), -Math.sin(Math.toRadians(60)), 0);
            assertFalse(oldCheckDraws(vp, cam, 2, 0));
            assertOnScreen(beam(vp, cam, 2, 0));
        }
    }

    @Test
    void theBeamShowsLookingAtTheTargetFromCloseBy() {
        // 100 blocks out at y 150, looking at the target's ground at y 64
        Vec3 cam = new Vec3(0, 150, 0);
        Matrix4f vp = view(100, 64 - 150, 0);
        assertFalse(oldCheckDraws(vp, cam, 100, 0));
        assertOnScreen(beam(vp, cam, 100, 0));
    }

    @Test
    void aBeamBehindTheCameraIsNotDrawn() {
        Vec3 cam = new Vec3(0, 100, 0);
        assertNull(beam(view(-1, 0, 0), cam, 200, 0));
    }

    @Test
    void aBeamWhollyInViewIsDrawnEndToEnd() {
        // far off and looking level at it: nothing is cut
        Vec3 cam = new Vec3(0, 100, 0);
        Matrix4f vp = view(1, 0, 0);
        float[][] s = beam(vp, cam, 1000, 0);
        assertNotNull(s);
        float[] base = ScreenProjection.project(vp, cam, 1000, BASE, 0, W, H, 0f), top = ScreenProjection.project(vp, cam, 1000, TOP, 0, W, H, 0f);
        assertArrayEquals(base, s[0], 1e-3f);
        assertArrayEquals(top, s[1], 1e-3f);
    }
}
