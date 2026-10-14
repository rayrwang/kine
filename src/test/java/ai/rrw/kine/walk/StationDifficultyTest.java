package ai.rrw.kine.walk;

import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A difficulty for each parkour station, measured in the copy (for the world's book, RW 2026-10-13: "sorted in ascending
 * order of difficulty"): a beam over every tick's input from the start (the keys, a strafe, sprinting, jump; turns of at
 * most 30 degrees a tick), led by the straight line to the landing; how many of the inputs tried land on it, and the
 * fewest ticks to land. KINE_DIFFICULTY set: the out file (CSV: id, landed, tried, fraction, first landing tick).
 */
public class StationDifficultyTest {
    static final int FLOOR = Layer2StationTest.FLOOR;
    static final float[] TURNS = {0f, 5f, -5f, 15f, -15f, 30f, -30f};
    static final int[] KEYS = {MoveState.FORWARD, MoveState.FORWARD | MoveState.LEFT, MoveState.FORWARD | MoveState.RIGHT,
        MoveState.BACKWARD, MoveState.BACKWARD | MoveState.LEFT, MoveState.BACKWARD | MoveState.RIGHT, MoveState.LEFT, MoveState.RIGHT, 0};

    record Node(MoveState s, float yaw, double score) {}

    /** The top of the collision of the block at (dx, dy, dz) in the layout, as the feet stand on it (1 for none: the
     *  block above the floor's height). */
    static double top(JsonObject L, int dx, int dy, int dz) {
        for (JsonElement el : L.getAsJsonArray("blocks")) {
            JsonObject b = el.getAsJsonObject();
            if (b.get("dx").getAsInt() != dx || b.get("dy").getAsInt() != dy || b.get("dz").getAsInt() != dz) continue;
            Kind k = Layer2StationTest.kindOf(b.get("block").getAsString());
            if (k == null || k.boxes.length == 0) return 1.0;
            double t = 0;
            for (double[] bx : k.boxes) t = Math.max(t, bx[4]);
            return t;
        }
        return 1.0;
    }

    /** The top of the block's collision at (dx, dy, dz) in the layout, 0 for none (air, water, a ladder). */
    static double collisionTop(JsonObject L, int dx, int dy, int dz) {
        for (JsonElement el : L.getAsJsonArray("blocks")) {
            JsonObject b = el.getAsJsonObject();
            if (b.get("dx").getAsInt() != dx || b.get("dy").getAsInt() != dy || b.get("dz").getAsInt() != dz) continue;
            Kind k = Layer2StationTest.kindOf(b.get("block").getAsString());
            double t = 0;
            if (k != null) for (double[] bx : k.boxes) t = Math.max(t, bx[4]);
            return t;
        }
        return 0;
    }

