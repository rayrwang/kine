package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.Shape;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The neo templates, searched once offline in the movement copy (RW 2026-10-14 22:42:38 EDT: "right maybe just do the
 * layer 2 tmeparlet fo the neo first, the layer 1 can wita since falls back to layer 2 alywyewany"). For each neo
 * layout of the catalogue the copy builds, ladder_neo, ladder_fence_neo and fence_column_neo, and the course's other
 * layouts none of the walk's searches gets over (COURSE_EXTRA; RW 2026-10-15: "impelnet everythign liekhte tirpel neo
 * and other tmeplates"): input sequences (each tick's yaw and keys) from standing still on a spot of the start block to
 * standing on the landing (or held on it, for a ladder); each one's robustness, the share of the small variations the
 * walk's in-play search tries (a constant yaw offset, the jump a tick early or late, the start a tick late) that land
 * from start states up to 0.08 off the spot; the most robust few per layout written to a JSON resource
 * (neo_templates.json) the walk loads.
 *
 * <p>The search: a beam over every tick's input (StationDifficultyTest's: 9 key sets, sprinting with forward, jump on
 * the ground or a climbable, turns of 0, 5, 15 and 29.5 degrees either way a tick, kine's limit), from each standing
 * spot (the centre, the back edge, the sides, the corners). The generic beam keeps the states nearest the landing's
 * centre in a straight line (less 3 x the speed); where it finds nothing on a layout (or nothing that lands from half
 * the start states), the wall-aware beams keep the states nearest along the shortest way round the walls
 * (TripleNeoSearchTest's way round the wall's end, made general: the distance on a grid round every block the player
 * cannot jump over from the start, widened by the box's half width; round either end, and the shorter way; no cost for
 * the height of a jump); where none finds anything, both again with finer turns (the same limit), wider and longer. A
 * landing whose fall hurts is not one.
 *
 * <p>The templates are given from the key block: the layout's take-off (its 0, 0, 0, the runway's last block, where the
 * walk stands when it is stuck) where it has a block to stand on, else its start block. The spot may lie back along the
 * runway (the start block is the runway's far end).
 *
 * <p>The world: Layer2StationTest's blocks, but fences and panes joined to their neighbours as the game has them once
 * the station is set (setblock fits a block to its neighbours, and each later neighbour refits it), and fences marked
 * tall (BlockInfo.LARGE: the copy reads a fence's top half block from above only then) and as fences.
 *
 * <p>KINE_NEO_GEN set: the output file. KINE_NEO_IDS: only these ids. KINE_NEO_THREADS: the beams' threads (default and
 * most 4). KINE_NEO_WIDTH, KINE_NEO_WIDE_WIDTH: the beams' widths (3000, 6000). KINE_NEO_KEEP: candidates per beam
 * whose robustness is measured (24). A table of the results goes to the output's name with .txt, progress to .log.
 */
public class NeoTemplateGenTest {
    static final int FLOOR = Layer2StationTest.FLOOR;
    /** The turns a tick (kine's limit 29.5 degrees). KINE_NEO_TURNS replaces them, for comparisons only (the templates
     *  are made with these). */
    static final float[] TURNS = System.getenv("KINE_NEO_TURNS") == null ? new float[]{0f, 5f, -5f, 15f, -15f, 29.5f, -29.5f}
        : parseTurns(System.getenv("KINE_NEO_TURNS"));

    static float[] parseTurns(String s) {
        String[] p = s.split(",");
        float[] t = new float[p.length];
        for (int i = 0; i < p.length; i++) t[i] = Float.parseFloat(p[i].trim());
        return t;
    }
    /** The key sets a tick, the sprint key with forward (StationDifficultyTest's). */
    static final int[] KEYS = {MoveState.FORWARD | MoveState.SPRINT, MoveState.FORWARD | MoveState.LEFT | MoveState.SPRINT,
        MoveState.FORWARD | MoveState.RIGHT | MoveState.SPRINT, MoveState.BACKWARD, MoveState.BACKWARD | MoveState.LEFT,
        MoveState.BACKWARD | MoveState.RIGHT, MoveState.LEFT, MoveState.RIGHT, 0};
    /** And forward without the sprint key (a walk, or a jam whose sprint comes ticks later: the 1bm jumps' timings). */
    static final int[] KEYS_PLUS = {MoveState.FORWARD | MoveState.SPRINT, MoveState.FORWARD | MoveState.LEFT | MoveState.SPRINT,
        MoveState.FORWARD | MoveState.RIGHT | MoveState.SPRINT, MoveState.FORWARD, MoveState.FORWARD | MoveState.LEFT,
        MoveState.FORWARD | MoveState.RIGHT, MoveState.BACKWARD, MoveState.BACKWARD | MoveState.LEFT,
        MoveState.BACKWARD | MoveState.RIGHT, MoveState.LEFT, MoveState.RIGHT, 0};
    /** The extra layouts besides the neo category (RW's brief). */
    static final List<String> EXTRA = List.of("ladder_neo", "ladder_fence_neo", "fence_column_neo");
    /** And the course's other layouts no search of the walk's finds a way over in the copy (the course agent,
     *  2026-10-15, from RW's "impelnet everythign liekhte tirpel neo and other tmeplates"). */
    static final List<String> COURSE_EXTRA = List.of("gap_5b_hh_momentum", "gap_5_375b_max_tdhh", "gap_5b_elevation", "diag_4x2",
        "diag_4x3", "mm_45_strafe", "mm_1bm_4_375b_bwmm45", "up_4p0p4375_c45", "up_4p1", "gap_6b_flat", "down_5m2", "down_5m1",
        "down_6m3", "down_7p0625m5_record", "diag_slab_fence_3x3_double45", "hh_3bc_1bm", "trapdoor_jump", "trapdoor_ladder",
        "ladder_90_spiral", "drop_jump", "blind_jump",
        // part 2 (the course agent, 2026-10-15): the cobweb station, ladder_5b and up_4p0p5
        "cobweb", "ladder_5b", "up_4p0p5");
    /** The turns of the fine round (where none of the others finds a way): more of them, the same limit. */
    static final float[] TURNS_FINE = {0f, 2f, -2f, 5f, -5f, 10f, -10f, 15f, -15f, 22f, -22f, 29.5f, -29.5f};
    /** The game's safe fall (Costs.maxFall, the course branch's Recipe.SAFE_FALL): a landing past it hurts. */
    static final double SAFE_FALL = 3.0;

    /** A landing that hurts (Recipe.hurts): the step just taken landed from a fall past the safe one onto a block that
     *  doesn't take it (landedFall cleared before the step). */
    static boolean hurts(MoveState s) {
        return Recipe.hurts(s);
    }

    /** A try that met a block that hurts or movement the copy leaves out (a cobweb, ...): Recipe.spoils. */
    static boolean spoils(MoveState s) {
        return Recipe.spoils(s);
    }

    /** The variations the in-play search tries: a constant yaw offset, the jump tick moved, the start delayed. */
    static final float[] YAW_OFFSETS = {0f, 0.3f, -0.3f, 0.6f, -0.6f, 1f, -1f, 1.5f, -1.5f, 2.5f, -2.5f, 4f, -4f};
    static final int[] JUMP_SHIFTS = {0, -1, 1};
    static final int[] DELAYS = {0, 1};
    /** The start states: the spot and points up to 0.08 off it (the walk comes to rest within 0.08 of a spot). */
    static final double[][] OFFSETS;
    static {
        double d = 0.08 / Math.sqrt(2);
        OFFSETS = new double[][]{{0, 0}, {0.08, 0}, {-0.08, 0}, {0, 0.08}, {0, -0.08}, {d, d}, {d, -d}, {-d, d}, {-d, -d},
            {0.04, 0}, {-0.04, 0}, {0, 0.04}, {0, -0.04}};
    }
    /** Ticks a variation may run past its inputs, keys up, to come down on the landing. */
    static final int EXTRA_TICKS = 10;

    // ------------------------------------------------------------------ the world

    static String bare(String block) {
        String b = block.replace("minecraft:", "");
        int i = b.indexOf('[');
        return i < 0 ? b : b.substring(0, i);
    }

    static boolean isFence(String id) { return id.endsWith("_fence"); }

    static boolean isPane(String id) { return id.endsWith("glass_pane") || id.equals("iron_bars"); }

    static JsonObject blockAt(JsonObject L, int dx, int dy, int dz) {
        for (JsonElement el : L.getAsJsonArray("blocks")) {
            JsonObject b = el.getAsJsonObject();
            if (b.get("dx").getAsInt() == dx && b.get("dy").getAsInt() == dy && b.get("dz").getAsInt() == dz) return b;
        }
        return null;
    }

    private static final Map<String, Kind> JOINED = new HashMap<>();

    /** A fence or a pane as the game has it in the station: its post and an arm toward each side it joins (a fence to
     *  a fence or a full block's side, a pane to a pane, bars, a wall or a full block's side: FenceBlock.connectsTo,
     *  IronBarsBlock.attachsTo); collision 1.5 high for a fence (posts 6 to 10 sixteenths), 1 for a pane (7 to 9).
     *  Null for any other block. */
    static Kind joined(JsonObject L, JsonObject b) {
        String id = bare(b.get("block").getAsString());
        boolean fence = isFence(id), pane = isPane(id);
        if (!fence && !pane) return null;
        int x = b.get("dx").getAsInt(), y = b.get("dy").getAsInt(), z = b.get("dz").getAsInt();
        int[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};   // north, south, west, east
        String[] tag = {"n", "s", "w", "e"};
        boolean[] join = new boolean[4];
        StringBuilder name = new StringBuilder(fence ? "neo_fence_" : "neo_pane_");
        for (int d = 0; d < 4; d++) {
            JsonObject o = blockAt(L, x + dirs[d][0], y, z + dirs[d][1]);
            if (o == null) continue;
            String oid = bare(o.get("block").getAsString());
            Kind ok = Layer2StationTest.kindOf(o.get("block").getAsString());
            boolean sturdy = ok != null && ok.info.shape == Shape.BLOCK;
            join[d] = fence ? isFence(oid) || sturdy : isPane(oid) || oid.endsWith("_wall") || sturdy;
            if (join[d]) name.append(tag[d]);
        }
        double a = fence ? 6 : 7, c = fence ? 10 : 9, h = fence ? 24 : 16;
        List<double[]> boxes = new ArrayList<>();
        boxes.add(Layer2StationTest.box16(a, 0, a, c, h, c));
        if (join[0]) boxes.add(Layer2StationTest.box16(a, 0, 0, c, h, c));
        if (join[1]) boxes.add(Layer2StationTest.box16(a, 0, a, c, h, 16));
        if (join[2]) boxes.add(Layer2StationTest.box16(0, 0, a, c, h, c));
        if (join[3]) boxes.add(Layer2StationTest.box16(a, 0, a, 16, h, c));
        synchronized (JOINED) {
            return JOINED.computeIfAbsent(name.toString(), n -> Palette.add(n, boxes, 0.6F, 1.0F, 0));
        }
    }

    /** A cobweb: no collision, movement the copy leaves out (SPECIAL: a try that touches it is marked UNCOPIED and
     *  spoiled, as the game's copy marks it, McKinds). */
    static final Kind COBWEB = Palette.add("neo_cobweb", List.of(), 0.6F, 1.0F, Kind.AVOID);

    /** A block's kind without its neighbours: the copy's (Layer2StationTest.kindOf), and a cobweb. */
    static Kind kindOf(String block) {
        if (bare(block).equals("cobweb")) return COBWEB;
        return Layer2StationTest.kindOf(block);
    }

    static Map<String, String> props(String block) {
        Map<String, String> m = new HashMap<>();
        int i = block.indexOf('[');
        if (i >= 0) for (String kv : block.substring(i + 1, block.length() - 1).split(",")) {
            String[] p = kv.split("=");
            if (p.length == 2) m.put(p[0], p[1]);
        }
        return m;
    }

    private static final Map<Kind, Kind> CLIMBABLE_OF = new HashMap<>();

    /** The block's kind in the generator's world: joined if a fence or a pane; an open trapdoor over a ladder facing the
     *  same way climbable (LivingEntity.onClimbable's trapdoorUsableAsLadder, as WorldSnapshot has it since 5784759);
     *  else kindOf. */
    static Kind kindIn(JsonObject L, JsonObject b) {
        Kind k = joined(L, b);
        if (k != null) return k;
        String block = b.get("block").getAsString();
        k = kindOf(block);
        Map<String, String> pr = props(block);
        if (k != null && bare(block).endsWith("_trapdoor") && "true".equals(pr.get("open"))) {
            JsonObject below = blockAt(L, b.get("dx").getAsInt(), b.get("dy").getAsInt() - 1, b.get("dz").getAsInt());
            if (below != null && bare(below.get("block").getAsString()).equals("ladder")
                && pr.getOrDefault("facing", "north").equals(props(below.get("block").getAsString()).getOrDefault("facing", "north"))) {
                Kind base = k;
                synchronized (CLIMBABLE_OF) {
                    k = CLIMBABLE_OF.computeIfAbsent(base, kk -> Palette.add(kk.name + "_climb", java.util.Arrays.asList(kk.boxes), 0.6F, 1.0F, kk.flags | Kind.CLIMB));
                }
            }
        }
        return k;
    }

    static boolean buildable(JsonObject L) {
        if (L == null || !L.has("blocks") || L.getAsJsonArray("blocks").isEmpty()) return false;
        for (JsonElement el : L.getAsJsonArray("blocks"))
            if (kindOf(el.getAsJsonObject().get("block").getAsString()) == null) return false;
        return true;
    }

    /** The station as Layer2StationTest builds it (the pool under it), with fences and panes joined. */
    static TestWorld build(JsonObject L) {
        TestWorld w = TestWorld.blocks(-48, -16, 31, 31);
        if (Layer2StationTest.POOL) {
            w.fill(-38, FLOOR - 16, -8, 22, FLOOR - 16, 12, TestWorld.STONE);
            w.fill(-38, FLOOR - 15, -8, 22, FLOOR - 14, 12, TestWorld.WATER);
        }
        for (JsonElement el : L.getAsJsonArray("blocks")) {
            JsonObject b = el.getAsJsonObject();
            w.set(b.get("dx").getAsInt(), FLOOR - 1 + b.get("dy").getAsInt(), b.get("dz").getAsInt(), kindIn(L, b));
        }
        return w;
    }

    // ------------------------------------------------------------------ one layout

    record Spot(String name, double x, double z, MoveState s) {}

    static final class Layout {
        final String id;
        final JsonObject L;
        final int sdx, sdy, sdz, ldx, ldy, ldz;
        /** The key block the template is given from: the take-off (the layout's 0, 0, 0, the runway's last block, where
         *  the walk stands when it is stuck) where the layout has a block to stand on there, else the start block. Its
         *  bottom and the feet standing on it. */
        final int kdx, kdy, kdz;
        final double kBase, kFeet;
        /** The landing block (world). */
        final int lx, ly, lz;
        /** The start block's bottom, the feet standing on it, the feet on the landing, the lowest the feet may go. */
        final double base, startY, landY, lowY;
        final boolean climb;
        final BlockGrid grid;
        final Body body = new Body();
        final float wall;
        final int ticks;
        final List<Spot> spots = new ArrayList<>();
        /** The ways round the walls: either side (0: the shorter), made on first use; null where no wall crosses the
         *  straight line (a side then means nothing). */
        final Geo[] geos = new Geo[3];
        final boolean[] geoMade = new boolean[3];

        synchronized Geo geo(int side) {
            if (!geoMade[side + 1]) {
                Geo g = new Geo(this, side);
                geos[side + 1] = g.ok ? g : null;
                geoMade[side + 1] = true;
            }
            return geos[side + 1];
        }
        final List<Cand> cands = Collections.synchronizedList(new ArrayList<>());
        final List<String> beams = Collections.synchronizedList(new ArrayList<>());
        long landed;
        double ms;
        final Set<String> found = Collections.synchronizedSet(new java.util.TreeSet<>());

        Layout(String id, JsonObject L) {
            this.id = id;
            this.L = L;
            JsonObject st = L.getAsJsonObject("start"), la = L.getAsJsonObject("landing");
            sdx = st.get("dx").getAsInt(); sdy = st.get("dy").getAsInt(); sdz = st.get("dz").getAsInt();
            ldx = la.get("dx").getAsInt(); ldy = la.get("dy").getAsInt(); ldz = la.get("dz").getAsInt();
            lx = ldx; ly = FLOOR - 1 + ldy; lz = ldz;
            base = FLOOR - 1 + sdy;
            startY = base + StationDifficultyTest.collisionTop(L, sdx, sdy, sdz);
            // the take-off where nothing with collision lies on it (up_4p0p4375_c45's carpet is the floor stood on there)
            JsonObject above = blockAt(L, 0, 1, 0);
            Kind ak = above == null ? null : kindOf(above.get("block").getAsString());
            boolean takeoff = blockAt(L, 0, 0, 0) != null && StationDifficultyTest.collisionTop(L, 0, 0, 0) > 0
                && (ak == null || ak.boxes.length == 0);
            kdx = takeoff ? 0 : sdx; kdy = takeoff ? 0 : sdy; kdz = takeoff ? 0 : sdz;
            kBase = FLOOR - 1 + kdy;
            kFeet = kBase + StationDifficultyTest.collisionTop(L, kdx, kdy, kdz);
            landY = Layer2StationTest.goalFeet(L);
            lowY = Math.min(startY, landY) - 1.3;
            JsonObject lb = blockAt(L, ldx, ldy, ldz);
            Kind lk = lb == null ? null : kindIn(L, lb);
            climb = lk != null && lk.is(Kind.CLIMB);
            grid = Layer2Sim.grid(build(L));
            // fences and walls: tall (the copy looks at them from the ring outside the box) and fences (getOnPos)
            for (JsonElement el : L.getAsJsonArray("blocks")) {
                JsonObject b = el.getAsJsonObject();
                String bid = bare(b.get("block").getAsString());
                int f = isFence(bid) ? BlockInfo.FENCE | BlockInfo.LARGE : bid.endsWith("_wall") ? BlockInfo.WALL | BlockInfo.LARGE : 0;
                if (f == 0) continue;
                int x = b.get("dx").getAsInt(), y = FLOOR - 1 + b.get("dy").getAsInt(), z = b.get("dz").getAsInt();
                BlockInfo i = grid.get(x, y, z);
                grid.set(x, y, z, new BlockInfo(i.shape, i.friction, i.speedFactor, i.jumpFactor, i.flags | f, i.fluid, i.fluidHeight, i.fallFactor));
            }
            wall = climb ? Recipe.wallYaw(new GroundPhysics(grid, body), lx, ly, lz) : Float.NaN;
            double dist = Math.hypot(lx - sdx, lz - sdz);
            ticks = Math.max(40, (int) Math.ceil(dist / 0.25) + 20);
        }

        GroundPhysics phys() { return new GroundPhysics(grid, body); }

        boolean over(MoveState s) {
            return s.x + 0.3 > lx && s.x - 0.3 < lx + 1 && s.z + 0.3 > lz && s.z - 0.3 < lz + 1;
        }

        boolean onLanding(MoveState s) {
            return over(s) && s.onGround && Math.abs(s.y - landY) < 0.05 && !hurts(s);
        }

        double margin(MoveState s) {
            return Math.min(Math.min(s.x - lx, lx + 1 - s.x), Math.min(s.z - lz, lz + 1 - s.z));
        }
    }

    /** Standing still at (x, y, z): the box free of blocks, and two ticks with the keys up leave it where it is, on the
     *  ground. The state after those ticks, or null. */
    static MoveState settle(GroundPhysics phys, double x, double y, double z) {
        float w = phys.body().width / 2;
        BlockGrid.Colliders c = new BlockGrid.Colliders();
        phys.grid().collect(new double[]{x - w + 1e-7, y + 1e-7, z - w + 1e-7, x + w - 1e-7, y + phys.body().height - 1e-7, z + w - 1e-7}, c);
        if (c.n != 0) return null;
        MoveState s = Layer2Sim.standing(x, y, z);
        for (int i = 0; i < 2; i++) phys.step(s, 0, 0f, 0f);
        if (!s.onGround || Math.abs(s.x - x) > 1e-6 || Math.abs(s.z - z) > 1e-6 || Math.abs(s.y - y) > 1e-6) return null;
        return s;
    }

    static final String[] SPOT_NAMES = {"centre", "back", "back-left", "back-right", "left", "right", "front-left", "front-right"};
    /** Each spot along the landing's bearing (u) and to its left (v), from the block's centre. */
    static final double[][] SPOT_REL = {{0, 0}, {-0.5, 0}, {-0.5, 0.4}, {-0.5, -0.4}, {0, 0.4}, {0, -0.4}, {0.4, 0.4}, {0.4, -0.4}};

    /** The standing spots on the start block: each named point, or the standing point nearest it (a 0.02 grid over the
     *  block and the 0.3 the box may hang over), none two within 0.1. */
    static void findSpots(Layout lay) {
        GroundPhysics phys = lay.phys();
        double cx = lay.sdx + 0.5, cz = lay.sdz + 0.5;
        double ux = lay.lx + 0.5 - cx, uz = lay.lz + 0.5 - cz, len = Math.hypot(ux, uz);
        ux /= len; uz /= len;
        double vx = uz, vz = -ux;   // the left hand, facing the landing (the game's: facing +x, left is -z)
        List<double[]> stand = new ArrayList<>();
        for (int i = 0; i <= 80; i++)
            for (int j = 0; j <= 80; j++) {
                double x = lay.sdx - 0.3 + i * 0.02, z = lay.sdz - 0.3 + j * 0.02;
                if (settle(phys, x, lay.startY, z) != null) stand.add(new double[]{x, z});
            }
        for (int k = 0; k < SPOT_NAMES.length; k++) {
            double tx = cx + SPOT_REL[k][0] * ux + SPOT_REL[k][1] * vx, tz = cz + SPOT_REL[k][0] * uz + SPOT_REL[k][1] * vz;
            tx = Math.max(lay.sdx, Math.min(lay.sdx + 1, tx));
            tz = Math.max(lay.sdz, Math.min(lay.sdz + 1, tz));
            tx = Math.round(tx * 100) / 100.0;
            tz = Math.round(tz * 100) / 100.0;
            MoveState s = settle(phys, tx, lay.startY, tz);
            if (s == null) {
                double best = Double.MAX_VALUE;
                double[] near = null;
                for (double[] p : stand) {
                    double d = Math.hypot(p[0] - tx, p[1] - tz);
                    if (d < best) { best = d; near = p; }
                }
                if (near == null) continue;
                tx = Math.round(near[0] * 100) / 100.0;
                tz = Math.round(near[1] * 100) / 100.0;
                s = settle(phys, tx, lay.startY, tz);
                if (s == null) continue;
            }
            boolean dup = false;
            for (Spot o : lay.spots) if (Math.hypot(o.x() - tx, o.z() - tz) < 0.1) { dup = true; break; }
            if (!dup) lay.spots.add(new Spot(SPOT_NAMES[k], tx, tz, s));
        }
        // a runway (the start block its far end): spots nearer the take-off too, on it and halfway along, at its height
        if ((lay.kdx != lay.sdx || lay.kdz != lay.sdz) && Math.abs(lay.kFeet - lay.startY) < 1e-6) {
            double kx = lay.kdx + 0.5, kz = lay.kdz + 0.5;
            double[][] more = {{kx, kz}, {kx - 0.5 * ux, kz - 0.5 * uz}, {Math.floor((cx + kx) / 2) + 0.5, Math.floor((cz + kz) / 2) + 0.5}};
            String[] names = {"takeoff", "takeoff-back", "runway-middle"};
            for (int k = 0; k < more.length; k++) {
                double tx = Math.round(more[k][0] * 100) / 100.0, tz = Math.round(more[k][1] * 100) / 100.0;
                MoveState s = settle(phys, tx, lay.startY, tz);
                if (s == null) continue;
                boolean dup = false;
                for (Spot o : lay.spots) if (Math.hypot(o.x() - tx, o.z() - tz) < 0.1) { dup = true; break; }
                if (!dup) lay.spots.add(new Spot(names[k], tx, tz, s));
            }
        }
    }

    // ------------------------------------------------------------------ the way round the walls

    /** The shortest way on a grid (0.05) from each point to the landing's centre, round every block the player cannot
     *  jump over from the start (its collision above the start's feet + 1.25 and below the head, standing), widened by
     *  the box's half width; the landing's own column left open. With a side (+1 left of the straight line from the
     *  start's centre to the landing's, facing the landing; -1 right), the other side is closed alongside the walls
     *  that cross that line (TripleNeoSearchTest's way round one end of the wall, made general). */
    static final class Geo {
        static final double R = 0.05;
        final double x0, z0;
        final int nx, nz;
        final float[] d;
        final int side;
        boolean ok = true;

        Geo(Layout lay, int side) {
            this.side = side;
            int minX = Math.min(lay.sdx, lay.lx), maxX = Math.max(lay.sdx, lay.lx), minZ = Math.min(lay.sdz, lay.lz), maxZ = Math.max(lay.sdz, lay.lz);
            List<double[]> rects = new ArrayList<>();
            for (JsonElement el : lay.L.getAsJsonArray("blocks")) {
                JsonObject b = el.getAsJsonObject();
                int bx = b.get("dx").getAsInt(), by = FLOOR - 1 + b.get("dy").getAsInt(), bz = b.get("dz").getAsInt();
                minX = Math.min(minX, bx); maxX = Math.max(maxX, bx); minZ = Math.min(minZ, bz); maxZ = Math.max(maxZ, bz);
                if (bx == lay.lx && bz == lay.lz) continue;
                Kind k = kindIn(lay.L, b);
                for (double[] q : k.boxes) {
                    double top = by + q[4], bottom = by + q[1];
                    if (top > lay.startY + 1.25 && bottom < lay.startY + 1.8)
                        rects.add(new double[]{bx + q[0] - 0.3, bz + q[2] - 0.3, bx + q[3] + 0.3, bz + q[5] + 0.3});
                }
            }
            x0 = minX - 3; z0 = minZ - 3;
            nx = (int) Math.round((maxX + 4 - x0) / R) + 1;
            nz = (int) Math.round((maxZ + 4 - z0) / R) + 1;
            // the straight line, and the stretch of it the walls cross (along it, from the start's centre)
            double cx0 = lay.sdx + 0.5, cz0 = lay.sdz + 0.5, ux = lay.lx + 0.5 - cx0, uz = lay.lz + 0.5 - cz0, ul = Math.hypot(ux, uz);
            ux /= ul; uz /= ul;
            double alongA = Double.MAX_VALUE, alongB = -Double.MAX_VALUE;
            for (double[] r : rects) {
                // crosses the line: its corners on both sides of it (or on it), within the segment
                double[][] cs = {{r[0], r[1]}, {r[2], r[1]}, {r[0], r[3]}, {r[2], r[3]}};
                double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE, a0 = Double.MAX_VALUE, a1 = -Double.MAX_VALUE;
                for (double[] c : cs) {
                    double cr = ux * (c[1] - cz0) - uz * (c[0] - cx0), al = ux * (c[0] - cx0) + uz * (c[1] - cz0);
                    lo = Math.min(lo, cr); hi = Math.max(hi, cr); a0 = Math.min(a0, al); a1 = Math.max(a1, al);
                }
                if (lo <= 0 && hi >= 0 && a1 > 0 && a0 < ul) { alongA = Math.min(alongA, a0); alongB = Math.max(alongB, a1); }
            }
            if (side != 0 && alongA == Double.MAX_VALUE) { ok = false; d = null; return; }
            boolean[] blocked = new boolean[nx * nz];
            for (int i = 0; i < nx; i++)
                for (int j = 0; j < nz; j++) {
                    double x = x0 + i * R, z = z0 + j * R;
                    for (double[] r : rects)
                        if (x > r[0] && x < r[2] && z > r[1] && z < r[3]) { blocked[i * nz + j] = true; break; }
                    if (side != 0 && !blocked[i * nz + j]) {
                        double cr = ux * (z - cz0) - uz * (x - cx0), al = ux * (x - cx0) + uz * (z - cz0);
                        // left of the line facing the landing is -cross here (the game's: facing +x, left is -z)
                        if (side * -cr < 0 && al > alongA && al < alongB) blocked[i * nz + j] = true;
                    }
                }
            d = new float[nx * nz];
            java.util.Arrays.fill(d, Float.MAX_VALUE);
            double cx = lay.lx + 0.5, cz = lay.lz + 0.5;
            PriorityQueue<double[]> q = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
            for (int i = 0; i < nx; i++)
                for (int j = 0; j < nz; j++) {
                    double x = x0 + i * R, z = z0 + j * R;
                    if (blocked[i * nz + j] || x < lay.lx || x > lay.lx + 1 || z < lay.lz || z > lay.lz + 1) continue;
                    d[i * nz + j] = (float) Math.hypot(x - cx, z - cz);
                    q.add(new double[]{d[i * nz + j], i, j});
                }
            int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {1, 2}, {2, 1}, {-1, 2}, {-2, 1}, {1, -2}, {2, -1}, {-1, -2}, {-2, -1}};
            while (!q.isEmpty()) {
                double[] e = q.poll();
                int i = (int) e[1], j = (int) e[2];
                if (e[0] > d[i * nz + j]) continue;
                for (int[] st : steps) {
                    int a = i + st[0], b = j + st[1];
                    if (a < 0 || b < 0 || a >= nx || b >= nz || blocked[a * nz + b]) continue;
                    // a knight's step: both cells it passes beside open
                    if (Math.abs(st[0]) == 2 && (blocked[(i + st[0] / 2) * nz + j] || blocked[(i + st[0] / 2) * nz + b])) continue;
                    if (Math.abs(st[1]) == 2 && (blocked[i * nz + j + st[1] / 2] || blocked[a * nz + j + st[1] / 2])) continue;
                    if (Math.abs(st[0]) + Math.abs(st[1]) == 2 && (blocked[(i + st[0]) * nz + j] || blocked[i * nz + j + st[1]])) continue;
                    float nd = (float) (e[0] + R * Math.hypot(st[0], st[1]));
                    if (nd < d[a * nz + b]) {
                        d[a * nz + b] = nd;
                        q.add(new double[]{nd, a, b});
                    }
                }
            }
        }

        /** The way's length from (x, z); far for a point off the grid, in a wall or cut off. */
        double at(double x, double z) {
            int i = (int) Math.round((x - x0) / R), j = (int) Math.round((z - z0) / R);
            if (i < 0 || j < 0 || i >= nx || j >= nz) return 100;
            float v = d[i * nz + j];
            return v == Float.MAX_VALUE ? 100 : v;
        }

        /** The way's heading from (x, z) (the game's yaw), from the way's slope; NaN where it has none. */
        float heading(double x, double z) {
            double h = 0.15;
            double gx = at(x + h, z) - at(x - h, z), gz = at(x, z + h) - at(x, z - h);
            if (Math.abs(gx) > 10 || Math.abs(gz) > 10 || Math.hypot(gx, gz) < 1e-6) return Float.NaN;
            return Recipe.yawTo(0, 0, -gx, -gz);
        }
    }

    // ------------------------------------------------------------------ the beam

    static final class Node {
        final MoveState s;
        final float yaw, jumpYaw;
        final int keys, jumpT;
        final Node parent;
        final double dev, score;
        /** The tick of the latest jump key (-1 for none yet): the phase of a run of hops, for the phased beam. */
        int lastJump = -1;

        Node(MoveState s, float yaw, int keys, Node parent, int jumpT, float jumpYaw, double dev, double score) {
            this.s = s; this.yaw = yaw; this.keys = keys; this.parent = parent; this.jumpT = jumpT; this.jumpYaw = jumpYaw; this.dev = dev;
            this.score = score;
        }
    }

    /** One way found: its inputs from the spot, and once measured, its robustness. */
    static final class Cand {
        Spot spot;
        float startYaw;
        float[] yaw;
        int[] keys;
        int jumpT, landT, side;
        double margin;
        String how;
        int robust, variations, starts, covered, probe;
        /** How it comes to rest on the landing once its inputs end: "keys up", "shift" (only with the shift key held),
         *  "held" (a ladder: the hold is in the inputs). */
        String stop;

        double share() { return variations == 0 ? 0 : (double) robust / variations; }
    }

    static final class Landing {
        Node n;
        int t;
        double margin;
        float[] holdYaw;
        int[] holdKeys;
    }

    /** Ways a beam's landings are told apart by (their jump tick, their landing tick, the side of the straight line they
     *  go round, the yaw at the jump to 3 degrees); the PER_GROUP widest of each kept, one per tick before the landing
     *  (the landing tick's own inputs make near copies); of the PROBE widest, the variations from the spot itself
     *  counted (78), and the KEEP best of those measured in full. */
    static final int PROBE = 400, PER_GROUP = 20;

    /** The beam from one spot: the ways it finds, grouped (above), the most promising measured. */
    /** Beam time a layout may take (ms; the course agent: about 20 minutes of beams a layout), after which its further
     *  beams are skipped. KINE_NEO_CAP_MIN. */
    static final double CAP_MS = Double.parseDouble(System.getenv().getOrDefault("KINE_NEO_CAP_MIN", "20")) * 60_000;

    static void beam(Layout lay, Spot sp, Geo geo, float[] turns, int[] keySet, int width, int ticks, int keep, java.io.PrintWriter log) {
        beam(lay, sp, geo, turns, keySet, false, width, ticks, keep, log);
    }

    static void beam(Layout lay, Spot sp, Geo geo, float[] turns, int[] keySet, boolean phased, int width, int ticks, int keep,
                     java.io.PrintWriter log) {
        synchronized (lay) {
            if (lay.ms > CAP_MS) {
                synchronized (log) { log.printf(Locale.ROOT, "%s from %s: skipped (%.0f s of beams already)%n", lay.id, sp.name(), lay.ms / 1000); log.flush(); }
                return;
            }
        }
        boolean wallAware = geo != null;
        long t0 = System.nanoTime();
        GroundPhysics phys = lay.phys();
        MoveState s0 = sp.s().copy();
        double tx = lay.lx + 0.5, tz = lay.lz + 0.5;
        double ux = tx - s0.x, uz = tz - s0.z, ul = Math.hypot(ux, uz);
        ux /= ul; uz /= ul;
        float yaw0 = Recipe.yawTo(s0.x, s0.z, tx, tz);
        if (wallAware) {
            float h = geo.heading(s0.x, s0.z);
            if (!Float.isNaN(h)) yaw0 = h;
        }
        Node root = new Node(s0, yaw0, 0, null, -1, Float.NaN, 0, 0);
        List<Node> beam = new ArrayList<>(List.of(root));
        Map<Long, List<Landing>> groups = new HashMap<>();
        long landed = 0;
        double bestMiss = Double.MAX_VALUE;
        float[] hy = new float[Recipe.HOLD];
        int[] hk = new int[Recipe.HOLD];
        double[] hp = new double[3 * Recipe.HOLD];
        for (int t = 0; t < ticks && !beam.isEmpty(); t++) {
            ArrayList<Node> next = new ArrayList<>(beam.size() * 64);
            for (Node n : beam) {
                boolean canJump = n.s.onGround || phys.onClimbable(n.s);
                for (float turn : turns)
                    for (int k : keySet)
                        for (int jump = 0; jump <= 1; jump++) {
                            if (jump == 1 && !canJump) continue;
                            float yaw = n.yaw + turn;
                            int keys = k | (jump == 1 ? MoveState.JUMP : 0);
                            MoveState s = n.s.copy();
                            s.landedFall = 0;
                            phys.step(s, keys, yaw, 0f);
                            if (s.inWater || s.y < lay.lowY || hurts(s) || spoils(s)) continue;
                            int jumpT = n.jumpT < 0 && jump == 1 ? t : n.jumpT;
                            // before the first jump, no other floor than the start's (Recipe.offFloor)
                            if (jumpT < 0 && Recipe.offFloor(s0, s)) continue;
                            // the nearest miss: coming down through the landing's height, the box's gap to its block
                            if (n.s.y >= lay.landY - 1e-6 && s.y < lay.landY - 1e-6 && !s.onGround) {
                                double gx = Math.max(0, Math.max(lay.lx - (s.x + 0.3), (s.x - 0.3) - lay.lx - 1));
                                double gz = Math.max(0, Math.max(lay.lz - (s.z + 0.3), (s.z - 0.3) - lay.lz - 1));
                                bestMiss = Math.min(bestMiss, Math.hypot(gx, gz));
                            }
                            float jumpYaw = n.jumpT < 0 && jump == 1 ? yaw : n.jumpYaw;
                            double c = ux * (s.z - s0.z) - uz * (s.x - s0.x);
                            double dev = Math.abs(c) > Math.abs(n.dev) ? c : n.dev;
                            Landing got = null;
                            if (!lay.climb) {
                                if (lay.onLanding(s)) {
                                    if (stays(phys, lay, s)) {
                                        got = new Landing();
                                        got.margin = lay.margin(s);
                                    } else continue;
                                }
                            } else if (Recipe.holdsOn(phys, s, lay.lx, lay.ly, lay.lz)) {
                                MoveState m = s.copy();
                                if (Recipe.hold(phys, m, yaw, lay.lx, lay.ly, lay.lz, lay.wall, hy, hk, hp) >= 0) {
                                    got = new Landing();
                                    got.margin = lay.margin(m);
                                    got.holdYaw = hy.clone();
                                    got.holdKeys = hk.clone();
                                }
                            }
                            if (got != null) {
                                landed++;
                                got.n = new Node(s, yaw, keys, n, jumpT, jumpYaw, dev, 0);
                                got.t = t;
                                int side = dev > 0.3 ? 2 : dev < -0.3 ? 0 : 1;
                                long bucket = Float.isNaN(jumpYaw) ? 0 : Math.round(jumpYaw / 3f) + 512;
                                long key = ((((long) (jumpT + 1) * 512 + t) * 4 + side) << 12) | bucket;
                                List<Landing> g = groups.computeIfAbsent(key, kk -> new ArrayList<>());
                                int same = -1, worst = -1;
                                for (int q = 0; q < g.size(); q++) {
                                    if (g.get(q).n.parent == n) same = q;
                                    if (worst < 0 || g.get(q).margin < g.get(worst).margin) worst = q;
                                }
                                if (same >= 0) { if (got.margin > g.get(same).margin) g.set(same, got); }
                                else if (g.size() < PER_GROUP) g.add(got);
                                else if (got.margin > g.get(worst).margin) g.set(worst, got);
                                continue;
                            }
                            // the generic beam: the difficulty test's (straight distance, and half the height off the
                            // landing's); the wall-aware: TripleNeoSearchTest's, no cost for the height of a jump
                            double h = wallAware ? geo.at(s.x, s.z) + Math.max(0, lay.landY - s.y) * 0.5
                                : Math.hypot(tx - s.x, tz - s.z) + Math.abs(s.y - lay.landY) * 0.5;
                            Node child = new Node(s, yaw, keys, n, jumpT, jumpYaw, dev, h - 3.0 * Math.hypot(s.vx, s.vz));
                            child.lastJump = jump == 1 ? t : n.lastJump;
                            next.add(child);
                        }
            }
            next.sort(Comparator.comparingDouble(a -> a.score));
            List<Node> kept = new ArrayList<>(Math.min(width, next.size()));
            Set<Long> seen = new HashSet<>();
            long[] keyOf = new long[next.size()];
            for (int i = 0; i < next.size(); i++) {
                MoveState q = next.get(i).s;
                long key = ((long) Math.floor(q.x * 20) << 42) ^ ((long) Math.floor(q.z * 20) << 21) ^ ((long) Math.floor(q.y * 20))
                    ^ ((long) Math.floor(q.vx * 40) * 31) ^ ((long) Math.floor(q.vz * 40) * 131);
                // the deep round tells sprinting and the jump's delay apart (a walk's state is not a sprint's)
                if (keySet == KEYS_PLUS) key ^= (q.sprinting ? 0x5bd1e9955bd1e995L : 0) ^ ((long) q.noJumpDelay * 0x9E3779B97F4A7C15L);
                keyOf[i] = key;
            }
            boolean[] taken = new boolean[next.size()];
            if (phased) {
                // half the width shared out by the phase of the hops (ticks since the latest jump, 0 to 15): a run of
                // hops whose last ground touch falls right at the gap's edge is not crowded out by the faster ones
                int buckets = 16, per = Math.max(1, width / (2 * buckets));
                int[] count = new int[buckets];
                for (int i = 0; i < next.size(); i++) {
                    Node n = next.get(i);
                    int b = Math.min(buckets - 1, n.lastJump < 0 ? t + 1 : t - n.lastJump);
                    if (count[b] >= per || seen.contains(keyOf[i])) continue;
                    seen.add(keyOf[i]);
                    count[b]++;
                    taken[i] = true;
                    kept.add(n);
                }
            }
            for (int i = 0; i < next.size() && kept.size() < width; i++) {
                if (taken[i] || !seen.add(keyOf[i])) continue;
                kept.add(next.get(i));
            }
            beam = kept;
        }
        double beamMs = (System.nanoTime() - t0) / 1e6;
        // the widest of each group; the PROBE widest probed from the spot, the KEEP best of them measured
        List<Landing> best = new ArrayList<>();
        for (List<Landing> g : groups.values()) best.addAll(g);
        best.sort(Comparator.comparingDouble((Landing l) -> -l.margin).thenComparingInt(l -> l.t));
        String kind = !wallAware ? "generic" : geo.side == 0 ? "wall-aware" : geo.side > 0 ? "wall-aware left" : "wall-aware right";
        String how = String.format(Locale.ROOT, "beam %s%s%s width %d from the start block's %s (%.2f, %.2f)", kind,
            turns == TURNS ? "" : " fine turns", (keySet == KEYS_PLUS ? " walk keys" : "") + (phased ? " phased" : ""), width, sp.name(),
            sp.x() - lay.sdx, sp.z() - lay.sdz);
        List<Cand> probed = new ArrayList<>();
        for (Landing l : best) {
            if (probed.size() >= PROBE) break;
            Cand c = new Cand();
            c.spot = sp;
            c.startYaw = yaw0;
            int n = l.t + 1, h = l.holdYaw == null ? 0 : l.holdYaw.length;
            c.yaw = new float[n + h];
            c.keys = new int[n + h];
            int i = n - 1;
            for (Node q = l.n; q.parent != null; q = q.parent, i--) { c.yaw[i] = q.yaw; c.keys[i] = q.keys; }
            if (h > 0) {
                System.arraycopy(l.holdYaw, 0, c.yaw, n, h);
                System.arraycopy(l.holdKeys, 0, c.keys, n, h);
            }
            c.jumpT = l.n.jumpT;
            c.landT = l.t;
            c.side = l.n.dev > 0.3 ? 1 : l.n.dev < -0.3 ? -1 : 0;
            c.margin = l.margin;
            c.how = how + (h > 0 ? ", then held on the ladder" : "");
            c.probe = probe(phys, lay, c);
            probed.add(c);
        }
        probed.sort(Comparator.comparingInt((Cand c) -> -c.probe).thenComparingDouble(c -> -c.margin));
        int measured = 0;
        double bestShare = 0;
        for (Cand c : probed) {
            if (measured >= keep) break;
            robustness(phys, lay, c);
            c.stop = stop(phys, lay, c.spot.s(), c.yaw, c.keys);
            lay.cands.add(c);
            bestShare = Math.max(bestShare, c.share());
            measured++;
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        synchronized (lay) {
            lay.landed += landed;
            lay.ms += ms;
            if (landed > 0) lay.found.add(kind);
        }
        String line = String.format(Locale.ROOT, "%s %s: landed %d (%d groups), %d measured, best share %.3f, nearest miss %s; beam %.0f ms, all %.0f ms",
            lay.id, how, landed, groups.size(), measured, bestShare, bestMiss == Double.MAX_VALUE ? "-" : String.format(Locale.ROOT, "%.3f", bestMiss), beamMs, ms);
        lay.beams.add(line);
        synchronized (log) { log.println(line); log.flush(); }
    }

    /** How the inputs from s come to rest on the landing (Cand.stop), or null if they don't land. */
    static String stop(GroundPhysics phys, Layout lay, MoveState start, float[] yaw, int[] keys) {
        if (lay.climb) return "held";
        MoveState m = start.copy();
        for (int t = 0; t < keys.length; t++) {
            m.landedFall = 0;
            phys.step(m, keys[t], yaw[t], 0f);
            if (lay.onLanding(m)) break;
        }
        if (!lay.onLanding(m)) return null;
        MoveState q = m.copy();
        for (int t = 0; t < 40; t++) {
            q.landedFall = 0;
            phys.step(q, 0, 0f, 0f);
            if (!lay.onLanding(q)) break;
            if (Math.hypot(q.vx, q.vz) < 0.003) return "keys up";
        }
        return stays(phys, lay, m) ? "shift" : null;
    }

    /**
     * The run-up family (part 2: a probe found up_4p0p4375_c45 and trapdoor_jump this way, 1 try in 14,976 each, where no
     * beam did): from points along the start block's middle line toward the landing (from 0.79 behind the centre to
     * 0.79 ahead, 0.02 apart, those the player stands on; 0.1 apart on a runway), the view held toward the landing's centre:
     * 0 to 8 ticks backward first (backward momentum; 0 to 3 on a runway), then forward, without the sprint key until a chosen tick (0 to 8) and with it
     * after, a jump on the first or second tick or none (a jam), and the take-off jump on a chosen later tick; on until
     * it lands (or holds, on a ladder). Every way that lands is probed from its spot (78 variations), the KEEP best
     * measured in full.
     */
    static void runups(Layout lay, int keep, java.io.PrintWriter log) {
        long t0 = System.nanoTime();
        GroundPhysics phys = lay.phys();
        double cx = lay.sdx + 0.5, cz = lay.sdz + 0.5, tx = lay.lx + 0.5, tz = lay.lz + 0.5;
        double ux = tx - cx, uz = tz - cz, ul = Math.hypot(ux, uz);
        ux /= ul; uz /= ul;
        float yaw = Recipe.yawTo(cx, cz, tx, tz);
        int limit = lay.ticks + 10;
        float[] hy = new float[Recipe.HOLD];
        int[] hk = new int[Recipe.HOLD];
        double[] hp = new double[3 * Recipe.HOLD];
        int[] ks = new int[limit];
        List<Cand> got = new ArrayList<>();
        long tried = 0, landed = 0;
        // a runway: the take-off may come later, and the line's points 0.1 apart (the run evens out small offsets)
        double runway = Math.hypot(lay.kdx - lay.sdx, lay.kdz - lay.sdz);
        int lastJump = 24 + (int) Math.ceil(runway / 0.25);
        double step = runway > 0 ? 0.1 : 0.02;
        for (int ai = -(int) Math.round(0.79 / step); ai <= (int) Math.round(0.79 / step); ai++) {
            double a = ai * step;
            double x = Math.round((cx + a * ux) * 1000) / 1000.0, z = Math.round((cz + a * uz) * 1000) / 1000.0;
            MoveState s0 = settle(phys, x, lay.startY, z);
            if (s0 == null) continue;
            Spot sp = new Spot(String.format(Locale.ROOT, "line %+.2f", a), x, z, s0);
            for (int back = 0; back <= (runway > 0 ? 3 : 8); back++)
                for (int jam = -1; jam <= 1; jam++)
                    for (int sprintAt = 0; sprintAt <= 8; sprintAt++)
                        for (int jump2 = jam + 2; jump2 <= lastJump; jump2++) {
                            tried++;
                            MoveState m = s0.copy();
                            boolean jumped = false, won = false;
                            int t = 0, firstJump = -1;
                            Landing l = null;
                            for (; t < limit; t++) {
                                int k;
                                if (t < back) k = MoveState.BACKWARD;
                                else {
                                    int u = t - back;
                                    k = MoveState.FORWARD | (u >= sprintAt ? MoveState.SPRINT : 0) | (u == jam || u == jump2 ? MoveState.JUMP : 0);
                                }
                                ks[t] = k;
                                if ((k & MoveState.JUMP) != 0 && !jumped) { jumped = true; firstJump = t; }
                                m.landedFall = 0;
                                phys.step(m, k, yaw, 0f);
                                if (m.inWater || m.y < lay.lowY || hurts(m) || spoils(m)) break;
                                if (!jumped && Recipe.offFloor(s0, m)) break;
                                if (!lay.climb) {
                                    if (lay.onLanding(m)) {
                                        if (stays(phys, lay, m)) { won = true; l = new Landing(); l.margin = lay.margin(m); }
                                        break;
                                    }
                                } else if (Recipe.holdsOn(phys, m, lay.lx, lay.ly, lay.lz)) {
                                    MoveState q = m.copy();
                                    if (Recipe.hold(phys, q, yaw, lay.lx, lay.ly, lay.lz, lay.wall, hy, hk, hp) >= 0) {
                                        won = true;
                                        l = new Landing();
                                        l.margin = lay.margin(q);
                                        l.holdYaw = hy.clone();
                                        l.holdKeys = hk.clone();
                                    }
                                    break;
                                }
                            }
                            if (!won) continue;
                            landed++;
                            Cand c = new Cand();
                            c.spot = sp;
                            c.startYaw = yaw;
                            int n = t + 1, h = l.holdYaw == null ? 0 : l.holdYaw.length;
                            c.yaw = new float[n + h];
                            java.util.Arrays.fill(c.yaw, yaw);
                            c.keys = java.util.Arrays.copyOf(ks, n + h);
                            if (h > 0) {
                                System.arraycopy(l.holdYaw, 0, c.yaw, n, h);
                                System.arraycopy(l.holdKeys, 0, c.keys, n, h);
                            }
                            c.jumpT = firstJump;
                            c.landT = t;
                            c.side = 0;
                            c.margin = l.margin;
                            c.how = String.format(Locale.ROOT, "run-up from the start block's middle line %+.2f (%.2f, %.2f): %d back, jam %s, sprint from %d, take-off at %d%s",
                                a, x - lay.sdx, z - lay.sdz, back, jam < 0 ? "none" : "at " + jam, sprintAt, jump2, h > 0 ? ", then held on the ladder" : "");
                            got.add(c);
                        }
        }
        got.sort(Comparator.comparingDouble((Cand c) -> -c.margin));
        List<Cand> probed = new ArrayList<>(got.subList(0, Math.min(PROBE, got.size())));
        for (Cand c : probed) c.probe = probe(phys, lay, c);
        probed.sort(Comparator.comparingInt((Cand c) -> -c.probe).thenComparingDouble(c -> -c.margin));
        double bestShare = 0;
        int measured = 0;
        for (Cand c : probed) {
            if (measured++ >= keep) break;
            robustness(phys, lay, c);
            c.stop = stop(phys, lay, c.spot.s(), c.yaw, c.keys);
            lay.cands.add(c);
            bestShare = Math.max(bestShare, c.share());
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        synchronized (lay) {
            lay.landed += landed;
            lay.ms += ms;
            if (landed > 0) lay.found.add("run-up");
        }
        String line = String.format(Locale.ROOT, "%s run-up family: %d of %d land, %d measured, best share %.3f; %.0f ms", lay.id, landed, tried,
            Math.min(keep, probed.size()), bestShare, ms);
        lay.beams.add(line);
        synchronized (log) { log.println(line); log.flush(); }
    }

    /** After standing on the landing: comes to rest on it, keys up or with the shift key held (the sneak's edge stop). */
    static boolean stays(GroundPhysics phys, Layout lay, MoveState s0) {
        for (int shift = 0; shift <= 1; shift++) {
            MoveState m = s0.copy();
            for (int t = 0; t < 40; t++) {
                m.landedFall = 0;
                phys.step(m, shift == 1 ? MoveState.SHIFT : 0, 0f, 0f);
                if (!lay.onLanding(m)) break;
                if (Math.hypot(m.vx, m.vz) < 0.003) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ robustness

    /** The keys with the first jump moved by `shift` ticks; null when it would leave the inputs. */
    static int[] shiftJump(int[] keys, int jumpT, int shift) {
        if (shift == 0) return keys;
        if (jumpT < 0) return null;
        int t2 = jumpT + shift;
        if (t2 < 0 || t2 >= keys.length) return null;
        int[] k = keys.clone();
        k[jumpT] &= ~MoveState.JUMP;
        k[t2] |= MoveState.JUMP;
        return k;
    }

    /** Whether the inputs (yaw + off each tick, a tick standing first if delayed) from `start` end standing on the
     *  landing and come to rest there (or held on its ladder), within their ticks and EXTRA_TICKS more (keys up; on a
     *  ladder its last keys). */
    static boolean plays(GroundPhysics phys, Layout lay, MoveState start, float[] yaw, int[] keys, float off, int delay) {
        MoveState m = start.copy();
        int n = keys.length;
        boolean jumped = false;
        m.landedFall = 0;
        if (delay > 0) phys.step(m, 0, yaw[0] + off, 0f);
        for (int t = 0; t < n + EXTRA_TICKS; t++) {
            int k = t < n ? keys[t] : lay.climb ? keys[n - 1] : 0;
            float y = (t < n ? yaw[t] : yaw[n - 1]) + off;
            m.landedFall = 0;
            phys.step(m, k, y, 0f);
            if (m.inWater || m.y < lay.lowY || hurts(m) || spoils(m)) return false;
            if (!jumped && (k & MoveState.JUMP) != 0) jumped = true;
            if (!jumped && Recipe.offFloor(start, m)) return false;
            if (!lay.climb) {
                if (lay.onLanding(m)) return stays(phys, lay, m);
            } else if (t >= n - 1 && Recipe.holdsOn(phys, m, lay.lx, lay.ly, lay.lz) && Math.hypot(m.vx, m.vz) <= 0.05) {
                return true;
            }
        }
        return false;
    }

    /** The variations from the spot itself that land (of 78, fewer where a jump moved would leave the inputs). */
    static int probe(GroundPhysics phys, Layout lay, Cand c) {
        int here = 0;
        for (float off : YAW_OFFSETS)
            for (int js : JUMP_SHIFTS) {
                int[] keys = shiftJump(c.keys, c.jumpT, js);
                if (keys == null) continue;
                for (int d : DELAYS) if (plays(phys, lay, c.spot.s(), c.yaw, keys, off, d)) here++;
            }
        return here;
    }

    /** The template's robustness: of every start state (the spot and up to 0.08 off it, those standing) and every
     *  variation, how many land; and from how many start states at least one does. */
    static void robustness(GroundPhysics phys, Layout lay, Cand c) {
        int landed = 0, tried = 0, starts = 0, covered = 0;
        for (double[] o : OFFSETS) {
            MoveState s = settle(phys, c.spot.x() + o[0], lay.startY, c.spot.z() + o[1]);
            if (s == null) continue;
            starts++;
            int here = 0;
            for (float off : YAW_OFFSETS)
                for (int js : JUMP_SHIFTS) {
                    int[] keys = shiftJump(c.keys, c.jumpT, js);
                    if (keys == null) continue;
                    for (int d : DELAYS) {
                        tried++;
                        if (plays(phys, lay, s, c.yaw, keys, off, d)) here++;
                    }
                }
            landed += here;
            if (here > 0) covered++;
        }
        c.robust = landed;
        c.variations = tried;
        c.starts = starts;
        c.covered = covered;
    }

    /** Up to four per layout, the most robust first: the best from each spot, then the next best ways. */
    static List<Cand> choose(Layout lay) {
        List<Cand> all = new ArrayList<>(lay.cands);
        all.sort(Comparator.comparingDouble((Cand c) -> -c.share()).thenComparingInt(c -> -c.robust).thenComparingInt(c -> c.keys.length));
        List<Cand> kept = new ArrayList<>();
        for (Cand c : all) {
            if (kept.size() >= 4) break;
            boolean spotTaken = false;
            for (Cand k : kept) if (k.spot == c.spot) { spotTaken = true; break; }
            if (!spotTaken) kept.add(c);
        }
        for (Cand c : all) {
            if (kept.size() >= 4) break;
            if (kept.contains(c)) continue;
            boolean same = false;
            for (Cand k : kept)
                if (k.spot == c.spot && k.jumpT == c.jumpT && k.landT == c.landT && k.side == c.side) { same = true; break; }
            if (!same) kept.add(c);
        }
        kept.sort(Comparator.comparingDouble((Cand c) -> -c.share()).thenComparingInt(c -> -c.robust));
        return kept;
    }

    // ------------------------------------------------------------------ the run

    static String num(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s + "0" : s;
    }

    @Test
    void generate() throws Exception {
        String out = System.getenv("KINE_NEO_GEN");
        if (out == null) return;
        // KINE_NEO_FALL_HP: Fall damage allowed (half hearts) for the landings (Recipe.hurts): the course's down jumps
        // (down_5m1, down_6m3, ...) land from falls past the safe 3, which a walk with the setting may take
        String fallHp = System.getenv("KINE_NEO_FALL_HP");
        if (fallHp != null) Recipe.safeFall = Costs.withFallDamage(Costs.LAYER2, Integer.parseInt(fallHp)).safeFall;
        String only = System.getenv("KINE_NEO_IDS");
        int threads = Math.max(1, Math.min(4, Integer.parseInt(System.getenv().getOrDefault("KINE_NEO_THREADS", "4"))));
        int width = Integer.parseInt(System.getenv().getOrDefault("KINE_NEO_WIDTH", "3000"));
        int wide = Integer.parseInt(System.getenv().getOrDefault("KINE_NEO_WIDE_WIDTH", "6000"));
        int keep = Integer.parseInt(System.getenv().getOrDefault("KINE_NEO_KEEP", "24"));
        String cat = System.getenv().getOrDefault("KINE_CATALOGUE", "/home/raymond/_/kine_course_2026-10-14/parkour_research/catalogue.json");
        String stationsFile = System.getenv().getOrDefault("KINE_STATIONS", "/home/raymond/_/kine_course_2026-10-14/parkour_world/stations.json");
        Path outPath = Path.of(out);
        String stem = out.endsWith(".json") ? out.substring(0, out.length() - 5) : out;
        java.io.PrintWriter log = new java.io.PrintWriter(Files.newBufferedWriter(Path.of(stem + ".log")));

        // the layouts, built on this thread (the palette and the copy's block infos are not for many threads)
        List<Layout> lays = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, Integer> catOrder = new HashMap<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray()) {
            JsonObject en = e.getAsJsonObject();
            String id = en.get("id").getAsString();
            catOrder.put(id, catOrder.size());
            boolean neo = en.has("category") && "neo".equals(en.get("category").getAsString());
            if (!neo && !EXTRA.contains(id) && !COURSE_EXTRA.contains(id)) continue;
            if (only != null && !java.util.Arrays.asList(only.split(",")).contains(id)) continue;
            JsonObject L = en.has("layout") && en.get("layout").isJsonObject() ? en.getAsJsonObject("layout") : null;
            if (!buildable(L)) {
                skipped.add(id + (L == null || !L.has("blocks") || L.getAsJsonArray("blocks").isEmpty() ? " (no blocks)" : " (blocks the copy lacks)"));
                continue;
            }
            Layout lay = new Layout(id, L);
            findSpots(lay);
            lays.add(lay);
            log.printf(Locale.ROOT, "%s: start %d,%d,%d feet %.4f landing %d,%d,%d feet %.4f%s, %d ticks, spots %s%n", id, lay.sdx, lay.sdy, lay.sdz,
                lay.startY - lay.base, lay.ldx, lay.ldy, lay.ldz, lay.landY - lay.base, lay.climb ? " (a ladder)" : "", lay.ticks,
                lay.spots.stream().map(s -> String.format(Locale.ROOT, "%s (%.2f, %.2f)", s.name(), s.x() - lay.sdx, s.z() - lay.sdz)).toList());
            log.flush();
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        String mode = System.getenv().getOrDefault("KINE_NEO_MODE", "all");   // all, deep; or for a look: generic, wall, fine
        if (only != null) {   // the layouts in the order asked
            List<String> ask = java.util.Arrays.asList(only.split(","));
            lays.sort(Comparator.comparingInt(l -> ask.indexOf(l.id)));
        }
        int[] sides = {1, -1, 0};
        // 1: the generic beam from every spot
        if (mode.equals("all") || mode.equals("generic")) run(pool, lays, lay -> true, null, TURNS, width, 0, keep, log, null);
        // 2: the wall-aware beams (round either side, and the shorter way) on the layouts with none, or with none that
        // lands from half the start states
        if (mode.equals("all") || mode.equals("wall")) run(pool, lays, lay -> bestCover(lay) < 0.5, sides, TURNS, width, 5, keep, log, null);
        // 3: where still none, finer turns (the same limit), wider and longer: the wall-aware shorter way and the
        // generic beam from the centre and the back
        // deep (part 2, the layouts the rounds above found nothing on): the walk keys too, the sprint and the jump's delay
        // told apart, every spot (runway spots too), the generic and the wall-aware beams, 10 ticks longer; where still
        // none, wider and 20 ticks longer from the centre, the back and the take-off; at most CAP_MS of beams a layout
        // the run-up family first in the deep round (and alone in mode runup): cheap, and the 1bm jumps' way
        if (mode.equals("deep") || mode.equals("runup")) {
            List<Future<?>> jobs = new ArrayList<>();
            for (Layout lay : lays) jobs.add(pool.submit(() -> { runups(lay, keep, log); return null; }));
            for (Future<?> f : jobs) f.get();
        }
        if (mode.equals("deep") || mode.equals("phased")) {   // phased: the last round only (layouts the others did)
            if (mode.equals("deep")) {
                run(pool, lays, lay -> bestCover(lay) < 0.5, null, TURNS, KEYS_PLUS, width, 10, keep, log, null);
                run(pool, lays, lay -> bestCover(lay) < 0.5, sides, TURNS, KEYS_PLUS, width, 10, keep, log, null);
            }
            List<Layout> none = lays.stream().filter(l -> l.cands.isEmpty()).toList();
            run(pool, none, lay -> true, null, TURNS, KEYS_PLUS, true, wide, 20, keep, log, null);
            run(pool, none, lay -> true, new int[]{0}, TURNS, KEYS_PLUS, true, wide, 20, keep, log, null);
        }
        if (mode.equals("all") || mode.equals("fine")) {
            List<Layout> none = lays.stream().filter(l -> l.cands.isEmpty()).toList();
            run(pool, none, lay -> true, new int[]{0}, TURNS_FINE, wide, 10, keep, log, Set.of("centre", "back"));
            run(pool, none, lay -> true, null, TURNS_FINE, wide, 10, keep, log, Set.of("centre", "back"));
        }
        pool.shutdown();
        log.printf(Locale.ROOT, "all beams: %.0f s%n", (System.nanoTime() - t0) / 1e9);

        // the stations and the course, for the table
        JsonObject world = JsonParser.parseString(Files.readString(Path.of(stationsFile))).getAsJsonObject();
        Map<String, Integer> stationN = new HashMap<>();
        for (JsonElement e : world.getAsJsonArray("stations")) {
            JsonObject st = e.getAsJsonObject();
            stationN.putIfAbsent(st.get("id").getAsString(), st.get("n").getAsInt());
        }
        Map<String, List<Integer>> courseK = new HashMap<>();
        for (JsonElement e : world.getAsJsonObject("course").getAsJsonArray("jumps")) {
            JsonObject j = e.getAsJsonObject();
            courseK.computeIfAbsent(j.get("id").getAsString(), k -> new ArrayList<>()).add(j.get("k").getAsInt());
        }

        // each template's text, by layout id (this run's; and with KINE_NEO_MERGE, the other layouts' from that file)
        Map<String, List<String>> texts = new HashMap<>();
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"version\": 1,\n");
        json.append("  \"frame\": \"the catalogue's, from the key block: its corner at (0, 0, 0), x and z as the catalogue's dx and dz, yaw as the game's (0 = +z, -90 = +x); the key block is the layout's take-off (its 0, 0, 0, the runway's last block, where the walk stands when stuck) where it has a block to stand on, else its start block; landing, feet (standing on the key block), landingFeet and spot from it; spotFeet the feet at the spot (on the catalogue's start block, which is from)\",\n");
        json.append("  \"robustness\": \"robust of variations: from the spot and 12 points up to 0.08 off it (those the player stands on: starts), each of 13 yaw offsets (0, +-0.3, +-0.6, +-1, +-1.5, +-2.5, +-4 degrees on every tick), the first jump moved by -1, 0 or +1 tick (where it stays in the inputs), the start delayed 0 or 1 tick; landed: standing on the landing within the inputs and 10 more ticks keys up, then coming to rest on it (keys up or the shift key held), or for a ladder held on it at the end; covered: the starts from which at least one variation lands\",\n");
        StringBuilder table = new StringBuilder();
        table.append(String.format(Locale.ROOT, "%-27s %5s %-9s %-6s %9s %6s %-60s %8s %s%n", "layout", "stn", "course", "found", "landings", "cands",
            "kept (robust/variations covered/starts spot)", "beams s", "found by"));
        for (Layout lay : lays) {
            List<Cand> kept = choose(lay);
            StringBuilder ks = new StringBuilder();
            for (Cand c : kept) {
                if (ks.length() > 0) ks.append("; ");
                ks.append(String.format(Locale.ROOT, "%d/%d %d/%d %s", c.robust, c.variations, c.covered, c.starts, c.spot.name()));
                StringBuilder json0 = json;
                json = new StringBuilder();
                json.append("    {\n");
                json.append("      \"id\": \"").append(lay.id).append("\",\n");
                json.append(String.format(Locale.ROOT, "      \"landing\": [%d, %d, %d],%n", lay.ldx - lay.kdx, lay.ldy - lay.kdy, lay.ldz - lay.kdz));
                json.append("      \"feet\": ").append(num(lay.kFeet - lay.kBase)).append(",\n");
                json.append("      \"landingFeet\": ").append(num(lay.landY - lay.kBase)).append(",\n");
                json.append("      \"spot\": [").append(num(c.spot.x() - lay.kdx)).append(", ").append(num(c.spot.z() - lay.kdz)).append("],\n");
                json.append("      \"spotFeet\": ").append(num(lay.startY - lay.kBase)).append(",\n");
                json.append(String.format(Locale.ROOT, "      \"from\": [%d, %d, %d],%n", lay.sdx - lay.kdx, lay.sdy - lay.kdy, lay.sdz - lay.kdz));
                json.append("      \"startYaw\": ").append(Float.toString(c.startYaw)).append(",\n");
                StringBuilder ys = new StringBuilder(), kk = new StringBuilder();
                for (int i = 0; i < c.keys.length; i++) {
                    if (i > 0) { ys.append(", "); kk.append(", "); }
                    ys.append(Float.toString(c.yaw[i]));
                    kk.append(c.keys[i]);
                }
                json.append("      \"yaw\": [").append(ys).append("],\n");
                json.append("      \"keys\": [").append(kk).append("],\n");
                json.append("      \"robust\": ").append(c.robust).append(",\n");
                json.append("      \"variations\": ").append(c.variations).append(",\n");
                json.append("      \"covered\": ").append(c.covered).append(",\n");
                json.append("      \"starts\": ").append(c.starts).append(",\n");
                json.append("      \"jumpTick\": ").append(c.jumpT).append(",\n");
                json.append("      \"stop\": \"").append(c.stop).append("\",\n");
                if (lay.climb) json.append("      \"climb\": true,\n");
                json.append("      \"how\": \"").append(c.how).append("\"\n");
                json.append("    }");
                texts.computeIfAbsent(lay.id, k -> new ArrayList<>()).add(json.toString());
                json = json0;
            }
            List<Integer> ks2 = courseK.getOrDefault(lay.id, List.of());
            table.append(String.format(Locale.ROOT, "%-27s %5s %-9s %-6s %9d %6d %-60s %8.0f %s%n", lay.id, stationN.getOrDefault(lay.id, 0),
                ks2.isEmpty() ? "-" : ks2.toString().replace(" ", ""), kept.isEmpty() ? "no" : "yes", lay.landed, lay.cands.size(),
                kept.isEmpty() ? "-" : ks.toString(), lay.ms / 1000, lay.found.isEmpty() ? "-" : String.join("+", lay.found)));
        }
        String merge = System.getenv("KINE_NEO_MERGE");
        if (merge != null) {
            Set<String> ran = new HashSet<>();
            for (Layout lay : lays) ran.add(lay.id);
            for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(merge))).getAsJsonObject().getAsJsonArray("templates")) {
                JsonObject o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                if (ran.contains(id)) continue;
                StringBuilder t = new StringBuilder("    {\n");
                int i = 0;
                for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                    String v = en.getValue().isJsonArray() ? en.getValue().toString().replace(",", ", ") : en.getValue().toString();
                    t.append("      \"").append(en.getKey()).append("\": ").append(v).append(++i < o.size() ? ",\n" : "\n");
                }
                texts.computeIfAbsent(id, k -> new ArrayList<>()).add(t.append("    }").toString());
            }
        }
        List<String> order = new ArrayList<>(texts.keySet());
        order.sort(Comparator.comparingInt(id -> catOrder.getOrDefault(id, Integer.MAX_VALUE)));
        json.append("  \"templates\": [");
        boolean firstT = true;
        for (String id : order)
            for (String t : texts.get(id)) {
                json.append(firstT ? "\n" : ",\n").append(t);
                firstT = false;
            }
        json.append("\n  ]\n}\n");
        for (String s : skipped) table.append("skipped: ").append(s).append('\n');
        // the course's neo stations, in course order
        table.append("\nthe course's jumps of these layouts (course order k: id, template or not):\n");
        Set<String> withT = new HashSet<>();
        for (Layout lay : lays) if (!choose(lay).isEmpty()) withT.add(lay.id);
        for (JsonElement e : world.getAsJsonObject("course").getAsJsonArray("jumps")) {
            JsonObject j = e.getAsJsonObject();
            String id = j.get("id").getAsString();
            boolean isNeo = lays.stream().anyMatch(l -> l.id.equals(id)) || skipped.stream().anyMatch(s -> s.startsWith(id + " "));
            if (!isNeo) continue;
            table.append(String.format(Locale.ROOT, "  k %3d %-27s %s%n", j.get("k").getAsInt(), id,
                withT.contains(id) ? "template" : lays.stream().anyMatch(l -> l.id.equals(id)) ? "none found" : "not built in the copy"));
        }
        if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());
        Files.writeString(outPath, json);
        Files.writeString(Path.of(stem + ".txt"), table);
        log.println(table);
        log.close();
        System.out.print(table);
    }

    /** KINE_NEO_ANNOTATE set (a templates file): each template replayed from its spot in the copy and its "stop" written
     *  in (how it comes to rest on the landing once its inputs end), the file rewritten in place; for a file made before
     *  the generator wrote it. */
    @Test
    void annotate() throws Exception {
        String file = System.getenv("KINE_NEO_ANNOTATE");
        if (file == null) return;
        String cat = System.getenv().getOrDefault("KINE_CATALOGUE", "/home/raymond/_/kine_course_2026-10-14/parkour_research/catalogue.json");
        Map<String, JsonObject> byId = new HashMap<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray())
            byId.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject().getAsJsonObject("layout"));
        String text = Files.readString(Path.of(file));
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        Map<String, Layout> lays = new HashMap<>();
        StringBuilder out = new StringBuilder(text.substring(0, text.indexOf("  \"templates\": [")));
        out.append("  \"templates\": [");
        int i = 0;
        for (JsonElement e : root.getAsJsonArray("templates")) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            Layout lay = lays.computeIfAbsent(id, k -> new Layout(k, byId.get(k)));
            GroundPhysics phys = lay.phys();
            var sp = o.getAsJsonArray("spot");
            MoveState s = settle(phys, lay.kdx + sp.get(0).getAsDouble(), lay.startY, lay.kdz + sp.get(1).getAsDouble());
            var ya = o.getAsJsonArray("yaw");
            var ka = o.getAsJsonArray("keys");
            float[] yaw = new float[ya.size()];
            int[] keys = new int[ka.size()];
            for (int t = 0; t < yaw.length; t++) { yaw[t] = ya.get(t).getAsFloat(); keys[t] = ka.get(t).getAsInt(); }
            String stop = s == null ? null : stop(phys, lay, s, yaw, keys);
            StringBuilder t = new StringBuilder("    {\n");
            int n = 0;
            for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                if (en.getKey().equals("stop")) continue;
                if (en.getKey().equals("how")) t.append("      \"stop\": \"").append(stop).append("\",\n");
                String v = en.getValue().isJsonArray() ? en.getValue().toString().replace(",", ", ") : en.getValue().toString();
                t.append("      \"").append(en.getKey()).append("\": ").append(v).append(++n < o.size() ? ",\n" : "\n");
            }
            out.append(i++ == 0 ? "\n" : ",\n").append(t.append("    }"));
            System.out.printf(Locale.ROOT, "annotate %s: stop %s%n", id, stop);
        }
        out.append("\n  ]\n}\n");
        Files.writeString(Path.of(file), out);
    }

    interface Pick { boolean test(Layout lay); }

    /** The best share of start states covered by one way found (0 for none). */
    static double bestCover(Layout lay) {
        synchronized (lay.cands) {
            return lay.cands.stream().mapToDouble(c -> c.starts == 0 ? 0 : (double) c.covered / c.starts).max().orElse(0);
        }
    }

    /** One round of beams, every spot of every layout picked (or the named spots), on the pool: the generic beam, or
     *  the wall-aware beams of these sides (each layout's way round its walls on that side; none where no wall
     *  crosses the straight line). ticks: that many more than the layout's own. */
    static void run(ExecutorService pool, List<Layout> lays, Pick pick, int[] sides, float[] turns, int width, int moreTicks, int keep,
                    java.io.PrintWriter log, Set<String> spots) throws Exception {
        run(pool, lays, pick, sides, turns, KEYS, width, moreTicks, keep, log, spots);
    }

    static void run(ExecutorService pool, List<Layout> lays, Pick pick, int[] sides, float[] turns, int[] keySet, int width, int moreTicks,
                    int keep, java.io.PrintWriter log, Set<String> spots) throws Exception {
        run(pool, lays, pick, sides, turns, keySet, false, width, moreTicks, keep, log, spots);
    }

    static void run(ExecutorService pool, List<Layout> lays, Pick pick, int[] sides, float[] turns, int[] keySet, boolean phased, int width,
                    int moreTicks, int keep, java.io.PrintWriter log, Set<String> spots) throws Exception {
        List<Future<?>> jobs = new ArrayList<>();
        for (Layout lay : lays) {
            if (!pick.test(lay)) continue;
            List<Geo> geos = new ArrayList<>();
            if (sides == null) geos.add(null);
            else for (int side : sides) { Geo g = lay.geo(side); if (g != null) geos.add(g); }
            for (Geo geo : geos)
            for (Spot sp : lay.spots) {
                if (spots != null && !spots.contains(sp.name())) continue;
                jobs.add(pool.submit(() -> {
                    try {
                        beam(lay, sp, geo, turns, keySet, phased, width, lay.ticks + moreTicks, keep, log);
                    } catch (Throwable ex) {
                        synchronized (log) { log.println(lay.id + " " + sp.name() + ": " + ex); ex.printStackTrace(log); log.flush(); }
                    }
                    return null;
                }));
            }
        }
        for (Future<?> f : jobs) f.get();
    }
}
