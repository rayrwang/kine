package ai.rrw.kine.walk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The gap jumps layer 1 offers with Parkour on: from a take-off floor to a landing floor (dx, dz) away and dh up or
 * down, over columns with nothing to stand on, and the run-up each needs (blocks of floor behind the take-off along
 * the jump's line). Measured in layer 2's movement copy (GapReachTest, 2026-10-13: every way in reach of a precise
 * vanilla client: a run-up of 0 to 8 blocks, sprinting or sprint-jumping, the last jump on any of the last four ground
 * ticks, the heading off the line by up to 10 degrees, forward alone or with a strafe key, the heading in the air
 * turning toward the landing at 29.5 degrees a tick); kept when at least 5 of about 220 ways land on a single block,
 * at the least run-up with which layer 2 itself then made the jump in the copy (Layer2JumpTest: 9 jumps at the edge of
 * what a standing start allows needed a block of run-up for it, one two). Straight out to 4 empty columns (5 from a block of run-up), diagonal to 3 by 3, the
 * knight's and other lines between; one up to 3 across. Down jumps stop at one down: from two down a jump's fall is
 * more than 3 and hurts. Given for dz up to dx and dx at least 2, turned into all eight directions here.
 */
final class Jumps {
    private Jumps() {}

    /** {dx, dz, dh, run-up}, dz <= dx (GapReachTest, threshold 5 ways; run-up as layer 2 needs it). */
    static final int[][] TABLE = {
        {2, 0, -1, 0}, {2, 1, -1, 0}, {2, 2, -1, 0}, {3, 0, -1, 0}, {3, 1, -1, 0}, {3, 2, -1, 0}, {4, 0, -1, 0},
        {4, 1, -1, 0}, {3, 3, -1, 0}, {4, 2, -1, 0}, {4, 3, -1, 0}, {5, 0, -1, 0}, {5, 1, -1, 1}, {5, 2, -1, 1},
        {4, 4, -1, 1}, {5, 3, -1, 1},
        {2, 0, 0, 0}, {2, 1, 0, 0}, {2, 2, 0, 0}, {3, 0, 0, 0}, {3, 1, 0, 0}, {3, 2, 0, 0}, {4, 0, 0, 0},
        {4, 1, 0, 0}, {3, 3, 0, 0}, {4, 2, 0, 0}, {4, 3, 0, 1}, {5, 0, 0, 1}, {5, 1, 0, 1}, {5, 2, 0, 2},
        {4, 4, 0, 2},
        {2, 0, 1, 0}, {2, 1, 1, 0}, {2, 2, 1, 0}, {3, 0, 1, 0}, {3, 1, 1, 0}, {3, 2, 1, 0}, {4, 0, 1, 1},
        {4, 1, 1, 1}, {3, 3, 1, 1}, {4, 2, 1, 2},
    };

    /** The half width of the band of columns the player's box (0.3 each side) may sweep, with slack for the heading. */
    static final double BAND = 0.45;

    /** One directed jump. */
    static final class Jump {
        final int dx, dz, dh, runUp;
        final double length;
        /** The columns jumped over, nearest the take-off first ({x, z} pairs relative to it). */
        final int[] over;
        /** The run-up's columns behind the take-off ({x, z} pairs). */
        final int[] runway;
        Jump(int dx, int dz, int dh, int runUp) {
            this.dx = dx; this.dz = dz; this.dh = dh; this.runUp = runUp;
            this.length = Math.hypot(dx, dz);
            this.over = band(0.5, 0.5, dx + 0.5, dz + 0.5, dx, dz);
            double ux = dx / length, uz = dz / length;
            this.runway = runUp == 0 ? new int[0] : band(0.5, 0.5, 0.5 - ux * (runUp + 0.3), 0.5 - uz * (runUp + 0.3), dx, dz);
        }
    }

    /** All the jumps, grouped by the first column each jumps over (an interior floor fails that check once for all). */
    static final Map<Long, List<Jump>> BY_FIRST = new LinkedHashMap<>();
    static final List<Jump> ALL = new ArrayList<>();

    static {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int[] t : TABLE)
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sz = -1; sz <= 1; sz += 2)
                    for (int swap = 0; swap <= 1; swap++) {
                        int dx = (swap == 0 ? t[0] : t[1]) * sx, dz = (swap == 0 ? t[1] : t[0]) * sz;
                        if (!seen.add(dx + "," + dz + "," + t[2])) continue;
                        Jump j = new Jump(dx, dz, t[2], t[3]);
                        if (j.over.length == 0) continue;
                        ALL.add(j);
                        BY_FIRST.computeIfAbsent(View.key(j.over[0], j.over[1]), k -> new ArrayList<>()).add(j);
                    }
    }

    /**
     * The columns a band of half width BAND around the segment from (ax, az) to (bx, bz) crosses, in the order met from
     * a, without the take-off column (0, 0) and the landing (lx, lz); {x, z} pairs.
     */
    static int[] band(double ax, double az, double bx, double bz, int lx, int lz) {
        double len = Math.hypot(bx - ax, bz - az);
        double ux = len < 1e-9 ? 0 : (bx - ax) / len, uz = len < 1e-9 ? 0 : (bz - az) / len;
        java.util.LinkedHashSet<Long> cols = new java.util.LinkedHashSet<>();
        for (double t = 0; t <= len + 1e-9; t += 0.05)
            for (double q = -BAND; q <= BAND + 1e-9; q += BAND / 4) {
                double x = ax + ux * t - uz * q, z = az + uz * t + ux * q;
                int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
                if (cx == 0 && cz == 0 || cx == lx && cz == lz) continue;
                cols.add(View.key(cx, cz));
            }
        int[] out = new int[cols.size() * 2];
        int k = 0;
        for (long key : cols) { out[k++] = (int) (key >> 32); out[k++] = (int) key; }
        return out;
    }
}
