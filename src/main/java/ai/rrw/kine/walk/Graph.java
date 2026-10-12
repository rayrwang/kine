package ai.rrw.kine.walk;

/**
 * The moves out of a floor, with their costs: the search's graph, worked out when a floor is expanded rather than
 * stored. Horizontal moves go to the eight neighbouring columns and the eight knight's moves (one over, two across),
 * which bring the worst detour of a grid path against the straight line from 8.2% (straight and diagonal only) to
 * 2.7%. Vertical moves: ladders up and down, and dropping down a shaft.
 *
 * <p>Whether a move fits is decided from the table where the floors are plain (their top covers the cell and the cell
 * is as free as the middle): heights, the free height above each floor, and for diagonal and knight's moves the cells
 * the player's box clips on the way. Otherwise the walk-straight sweep decides, against the blocks' own shapes. Walk
 * only (RW's ruling): nothing is broken or placed.
 *
 * <ul>
 *   <li>WALK: to a floor within a step (0.6) up or down, straight, diagonal or knight's; WADE when water is at the feet
 *       at either end (water's cost);</li>
 *   <li>DROP: straight off an edge to a floor more than a step and at most 3 below (any height when landing in
 *       water);</li>
 *   <li>JUMP: straight up onto a floor more than a step and at most 1.25 above;</li>
 *   <li>WATER_IN, SWIM, WATER_OUT: into deep water from a bank (any height), across its top with jump held, and out
 *       onto a bank at most a quarter block above the water's top;</li>
 *   <li>CLIMB_ON, CLIMB_UP, CLIMB_DOWN, CLIMB_OFF: walking into a ladder, up and down it, and off it sideways or at
 *       its top;</li>
 *   <li>EXIT: from a floor at the edge of what is loaded to a goal beyond it, the rest charged as a straight sprint
 *       over open ground.</li>
 * </ul>
 */
final class Graph {
    static final int WALK = 0, WADE = 1, DROP = 2, JUMP = 3, WATER_IN = 4, SWIM = 5, WATER_OUT = 6,
        CLIMB_ON = 7, CLIMB_UP = 8, CLIMB_DOWN = 9, CLIMB_OFF = 10, EXIT = 11, START = 12;
    static final String[] NAMES = {"walk", "wade", "drop", "jump", "water-in", "swim", "water-out",
        "climb-on", "climb-up", "climb-down", "climb-off", "exit", "start"};

    // 8 neighbours, then 8 knight's moves
    static final int[] DX = {1, 0, -1, 0, 1, -1, -1, 1, 1, 2, 2, 1, -1, -2, -2, -1};
    static final int[] DZ = {0, 1, 0, -1, 1, 1, -1, -1, 2, 1, -1, -2, -2, -1, 1, 2};
    static final double[] LEN = new double[16];
    static { for (int d = 0; d < 16; d++) LEN[d] = Math.sqrt(DX[d] * DX[d] + DZ[d] * DZ[d]); }

    static final double STEP = 0.6, JUMP_MAX = 1.25, H = 1.8;

    final View v;
    final Costs c;
    final Sweep sweep;
    boolean canSprint = true;
    /** The goal column, and whether the goal lies beyond the loaded area (then edge floors get an EXIT move). */
    int goalX, goalZ;
    boolean goalUnknown;

    // the moves found by expand()
    int n;
    long[] to = new long[64];
    double[] cost = new double[64];
    byte[] move = new byte[64];

    /** Moves the fast table check couldn't decide and the sweep did (for the report). */
    long sweeps = 0;

    Graph(View v, Costs c) {
        this.v = v;
        this.c = c;
        this.sweep = new Sweep(v, c, 0.0);
        this.sweep.allowClimb = true;
    }

    private void add(long node, double cst, int mv) {
        if (n == to.length) {
            to = java.util.Arrays.copyOf(to, n * 2);
            cost = java.util.Arrays.copyOf(cost, n * 2);
            move = java.util.Arrays.copyOf(move, n * 2);
        }
        to[n] = node; cost[n] = cst; move[n] = (byte) mv; n++;
    }

