package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2's own run simulated from the standing spots (RunSim): the course's #67 (neo_2_5bc), where the walk came to
 * rest against the neo's wall (446.70 on a block from 446 to 447: 0.70 into it) and neither the jump search nor layer
 * 2's live replans found a way; from the spots on its start block, layer 2's run in the copy, walking without a route
 * to the landing as the course's hop does.
 */
public class RunSimTest {
    static final int FLOOR = Layer2StationTest.FLOOR;
    static final String CAT = "/home/raymond/_/kine_course_2026-10-14/parkour_research/catalogue.json";

    static JsonObject layout(String id) throws Exception {
        if (!Files.exists(Path.of(CAT))) return null;
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(CAT))).getAsJsonArray())
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject().getAsJsonObject("layout");
        return null;
    }

    /** The spots' outcomes for one layout from where the walk stood: the jump search, then layer 2's run. */
    static int spots(String id, double fx, double fz, boolean print) throws Exception {
        return spots(id, fx, fz, print, false);
    }

    /** As above; fromTakeoff: the walk stands on the layout's take-off block (its 0, 0, 0), not its start (the end of
     *  a runway, where the course's hop leaves the walk before a long jump), and the run-up spots behind it count. */
    static int spots(String id, double fx, double fz, boolean print, boolean fromTakeoff) throws Exception {
        return spots(id, fx, fz, Float.NaN, print, fromTakeoff);
    }

    /** As above, the view at sentYaw (NaN: toward the landing). */
    static int spots(String id, double fx, double fz, float sentYaw, boolean print, boolean fromTakeoff) throws Exception {
        JsonObject L = layout(id);
        if (L == null) return -1;
        if (!Layer2StationTest.buildable(L)) { if (print) System.out.printf(Locale.ROOT, "runsim %s: not buildable in the copy%n", id); return -1; }
        Layer2Sim sim = new Layer2Sim(Layer2StationTest.build(L));
        JsonObject la = L.getAsJsonObject("landing"), st = L.getAsJsonObject("start");
        int lx = la.get("dx").getAsInt(), ly = FLOOR - 1 + la.get("dy").getAsInt(), lz = la.get("dz").getAsInt();
        int sdx = fromTakeoff ? 0 : st.get("dx").getAsInt(), sdy = fromTakeoff ? 0 : st.get("dy").getAsInt(),
            sdz = fromTakeoff ? 0 : st.get("dz").getAsInt();
        double sy = FLOOR - 1 + sdy + StationDifficultyTest.collisionTop(L, sdx, sdy, sdz);
        double goalY = Layer2StationTest.goalFeet(L);
        long start = sim.view.nodeAt(sdx, sy, sdz);
        Planner.Plan dp = Planner.direct(sim.view, start, lx, lz, goalY, sim.costs, 0.2, true);
        assertNotNull(dp, "a direct plan");
        Field f = Field.direct(sim.view, sim.costs, true, true, false, 1, dp.result.path, lx, lz, goalY);
        RunSim.Goal goal = new RunSim.Goal(lx, lz, goalY, true, 0f, sim.costs.maxFall, true, true);
        MoveState here = Layer2Sim.standing(sdx + fx, sy, sdz + fz);
        float sent = Float.isNaN(sentYaw) ? Recipe.yawTo(here.x, here.z, lx + 0.5, lz + 0.5) : sentYaw;
        int[][] land = {{lx, ly, lz}};
        int landed = 0;
        long t0 = System.nanoTime();
        Recipe fromHere = RunSim.run(f, sim.grid, sim.body, goal, here, sent, 0f, land);
        if (print) System.out.printf(Locale.ROOT, "runsim %s from here %.2f %.2f: %s%n", id, fx, fz, fromHere == null ? "no" : fromHere.how);
        double[][] near = Recipe.spots(sdx, sdz, here.x, here.z, lx + 0.5, lz + 0.5);
        double[][] back = Recipe.backSpots(sim.phys, sdx, FLOOR - 1 + sdy, sdz, sy, lx + 0.5, lz + 0.5);
        for (int si = 0; si < near.length + back.length; si++) {
            double[] sp = si < near.length ? near[si] : back[si - near.length];
            Recipe a = si < near.length ? Recipe.approach(sim.phys, here, sent, sp[0], sp[1]) : Recipe.approachFar(sim.phys, here, sent, sp[0], sp[1]);
            if (a == null) { if (print) System.out.printf(Locale.ROOT, "runsim %s spot %.2f %.2f: no walk there%n", id, sp[0] - sdx, sp[1] - sdz); continue; }
            MoveState m = Recipe.after(sim.phys, here, a);
            float view = a.yaw[a.ticks - 1];
            float b = Recipe.yawTo(m.x, m.z, lx + 0.5, lz + 0.5);
            Recipe j = Recipe.search(sim.phys, m, view, lx, ly, lz, b - 90f, b + 90f, 6f, 8, 8, 60);
            String how = j.found() ? "jump search" : "";
            Recipe r = j.found() ? j : RunSim.run(f, sim.grid, sim.body, goal, m, view, 0f, land);
            if (r != null && !j.found()) how = r.how;
            if (r != null) {
                landed++;
                Recipe all = Recipe.concat(a, r);
                assertTrue(all.stillLands(sim.phys, here, 0, lx, ly, lz), "the walk to the spot and the run, from where it stood, land");
            }
            if (print) System.out.printf(Locale.ROOT, "runsim %s spot %.2f %.2f: %s%n", id, sp[0] - sdx, sp[1] - sdz, r == null ? "no" : how);
        }
        if (print) System.out.printf(Locale.ROOT, "runsim %s: %d spots land, %.0f ms in all%n", id, landed, (System.nanoTime() - t0) / 1e6);
        return landed;
    }

    /** A plain 3-block gap: layer 2's run from the start's centre lands, and its inputs, played from there, land. */
    @Test
    void layer2sRunCrossesAPlainGap() throws Exception {
        TestWorld w = TestWorld.blocks(-16, -16, 15, 15);
        w.fill(-12, FLOOR - 16, -12, 12, FLOOR - 16, 12, TestWorld.STONE);
        w.set(0, FLOOR - 1, 0, TestWorld.STONE);
        w.set(4, FLOOR - 1, 0, TestWorld.STONE);
        Layer2Sim sim = new Layer2Sim(w);
        long start = sim.view.nodeAt(0, FLOOR, 0);
        Planner.Plan dp = Planner.direct(sim.view, start, 4, 0, FLOOR, sim.costs, 0.2, true);
        assertNotNull(dp);
        Field f = Field.direct(sim.view, sim.costs, true, true, false, 1, dp.result.path, 4, 0, FLOOR);
        RunSim.Goal goal = new RunSim.Goal(4, 0, FLOOR, true, 0f, sim.costs.maxFall, true, true);
        MoveState s = Layer2Sim.standing(0.5, FLOOR, 0.5);
        Recipe r = RunSim.run(f, sim.grid, sim.body, goal, s, -90f, 0f, new int[][]{{4, FLOOR - 1, 0}});
        assertNotNull(r, "layer 2's run over the gap");
        assertTrue(r.stillLands(sim.phys, s, 0, 4, FLOOR - 1, 0));
    }

    /** #67 from where the walk stood against the wall: the spots' outcomes, printed (in the copy layer 2's run from a
     *  standing spot doesn't cross it: the crossings in the game came from a moving arrival). */
    @Test
    void neo67FromWhereTheWalkStoodAgainstTheWall() throws Exception {
        spots("neo_2_5bc", 0.70, 0.45, true);
    }

    /** A long jump from the end of its runway (up_4p0p5: a 4-block gap a half block up), the run-up spots back along
     *  the runway among the spots: printed (layer 2's run made it from 5 back once while its replans ran on the clock,
     *  and not since with the clock taken out: no assertion). */
    @Test
    void aLongJumpFromTheEndOfItsRunway() throws Exception {
        spots("up_4p0p5", 0.5, 0.5, true, true);
    }

    /** KINE_RUNSIM: ids (comma-separated, "fx fz" after a colon for where the walk stands; the centre otherwise). */
    @Test
    void survey() throws Exception {
        String ids = System.getenv("KINE_RUNSIM");
        if (ids == null) return;
        RunSim.beam = Integer.parseInt(System.getenv().getOrDefault("KINE_RUNSIM_BEAM", "0"));
        RunSim.draws = Integer.parseInt(System.getenv().getOrDefault("KINE_RUNSIM_DRAWS", "0"));
        for (String one : ids.split(",")) {
            String[] p = one.split(":");
            double fx = 0.5, fz = 0.5;
            float yaw = Float.NaN;
            if (p.length > 1) {
                String[] q = p[1].split(" ");
                fx = Double.parseDouble(q[0]); fz = Double.parseDouble(q[1]);
                if (q.length > 2) yaw = Float.parseFloat(q[2]);
            }
            spots(p[0], fx, fz, yaw, true, System.getenv("KINE_RUNSIM_TAKEOFF") != null);
        }
    }
}
