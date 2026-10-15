package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The standing spots and the walk to one; a layout the search from the start's centre misses and a spot opens (the
 *  course's neo_head_h2h, in the copy). */
public class RecipeSpotTest {
    static final int FLOOR = Layer2StationTest.FLOOR;
    static final String CAT = "/home/raymond/_/kine_course_2026-10-14/parkour_research/catalogue.json";

    static JsonObject layout(String id) throws Exception {
        if (!Files.exists(Path.of(CAT))) return null;
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(CAT))).getAsJsonArray())
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject().getAsJsonObject("layout");
        return null;
    }

    @Test
    void spotsStayOnTheBlockAndLeaveOutWhereTheWalkStands() {
        double[][] sp = Recipe.spots(5, 7, 5.5, 7.5, 9.5, 7.5);
        assertEquals(7, sp.length);   // the centre left out (the walk stands there)
        for (double[] p : sp) {
            assertTrue(p[0] >= 5 + Recipe.SPOT_EDGE - 1e-9 && p[0] <= 6 - Recipe.SPOT_EDGE + 1e-9);
            assertTrue(p[1] >= 7 + Recipe.SPOT_EDGE - 1e-9 && p[1] <= 8 - Recipe.SPOT_EDGE + 1e-9);
        }
        // the first is back, away from the landing (at +x): a run up
        assertTrue(sp[0][0] < 5.5 - 0.3 && Math.abs(sp[0][1] - 7.5) < 1e-9, Arrays.toString(sp[0]));
    }

    @Test
    void theWalkToASpotComesToRestThere() {
        TestWorld w = TestWorld.blocks(-16, -16, 31, 15);
        w.set(0, FLOOR - 1, 0, TestWorld.STONE);
        Layer2Sim sim = new Layer2Sim(w);
        MoveState s = Layer2Sim.standing(0.7, FLOOR, 0.45);
        for (double[] sp : Recipe.spots(0, 0, s.x, s.z, 3.5, 0.5)) {
            Recipe a = Recipe.approach(sim.phys, s, 90f, sp[0], sp[1]);
            assertNotNull(a, "a walk to " + Arrays.toString(sp));
            MoveState m = Recipe.after(sim.phys, s, a);
            assertTrue(m.onGround && Math.abs(m.y - FLOOR) < 1e-6 && Math.hypot(m.vx, m.vz) <= 0.01);
            assertTrue(Math.hypot(m.x - sp[0], m.z - sp[1]) <= Recipe.SPOT_NEAR, Arrays.toString(sp) + " " + m);
        }
    }

    /** neo_head_h2h: no way from the start's centre in the jump search; from the spot back toward its -z side there is
     *  one (the walk there, then the jump: RecipeSpotsTest.spotSearch over the catalogue). */
    @Test
    void aSpotOpensAJumpTheCentreMisses() throws Exception {
        JsonObject L = layout("neo_head_h2h");
        if (L == null) return;   // the catalogue is not on this machine
        Layer2Sim sim = new Layer2Sim(Layer2StationTest.build(L));
        JsonObject la = L.getAsJsonObject("landing"), st = L.getAsJsonObject("start");
        int lx = la.get("dx").getAsInt(), ly = FLOOR - 1 + la.get("dy").getAsInt(), lz = la.get("dz").getAsInt();
        int sdx = st.get("dx").getAsInt(), sdy = st.get("dy").getAsInt(), sdz = st.get("dz").getAsInt();
        double sy = FLOOR - 1 + sdy + StationDifficultyTest.collisionTop(L, sdx, sdy, sdz);
        MoveState s = Layer2Sim.standing(sdx + 0.5, sy, sdz + 0.5);
        float b = Recipe.yawTo(s.x, s.z, lx + 0.5, lz + 0.5);
        assertTrue(!Recipe.search(sim.phys, s, b, lx, ly, lz, b - 90f, b + 90f, 6f, 8, 8, 60).found());
        Recipe found = null;
        for (double[] sp : Recipe.spots(sdx, sdz, s.x, s.z, lx + 0.5, lz + 0.5)) {
            Recipe a = Recipe.approach(sim.phys, s, b, sp[0], sp[1]);
            if (a == null) continue;
            MoveState m = Recipe.after(sim.phys, s, a);
            float bb = Recipe.yawTo(m.x, m.z, lx + 0.5, lz + 0.5);
            Recipe j = Recipe.search(sim.phys, m, a.yaw[a.ticks - 1], lx, ly, lz, bb - 90f, bb + 90f, 6f, 8, 8, 60);
            if (j.found()) { found = Recipe.concat(a, j); break; }
        }
        assertNotNull(found);
        assertTrue(found.stillLands(sim.phys, s, 0, lx, ly, lz), "the walk there and the jump, from the start, land");
    }
}