    /** KINE_RECIPE_WAYS set (the out file): kine's jump search (Recipe) from each buildable station's start in the copy,
     *  with the parameters the game's suite uses (ParkourTest: headings 90 degrees either side of the landing every 6,
     *  jump ticks to 8, 8 turns, 60 ticks), so its ways count stands in for the game's where no game run has the layout
     *  (CSV: id, found, ways, tried). */
    @Test
    void recipeWays() throws Exception {
        String out = System.getenv("KINE_RECIPE_WAYS");
        if (out == null) return;
        String cat = System.getenv().getOrDefault("KINE_CATALOGUE", "/home/raymond/_/kine_path_2026-10-12/parkour_research/catalogue.json");
        StringBuilder csv = new StringBuilder("id,found,ways,tried\n");
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray()) {
            JsonObject en = e.getAsJsonObject();
            if (!en.has("layout") || en.get("layout").isJsonNull()) continue;
            JsonObject L = en.getAsJsonObject("layout");
            if (!L.has("blocks") || L.getAsJsonArray("blocks").isEmpty() || !Layer2StationTest.buildable(L)) continue;
            String id = en.get("id").getAsString();
            JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
            int sdx = st.get("dx").getAsInt(), sdy = st.get("dy").getAsInt(), sdz = st.get("dz").getAsInt();
            int lx = la.get("dx").getAsInt(), ly = FLOOR - 1 + la.get("dy").getAsInt(), lz = la.get("dz").getAsInt();
            // the start's feet as the game's suite puts them: on the top of the block's collision, in its cell if none
            double sx = sdx + 0.5, sz = sdz + 0.5, sy = FLOOR - 1 + sdy + collisionTop(L, sdx, sdy, sdz);
            Layer2Sim sim = new Layer2Sim(Layer2StationTest.build(L));
            float yaw = Recipe.yawTo(sx, sz, lx + 0.5, lz + 0.5);
            Recipe r = Recipe.search(sim.phys, Layer2Sim.standing(sx, sy, sz), yaw, lx, ly, lz, yaw - 90f, yaw + 90f, 6f, 8, 8, 60);
            csv.append(String.format(Locale.ROOT, "%s,%b,%d,%d%n", id, r.found(), r.ways, r.tried));
            System.out.printf(Locale.ROOT, "recipe ways %s: found %b ways %d of %d%n", id, r.found(), r.ways, r.tried);
        }
        Files.writeString(Path.of(out), csv);
    }

    /** KINE_PLAIN_WAYS set (the out file): kine's jump search, as recipeWays, from a stone block to a stone block dx
     *  ahead, dz to the side and dy up (centre to centre), over the pool: the plain jumps' difficulty on the stations'
     *  scale, for the course's joins (a join's block must be harder to reach from a jump's take-off than the jump's own
     *  landing). CSV: dx, dz, dy, ways, tried. */
    @Test
    void plainWays() throws Exception {
        String out = System.getenv("KINE_PLAIN_WAYS");
        if (out == null) return;
        StringBuilder csv = new StringBuilder("dx,dz,dy,ways,tried\n");
        for (int dy = -3; dy <= 1; dy++)
            for (int dz = 0; dz <= 4; dz++)
                for (int dx = 1; dx <= 8; dx++) {
                    if (dx * dx + dz * dz < 4) continue;
                    TestWorld w = TestWorld.blocks(-48, -16, 31, 31);
                    w.fill(-38, FLOOR - 16, -8, 22, FLOOR - 16, 12, TestWorld.STONE);
                    w.fill(-38, FLOOR - 15, -8, 22, FLOOR - 14, 12, TestWorld.WATER);
                    w.set(0, FLOOR - 1, 0, TestWorld.STONE);
                    w.set(dx, FLOOR - 1 + dy, dz, TestWorld.STONE);
                    Layer2Sim sim = new Layer2Sim(w);
                    float yaw = Recipe.yawTo(0.5, 0.5, dx + 0.5, dz + 0.5);
                    Recipe r = Recipe.search(sim.phys, Layer2Sim.standing(0.5, FLOOR, 0.5), yaw, dx, FLOOR - 1 + dy, dz,
                        yaw - 90f, yaw + 90f, 6f, 8, 8, 60);
                    csv.append(String.format(Locale.ROOT, "%d,%d,%d,%d,%d%n", dx, dz, dy, r.ways, r.tried));
                }
        Files.writeString(Path.of(out), csv);
    }

    /** KINE_TOPS set (the out file): for every block of the catalogue's layouts the copy builds, the top of its
     *  collision (0 for none) and whether it is climbable, as JSON {block: [top, climb]}: for the course's joins. */
    @Test
    void blockTops() throws Exception {
        String out = System.getenv("KINE_TOPS");
        if (out == null) return;
        String cat = System.getenv().getOrDefault("KINE_CATALOGUE", "/home/raymond/_/kine_path_2026-10-12/parkour_research/catalogue.json");
        java.util.TreeMap<String, String> tops = new java.util.TreeMap<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray()) {
            JsonObject en = e.getAsJsonObject();
            if (!en.has("layout") || en.get("layout").isJsonNull() || !en.getAsJsonObject("layout").has("blocks")) continue;
            for (JsonElement el : en.getAsJsonObject("layout").getAsJsonArray("blocks")) {
                String blk = el.getAsJsonObject().get("block").getAsString();
                Kind k = Layer2StationTest.kindOf(blk);
                if (k == null) continue;
                double t = 0;
                for (double[] bx : k.boxes) t = Math.max(t, bx[4]);
                tops.put(blk, String.format(Locale.ROOT, "[%.4f, %b]", t, k.is(Kind.CLIMB)));
            }
        }
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (var en : tops.entrySet())
            sb.append(" \"").append(en.getKey().replace("\"", "\\\"")).append("\": ").append(en.getValue()).append(++i < tops.size() ? ",\n" : "\n");
        Files.writeString(Path.of(out), sb.append("}\n"));
    }

    @Test
    void difficulties() throws Exception {
        String out = System.getenv("KINE_DIFFICULTY");
        if (out == null) return;
        int width = Integer.parseInt(System.getenv().getOrDefault("KINE_DIFFICULTY_BEAM", "1500"));
        String cat = "/home/raymond/_/kine_path_2026-10-12/parkour_research/catalogue.json";
        StringBuilder csv = new StringBuilder("id,landed,tried,fraction,first\n");
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray()) {
            JsonObject en = e.getAsJsonObject();
            if (!en.has("layout") || en.get("layout").isJsonNull()) continue;
            JsonObject L = en.getAsJsonObject("layout");
            if (!L.has("blocks") || L.getAsJsonArray("blocks").isEmpty() || !Layer2StationTest.buildable(L)) continue;
            String id = en.get("id").getAsString();
            TestWorld w = Layer2StationTest.build(L);
            GroundPhysics phys = new Layer2Sim(w).phys;
            JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
            int sdx = st.get("dx").getAsInt(), sdy = st.get("dy").getAsInt(), sdz = st.get("dz").getAsInt();
            int lx = la.get("dx").getAsInt(), ldy = la.get("dy").getAsInt(), lz = la.get("dz").getAsInt();
            double startY = FLOOR - 1 + sdy + top(L, sdx, sdy, sdz), landY = FLOOR - 1 + ldy + top(L, lx, ldy, lz);
            MoveState s0 = Layer2Sim.standing(sdx + 0.5, startY, sdz + 0.5);
            float yaw0 = (float) Math.toDegrees(Math.atan2(-(lx - sdx), lz - sdz));
            List<Node> beam = new ArrayList<>();
            beam.add(new Node(s0, yaw0, 0));
            long tried = 0, landed = 0;
            int first = -1;
            for (int t = 0; t < 45 && !beam.isEmpty(); t++) {
                List<Node> next = new ArrayList<>();
                for (Node n : beam)
                    for (float turn : TURNS)
                        for (int k : KEYS)
                            for (int jump = 0; jump <= 1; jump++) {
                                if (jump == 1 && !n.s.onGround && !phys.onClimbable(n.s)) continue;
                                float yaw = n.yaw + turn;
                                int keys = k | ((k & MoveState.FORWARD) != 0 ? MoveState.SPRINT : 0) | (jump == 1 ? MoveState.JUMP : 0);
                                MoveState s = n.s.copy();
                                phys.step(s, keys, yaw, 0f);
                                tried++;
                                if (s.y < FLOOR - 8 || s.inWater) continue;   // fell
                                boolean over = s.x + 0.3 > lx && s.x - 0.3 < lx + 1 && s.z + 0.3 > lz && s.z - 0.3 < lz + 1;
                                boolean on = over && (s.onGround && Math.abs(s.y - landY) < 0.05
                                    || phys.onClimbable(s) && s.y > landY - 1.2 && s.y < landY + 0.6);
                                if (on) {
                                    landed++;
                                    if (first < 0) first = t + 1;
                                    continue;
                                }
                                double d = Math.hypot(lx + 0.5 - s.x, lz + 0.5 - s.z) + Math.abs(s.y - landY) * 0.5;
                                next.add(new Node(s, yaw, d - 3.0 * Math.hypot(s.vx, s.vz)));
                            }
                next.sort(Comparator.comparingDouble(Node::score));
                List<Node> kept = new ArrayList<>();
                java.util.Set<Long> seen = new java.util.HashSet<>();
                for (Node n : next) {
                    long key = ((long) Math.floor(n.s.x * 20) << 42) ^ ((long) Math.floor(n.s.z * 20) << 21) ^ ((long) Math.floor(n.s.y * 20))
                        ^ ((long) Math.floor(n.s.vx * 40) * 31) ^ ((long) Math.floor(n.s.vz * 40) * 131);
                    if (!seen.add(key)) continue;
                    kept.add(n);
                    if (kept.size() >= width) break;
                }
                beam = kept;
            }
            double fraction = tried == 0 ? 0 : (double) landed / tried;
            csv.append(String.format(Locale.ROOT, "%s,%d,%d,%.6g,%d%n", id, landed, tried, fraction, first));
            System.out.printf(Locale.ROOT, "difficulty %s: landed %d of %d (%.3g), first at tick %d%n", id, landed, tried, fraction, first);
        }
        Files.writeString(Path.of(out), csv);
    }
}
