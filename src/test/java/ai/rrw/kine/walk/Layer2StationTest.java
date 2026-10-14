package ai.rrw.kine.walk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The parkour stations in the movement copy (KINE_MEASURE set, the catalogue at KINE_CATALOGUE): every station built
 * of blocks the copy has (kindOf: full blocks, ladders, and the shaped ones listed there; "plain": full blocks and
 * ladders only), its take-off at (0, 20, 0), nothing else, walked from its start to its landing
 * by: layer 1 and layer 2 (Parkour on); layer 2 alone with its own actions only; layer 2 alone with free actions near
 * jumps; and with the copy-found jumps too. Arrivals and the replan time (mean and worst) for each.
 */
public class Layer2StationTest {
    static final int FLOOR = 20;

    static double[] box16(double x0, double y0, double z0, double x1, double y1, double z1) {
        return TestWorld.box(x0 / 16, y0 / 16, z0 / 16, x1 / 16, y1 / 16, z1 / 16);
    }

    static Kind kind(String name, List<double[]> boxes) { return Palette.add("station_" + name, boxes, 0.6F, 1.0F, 0); }

    /** The catalogue's blocks the copy can build: their collision boxes as the game has them (26.1.2; a pane, bars,
     *  a fence and a wall unconnected), friction, speed. Anything else (anvils, stairs, slime, honey, scaffolding,
     *  plants ...) leaves the station out. */
    static final java.util.Map<String, Kind> KINDS = new java.util.HashMap<>();
    static {
        Kind full = TestWorld.STONE;
        for (String b : new String[]{"stone", "gold_block", "jungle_log", "grass_block", "sand"}) KINDS.put(b, full);
        KINDS.put("ice", TestWorld.ICE);
        KINDS.put("packed_ice", TestWorld.ICE);
        KINDS.put("blue_ice", Palette.add("station_blue_ice", List.of(TestWorld.box(0, 0, 0, 1, 1, 1)), 0.989F, 1.0F, 0));
        KINDS.put("soul_sand", TestWorld.SOUL_SAND);
        KINDS.put("magma_block", TestWorld.MAGMA);
        KINDS.put("cactus", TestWorld.CACTUS);
        KINDS.put("oak_fence", TestWorld.FENCE);
        KINDS.put("stone_slab[type=bottom]", TestWorld.SLAB);
        KINDS.put("stone_slab[type=top]", kind("slab_top", List.of(TestWorld.box(0, 0.5, 0, 1, 1, 1))));
        Kind pane = kind("pane", List.of(box16(7, 0, 7, 9, 16, 9)));
        KINDS.put("glass_pane", pane);
        KINDS.put("iron_bars", pane);
        KINDS.put("iron_chain[axis=y]", kind("chain", List.of(box16(6.5, 0, 6.5, 9.5, 16, 9.5))));
        Kind rod = kind("rod", List.of(box16(6, 0, 6, 10, 16, 10)));
        KINDS.put("end_rod[facing=up]", rod);
        KINDS.put("lightning_rod[facing=up]", rod);
        KINDS.put("white_carpet", kind("carpet", List.of(box16(0, 0, 0, 16, 1, 16))));
        for (int n = 1; n <= 8; n++)
            KINDS.put("snow[layers=" + n + "]", n == 1 ? kind("snow1", List.of()) : kind("snow" + n, List.of(box16(0, 0, 0, 16, 2 * (n - 1), 16))));
        KINDS.put("skeleton_skull", kind("skull", List.of(box16(4, 0, 4, 12, 8, 12))));
        KINDS.put("cobblestone_wall", kind("wall", List.of(box16(4, 0, 4, 12, 24, 12))));
        KINDS.put("flower_pot", kind("pot", List.of(box16(5, 0, 5, 11, 6, 11))));
        KINDS.put("lantern[hanging=false]", kind("lantern", List.of(box16(5, 0, 5, 11, 7, 11), box16(6, 7, 6, 10, 9, 10))));
        KINDS.put("daylight_detector", kind("detector", List.of(box16(0, 0, 0, 16, 6, 16))));
        KINDS.put("enchanting_table", kind("table", List.of(box16(0, 0, 0, 16, 12, 16))));
        KINDS.put("end_portal_frame", kind("frame", List.of(box16(0, 0, 0, 16, 13, 16))));
        KINDS.put("chest", kind("chest", List.of(box16(1, 0, 1, 15, 14, 15))));
        KINDS.put("dragon_egg", kind("egg", List.of(box16(1, 0, 1, 15, 16, 15))));
        KINDS.put("brewing_stand", kind("brewing", List.of(box16(0, 0, 0, 16, 2, 16), box16(7, 0, 7, 9, 14, 9))));
        // trapdoors: closed on the bottom or the top of the cell; open against the side it faces away from
        KINDS.put("oak_trapdoor[half=bottom,open=false]", kind("trap_bottom", List.of(box16(0, 0, 0, 16, 3, 16))));
        KINDS.put("oak_trapdoor[half=top,open=false]", kind("trap_top", List.of(box16(0, 13, 0, 16, 16, 16))));
        KINDS.put("oak_trapdoor[open=true,facing=north]", kind("trap_n", List.of(box16(0, 0, 13, 16, 16, 16))));
        KINDS.put("oak_trapdoor[open=true,facing=south]", kind("trap_s", List.of(box16(0, 0, 0, 16, 16, 3))));
        KINDS.put("oak_trapdoor[open=true,facing=west]", kind("trap_w", List.of(box16(13, 0, 0, 16, 16, 16))));
        KINDS.put("oak_trapdoor[open=true,facing=east]", kind("trap_e", List.of(box16(0, 0, 0, 3, 16, 16))));
        // a ladder faces away from its wall: facing east, its box on the cell's west side
        KINDS.put("ladder[facing=east]", TestWorld.LADDER_WEST);
        KINDS.put("ladder[facing=west]", TestWorld.LADDER_EAST);
        KINDS.put("ladder[facing=south]", Palette.add("station_ladder_s", List.of(box16(0, 0, 0, 16, 16, 3)), 0.6F, 1.0F, Kind.CLIMB));
        KINDS.put("ladder[facing=north]", Palette.add("station_ladder_n", List.of(box16(0, 0, 13, 16, 16, 16)), 0.6F, 1.0F, Kind.CLIMB));
        Kind vine = Palette.add("station_vine", List.of(), 0.6F, 1.0F, Kind.CLIMB);
        KINDS.put("vine", vine);
        KINDS.put("twisting_vines", vine);
        KINDS.put("twisting_vines_plant", vine);
        KINDS.put("water", TestWorld.WATER);
    }

