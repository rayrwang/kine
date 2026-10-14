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
 * The triple neo check (RW, 2026-10-13: "yeah sendht etripe neo check"), in the copy, findings only: does any input
 * sequence a precise client could press make the catalogue's triple neos (neo_triple, neo_triple_2bm,
 * neo_triple_winged), within kine's rule of at most 30 degrees of turn a tick? A beam over every tick's input: the keys
 * (forward, forward with a strafe either way; sprinting; jump), and the turn (0, 5, 15 or 30 degrees either way). The
 * beam keeps the states furthest along the way round the wall (the shortest way from the feet to the landing round the
 * wall's end, the box's half width off it), the faster first. KINE_TRIPLE set to the ids ("all": the three).
 */
public class TripleNeoSearchTest {
    static final int FLOOR = Layer2StationTest.FLOOR;
    /** The turns a tick: kine's rule (at most 30 degrees); KINE_TRIPLE_FAST adds 60 and 90 either way (a person's
     *  mouse, to tell the turn rule from the physics). */
    static final float[] TURNS = System.getenv("KINE_TRIPLE_FAST") != null
        ? new float[]{0f, 5f, -5f, 15f, -15f, 30f, -30f, 60f, -60f, 90f, -90f}
        : new float[]{0f, 5f, -5f, 15f, -15f, 30f, -30f};
    static final int[] KEYS = {MoveState.FORWARD, MoveState.FORWARD | MoveState.LEFT, MoveState.FORWARD | MoveState.RIGHT,
        MoveState.BACKWARD, MoveState.BACKWARD | MoveState.LEFT, MoveState.BACKWARD | MoveState.RIGHT, MoveState.LEFT, MoveState.RIGHT, 0};
    /** The wall's ends (x) for the layout being searched (blocks above the floor at dz 0). */
    static double wallA = 1, wallB = 4, wallZ0 = 0, wallZ1 = 1;

    record Node(MoveState s, float yaw, int[] keys, float[] yaws, double score, boolean landed, double minZ, double maxZ, double zMid) {}

    static int landX = 4, landZ = 0;
    /** Each landed sequence's path: the least and the most z the feet reached (which side of the wall it went). */
    static double landedMinZ = Double.NaN, landedMaxZ = Double.NaN;
    static int landedPlus, landedMinus;

    /** The way left round the wall (x 1..4, z 0..1, inflated by the box's half width) from (x, z) to the landing's
     *  centre (4.5, 0.5), on the side with z > 1 (side +1) or z < 0 (side -1). */
    static double wayLeft(double x, double z, int side) {
        if (side == 0) return Math.hypot(landX + 0.5 - x, landZ + 0.5 - z);   // no knowledge of the wall
        double cz = side > 0 ? wallZ1 + 0.3 : wallZ0 - 0.3;
        double ax = wallA - 0.3, bx = wallB + 0.3;   // the wall's ends, inflated
        double lx = landX + 0.5, lz = landZ + 0.5;
        if (x >= bx) return Math.hypot(lx - x, lz - z);
        boolean clear = side > 0 ? z >= cz : z <= cz;
        if (x <= ax) {
            // to the near corner (or straight to the far one when beside the wall already), along, then down
            return Math.hypot(ax - x, cz - z) + (bx - ax) + Math.hypot(lx - bx, lz - cz);
        }
        if (clear) return (bx - x) + Math.abs(z - cz) * 0 + Math.hypot(lx - bx, lz - cz);
        return 100;   // inside the wall's band: it hit the wall
    }

