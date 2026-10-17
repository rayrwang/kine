package ai.rrw.kine.walk;

/**
 * The moves out of a floor, with their costs: the search's graph, worked out when a floor is expanded rather than
 * stored. Horizontal moves go to the eight neighbouring columns and the eight knight's moves (one over, two across),
 * which bring the worst detour of a grid path against the straight line from 8.2% (straight and diagonal only) to
 * 2.7%, on foot and swimming. Vertical moves: ladders up and down, and dropping down a shaft.
 *
 * <p>Whether a move fits is decided from the table where the floors are plain (their top covers the cell and the cell
 * is as free as the middle): heights, the free height above each floor, and for diagonal and knight's moves the cells
 * the player's box clips on the way. Otherwise the walk-straight sweep decides, against the blocks' own shapes. Walk
 * only (RW's ruling): nothing is broken or placed.
 *
 * <ul>
 *   <li>WALK: to a floor within a step (0.6) up or down, straight, diagonal or knight's; WADE when water is at the feet
 *       at either end (water's cost; its diagonal and knight's moves cross cells of water at the feet too);</li>
 *   <li>DROP: straight off an edge to a floor more than a step and at most 3 below (any height when landing in
 *       water);</li>
 *   <li>JUMP: straight up onto a floor more than a step and at most 1.25 above;</li>
 *   <li>WATER_IN, SWIM, WATER_OUT: into deep water from a bank (any height), across its top with jump held (straight,
 *       diagonal or knight's), and out onto a bank at most a quarter block above the water's top;</li>
 *   <li>CLIMB_ON, CLIMB_UP, CLIMB_DOWN, CLIMB_OFF: walking (or swimming) into a ladder, up and down it, and off it
 *       sideways or at its top;</li>
 *   <li>EXIT: from a floor at the edge of what is loaded to a goal beyond it, the rest charged as a straight line at
 *       one rate for every way out (beyondRate: the search sets it from the ground on the straight line to the goal,
 *       so a sea's way on is charged as a swim and the land's as a sprint).</li>
 * </ul>
 */
final class Graph {
    static final int WALK = 0, WADE = 1, DROP = 2, JUMP = 3, WATER_IN = 4, SWIM = 5, WATER_OUT = 6,
        CLIMB_ON = 7, CLIMB_UP = 8, CLIMB_DOWN = 9, CLIMB_OFF = 10, EXIT = 11, START = 12, GAP = 13, GLIDE = 14, LOOSE = 15,
        OPEN = 16;
    static final String[] NAMES = {"walk", "wade", "drop", "jump", "water-in", "swim", "water-out",
        "climb-on", "climb-up", "climb-down", "climb-off", "exit", "start", "gap", "glide", "loose", "door"};

    /** What opening a door costs the route (ticks): turning to it, the click, the door's swing, the step through. */
    static final double DOOR_COST = 20.0;

    /** A move layer 2 makes as a jump the jump search can find (Layer2's stall search, the field's jump ahead): a gap,
     *  a loose move. */
    static boolean jumpMove(int mv) {
        return mv == GAP || mv == LOOSE;
    }

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
    /** Offer jumps over gaps (GAP): only when layer 2 walks, which finds how to make them in the movement copy; the
     *  layer 1 follower has no way to. */
    boolean gaps = false;
    /** Offer glides off drops with an elytra (layer 2 with elytra hops on, a glider worn). */
    boolean glides = false;
    /** Offer loose moves (Loose): the loosened layer 1, only when the proven moves found no way. */
    boolean loose = false;
    /** Offer walking through closed wooden doors (OPEN): Walk opens them on the way (DoorOpener). */
    boolean doors = false;
    /** The Open doors setting as the walk last read it (Walk.replan): the planner's searches, layer 2's field and the
     *  time profile take it from here, not from Settings (which the unit tests can't load). */
    static volatile boolean doorsOn = false;
    /** Moves not to offer, by Loose.edge(from, to): loose moves whose check in the movement copy failed, and moves the
     *  walk stood before while the jump search found no way over them (Walk's blocked moves). Null: none. */
    it.unimi.dsi.fastutil.longs.LongSet blocked = null;
    /** Extra costs on moves (by Loose.edge): loose moves whose quick check found no way (doubtful, LooseCheck), priced up
     *  so the search looks for a way round them before they get the full check. Null: none. */
    it.unimi.dsi.fastutil.longs.Long2DoubleMap penalty = null;
    /** Loose moves offered beyond the proven ones without the loose search (a loose route's own, for layer 2's field and
     *  the route's time): from node to its landings. Null: none. */
    it.unimi.dsi.fastutil.longs.Long2ObjectMap<long[]> routeLoose = null;
    /** The floor being expanded (for the blocked moves' keys). */
    private long expanding;

