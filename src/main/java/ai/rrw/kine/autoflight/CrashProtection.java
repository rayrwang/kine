package ai.rrw.kine.autoflight;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class CrashProtection {

    // tuning
    private static final double GAIN            = 0.4;   // allowed closing speed = (clearance - standoff) * GAIN
    private static final double WALL_STANDOFF   = 1.5;   // stop this far short of walls
    private static final double WALL_DROP_CAP   = 5.0;   // how far below to look for walls we'll descend into
    private static final double CEIL_STANDOFF   = 0.5;   // stop this far below ceilings
    private static final double SAFE_LAND       = 0.3;   // descent speed near the ground (blocks/tick), under 0.5 (see clamp)
    private static final double GROUND_FLARE    = 6.0;   // hard-limit descent to SAFE_LAND within this many blocks
    private static final double GROUND_GAIN     = 0.3;   // gentler ramp above the flare zone
    private static final double STEP            = 0.25;  // sweep granularity (blocks)
    private static final double MAX_LOOK        = 20.0;  // longest sweep (blocks)
    private static final double CUSHION_RANGE   = 8.0;   // hard-clamp fallDistance when ground is within this

    // Only removes velocity heading into a surface; never adds, turns or redirects it. An over-eager
    // detection can at worst slow you for a moment.
    public static Vec3 clamp(LivingEntity p, Vec3 v) {
        Level level = p.level();
        AABB box = p.getBoundingBox();
        double vx = v.x, vy = v.y, vz = v.z;

        // Ground: flare early and descend slowly. While gliding, the game caps fallDistance at 1 on any tick
        // the descent is slower than 0.5 blocks/tick (Entity.checkFallDistanceAccumulation), so a landing at
        // SAFE_LAND does no fall damage.
        if (vy < 0) {
            double look = Math.min(MAX_LOOK, -vy / GROUND_GAIN + GROUND_FLARE + 2);
            double clr = sweep(level, box, 0, -1, 0, look);
            double allowed = clr <= GROUND_FLARE
                ? SAFE_LAND
                : SAFE_LAND + (clr - GROUND_FLARE) * GROUND_GAIN;
            if (-vy > allowed) vy = -allowed;
            if (clr < CUSHION_RANGE && p.fallDistance > 1.0) p.fallDistance = 1.0;   // client-side backstop
        }

        // Ceiling: stop short, to avoid bumping and sticking
        if (vy > 0) {
            double look = Math.min(MAX_LOOK, vy / GAIN + CEIL_STANDOFF + 2);
            double clr = sweep(level, box, 0, 1, 0, look);
            double allowed = Math.max(0, (clr - CEIL_STANDOFF) * GAIN);
            if (vy > allowed) vy = allowed;
        }

        // Wall: bleed the closing speed so any contact is gentle (no wall damage). Each horizontal axis is
        // clamped on its own, so hitting a face removes only the component into it: you slide along the
        // wall and keep your speed and lift instead of braking the whole vector and stalling. Each sweep
        // box reaches down by the height lost over its lookahead, so a wall just below is still caught.
        double hs = Math.sqrt(vx * vx + vz * vz);
        if (hs > 1e-4) {
            // The downward reach must stay above the ground below, or a low pass over flat ground would read
            // the ground itself as a wall. Cap it at the clearance straight down, less a small margin.
            double groundBelow = sweep(level, box, 0, -1, 0, WALL_DROP_CAP + 1);
            double dropCap = Math.max(0, groundBelow - 0.5);

            boolean blocked = false;
            if (vx != 0) {
                double ok = axisAllowed(level, box, Math.signum(vx), true, Math.abs(vx), vy, hs, dropCap);
                if (Math.abs(vx) > ok) { vx = Math.signum(vx) * ok; blocked = true; }
            }
            if (vz != 0) {
                double ok = axisAllowed(level, box, Math.signum(vz), false, Math.abs(vz), vy, hs, dropCap);
                if (Math.abs(vz) > ok) { vz = Math.signum(vz) * ok; blocked = true; }
            }
            // Backstop: a block dead ahead on the diagonal slips between the two axis checks. Only when
            // neither axis clamped, sweep along the actual heading and brake the whole vector; not while
            // sliding, so it can't bring back the stall the per-axis clamp avoids.
            if (!blocked) {
                double nx = vx / hs, nz = vz / hs;
                double look = Math.min(MAX_LOOK, hs / GAIN + WALL_STANDOFF + 2);
                double drop = vy < 0 ? Math.min(Math.min(WALL_DROP_CAP, look * (-vy) / hs), dropCap) : 0;
                AABB b = drop > 0 ? box.expandTowards(0, -drop, 0) : box;
                double clr = sweep(level, b, nx, 0, nz, look);
                double allowed = Math.max(0, (clr - WALL_STANDOFF) * GAIN);
                if (hs > allowed) { double s = allowed / hs; vx *= s; vz *= s; }
            }
        }

        return new Vec3(vx, vy, vz);
    }

    // Largest closing speed allowed on one horizontal axis before breaching the standoff. The swept box
    // reaches down by the height lost over the lookahead, so a descent into a wall is caught too.
    private static double axisAllowed(Level level, AABB box, double sign, boolean xAxis,
                                      double speed, double vy, double hs, double dropCap) {
        double look = Math.min(MAX_LOOK, speed / GAIN + WALL_STANDOFF + 2);
        double drop = vy < 0 ? Math.min(Math.min(WALL_DROP_CAP, look * (-vy) / hs), dropCap) : 0;
        AABB b = drop > 0 ? box.expandTowards(0, -drop, 0) : box;
        double clr = xAxis ? sweep(level, b, sign, 0, 0, look) : sweep(level, b, 0, 0, sign, look);
        return Math.max(0, (clr - WALL_STANDOFF) * GAIN);
    }

    // swept-box clearance: how far the box can travel along (dx, dy, dz) before hitting a block
    private static double sweep(Level level, AABB box, double dx, double dy, double dz, double look) {
        for (double d = STEP; d <= look; d += STEP) {
            if (!level.noCollision(box.move(dx * d, dy * d, dz * d))) return d - STEP;
        }
        return look;
    }
}