    /** A catalogue block's kind in the copy, or null. */
    static Kind kindOf(String block) {
        String b = block.replace("minecraft:", "");
        String id = b.split("\\[")[0];
        if (KINDS.containsKey(b)) return KINDS.get(b);
        if (KINDS.containsKey(id) && !id.equals("stone_slab") && !id.equals("ladder") && !id.equals("oak_trapdoor")) return KINDS.get(id);
        java.util.Map<String, String> props = new java.util.HashMap<>();
        if (b.contains("[")) for (String kv : b.substring(b.indexOf('[') + 1, b.length() - 1).split(",")) {
            String[] p = kv.split("=");
            props.put(p[0], p[1]);
        }
        switch (id) {
            case "stone_slab": return KINDS.get("stone_slab[type=" + props.getOrDefault("type", "bottom") + "]");
            case "ladder": return KINDS.get("ladder[facing=" + props.getOrDefault("facing", "north") + "]");
            case "oak_trapdoor":
                if ("true".equals(props.get("open"))) return KINDS.get("oak_trapdoor[open=true,facing=" + props.getOrDefault("facing", "north") + "]");
                return KINDS.get("oak_trapdoor[half=" + props.getOrDefault("half", "bottom") + ",open=false]");
            case "iron_chain": return "y".equals(props.getOrDefault("axis", "y")) ? KINDS.get("iron_chain[axis=y]") : null;
            case "end_rod": case "lightning_rod": return "up".equals(props.getOrDefault("facing", "up")) ? KINDS.get("end_rod[facing=up]") : null;
            case "lantern": return "false".equals(props.getOrDefault("hanging", "false")) ? KINDS.get("lantern[hanging=false]") : null;
            case "snow": return KINDS.get("snow[layers=" + props.getOrDefault("layers", "1") + "]");
            default: return null;
        }
    }