    /** Offer the loose moves of a search's result (its LOOSE steps) as moves from their take-offs. */
    void looseOf(PathSearch.Result r) {
        if (r == null) return;
        for (int i = 1; i < r.path.length; i++) {
            if (r.moves[i] != LOOSE) continue;
            if (routeLoose == null) routeLoose = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
            long[] old = routeLoose.get(r.path[i - 1]);
            long[] nu = old == null ? new long[1] : java.util.Arrays.copyOf(old, old.length + 1);
            nu[nu.length - 1] = r.path[i];
            routeLoose.put(r.path[i - 1], nu);
        }
    }
    /** The goal column, and whether the goal lies beyond the loaded area (then edge floors get an EXIT move). */
    int goalX, goalZ;
    boolean goalUnknown;
    /** Ticks a block past the loaded edge, the same for every way out (PathSearch sets it). */
    double beyondRate;

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
        this.beyondRate = c.sprint;
    }

    private void add(long node, double cst, int mv) {
        if (blocked != null && node != Long.MIN_VALUE && (blocked.contains(Loose.edge(expanding, node))
            || mv == LOOSE && (blocked.contains(Loose.landingKey(v, node)) || blocked.contains(Loose.area(v, expanding, node)))
            || mv == GAP && blocked.contains(Loose.strictEdge(expanding, node)))) return;
        if (penalty != null && node != Long.MIN_VALUE) cst += penalty.get(Loose.edge(expanding, node));
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
        expanding = node;
        int x = View.nodeX(node), z = View.nodeZ(node), i = View.nodeI(node);
        ChunkFloors a = v.chunk(x, z);
        if (a == null) return;
        int ai = a.index(x & 15, z & 15, i);
        int type = a.type(ai);
        float ah = a.h(ai);
        boolean edge = false;
        if (type == ChunkFloors.SWIM) waterRound(x, z, ah);

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
            if (d >= 8 && type == ChunkFloors.CLIMB) continue;      // knight's moves on foot and swimming
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
        if (gaps && type == ChunkFloors.GROUND) gapMoves(a, ai, x, z);
        if (glides && type == ChunkFloors.GROUND) glideMoves(a, ai, x, z);
        if (type == ChunkFloors.GROUND && (loose || routeLoose != null)) looseMoves(a, ai, x, z, node);
        if (edge && goalUnknown) {
            double gx = goalX + 0.5 - (x + 0.5), gz = goalZ + 0.5 - (z + 0.5);
            add(Long.MIN_VALUE, Math.sqrt(gx * gx + gz * gz) * beyondRate, EXIT);
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
                if (!walkFits(a, ai, x, z, b, bj, bx, bz, d)) {
                    // a closed wooden door in the way, in this column or the next at the body's height: through it, opened
                    if (doors && straight && (closedDoor(x, z, ah) || closedDoor(bx, bz, bh))
                        && b.head(bj) >= H - 1e-4 && a.head(ai) >= H - 1e-4)
                        add(target, len * c.rate(canSprint, slow, wade) + DOOR_COST + hazardCost(b, bj, bx, bz), OPEN);
                    return;
                }
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
                if (!plainOrVine(a, ai) || !plainOrVine(b, bj)) return;
                if (a.clearTop(ai) < bh + H - 1e-4) return;
                add(target, c.jumpCost(len, dh) + hazardCost(b, bj, bx, bz), JUMP);
            } else if (straight && dh < -STEP) {
                boolean water = (b.flags(bj) & ChunkFloors.WADE) != 0;
                if (-dh > Costs.fallLimit(c.maxFall, fallFactor(b, bj)) + 1e-5 && !water) return;
                // fall through the other column: free over the whole cell from its floor to above where we start
                if (!plainOrVine(b, bj) || b.clearTop(bj) < ah + H - 1e-4) return;
                if (!plain(a, ai) && !sweepFits(a, ai, x, z, bx, bz, ah)) return;
                add(target, c.dropCost(len, -dh) + c.damageCost(-dh, fallFactor(b, bj)) + hazardCost(b, bj, bx, bz), DROP);
            }
        } else if (btype == ChunkFloors.SWIM && (straight || diagonal) && d < 4) {
            // into deep water from a bank at or above its top: fall through the air above it
            if (ah < bh - 0.5) return;
            if (b.clearTop(bj) < ah + H - 1e-4) return;
            if (a.clearTop(ai) < ah + H - 1e-4) return;
            double fall = Math.max(0, ah - bh);
            add(target, len * c.walk * c.walkOffFraction + Costs.fallTicks(fall) + c.water * (1 - c.walkOffFraction)
                + c.waterPenalty, WATER_IN);
        } else if (btype == ChunkFloors.CLIMB && straight) {
            // walk into a ladder at about the same height, or step down onto one
            if (dh > STEP + 1e-5 || dh < -JUMP_MAX - 1e-5) return;
            float top = Math.max(ah, bh);
            if (bh + b.head(bj) < top + H - 1e-4) return;
            if (a.clearTop(ai) < top + H - 1e-4) return;
            add(target, len * c.walk + (dh < 0 ? Costs.fallTicks(-dh) : 0), CLIMB_ON);
        }
    }

    private void fromSwim(ChunkFloors a, int ai, int x, int z, ChunkFloors b, int bj, int bx, int bz, int btype, int d,
                          boolean straight, boolean diagonal, long target) {
        float ah = a.h(ai), bh = b.h(bj), dh = bh - ah;
        double len = LEN[d];
        if (btype == ChunkFloors.SWIM && (straight || diagonal)) {
            if (Math.abs(dh) > 1e-4) return;
            if (diagonal && (!sideOpenWater(DX[d], 0, x, z, ah) || !sideOpenWater(0, DZ[d], x, z, ah))) return;
            add(target, len * c.water + c.waterPenalty, SWIM);
        } else if (btype == ChunkFloors.SWIM) {
            // a knight's move across open water: the box passes over every column of the two by three it spans, each
            // a swimming floor at this water's top (without them a swim off the axes and diagonals was priced up to
            // 8.2% high, and from a shore the search swam off at 0 or 45 degrees only). Its penalty is the two moves'
            // it stands for, so a zigzag of knight's moves never undercuts a straight swim on a penalty per move.
            if (Math.abs(dh) > 1e-4 || !knightOverWater(d)) return;
            add(target, len * c.water + 2 * c.waterPenalty, SWIM);
        } else if (btype == ChunkFloors.GROUND && straight) {
            // out onto a bank no more than a quarter block above the water's top, or a shore at or below it
            if (dh > 0.25 + 1e-5 || dh < -1.0) return;
            if (b.head(bj) < H - 1e-4) return;
            if (!plain(b, bj)) return;
            if (dh > 0 && a.clearTop(ai) < bh + H - 1e-4) return;
            add(target, len * c.water + c.jumpPenalty, WATER_OUT);
        } else if (btype == ChunkFloors.CLIMB && straight) {
            // onto a ladder from the water (out of a channel whose banks are too high to climb out onto): a rung at
            // most a quarter block above the water's top and at most one below it
            if (dh > 0.25 + 1e-5 || dh < -1.0 - 1e-5) return;
            if (bh + b.head(bj) < Math.max(ah, bh) + H - 1e-4) return;
            add(target, len * c.water + c.jumpPenalty, CLIMB_ON);
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

    /** Whether column (x, z) holds a closed wooden door at the feet or the head of a body standing at height h. */
    boolean closedDoor(int x, int z, double h) {
        int y = (int) Math.floor(h + 1e-3);
        return v.block(x, y, z).is(Kind.CLOSED_DOOR) || v.block(x, y + 1, z).is(Kind.CLOSED_DOOR);
    }

    /** Room above the take-off, the gap and the landing for the jump's arc (the feet rise 1.25 at most). */
    static final double ARC = H + 1.25;

    /**
     * GAP (Parkour on): a jump from this floor over columns with nothing to stand on (a pit, a ravine, lava, a channel)
     * to a plain floor, along any of the lines in {@link Jumps}: straight, diagonal, the knight's and the lines between,
     * out to what the movement copy measured a vanilla client can clear (4 empty columns straight, 5 with a block of
     * run-up; 3 by 3 diagonally), from one down to one up, with the run-up a jump needs behind the take-off along its
     * line. Every column the jump's band crosses must be a gap with room for the arc; the landing needs room for the
     * player. How to make it is layer 2's to find in the movement copy; the cost is the sprint over the distance and a
     * jump's penalty.
     */
    private void gapMoves(ChunkFloors a, int ai, int x, int z) {
        // a slow floor (soul sand, slime) gives no sprint's run-up: the table's jumps don't hold from it (the loose
        // search offers the jump at an edge, checked in the copy)
        if (!plain(a, ai) || (a.flags(ai) & ChunkFloors.SLOW) != 0) return;
        float ah = a.h(ai);
        if (a.clearTop(ai) < ah + ARC - 1e-4) return;
        for (java.util.Map.Entry<Long, java.util.List<Jumps.Jump>> e : Jumps.BY_FIRST.entrySet()) {
            long first = e.getKey();
            int fx = x + (int) (first >> 32), fz = z + (int) first;
            ChunkFloors fb = v.chunk(fx, fz);
            // the first column jumped over: a gap at this height, or none of this group's jumps can start here
            if (fb == null || !gapColumn(fb, fx & 15, fz & 15, fx, fz, ah, ah)) continue;
            for (Jumps.Jump j : e.getValue()) jump(ah, x, z, j);
        }
    }

    /** One jump of the table from (x, z) at height ah, if its landing, gap and run-up are there. */
    private void jump(float ah, int x, int z, Jumps.Jump j) {
        // without sprinting (the gait setting's Walk, or food at 6 or less) only the gaps a walking jump makes
        if (!canSprint && !Jumps.walkable(j)) return;
        int lx = x + j.dx, lz = z + j.dz;
        ChunkFloors b = v.chunk(lx, lz);
        if (b == null) return;
        int cnt = b.count(lx & 15, lz & 15);
        for (int k = 0; k < cnt; k++) {
            int bj = b.index(lx & 15, lz & 15, k);
            if (b.type(bj) != ChunkFloors.GROUND || !plain(b, bj) || (b.flags(bj) & ChunkFloors.WADE) != 0) continue;
            float bh = b.h(bj), dh = bh - ah;
            // the table's height: the landing from just above dh - 1 to dh (a lower landing is no harder)
            if (dh > j.dh + 1e-4 || dh <= j.dh - 1 + 1e-4) continue;
            if (b.clearTop(bj) < Math.max(ah, bh) + H - 1e-4 || b.head(bj) < H - 1e-4) continue;
            float low = Math.min(ah, bh);
            boolean ok = true;
            for (int i = 0; i < j.over.length && ok; i += 2) {
                int cx = x + j.over[i], cz = z + j.over[i + 1];
                ChunkFloors cb = v.chunk(cx, cz);
                ok = cb != null && gapColumn(cb, cx & 15, cz & 15, cx, cz, ah, low);
            }
            for (int i = 0; i < j.runway.length && ok; i += 2) ok = runwayColumn(x + j.runway[i], z + j.runway[i + 1], ah);
            if (!ok) continue;
            add(View.node(lx, lz, k), j.length * c.sprint + c.gapPenalty + hazardCost(b, bj, lx, lz), GAP);
        }
    }

    /**
     * LOOSE (the loosened layer 1, Loose): from a ground floor at an edge (Loose's take-offs: a floor not plain, or one
     * with a neighbouring column holding no floor within a step of it), a jump to any floor within Loose.reach for the
     * landing's height (a ground floor with room to stand, at most a jump up and no more than a safe fall down unless
     * into water, or a climbable's cell at most a jump up), where no proven move already goes. The route's own loose
     * moves (routeLoose) are offered from their take-offs with or without the loose search.
     */
    private void looseMoves(ChunkFloors a, int ai, int x, int z, long node) {
        float ah = a.h(ai);
        if (a.head(ai) < H - 1e-4) return;
        int strict = n;
        if (routeLoose != null) {
            long[] own = routeLoose.get(node);
            // the route's own: as the loose search chose them (a bounce off slime among them, whose straight line may
            // cross a wall the bounce goes round), so no flight line
            if (own != null) for (long t : own) looseTo(ah, x, z, t, strict, false);
        }
        if (!loose) return;
        bounceMoves(ah, x, z);
        if (!looseEdge(a, ai, x, z)) return;
        int r = (int) Math.ceil(Loose.MAX_REACH);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (dx == 0 && dz == 0) continue;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > Loose.MAX_REACH + 1e-9) continue;
                int bx = x + dx, bz = z + dz;
                ChunkFloors b = v.chunk(bx, bz);
                if (b == null) continue;
                int cnt = b.count(bx & 15, bz & 15);
                for (int j = 0; j < cnt; j++) {
                    int bj = b.index(bx & 15, bz & 15, j);
                    float bh = b.h(bj);
                    double reach = Loose.reach(bh - ah, Costs.fallLimit(c.safeFall, fallFactor(b, bj)));
                    // more than a jump up, onto an edge beside it and a step: a jump's reach at its height
                    if (reach == 0.0 && d <= Loose.reach(Loose.MAX_UP) + 1e-9 && stepEdge(x, z, ah, bx, bz, bh)) reach = Loose.reach(Loose.MAX_UP);
                    if (d > reach + 1e-9) continue;
                    looseTo(ah, x, z, View.node(bx, bz, j), strict, true);
                }
            }
    }

    /** The loose move from the floor being expanded (at ah, column x z) to floor `target`, if it is one: see looseMoves. */
    private void looseTo(float ah, int x, int z, long target, int strict, boolean flight) {
        int bx = View.nodeX(target), bz = View.nodeZ(target);
        ChunkFloors b = v.chunk(bx, bz);
        if (b == null) return;
        int bj = v.index(target);
        if (bj < 0) return;
        int btype = b.type(bj);
        float bh = b.h(bj);
        double dh = bh - ah;
        if (btype == ChunkFloors.GROUND) {
            if (dh > Loose.MAX_UP + 1e-4 && !stepEdge(x, z, ah, bx, bz, bh) || b.head(bj) < H - 1e-4) return;
            if (!flight) { add(target, Loose.cost(c, Math.hypot(bx - x, bz - z), dh) + c.damageCost(-dh, fallFactor(b, bj)) + hazardCost(b, bj, bx, bz), LOOSE); return; }
            if (dh < -Costs.fallLimit(c.safeFall, fallFactor(b, bj)) - 1e-4 && (b.flags(bj) & ChunkFloors.WADE) == 0) return;
        } else if (btype == ChunkFloors.CLIMB) {
            if (dh > Loose.CLIMB_UP + 1e-4 || dh < -c.safeFall - 1e-4) return;
        } else return;
        for (int k = 0; k < strict; k++) if (to[k] == target) return;   // a proven move goes there
        if (flight && FLIGHT_CHECK && !flightOpen(x, z, ah, bx, bz, bh)) return;
        if (flight && DESCENT_CHECK && btype == ChunkFloors.GROUND
            && !descentOpen(x, z, ah, bx, bz, bh, b.open(bj) ? Double.POSITIVE_INFINITY : bh + b.head(bj))) return;
        double d = Math.hypot(bx - x, bz - z);
        add(target, Loose.cost(c, d, dh) + c.damageCost(-dh, btype == ChunkFloors.GROUND ? fallFactor(b, bj) : 1f) + hazardCost(b, bj, bx, bz), LOOSE);
    }

    /**
     * Whether a landing more than a jump over the take-off (Loose.MAX_UP, at most a step more) has an edge beside it a
     * jump lands on and steps up from: in one of the landing's eight neighbouring columns on the take-off's side, a block
     * the table knows whose collision top (anywhere in its cell) is at most a jump over the take-off and a step (STEP)
     * under the landing, not a floor of its own there (a floor is the proven moves' jump and step) and not one that
     * hurts. The Parkour Spiral's leg 37: a lamp post's daylight detector at 196.375 over a shelf at 195 is reached over
     * the open trapdoor hung on the lamp's side, whose panel top at 196 lies off its column's middle (no floor in the
     * table); the copy finds the way (jump onto the panel, step onto the detector) and the graph offered nothing.
     */
    boolean stepEdge(int ax, int az, double ah, int bx, int bz, double bh) {
        if (bh - ah > Loose.MAX_UP + STEP + 1e-4) return false;
        int y = (int) Math.floor(bh - 1e-3);
        for (int d = 0; d < 8; d++) {
            if (DX[d] * (ax - bx) + DZ[d] * (az - bz) <= 0) continue;
            int nx = bx + DX[d], nz = bz + DZ[d];
            for (int yy = y; yy >= y - 1; yy--) {
                Kind k = v.blockKnown(nx, yy, nz);
                if (k == null || Float.isNaN(k.cellTop) || k.is(Kind.HAZARD) || k.is(Kind.FLOOR_HAZARD) || k.is(Kind.LAVA)
                    || k == Palette.SOLID || k == Palette.UNKNOWN || k == Palette.HAZARD_SOLID) continue;
                double t = yy + k.cellTop;
                if (t > ah + Loose.MAX_UP + 1e-4 || t < bh - STEP - 1e-4 || t > bh + 1e-4) continue;
                if (floorAt(nx, nz, t)) continue;
                return true;
            }
        }
        return false;
    }

    /** Whether column (x, z) has a ground floor within 0.05 of height h. */
    private boolean floorAt(int x, int z, double h) {
        ChunkFloors b = v.chunk(x, z);
        if (b == null) return false;
        int cnt = b.count(x & 15, z & 15);
        for (int j = 0; j < cnt; j++) {
            int bj = b.index(x & 15, z & 15, j);
            if (b.type(bj) == ChunkFloors.GROUND && Math.abs(b.h(bj) - h) < 0.05) return true;
        }
        return false;
    }

    /** How far under a take-off a slime floor is offered for a bounce (blocks): a drop from a level's treetops onto
     *  slime on its floor (the Parkour Spiral's leg 22: from a canopy at 143 onto a pad at 135, the way onto a mound at
     *  138). The jump search follows a try that far down while slime or a bed is under it (Recipe.overBounce). */
    static final double BOUNCE_DEPTH = 10.0;

    /**
     * Bounces off slime (loose): from the take-off, a fall onto a slime floor at least a block below it (and no more than
     * BOUNCE_DEPTH) within Loose.bounceReach of the fall, and the bounce on to any
     * ground floor within BOUNCE_REACH of the slime and no higher than a jump over the take-off (the bounce gives back
     * the fall, less the air's drag). One loose move from the take-off to that floor, the bounce inside it: the jump search
     * goes on through a bounce (Recipe: on the ground and going up). On the Parkour Spiral a checkpoint four blocks over
     * the walkway is reached so, by a drop from the level before onto slime at the walkway's height.
     */
    private void bounceMoves(float ah, int x, int z) {
        int r = (int) Math.ceil(Loose.bounceReach(BOUNCE_DEPTH));
        it.unimi.dsi.fastutil.longs.LongArrayList slimes = null;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                double ds = Math.sqrt(dx * dx + dz * dz);
                if (ds > Loose.bounceReach(BOUNCE_DEPTH) + 1e-9) continue;
                ChunkFloors b = v.chunk(x + dx, z + dz);
                if (b == null) continue;
                int cnt = b.count((x + dx) & 15, (z + dz) & 15);
                for (int j = 0; j < cnt; j++) {
                    int bj = b.index((x + dx) & 15, (z + dz) & 15, j);
                    float bh = b.h(bj);
                    if (b.type(bj) != ChunkFloors.GROUND || bh > ah - 1.0 + 1e-4 || bh < ah - BOUNCE_DEPTH
                        || ds > Loose.bounceReach(ah - bh) + 1e-9) continue;
                    if (!Palette.get(b.window(bj, 0)).info.is(ai.rrw.kine.move.BlockInfo.SLIME)) continue;
                    if (slimes == null) slimes = new it.unimi.dsi.fastutil.longs.LongArrayList();
                    slimes.add(View.node(x + dx, z + dz, j));
                }
            }
        if (slimes == null) return;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet have = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int k = 0; k < n; k++) have.add(to[k]);
        for (int si = 0; si < slimes.size(); si++) {
            long sl = slimes.getLong(si);
            int sx = View.nodeX(sl), sz = View.nodeZ(sl);
            double dAS = Math.hypot(sx - x, sz - z);
            float s1h = v.chunk(sx, sz).h(v.index(sl));
            double peak1 = bouncePeak(ah + Loose.MAX_UP, s1h);
            bounceTargets(ah, x, z, sx, sz, dAS, peak1, have);
            // a second bounce: on to another slime floor within reach, at least a block under the first bounce's top,
            // and from it to the landing. On the Parkour Spiral a garden three blocks over the floor beside it is reached
            // so, by two slime pads under the level before.
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    double d12 = Math.sqrt(dx * dx + dz * dz);
                    if (d12 > Loose.BOUNCE_REACH || d12 < 1.0) continue;
                    int tx = sx + dx, tz = sz + dz;
                    ChunkFloors b = v.chunk(tx, tz);
                    if (b == null) continue;
                    int cnt = b.count(tx & 15, tz & 15);
                    for (int j = 0; j < cnt; j++) {
                        int bj = b.index(tx & 15, tz & 15, j);
                        float bh = b.h(bj);
                        if (b.type(bj) != ChunkFloors.GROUND || bh > peak1 - 1.0 + 1e-4 || bh < s1h - BOUNCE_DEPTH) continue;
                        if (!Palette.get(b.window(bj, 0)).info.is(ai.rrw.kine.move.BlockInfo.SLIME)) continue;
                        bounceTargets(ah, x, z, tx, tz, dAS + d12, bouncePeak(peak1, bh), have);
                    }
                }
        }
    }

    /** About how high a bounce off slime whose top is at sh goes, from a fall that started at rest at top: the copy's
     *  fall and bounce (gravity 0.08, drag 0.98 a tick), the bounce by the running release's rule (26.2+: Entity.move's
     *  restitution, which gives back the part of the tick's fall made before the hit; before: the speed reversed on the
     *  hit, then a tick's gravity), plus BOUNCE_SLACK. Was the fall less a tenth, which overshot by 0.6 at a 3.75 fall
     *  (2.53 and 2.82 in the copy, 3.38 offered: the Parkour Spiral's leg 22, 17 checks of bounces off a pad from a 2.5
     *  high pillar onto a mound 3 up, none possible) and by 2.6 at 9.25 (drag: 5.7 and 6.1). */
    static double bouncePeak(double top, double sh) {
        double fall = top - sh;
        if (!(fall > 0.0)) return sh;
        int i = (int) Math.min(BOUNCE_TABLE.length - 1, Math.round(fall / BOUNCE_STEP));
        return sh + BOUNCE_TABLE[i] + BOUNCE_SLACK;
    }

    /** Slack on bouncePeak (blocks): a jump's peak isn't quite at rest, the jump's sprint boost, a fall's phase. */
    static final double BOUNCE_SLACK = 0.15;
    private static final double BOUNCE_STEP = 0.125;
    /** The bounce's height over the pad by the fall to it from rest (BOUNCE_STEP apart, to 16 blocks). */
    private static final double[] BOUNCE_TABLE = bounceTable(ai.rrw.kine.compat.Rules.BOUNCE_RESTITUTION);

    static double[] bounceTable(boolean restitution) {
        double[] t = new double[(int) (16.0 / BOUNCE_STEP) + 1];
        for (int i = 0; i < t.length; i++) t[i] = bounceHeight(i * BOUNCE_STEP, restitution);
        return t;
    }

    /** The copy's bounce height over a pad from a fall of `fall` blocks from rest (its peak: the best of the tick
     *  phases, eight starts a tick apart in eighths). */
    static double bounceHeight(double fall, boolean restitution) {
        double best = 0.0;
        for (int ph = 0; ph < 8; ph++) {
            double y = fall, vy = -0.08 * ph / 8.0;
            double nvy = 0.0;
            for (int t = 0; t < 400; t++) {
                double ny = y + vy;
                if (ny <= 0.0 && vy < 0.0) {
                    if (restitution) {
                        double portion = -y / vy;
                        nvy = (portion * 0.08 - vy) * (1.0 + portion * ((double) 0.98F - 1.0));
                    } else nvy = -vy;
                    break;
                }
                y = ny;
                vy = (vy - 0.08) * (double) 0.98F;
            }
            double h = 0.0, v = (nvy - 0.08) * (double) 0.98F, peak = 0.0;
            for (int t = 0; t < 400 && v > 0.0; t++) { h += v; v = (v - 0.08) * (double) 0.98F; peak = Math.max(peak, h); }
            best = Math.max(best, peak);
        }
        return best;
    }

    /** The landings of a bounce off the slime in column sx sz (reached over dBefore blocks from the take-off at ah,
     *  column x z) that goes up to peak: ground floors within BOUNCE_REACH of it, no more than a tenth over the
     *  copy's top (peak less BOUNCE_SLACK), no higher than a jump over the take-off and no more than a safe fall under the peak, not slime. */
    private void bounceTargets(float ah, int x, int z, int sx, int sz, double dBefore, double peak,
                               it.unimi.dsi.fastutil.longs.LongOpenHashSet have) {
        int r = (int) Math.ceil(Loose.BOUNCE_REACH);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                double dSB = Math.sqrt(dx * dx + dz * dz);
                if (dSB > Loose.BOUNCE_REACH) continue;
                int bx = sx + dx, bz = sz + dz;
                if (bx == x && bz == z) continue;
                ChunkFloors b = v.chunk(bx, bz);
                if (b == null) continue;
                int cnt = b.count(bx & 15, bz & 15);
                for (int j = 0; j < cnt; j++) {
                    int bj = b.index(bx & 15, bz & 15, j);
                    float bh = b.h(bj);
                    // the landing's fall is from the bounce's top, not the take-off's (a drop onto slime takes none);
                    // the landing up to a tenth over the copy's top (peak less BOUNCE_SLACK): on the Parkour Spiral's
                    // leg 33 the copy lands a bounce from a post 2.5 over the slime back on a post at its height, 0.05
                    // under the top (helper B; a quarter under the peak refused it)
                    if (b.type(bj) != ChunkFloors.GROUND || bh > ah + Loose.MAX_UP + 1e-4 || bh > peak - BOUNCE_SLACK + 0.1
                        || bh < peak - Costs.fallLimit(c.safeFall, fallFactor(b, bj)) - 1e-4 || b.head(bj) < H - 1e-4) continue;
                    if (Palette.get(b.window(bj, 0)).info.is(ai.rrw.kine.move.BlockInfo.SLIME)) continue;
                    long target = View.node(bx, bz, j);
                    if (!have.add(target)) continue;
                    add(target, Loose.bounceCost(c, dBefore + dSB) + hazardCost(b, bj, bx, bz), LOOSE);
                }
            }
    }

    /**
     * Whether a jump from floor (ax, az) at height ah to floor (bx, bz) at bh can get through, by the table alone: the
     * straight line between the columns' centres, or one beside it (0.9 to either side: a neo's way round a wall's
     * end), crosses no wall at the body's height over the higher floor (a block covering its cell across, more than half
     * a block tall: the cells half a block and one and a half over it both open, or the one and a half and the two and a
     * half over it: two open cells for the body, walking or jumping over a block), past the take-off's and short of the landing's own
     * columns. A wall across all three is no jump of any kind; the copy's check would only spend its time finding so
     * (the Parkour Spiral offline: dozens of checks of jumps through the tower's own walls).
     */
    static boolean FLIGHT_CHECK = !"0".equals(System.getenv("KINE_FLIGHT_CHECK"));

    boolean flightOpen(int ax, int az, double ah, int bx, int bz, double bh) {
        double top = Math.max(ah, bh);
        int y1 = (int) Math.floor(top + 0.5), y2 = (int) Math.floor(top + 1.5), y3 = y2 + 1;
        double sx = ax + 0.5, sz = az + 0.5, ex = bx + 0.5, ez = bz + 0.5;
        double lx = ex - sx, lz = ez - sz, len = Math.hypot(lx, lz);
        if (len < 1.5) return true;
        double nx = -lz / len, nz = lx / len;
        for (double off : new double[]{0.0, 0.9, -0.9}) {
            boolean clear = true;
            for (double t = 0.5; t <= len - 0.5 && clear; t += 0.25) {
                double px = sx + lx / len * t + nx * off, pz = sz + lz / len * t + nz * off;
                int cx = (int) Math.floor(px), cz = (int) Math.floor(pz);
                if (cx == ax && cz == az || cx == bx && cz == bz) continue;
                // the body (1.8) needs two open cells one over the other: y1 and y2 (walking, or under a low
                // ceiling), or y2 and y3 (jumping over a block a cell high). A wall across both y1 and y2 was all that
                // counted, so a window a cell high (a house wall's fence-barred window at 155 between sandstone at 154
                // and 156: the Parkour Spiral's leg 28) let dozens of jumps through the wall each fail in the copy
                boolean alongX = Math.abs(lx) >= Math.abs(lz);
                boolean w2 = wallCell(cx, y2, cz, alongX);
                if (w2 || wallCell(cx, y1, cz, alongX) && wallCell(cx, y3, cz, alongX)) clear = false;
            }
            if (clear) return true;
        }
        return false;
    }

    /** The descent check of loose moves (descentOpen); KINE_DESCENT_CHECK=0 leaves it out (for comparing). */
    static boolean DESCENT_CHECK = FLIGHT_CHECK && !"0".equals(System.getenv("KINE_DESCENT_CHECK"));

    /**
     * Whether a loose move from floor (ax, az) at height ah down onto floor (bx, bz) at bh, whose free height ends at
     * `ceil`, has a way down by the table. When the landing's free height reaches over the body standing at the
     * take-off, the body comes down into the landing's column from above. When it ends lower (a floor under a ceiling:
     * a room's floor under the take-off's own level), the body has to come down in another column and go in under that
     * ceiling from the side: a column beside the take-off, or one on the way (on the straight line between the columns'
     * centres or 0.9 to either side, as the flight line, up to a wall at the body's height over the take-off), with no
     * wall from the highest feet height that fits under the ceiling up to the body's top at the take-off, and from it no
     * wall at the body's height over the landing on the straight line in. Walls as the flight line counts them; the
     * copy's check decides the rest. The Parkour Spiral's leg 32 (with 6 points of fall damage allowed): the loose search
     * offered drops from the roof of a lava room, a floor of end stone and coral at 170, onto the stepping slabs under it
     * at 164, with no column open on the way; 31 such moves failed their checks in the copy and the leg ran out of time
     * before the one way in, the drop down the tower's shaft, came up.
     */
    boolean descentOpen(int ax, int az, double ah, int bx, int bz, double bh, double ceil) {
        double top = ah + H;
        if (ceil >= top - 1e-4) return true;
        // a column to come down in: free from the highest feet under the ceiling (y0) to the body's top (y1)
        int y0 = (int) Math.floor(ceil - H + 1e-3), y1 = (int) Math.floor(top - 1e-3);
        int hi1 = (int) Math.floor(ah + 0.5), hi2 = (int) Math.floor(ah + 1.5);
        // beside the take-off (stepped off any of its edges)
        for (int d = 0; d < 8; d++) {
            int cx = ax + DX[d], cz = az + DZ[d];
            if ((cx != bx || cz != bz) && columnFree(cx, cz, y0, y1) && lowPath(cx, cz, bx, bz, bh)) return true;
        }
        // on the way: the straight line and the lines 0.9 to either side, as far as the body gets at the take-off's height
        double sx = ax + 0.5, sz = az + 0.5, lx = bx - ax, lz = bz - az, len = Math.hypot(lx, lz);
        double nx = -lz / len, nz = lx / len;
        for (double off : new double[]{0.0, 0.9, -0.9})
            for (double t = 0.25; t <= len - 0.25 + 1e-9; t += 0.25) {
                int cx = (int) Math.floor(sx + lx / len * t + nx * off), cz = (int) Math.floor(sz + lz / len * t + nz * off);
                if (cx == ax && cz == az || cx == bx && cz == bz) continue;
                if (columnFree(cx, cz, y0, y1) && lowPath(cx, cz, bx, bz, bh)) return true;
                boolean alongX = Math.abs(lx) >= Math.abs(lz);
                if (wallCell(cx, hi1, cz, alongX) && wallCell(cx, hi2, cz, alongX)) break;   // a wall at the body's height: no further
            }
        return false;
    }

    /** Whether the straight line from column (x, z) to the landing (bx, bz) at height bh crosses no wall at the body's
     *  height over the landing (the flight line's two cells), past both ends: the way in under a ceiling. */
    private boolean lowPath(int x, int z, int bx, int bz, double bh) {
        int lo1 = (int) Math.floor(bh + 0.5), lo2 = (int) Math.floor(bh + 1.5);
        double sx = x + 0.5, sz = z + 0.5, lx = bx - x, lz = bz - z, len = Math.hypot(lx, lz);
        for (double t = 0.25; t <= len - 0.25 + 1e-9; t += 0.25) {
            int cx = (int) Math.floor(sx + lx / len * t), cz = (int) Math.floor(sz + lz / len * t);
            if (cx == x && cz == z || cx == bx && cz == bz) continue;
            boolean alongX = Math.abs(lx) >= Math.abs(lz);
            if (wallCell(cx, lo1, cz, alongX) && wallCell(cx, lo2, cz, alongX)) return false;
        }
        return true;
    }

    /** Whether column (x, z) has no wall (wallCell) in block heights y0 to y1. */
    private boolean columnFree(int x, int z, int y0, int y1) {
        // coming down a column: a thin block spanning it either way fills it for the body
        for (int y = y0; y <= y1; y++) if (wallCell(x, y, z, true) || wallCell(x, y, z, false)) return false;
        return true;
    }

    /** A cell a body can't pass through: unknown or solid as far as the table knows, or a block covering the whole cell
     *  across and more than half a block tall, or a thin one more than half a block tall whose collision spans the cell
     *  across the way (alongX: the jump goes more along x than z). Other thin ones (a lone post, bamboo) a jump can pass
     *  beside: they don't count (the copy's check decides). */
    private boolean wallCell(int x, int y, int z, boolean alongX) {
        Kind k = v.blockKnown(x, y, z);
        if (k == null) return false;   // not known to the table (a tall gap's middle): not counted as a wall
        if (k == Palette.SOLID || k == Palette.UNKNOWN || k == Palette.HAZARD_SOLID) return true;
        if (Float.isNaN(k.cellBottom) || k.cellTop - k.cellBottom <= 0.5) return false;
        if (k.topFull) return true;
        // a thin block joined across the way (a fence's or a pane's arms both sides, a closed door, a wall's arms): its
        // collision spans the cell across the jump's way, no room to pass beside it. The Parkour Spiral's leg 28: the
        // house wall's windows at x -8 are barred by fences joined north and south, and jumps west through them were
        // each checked in the copy and failed
        return k.spans(alongX ? 2 : 0);
    }

    /** A loose jump's take-off: a floor not plain, or one with a neighbouring column (of the eight) holding no ground
     *  floor within a step of it (an edge: a drop, a gap, a wall), or only one a quarter block or more under it (a raised
     *  take-off: a slab on the floor, whose jump reaches higher than its neighbours'; on the Parkour Spiral the way up
     *  from leg 17's walkway starts from one). An inner floor of open ground offers none: a jump from it would start
     *  from the edge floor beside it, which has them (the jump search's run-up spots go back). */
    private boolean looseEdge(ChunkFloors a, int ai, int x, int z) {
        if (!plain(a, ai)) return true;
        float ah = a.h(ai);
        for (int d = 0; d < 8; d++) {
            int bx = x + DX[d], bz = z + DZ[d];
            ChunkFloors b = v.chunk(bx, bz);
            if (b == null) return true;
            int cnt = b.count(bx & 15, bz & 15);
            boolean floor = false;
            for (int j = 0; j < cnt && !floor; j++) {
                int bj = b.index(bx & 15, bz & 15, j);
                floor = b.type(bj) == ChunkFloors.GROUND && Math.abs(b.h(bj) - ah) <= STEP + 1e-5 && b.head(bj) >= H - 1e-4
                    && b.h(bj) > ah - 0.25f;
            }
            if (!floor) return true;
        }
        return false;
    }

    /** A column of the run-up behind a take-off: a plain floor at the take-off's height with room to run, not slow. */
    private boolean runwayColumn(int cx, int cz, float ah) {
        ChunkFloors b = v.chunk(cx, cz);
        if (b == null) return false;
        int cnt = b.count(cx & 15, cz & 15);
        for (int k = 0; k < cnt; k++) {
            int bj = b.index(cx & 15, cz & 15, k);
            if (b.type(bj) == ChunkFloors.GROUND && plain(b, bj) && Math.abs(b.h(bj) - ah) < 1e-3 && b.head(bj) >= H - 1e-4
                && (b.flags(bj) & (ChunkFloors.WADE | ChunkFloors.SLOW)) == 0) return true;
        }
        return false;
    }

    /** A column jumped over: no floor within a step of anything from the landing's height (low) to the take-off's, and
     *  free of collisions, water and hazards from low to the top of the arc. */
    private boolean gapColumn(ChunkFloors b, int lx, int lz, int cx, int cz, float ah, float low) {
        int cnt = b.count(lx, lz);
        // free from low to the arc's top: inside the free space over one of its floors, or a column with no floor at all
        // (nothing in it to stand on anywhere: open to the void)
        boolean free = cnt == 0 || b.openFrom(lx, lz) <= low + 1e-4;   // no floor at all (open to the void), or open sky
        for (int j = 0; j < cnt; j++) {
            int bj = b.index(lx, lz, j);
            float h = b.h(bj);
            if (h > low - STEP - 1e-5 && h < ah + STEP + 1e-5) return false;
            if (b.type(bj) == ChunkFloors.SWIM && h > low - 1e-4) return false;
            if (b.type(bj) == ChunkFloors.CLIMB) continue;
            if (b.cellTop(bj) <= low + 1e-4 && b.clearTop(bj) >= ah + ARC - 1e-4) free = true;
        }
        if (!free) return false;
        return !v.hazardIn(cx, cz, (int) Math.floor(low + 1e-4), (int) Math.floor(ah + ARC - 1e-4));
    }

    /** How far a glide is offered, blocks; and the least drop for one (less is a drop or a gap's job). */
    static final double GLIDE_REACH = 24.0, GLIDE_MIN_DROP = 2.0;

    /**
     * GLIDE (elytra hops on): off an edge with a drop of more than a safe fall in front, along one of the eight
     * directions, to a plain floor at least 2 below and within reach of a glide: no farther than 2 + 2.5 times the drop
     * (measured on the copy: from 30 up a glide carries over 100 blocks; this keeps well inside), over columns whose
     * floors all lie half a block under the straight line from the take-off to the landing. How (the jump, opening the
     * glider, the pitch) is layer 2's to find.
     */
    private void glideMoves(ChunkFloors a, int ai, int x, int z) {
        if (!plain(a, ai)) return;
        float ah = a.h(ai);
        if (a.clearTop(ai) < ah + ARC - 1e-4) return;
        for (int d = 0; d < 8; d++) {
            // the first column must fall away: no floor from a safe fall below the take-off up to a jump above it
            ChunkFloors f = v.chunk(x + DX[d], z + DZ[d]);
            if (f == null || highestBelow(f, x + DX[d], z + DZ[d], ah + JUMP_MAX) > ah - c.maxFall - 1e-4) continue;
            for (int k = 2; k * LEN[d] <= GLIDE_REACH; k++) {
                int cx = x + DX[d] * k, cz = z + DZ[d] * k;
                ChunkFloors b = v.chunk(cx, cz);
                if (b == null) break;
                int lx = cx & 15, lz = cz & 15, cnt = b.count(lx, lz);
                double dist = k * LEN[d];
                for (int j = 0; j < cnt; j++) {
                    int bj = b.index(lx, lz, j);
                    if (b.type(bj) != ChunkFloors.GROUND || !plain(b, bj)) continue;
                    float bh = b.h(bj);
                    double drop = ah - bh;
                    if (drop < GLIDE_MIN_DROP || dist > 2.0 + 2.5 * drop) continue;
                    if (b.clearTop(bj) < bh + H - 1e-4) continue;
                    if (!glideClear(x, z, d, k, ah, bh)) continue;
                    add(View.node(cx, cz, j), c.glideCost(dist) + hazardCost(b, bj, cx, cz), GLIDE);
                }
            }
        }
    }

    /** The highest floor of column (x, z) at or below `y`, or -infinity. */
    private static float highestBelow(ChunkFloors c, int x, int z, double y) {
        int lx = x & 15, lz = z & 15, n = c.count(lx, lz);
        float best = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            float h = c.h(c.index(lx, lz, i));
            if (h <= y && h > best) best = h;
        }
        return best;
    }

    /** The columns between take-off and landing all lie half a block under the straight glide line (any floor in
     *  them, which is the top of whatever stands there). */
    private boolean glideClear(int x, int z, int d, int k, float ah, float bh) {
        for (int i = 1; i < k; i++) {
            int cx = x + DX[d] * i, cz = z + DZ[d] * i;
            ChunkFloors c = v.chunk(cx, cz);
            if (c == null) return false;
            double line = ah + 0.5 - (ah + 0.5 - bh) * i / (double) k;
            int lx = cx & 15, lz = cz & 15, n = c.count(lx, lz);
            for (int j = 0; j < n; j++) if (c.h(c.index(lx, lz, j)) > line - 0.5) return false;
        }
        return true;
    }

    /** A floor whose top covers its cell and whose cell is as free as its middle. */
    /** The share of a fall's damage a landing on floor idx takes (BlockInfo.fallFactor of the block it stands on). */
    static float fallFactor(ChunkFloors c, int idx) {
        return Palette.get(c.window(idx, 0)).info.fallFactor;
    }

    static boolean plain(ChunkFloors c, int idx) {
        return (c.flags(idx) & ChunkFloors.SIMPLE) != 0;
    }

    /** Plain, or plain but for a vine at the feet (ChunkFloors.CLIMB_PLAIN): for the jump up and the drop down. */
    static boolean plainOrVine(ChunkFloors c, int idx) {
        return (c.flags(idx) & (ChunkFloors.SIMPLE | ChunkFloors.CLIMB_PLAIN)) != 0;
    }

    /** Whether a walk (within a step up or down) from floor a to floor b in direction d fits. */
    private boolean walkFits(ChunkFloors a, int ai, int x, int z, ChunkFloors b, int bj, int bx, int bz, int d) {
        float ah = a.h(ai), bh = b.h(bj), top = Math.max(ah, bh);
        if (a.head(ai) < top - ah + H - 1e-4 || b.head(bj) < top - bh + H - 1e-4) return false;
        boolean fast = plain(a, ai) && plain(b, bj)
            && a.clearTop(ai) >= top + H - 1e-4 && b.clearTop(bj) >= top + H - 1e-4;
        boolean wade = ((a.flags(ai) | b.flags(bj)) & ChunkFloors.WADE) != 0;
        if (fast && d >= 4 && d < 8) {
            fast = sideFree(x + DX[d], z, top) && sideFree(x, z + DZ[d], top)
                || wade && wadeable(x + DX[d], z, ah, bh) && wadeable(x, z + DZ[d], ah, bh);
        } else if (fast && d >= 8) {
            int sx = Integer.signum(DX[d]), sz = Integer.signum(DZ[d]);
            if (Math.abs(DX[d]) == 1) {   // one over in x, two across in z
                fast = crossed(x, z + sz, ah, bh) && crossed(x + sx, z + sz, ah, bh)
                    && sideFree(x + sx, z, top) && sideFree(x, z + 2 * sz, top)
                    || wade && wadeable(x, z + sz, ah, bh) && wadeable(x + sx, z + sz, ah, bh)
                    && wadeable(x + sx, z, ah, bh) && wadeable(x, z + 2 * sz, ah, bh);
            } else {
                fast = crossed(x + sx, z, ah, bh) && crossed(x + sx, z + sz, ah, bh)
                    && sideFree(x, z + sz, top) && sideFree(x + 2 * sx, z, top)
                    || wade && wadeable(x + sx, z, ah, bh) && wadeable(x + sx, z + sz, ah, bh)
                    && wadeable(x, z + sz, ah, bh) && wadeable(x + 2 * sx, z, ah, bh);
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
        if (c.openFrom(lx, lz) <= lo + 1e-5) {
            // open sky over the cell (its floors, if any, are below the move): only hazards and water to check
            for (int y = (int) Math.floor(top); y <= (int) Math.floor(hi - 1e-4); y++) {
                Kind k = v.block(x, y, z);
                if (k.is(Kind.WATER) || k.blocksBody()) return false;
            }
            boolean below = true;
            for (int i = 0; i < n; i++) if (c.h(c.index(lx, lz, i)) > lo + 1e-5) below = false;
            if (below) return true;
        }
        for (int i = 0; i < n; i++) {
            int idx = c.index(lx, lz, i);
            if (c.type(idx) == ChunkFloors.CLIMB) continue;
            float ct = c.cellTop(idx);
            if (ct <= lo + 1e-5 && c.clearTop(idx) >= hi - 1e-5) {
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
            if (c.clearTop(idx) >= top + H - 1e-4 && (c.flags(idx) & ChunkFloors.WADE) == 0) return true;
        }
        return false;
    }

    /** Open water round the swimming floor being expanded, at its water's top: a bit for each of the eight columns
     *  round it and the four two away along the axes (all the columns a diagonal or knight's swim from it passes over
     *  besides its ends), worked out once per expansion. */
    private int water;

    private static int waterBit(int dx, int dz) {
        return 1 << ((dx + 2) * 5 + dz + 2);
    }

    private void waterRound(int x, int z, float top) {
        water = 0;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                if ((Math.abs(dx) <= 1 && Math.abs(dz) <= 1 || dx == 0 || dz == 0) && (dx != 0 || dz != 0) && swimAt(x + dx, z + dz, top))
                    water |= waterBit(dx, dz);
    }

    private boolean waterAt(int dx, int dz) {
        return (water & waterBit(dx, dz)) != 0;
    }

    /** The four other columns of the two by three a knight's move in direction d spans from the floor being expanded
     *  (the player's box passes over all of them): each a swimming floor at its water's top. */
    private boolean knightOverWater(int d) {
        int sx = Integer.signum(DX[d]), sz = Integer.signum(DZ[d]);
        if (Math.abs(DX[d]) == 1)   // one over in x, two across in z
            return waterAt(0, sz) && waterAt(sx, sz) && waterAt(sx, 0) && waterAt(0, 2 * sz);
        return waterAt(sx, 0) && waterAt(sx, sz) && waterAt(0, sz) && waterAt(2 * sx, 0);
    }

    /** A swimming floor in column (x, z) at the water's top `top`. */
    private boolean swimAt(int x, int z, float top) {
        ChunkFloors c = v.chunk(x, z);
        if (c == null) return false;
        int lx = x & 15, lz = z & 15, n = c.count(lx, lz);
        for (int i = 0; i < n; i++) {
            int idx = c.index(lx, lz, i);
            if (c.type(idx) == ChunkFloors.SWIM && Math.abs(c.h(idx) - top) < 1e-4) return true;
        }
        return false;
    }

    /**
     * A cell a diagonal or knight's wade crosses or clips (water at the feet at either end): a plain floor within a step
     * of both ends, water at its feet or not, room for the player above it over the whole cell, and nothing that hurts
     * from the floor block to head height. The dry checks (sideFree, crossed) refuse water at the feet, so a shallow
     * lake was waded in straight steps only, a staircase on the axes up to 41% longer than the straight line (measured
     * 2026-10-14: 120 blocks of grid for 84.9 straight at 45 degrees).
     */
    private boolean wadeable(int x, int z, float ah, float bh) {
        ChunkFloors c = v.chunk(x, z);
        if (c == null) return false;
        int lx = x & 15, lz = z & 15, n = c.count(lx, lz);
        for (int i = 0; i < n; i++) {
            int idx = c.index(lx, lz, i);
            if (c.type(idx) != ChunkFloors.GROUND || !plain(c, idx)) continue;
            float h = c.h(idx);
            if (Math.abs(h - ah) > STEP + 1e-5 || Math.abs(h - bh) > STEP + 1e-5) continue;
            float top = Math.max(h, Math.max(ah, bh));
            if (c.clearTop(idx) < top + H - 1e-4) continue;
            return !v.hazardIn(x, z, (int) Math.floor(h - 1e-4), (int) Math.floor(top + H - 1e-4));
        }
        return false;
    }

    /** A diagonal swim's side cell (dx, dz from the floor being expanded at (x, z)): water at the same top or open air
     *  over the water level. */
    private boolean sideOpenWater(int dx, int dz, int x, int z, float top) {
        return waterAt(dx, dz) || sideFree(x + dx, z + dz, top - 1);
    }

    /** The extra for a floor next to hazards: each of the eight columns around with one at body height. */
    double hazardCost(ChunkFloors b, int bj, int bx, int bz) {
        // a floor on or in a harmful block (Harmful blocks allowed: a cactus's top): the hits while
        // there, HARM_HITS half hearts at the planner's price of one
        double harm = (b.flags(bj) & ChunkFloors.HARMFUL) != 0 ? HARM_HITS * HARM_PRICE : 0.0;
        if (c.hazardNear <= 0) return harm;
        float h = b.h(bj);
        int y0 = (int) Math.floor(h - 1e-4), y1 = (int) Math.floor(h + H);
        int k = 0;
        for (int d = 0; d < 8; d++) if (v.hazardIn(bx + DX[d], bz + DZ[d], y0, y1)) k++;
        return k * c.hazardNear + harm;
    }

    /** Half hearts a stop on a harmful floor is priced at: a landing and the jump on from it (a touch hurts every half
     *  second at most, Recipe.HARM_TICKS_PER_HP). */
    static final double HARM_HITS = 2.0;
    /** The price of a half heart lost to a harmful block (ticks): a quarter of a fall's (Costs.damageTicks, 400), the
     *  setting being the walker's say-so for this damage. At the fall's price leg 43's two cactus stops cost 1600 and
     *  its searches expanded 38000 floors each, 75 s for the checked way (bench); the walk ran out of its 2400 ticks. */
    static final double HARM_PRICE = 100.0;
}
