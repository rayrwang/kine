package ai.rrw.kine.walk;

import java.util.Set;

/**
 * Walking without a route toward a floor past where a far goal's route stops (RW 2026-10-13: "the fall back to layer 2
 * wheh layer 1 cant find path already exists, but only for close targets? so now here fo the logne rtaret wohsoudl
 * extend it to gathat goto?", "do that"). When layer 1 has no way on (every reachable place searched, the walk at the
 * floor nearest the goal) and the goal is too far for walking without a route to it, the walk tries a floor within that
 * range instead: the nearest one to the player that is at least a block nearer the goal than the walk has yet stood (so
 * layer 1 couldn't reach it: it would have been the nearest), within a jump's reach (Walk.HOP_REACH, 8 blocks; no more
 * than a jump up, no more than a safe fall down) and not tried before in this walk. Once there, layer 1 plans to the
 * real goal again. Three tried in a row without getting across, and the walk ends as near as it gets.
 *
 * Why the nearest such floor and not the one nearest the goal: layer 2 without a route crosses one thing at a time
 * (a jump found in the movement copy, then the field's way on); the next floor past the obstacle is the crossing it can
 * make, and from there layer 1 takes over again. A floor far along would ask layer 2 to make several jumps the graph
 * has no move for, with no route between.
 */
final class Hop {
    private Hop() {}

    /** The floor to walk to without a route, from (px, py, pz) toward goal column (gx, gz), or View.NONE: within reach
     *  (blocks, straight line), at least a block nearer the goal than best (the nearest the walk has stood to it, centre
     *  to centre), from maxFall below to a jump above, a ground floor or a climbable, not in tried. */
    static long pick(View v, double px, double py, double pz, int gx, int gz, double best, Set<Long> tried, double maxFall,
                     double reach) {
        int bx = (int) Math.floor(px), bz = (int) Math.floor(pz), r = (int) Math.ceil(reach);
        long pick = View.NONE;
        double pickD = Double.MAX_VALUE, pickG = Double.MAX_VALUE;
        for (int x = bx - r; x <= bx + r; x++)
            for (int z = bz - r; z <= bz + r; z++) {
                double d = Math.hypot(x + 0.5 - px, z + 0.5 - pz);
                if (d > reach || d < 1.0) continue;
                double g = Math.hypot(gx - x, gz - z);
                if (g > best - 1.0) continue;
                ChunkFloors c = v.chunk(x, z);
                if (c == null) continue;
                int n = c.count(x & 15, z & 15);
                for (int i = 0; i < n; i++) {
                    int idx = c.index(x & 15, z & 15, i);
                    int type = c.type(idx);
                    if (type != ChunkFloors.GROUND && type != ChunkFloors.CLIMB) continue;
                    double h = c.h(idx);
                    if (h > py + Graph.JUMP_MAX + 1e-4 || h < py - maxFall - 1e-4) continue;
                    long node = View.node(x, z, i);
                    if (tried.contains(node)) continue;
                    if (d < pickD - 1e-9 || Math.abs(d - pickD) < 1e-9 && g < pickG) {
                        pick = node; pickD = d; pickG = g;
                    }
                }
            }
        return pick;
    }
}
