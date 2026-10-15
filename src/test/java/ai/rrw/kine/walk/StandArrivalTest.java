package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hop's floor counts as reached only standing still on it (RunModel.stand): the walk comes to rest on it within a
 * few ticks of coming within half a block of its centre, without the keys flipping round the centre (RW 2026-10-14
 * 17:25:43, the vibrating on landings: 30 ticks of it at nearly every hop of the course's walks).
 */
public class StandArrivalTest {
    static final int FLOOR = 20;

    /** Ticks from the first within half a block of the goal's centre to the walk's arrival (standing). */
    static int settle(Layer2Sim.Outcome o, int gx, int gz) {
        List<double[]> path = o.path();
        for (int i = 0; i < path.size(); i++) {
            double[] q = path.get(i);
            if (Math.hypot(gx + 0.5 - q[0], gz + 0.5 - q[2]) <= 0.5) return path.size() - i;
        }
        return -1;
    }

    @Test
    void aSprintOntoAPadComesToRest() {
        for (int len : new int[]{3, 5, 8}) {
            TestWorld w = TestWorld.blocks(-16, -16, 31, 15);
            w.fill(-2, FLOOR - 1, -1, len + 2, FLOOR - 1, 1, TestWorld.STONE);
            Layer2Sim sim = new Layer2Sim(w);
            sim.stand = true;
            Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, FLOOR, 0.5), -90f, len, 0, 200);
            assertTrue(o.arrived(), "arrived at " + len + ": " + o.why());
            double[] end = o.path().get(o.path().size() - 1);
            int st = settle(o, len, 0);
            System.out.printf(java.util.Locale.ROOT, "stand arrival %d blocks: %d ticks, settle %d ticks, end %.3f %.3f%n",
                len, o.ticks(), st, end[0], end[2]);
            assertTrue(st >= 0 && st <= 10, "came to rest within 10 ticks of reaching the centre: " + st);
            assertTrue(Math.hypot(len + 0.5 - end[0], 0.5 - end[2]) <= 0.5);
        }
    }

    /** Small landings walked without a route (the course's hops), standing to arrive: layer 2 still lands on them. The
     *  first form of the stand rule (moving ticks on the floor at full cost) made full3_c1's walk 1 sneak in the air
     *  toward end_rod_jump's rod and fall short. */
    @Test
    void smallLandingsStillLandStandingToArrive() throws Exception {
        String cat = "/home/raymond/_/kine_course_2026-10-14/parkour_research/catalogue.json";
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(cat))) return;
        java.util.Map<String, com.google.gson.JsonObject> byId = new java.util.HashMap<>();
        for (com.google.gson.JsonElement e : com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(cat))).getAsJsonArray())
            byId.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        int arrived = 0, n = 0;
        for (String id : new String[]{"end_rod_jump", "tiny_blocks", "pane_p2p", "chain_jump", "iron_bars_jump"}) {
            com.google.gson.JsonObject L = byId.get(id).getAsJsonObject("layout");
            Layer2Sim sim = new Layer2Sim(Layer2StationTest.build(L));
            sim.direct = true;
            sim.forceDirect = true;
            sim.stand = true;
            sim.recipes = false;   // layer 2's own moves, as on the course where the field joins the start to the landing
            sim.effort.budgetNanos = 20_000_000L;   // the game's budget a replan
            sim.goalY = Layer2StationTest.goalFeet(L);
            com.google.gson.JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
            int sdx = st.get("dx").getAsInt(), sdy = st.get("dy").getAsInt(), sdz = st.get("dz").getAsInt();
            double sy = FLOOR - 1 + sdy + StationDifficultyTest.collisionTop(L, sdx, sdy, sdz);
            float yaw = Recipe.yawTo(sdx + 0.5, sdz + 0.5, la.get("dx").getAsInt() + 0.5, la.get("dz").getAsInt() + 0.5);
            Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(sdx + 0.5, sy, sdz + 0.5), yaw, la.get("dx").getAsInt(), la.get("dz").getAsInt(), 200);
            System.out.printf(java.util.Locale.ROOT, "stand small landing %s: %s in %d ticks (%s)%n", id, o.arrived() ? "arrived" : "not", o.ticks(), o.why());
            n++;
            if (o.arrived()) arrived++;
        }
        assertTrue(arrived == n, arrived + " of " + n);
    }
}
