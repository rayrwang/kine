package ai.rrw.kine.walk;

import java.util.Set;

/**
 * Walking without a route toward a floor past where a far goal's route stops (RW 2026-10-13: "the fall back to layer 2
 * wheh layer 1 cant find path already exists, but only for close targets? so now here fo the logne rtaret wohsoudl
 * extend it to gathat goto?", "do that"). When layer 1 has no way on (every reachable place searched, the walk at the
 * floor nearest the goal) and the goal is too far for walking without a route to it, the walk tries a floor within that
 * range instead: the nearest one to the player that is nearer the goal than the walk has yet stood (so layer 1 couldn't
 * reach it: it would have been the nearest), within a jump's reach (Walk.HOP_REACH, 8 blocks; no more
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
     *  (blocks, straight line), nearer the goal than best (the nearest the walk has stood to it, centre
     *  to centre), from maxFall below to a jump above, a ground floor or a climbable, not in tried. */
    static long pick(View v, double px, double py, double pz, int gx, int gz, double best, Set<Long> tried, double maxFall,
                     double reach) {
        return pick(v, px, py, pz, gx, gz, Double.NaN, best, tried, maxFall, reach);
    }

    /** Within this (blocks) of as near the goal as the walk has stood, a floor is as near, not nearer: a block aside of a
     *  far goal is thousandths of a block nearer or farther. */
    static final double TIE = 0.05;

    /** As above, with the destination's Y (NaN: none): a floor as near as the walk has stood (within TIE) counts as nearer
     *  when its height is nearer that Y than the walk's by 0.4 or more (the course's start beside its pad: the pad a block
     *  lower and a block aside, toward or away from the goal's line by its z; RW 2026-10-14, flower_pot_jump and
     *  mm_backwalled_1bm_4b: from the start the pad was taken for nearer, and the jump was never tried). */
    static long pick(View v, double px, double py, double pz, int gx, int gz, double goalY, double best, Set<Long> tried,
                     double maxFall, double reach) {
        int bx = (int) Math.floor(px), bz = (int) Math.floor(pz), r = (int) Math.ceil(reach);
        long pick = View.NONE;
        double pickD = Double.MAX_VALUE, pickG = Double.MAX_VALUE;
        for (int x = bx - r; x <= bx + r; x++)
            for (int z = bz - r; z <= bz + r; z++) {
                double d = Math.hypot(x + 0.5 - px, z + 0.5 - pz);
                // any column but the one it stands in (the next start beside the pad is a block off, d about 1)
                if (d > reach || x == bx && z == bz) continue;
                double g = Math.hypot(gx - x, gz - z);
                // nearer the goal than the walk has stood (layer 1's way ends at the floor nearest the goal it can reach,
                // so a floor nearer is one it can't): any nearer counts (a block aside, a far goal is thousandths of a
                // block nearer), so the next jump's start beside the course's pad is taken (an end rod: no graph move
                // onto it), not the landing past it. Or a step up beside the walk (within 1.5, at least 0.4 up),
                // nearer or not: the start beside a pad on the side away from the goal (ladder_to_ladder: a ladder in
                // its feet's cell, no graph move onto it). Only up, so it can't step back down onto the pad.
                boolean step = d <= 1.5;
                boolean tie = !Double.isNaN(goalY) && Math.abs(g - best) <= TIE;
                boolean nearer = Double.isNaN(goalY) ? g <= best - 1e-6 : g < best - TIE;
                if (!nearer && !tie && !step) continue;
                ChunkFloors c = v.chunk(x, z);
                if (c == null) continue;
                int n = c.count(x & 15, z & 15);
                for (int i = 0; i < n; i++) {
                    int idx = c.index(x & 15, z & 15, i);
                    int type = c.type(idx);
                    if (type != ChunkFloors.GROUND && type != ChunkFloors.CLIMB) continue;
                    double h = c.h(idx);
                    if (h > py + Graph.JUMP_MAX + 1e-4 || h < py - maxFall - 1e-4) continue;
                    boolean yNearer = tie && Math.abs(h - goalY) < Math.abs(py - goalY) - 0.4;
                    if (!nearer && !yNearer && !(step && h >= py + 0.4)) continue;   // not nearer: only a step up beside it
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
