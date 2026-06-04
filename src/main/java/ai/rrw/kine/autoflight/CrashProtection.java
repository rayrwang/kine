package ai.rrw.kine.autoflight;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class CrashProtection {

    // --- tuning ---
    private static final double GAIN            = 0.4;   // allowed closing speed = (clearance - standoff) * GAIN
    private static final double WALL_STANDOFF   = 1.5;   // stop this far short of walls
    private static final double WALL_DROP_CAP   = 5.0;   // how far below to look for walls we'll descend into
    private static final double CEIL_STANDOFF   = 0.5;   // stop this far below ceilings
    private static final double SAFE_LAND       = 0.3;   // descend this slow near ground (well under the 0.5 cap threshold)
    private static final double GROUND_FLARE    = 6.0;   // hard-limit descent to SAFE_LAND within this many blocks
    private static final double GROUND_GAIN     = 0.3;   // gentler ramp above the flare zone
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

        // WALL — bleed horizontal closing speed so any contact is gentle (no flyIntoWall damage).
        // Each horizontal axis is clamped on its own, so hitting a face kills only the component INTO
        // that face and leaves the along-face component intact: you slide along the wall and keep the
        // airspeed (and therefore the lift) instead of braking the whole vector and stalling — which is
        // what made diagonal approaches and corners fail. Every sweep box is extended downward by the
        // distance we'll sink over its lookahead, so a wall sitting just below the current level (which
        // a level sweep skims straight over as we descend into it) is still caught.
        double hs = Math.sqrt(vx * vx + vz * vz);
        if (hs > 1e-4) {
            // The wall sweeps reach below the current level so a wall sitting just under us is caught as we
            // descend into it. But that downward reach must never dip past the floor we're gliding over, or a
            // low pass across flat ground reads the floor itself as a wall and brakes us. Cap the drop to the
            // clearance straight down (less a small margin), keeping the box just above the ground while still
            // catching anything that rises above it.
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
            // Backstop: a block dead ahead on the diagonal slips between the two axis checks (each axis
            // alone is clear). Only when neither axis clamped do we sweep along the actual heading and
            // brake the whole vector for a closing corner — never while we're already sliding, so it
            // can't re-introduce the very stall it exists to avoid.
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

    // Largest closing speed allowed on one horizontal axis before we'd breach the standoff. The swept
    // box is extended downward by however far we'll sink over the lookahead, so descending into a wall
    // is caught the same way a level approach is.
    private static double axisAllowed(Level level, AABB box, double sign, boolean xAxis,
                                      double speed, double vy, double hs, double dropCap) {
        double look = Math.min(MAX_LOOK, speed / GAIN + WALL_STANDOFF + 2);
        double drop = vy < 0 ? Math.min(Math.min(WALL_DROP_CAP, look * (-vy) / hs), dropCap) : 0;
        AABB b = drop > 0 ? box.expandTowards(0, -drop, 0) : box;
        double clr = xAxis ? sweep(level, b, sign, 0, 0, look) : sweep(level, b, 0, 0, sign, look);
        return Math.max(0, (clr - WALL_STANDOFF) * GAIN);
    }

    // swept-AABB clearance: distance the box can travel along (dx,dy,dz) before hitting terrain
    private static double sweep(Level level, AABB box, double dx, double dy, double dz, double look) {
        for (double d = STEP; d <= look; d += STEP) {
            if (!level.noCollision(box.move(dx * d, dy * d, dz * d))) return d - STEP;
        }
        return look;
    }
}
