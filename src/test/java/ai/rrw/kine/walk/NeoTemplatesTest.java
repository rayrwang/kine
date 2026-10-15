package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Neo templates: the 8 ways a layout can lie (turned and mirrored) move a template's inputs with it; a template of the
 * course's #67 (neo_2_5bc, a sequence the wide beam found in the copy from its start block's centre) fits the layout
 * however it lies, from near its spot, and not where its blocks aren't.
 */
public class NeoTemplatesTest {
    static final int FLOOR = Layer2StationTest.FLOOR;
    static final String CAT = "/home/raymond/_/kine_course_2026-10-14/parkour_research/catalogue.json";

    /** neo_2_5bc from (0.5, 0.5), the view at -90 (RecipeSpotsTest.beams, width 3000: the first landing, tick 12). */
    static final float[] YAW = {-60f, -90f, -105f, -75f, -90f, -95f, -110f, -140f, -135f, -135f, -150f, -150f};
    static final int[] KEYS = {10, 8, 73, 73, 73, 73, 81, 73, 73, 73, 73, 65};

    static NeoTemplates.Template template() {
        return new NeoTemplates.Template("neo_2_5bc", 2, 0, 0, 1.0, 1.0, 0.5, 0.5, -90f, YAW, KEYS, 0);
    }

    @Test
    void aTurnedOrMirroredYawPointsWhereTheTurnedOrMirroredDirectionDoes() {
        for (float y = -180f; y < 180f; y += 17f)
            for (int r = 0; r < 4; r++)
                for (int m = 0; m <= 1; m++) {
                    double dx = -Math.sin(Math.toRadians(y)), dz = Math.cos(Math.toRadians(y));
                    double[] t = NeoTemplates.turn(dx, dz, r, m == 1);
                    float yy = NeoTemplates.yaw(y, r, m == 1);
                    assertEquals(t[0], -Math.sin(Math.toRadians(yy)), 1e-9);
                    assertEquals(t[1], Math.cos(Math.toRadians(yy)), 1e-9);
                }
        assertEquals(MoveState.FORWARD | MoveState.RIGHT, NeoTemplates.keys(MoveState.FORWARD | MoveState.LEFT, true));
        assertEquals(MoveState.FORWARD | MoveState.LEFT, NeoTemplates.keys(MoveState.FORWARD | MoveState.LEFT, false));
    }