    /** The moves out of this node into to/cost/move (n of them). */
    void expand(long node) {
        n = 0;
        int x = View.nodeX(node), z = View.nodeZ(node), i = View.nodeI(node);
        ChunkFloors a = v.chunk(x, z);
        if (a == null) return;
        int ai = a.index(x & 15, z & 15, i);
        int type = a.type(ai);
        float ah = a.h(ai);
        boolean edge = false;

        // vertical moves in the same column
        int cnt = a.count(x & 15, z & 15);
        if (type == ChunkFloors.CLIMB) {
            if (i + 1 < cnt) {
                int up = a.index(x & 15, z & 15, i + 1);
                if (a.type(up) == ChunkFloors.CLIMB && Math.abs(a.h(up) - (ah + 1)) < 1e-4)
                    add(View.node(x, z, i + 1), c.ladderUp, CLIMB_UP);
            }
            if (i > 0) {
                int dn = a.index(x & 15, z & 15, i - 1);
                float d = ah - a.h(dn);
                boolean intoWater = a.type(dn) == ChunkFloors.SWIM || (a.flags(dn) & ChunkFloors.WADE) != 0;
                // down the ladder to the floor below it, at most a safe fall when the ladder stops short
                if (d > 0 && (d <= c.maxFall + 1e-4 || intoWater))
                    add(View.node(x, z, i - 1), c.ladderDown * Math.max(1.0, d), CLIMB_DOWN);
            }
        } else if (type == ChunkFloors.GROUND && (a.flags(ai) & ChunkFloors.CLIMB_FEET) != 0 && i + 1 < cnt) {
            int up = a.index(x & 15, z & 15, i + 1);
            int fy = (int) Math.floor(ah + 1e-4);
            if (a.type(up) == ChunkFloors.CLIMB && Math.abs(a.h(up) - (fy + 1)) < 1e-4)
                add(View.node(x, z, i + 1), c.ladderUp, CLIMB_UP);
        }

        for (int d = 0; d < 16; d++) {
            int bx = x + DX[d], bz = z + DZ[d];
            ChunkFloors b = v.chunk(bx, bz);
            if (b == null) { if (d < 8) edge = true; continue; }
            if (d >= 8 && type != ChunkFloors.GROUND) continue;     // knight's moves on foot only
            boolean straight = d < 4, diagonal = d >= 4 && d < 8;
            int bc = b.count(bx & 15, bz & 15);
            for (int j = 0; j < bc; j++) {
                int bj = b.index(bx & 15, bz & 15, j);
                int btype = b.type(bj);
                float bh = b.h(bj);
                float dh = bh - ah;
                if (dh > JUMP_MAX + 0.5 || dh < -64) continue;
                long target = View.node(bx, bz, j);
                switch (type) {
                    case ChunkFloors.GROUND -> fromGround(a, ai, x, z, b, bj, bx, bz, btype, d, straight, diagonal, target);
                    case ChunkFloors.SWIM -> fromSwim(a, ai, x, z, b, bj, bx, bz, btype, d, straight, diagonal, target);
                    case ChunkFloors.CLIMB -> fromClimb(a, ai, x, z, b, bj, btype, straight, target);
                    default -> {}
                }
            }
        }
        if (edge && goalUnknown) {
            double gx = goalX + 0.5 - (x + 0.5), gz = goalZ + 0.5 - (z + 0.5);
            add(Long.MIN_VALUE, Math.sqrt(gx * gx + gz * gz) * c.sprint, EXIT);
        }
    }

    private void fromGround(ChunkFloors a, int ai, int x, int z, ChunkFloors b, int bj, int bx, int bz, int btype, int d,
                            boolean straight, boolean diagonal, long target) {
        float ah = a.h(ai), bh = b.h(bj), dh = bh - ah;
        double len = LEN[d];
        boolean slow = (a.flags(ai) & ChunkFloors.SLOW) != 0 || btype == ChunkFloors.GROUND && (b.flags(bj) & ChunkFloors.SLOW) != 0;
        if (btype == ChunkFloors.GROUND) {
            boolean wade = ((a.flags(ai) | b.flags(bj)) & ChunkFloors.WADE) != 0;
            if (Math.abs(dh) <= STEP + 1e-5) {
                if (!walkFits(a, ai, x, z, b, bj, bx, bz, d)) return;
                add(target, len * c.rate(canSprint, slow, wade) + hazardCost(b, bj, bx, bz), wade ? WADE : WALK);
                return;
            }
            // stairs and other floors that aren't plain: the sweep walks them step by step (up or down a step at a time)
            if ((straight || diagonal) && !wade && dh <= JUMP_MAX + 1e-5 && dh >= -c.maxFall - 1e-5
                && (!plain(a, ai) || !plain(b, bj)) && sweepFits(a, ai, x, z, bx, bz, ah) && Math.abs(sweep.endH - bh) < 0.05) {
                add(target, len * c.rate(canSprint, slow, false) + hazardCost(b, bj, bx, bz), WALK);
                return;
            }
            if (straight && dh > STEP && dh <= JUMP_MAX + 1e-5) {
                // rise in this column to the higher floor, then over: room above here, and the higher floor plain
                if (ah + a.head(ai) < bh + H - 1e-4) return;
                if (!plain(a, ai) || !plain(b, bj)) return;
                if (a.cellTop(ai) + a.cellClear(ai) < bh + H - 1e-4) return;
                add(target, c.jumpCost(len, dh) + hazardCost(b, bj, bx, bz), JUMP);
            } else if (straight && dh < -STEP) {
                boolean water = (b.flags(bj) & ChunkFloors.WADE) != 0;
                if (-dh > c.maxFall + 1e-5 && !water) return;
                // fall through the other column: free over the whole cell from its floor to above where we start
                if (!plain(b, bj) || b.cellTop(bj) + b.cellClear(bj) < ah + H - 1e-4) return;
                if (!plain(a, ai) && !sweepFits(a, ai, x, z, bx, bz, ah)) return;
                add(target, c.dropCost(len, -dh) + hazardCost(b, bj, bx, bz), DROP);
            }
        } else if (btype == ChunkFloors.SWIM && (straight || diagonal) && d < 4) {
            // into deep water from a bank at or above its top: fall through the air above it
            if (ah < bh - 0.5) return;
            if (b.cellTop(bj) + b.cellClear(bj) < ah + H - 1e-4) return;
            if (a.cellTop(ai) + a.cellClear(ai) < ah + H - 1e-4) return;
            double fall = Math.max(0, ah - bh);
            add(target, len * c.walk * c.walkOffFraction + Costs.fallTicks(fall) + c.water * (1 - c.walkOffFraction)
                + c.waterPenalty, WATER_IN);
        } else if (btype == ChunkFloors.CLIMB && straight) {
            // walk into a ladder at about the same height, or step down onto one
            if (dh > STEP + 1e-5 || dh < -JUMP_MAX - 1e-5) return;
            float top = Math.max(ah, bh);
            if (bh + b.head(bj) < top + H - 1e-4) return;
            if (a.cellTop(ai) + a.cellClear(ai) < top + H - 1e-4) return;
            add(target, len * c.walk + (dh < 0 ? Costs.fallTicks(-dh) : 0), CLIMB_ON);
        }
    }