    /** The pool under a station, as in the game's test (ParkourTest: stone 15 under the take-off, water the two above
     *  it, from 38 behind to 22 past the take-off and 8 to 12 aside); KINE_POOL=0 leaves it out (the void). */
    static final boolean POOL = !"0".equals(System.getenv("KINE_POOL"));

    static TestWorld build(JsonObject L) {
        TestWorld w = TestWorld.blocks(-48, -16, 31, 31);
        if (POOL) {
            w.fill(-38, FLOOR - 16, -8, 22, FLOOR - 16, 12, TestWorld.STONE);
            w.fill(-38, FLOOR - 15, -8, 22, FLOOR - 14, 12, TestWorld.WATER);
        }
        for (JsonElement el : L.getAsJsonArray("blocks")) {
            JsonObject b = el.getAsJsonObject();
            w.set(b.get("dx").getAsInt(), FLOOR - 1 + b.get("dy").getAsInt(), b.get("dz").getAsInt(), kindOf(b.get("block").getAsString()));
        }
        return w;
    }

    /** The feet's height standing on the landing: the top of its block's collision (a full block's at dy + 1). */
    static double goalFeet(JsonObject L) {
        JsonObject la = L.getAsJsonObject("landing");
        int dx = la.get("dx").getAsInt(), dy = la.get("dy").getAsInt(), dz = la.get("dz").getAsInt();
        double top = 1.0;
        for (JsonElement el : L.getAsJsonArray("blocks")) {
            JsonObject b = el.getAsJsonObject();
            if (b.get("dx").getAsInt() != dx || b.get("dy").getAsInt() != dy || b.get("dz").getAsInt() != dz) continue;
            Kind k = kindOf(b.get("block").getAsString());
            if (k == null || k.boxes.length == 0) continue;
            top = 0;
            for (double[] bx : k.boxes) top = Math.max(top, bx[4]);
        }
        return FLOOR - 1 + dy + top;
    }

    /** Every block plain stone or a ladder (the first measured set). */
    static boolean plain(JsonObject L) {
        for (JsonElement el : L.getAsJsonArray("blocks")) {
            String blk = el.getAsJsonObject().get("block").getAsString();
            if (!(blk.equals("minecraft:stone") || blk.equals("minecraft:gold_block")
                  || blk.startsWith("minecraft:ladder[facing=east") || blk.startsWith("minecraft:ladder[facing=west"))) return false;
        }
        return true;
    }

    /** Every block one the copy builds (kindOf). */
    static boolean buildable(JsonObject L) {
        for (JsonElement el : L.getAsJsonArray("blocks"))
            if (kindOf(el.getAsJsonObject().get("block").getAsString()) == null) return false;
        return true;
    }

    static double pct(List<Double> v, double q) {
        if (v.isEmpty()) return 0;
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        return c.get(Math.min(c.size() - 1, (int) Math.floor(q * c.size())));
    }

