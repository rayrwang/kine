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
 * The jump search (Recipe) from each standing spot on a station's start block, in the copy (KINE_SPOTS: catalogue ids,
 * comma-separated): which spots find a way across and how long a search takes. For #67 (neo_2_5bc), where the walk came
 * to rest against the neo's wall and the search found nothing.
 */
public class RecipeSpotsTest {
    static final int FLOOR = Layer2StationTest.FLOOR;

    @Test
    void spots() throws Exception {
        String ids = System.getenv("KINE_SPOTS");
        if (ids == null) return;
        String cat = System.getenv().getOrDefault("KINE_CATALOGUE", "/home/raymond/_/kine_course_2026-10-14/parkour_research/catalogue.json");
        java.util.Map<String, JsonObject> byId = new java.util.HashMap<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray())
            byId.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        for (String id : ids.split(",")) {
            JsonObject L = byId.get(id.trim()).getAsJsonObject("layout");
            JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
            int sdx = st.get("dx").getAsInt(), sdy = st.get("dy").getAsInt(), sdz = st.get("dz").getAsInt();
            int lx = la.get("dx").getAsInt(), ly = FLOOR - 1 + la.get("dy").getAsInt(), lz = la.get("dz").getAsInt();
            double sy = FLOOR - 1 + sdy + StationDifficultyTest.collisionTop(L, sdx, sdy, sdz);
            Layer2Sim sim = new Layer2Sim(Layer2StationTest.build(L));
            StringBuilder map = new StringBuilder();
            int found = 0, spots = 0;
            long total = 0;
            for (double fz = 1.3; fz >= -0.3 - 1e-9; fz -= 0.1) {
                StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "z%+.1f ", fz));
                for (double fx = -0.3; fx <= 1.3 + 1e-9; fx += 0.1) {
                    double x = sdx + fx, z = sdz + fz;
                    MoveState s = Layer2Sim.standing(x, sy, z);
                    MoveState t = s.copy();
                    sim.phys.step(t, 0, 0f, 0f);
                    sim.phys.step(t, 0, 0f, 0f);
                    if (!t.onGround || Math.abs(t.x - x) > 1e-6 || Math.abs(t.z - z) > 1e-6 || Math.abs(t.y - sy) > 1e-6) {
                        row.append("  .   ");
                        continue;
                    }
                    spots++;
                    float yaw = Recipe.yawTo(x, z, lx + 0.5, lz + 0.5);
                    long t0 = System.nanoTime();
                    float step = Float.parseFloat(System.getenv().getOrDefault("KINE_SPOTS_STEP", "6"));
                    int mj = Integer.parseInt(System.getenv().getOrDefault("KINE_SPOTS_JUMP", "8"));
                    Recipe r = Recipe.search(sim.phys, t, yaw, lx, ly, lz, yaw - 90f, yaw + 90f, step, mj, 8, 60);
                    long ns = System.nanoTime() - t0;
                    total += ns;
                    if (r.found()) found++;
                    row.append(String.format(Locale.ROOT, "%5d ", r.ways));
                }
                map.append(row).append('\n');
            }
            System.out.printf(Locale.ROOT, "SPOTS %s: start %d %.2f %d landing %d %d %d; %d spots, %d find a way, mean search %.0f ms%n"
                    + "  (ways found per spot; x from -0.3 to 1.3 across, z rows; . no standing there)%n%s",
                id, sdx, sy, sdz, lx, ly, lz, spots, found, total / 1e6 / Math.max(1, spots), map);
        }
    }

    record Node(MoveState s, float yaw, double score, Node parent, int keys) {}

    /** The difficulty test's beam (every tick: the keys, a turn, jump) from s0: how many landings, and the first one's
     *  inputs (yaw/keys per tick). */
    static String beam(ai.rrw.kine.move.GroundPhysics phys, MoveState s0, float yaw0, int lx, int lz, double landY, int width, int ticks) {
        java.util.List<Node> beam = new java.util.ArrayList<>();
        beam.add(new Node(s0, yaw0, 0, null, 0));
        long landed = 0;
        Node first = null;
        int firstT = -1;
        for (int t = 0; t < ticks && !beam.isEmpty(); t++) {
            java.util.List<Node> next = new java.util.ArrayList<>();
            for (Node n : beam)
                for (float turn : StationDifficultyTest.TURNS)
                    for (int k : StationDifficultyTest.KEYS)
                        for (int jump = 0; jump <= 1; jump++) {
                            if (jump == 1 && !n.s().onGround) continue;
                            float yaw = n.yaw() + turn;
                            int keys = k | ((k & MoveState.FORWARD) != 0 ? MoveState.SPRINT : 0) | (jump == 1 ? MoveState.JUMP : 0);
                            MoveState s = n.s().copy();
                            phys.step(s, keys, yaw, 0f);
                            if (s.y < s0.y - 3 || s.inWater) continue;
                            boolean over = s.x + 0.3 > lx && s.x - 0.3 < lx + 1 && s.z + 0.3 > lz && s.z - 0.3 < lz + 1;
                            if (over && s.onGround && Math.abs(s.y - landY) < 0.05) {
                                landed++;
                                if (first == null) { first = new Node(s, yaw, 0, n, keys); firstT = t + 1; }
                                continue;
                            }
                            double d = Math.hypot(lx + 0.5 - s.x, lz + 0.5 - s.z) + Math.abs(s.y - landY) * 0.5;
                            next.add(new Node(s, yaw, d - 3.0 * Math.hypot(s.vx, s.vz), n, keys));
                        }
            next.sort(java.util.Comparator.comparingDouble(Node::score));
            java.util.List<Node> kept = new java.util.ArrayList<>();
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (Node n : next) {
                MoveState q = n.s();
                long key = ((long) Math.floor(q.x * 20) << 42) ^ ((long) Math.floor(q.z * 20) << 21) ^ ((long) Math.floor(q.y * 20))
                    ^ ((long) Math.floor(q.vx * 40) * 31) ^ ((long) Math.floor(q.vz * 40) * 131);
                if (!seen.add(key)) continue;
                kept.add(n);
                if (kept.size() >= width) break;
            }
            beam = kept;
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "landed %d, first at tick %d", landed, firstT));
        if (first != null) {
            java.util.List<String> seq = new java.util.ArrayList<>();
            for (Node n = first; n.parent() != null; n = n.parent())
                seq.add(0, String.format(Locale.ROOT, "%.1f/%d@%.2f,%.2f,%.2f", n.yaw(), n.keys(), n.s().x, n.s().y, n.s().z));
            sb.append(": ").append(String.join(" ", seq));
        }
        return sb.toString();
    }

    @Test
    void beams() throws Exception {
        String spec = System.getenv("KINE_BEAM");   // id:fx,fz;fx,fz;...
        if (spec == null) return;
        String cat = System.getenv().getOrDefault("KINE_CATALOGUE", "/home/raymond/_/kine_course_2026-10-14/parkour_research/catalogue.json");
        java.util.Map<String, JsonObject> byId = new java.util.HashMap<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray())
            byId.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        String id = spec.split(":")[0];
        JsonObject L = byId.get(id).getAsJsonObject("layout");
        JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
        int sdx = st.get("dx").getAsInt(), sdy = st.get("dy").getAsInt(), sdz = st.get("dz").getAsInt();
        int lx = la.get("dx").getAsInt(), ly = FLOOR - 1 + la.get("dy").getAsInt(), lz = la.get("dz").getAsInt();
        double sy = FLOOR - 1 + sdy + StationDifficultyTest.collisionTop(L, sdx, sdy, sdz);
        double landY = Layer2StationTest.goalFeet(L);
        Layer2Sim sim = new Layer2Sim(Layer2StationTest.build(L));
        int width = Integer.parseInt(System.getenv().getOrDefault("KINE_BEAM_WIDTH", "3000"));
        for (String sp : spec.split(":")[1].split(";")) {
            double fx = Double.parseDouble(sp.split(",")[0]), fz = Double.parseDouble(sp.split(",")[1]);
            double x = sdx + fx, z = sdz + fz;
            MoveState s = Layer2Sim.standing(x, sy, z);
            float yaw = Recipe.yawTo(x, z, lx + 0.5, lz + 0.5);
            long t0 = System.nanoTime();
            String r = beam(sim.phys, s, yaw, lx, lz, landY, width, 40);
            System.out.printf(Locale.ROOT, "BEAM %s from %.2f,%.2f (%.0f ms): %s%n", id, fx, fz, (System.nanoTime() - t0) / 1e6, r);
        }
    }

    /** Recipe.search with the turn after the jump aimed at one of these points of the landing block (fractions of the
     *  cell; NaN: no turn, the heading held): how many ways land, over all aims, and which aims. */
    static int[] searchAims(ai.rrw.kine.move.GroundPhysics phys, MoveState start, float startYaw, int lx, int ly, int lz,
                            float yawA, float yawB, float step, int maxJump, int maxTurn, int limit, double[][] aims) {
        int[] perAim = new int[aims.length + 1];
        for (int q = 0; q < aims.length; q++) {
            double ax = lx + aims[q][0], az = lz + aims[q][1];
            boolean hold = Double.isNaN(aims[q][0]);
            for (float yaw0 = yawA; yaw0 <= yawB + 1e-3; yaw0 += step)
                for (int sprint = 1; sprint >= 0; sprint--)
                    for (int strafe = -1; strafe <= 1; strafe++)
                        for (int jumpAt = 0; jumpAt <= maxJump; jumpAt++)
                            for (int turnAt = 0; turnAt <= (hold ? 0 : maxTurn); turnAt++) {
                                MoveState s = start.copy();
                                int base = MoveState.FORWARD | (sprint == 1 ? MoveState.SPRINT : 0)
                                    | (strafe < 0 ? MoveState.LEFT : strafe > 0 ? MoveState.RIGHT : 0);
                                float aim0 = yaw0 - strafe * 45f;
                                float view = startYaw;
                                boolean jumped = false, won = false;
                                int after = -1;
                                for (int t = 0; t < limit; t++) {
                                    int k = base;
                                    if (t == jumpAt && s.onGround) { k |= MoveState.JUMP; jumped = true; after = 0; }
                                    float want = !hold && after >= turnAt ? Recipe.yawTo(s.x, s.z, ax, az) - strafe * 45f : aim0;
                                    float d = ai.rrw.kine.move.GameMath.wrapDegrees(want - view);
                                    view += Math.max(-29.5f, Math.min(29.5f, d));
                                    phys.step(s, k, view, 0f);
                                    if (after >= 0) after++;
                                    boolean onCol = s.x + 0.3 > lx && s.x - 0.3 < lx + 1 && s.z + 0.3 > lz && s.z - 0.3 < lz + 1;
                                    boolean landed = s.onGround && !(s.vy > 0.0);
                                    if (jumped && after > 2 && landed) { won = onCol && s.y > ly + 0.4 && s.y < ly + 1.6; break; }
                                    if (s.y < start.y - 6) break;
                                }
                                if (won) { perAim[q]++; perAim[aims.length]++; }
                            }
        }
        return perAim;
    }

    static final double[][] AIMS = {{0.5, 0.5}, {0.5, 0.15}, {0.5, 0.85}, {0.15, 0.5}, {0.85, 0.5},
        {0.15, 0.15}, {0.15, 0.85}, {0.85, 0.15}, {0.85, 0.85}, {Double.NaN, Double.NaN}};

    @Test
    void aims() throws Exception {
        String spec = System.getenv("KINE_AIMS");   // id:fx,fz;fx,fz;...
        if (spec == null) return;
        String cat = System.getenv().getOrDefault("KINE_CATALOGUE", "/home/raymond/_/kine_course_2026-10-14/parkour_research/catalogue.json");
        java.util.Map<String, JsonObject> byId = new java.util.HashMap<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray())
            byId.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        for (String one : spec.split("\\|")) {
            String id = one.split(":")[0];
            JsonObject L = byId.get(id).getAsJsonObject("layout");
            JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
            int sdx = st.get("dx").getAsInt(), sdy = st.get("dy").getAsInt(), sdz = st.get("dz").getAsInt();
            int lx = la.get("dx").getAsInt(), ly = FLOOR - 1 + la.get("dy").getAsInt(), lz = la.get("dz").getAsInt();
            double sy = FLOOR - 1 + sdy + StationDifficultyTest.collisionTop(L, sdx, sdy, sdz);
            Layer2Sim sim = new Layer2Sim(Layer2StationTest.build(L));
            for (String sp : one.split(":")[1].split(";")) {
                double fx = Double.parseDouble(sp.split(",")[0]), fz = Double.parseDouble(sp.split(",")[1]);
                double x = sdx + fx, z = sdz + fz;
                MoveState s = Layer2Sim.standing(x, sy, z);
                float yaw = Recipe.yawTo(x, z, lx + 0.5, lz + 0.5);
                long t0 = System.nanoTime();
                int[] r = searchAims(sim.phys, s, yaw, lx, ly, lz, yaw - 90f, yaw + 90f, 6f, 8, 8, 60, AIMS);
                System.out.printf(Locale.ROOT, "AIMS %s from %.2f,%.2f (%.0f ms): all %d, per aim %s%n", id, fx, fz,
                    (System.nanoTime() - t0) / 1e6, r[AIMS.length], java.util.Arrays.toString(java.util.Arrays.copyOf(r, AIMS.length)));
            }
        }
    }

    /** KINE_SPOT_SEARCH (out CSV): every buildable catalogue layout, the jump search from its start's centre, and if it
     *  finds nothing, from the standing spots (Recipe.spots, each reached by Recipe.approach), as layer 2 does: which
     *  layouts the spots open, from which spot, and the time. */
    @Test
    void spotSearch() throws Exception {
        String out = System.getenv("KINE_SPOT_SEARCH");
        if (out == null) return;
        String only = System.getenv("KINE_SPOT_IDS");
        String cat = System.getenv().getOrDefault("KINE_CATALOGUE", "/home/raymond/_/kine_course_2026-10-14/parkour_research/catalogue.json");
        StringBuilder csv = new StringBuilder("id,here,spot,spotHow,hereMs,spotsMs\n");
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
            double sx = sdx + 0.5, sz = sdz + 0.5, sy = FLOOR - 1 + sdy + StationDifficultyTest.collisionTop(L, sdx, sdy, sdz);
            Layer2Sim sim = new Layer2Sim(Layer2StationTest.build(L));
            MoveState s = Layer2Sim.standing(sx, sy, sz);
            float yaw = Recipe.yawTo(sx, sz, lx + 0.5, lz + 0.5);
            long t0 = System.nanoTime();
            Recipe here = Recipe.search(sim.phys, s, yaw, lx, ly, lz, yaw - 90f, yaw + 90f, 6f, 8, 8, 60);
            double hereMs = (System.nanoTime() - t0) / 1e6;
            String spot = "", how = "";
            double spotsMs = 0;
            if (!here.found()) {
                long t1 = System.nanoTime();
                for (double[] sp : Recipe.spots(sdx, sdz, sx, sz, lx + 0.5, lz + 0.5)) {
                    Recipe a = Recipe.approach(sim.phys, s, yaw, sp[0], sp[1]);
                    if (a == null) continue;
                    MoveState m = Recipe.after(sim.phys, s, a);
                    float v = a.yaw[a.ticks - 1];
                    float b = Recipe.yawTo(m.x, m.z, lx + 0.5, lz + 0.5);
                    Recipe j = Recipe.search(sim.phys, m, v, lx, ly, lz, b - 90f, b + 90f, 6f, 8, 8, 60);
                    if (j.found()) {
                        spot = String.format(Locale.ROOT, "%.2f %.2f", sp[0] - sdx, sp[1] - sdz);
                        how = Recipe.concat(a, j).how;
                        break;
                    }
                }
                spotsMs = (System.nanoTime() - t1) / 1e6;
            }
            csv.append(String.format(Locale.ROOT, "%s,%b,%s,\"%s\",%.0f,%.0f%n", id, here.found(), spot, how, hereMs, spotsMs));
            System.out.printf(Locale.ROOT, "spotSearch %s: here %b, spot %s %s (%.0f + %.0f ms)%n", id, here.found(), spot, how, hereMs, spotsMs);
        }
        Files.writeString(Path.of(out), csv);
    }
}
