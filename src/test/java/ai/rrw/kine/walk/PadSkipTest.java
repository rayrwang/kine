package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The course's join pads as take-offs for the jump they lead into (for make_course.py's no-skip rule, RW 2026-10-14:
 * #67's pad and the no-skip check with the standing spots, the chained jumps and the templates): a join's pad is a
 * block one lower than the next jump's start and beside it (the start's x, its z one to either side). From the pad,
 * standing on it, can kine reach that jump's landing by its own searches in the copy (the jump search, the standing spots
 * with layer 2's run, the templates)? If it can, the jump can be passed without being made as meant (the course's #67,
 * neo_2_5bc: from the pad under the neo wall's open side, a plain jump round it). From a pad beside the start most
 * landings are reachable (a jump a block up and aside), so a pad counts as a skip only when it opens an easier way
 * than the start's: kine's jump search finds none from the start's centre and one of its searches does from the pad.
 * KINE_PAD_SKIP: the output JSON ({id: {"1": skip, "-1": skip}}, the side the pad is on in z; null where the pad's
 * cell is the layout's).
 */
public class PadSkipTest {
    static final int FLOOR = Layer2StationTest.FLOOR;

    @Test
    void padSkips() throws Exception {
        String out = System.getenv("KINE_PAD_SKIP");
        if (out == null) return;
        String only = System.getenv("KINE_PAD_IDS");
        String cat = System.getenv().getOrDefault("KINE_CATALOGUE", "/home/raymond/_/kine_course_2026-10-14/parkour_research/catalogue.json");
        StringBuilder json = new StringBuilder("{\n");
        int n = 0;
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray()) {
            JsonObject en = e.getAsJsonObject();
            if (!en.has("layout") || en.get("layout").isJsonNull()) continue;
            JsonObject L = en.getAsJsonObject("layout");
            if (!L.has("blocks") || L.getAsJsonArray("blocks").isEmpty() || !Layer2StationTest.buildable(L)) continue;
            String id = en.get("id").getAsString();
            if (only != null && !java.util.Arrays.asList(only.split(",")).contains(id)) continue;
            JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
            int sdx = st.get("dx").getAsInt(), sdy = st.get("dy").getAsInt(), sdz = st.get("dz").getAsInt();
            int lx = la.get("dx").getAsInt(), ly = FLOOR - 1 + la.get("dy").getAsInt(), lz = la.get("dz").getAsInt();
            double goalY = Layer2StationTest.goalFeet(L);
            StringBuilder row = new StringBuilder();
            // the jump as meant: kine's jump search from the start's centre
            boolean fromStart;
            {
                Layer2Sim s0 = new Layer2Sim(NeoTemplateGenTest.build(L));
                double sy = FLOOR - 1 + sdy + StationDifficultyTest.collisionTop(L, sdx, sdy, sdz);
                MoveState m = Layer2Sim.standing(sdx + 0.5, sy, sdz + 0.5);
                float b0 = Recipe.yawTo(m.x, m.z, lx + 0.5, lz + 0.5);
                fromStart = Recipe.search(s0.phys, m, b0, lx, ly, lz, b0 - 90f, b0 + 90f, 6f, 8, 8, 60).found();
            }
            boolean neo = en.has("category") && "neo".equals(en.get("category").getAsString());
            for (int side : neo ? new int[]{1, -1, 0, 2} : new int[]{1, -1}) {
                // side 0 (neos only: the course builder uses it for them): the pad behind the start (one lower, x - 1), for neos whose side pads both open a way
                // round: there the test is strict (any of kine's searches from the pad that lands is a skip, whatever
                // the start's own search finds), as the walk without a route hops from the pad toward the landing
                // side 2: two behind (x - 2, one lower: the walk jumps a block's gap onto the start), where the pad
                // right behind opens a way too (neo_head_h2h: the skulls' start is half a block high)
                boolean behind = side == 0 || side == 2;
                boolean strict = behind;
                TestWorld w = NeoTemplateGenTest.build(L);
                int px = sdx - (side == 0 ? 1 : side == 2 ? 2 : 0), py = FLOOR - 1 + sdy - 1, pz = sdz + (behind ? 0 : side);
                boolean free = w.get(px, py, pz) == Palette.AIR && w.get(px, py + 1, pz) == Palette.AIR && w.get(px, py + 2, pz) == Palette.AIR;
                boolean skip = false;
                String how = "";
                if (free) {
                    w.set(px, py, pz, TestWorld.STONE);
                    Layer2Sim sim = new Layer2Sim(w);
                    MoveState s = Layer2Sim.standing(px + 0.5, py + 1, pz + 0.5);
                    float b = Recipe.yawTo(s.x, s.z, lx + 0.5, lz + 0.5);
                    Recipe r = Recipe.search(sim.phys, s, b, lx, ly, lz, b - 90f, b + 90f, 6f, 8, 8, 60);
                    boolean judge = strict || !fromStart;
                    if (!judge) { /* the jump as meant is the jump search's already: the pad is no easier */ }
                    else if (r.found()) { skip = true; how = "jump search"; }
                    if (!skip && judge) {
                        long start = sim.view.nodeAt(px, py + 1, pz);
                        Planner.Plan dp = start == View.NONE ? null : Planner.direct(sim.view, start, lx, lz, goalY, sim.costs, 0.2, true);
                        if (dp != null) {
                            Field f = Field.direct(sim.view, sim.costs, true, true, false, 1, dp.result.path, lx, lz, goalY);
                            RunSim.Goal goal = new RunSim.Goal(lx, lz, goalY, true, 0f, sim.costs.maxFall, true, true);
                            int[][] land = {{lx, ly, lz}};
                            if (RunSim.run(f, sim.grid, sim.body, goal, s, b, 0f, land) != null) { skip = true; how = "layer 2's run"; }
                            for (double[] sp : Recipe.spots(px, pz, s.x, s.z, lx + 0.5, lz + 0.5)) {
                                if (skip) break;
                                Recipe a = Recipe.approach(sim.phys, s, b, sp[0], sp[1]);
                                if (a == null) continue;
                                MoveState m = Recipe.after(sim.phys, s, a);
                                float v = a.yaw[a.ticks - 1], bb = Recipe.yawTo(m.x, m.z, lx + 0.5, lz + 0.5);
                                if (Recipe.search(sim.phys, m, v, lx, ly, lz, bb - 90f, bb + 90f, 6f, 8, 8, 60).found()
                                    || RunSim.run(f, sim.grid, sim.body, goal, m, v, 0f, land) != null) { skip = true; how = "a spot on the pad"; }
                            }
                        }
                    }
                    if (!skip && judge) {
                        Recipe t = NeoTemplates.fit(sim.phys, s, b, px, py, pz, new int[]{lx, ly, lz});
                        if (t != null) { skip = true; how = "a template"; }
                    }
                }
                if (row.length() > 0) row.append(", ");
                row.append(String.format(Locale.ROOT, "\"%s\": %s", side == 0 ? "b" : side == 2 ? "b2" : Integer.toString(side), free ? Boolean.toString(skip) : "null"));
                System.out.printf(Locale.ROOT, "pad skip %s side %s: %s %s%n", id, side == 0 ? "behind" : side == 2 ? "two behind" : String.format(Locale.ROOT, "%+d", side), free ? (skip ? "SKIP" : "ok") : "not free", how);
            }
            json.append(n++ > 0 ? ",\n" : "").append(String.format(Locale.ROOT, " \"%s\": {%s}", id, row));
        }
        Files.writeString(Path.of(out), json.append("\n}\n").toString());
    }
}