    @Test
    void searchTheTripleNeos() throws Exception {
        String ids = System.getenv("KINE_TRIPLE");
        if (ids == null) return;
        int width = Integer.parseInt(System.getenv().getOrDefault("KINE_TRIPLE_BEAM", "4000"));
        String cat = System.getenv().getOrDefault("KINE_CATALOGUE", "/home/raymond/_/kine_path_2026-10-12/parkour_research/catalogue.json");
        for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(cat))).getAsJsonArray()) {
            JsonObject en = e.getAsJsonObject();
            String id = en.get("id").getAsString();
            if (!(ids.equals("all") ? id.startsWith("neo_triple") && !id.equals("neo_triple_trapdoors") : java.util.Arrays.asList(ids.split(",")).contains(id)))
                continue;
            JsonObject L = en.getAsJsonObject("layout");
            if (!Layer2StationTest.buildable(L)) { System.out.println("triple " + id + ": not buildable in the copy"); continue; }
            TestWorld w = Layer2StationTest.build(L);
            int wa = Integer.MAX_VALUE, wb = Integer.MIN_VALUE;
            for (JsonElement b : L.getAsJsonArray("blocks")) {
                JsonObject bo = b.getAsJsonObject();
                if (bo.get("dz").getAsInt() == 0 && bo.get("dy").getAsInt() >= 1) { wa = Math.min(wa, bo.get("dx").getAsInt()); wb = Math.max(wb, bo.get("dx").getAsInt()); }
            }
            wallA = wa; wallB = wb + 1;
            // the wall's faces in z, from the collision boxes of its blocks and what sticks out of it (trapdoors, panes),
            // over the floor, at the wall's x (a side wall at dz -1 aside)
            double za = Double.MAX_VALUE, zb = -Double.MAX_VALUE;
            for (JsonElement b : L.getAsJsonArray("blocks")) {
                JsonObject bo = b.getAsJsonObject();
                int dx = bo.get("dx").getAsInt(), dz = bo.get("dz").getAsInt();
                if (dx < wa || dx > wb || bo.get("dy").getAsInt() < 1 || dz < 0) continue;
                Kind k = Layer2StationTest.kindOf(bo.get("block").getAsString());
                for (double[] bx : k.boxes) { za = Math.min(za, dz + bx[2]); zb = Math.max(zb, dz + bx[5]); }
            }
            wallZ0 = za == Double.MAX_VALUE ? 0 : za; wallZ1 = zb == -Double.MAX_VALUE ? 1 : zb;
            landX = L.getAsJsonObject("landing").get("dx").getAsInt();
            landZ = L.getAsJsonObject("landing").get("dz").getAsInt();
            Layer2Sim sim = new Layer2Sim(w);
            GroundPhysics phys = sim.phys;
            JsonObject st = L.getAsJsonObject("start");
            double sx = st.get("dx").getAsInt() + 0.5, sz = st.get("dz").getAsInt() + 0.5;
            int sy = FLOOR + st.get("dy").getAsInt();
            for (int side : System.getenv("KINE_TRIPLE_SIDES") != null ? java.util.Arrays.stream(System.getenv("KINE_TRIPLE_SIDES").split(",")).mapToInt(Integer::parseInt).toArray()
                                                                      : new int[]{1, -1, 0}) {
                // from the start, facing the way (+x), standing; or from the back of the start block, its back edge
                for (double back : System.getenv("KINE_TRIPLE_BACK") != null ? new double[]{0.0} : new double[]{0.0, 0.2}) {
                    MoveState s0 = Layer2Sim.standing(sx - back, sy, sz);
                    search(id, side, back, phys, s0, width);
                }
            }
        }
    }

    static void search(String id, int side, double back, GroundPhysics phys, MoveState s0, int width) {
        long t0 = System.nanoTime();
        List<Node> beam = new ArrayList<>();
        beam.add(new Node(s0, -90f, new int[0], new float[0], wayLeft(s0.x, s0.z, side), false, s0.z, s0.z, Double.NaN));
        landedPlus = landedMinus = 0;
        int horizon = 45;
        long tried = 0;
        int landed = 0;
        Node best = beam.get(0), bestLanded = null;
        double bestMiss = Double.MAX_VALUE;
        for (int t = 0; t < horizon && !beam.isEmpty(); t++) {
            List<Node> next = new ArrayList<>();
            for (Node n : beam) {
                for (float turn : TURNS)
                    for (int k : KEYS)
                        for (int jump = 0; jump <= 1; jump++) {
                            if (jump == 1 && !n.s.onGround) continue;   // a jump key in the air changes nothing here
                            float yaw = n.yaw + turn;
                            int keys = k | ((k & MoveState.FORWARD) != 0 ? MoveState.SPRINT : 0) | (jump == 1 ? MoveState.JUMP : 0);
                            MoveState s = n.s.copy();
                            phys.step(s, keys, yaw, 0f);
                            tried++;
                            if (s.y < FLOOR - 1.5) continue;   // fell
                            int[] ks = java.util.Arrays.copyOf(n.keys, n.keys.length + 1);
                            ks[ks.length - 1] = keys;
                            float[] ys = java.util.Arrays.copyOf(n.yaws, n.yaws.length + 1);
                            ys[ys.length - 1] = yaw;
                            // landed: on the ground on the landing block (x 4..5, z 0..1), at its top
                            boolean onLanding = s.onGround && Math.abs(s.y - FLOOR) < 1e-6 && s.x + 0.3 > landX && s.x - 0.3 < landX + 1
                                && s.z + 0.3 > landZ && s.z - 0.3 < landZ + 1 && s.x > landX - 0.3;
                            double minZ = Math.min(n.minZ, s.z), maxZ = Math.max(n.maxZ, s.z);
                            // which side of the wall: the feet's z where they first pass the wall's middle (x)
                            double mid = (wallA + wallB) / 2;
                            double zMid = Double.isNaN(n.zMid) && n.s.x < mid && s.x >= mid ? s.z : n.zMid;
                            if (onLanding) {
                                landed++;
                                if (!Double.isNaN(zMid) && zMid < (wallZ0 + wallZ1) / 2) landedMinus++;
                                if (!Double.isNaN(zMid) && zMid >= (wallZ0 + wallZ1) / 2) landedPlus++;
                                if (bestLanded == null || ks.length < bestLanded.keys.length)
                                    bestLanded = new Node(s, yaw, ks, ys, 0, true, minZ, maxZ, zMid);
                                continue;
                            }
                            double way = wayLeft(s.x, s.z, side);
                            // the miss: where the feet come down through the landing's height, how far from its block
                            if (n.s.y >= FLOOR - 1e-6 && s.y < FLOOR - 1e-6 && !s.onGround) {
                                double mx = Math.max(0, Math.max(landX - (s.x + 0.3), (s.x - 0.3) - landX - 1));
                                double mz = Math.max(0, Math.max(landZ - (s.z + 0.3), (s.z - 0.3) - landZ - 1));
                                double miss = Math.hypot(mx, mz);
                                if (s.x > landX - 1 && miss < bestMiss) bestMiss = miss;
                            }
                            double speed = Math.hypot(s.vx, s.vz);
                            next.add(new Node(s, yaw, ks, ys, way - 3.0 * speed, false, minZ, maxZ, zMid));
                        }
            }
            next.sort(Comparator.comparingDouble(Node::score));
            // keep the best, a few of each place apart (no near copies)
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
            if (!beam.isEmpty() && beam.get(0).score < best.score) best = beam.get(0);
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf(Locale.ROOT, "triple %s heuristic %s back %.1f (wall z %.2f..%.2f): %s; landed %d of %d tried (round -z %d, round +z %d); best miss %.3f blocks; %.0f ms%n",
            id, side > 0 ? "+z" : side < 0 ? "-z" : "straight", back, wallZ0, wallZ1, bestLanded != null ? "LANDS in " + bestLanded.keys.length + " ticks" : "no landing", landed, tried, landedMinus, landedPlus,
            bestMiss == Double.MAX_VALUE ? Double.NaN : bestMiss, ms);
        if (bestLanded != null) {
            StringBuilder sb = new StringBuilder("   inputs (yaw keys):");
            for (int i = 0; i < bestLanded.keys.length; i++) sb.append(String.format(Locale.ROOT, " %.0f/%d", bestLanded.yaws[i], bestLanded.keys[i]));
            System.out.println(sb);
        }
    }
}
