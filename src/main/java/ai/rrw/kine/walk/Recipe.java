package ai.rrw.kine.walk;

import ai.rrw.kine.move.GameMath;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;

/**
 * A jump's inputs found in the movement copy (RW, 2026-10-13, on the harder parkour tricks: "verify with scripted
 * mvoemetnd in game, and/or verify with teh short-rnage layer 2 search"): from a state standing on the take-off,
 * forward toward a first heading (alone or with a strafe key, walking or sprinting), the jump on a chosen tick, and from
 * a chosen tick after it a turn toward the landing block's centre at most 29.5 degrees a tick. Searched over a grid of
 * those choices; the inputs of the way that lands nearest the landing's centre are kept, tick by tick, for a client to
 * replay (the rotation each tick, the keys). The copy is deterministic and the client moves itself, so the same inputs
 * from the same state land the same in the game.
 */
public final class Recipe {
    /** Per tick: the view's yaw, the keys (MoveState's bits). */
    public final float[] yaw;
    public final int[] keys;
    public final int ticks;
    /** How many of the ways searched landed, of how many. */
    public final int ways, tried;
    /** What the search chose (a template's fit names itself after it is made). */
    public String how;
    /** Where the copy says the player stands at the end, and after each tick ({x, y, z} per tick). */
    public final double endX, endY, endZ;
    public final double[] path;

    private Recipe(float[] yaw, int[] keys, int ticks, int ways, int tried, String how, double ex, double ey, double ez,
                   double[] path) {
        this.yaw = yaw; this.keys = keys; this.ticks = ticks; this.ways = ways; this.tried = tried; this.how = how;
        this.endX = ex; this.endY = ey; this.endZ = ez; this.path = path;
    }

