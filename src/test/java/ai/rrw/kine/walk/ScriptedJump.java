package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GameMath;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;

import java.util.Locale;

/**
 * Whether a jump can be made at all in the movement copy, by scripted inputs a precise vanilla client could give: from
 * a start standing on the take-off, walk or sprint toward a first heading (forward alone or with a strafe key), jump on
 * a chosen tick, and from a chosen tick turn toward a second heading (the landing's centre or a set yaw), at most 29.5
 * degrees a tick, keys held. Searches the grid of those choices; a way counts when the player stands on the landing
 * block. For checking parkour layouts (neo and the rest) and how much slack each has.
 */
final class ScriptedJump {
    /** The landing block and the height its top must be at. */
    final int lx, ly, lz;
    final BlockGrid grid;
    int ways, tried;
    String best = "";

    ScriptedJump(BlockGrid grid, int lx, int ly, int lz) {
        this.grid = grid; this.lx = lx; this.ly = ly; this.lz = lz;
    }

    static float yawTo(double fx, double fz, double tx, double tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - fx), tz - fz));
    }

    /** Search from standing at (sx, sy, sz). yaw0 from yawA to yawB in steps; the jump on ticks 0..maxJump; the turn
     *  toward the landing from ticks 0..maxTurn after the jump; strafe none, left, right; walking or sprinting. */
    ScriptedJump search(double sx, double sy, double sz, float yawA, float yawB, float yawStep, int maxJump, int maxTurn) {
        GroundPhysics phys = new GroundPhysics(grid, new Body());
        for (float yaw0 = yawA; yaw0 <= yawB + 1e-3; yaw0 += yawStep)
            for (int sprint = 0; sprint <= 1; sprint++)
                for (int strafe = -1; strafe <= 1; strafe++)
                    for (int jumpAt = 0; jumpAt <= maxJump; jumpAt++)
                        for (int turnAt = 0; turnAt <= maxTurn; turnAt++) {
                            tried++;
                            MoveState s = Layer2Sim.standing(sx, sy, sz);
                            int keys = MoveState.FORWARD | (sprint == 1 ? MoveState.SPRINT : 0)
                                | (strafe < 0 ? MoveState.LEFT : strafe > 0 ? MoveState.RIGHT : 0);
                            float view = yaw0 - strafe * 45f;
                            boolean jumped = false, won = false;
                            int afterJump = -1;
                            for (int t = 0; t < 60; t++) {
                                int k = keys;
                                if (t == jumpAt && s.onGround) { k |= MoveState.JUMP; jumped = true; afterJump = 0; }
                                if (afterJump >= turnAt) {
                                    float want = yawTo(s.x, s.z, lx + 0.5, lz + 0.5) - strafe * 45f;
                                    float d = GameMath.wrapDegrees(want - view);
                                    view += Math.max(-29.5f, Math.min(29.5f, d));
                                }
                                phys.step(s, k, view, 0f);
                                if (afterJump >= 0) afterJump++;
                                if (s.onGround && jumped && afterJump > 2) {
                                    won = Math.abs(s.y - (ly + 1)) < 0.01 && s.x + 0.3 > lx && s.x - 0.3 < lx + 1
                                        && s.z + 0.3 > lz && s.z - 0.3 < lz + 1;
                                    break;
                                }
                                if (s.y < sy - 4) break;
                            }
                            if (won) {
                                ways++;
                                if (best.isEmpty())
                                    best = String.format(Locale.ROOT, "yaw0 %.0f %s strafe %d jump t%d turn +%d", yaw0,
                                        sprint == 1 ? "sprint" : "walk", strafe, jumpAt, turnAt);
                            }
                        }
        return this;
    }
}
