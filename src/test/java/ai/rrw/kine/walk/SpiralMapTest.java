package ai.rrw.kine.walk;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The walk's planners on Hielke's Parkour Spiral, offline (SpiralMap; skipped without KINE_SPIRAL_TSV, the scan's
 * tower.tsv, and KINE_SPIRAL_CPS, its checkpoints.json): for each leg (checkpoint k to k + 1, the destination's Y the
 * next plate's feet), what the proven-moves search finds and what the loosened one finds, its loose moves, sizes and
 * times. An exploration, not an assertion: the development loop the map is (as the Kine Parkour course was).
 */
public class SpiralMapTest {
    static SpiralMap map;
    static View view;

    static void load() throws Exception {
        String tsv = System.getenv("KINE_SPIRAL_TSV");
        Assumptions.assumeTrue(tsv != null && System.getenv("KINE_SPIRAL_CPS") != null, "KINE_SPIRAL_TSV and KINE_SPIRAL_CPS not set");
        if (map != null) return;
        long t0 = System.nanoTime();
        map = SpiralMap.load(Path.of(tsv));
        long t1 = System.nanoTime();
        view = map.view();
        System.out.printf(Locale.ROOT, "spiral map: loaded in %.1f s, table in %.1f s, %d unparsed states %s%n", (t1 - t0) / 1e9,
            (System.nanoTime() - t1) / 1e9, map.unparsed, map.unparsedNames);
    }

    static List<SpiralMap.Cp> cps() throws Exception {
        return SpiralMap.checkpoints(Path.of(System.getenv("KINE_SPIRAL_CPS")));
    }

    /** The walk's costs for the bench: layer 2's, with KINE_SPIRAL_FALL_HP points of fall damage allowed (the "Fall
     *  damage allowed" setting; its fall limits rise by as much). */
    static Costs costs() {
        String hp = System.getenv("KINE_SPIRAL_FALL_HP");
        Costs c = hp == null ? Costs.LAYER2 : Costs.withFallDamage(Costs.LAYER2, Integer.parseInt(hp));
        Recipe.safeFall = c.safeFall;   // the checks' jump search (the walk sets it at each plan)
        return c;
    }

    /** The floors of the columns in KINE_SPIRAL_COLUMNS ("x,z;x,z") and every move out of each (the graph with loose
     *  moves): for finding why a way breaks where the reach sets part. */
    @Test
    void columns() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINE_SPIRAL_COLUMNS") != null, "KINE_SPIRAL_COLUMNS not set");
        load();
        Graph g = new Graph(view, costs());
        g.gaps = true;
        g.loose = true;
        for (String part : System.getenv("KINE_SPIRAL_COLUMNS").split(";")) {
            String[] xz = part.split(",");
            int x = Integer.parseInt(xz[0].trim()), z = Integer.parseInt(xz[1].trim());
            ChunkFloors c = view.chunk(x, z);
            int cnt = c == null ? 0 : c.count(x & 15, z & 15);
            System.out.printf(Locale.ROOT, "column %d,%d: %d floors%n", x, z, cnt);
            for (int j = 0; j < cnt; j++) {
                int idx = c.index(x & 15, z & 15, j);
                long n = View.node(x, z, j);
                g.expand(n);
                StringBuilder sb = new StringBuilder();
                for (int e = 0; e < g.n; e++) {
                    if (g.to[e] == Long.MIN_VALUE) continue;
                    long t = g.to[e];
                    ChunkFloors tc = view.chunk(View.nodeX(t), View.nodeZ(t));
                    sb.append(String.format(Locale.ROOT, " %s>%d,%.2f,%d", String.valueOf(g.move[e]), View.nodeX(t), tc.h(view.index(t)), View.nodeZ(t)));
                }
                System.out.printf(Locale.ROOT, "  floor %d: h=%.3f type=%d flags=%d head=%.2f kind=%s moves:%s%n", j, c.h(idx), c.type(idx), c.flags(idx),
                    c.head(idx), Palette.get(c.window(idx, 0)).name, sb);
            }
        }
    }

    /** The index in cps of checkpoint number n (a secret checkpoint isn't in cps). */
    static int index(List<SpiralMap.Cp> cps, int n) {
        for (int i = 0; i < cps.size(); i++) if (cps.get(i).n() == n) return i;
        throw new IllegalArgumentException("no checkpoint " + n);
    }

    /** Whether the leg from checkpoint number n is asked for (KINE_SPIRAL_LEGS). */
    static boolean wanted(int k) {
        String s = System.getenv("KINE_SPIRAL_LEGS");
        if (s == null || s.equals("all")) return true;
        for (String part : s.split(",")) {
            int dash = part.indexOf('-', 1);
            if (dash > 0) { if (k >= Integer.parseInt(part.substring(0, dash)) && k <= Integer.parseInt(part.substring(dash + 1))) return true; }
            else if (!part.isBlank() && Integer.parseInt(part.trim()) == k) return true;
        }
        return false;
    }

    static String describe(PathSearch.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.found ? "FOUND" : r.nearest ? "nearest" : r.partial ? "partial" : r.exhausted ? "exhausted" : "none");
        sb.append(String.format(Locale.ROOT, " cost=%.1f expanded=%d generated=%d ms=%.1f", r.cost, r.expanded, r.generated, r.nanos / 1e6));
        int loose = 0, gaps = 0, jumps = 0;
        for (int i = 1; i < r.moves.length; i++) {
            if (r.moves[i] == Graph.LOOSE) loose++;
            if (r.moves[i] == Graph.GAP) gaps++;
            if (r.moves[i] == Graph.JUMP) jumps++;
        }
        sb.append(" steps=").append(r.path.length).append(" loose=").append(loose).append(" gaps=").append(gaps).append(" jumps=").append(jumps);
        if (r.path.length > 0) {
            long e = r.path[r.path.length - 1];
            sb.append(String.format(Locale.ROOT, " end=%d,%.2f,%d", View.nodeX(e), r.pathY[r.pathY.length - 1], View.nodeZ(e)));
        }
        return sb.toString();
    }

    static String looseSteps(PathSearch.Result r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < r.moves.length; i++) {
            if (r.moves[i] != Graph.LOOSE) continue;
            long a = r.path[i - 1], b = r.path[i];
            sb.append(String.format(Locale.ROOT, " [%d,%.2f,%d -> %d,%.2f,%d d=%.2f]", View.nodeX(a), r.pathY[i - 1], View.nodeZ(a),
                View.nodeX(b), r.pathY[i], View.nodeZ(b), Math.hypot(View.nodeX(b) - View.nodeX(a), View.nodeZ(b) - View.nodeZ(a))));
        }
        return sb.toString();
    }

    /** Each leg: the proven-moves search (Parkour on: gaps), then the loosened one. */
    @Test
    void strictThenLoose() throws Exception {
        load();
        List<SpiralMap.Cp> cps = cps();
        Costs c = costs();
        long budget = Long.parseLong(System.getenv().getOrDefault("KINE_SPIRAL_BUDGET_MS", "2000")) * 1_000_000L;
        int strictFound = 0, looseFound = 0, legs = 0;
        for (int k = 0; k + 1 < cps.size(); k++) {
            if (!wanted(cps.get(k).n())) continue;
            legs++;
            SpiralMap.Cp a = cps.get(k), b = cps.get(k + 1);
            long start = view.nodeAt(a.x(), a.y(), a.z());
            if (start == View.NONE) { System.out.println("leg " + k + ": no floor at the start"); continue; }
            PathSearch strict = new PathSearch(view, c).canSprint(true).gaps(true).goalY(b.y());
            PathSearch.Result rs = strict.run(start, b.x(), b.z(), 2.0, System.nanoTime() + budget, 2_000_000, false);
            PathSearch loose = new PathSearch(view, c).canSprint(true).gaps(true).loose(true).goalY(b.y());
            PathSearch.Result rl = loose.run(start, b.x(), b.z(), 2.0, System.nanoTime() + budget, 2_000_000, false);
            if (rs.found) strictFound++;
            if (rl.found) looseFound++;
            System.out.printf(Locale.ROOT, "leg %2d (%d,%d,%d -> %d,%d,%d): strict %s | loose %s%s%n", a.n(), a.x(), a.y(), a.z(), b.x(), b.y(), b.z(),
                describe(rs), describe(rl), looseSteps(rl));
        }
        System.out.printf(Locale.ROOT, "legs %d: strict found %d, loose found %d%n", legs, strictFound, looseFound);
    }

    /** Each leg: the loosened search with its loose moves checked in the movement copy, a failing one blocked and the
     *  search run again (LazySP), until a route's loose moves all have a way, no route is left, or the leg's time runs
     *  out (KINE_SPIRAL_CHECK_S, default 60 s). */
    @Test
    void looseChecked() throws Exception {
        load();
        Assumptions.assumeTrue(System.getenv("KINE_SPIRAL_CHECK") != null, "KINE_SPIRAL_CHECK not set");
        List<SpiralMap.Cp> cps = cps();
        Costs c = costs();
        long legBudget = Long.parseLong(System.getenv().getOrDefault("KINE_SPIRAL_CHECK_S", "60")) * 1_000_000_000L;
        ai.rrw.kine.move.Body body = new ai.rrw.kine.move.Body();
        LooseCheck.Grids grids = (x, y, z, r, down, up) -> map.grid(x - r, y - down, z - r, 2 * r + 1, down + up + 1, 2 * r + 1);
        int legs = 0, made = 0;
        for (int k = 0; k + 1 < cps.size(); k++) {
            if (!wanted(cps.get(k).n())) continue;
            legs++;
            SpiralMap.Cp a = cps.get(k), b = cps.get(k + 1);
            long start = view.nodeAt(a.x(), a.y(), a.z());
            it.unimi.dsi.fastutil.longs.LongOpenHashSet blocked = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            LooseCheck.Memo memo = new LooseCheck.Memo();
            long t0 = System.nanoTime();
            int iters = 0, checks = 0, doubts = 0;
            String verdict = "time";
            PathSearch.Result last = null;
            StringBuilder failedMoves = new StringBuilder();
            it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap pen = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
            while (System.nanoTime() - t0 < legBudget) {
                iters++;
                PathSearch.Result r = new PathSearch(view, c).canSprint(true).gaps(true).loose(true).blocked(blocked).penalties(pen)
                    .doors(true).goalY(b.y())
                    .run(start, b.x(), b.z(), 2.0, System.nanoTime() + 2_000_000_000L, 2_000_000, false);
                last = r;
                if (!r.found) { verdict = "no route (" + describe(r) + ")"; break; }
                double bound = Loose.costBound(c, start, b.x(), b.z(), b.y(), view);
                if (r.cost > bound) { verdict = String.format(Locale.ROOT, "too long (%.0f > %.0f)", r.cost, bound); break; }
                int[] n = {0};
                long deadline = t0 + legBudget;
                java.util.Set<Long> priced = new java.util.HashSet<>();
                for (long e : pen.keySet()) priced.add(e);
                LooseCheck.RouteVerdict rv = LooseCheck.checkRoute(r, grids, body, memo, 0, true, () -> System.nanoTime() > deadline, i -> n[0]++, priced);
                int fail = rv.index();
                checks += n[0];
                if (rv.isDoubtful()) {
                    for (long e : rv.doubtful()) pen.put(e, Planner.DOUBT);
                    for (long e : Loose.family(view, r.path[fail - 1], r.path[fail])) pen.put(e, Planner.DOUBT);
                    doubts++;
                    continue;
                }
                if (fail == -1) { verdict = "CHECKED"; made++; break; }
                if (fail == -2) break;
                for (long e : Loose.family(view, r.path[fail - 1], r.path[fail])) blocked.add(e);
                if (failedMoves.length() < 600)
                    failedMoves.append(String.format(Locale.ROOT, " [%d,%.2f,%d->%d,%.2f,%d]", View.nodeX(r.path[fail - 1]), r.pathY[fail - 1],
                        View.nodeZ(r.path[fail - 1]), View.nodeX(r.path[fail]), r.pathY[fail], View.nodeZ(r.path[fail])));
            }
            long moving = memo.found.values().stream().filter(LooseCheck.Verdict::moving).count();
            System.out.printf(Locale.ROOT, "leg %2d: %s iters=%d doubts=%d blocked=%d memo ok=%d (moving starts %d) failed=%d s=%.1f final: %s%s%n failed:%s%n", cps.get(k).n(), verdict, iters,
                doubts, blocked.size(), memo.found.size(), moving, memo.failed.size(), (System.nanoTime() - t0) / 1e9, last == null ? "-" : describe(last),
                last == null ? "" : looseSteps(last), failedMoves);
        }
        System.out.printf(Locale.ROOT, "legs %d: checked routes %d%n", legs, made);
    }

    /** What the loosened graph reaches from a leg's start (KINE_SPIRAL_REACH_LEG), every floor within 40 blocks, written to
     *  KINE_SPIRAL_REACH_OUT as "x y z cost": for drawing where a leg's way runs out. */
    @Test
    void reach() throws Exception {
        load();
        String legEnv = System.getenv("KINE_SPIRAL_REACH_LEG");
        Assumptions.assumeTrue(legEnv != null, "KINE_SPIRAL_REACH_LEG not set");
        List<SpiralMap.Cp> cps = cps();
        SpiralMap.Cp a = cps.get(index(cps, Integer.parseInt(legEnv)));
        String out = reachFrom(a, Double.parseDouble(System.getenv().getOrDefault("KINE_SPIRAL_REACH_R", "40")),
            System.getenv("KINE_SPIRAL_REACH_STRICT") == null);
        java.nio.file.Files.writeString(Path.of(System.getenv().getOrDefault("KINE_SPIRAL_REACH_OUT", "/tmp/spiral_reach.txt")), out);
    }

    /** The floors reachable from checkpoint a within radius (loose moves too, unless strict), as lines "x y z cost". */
    static String reachFrom(SpiralMap.Cp a, double radius, boolean loose) {
        long start = view.nodeAt(a.x(), a.y(), a.z());
        Graph g = new Graph(view, costs());
        g.gaps = true;
        g.loose = loose;
        it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap dist = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
        java.util.PriorityQueue<double[]> q = new java.util.PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));
        dist.put(start, 0);
        q.add(new double[]{0, Double.longBitsToDouble(start)});
        StringBuilder out = new StringBuilder();
        while (!q.isEmpty()) {
            double[] e = q.poll();
            long n = Double.doubleToRawLongBits(e[1]);
            if (e[0] > dist.get(n)) continue;
            int x = View.nodeX(n), z = View.nodeZ(n);
            if (Math.hypot(x - a.x(), z - a.z()) > radius) continue;
            ChunkFloors c = view.chunk(x, z);
            out.append(x).append(' ').append(c.h(view.index(n))).append(' ').append(z).append(' ').append(String.format(Locale.ROOT, "%.1f", e[0])).append('\n');
            g.expand(n);
            long[] to = java.util.Arrays.copyOf(g.to, g.n);
            double[] cost = java.util.Arrays.copyOf(g.cost, g.n);
            for (int i = 0; i < to.length; i++) {
                if (to[i] == Long.MIN_VALUE) continue;
                double nd = e[0] + cost[i];
                if (nd < dist.getOrDefault(to[i], Double.MAX_VALUE)) { dist.put(to[i], nd); q.add(new double[]{nd, Double.longBitsToDouble(to[i])}); }
            }
        }
        System.out.println("reach: " + dist.size() + " floors");
        return out.toString();
    }


    /** Every jump of a leg's route (KINE_SPIRAL_JUMPS_LEGS; the strict search's, else the loose one's): JUMP, GAP and
     *  LOOSE moves each checked from standing at its take-off (as the walk's stall search does), the ones without a way
     *  listed. Proven moves can fail too: a jump up onto a carpet on a cactus beside it. */
    @Test
    void checkJumps() throws Exception {
        load();
        Assumptions.assumeTrue(System.getenv("KINE_SPIRAL_JUMPS_LEGS") != null, "KINE_SPIRAL_JUMPS_LEGS not set");
        List<SpiralMap.Cp> cps = cps();
        ai.rrw.kine.move.Body body = new ai.rrw.kine.move.Body();
        LooseCheck.Grids grids = (x, y, z, r, down, up) -> map.grid(x - r, y - down, z - r, 2 * r + 1, down + up + 1, 2 * r + 1);
        for (String part : System.getenv("KINE_SPIRAL_JUMPS_LEGS").split(",")) {
            int k = index(cps, Integer.parseInt(part.trim()));
            SpiralMap.Cp a = cps.get(k), b = cps.get(k + 1);
            long start = view.nodeAt(a.x(), a.y(), a.z());
            PathSearch.Result r = new PathSearch(view, costs()).canSprint(true).gaps(true).goalY(b.y())
                .run(start, b.x(), b.z(), 2.0, Long.MAX_VALUE, 2_000_000, false);
            if (!r.found) r = new PathSearch(view, costs()).canSprint(true).gaps(true).loose(true).goalY(b.y())
                .run(start, b.x(), b.z(), 2.0, Long.MAX_VALUE, 2_000_000, false);
            StringBuilder sb = new StringBuilder();
            int n = 0, bad = 0;
            for (int i = 1; i < r.path.length; i++) {
                int mv = r.moves[i];
                if (mv != Graph.JUMP && mv != Graph.GAP && mv != Graph.LOOSE) continue;
                n++;
                long ta = r.path[i - 1], tb = r.path[i];
                LooseCheck.Verdict v = LooseCheck.check(grids, body, null, Float.NaN, View.nodeX(ta), r.pathY[i - 1], View.nodeZ(ta),
                    View.nodeX(tb), r.pathY[i], View.nodeZ(tb), true, () -> false);   // the full check
                if (!v.ok()) {
                    bad++;
                    sb.append(String.format(Locale.ROOT, " [%s %d,%.2f,%d -> %d,%.2f,%d]", Graph.NAMES[mv], View.nodeX(ta), r.pathY[i - 1],
                        View.nodeZ(ta), View.nodeX(tb), r.pathY[i], View.nodeZ(tb)));
                }
            }
            System.out.printf(Locale.ROOT, "leg %d: %s route, %d jumps, %d without a way:%s%n", a.n(), r.found ? "a" : "no", n, bad, sb);
        }
    }

    /** The floors from which a leg's goal can be reached (KINE_SPIRAL_BACK_LEG: the goal of that leg; within
     *  KINE_SPIRAL_REACH_R of it), by the loosened graph's moves, backward: written to KINE_SPIRAL_REACH_OUT as "x y z
     *  steps". With reach(), where a leg's way breaks shows as the gap between the two sets. */
    @Test
    void reachBack() throws Exception {
        load();
        String legEnv = System.getenv("KINE_SPIRAL_BACK_LEG");
        Assumptions.assumeTrue(legEnv != null, "KINE_SPIRAL_BACK_LEG not set");
        List<SpiralMap.Cp> all = cps();
        SpiralMap.Cp b = all.get(index(all, Integer.parseInt(legEnv)) + 1);
        String out = reachTo(b, Double.parseDouble(System.getenv().getOrDefault("KINE_SPIRAL_REACH_R", "25")));
        java.nio.file.Files.writeString(Path.of(System.getenv().getOrDefault("KINE_SPIRAL_REACH_OUT", "/tmp/spiral_back.txt")), out);
    }

    /** Both reach sets of each leg in KINE_SPIRAL_GAP_LEGS (checkpoint numbers): /tmp/spiral_gap_<n>_fw.txt (from its
     *  start, KINE_SPIRAL_REACH_R, default 40) and _bw.txt (to its goal, KINE_SPIRAL_BACK_R, default 25); tools/gap.py
     *  shows where they come closest. */
    @Test
    void gaps() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINE_SPIRAL_GAP_LEGS") != null, "KINE_SPIRAL_GAP_LEGS not set");
        load();
        List<SpiralMap.Cp> all = cps();
        for (String part : System.getenv("KINE_SPIRAL_GAP_LEGS").split(",")) {
            int n = Integer.parseInt(part.trim());
            int k = index(all, n);
            java.nio.file.Files.writeString(Path.of("/tmp/spiral_gap_" + n + "_fw.txt"),
                reachFrom(all.get(k), Double.parseDouble(System.getenv().getOrDefault("KINE_SPIRAL_REACH_R", "40")), true));
            java.nio.file.Files.writeString(Path.of("/tmp/spiral_gap_" + n + "_bw.txt"),
                reachTo(all.get(k + 1), Double.parseDouble(System.getenv().getOrDefault("KINE_SPIRAL_BACK_R", "25"))));
        }
    }

    /** The floors within radius of checkpoint b from which it can be reached (loose moves too), as lines "x y z steps". */
    static String reachTo(SpiralMap.Cp b, double radius) {
        long goal = view.nodeAt(b.x(), b.y(), b.z());
        Graph g = new Graph(view, costs());
        g.gaps = true;
        g.loose = true;
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.longs.LongArrayList> back = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        int r = (int) Math.ceil(radius);
        for (int x = b.x() - r; x <= b.x() + r; x++)
            for (int z = b.z() - r; z <= b.z() + r; z++) {
                if (Math.hypot(x - b.x(), z - b.z()) > radius) continue;
                ChunkFloors c = view.chunk(x, z);
                if (c == null) continue;
                for (int j = 0; j < c.count(x & 15, z & 15); j++) {
                    long n = View.node(x, z, j);
                    g.expand(n);
                    for (int e = 0; e < g.n; e++) {
                        if (g.to[e] == Long.MIN_VALUE) continue;
                        back.computeIfAbsent(g.to[e], kk -> new it.unimi.dsi.fastutil.longs.LongArrayList()).add(n);
                    }
                }
            }
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap steps = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        java.util.ArrayDeque<Long> q = new java.util.ArrayDeque<>();
        steps.put(goal, 0);
        q.add(goal);
        StringBuilder out = new StringBuilder();
        while (!q.isEmpty()) {
            long n = q.poll();
            int sN = steps.get(n);
            ChunkFloors c = view.chunk(View.nodeX(n), View.nodeZ(n));
            out.append(View.nodeX(n)).append(' ').append(c.h(view.index(n))).append(' ').append(View.nodeZ(n)).append(' ').append(sN).append('\n');
            var from = back.get(n);
            if (from == null) continue;
            for (int kk = 0; kk < from.size(); kk++) {
                long m = from.getLong(kk);
                if (steps.containsKey(m)) continue;
                steps.put(m, sN + 1);
                q.add(m);
            }
        }
        System.out.println("reach back: " + steps.size() + " floors");
        return out.toString();
    }

}
