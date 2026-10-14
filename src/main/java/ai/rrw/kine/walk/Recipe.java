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
    public final String how;
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
        int ways = 0, tried = 0;
        double bestMargin = -1;
        float[] bestYaw = null;
        int[] bestKeys = null;
        int bestTicks = 0;
        String bestHow = "";
        double[] bestEnd = null, bestPath = null;
        float[] yaws = new float[limit];
        int[] ks = new int[limit];
        double[] pos = new double[3 * limit];
        for (float yaw0 = yawA; yaw0 <= yawB + 1e-3; yaw0 += step)
            for (int sprint = 1; sprint >= 0; sprint--)
                for (int strafe = -1; strafe <= 1; strafe++)
                    for (int jumpAt = 0; jumpAt <= maxJump; jumpAt++)
                        for (int turnAt = 0; turnAt <= maxTurn; turnAt++) {
                            tried++;
                            MoveState s = start.copy();
                            int base = MoveState.FORWARD | (sprint == 1 ? MoveState.SPRINT : 0)
                                | (strafe < 0 ? MoveState.LEFT : strafe > 0 ? MoveState.RIGHT : 0);
                            float aim0 = yaw0 - strafe * 45f;
                            float view = Float.isNaN(startYaw) ? aim0 : startYaw;
                            boolean jumped = false, won = false;
                            int after = -1, t = 0;
                            for (; t < limit; t++) {
                                int k = base;
                                if (t == jumpAt && s.onGround) { k |= MoveState.JUMP; jumped = true; after = 0; }
                                float want = after >= turnAt ? yawTo(s.x, s.z, lx + 0.5, lz + 0.5) - strafe * 45f : aim0;
                                float d = GameMath.wrapDegrees(want - view);
                                view += Math.max(-29.5f, Math.min(29.5f, d));
                                yaws[t] = view;
                                ks[t] = k;
                                phys.step(s, k, view, 0f);
                                pos[3 * t] = s.x; pos[3 * t + 1] = s.y; pos[3 * t + 2] = s.z;
                                if (after >= 0) after++;
                                boolean onCol = s.x + 0.3 > lx && s.x - 0.3 < lx + 1 && s.z + 0.3 > lz && s.z - 0.3 < lz + 1;
                                if (jumped && after > 2 && (s.onGround || phys.onClimbable(s))) {
                                    won = onCol && s.y > ly + 0.4 && s.y < ly + 1.6;
                                    if (s.onGround || won) { t++; break; }
                                }
                                if (s.y < start.y - 6) break;
                            }
                            if (!won) continue;
                            ways++;
                            double margin = Math.min(Math.min(s.x - lx, lx + 1 - s.x), Math.min(s.z - lz, lz + 1 - s.z));
                            if (margin > bestMargin) {
                                bestMargin = margin;
                                bestYaw = java.util.Arrays.copyOf(yaws, t);
                                bestKeys = java.util.Arrays.copyOf(ks, t);
                                bestTicks = t;
                                bestHow = String.format(java.util.Locale.ROOT, "yaw0 %.0f %s strafe %d jump t%d turn +%d", yaw0,
                                    sprint == 1 ? "sprint" : "walk", strafe, jumpAt, turnAt);
                                bestEnd = new double[]{s.x, s.y, s.z};
                                bestPath = java.util.Arrays.copyOf(pos, 3 * t);
                            }
                        }
        Recipe out = bestYaw == null
            ? new Recipe(new float[0], new int[0], 0, 0, tried, "", Double.NaN, Double.NaN, Double.NaN, new double[0])
            : new Recipe(bestYaw, bestKeys, bestTicks, ways, tried, bestHow, bestEnd[0], bestEnd[1], bestEnd[2], bestPath);
        out.lx = lx; out.ly = ly; out.lz = lz;
        return out;
    }

    public boolean found() {
        return ticks > 0;
    }

    /** Whether the inputs from tick `from` on, given from state s, still end standing on (lx, ly, lz). */
    public boolean stillLands(GroundPhysics phys, MoveState s, int from, int lx, int ly, int lz) {
        MoveState m = s.copy();
        for (int t = from; t < ticks; t++) phys.step(m, keys[t], yaw[t], 0f);
        boolean onCol = m.x + 0.3 > lx && m.x - 0.3 < lx + 1 && m.z + 0.3 > lz && m.z - 0.3 < lz + 1;
        return onCol && m.y > ly + 0.4 && m.y < ly + 1.6 && (m.onGround || phys.onClimbable(m));
    }

    /** The landing block it was found for. */
    public int lx, ly, lz;
}