    /**
     * The replan time with the game's effort and budget (20 ms), layer 2 alone with free moves (KINE_MEASURE set):
     * the stations walked twice (the first pass warms the JIT, the second is measured); percentiles of the replan's
     * time, apart for replans with a jump ahead (free plans scored) and without, and the free plans' part.
     */
    @Test
    void replanTimes() throws Exception {
        if (System.getenv("KINE_MEASURE") == null) return;
        String cat = System.getenv().getOrDefault("KINE_CATALOGUE", "/home/raymond/_/kine_path_2026-10-12/parkour_research/catalogue.json");
        List<JsonObject> layouts = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray()) {
            JsonObject en = e.getAsJsonObject();
            if (!en.has("layout") || en.get("layout").isJsonNull()) continue;
            JsonObject L = en.getAsJsonObject("layout");
            if (!L.has("blocks") || L.getAsJsonArray("blocks").isEmpty() || !buildable(L)) continue;
            layouts.add(L);
        }
        for (String mode : new String[]{"alone"}) {
            List<Double> withJump = new ArrayList<>(), without = new ArrayList<>(), freePart = new ArrayList<>(), rollouts = new ArrayList<>();
            int arrived = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (JsonObject L : layouts) {
                    JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
                    double sx = st.get("dx").getAsInt() + 0.5, sz = st.get("dz").getAsInt() + 0.5;
                    int sy = FLOOR + st.get("dy").getAsInt();
                    int lx = la.get("dx").getAsInt(), lz = la.get("dz").getAsInt();
                    Layer2Sim sim = new Layer2Sim(build(L));
                    sim.parkour = true;
                    sim.direct = true;
                    sim.forceDirect = mode.equals("alone");
                    sim.freeJumps = true;
                    sim.recipes = true;
                    sim.effort.budgetNanos = 20_000_000L;   // the game's
                    double goalFeet = goalFeet(L);
                    float yaw = (float) Math.toDegrees(Math.atan2(-(lx + 0.5 - sx), lz + 0.5 - sz));
                    sim.goalY = goalFeet(L);   // the landing's floor, not the pool under it
                    Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(sx, sy, sz), yaw, lx, lz, 300);
                    if (pass == 0) continue;
                    boolean ok = o.arrived();
                    if (ok && !o.path().isEmpty()) {
                        double[] q = o.path().get(o.path().size() - 1);
                        ok = Math.abs(q[1] - goalFeet) < 0.6 || (((int) q[3]) & 4) == 0;
                    }
                    if (ok) arrived++;
                    for (double[] t : sim.replanTimes) {
                        if (t[2] > 0) { withJump.add(t[0]); freePart.add(t[1]); rollouts.add(t[3]); } else without.add(t[0]);
                    }
                }
            }
            System.out.printf(Locale.ROOT, "replan times, %s (budget 20 ms): arrived %d of %d; with a jump ahead n=%d p50 %.1f p90 %.1f p99 %.1f max %.1f ms "
                    + "(free part p50 %.1f p90 %.1f max %.1f, free plans scored p50 %.0f max %.0f); without n=%d p50 %.1f p90 %.1f p99 %.1f max %.1f ms%n",
                mode, arrived, layouts.size(), withJump.size(), pct(withJump, .5), pct(withJump, .9), pct(withJump, .99), pct(withJump, 1),
                pct(freePart, .5), pct(freePart, .9), pct(freePart, 1), pct(rollouts, .5), pct(rollouts, 1),
                without.size(), pct(without, .5), pct(without, .9), pct(without, .99), pct(without, 1));
        }
    }

    /** One station walked in the copy, its track printed (KINE_STATION = the catalogue id; layer 2 alone, free moves
     *  and recipes on): for looking into one. */
    @Test
    void oneStation() throws Exception {
        String id = System.getenv("KINE_STATION");
        if (id == null) return;
        String cat = "/home/raymond/_/kine_path_2026-10-12/parkour_research/catalogue.json";
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray()) {
            JsonObject en = e.getAsJsonObject();
            if (!en.get("id").getAsString().equals(id)) continue;
            JsonObject L = en.getAsJsonObject("layout");
            JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
            double sx = st.get("dx").getAsInt() + 0.5, sz = st.get("dz").getAsInt() + 0.5;
            int sy = FLOOR + st.get("dy").getAsInt();
            int lx = la.get("dx").getAsInt(), lz = la.get("dz").getAsInt();
            Layer2Sim sim = new Layer2Sim(build(L));
            sim.parkour = true; sim.direct = true; sim.forceDirect = true;
            sim.freeJumps = !"0".equals(System.getenv("KINE_FREE"));
            sim.recipes = !"0".equals(System.getenv("KINE_RECIPES"));
            sim.recipeDelay = Integer.parseInt(System.getenv().getOrDefault("KINE_RECIPE_DELAY", "0"));
            float yaw = (float) Math.toDegrees(Math.atan2(-(lx + 0.5 - sx), lz + 0.5 - sz));
            sim.goalY = goalFeet(L);   // the landing's floor, not the pool under it
            Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(sx, sy, sz), yaw, lx, lz, 300);
            System.out.printf(Locale.ROOT, "station %s: arrived %b ticks %d jumps %d recipes %d freeSeeds %d why %s%n", id, o.arrived(), o.ticks(),
                o.jumps(), sim.recipesFound, sim.freeSeeds, o.why());
            List<double[]> path = o.path();
            for (int t = 0; t < path.size(); t += (t < 60 ? 1 : 20)) {
                double[] q = path.get(t);
                System.out.printf(Locale.ROOT, "  t %3d  %.3f %.3f %.3f ground %d keys %d%n", t, q[0], q[1], q[2], ((int) q[3] >> 2) & 1, (int) q[4]);
            }
        }
    }

    /** Whether a scripted jump (Recipe: one heading, a strafe or none, the jump on any tick to 24, a turn toward the
     *  landing) makes each station named in KINE_RECIPE_STATIONS from its start in the copy: for telling a jump the
     *  search misses from one outside that family. */
    @Test
    void recipeFromStart() throws Exception {
        String ids = System.getenv("KINE_RECIPE_STATIONS");
        if (ids == null) return;
        String cat = "/home/raymond/_/kine_path_2026-10-12/parkour_research/catalogue.json";
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray()) {
            JsonObject en = e.getAsJsonObject();
            if (!java.util.Arrays.asList(ids.split(",")).contains(en.get("id").getAsString())) continue;
            JsonObject L = en.getAsJsonObject("layout");
            JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
            double sx = st.get("dx").getAsInt() + 0.5, sz = st.get("dz").getAsInt() + 0.5;
            int sy = FLOOR + st.get("dy").getAsInt();
            int lx = la.get("dx").getAsInt(), ly = FLOOR - 1 + la.get("dy").getAsInt(), lz = la.get("dz").getAsInt();
            Layer2Sim sim = new Layer2Sim(build(L));
            float bearing = Recipe.yawTo(sx, sz, lx + 0.5, lz + 0.5);
            long t0 = System.nanoTime();
            Recipe r = Recipe.search(sim.phys, Layer2Sim.standing(sx, sy, sz), bearing, lx, ly, lz, bearing - 60f, bearing + 60f, 2f, 24, 12, 60);
            System.out.printf(Locale.ROOT, "recipe %s: found %b ways %d of %d how %s (%.0f ms)%n", en.get("id").getAsString(), r.found(), r.ways, r.tried,
                r.found() ? r.how : "", (System.nanoTime() - t0) / 1e6);
        }
    }

    /** The columns a copy-found jump crosses (KINE_SWEEP_STATIONS): from its start, the recipe's path, each tick's
     *  box (0.6 wide) as the columns under it, in the air and on the ground before the take-off, relative to the
     *  take-off block: what a layer 1 move for that jump would have to find free (for the porting decision). */
    @Test
    void sweptColumns() throws Exception {
        String ids = System.getenv("KINE_SWEEP_STATIONS");
        if (ids == null) return;
        String cat = "/home/raymond/_/kine_path_2026-10-12/parkour_research/catalogue.json";
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray()) {
            JsonObject en = e.getAsJsonObject();
            String id = en.get("id").getAsString();
            if (!ids.equals("all") && !java.util.Arrays.asList(ids.split(",")).contains(id)) continue;
            JsonObject L = en.getAsJsonObject("layout");
            if (L == null || !L.has("blocks") || !buildable(L)) continue;
            JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
            double sx = st.get("dx").getAsInt() + 0.5, sz = st.get("dz").getAsInt() + 0.5;
            int sy = FLOOR + st.get("dy").getAsInt();
            int lx = la.get("dx").getAsInt(), ly = FLOOR - 1 + la.get("dy").getAsInt(), lz = la.get("dz").getAsInt();
            Layer2Sim sim = new Layer2Sim(build(L));
            float bearing = Recipe.yawTo(sx, sz, lx + 0.5, lz + 0.5);
            Recipe r = Recipe.search(sim.phys, Layer2Sim.standing(sx, sy, sz), bearing, lx, ly, lz, bearing - 90f, bearing + 90f, 6f, 8, 8, 60);
            if (!r.found()) { System.out.println("sweep " + id + ": no recipe"); continue; }
            java.util.TreeSet<String> air = new java.util.TreeSet<>(), ground = new java.util.TreeSet<>();
            boolean left = false;
            for (int t = 0; t < r.path.length / 3; t++) {
                double x = r.path[3 * t], y = r.path[3 * t + 1], z = r.path[3 * t + 2];
                boolean up = Math.abs(y - sy) > 1e-3 || left;
                if (Math.abs(y - sy) > 1e-3) left = true;
                for (int cx = (int) Math.floor(x - 0.3); cx <= (int) Math.floor(x + 0.3 - 1e-9); cx++)
                    for (int cz = (int) Math.floor(z - 0.3); cz <= (int) Math.floor(z + 0.3 - 1e-9); cz++)
                        (up ? air : ground).add(cx + "," + cz);
            }
            System.out.printf(Locale.ROOT, "sweep %s: landing %d,%d,%d how %s ticks %d; ground %s; air %s%n", id, lx, la.get("dy").getAsInt(), lz,
                r.how, r.ticks, ground, air);
        }
    }

    @Test
    void stationsInTheCopy() throws Exception {
        if (System.getenv("KINE_MEASURE") == null) return;
        String cat = System.getenv().getOrDefault("KINE_CATALOGUE", "/home/raymond/_/kine_path_2026-10-12/parkour_research/catalogue.json");
        String[] modes = {"route", "alone", "alone+free", "alone+free+recipe"};
        int[] arrived = new int[modes.length];
        double[] ms = new double[modes.length], worst = new double[modes.length];
        int n = 0, nPlain = 0, routeDirect = 0;
        int[] arrivedPlain = new int[modes.length];
        StringBuilder csv = new StringBuilder("id,status,plain");
        for (String m : modes) csv.append(',').append(m);
        csv.append('\n');
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray()) {
            JsonObject en = e.getAsJsonObject();
            if (!en.has("layout") || en.get("layout").isJsonNull()) continue;
            JsonObject L = en.getAsJsonObject("layout");
            if (!L.has("blocks") || L.getAsJsonArray("blocks").isEmpty() || !buildable(L)) continue;
            boolean isPlain = plain(L);
            n++;
            if (isPlain) nPlain++;
            JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
            double sx = st.get("dx").getAsInt() + 0.5, sz = st.get("dz").getAsInt() + 0.5;
            int sy = FLOOR + st.get("dy").getAsInt();
            int lx = la.get("dx").getAsInt(), lz = la.get("dz").getAsInt();
            csv.append(en.get("id").getAsString()).append(',').append(en.has("status") ? en.get("status").getAsString() : "")
                .append(',').append(isPlain ? "plain" : "shaped");
            for (int m = 0; m < modes.length; m++) {
                Layer2Sim sim = new Layer2Sim(build(L));
                sim.parkour = true;
                sim.direct = true;
                sim.forceDirect = m > 0;
                sim.freeJumps = m == 0 || m >= 2;
                sim.recipes = m == 3;
                double goalFeet = goalFeet(L);
                float yaw = (float) Math.toDegrees(Math.atan2(-(lx + 0.5 - sx), lz + 0.5 - sz));
                sim.goalY = goalFeet(L);   // the landing's floor, not the pool under it
                Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(sx, sy, sz), yaw, lx, lz, 300);
                if (m == 0 && sim.wentDirect) routeDirect++;
                boolean ok = o.arrived();
                if (ok && !o.path().isEmpty()) {
                    double[] q = o.path().get(o.path().size() - 1);
                    ok = Math.abs(q[1] - goalFeet) < 0.6 || (((int) q[3]) & 4) == 0;   // on the landing's floor (or on its ladder)
                }
                if (ok) { arrived[m]++; if (isPlain) arrivedPlain[m]++; }
                ms[m] += o.meanMs();
                worst[m] = Math.max(worst[m], o.worstMs());
                csv.append(',').append(ok ? "yes" : "no");
            }
            csv.append('\n');
        }
        StringBuilder sum = new StringBuilder();
        for (int m = 0; m < modes.length; m++)
            sum.append(String.format(Locale.ROOT, " %s: %d of %d, plain %d of %d (replan mean %.1f ms, worst %.1f ms);", modes[m], arrived[m], n,
                arrivedPlain[m], nPlain, ms[m] / Math.max(1, n), worst[m]));
        System.out.println("stations in the copy:" + sum + " (route mode: no route from layer 1 on " + routeDirect + ")");
        Path out = Path.of(System.getenv("KINE_MEASURE"), "stations_copy.csv");
        Files.createDirectories(out.getParent());
        Files.writeString(out, csv);
    }
}