    static float yawTo(double fx, double fz, double tx, double tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - fx), tz - fz));
    }

    /**
     * Search from `start` (a copy is made) to stand on block (lx, ly, lz) (its top at ly + 1, or within half a block
     * of it for a climbable or a partial block: onGround or climbing at its column). yaw0 from yawA to yawB by step;
     * the jump on ticks 0..maxJump; the turn from 0..maxTurn ticks after the jump; up to `limit` ticks a try. Null when
     * no way lands.
     */
    public static Recipe search(GroundPhysics phys, MoveState start, int lx, int ly, int lz, float yawA, float yawB,
                                float step, int maxJump, int maxTurn, int limit) {
        return search(phys, start, Float.NaN, lx, ly, lz, yawA, yawB, step, maxJump, maxTurn, limit);
    }

    /** As above, the view starting at startYaw and turning to each try's first heading at 29.5 degrees a tick (NaN:
     *  starting at it, as a client that sets its view at once). */
    public static Recipe search(GroundPhysics phys, MoveState start, float startYaw, int lx, int ly, int lz, float yawA,
                                float yawB, float step, int maxJump, int maxTurn, int limit) {
        return search(phys, start, startYaw, lx, ly, lz, yawA, yawB, step, maxJump, maxTurn, limit, true);
    }

    /** As above; sprint false: walking only (the gait setting's Walk), no sprint key in any try. */
    public static Recipe search(GroundPhysics phys, MoveState start, float startYaw, int lx, int ly, int lz, float yawA,
                                float yawB, float step, int maxJump, int maxTurn, int limit, boolean sprint) {
        float[] y0 = headings(yawA, yawB, step);
        return search(phys, start, startYaw, lx, ly, lz, y0, 0, y0.length, maxJump, maxTurn, limit, sprint);
    }

    /** The first headings yawA, yawA + step, ... up to yawB, as the search's loop steps them. */
    static float[] headings(float yawA, float yawB, float step) {
        int n = 0;
        for (float y = yawA; y <= yawB + 1e-3; y += step) n++;
        float[] out = new float[n];
        int i = 0;
        for (float y = yawA; y <= yawB + 1e-3; y += step) out[i++] = y;
        return out;
    }

    /**
     * The search above split by its first heading into `parts` runs side by side on `pool` (each with its own copy of
     * the movement copy's state: GroundPhysics is not shared between threads), merged as the one run would keep its
     * best: the widest margin, the earlier heading on a tie. The same recipe as the search on one thread, about `parts`
     * times sooner (the walk stands while it runs: some 250 ms on one thread).
     */
    static Recipe searchParallel(java.util.concurrent.ExecutorService pool, int parts, GroundPhysics phys, MoveState start,
                                 float startYaw, int lx, int ly, int lz, float yawA, float yawB, float step, int maxJump,
                                 int maxTurn, int limit) {
        return searchParallel(pool, parts, phys, start, startYaw, lx, ly, lz, yawA, yawB, step, maxJump, maxTurn, limit, true);
    }

    /** As above; sprint false: walking only (the gait setting's Walk). */
    static Recipe searchParallel(java.util.concurrent.ExecutorService pool, int parts, GroundPhysics phys, MoveState start,
                                 float startYaw, int lx, int ly, int lz, float yawA, float yawB, float step, int maxJump,
                                 int maxTurn, int limit, boolean sprint) {
        float[] y0 = headings(yawA, yawB, step);
        int n = y0.length, k = Math.max(1, Math.min(parts, n));
        java.util.List<java.util.concurrent.Callable<Recipe>> jobs = new java.util.ArrayList<>();
        for (int i = 0; i < k; i++) {
            int a = i * n / k, b = (i + 1) * n / k;
            jobs.add(() -> search(new GroundPhysics(phys.grid(), phys.body()), start, startYaw, lx, ly, lz, y0, a, b, maxJump,
                maxTurn, limit, sprint));
        }
        Recipe best = null;
        int ways = 0, tried = 0;
        try {
            for (java.util.concurrent.Future<Recipe> f : pool.invokeAll(jobs)) {
                Recipe r = f.get();
                ways += r.ways;
                tried += r.tried;
                if (r.found() && (best == null || r.margin > best.margin)) best = r;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return search(phys, start, startYaw, lx, ly, lz, y0, 0, n, maxJump, maxTurn, limit, sprint);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new RuntimeException(ex.getCause());
        }
        Recipe out = best == null
            ? new Recipe(new float[0], new int[0], 0, 0, tried, "", Double.NaN, Double.NaN, Double.NaN, new double[0])
            : new Recipe(best.yaw, best.keys, best.ticks, ways, tried, best.how, best.endX, best.endY, best.endZ, best.path);
        out.margin = best == null ? -1 : best.margin;
        out.lx = lx; out.ly = ly; out.lz = lz;
        return out;
    }

    /** How far inside the landing block's sides the found way ends (the search keeps the widest); -1 for none. */
    double margin = -1;

    /** The search over the first headings y0[from..to). */
    static Recipe search(GroundPhysics phys, MoveState start, float startYaw, int lx, int ly, int lz, float[] y0, int from,
                         int to, int maxJump, int maxTurn, int limit) {
        return search(phys, start, startYaw, lx, ly, lz, y0, from, to, maxJump, maxTurn, limit, true);
    }

    /** As above; allowSprint false: walking only (the gait setting's Walk), no sprint key in any try. */
    static Recipe search(GroundPhysics phys, MoveState start, float startYaw, int lx, int ly, int lz, float[] y0, int from,
                         int to, int maxJump, int maxTurn, int limit, boolean allowSprint) {
        int ways = 0, tried = 0;
        double bestMargin = -1;
        float[] bestYaw = null;
        int[] bestKeys = null;
        int bestTicks = 0;
        String bestHow = "";
        double[] bestEnd = null, bestPath = null;
        float[] yaws = new float[limit + HOLD];
        int[] ks = new int[limit + HOLD];
        double[] pos = new double[3 * (limit + HOLD)];
        float[] holdYaw = new float[HOLD];
        int[] holdKeys = new int[HOLD];
        double[] holdPos = new double[3 * HOLD];
        float wall = wallYaw(phys, lx, ly, lz);
        // a landing on scaffolding (the course's scaffolding_jump, 2026-10-15): a try that comes into the landing's
        // column below its top climbs it, the jump key held (climb), and counts only standing on the top
        boolean scaffold = phys.grid().get(lx, ly, lz).is(ai.rrw.kine.move.BlockInfo.SCAFFOLD);
        // standing still, the view far off a try's first heading: the try turns first, keys up (a run that starts
        // pressing forward while the view swings round curves off: detect_c13, pixel_landing, from its start's edge
        // the walk to the centre ended facing away, and no try from there landed)
        boolean still = start.onGround && start.vx * start.vx + start.vz * start.vz <= RunModel.STILL2;
        for (int yi = from; yi < to; yi++) {
            float yaw0 = y0[yi];
            for (int sprint = allowSprint ? 1 : 0; sprint >= 0; sprint--)
                for (int strafe = -1; strafe <= 1; strafe++)
                    for (int jumpAt = 0; jumpAt <= maxJump; jumpAt++)
                        for (int turnAt = 0; turnAt <= maxTurn; turnAt++) {
                            tried++;
                            MoveState s = start.copy();
                            int base = MoveState.FORWARD | (sprint == 1 ? MoveState.SPRINT : 0)
                                | (strafe < 0 ? MoveState.LEFT : strafe > 0 ? MoveState.RIGHT : 0);
                            float aim0 = yaw0 - strafe * 45f;
                            float view = Float.isNaN(startYaw) ? aim0 : startYaw;
                            float off = Math.abs(GameMath.wrapDegrees(aim0 - view));
                            int pre = still && off > 60f ? (int) Math.ceil((off - 29.5f) / 29.5f) : 0;
                            boolean jumped = false, won = false, climbing = false;
                            int after = -1, t = 0;
                            for (; t < limit; t++) {
                                boolean turning = t < pre;
                                int k = turning ? 0 : base;
                                if (climbing) k = climbKeys(s, view, lx, lz);
                                else if (!turning && t - pre == jumpAt && s.onGround) { k |= MoveState.JUMP; jumped = true; after = 0; }
                                // climbing, the view is held (the keys steer the player to the column's middle)
                                float want = climbing ? view : !turning && after >= turnAt ? yawTo(s.x, s.z, lx + 0.5, lz + 0.5) - strafe * 45f : aim0;
                                float d = GameMath.wrapDegrees(want - view);
                                view += Math.max(-29.5f, Math.min(29.5f, d));
                                yaws[t] = view;
                                ks[t] = k;
                                s.landedFall = 0.0;
                                s.touched = 0;
                                phys.step(s, k, view, 0f);
                                if (spoils(s)) break;
                                pos[3 * t] = s.x; pos[3 * t + 1] = s.y; pos[3 * t + 2] = s.z;
                                if (after >= 0) after++;
                                boolean onCol = s.x + 0.3 > lx && s.x - 0.3 < lx + 1 && s.z + 0.3 > lz && s.z - 0.3 < lz + 1;
                                if (scaffold && jumped && !climbing && inScaffoldColumn(phys, s, lx, ly, lz)) climbing = true;
                                // on the ground and going up: a bounce (slime, a bed), on through it
                                boolean landed = s.onGround && !(s.vy > 0.0);
                                if (jumped && after > 2 && (landed || phys.onClimbable(s))) {
                                    // scaffolding: standing on its top only (inside it the player sinks)
                                    won = onCol && s.y > ly + 0.4 && s.y < ly + 1.6 && !hurts(s) && (landed || !scaffold);
                                    // climbing, a floor inside the column on the way up is no landing
                                    if (won || landed && !climbing) { t++; break; }
                                }
                                if (climbing && !onCol && !phys.onClimbable(s)) break;   // out of the column
                                if (s.y < start.y - 6) break;
                                // off the floor it stands on before the jump (offFloor: the course's join pad beside
                                // #67's start, fence_column_neo's fence arm)
                                if (!jumped && offFloor(start, s)) break;
                            }
                            if (!won) continue;
                            if (!s.onGround) {
                                // on a ladder or a vine: it counts only if the player stays on it (HOLD ticks pressing
                                // toward the wall it hangs on, or across it against the way it came in), the inputs then
                                // part of the recipe (RW 2026-10-14: ladder_diagonal came in sideways, touched the
                                // ladder and slid past its side)
                                int hold = hold(phys, s, view, lx, ly, lz, wall, holdYaw, holdKeys, holdPos);
                                if (hold < 0) continue;
                                System.arraycopy(holdYaw, 0, yaws, t, HOLD);
                                System.arraycopy(holdKeys, 0, ks, t, HOLD);
                                System.arraycopy(holdPos, 0, pos, 3 * t, 3 * HOLD);
                                t += HOLD;
                            }
                            ways++;
                            double margin = Math.min(Math.min(s.x - lx, lx + 1 - s.x), Math.min(s.z - lz, lz + 1 - s.z));
                            if (margin > bestMargin) {
                                bestMargin = margin;
                                bestYaw = java.util.Arrays.copyOf(yaws, t);
                                bestKeys = java.util.Arrays.copyOf(ks, t);
                                bestTicks = t;
                                bestHow = String.format(java.util.Locale.ROOT, "yaw0 %.0f %s strafe %d jump t%d turn +%d%s", yaw0,
                                    sprint == 1 ? "sprint" : "walk", strafe, jumpAt, turnAt, pre > 0 ? " (turned " + pre + " ticks first)" : "");
                                bestEnd = new double[]{s.x, s.y, s.z};
                                bestPath = java.util.Arrays.copyOf(pos, 3 * t);
                            }
                        }
        }
        Recipe out = bestYaw == null
            ? new Recipe(new float[0], new int[0], 0, 0, tried, "", Double.NaN, Double.NaN, Double.NaN, new double[0])
            : new Recipe(bestYaw, bestKeys, bestTicks, ways, tried, bestHow, bestEnd[0], bestEnd[1], bestEnd[2], bestPath);
        out.margin = bestMargin;
        out.lx = lx; out.ly = ly; out.lz = lz;
        return out;
    }

    public boolean found() {
        return ticks > 0;
    }

    /** Whether the step just taken touched a block that hurts (magma, a berry bush, cactus) or one whose movement the
     *  copy leaves out (a cobweb, honey, a current: MoveState.touched, cleared before the step; scaffolding is copied
     *  since 2026-10-15): the copy's
     *  path is no longer the game's from there (the course's cobweb jump: a way through the web's edge, slowed in the
     *  game, fell). */
    static boolean spoils(MoveState s) {
        return (s.touched & (ai.rrw.kine.move.BlockInfo.HAZARD | ai.rrw.kine.move.BlockInfo.UNCOPIED)) != 0;
    }

    /** Whether a try, before its jump, stands on another floor than the one it started from: more than 0.6 lower (the
     *  course's join pad beside #67's start, one block down: full3_c1), or more than 0.25 higher on another block
     *  (fence_column_neo's fence, whose arm toward the start is a floor half a block up beside it: detect_c10 took off
     *  from it). Either is a jump from another take-off, not this one's; the hops take other floors as floors of their
     *  own. A stair's upper half is the same block as its lower one: no change of floor. */
    static boolean offFloor(MoveState start, MoveState s) {
        if (!s.onGround) return false;
        if (s.y < start.y - 0.6) return true;
        if (!(s.y > start.y + 0.25)) return false;
        int sx = start.hasSupport ? start.supX : GameMath.floor(start.x), sy = start.hasSupport ? start.supY : GameMath.floor(start.y - 1e-3),
            sz = start.hasSupport ? start.supZ : GameMath.floor(start.z);
        return !(s.hasSupport && s.supX == sx && s.supY == sy && s.supZ == sz);
    }

    /** The game's safe fall (Costs.maxFall): a landing's fall past it hurts, unless the block takes it all (slime). */
    static final double SAFE_FALL = 3.0;

    /** Whether the step just taken landed with a fall that hurts (MoveState.landedFall, set by a landing step; the
     *  caller clears it before the step). A copy-found jump took a bounce off slime that came down 4.25 onto the
     *  landing (dev5_c5, slime_bounce: health lost): the search checked only where it came down. */
    static boolean hurts(MoveState s) {
        return s.onGround && s.landedFall > SAFE_FALL + 1e-6 && s.landedFallFactor > 0f;
    }

    /** Whether s is in the landing's scaffolding column below its top: the feet in a piece of it (climbable there). */
    static boolean inScaffoldColumn(GroundPhysics phys, MoveState s, int lx, int ly, int lz) {
        int fy = GameMath.floor(s.y);
        return GameMath.floor(s.x) == lx && GameMath.floor(s.z) == lz && fy <= ly
            && phys.grid().get(lx, fy, lz).is(ai.rrw.kine.move.BlockInfo.SCAFFOLD);
    }

    /** How far ahead the climb's steering looks: where the speed now carries the player with no keys (the air's 0.91
     *  a tick: some ten ticks of it). */
    static final double CLIMB_LOOK = 10.0;
    /** The climb's dead band: within this of the column's middle (where the speed carries it) no side keys. */
    static final double CLIMB_BAND = 0.1;

    /**
     * The keys of a climb up a scaffolding column (RW's plan 2026-10-14/15, the course's scaffolding_jump): the jump
     * held (in scaffolding, jumping gives 0.2 up a tick: LivingEntity.travel), and the forward, back and side keys
     * that steer the point the speed carries the player to (CLIMB_LOOK ticks on) to the column's middle, the view held
     * at `view`: the jump into the column's side carries the player across it and out again otherwise.
     */
    static int climbKeys(MoveState s, float view, int lx, int lz) {
        double dx = lx + 0.5 - (s.x + s.vx * CLIMB_LOOK), dz = lz + 0.5 - (s.z + s.vz * CLIMB_LOOK);
        float r = view * (float) (Math.PI / 180.0);
        double sin = GameMath.sin(r), cos = GameMath.cos(r);
        // Entity.moveRelative: forward is (-sin, cos), left is (cos, sin)
        double fwd = -dx * sin + dz * cos, left = dx * cos + dz * sin;
        int k = MoveState.JUMP;
        if (fwd > CLIMB_BAND) k |= MoveState.FORWARD; else if (fwd < -CLIMB_BAND) k |= MoveState.BACKWARD;
        if (left > CLIMB_BAND) k |= MoveState.LEFT; else if (left < -CLIMB_BAND) k |= MoveState.RIGHT;
        return k;
    }

    /** Ticks a landing on a ladder or a vine is held for (part of the recipe). */
    static final int HOLD = 12;
    /** The hold's aims, degrees off the way toward the wall the climbable hangs on. */
    private static final float[] HOLD_OFFSETS = {0f, -30f, 30f, -60f, 60f, -90f, 90f};

    /** The yaw from the climbable's cell toward the full block beside it that it hangs on (the first of +x, -x, +z, -z
     *  with a full block); NaN if none. */
    static float wallYaw(GroundPhysics phys, int lx, int ly, int lz) {
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] e : d)
            if (phys.grid().get(lx + e[0], ly, lz + e[1]).shape.block) return yawTo(0, 0, e[0], e[1]);
        return Float.NaN;
    }

    /**
     * From state s, just on the climbable at (lx, ly, lz): HOLD ticks pressing forward (and with the shift key: it keeps
     * the player from sliding down), the view turning at most 29.5 degrees a tick toward the wall's way turned by each of
     * HOLD_OFFSETS (toward the cell's centre when there is no wall). Of the holds that end on the climbable in that
     * column, at its height or above and slower than 0.05 a tick sideways, the one nearest the column's middle; its
     * inputs and path written to yaw, keys, path, and s moved to its end. -1 when none holds.
     */
    static int hold(GroundPhysics phys, MoveState s, float view0, int lx, int ly, int lz, float wall, float[] yaw, int[] keys,
                    double[] path) {
        double best = -1;
        MoveState bestEnd = null;
        float[] y = new float[HOLD];
        int[] k = new int[HOLD];
        double[] p = new double[3 * HOLD];
        for (int shift = 0; shift <= 1; shift++)
            for (float off : HOLD_OFFSETS) {
                MoveState m = s.copy();
                float view = view0;
                int key = MoveState.FORWARD | (shift == 1 ? MoveState.SHIFT : 0);
                for (int t = 0; t < HOLD; t++) {
                    float want = Float.isNaN(wall) ? yawTo(m.x, m.z, lx + 0.5, lz + 0.5) : wall + off;
                    float d = GameMath.wrapDegrees(want - view);
                    view += Math.max(-29.5f, Math.min(29.5f, d));
                    y[t] = view;
                    k[t] = key;
                    phys.step(m, key, view, 0f);
                    p[3 * t] = m.x; p[3 * t + 1] = m.y; p[3 * t + 2] = m.z;
                }
                if (!holdsOn(phys, m, lx, ly, lz) || Math.hypot(m.vx, m.vz) > 0.05) continue;
                double margin = Math.min(Math.min(m.x - lx, lx + 1 - m.x), Math.min(m.z - lz, lz + 1 - m.z));
                if (margin > best) {
                    best = margin;
                    bestEnd = m;
                    System.arraycopy(y, 0, yaw, 0, HOLD);
                    System.arraycopy(k, 0, keys, 0, HOLD);
                    System.arraycopy(p, 0, path, 0, 3 * HOLD);
                }
                if (Float.isNaN(wall)) break;
            }
        if (bestEnd == null) return -1;
        s.set(bestEnd);
        return HOLD;
    }

    /** Whether a fall from state `from` (a copy of it stepped, keys up, up to 80 ticks) ends without hurting: onto slime
     *  (the bounce, no damage), into water, or from no more than maxFall (Layer2.fallEndsSafely, the walk's hop). */
    public static boolean fallEndsSafely(GroundPhysics phys, MoveState from, double maxFall) {
        MoveState s = from.copy();
        for (int t = 0; t < 80; t++) {
            double fall0 = s.fallDistance, y0 = s.y;
            phys.step(s, 0, 0f, 0f);
            if (s.inWater) return true;
            if ((s.touched & ai.rrw.kine.move.BlockInfo.SLIME) != 0) return true;   // a bounce: no fall damage
            if (s.onGround && !(s.vy > 0.0)) return fall0 + Math.max(0.0, y0 - s.y) <= maxFall;
            s.touched = 0;
        }
        return false;
    }

    /** On the climbable in the landing's column, at the landing's height or above. */
    static boolean holdsOn(GroundPhysics phys, MoveState m, int lx, int ly, int lz) {
        return phys.onClimbable(m) && GameMath.floor(m.x) == lx && GameMath.floor(m.z) == lz && m.y > ly - 1e-6;
    }

    /** Whether the inputs from tick `from` on, given from state s, still end standing on (lx, ly, lz). */
    public boolean stillLands(GroundPhysics phys, MoveState s, int from, int lx, int ly, int lz) {
        MoveState m = s.copy();
        for (int t = from; t < ticks; t++) phys.step(m, keys[t], yaw[t], 0f);
        if (!m.onGround && holdsOn(phys, m, lx, ly, lz)) return true;   // a held climbable landing
        boolean onCol = m.x + 0.3 > lx && m.x - 0.3 < lx + 1 && m.z + 0.3 > lz && m.z - 0.3 < lz + 1;
        return onCol && m.y > ly + 0.4 && m.y < ly + 1.6 && (m.onGround || phys.onClimbable(m));
    }

    /** The landing block it was found for. */
    public int lx, ly, lz;
    /** Searched from the state the player comes to rest in (Layer2.submitSearch): its first tick waits for rest. */
    public boolean fromRest;

    // ------------------------------------------------------------------ standing spots (RW 2026-10-14, #67)

    /** How near a standing spot the walk must come to rest for the search from it to count (blocks). */
    static final double SPOT_NEAR = 0.08;
    /** How far from the block's centre the spots off it are (blocks), and how near its edge they may come. */
    static final double SPOT_OFF = 0.35, SPOT_EDGE = 0.15;

    /**
     * Standing spots on the block the player stands on (column bx, bz), for the jump search when it finds nothing from
     * where the walk stands (RW 2026-10-14 22:36:05, on #67: "the repostion and try many jump searches fomr teh blcok"):
     * the block's centre; back from it, away from the landing at (tx, tz) (a run-up); the two sides; the two back
     * corners; the two front corners. Each SPOT_OFF from the centre along the landing's bearing and across it, kept
     * SPOT_EDGE inside the block. Spots within 0.15 of (px, pz), where the walk stands now, are left out. {x, z} each.
     */
    static double[][] spots(int bx, int bz, double px, double pz, double tx, double tz) {
        double cx = bx + 0.5, cz = bz + 0.5;
        double ux = tx - cx, uz = tz - cz, len = Math.hypot(ux, uz);
        if (len < 1e-6) { ux = 1; uz = 0; } else { ux /= len; uz /= len; }
        double vx = -uz, vz = ux;   // across the bearing
        double[][] rel = {{0, 0}, {-1, 0}, {0, 1}, {0, -1}, {-1, 1}, {-1, -1}, {1, 1}, {1, -1}};
        java.util.List<double[]> out = new java.util.ArrayList<>();
        for (double[] r : rel) {
            double x = cx + SPOT_OFF * (r[0] * ux + r[1] * vx), z = cz + SPOT_OFF * (r[0] * uz + r[1] * vz);
            x = Math.max(bx + SPOT_EDGE, Math.min(bx + 1 - SPOT_EDGE, x));
            z = Math.max(bz + SPOT_EDGE, Math.min(bz + 1 - SPOT_EDGE, z));
            // (the centre itself unless the walk stands on it within 0.005: detect_c19, diag_4x1, 0.02 off the
            // centre facing away the search found nothing, from the centre it did)
            if (Math.hypot(x - px, z - pz) < (r[0] == 0 && r[1] == 0 ? 0.005 : 0.15)) continue;
            boolean dup = false;
            for (double[] o : out) if (Math.hypot(o[0] - x, o[1] - z) < 0.05) { dup = true; break; }
            if (!dup) out.add(new double[]{x, z});
        }
        return out.toArray(new double[0][]);
    }

    /**
     * Inputs that bring the player from `start` to rest at (tx, tz) on the floor it stands on: the view turned toward
     * the spot first (keys up, at most 29.5 degrees a tick), then forward (walking for some ticks, then sneaking: the
     * game's sneak stops it at an edge), the view kept toward the spot, then keys up until it stands still. Of the
     * walking and sneaking lengths tried, the one that comes to rest nearest the spot, within SPOT_NEAR, on the ground
     * at the start's height; ties to the quicker. Null when none does.
     */
    /** Run-up spots: block centres this many blocks back from the block stood on, away from the landing. */
    static final int[] BACK = {1, 2, 3, 5, 8};

    /**
     * Run-up spots further back than the block stood on (RW 2026-10-14, #67's repositioning: "further back for a
     * run-up"): the centres of the blocks 1, 2, 3, 5 and 8 back along the way away from the landing at (tx, tz), each a
     * floor at the feet's height (its collision top at feetY) with room to stand above it, so long as every block on
     * the way back is one too (a runway): a long jump (the course's up_4p0p5, a 4-block gap a half block up from a
     * 10-block runway) needs the speed a run along it gives, which a jump search from the take-off's block can't.
     * Layer 2's own run (RunSim) is searched from them, the jump search too. {x, z} each, nearest first.
     */
    static double[][] backSpots(GroundPhysics phys, int bx, int by, int bz, double feetY, double tx, double tz) {
        double ux = tx - (bx + 0.5), uz = tz - (bz + 0.5), len = Math.hypot(ux, uz);
        if (len < 1e-6) return new double[0][];
        ux /= len; uz /= len;
        java.util.List<double[]> out = new java.util.ArrayList<>();
        int max = BACK[BACK.length - 1], bi = 0;
        for (int k = 1; k <= max; k++) {
            int cx = bx + (int) Math.round(-ux * k), cz = bz + (int) Math.round(-uz * k);
            ai.rrw.kine.move.BlockInfo b = phys.grid().get(cx, by, cz);
            if (b.shape.empty || Math.abs(by + b.shape.top() - feetY) > 0.01) break;
            if (!phys.grid().get(cx, by + 1, cz).shape.empty || !phys.grid().get(cx, by + 2, cz).shape.empty) break;
            if ((b.flags & (ai.rrw.kine.move.BlockInfo.HAZARD | ai.rrw.kine.move.BlockInfo.SPECIAL)) != 0) break;
            if (bi < BACK.length && k == BACK[bi]) { out.add(new double[]{cx + 0.5, cz + 0.5}); bi++; }
        }
        return out.toArray(new double[0][]);
    }

    /** The walk to a run-up spot (up to 60 ticks walking, then sneaking to it), at rest within SPOT_NEAR. */
    static Recipe approachFar(GroundPhysics phys, MoveState start, float startYaw, double tx, double tz) {
        Recipe a = approach(phys, start, startYaw, tx, tz, 60, 16, SPOT_NEAR);
        if (a == null) return null;
        double err = Math.hypot(a.endX - tx, a.endZ - tz);
        if (err <= SPOT_FINE) return a;
        MoveState m = after(phys, start, a);
        Recipe b = approach(phys, m, a.yaw[a.ticks - 1], tx, tz, 0, 6, err - 0.003);
        return b == null ? a : concatMoves(a, b);
    }

    static Recipe approach(GroundPhysics phys, MoveState start, float startYaw, double tx, double tz) {
        Recipe a = approach(phys, start, startYaw, tx, tz, 6, 16, SPOT_NEAR);
        if (a == null) return null;
        // a second, finer step from where the first comes to rest (a few ticks of sneaking): a template's run round a
        // wall's corner can miss from a few hundredths off its spot
        double err = Math.hypot(a.endX - tx, a.endZ - tz);
        if (err <= SPOT_FINE) return a;
        MoveState m = after(phys, start, a);
        Recipe b = approach(phys, m, a.yaw[a.ticks - 1], tx, tz, 0, 6, err - 0.003);
        return b == null ? a : concatMoves(a, b);
    }

    /** How near a spot the walk to it tries to come to rest with its second, finer step (blocks). */
    static final double SPOT_FINE = 0.012;

    /** Two walks one after the other (the second searched from where the first ends). */
    private static Recipe concatMoves(Recipe a, Recipe b) {
        Recipe r = concat(a, b);
        r.how = a.how + ", " + b.how;
        r.approachTicks = 0;
        return r;
    }

    /** The walk to (tx, tz) with up to maxWalk ticks walking then maxSneak sneaking, at rest within `near`. */
    private static Recipe approach(GroundPhysics phys, MoveState start, float startYaw, double tx, double tz, int maxWalk,
                                   int maxSneak, double near) {
        double bestD = near;
        int bestT = Integer.MAX_VALUE;
        float[] bestYaw = null;
        int[] bestKeys = null;
        double[] bestPath = null, bestEnd = null;
        String bestHow = "";
        int cap = 60 + 2 * maxWalk;
        float[] yaws = new float[cap];
        int[] ks = new int[cap];
        double[] pos = new double[3 * cap];
        for (int walkT = 0; walkT <= maxWalk; walkT++)
            for (int sneakT = 0; sneakT <= maxSneak; sneakT++) {
                if (walkT + sneakT == 0) continue;
                MoveState s = start.copy();
                float view = Float.isNaN(startYaw) ? yawTo(s.x, s.z, tx, tz) : startYaw;
                int pressed = 0, t = 0;
                boolean still = false;
                for (; t < cap; t++) {
                    float want = Math.hypot(tx - s.x, tz - s.z) < 0.02 ? view : yawTo(s.x, s.z, tx, tz);
                    float d = GameMath.wrapDegrees(want - view);
                    boolean turned = Math.abs(d) < 30f;
                    view += Math.max(-29.5f, Math.min(29.5f, d));
                    int k = 0;
                    if (pressed < walkT + sneakT && (turned || pressed > 0)) {
                        k = MoveState.FORWARD | (pressed >= walkT ? MoveState.SHIFT : 0);
                        pressed++;
                    }
                    yaws[t] = view;
                    ks[t] = k;
                    phys.step(s, k, view, 0f);
                    pos[3 * t] = s.x; pos[3 * t + 1] = s.y; pos[3 * t + 2] = s.z;
                    if (pressed >= walkT + sneakT && k == 0 && s.onGround && s.vx * s.vx + s.vz * s.vz <= RunModel.STILL2
                        && !s.crouching) {
                        t++;
                        still = true;
                        break;
                    }
                }
                if (!still || Math.abs(s.y - start.y) > 1e-3) continue;
                double dd = Math.hypot(s.x - tx, s.z - tz);
                if (dd < bestD - 0.01 || dd <= bestD + 0.01 && t < bestT) {
                    bestD = Math.min(bestD, dd);
                    bestT = t;
                    bestYaw = java.util.Arrays.copyOf(yaws, t);
                    bestKeys = java.util.Arrays.copyOf(ks, t);
                    bestPath = java.util.Arrays.copyOf(pos, 3 * t);
                    bestEnd = new double[]{s.x, s.y, s.z};
                    bestHow = String.format(java.util.Locale.ROOT, "to %.2f %.2f walk %d sneak %d", tx, tz, walkT, sneakT);
                }
            }
        if (bestYaw == null) return null;
        return new Recipe(bestYaw, bestKeys, bestKeys.length, 1, 1, bestHow, bestEnd[0], bestEnd[1], bestEnd[2], bestPath);
    }

    /** a's inputs, then b's (b searched from where a ends); b's landing. */
    static Recipe concat(Recipe a, Recipe b) {
        int n = a.ticks + b.ticks;
        float[] y = java.util.Arrays.copyOf(a.yaw, n);
        System.arraycopy(b.yaw, 0, y, a.ticks, b.ticks);
        int[] k = java.util.Arrays.copyOf(a.keys, n);
        System.arraycopy(b.keys, 0, k, a.ticks, b.ticks);
        double[] p = java.util.Arrays.copyOf(a.path, 3 * n);
        System.arraycopy(b.path, 0, p, 3 * a.ticks, 3 * b.ticks);
        Recipe r = new Recipe(y, k, n, b.ways, a.tried + b.tried, "spot " + a.how + ", then " + b.how, b.endX, b.endY, b.endZ, p);
        r.lx = b.lx; r.ly = b.ly; r.lz = b.lz;
        r.approachTicks = a.ticks;
        return r;
    }

    /** A jump, then the next one chained on from where it lands (b searched from a's end, moving): b's landing. */
    static Recipe then(Recipe a, Recipe b) {
        Recipe r = concat(a, b);
        r.how = a.how + "; chained: " + b.how;
        r.approachTicks = a.approachTicks;
        return r;
    }

    /** Ticks of a repositioning before the jump's own inputs (0 for a jump searched from where the walk stood). */
    public int approachTicks = 0;

    /** A recipe of these inputs from `start` onto landing block l (its path and end stepped in the copy). */
    static Recipe of(GroundPhysics phys, MoveState start, float[] yaw, int[] keys, int[] l) {
        MoveState s = start.copy();
        double[] path = new double[3 * keys.length];
        for (int t = 0; t < keys.length; t++) {
            phys.step(s, keys[t], yaw[t], 0f);
            path[3 * t] = s.x; path[3 * t + 1] = s.y; path[3 * t + 2] = s.z;
        }
        Recipe r = new Recipe(yaw, keys, keys.length, 1, 1, "", s.x, s.y, s.z, path);
        r.lx = l[0]; r.ly = l[1]; r.lz = l[2];
        return r;
    }

    /** The state the copy says `inputs` end in from `start`. */
    static MoveState after(GroundPhysics phys, MoveState start, Recipe inputs) {
        MoveState s = start.copy();
        for (int t = 0; t < inputs.ticks; t++) phys.step(s, inputs.keys[t], inputs.yaw[t], 0f);
        return s;
    }
}
