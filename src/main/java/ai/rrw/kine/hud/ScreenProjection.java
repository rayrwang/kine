package ai.rrw.kine.hud;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** World points to GUI pixels through the camera's view-rotation-projection matrix. */
public final class ScreenProjection {
    private ScreenProjection() {}

    /** World point to clip space (before the perspective divide), relative to the camera at `cam`. */
    public static Vector4f clip(Matrix4f vp, Vec3 cam, double x, double y, double z) {
        Vector4f v = new Vector4f((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z), 1.0f);
        vp.transform(v);
        return v;
    }

    /** Clip-space point to GUI pixel (x, y). */
    public static float[] toScreen(Vector4f v, int w, int h) {
        return new float[]{ (v.x / v.w * 0.5f + 0.5f) * w, (1.0f - (v.y / v.w * 0.5f + 0.5f)) * h };
    }

    /** World point to GUI pixel, or null when its clip w is at or below minW (behind the camera). */
    public static float[] project(Matrix4f vp, Vec3 cam, double x, double y, double z, int w, int h, float minW) {
        Vector4f v = clip(vp, cam, x, y, z);
        if (v.w <= minW) return null;
        return toScreen(v, w, h);
    }
}
