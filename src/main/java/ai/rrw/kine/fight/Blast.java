package ai.rrw.kine.fight;

import ai.rrw.kine.move.BlockGrid;

/**
 * A creeper's explosion on the player (ServerExplosion.hurtEntities, ExplosionDamageCalculator, 26.1.2): with dist the
 * player's distance (feet) from the centre over twice the radius, nothing past 1; exposure the share of points on the
 * player's box with a clear line to the centre (ServerExplosion.getSeenPercent: a grid of points over the box, each ray
 * clipped against the blocks); damage (p^2 + p) / 2 * 7 * 2r + 1 with p = (1 - dist) * exposure, scaled by difficulty
 * always, armour applying, no hurt knockback (the explosion damage types are NO_KNOCKBACK); and a push of
 * (1 - dist) * exposure along the line from the centre to the player's eyes, which the explode packet adds to the
 * client's velocity. The copy counts only full blocks in the way (none outside the block snapshot), so its exposure is
 * never less than the game's.
 */
public final class Blast {
    private Blast() {}

    /** {damage before difficulty and armour, push x, push y, push z}, or null if the player is out of its range. */
    static double[] onPlayer(double cx, double cy, double cz, double radius, double[] pb, double eyeY, BlockGrid grid) {
        double px = (pb[0] + pb[3]) / 2, py = pb[1], pz = (pb[2] + pb[5]) / 2;
        double two = radius * 2.0;
        double dist = Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy) + (pz - cz) * (pz - cz)) / two;
        if (dist > 1.0) return null;
        double exposure = seenPercent(cx, cy, cz, pb, grid);
        double p = (1.0 - dist) * exposure;
        double damage = (float) ((p * p + p) / 2.0 * 7.0 * two + 1.0);
        double dx = px - cx, dy = eyeY - cy, dz = pz - cz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double k = (1.0 - dist) * exposure;
        if (d < 1e-9) return new double[]{damage, 0, 0, 0};
        return new double[]{damage, dx / d * k, dy / d * k, dz / d * k};
    }

    /** ServerExplosion.getSeenPercent with full blocks only. */
    static double seenPercent(double cx, double cy, double cz, double[] bb, BlockGrid grid) {
        double xs = 1.0 / ((bb[3] - bb[0]) * 2.0 + 1.0);
        double ys = 1.0 / ((bb[4] - bb[1]) * 2.0 + 1.0);
        double zs = 1.0 / ((bb[5] - bb[2]) * 2.0 + 1.0);
        double xOff = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOff = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        int hits = 0, count = 0;
        for (double xx = 0.0; xx <= 1.0; xx += xs)
            for (double yy = 0.0; yy <= 1.0; yy += ys)
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double x = bb[0] + xx * (bb[3] - bb[0]) + xOff;
                    double y = bb[1] + yy * (bb[4] - bb[1]);
                    double z = bb[2] + zz * (bb[5] - bb[2]) + zOff;
                    if (clear(x, y, z, cx, cy, cz, grid)) hits++;
                    count++;
                }
        return count == 0 ? 0.0 : (double) hits / count;
    }

    /** Whether the segment from (x0, y0, z0) to (x1, y1, z1) passes no full block (a voxel walk; the start's own block
     *  and blocks outside the snapshot don't count). */
    static boolean clear(double x0, double y0, double z0, double x1, double y1, double z1, BlockGrid grid) {
        int x = (int) Math.floor(x0), y = (int) Math.floor(y0), z = (int) Math.floor(z0);
        int ex = (int) Math.floor(x1), ey = (int) Math.floor(y1), ez = (int) Math.floor(z1);
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tx = sx == 0 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 - x0 : x0 - x) / Math.abs(dx));
        double ty = sy == 0 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 - y0 : y0 - y) / Math.abs(dy));
        double tz = sz == 0 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 - z0 : z0 - z) / Math.abs(dz));
        double ddx = sx == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double ddy = sy == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double ddz = sz == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);
        for (int i = 0; i < 64; i++) {
            if (x == ex && y == ey && z == ez) return true;
            if (tx <= ty && tx <= tz) { if (tx > 1.0) return true; x += sx; tx += ddx; }
            else if (ty <= tz) { if (ty > 1.0) return true; y += sy; ty += ddy; }
            else { if (tz > 1.0) return true; z += sz; tz += ddz; }
            if (grid.inside(x, y, z) && grid.get(x, y, z).shape.block) return false;
        }
        return true;
    }
}
