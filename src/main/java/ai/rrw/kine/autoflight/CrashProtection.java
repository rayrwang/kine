package ai.rrw.kine.autoflight;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class CrashProtection {

    // --- tuning ---
    private static final double GAIN            = 0.4;   // allowed closing speed = (clearance - standoff) * GAIN
    private static final double WALL_STANDOFF   = 1.5;   // stop this far short of walls
    private static final double CEIL_STANDOFF   = 0.5;   // stop this far below ceilings
    private static final double GROUND_STANDOFF = 1.0;
    private static final double SAFE_LAND    = 0.3;   // descend this slow near ground (well under the 0.5 cap threshold)
    private static final double GROUND_FLARE  = 6.0;   // hard-limit descent to SAFE_LAND within this many blocks
    private static final double GROUND_GAIN   = 0.3;   // gentler ramp above the flare zone
    private static final double STEP            = 0.25;  // sweep granularity (blocks)
    private static final double MAX_LOOK        = 20.0;  // never sweep further than this
    private static final double CUSHION_RANGE   = 8.0;   // hard-clamp fallDistance when ground is within this

    // Only ever REMOVES velocity heading into a surface. Never adds, turns, or redirects, so an
    // over-eager detection can at worst slow you for a moment — it can never fling you anywhere.
    public static Vec3 clamp(LivingEntity p, Vec3 v) {
        Level level = p.level();
        AABB box = p.getBoundingBox();
        double vx = v.x, vy = v.y, vz = v.z;

        // GROUND — flare early and descend slowly so the server's own fall-distance cap reliably triggers
        if (vy < 0) {
            double look = Math.min(MAX_LOOK, -vy / GROUND_GAIN + GROUND_FLARE + 2);
            double clr = sweep(level, box, 0, -1, 0, look);
            double allowed = clr <= GROUND_FLARE
                ? SAFE_LAND
                : SAFE_LAND + (clr - GROUND_FLARE) * GROUND_GAIN;
            if (-vy > allowed) vy = -allowed;
            if (clr < CUSHION_RANGE && p.fallDistance > 1.0) p.fallDistance = 1.0;   // client-side backstop
        }

        // CEILING — stop short (harmless anyway; just avoids bonking/sticking)
        if (vy > 0) {
            double look = Math.min(MAX_LOOK, vy / GAIN + CEIL_STANDOFF + 2);
            double clr = sweep(level, box, 0, 1, 0, look);
            double allowed = Math.max(0, (clr - CEIL_STANDOFF) * GAIN);
            if (vy > allowed) vy = allowed;
        }

        // WALL — bleed horizontal closing speed so contact (if any) is gentle (no flyIntoWall damage)
        double hs = Math.sqrt(vx * vx + vz * vz);
        if (hs > 1e-4) {
            double nx = vx / hs, nz = vz / hs;
            double look = Math.min(MAX_LOOK, hs / GAIN + WALL_STANDOFF + 2);
            double clr = sweep(level, box, nx, 0, nz, look);
            double allowed = Math.max(0, (clr - WALL_STANDOFF) * GAIN);
            if (hs > allowed) {
                double s = allowed / hs;
                vx *= s; vz *= s;
            }
        }

        return new Vec3(vx, vy, vz);
    }

    // swept-AABB clearance: distance the box can travel along (dx,dy,dz) before hitting terrain
    private static double sweep(Level level, AABB box, double dx, double dy, double dz, double look) {
        for (double d = STEP; d <= look; d += STEP) {
            if (!level.noCollision(box.move(dx * d, dy * d, dz * d))) return d - STEP;
        }
        return look;
    }
}