    private void fromSwim(ChunkFloors a, int ai, int x, int z, ChunkFloors b, int bj, int bx, int bz, int btype, int d,
                          boolean straight, boolean diagonal, long target) {
        float ah = a.h(ai), bh = b.h(bj), dh = bh - ah;
        double len = LEN[d];
        if (btype == ChunkFloors.SWIM && (straight || diagonal)) {
            if (Math.abs(dh) > 1e-4) return;
            if (diagonal && (!sideOpenWater(x + DX[d], z, ah) || !sideOpenWater(x, z + DZ[d], ah))) return;
            add(target, len * c.water + c.waterPenalty, SWIM);
        } else if (btype == ChunkFloors.GROUND && straight) {
            // out onto a bank no more than a quarter block above the water's top, or a shore at or below it
            if (dh > 0.25 + 1e-5 || dh < -1.0) return;
            if (b.head(bj) < H - 1e-4) return;
            if (!plain(b, bj)) return;
            if (dh > 0 && a.cellTop(ai) + a.cellClear(ai) < bh + H - 1e-4) return;
            add(target, len * c.water + c.jumpPenalty, WATER_OUT);
        }
    }

    private void fromClimb(ChunkFloors a, int ai, int x, int z, ChunkFloors b, int bj, int btype, boolean straight, long target) {
        if (!straight || btype != ChunkFloors.GROUND) return;
        float ah = a.h(ai), bh = b.h(bj), dh = bh - ah;
        if (dh < -STEP - 1e-5 || dh > JUMP_MAX + 1e-5) return;
        // off at the top only where the ladder ends (else keep climbing)
        if (dh > STEP) {
            int col = a.count(x & 15, z & 15);
            for (int k = 0; k < col; k++) {
                int idx = a.index(x & 15, z & 15, k);
                if (a.type(idx) == ChunkFloors.CLIMB && Math.abs(a.h(idx) - (ah + 1)) < 1e-4) return;
            }
        }
        float top = Math.max(ah, bh);
        if (ah + a.head(ai) < top + H - 1e-4) return;
        if (b.head(bj) < H - 1e-4 || !plain(b, bj)) return;
        add(target, c.walk + (dh > STEP ? c.jumpPenalty : 0), CLIMB_OFF);
    }

    /** A floor whose top covers its cell and whose cell is as free as its middle. */
    static boolean plain(ChunkFloors c, int idx) {
        return (c.flags(idx) & ChunkFloors.SIMPLE) != 0;
    }

