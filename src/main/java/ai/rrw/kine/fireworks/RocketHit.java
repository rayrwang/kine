package ai.rrw.kine.fireworks;

/**
 * Whether a firework rocket used while already flying fast hits its own player on the server, exploding a few ticks
 * into its burn (RW's takeoff rocket spam, 2026-10-11, found with server-side logging in his world). From the 26.1.2
 * code: a rocket is spawned at the player's position with a speed of 0.05, so if the player has moved more than about
 * 1.4 blocks by its first tick, the server counts it as having left its owner (Projectile.isOutsideOwnerCollisionRange)
 * and from then on the player is a target it can hit. Each tick the attached rocket sits at the hand
 * (Entity.getHandHoldingItemAngle: half a block from the feet, at the player's yaw plus 80 degrees for the right arm,
 * minus 80 for the left) and traces one tick along the player's velocity (ProjectileUtil.getHitResultOnMoveVector)
 * against the player's box, 0.6 a side while gliding, grown by ProjectileUtil.computeMargin with the rocket's age;
 * AABB.clip counts only a face the path enters, so a path starting inside never hits. Whether the path enters depends on
 * the heading against the world's axes: with the right arm it does at yaws of about 63 to 84 degrees and every 90 from
 * there, with the left about 6 to 27 and every 90, never with both at once, never straight up, never at exact
 * cardinal yaws. The velocity is the rocket's boost of it, v = 0.5 v + 0.85 look a tick, from a fast start.
 */
public final class RocketHit {
    private RocketHit() {}

    private static final int    TICKS        = 40;        // the rocket's life checked (the hits come at ticks 3 and 4)
    private static final double HALF         = 0.3;       // the gliding player's box: 0.6 a side, 0.6 tall, from the feet
    private static final double HEIGHT       = 0.6;
    private static final double HAND         = 0.5;       // the hand's distance from the feet
    private static final double[] SPEEDS     = { 1.2, 1.5, 1.7 };   // speeds (blocks a tick) at the use, the boost's top 1.7
    public  static final double MARGIN_DEG   = 3.0;       // a heading counts as safe only this far from any that hits

    /** Whether a rocket used at this look (yaw and pitch in degrees, Minecraft's, pitch negative up) and held in the
     *  right arm or the left hits the player, at any of a few fast speeds at the use. */
    public static boolean hits(double yaw, double pitch, boolean rightArm) {
        for (double speed : SPEEDS) if (hitTick(yaw, pitch, rightArm, speed) >= 0) return true;
        return false;
    }

    /** The rocket's life at the hit, or -1 if it never hits within TICKS: one speed at the use along the look. */
    static int hitTick(double yaw, double pitch, boolean rightArm, double speed) {
        double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
        double lx = -Math.sin(ry) * Math.cos(rp), ly = -Math.sin(rp), lz = Math.cos(ry) * Math.cos(rp);
        double ha = Math.toRadians(yaw + (rightArm ? 80.0 : -80.0));
        double fx = -Math.sin(ha) * HAND, fy = 0.0, fz = Math.cos(ha) * HAND;
        double vx = lx * speed, vy = ly * speed, vz = lz * speed;
        for (int n = 1; n <= TICKS; n++) {
            vx = 0.5 * vx + 0.85 * lx; vy = 0.5 * vy + 0.85 * ly; vz = 0.5 * vz + 0.85 * lz;
            double m = Math.max(0.0, Math.min(0.3, (n - 2) / 20.0));   // computeMargin at the rocket's tick count
            if (enters(-HALF - m, -m, -HALF - m, HALF + m, HEIGHT + m, HALF + m, fx, fy, fz, vx, vy, vz)) return n - 1;
        }
        return -1;
    }

    // AABB.clip's test: the segment from f along d (s in 0 to 1) crosses a face it moves toward, inside that face.
    private static boolean enters(double x0, double y0, double z0, double x1, double y1, double z1,
                                  double fx, double fy, double fz, double dx, double dy, double dz) {
        return face(dx, fx, fy, fz, dy, dz, x0, x1, y0, y1, z0, z1)
            || face(dy, fy, fz, fx, dz, dx, y0, y1, z0, z1, x0, x1)
            || face(dz, fz, fx, fy, dx, dy, z0, z1, x0, x1, y0, y1);
    }

    private static boolean face(double da, double fa, double fb, double fc, double db, double dc,
                                double a0, double a1, double b0, double b1, double c0, double c1) {
        if (Math.abs(da) <= 1.0e-7) return false;
        double s = ((da > 0 ? a0 : a1) - fa) / da;
        if (!(0.0 < s && s < 1.0)) return false;
        double pb = fb + s * db, pc = fc + s * dc;
        return b0 - 1.0e-7 < pb && pb < b1 + 1.0e-7 && c0 - 1.0e-7 < pc && pc < c1 + 1.0e-7;
    }

    /** Whether no look within MARGIN_DEG of this yaw hits, at any of the pitches. */
    public static boolean safe(double yaw, double[] pitches, boolean rightArm) {
        for (double pitch : pitches)
            for (double off = -MARGIN_DEG; off <= MARGIN_DEG; off += 0.5)
                if (hits(yaw + off, pitch, rightArm)) return false;
        return true;
    }

    /** The nearest yaw to the one wanted that is safe at all the pitches: within 45 degrees, since exact cardinal
     *  yaws never hit. */
    public static float safeYaw(float wanted, double[] pitches, boolean rightArm) {
        for (double off = 0.0; off <= 45.0; off += 0.5) {
            if (safe(wanted + off, pitches, rightArm)) return (float) (wanted + off);
            if (off > 0.0 && safe(wanted - off, pitches, rightArm)) return (float) (wanted - off);
        }
        return Math.round(wanted / 90f) * 90f;   // a cardinal yaw, never hit
    }
}
