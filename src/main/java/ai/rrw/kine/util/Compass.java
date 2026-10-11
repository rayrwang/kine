package ai.rrw.kine.util;

/** Minecraft yaw is measured from south (0 = +Z); a compass heading from north (0 = north, 90 = east). */
public final class Compass {
    private Compass() {}

    /** Compass heading in [0, 360) for a Minecraft yaw, in double arithmetic. */
    public static double heading(double yaw) {
        return (((yaw + 180.0) % 360.0) + 360.0) % 360.0;
    }

    /** The same in float arithmetic (the nav screen's dial works in float). */
    public static float headingF(float yaw) {
        return ((yaw + 180f) % 360f + 360f) % 360f;
    }
}