    /** Whether a walk (within a step up or down) from floor a to floor b in direction d fits. */
    private boolean walkFits(ChunkFloors a, int ai, int x, int z, ChunkFloors b, int bj, int bx, int bz, int d) {
        float ah = a.h(ai), bh = b.h(bj), top = Math.max(ah, bh);
        if (a.head(ai) < top - ah + H - 1e-4 || b.head(bj) < top - bh + H - 1e-4) return false;
        boolean fast = plain(a, ai) && plain(b, bj)
            && a.cellTop(ai) + a.cellClear(ai) >= top + H - 1e-4 && b.cellTop(bj) + b.cellClear(bj) >= top + H - 1e-4;
        if (fast && d >= 4 && d < 8) {
            fast = sideFree(x + DX[d], z, top) && sideFree(x, z + DZ[d], top);
        } else if (fast && d >= 8) {
            int sx = Integer.signum(DX[d]), sz = Integer.signum(DZ[d]);
            if (Math.abs(DX[d]) == 1) {   // one over in x, two across in z
                fast = crossed(x, z + sz, ah, bh) && crossed(x + sx, z + sz, ah, bh)
                    && sideFree(x + sx, z, top) && sideFree(x, z + 2 * sz, top);
            } else {
                fast = crossed(x + sx, z, ah, bh) && crossed(x + sx, z + sz, ah, bh)
                    && sideFree(x, z + sz, top) && sideFree(x + 2 * sx, z, top);
            }
        }
        if (fast) return true;
        if (d >= 8) return false;                                                   // knight's moves: plain floors only
        if (((a.flags(ai) | b.flags(bj)) & ChunkFloors.WADE) != 0) return false;   // wading: the table's word only
        return sweepFits(a, ai, x, z, bx, bz, ah) && Math.abs(sweep.endH - bh) < 0.05;
    }

    private boolean sweepFits(ChunkFloors a, int ai, int x, int z, int bx, int bz, float ah) {
        sweeps++;
        return sweep.walk(x + 0.5, ah, z + 0.5, bx + 0.5, bz + 0.5);
    }

    /** A cell the box clips on a diagonal or knight's move: free of collisions and hazards from a step above the
     *  higher floor to the player's height above it (a floor there at most a step up is fine: the box would step). */
    private boolean sideFree(int x, int z, float top) {
        ChunkFloors c = v.chunk(x, z);
        if (c == null) return false;
        int lx = x & 15, lz = z & 15, n = c.count(lx, lz);
        double lo = top + STEP, hi = top + H;
        for (int i = 0; i < n; i++) {
            int idx = c.index(lx, lz, i);
            if (c.type(idx) == ChunkFloors.CLIMB) continue;
            float ct = c.cellTop(idx);
            if (ct <= lo + 1e-5 && ct + c.cellClear(idx) >= hi - 1e-5) {
                // no water or hazard in the way
                for (int y = (int) Math.floor(top); y <= (int) Math.floor(hi - 1e-4); y++) {
                    Kind k = v.block(x, y, z);
                    if (k.is(Kind.WATER) || k.blocksBody()) return false;
                }
                return true;
            }
        }
        return false;
    }

    /** A cell the line of a knight's move crosses: a plain floor within a step of both ends, room above it. */
    private boolean crossed(int x, int z, float ah, float bh) {
        ChunkFloors c = v.chunk(x, z);
        if (c == null) return false;
        int lx = x & 15, lz = z & 15, n = c.count(lx, lz);
        for (int i = 0; i < n; i++) {
            int idx = c.index(lx, lz, i);
            if (c.type(idx) != ChunkFloors.GROUND || !plain(c, idx)) continue;
            float h = c.h(idx);
            if (Math.abs(h - ah) > STEP + 1e-5 || Math.abs(h - bh) > STEP + 1e-5) continue;
            float top = Math.max(h, Math.max(ah, bh));
            if (c.cellTop(idx) + c.cellClear(idx) >= top + H - 1e-4 && (c.flags(idx) & ChunkFloors.WADE) == 0) return true;
        }
        return false;
    }

    /** A diagonal swim's side cell: water at the same top or open air over the water level. */
    private boolean sideOpenWater(int x, int z, float top) {
        ChunkFloors c = v.chunk(x, z);
        if (c == null) return false;
        int lx = x & 15, lz = z & 15, n = c.count(lx, lz);
        for (int i = 0; i < n; i++) {
            int idx = c.index(lx, lz, i);
            if (c.type(idx) == ChunkFloors.SWIM && Math.abs(c.h(idx) - top) < 1e-4) return true;
        }
        return sideFree(x, z, top - 1);
    }

    /** The extra for a floor next to hazards: each of the eight columns around with one at body height. */
    double hazardCost(ChunkFloors b, int bj, int bx, int bz) {
        if (c.hazardNear <= 0) return 0;
        float h = b.h(bj);
        int y0 = (int) Math.floor(h - 1e-4), y1 = (int) Math.floor(h + H);
        int k = 0;
        for (int d = 0; d < 8; d++) if (v.hazardIn(bx + DX[d], bz + DZ[d], y0, y1)) k++;
        return k * c.hazardNear;
    }
}