    static JsonObject layout(String id) throws Exception {
        if (!Files.exists(Path.of(CAT))) return null;
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(CAT))).getAsJsonArray())
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject().getAsJsonObject("layout");
        return null;
    }

    /** The layout built turned and mirrored about its start block (the block at the origin stays there). */
    static TestWorld built(JsonObject L, int r, boolean m) {
        TestWorld w = TestWorld.blocks(-48, -48, 31, 31);
        w.fill(-12, FLOOR - 16, -12, 12, FLOOR - 16, 12, TestWorld.STONE);
        for (JsonElement el : L.getAsJsonArray("blocks")) {
            JsonObject b = el.getAsJsonObject();
            double[] t = NeoTemplates.turn(b.get("dx").getAsInt(), b.get("dz").getAsInt(), r, m);
            w.set((int) Math.round(t[0]), FLOOR - 1 + b.get("dy").getAsInt(), (int) Math.round(t[1]),
                Layer2StationTest.kindOf(b.get("block").getAsString()));
        }
        return w;
    }

    @Test
    void theNeo67TemplateFitsHoweverTheLayoutLies() throws Exception {
        JsonObject L = layout("neo_2_5bc");
        if (L == null) return;   // the catalogue is not on this machine
        NeoTemplates.use(List.of(template()));
        try {
            int landed = 0, tried = 0;
            for (int r = 0; r < 4; r++)
                for (int m = 0; m <= 1; m++) {
                    Layer2Sim sim = new Layer2Sim(built(L, r, m == 1));
                    double[] lt = NeoTemplates.turn(2, 0, r, m == 1);
                    int[] land = {(int) Math.round(lt[0]), FLOOR - 1, (int) Math.round(lt[1])};
                    // from the spot, and from a few hundredths off it, as the walk comes to rest
                    for (double[] off : new double[][]{{0, 0}, {0.04, 0}, {0, 0.04}, {-0.04, 0.03}}) {
                        MoveState s = Layer2Sim.standing(0.5 + off[0], FLOOR, 0.5 + off[1]);
                        float view = NeoTemplates.yaw(-90f, r, m == 1) + 40f;
                        Recipe rc = NeoTemplates.fit(sim.phys, s, view, 0, FLOOR - 1, 0, land);
                        tried++;
                        if (rc == null) continue;
                        landed++;
                        assertTrue(rc.stillLands(sim.phys, s, 0, land[0], land[1], land[2]), rc.how);
                    }
                }
            System.out.printf("neo 67 template: fits %d of %d (8 ways x 4 starts)%n", landed, tried);
            assertTrue(landed >= 30, "fits from nearly every start: " + landed);
            // and not where the wall isn't: a plain gap of two blocks, the same landing
            TestWorld plain = TestWorld.blocks(-48, -48, 31, 31);
            plain.set(0, FLOOR - 1, 0, TestWorld.STONE);
            plain.set(2, FLOOR - 1, 0, TestWorld.STONE);
            plain.fill(0, FLOOR + 2, -1, 2, FLOOR + 2, 2, TestWorld.STONE);   // a ceiling, no wall: the template's run misses
            Layer2Sim sim = new Layer2Sim(plain);
            Recipe rc = NeoTemplates.fit(sim.phys, Layer2Sim.standing(0.5, FLOOR, 0.5), -90f, 0, FLOOR - 1, 0, new int[]{2, FLOOR - 1, 0});
            if (rc != null) assertTrue(rc.stillLands(sim.phys, Layer2Sim.standing(0.5, FLOOR, 0.5), 0, 2, FLOOR - 1, 0));
        } finally {
            NeoTemplates.use(null);
        }
    }

    @Test
    void noTemplateForALandingNoTemplateHas() {
        NeoTemplates.use(List.of(template()));
        try {
            TestWorld w = TestWorld.blocks(-16, -16, 15, 15);
            w.set(0, FLOOR - 1, 0, TestWorld.STONE);
            w.set(3, FLOOR - 1, 1, TestWorld.STONE);
            Layer2Sim sim = new Layer2Sim(w);
            assertNull(NeoTemplates.fit(sim.phys, Layer2Sim.standing(0.5, FLOOR, 0.5), -90f, 0, FLOOR - 1, 0, new int[]{3, FLOOR - 1, 1}));
        } finally {
            NeoTemplates.use(null);
        }
    }

    /** KINE_TEMPLATE_SURVEY (any value; KINE_TEMPLATE_IDS to narrow): each layout the resource has a template for,
     *  built as the generator builds it (fences and panes joined), the walk standing on its key block (the layout's
     *  0, 0, 0 where it has a block, else its start) at a few places: whether NeoTemplates.fit lands it there, with the
     *  whole resource, and the time it takes. Printed. */
    @Test
    void survey() throws Exception {
        if (System.getenv("KINE_TEMPLATE_SURVEY") == null) return;
        String only = System.getenv("KINE_TEMPLATE_IDS");
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (NeoTemplates.Template t : NeoTemplates.load()) ids.add(t.id());
        for (String id : ids) {
            if (only != null && !java.util.Arrays.asList(only.split(",")).contains(id)) continue;
            JsonObject L = layout(id);
            if (L == null || !NeoTemplateGenTest.buildable(L)) { System.out.printf("template survey %s: not built%n", id); continue; }
            TestWorld w = NeoTemplateGenTest.build(L);
            // the key block: the layout's 0, 0, 0 where it has a block with room to stand above it
            boolean key = false, above = false;
            for (JsonElement el : L.getAsJsonArray("blocks")) {
                JsonObject b = el.getAsJsonObject();
                if (b.get("dx").getAsInt() != 0 || b.get("dz").getAsInt() != 0) continue;
                int dy = b.get("dy").getAsInt();
                if (dy == 0) key = true;
                if (dy == 1 || dy == 2) above = true;
            }
            key &= !above;
            JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
            int kx = key ? 0 : st.get("dx").getAsInt(), ky = FLOOR - 1 + (key ? 0 : st.get("dy").getAsInt()), kz = key ? 0 : st.get("dz").getAsInt();
            // the landing as layer 2 gives it in play: the block under its feet rounded (a trapdoor's, a carpet's: the
            // block below it)
            double lfeet = FLOOR - 1 + la.get("dy").getAsInt() + StationDifficultyTest.collisionTop(L, la.get("dx").getAsInt(), la.get("dy").getAsInt(), la.get("dz").getAsInt());
            int[] land = {la.get("dx").getAsInt(), (int) Math.round(lfeet) - 1, la.get("dz").getAsInt()};
            double top = ky + StationDifficultyTest.collisionTop(L, kx, ky - FLOOR + 1, kz);
            int found = 0, n = 0;
            StringBuilder hows = new StringBuilder();
            long t0 = System.nanoTime();
            for (double[] at : new double[][]{{0.5, 0.5}, {0.3, 0.5}, {0.7, 0.5}, {0.5, 0.3}, {0.5, 0.7}, {0.7, 0.45}, {0.25, 0.75}}) {
                Layer2Sim sim = new Layer2Sim(w);
                MoveState s = Layer2Sim.standing(kx + at[0], top, kz + at[1]);
                float view = Recipe.yawTo(s.x, s.z, land[0] + 0.5, land[2] + 0.5) + 40f;
                Recipe rc = NeoTemplates.fit(sim.phys, s, view, kx, ky, kz, land);
                n++;
                if (rc == null) continue;
                found++;
                boolean replays = rc.stillLands(sim.phys, s, 0, land[0], land[1], land[2]);
                if (hows.length() < 240) hows.append(" | ").append(replays ? "" : "(replay misses) ").append(rc.how);
            }
            System.out.printf(java.util.Locale.ROOT, "template survey %s: %d of %d places, %.0f ms%s%n", id, found, n,
                (System.nanoTime() - t0) / 1e6, hows);
        }
    }

    /** KINE_TEMPLATE_RAW: each template of the listed ids played as stored from exactly its spot, its view at its start
     *  yaw, in the layout as the generator builds it: where it ends (a check of the frame). */
    @Test
    void raw() throws Exception {
        String only = System.getenv("KINE_TEMPLATE_RAW");
        if (only == null) return;
        for (NeoTemplates.Template t : NeoTemplates.load()) {
            if (!java.util.Arrays.asList(only.split(",")).contains(t.id())) continue;
            JsonObject L = layout(t.id());
            Layer2Sim sim = new Layer2Sim(NeoTemplateGenTest.build(L));
            MoveState s = Layer2Sim.standing(t.sx(), FLOOR - 1 + t.feet(), t.sz());
            for (int i = 0; i < 3; i++) sim.phys.step(s, 0, t.startYaw(), 0f);
            StringBuilder tr = new StringBuilder();
            for (int i = 0; i < t.keys().length + 6; i++) {
                int k = i < t.keys().length ? t.keys()[i] : t.keys()[t.keys().length - 1] & ~MoveState.JUMP;
                float y = i < t.keys().length ? t.yaw()[i] : t.yaw()[t.keys().length - 1];
                sim.phys.step(s, k, y, 0f);
                if (i % 5 == 0 || i >= t.keys().length - 2) tr.append(String.format(java.util.Locale.ROOT, " %d:%.2f,%.2f,%.2f%s", i, s.x, s.y - FLOOR + 1, s.z, s.onGround ? "g" : ""));
            }
            System.out.printf(java.util.Locale.ROOT, "template raw %s spot %.2f %.2f (start %.2f %.2f): landing %s:%s%n", t.id(), t.sx(), t.sz(),
                0.0, 0.0, java.util.Arrays.toString(new int[]{t.ldx(), t.ldy(), t.ldz()}), tr);
        }
    }

    @Test
    void theResourceReads() {
        List<NeoTemplates.Template> all = NeoTemplates.load();
        assertNotNull(all);
        for (NeoTemplates.Template t : all) assertEquals(t.yaw().length, t.keys().length, t.id());
    }
}
