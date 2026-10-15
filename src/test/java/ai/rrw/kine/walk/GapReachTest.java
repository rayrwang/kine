package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a jump across a gap really clears, measured in the movement copy (RW, 2026-10-13: "as far as psoible liek to 3
 * or 4 blocks out too"): from a take-off block T to a landing block L at (dx, dz) and dh up or down, with nothing
 * between (a pit with no bottom), over every way in reach of a precise vanilla client: run-up on a straight runway of
 * R blocks behind T (0: standing at T's back), sprinting or sprint-jumping on it, the last jump on any of the last four
 * ground ticks, the run-up's heading off the line by up to 10 degrees, forward alone or with a strafe key (45 strafe);
 * in the air the heading turns toward L's centre at most 29.5 degrees a tick. A jump counts when the player stands on
 * L. With KINE_MEASURE set, every offset (dx, dz) out to 7 and dh from -3 to +1 is measured and written to
 * gapreach.csv; otherwise a few checks.
 */
public class GapReachTest {
    static final int FLOOR = 10;   // the take-off's top; its block at FLOOR - 1
    static final double PIT = -20;

    record Result(int dx, int dz, int dh, int runUp, int wins, int tried, String best, double margin) {}

    /** The blocks: T, the runway behind it along T to L, and L. */
    static BlockGrid world(int dx, int dz, int dh, int runUp) {
        BlockGrid g = new BlockGrid(-16, -32, -16, 40, 64, 40);
        double len = Math.hypot(dx, dz), ux = dx / len, uz = dz / len;
        for (int x = -15; x <= 15; x++)
            for (int z = -15; z <= 15; z++) {
                double p = x * ux + z * uz, q = -x * uz + z * ux;
                boolean runway = x == 0 && z == 0 || p <= 0 && p >= -runUp - 0.5 && Math.abs(q) <= 0.75;
                if (runway) g.set(x, FLOOR - 1, z, Layer2Sim.STONE);
            }
        g.set(dx, FLOOR - 1 + dh, dz, Layer2Sim.STONE);
        return g;
    }

    static float yawOf(double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-x, z));
    }

    /** All the ways tried for one geometry. */
    static Result measure(int dx, int dz, int dh, int runUp) {
        return measure(dx, dz, dh, runUp, true);
    }

    /** As above; sprint false: no sprint key anywhere (the gait setting's Walk: walking, and jumps while walking). */
    static Result measure(int dx, int dz, int dh, int runUp, boolean sprint) {
        BlockGrid g = world(dx, dz, dh, runUp);
        GroundPhysics phys = new GroundPhysics(g, new Body());
        double len = Math.hypot(dx, dz), ux = dx / len, uz = dz / len;
        float lineYaw = yawOf(ux, uz);
        double lx = dx + 0.5, lz = dz + 0.5;
        int wins = 0, tried = 0;
        String best = "";
        double bestMargin = -1;
        for (int chain = 0; chain <= 1; chain++)
            for (int off = -10; off <= 10; off += 2)
                for (int strafe = -1; strafe <= 1; strafe++) {
                    // the run-up: from the runway's back (or T's back) along the line, heading off by `off`
                    double back = runUp + 0.2;
                    MoveState s = Layer2Sim.standing(0.5 - ux * back, FLOOR, 0.5 - uz * back);
                    float moveYaw = lineYaw + off;
                    // a strafe key turns the view 45 degrees the other way so the movement keeps its heading
                    float viewYaw = moveYaw - strafe * 45f;
                    int keys = MoveState.FORWARD | (sprint ? MoveState.SPRINT : 0) | (strafe < 0 ? MoveState.LEFT : strafe > 0 ? MoveState.RIGHT : 0);
                    List<MoveState> ground = new ArrayList<>();
                    for (int t = 0; t < 120 && s.y > FLOOR - 0.5; t++) {
                        if (s.onGround) ground.add(s.copy());
                        int k = keys | (chain == 1 && s.onGround && t > 0 ? MoveState.JUMP : 0);
                        phys.step(s, k, viewYaw, 0f);
                        if (s.y < FLOOR - 0.5) break;
                    }
                    // the last jump on each of the last four ground ticks
                    for (int i = Math.max(0, ground.size() - 4); i < ground.size(); i++) {
                        MoveState j = ground.get(i).copy();
                        tried++;
                        float yaw = viewYaw;
                        boolean won = false;
                        double margin = 0;
                        for (int t = 0; t < 80; t++) {
                            phys.step(j, keys | (t == 0 ? MoveState.JUMP : 0), yaw, 0f);
                            // in the air: the heading turns toward L's centre (the strafe's view offset kept)
                            float want = yawOf(lx - j.x, lz - j.z) - strafe * 45f;
                            float d = ai.rrw.kine.move.GameMath.wrapDegrees(want - yaw);
                            yaw += Math.max(-29.5f, Math.min(29.5f, d));
                            if (j.onGround) {
                                boolean onL = Math.abs(j.y - (FLOOR + dh)) < 0.01 && j.x + 0.3 > dx && j.x - 0.3 < dx + 1
                                    && j.z + 0.3 > dz && j.z - 0.3 < dz + 1;
                                if (onL) {
                                    won = true;
                                    // how far inside L the centre landed (the jump's slack)
                                    margin = Math.min(Math.min(j.x - dx, dx + 1 - j.x), Math.min(j.z - dz, dz + 1 - j.z));
                                }
                                if (t > 2) break;
                            }
                            if (j.y < PIT) break;
                        }
                        if (won) {
                            wins++;
                            if (margin > bestMargin) {
                                bestMargin = margin;
                                best = String.format(Locale.ROOT, "%s off=%d strafe=%d jumpAt=-%d margin=%.2f",
                                    (chain == 1 ? "jumps" : "runs") + (sprint ? " sprinting" : " walking"), off, strafe, ground.size() - i, margin);
                            }
                        }
                    }
                }
        return new Result(dx, dz, dh, runUp, wins, tried, best, bestMargin);
    }

    @Test
    void whatAJumpClears() throws Exception {
        boolean full = System.getenv("KINE_MEASURE") != null;
        if (!full) {
            // the well-known flat limits: four blocks across with a run-up, not five
            Result four = measure(5, 0, 0, 4), five = measure(6, 0, 0, 4);
            System.out.println("gap reach: straight 4 across " + four + "; 5 across " + five);
            assertTrue(four.wins() > 0, "a 4 block gap is cleared with a run-up");
            assertEquals(0, five.wins(), "a 5 block gap is not");
            return;
        }
        StringBuilder csv = new StringBuilder("dx,dz,dh,runUp,wins,tried,best\n");
        for (int dh = -3; dh <= 1; dh++)
            for (int dx = 2; dx <= 7; dx++)
                for (int dz = 0; dz <= dx; dz++) {
                    for (int runUp : new int[]{0, 1, 2, 4, 8}) {
                        Result r = measure(dx, dz, dh, runUp);
                        csv.append(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%s%n", dx, dz, dh, runUp, r.wins(), r.tried(), r.best()));
                        if (r.wins() > 0 && runUp == 8) System.out.println("gap reach " + r);
                    }
                }
        java.nio.file.Path out = java.nio.file.Path.of(System.getenv("KINE_MEASURE"), "gapreach.csv");
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out, csv);
        System.out.println("gap reach written to " + out);
    }

    /** Which of the gap jumps layer 1 offers (Jumps.TABLE) a walk without sprinting makes (the gait setting's Walk):
     *  the same ways as the table's, a run-up of 2 (or the table's), no sprint key, and a stricter threshold
     *  (walkable). Printed; the set is Jumps.WALKABLE. */
    static final int RUNUP = Integer.parseInt(System.getenv().getOrDefault("KINE_WALK_RUNUP", "0"));

    @Test
    void whatAWalkingJumpClears() {
        StringBuilder made = new StringBuilder();
        for (int[] j : Jumps.TABLE) {
            Result r = measure(j[0], j[1], j[2], RUNUP, false);
            if (walkable(r)) made.append(String.format(Locale.ROOT, " {%d, %d, %d}", j[0], j[1], j[2]));
            System.out.printf(Locale.ROOT, "walking: %d %d %d run-up %d: %d of %d %s%n", j[0], j[1], j[2], RUNUP, r.wins(), r.tried(), r.best());
        }
        System.out.println("walkable:" + made);
        for (int[] w : Jumps.WALKABLE) {
            Result r = measure(w[0], w[1], w[2], RUNUP, false);
            assertTrue(walkable(r), "a walking jump makes " + w[0] + " " + w[1] + " " + w[2] + ": " + r);
        }
    }

    /** Made walking with room to spare: at least 20 of the ways land (about one in eleven; the sprint table's 5 let in
     *  jumps whose best landing was on the block's edge), the best with its centre 0.1 inside the landing. */
    static boolean walkable(Result r) {
        return r.wins() >= 20 && r.margin() >= 0.1;
    }
}
