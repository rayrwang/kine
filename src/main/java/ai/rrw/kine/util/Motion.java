package ai.rrw.kine.util;

import ai.rrw.kine.autoflight.FlightModel3D;
import net.minecraft.world.entity.Entity;

/** An entity's motion from its last tick's position change, the most reliable velocity for the local
 *  player (getDeltaMovement() reads low for it). */
public final class Motion {
    private Motion() {}

    /** Horizontal speed in m/s. */
    public static double groundSpeed(Entity e) {
        double dx = e.getX() - e.xOld, dz = e.getZ() - e.zOld;
        return Math.sqrt(dx * dx + dz * dz) * 20.0;
    }

    /** Heading of the ground track as a Minecraft yaw (degrees), or `fallback` when barely moving. */
    public static double trackYaw(Entity e, double fallback) {
        double dx = e.getX() - e.xOld, dz = e.getZ() - e.zOld;
        return (dx * dx + dz * dz > 1.0e-8) ? FlightModel3D.velYaw(dx, dz) : fallback;
    }
}
