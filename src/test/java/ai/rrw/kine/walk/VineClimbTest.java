package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import java.util.Locale;

/** The course's vine_jump after its landing (RW 2026-10-14, round 2): hanging on the vine, still, the walk's way up to
 *  the top of the wall the vine is on. From half a block up it hung below the top rung (the field showed no progress
 *  over the last rung, and climbing out over the top into the air scored worse); now it climbs out. */
public class VineClimbTest {
    static final int FLOOR = Layer2StationTest.FLOOR;

    @Test
    void upTheVineToTheWallTop() {
        TestWorld w = TestWorld.blocks(-48, -16, 31, 31);
        w.fill(-38, FLOOR - 16, -8, 22, FLOOR - 16, 12, TestWorld.STONE);
        w.fill(-38, FLOOR - 15, -8, 22, FLOOR - 14, 12, TestWorld.WATER);
        Kind vine = Layer2StationTest.kindOf("minecraft:vine[east=true]");
        w.set(0, FLOOR - 1, 0, TestWorld.STONE);
        for (int dy = 0; dy <= 4; dy++) w.set(5, FLOOR - 1 + dy, 0, TestWorld.STONE);
        for (int dy = 1; dy <= 4; dy++) w.set(4, FLOOR - 1 + dy, 0, vine);
        Layer2Sim sim = new Layer2Sim(w);
        if (System.getenv("KINE_VINE_DEBUG") != null) for (String t : System.getenv("KINE_VINE_DEBUG").split(",")) sim.debugTicks.add(Integer.parseInt(t));
        sim.goalY = FLOOR + 4;
        for (double y0 : System.getenv("KINE_VINE_DEBUG") != null ? new double[]{FLOOR + 0.5} : new double[]{FLOOR + 0.02, FLOOR + 0.5}) {
            MoveState s = Layer2Sim.standing(4.51, y0, 0.5);
            s.onGround = false;
            s.hasSupport = false;
            Layer2Sim.Outcome o = sim.walk(s, -90f, 5, 0, 300);
            double[] e = o.path().isEmpty() ? new double[]{s.x, s.y, s.z} : o.path().get(o.path().size() - 1);
            if (System.getenv("KINE_VINE_TRACE") != null)
                for (int i = 0; i < o.path().size(); i += 5) { double[] q = o.path().get(i);
                    System.out.printf(Locale.ROOT, "   t%d %.2f %.2f %.2f flags %d keys %d%n", i, q[0], q[1] - FLOOR, q[2], (int) q[3], (int) q[4]); }
            org.junit.jupiter.api.Assertions.assertTrue(o.arrived(), "up the vine from " + (y0 - FLOOR) + " over the vine's foot: " + o.why());
            System.out.printf(Locale.ROOT, "vine from y %.2f: arrived %b in %d ticks (%s), end %.2f %.2f %.2f; route moves %s%n", y0 - FLOOR,
                o.arrived(), o.ticks(), o.why(), e[0], e[1] - FLOOR, e[2], sim.lastRoute == null ? "-" : java.util.Arrays.toString(sim.lastRoute.moves));
        }
    }
}
