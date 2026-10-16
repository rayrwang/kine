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

    /** The walk table's floors in a box (KINE_SPIRAL_FLOORS "x0,x1,z0,z1,y0,y1"), one line per floor: "x h z type kind
     *  head" (helper B, 2026-10-16: the planner's own view of a level, heights from the collision shapes). */
    @Test
    void floors() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINE_SPIRAL_FLOORS") != null, "KINE_SPIRAL_FLOORS not set");
        load();
        String[] b = System.getenv("KINE_SPIRAL_FLOORS").split(",");
        int x0 = Integer.parseInt(b[0].trim()), x1 = Integer.parseInt(b[1].trim()), z0 = Integer.parseInt(b[2].trim()), z1 = Integer.parseInt(b[3].trim());
        double y0 = Double.parseDouble(b[4].trim()), y1 = Double.parseDouble(b[5].trim());
        StringBuilder sb = new StringBuilder();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                ChunkFloors c = view.chunk(x, z);
                if (c == null) continue;
                for (int j = 0; j < c.count(x & 15, z & 15); j++) {
                    int idx = c.index(x & 15, z & 15, j);
                    if (c.h(idx) < y0 || c.h(idx) > y1) continue;
                    sb.append(String.format(Locale.ROOT, "%d %.4f %d %d %s %.2f%n", x, c.h(idx), z, c.type(idx),
                        Palette.get(c.window(idx, 0)).name.replace(' ', '_'), c.head(idx)));
                }
            }
        java.nio.file.Files.writeString(Path.of(System.getenv().getOrDefault("KINE_SPIRAL_FLOORS_OUT", "/tmp/spiral_floors.txt")), sb);
    }

    /** The loosened search's route (unchecked) from a leg's start to a floor (KINE_SPIRAL_ROUTE_TO "leg;x,y,z;..." ,
     *  each target its own search), every step printed (helper B, 2026-10-16: how the graph reaches a place). */
    @Test
    void routeTo() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINE_SPIRAL_ROUTE_TO") != null, "KINE_SPIRAL_ROUTE_TO not set");
        load();
        List<SpiralMap.Cp> cps = cps();
        String[] parts = System.getenv("KINE_SPIRAL_ROUTE_TO").split(";");
        SpiralMap.Cp a = cps.get(index(cps, Integer.parseInt(parts[0].trim())));
        long start = view.nodeAt(a.x(), a.y(), a.z());
        for (int i = 1; i < parts.length; i++) {
            String[] t = parts[i].split(",");
            int x = Integer.parseInt(t[0].trim()), z = Integer.parseInt(t[2].trim());
            double y = Double.parseDouble(t[1].trim());
            PathSearch.Result r = new PathSearch(view, costs()).canSprint(true).gaps(true).loose(true).doors(true).goalY(y)
                .run(start, x, z, 1.0, System.nanoTime() + 5_000_000_000L, 4_000_000, false);
            System.out.println("to " + parts[i].trim() + ": " + describe(r) + "\n  " + steps(r));
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

    /** Every step of a route: its move and floor. */
    static String steps(PathSearch.Result r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.path.length; i++)
            sb.append(String.format(Locale.ROOT, " %s>%d,%.2f,%d", i == 0 ? "start" : Graph.NAMES[r.moves[i]], View.nodeX(r.path[i]), r.pathY[i],
                View.nodeZ(r.path[i])));
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
        String failedPath = System.getenv("KINE_SPIRAL_FAILED_OUT");
        StringBuilder failedOut = failedPath == null ? null : new StringBuilder("leg\titer\tax\tay\taz\tbx\tby\tbz\ts\n");
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
                LooseCheck.RouteVerdict rv = LooseCheck.checkRoute(r, grids, body, memo, 0, true, () -> System.nanoTime() > deadline, i -> n[0]++,
                    doubts >= Planner.DOUBT_ROUNDS && System.getenv("KINE_SPIRAL_NO_ESCALATE") == null ? Planner.EVERY : priced);
                int fail = rv.index();
                checks += n[0];
                if (rv.isDoubtful()) {
                    for (long e : rv.doubtful()) pen.put(e, Planner.DOUBT);
                    for (long e : Loose.family(view, r.path[fail - 1], r.path[fail])) pen.put(e, Planner.DOUBT);
                    doubts++;
                    continue;
                }
                if (fail == -1) {
                    verdict = "CHECKED";
                    made++;
                    if (System.getenv("KINE_SPIRAL_PRINT_ROUTE") != null) System.out.println("route of leg " + cps.get(k).n() + ":" + steps(r));
                    break;
                }
                if (fail == -2) break;
                for (long e : Loose.family(view, r.path[fail - 1], r.path[fail])) blocked.add(e);
                if (System.getenv("KINE_SPIRAL_NO_LANDING") == null) {
                    Planner.landingFailed(view, memo, blocked, r.path[fail]);
                    if (System.getenv("KINE_SPIRAL_NO_AREA") == null) {
                        long ak = Loose.area(view, r.path[fail - 1], r.path[fail]);
                        if (ak != 0L && memo.areaFails.merge(ak, 1, Integer::sum) >= Planner.AREA_FAILS) blocked.add(ak);
                    }
                }
                if (failedMoves.length() < 600)
                    failedMoves.append(String.format(Locale.ROOT, " [%d,%.2f,%d->%d,%.2f,%d]", View.nodeX(r.path[fail - 1]), r.pathY[fail - 1],
                        View.nodeZ(r.path[fail - 1]), View.nodeX(r.path[fail]), r.pathY[fail], View.nodeZ(r.path[fail])));
                if (failedOut != null)
                    failedOut.append(String.format(Locale.ROOT, "%d\t%d\t%d\t%.3f\t%d\t%d\t%.3f\t%d\t%.2f\n", cps.get(k).n(), iters,
                        View.nodeX(r.path[fail - 1]), r.pathY[fail - 1], View.nodeZ(r.path[fail - 1]), View.nodeX(r.path[fail]), r.pathY[fail],
                        View.nodeZ(r.path[fail]), (System.nanoTime() - t0) / 1e9));
            }
            if (System.getenv("KINE_SPIRAL_PRINT_ROUTE") != null && last != null && !verdict.equals("CHECKED"))
                System.out.println("last route of leg " + cps.get(k).n() + ":" + steps(last));
            long moving = memo.found.values().stream().filter(LooseCheck.Verdict::moving).count();
            System.out.printf(Locale.ROOT, "leg %2d: %s iters=%d doubts=%d blocked=%d memo ok=%d (moving starts %d) failed=%d s=%.1f final: %s%s%n failed:%s%n", cps.get(k).n(), verdict, iters,
                doubts, blocked.size(), memo.found.size(), moving, memo.failed.size(), (System.nanoTime() - t0) / 1e9, last == null ? "-" : describe(last),
                last == null ? "" : looseSteps(last), failedMoves);
        }
        System.out.printf(Locale.ROOT, "legs %d: checked routes %d%n", legs, made);
        if (failedOut != null) java.nio.file.Files.writeString(Path.of(failedPath), failedOut);
    }

    /** Layer 2's field on a leg's checked route (KINE_SPIRAL_FIELD_LEG), built with the player at each of
     *  KINE_SPIRAL_FIELD_AT ("x,y,z;..."): the window, and for every floor in KINE_SPIRAL_FIELD_BOX ("x0,x1,z0,z1,y0,y1")
     *  its time, next floor and move. For finding why layer 2 heads back (the Parkour Spiral's leg 38: between two pillars). */
    @Test
    void field() throws Exception {
        load();
        String legS = System.getenv("KINE_SPIRAL_FIELD_LEG");
        Assumptions.assumeTrue(legS != null, "KINE_SPIRAL_FIELD_LEG not set");
        int leg = Integer.parseInt(legS);
        List<SpiralMap.Cp> cps = cps();
        Costs c = costs();
        ai.rrw.kine.move.Body body = new ai.rrw.kine.move.Body();
        LooseCheck.Grids grids = (x, y, z, r, down, up) -> map.grid(x - r, y - down, z - r, 2 * r + 1, down + up + 1, 2 * r + 1);
        int k = 0;
        while (k + 1 < cps.size() && cps.get(k).n() != leg) k++;
        SpiralMap.Cp a = cps.get(k), b = cps.get(k + 1);
        long start = view.nodeAt(a.x(), a.y(), a.z());
        it.unimi.dsi.fastutil.longs.LongOpenHashSet blocked = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap pen = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
        LooseCheck.Memo memo = new LooseCheck.Memo();
        PathSearch.Result r = null;
        long t0 = System.nanoTime();
        int doubts = 0;
        while (System.nanoTime() - t0 < 90_000_000_000L) {
            r = new PathSearch(view, c).canSprint(true).gaps(true).loose(true).blocked(blocked).penalties(pen).doors(true).goalY(b.y())
                .run(start, b.x(), b.z(), 2.0, System.nanoTime() + 2_000_000_000L, 2_000_000, false);
            if (!r.found) break;
            java.util.Set<Long> priced = new java.util.HashSet<>(pen.keySet());
            LooseCheck.RouteVerdict rv = LooseCheck.checkRoute(r, grids, body, memo, 0, true, () -> false, i -> { },
                doubts >= Planner.DOUBT_ROUNDS ? Planner.EVERY : priced);
            int fail = rv.index();
            if (rv.isDoubtful()) {
                for (long e : rv.doubtful()) pen.put(e, Planner.DOUBT);
                for (long e : Loose.family(view, r.path[fail - 1], r.path[fail])) pen.put(e, Planner.DOUBT);
                doubts++;
                continue;
            }
            if (fail < 0) break;
            for (long e : Loose.family(view, r.path[fail - 1], r.path[fail])) blocked.add(e);
        }
        System.out.printf(Locale.ROOT, "leg %d route: %s%s%n", leg, describe(r), looseSteps(r));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.path.length; i++)
            sb.append(String.format(Locale.ROOT, " %d:%d,%.2f,%d/%d", i, View.nodeX(r.path[i]), r.pathY[i], View.nodeZ(r.path[i]), r.moves[i]));
        System.out.println("path:" + sb);
        Route route = new Tighten(view, c, 0.3, true).route(r, b.x(), b.z());
        int[] box = java.util.Arrays.stream(System.getenv().getOrDefault("KINE_SPIRAL_FIELD_BOX", "-30,-10,-46,-30,193,201").split(","))
            .mapToInt(Integer::parseInt).toArray();
        for (String at : System.getenv().getOrDefault("KINE_SPIRAL_FIELD_AT", "-19.5,194,-39.5").split(";")) {
            String[] q = at.split(",");
            double x = Double.parseDouble(q[0]), y = Double.parseDouble(q[1]), z = Double.parseDouble(q[2]);
            Field f = Field.build(view, c, true, true, false, 1L, r.path, r, route, x, y, z, b.x(), b.z(), b.y(), true);
            int me = f.nodeAt(x, y, z);
            System.out.printf(Locale.ROOT, "field at %s: from %d to %d, own floor %s value %.1f at() %.1f%n", at, f.from, f.to,
                me < 0 ? "-" : String.format(Locale.ROOT, "%.1f,%.2f,%.1f", f.px[me], f.py[me], f.pz[me]), me < 0 ? Double.NaN : f.value[me], f.at(x, y, z));
            for (int i = 0; i < f.n; i++) {
                if (f.px[i] < box[0] || f.px[i] > box[1] + 1 || f.pz[i] < box[2] || f.pz[i] > box[3] + 1 || f.py[i] < box[4] || f.py[i] > box[5]) continue;
                int nx = f.next[i];
                System.out.printf(Locale.ROOT, "  %.1f,%.2f,%.1f t%d v=%.1f next=%s mv=%d%n", f.px[i], f.py[i], f.pz[i], f.type[i], f.value[i],
                    nx < 0 ? "-" : String.format(Locale.ROOT, "%.1f,%.2f,%.1f", f.px[nx], f.py[nx], f.pz[nx]), f.nextMove[i]);
            }
        }
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

    /** The moves in KINE_SPIRAL_MOVES ("ax,ay,az>bx,by,bz;...", feet heights) each checked in the movement copy from
     *  standing at the take-off's centre: the quick check (the jump search from there) and the full one (spots, run-ups,
     *  templates), with the way found (helper B, 2026-10-16: for asking why one loose move fails). */
    @Test
    void checkMoves() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINE_SPIRAL_MOVES") != null, "KINE_SPIRAL_MOVES not set");
        load();
        costs();
        ai.rrw.kine.move.Body body = new ai.rrw.kine.move.Body();
        LooseCheck.Grids grids = (x, y, z, r, down, up) -> map.grid(x - r, y - down, z - r, 2 * r + 1, down + up + 1, 2 * r + 1);
        for (String part : System.getenv("KINE_SPIRAL_MOVES").split(";")) {
            if (part.isBlank()) continue;
            String[] ab = part.split(">");
            String[] a = ab[0].split(","), b = ab[1].split(",");
            int tx = Integer.parseInt(a[0].trim()), tz = Integer.parseInt(a[2].trim()), lx = Integer.parseInt(b[0].trim()), lz = Integer.parseInt(b[2].trim());
            double th = Double.parseDouble(a[1].trim()), lh = Double.parseDouble(b[1].trim());
            Graph fg = new Graph(view, costs());
            StringBuilder known = new StringBuilder();
            int y1 = (int) Math.floor(Math.max(th, lh) + 0.5);
            for (int[] c : new int[][]{{tx, tz}, {lx, lz}, {(tx + lx) >> 1, (tz + lz) >> 1}}) {
                Kind kk = view.blockKnown(c[0], y1, c[1]);
                known.append(String.format(Locale.ROOT, " %d,%d,%d=%s", c[0], y1, c[1], kk == null ? "null" : kk.name));
            }
            long ln = view.nodeAt(lx, lh, lz);
            String descent = "no landing floor";
            if (ln != View.NONE) {
                ChunkFloors lc = view.chunk(lx, lz);
                int li = view.index(ln);
                double ceil = lc.open(li) ? Double.POSITIVE_INFINITY : lc.h(li) + lc.head(li);
                descent = String.format(Locale.ROOT, "descent %s (ceiling %.2f)", fg.descentOpen(tx, tz, th, lx, lz, lc.h(li), ceil) ? "open" : "blocked", ceil);
            }
            System.out.printf(Locale.ROOT, "move %s: flight line %s; %s;%s%n", part.trim(), fg.flightOpen(tx, tz, th, lx, lz, lh) ? "open" : "blocked",
                descent, known);
            if (System.getenv("KINE_SPIRAL_FLIGHT_ONLY") != null) continue;
            for (boolean quick : new boolean[]{true, false}) {
                LooseCheck.Verdict v = LooseCheck.check(grids, body, null, Float.NaN, tx, th, tz, lx, lh, lz, true, () -> false, quick, true);
                System.out.printf(Locale.ROOT, "move %s: %s %s in %.0f ms%s%n", part.trim(), quick ? "quick" : "full", v.ok() ? "WAY" : "none",
                    v.nanos() / 1e6, v.ok() ? String.format(Locale.ROOT, " (%s; %d ticks; ends %.2f,%.2f,%.2f)", v.recipe().how, v.recipe().ticks,
                        v.end().x, v.end().y, v.end().z) : "");
                if (v.ok()) break;
            }
        }
    }

    /** Scripted keys in the movement copy (KINE_SPIRAL_SCRIPT "x,y,z|keys@yaw,pitch*ticks|...": a start standing at the
     *  feet position, then phases of keys held, F B L R forward back left right, J jump, S sneak, P sprint, at a view;
     *  yaw 0 south, 90 west, -90 east), every tick printed with the pose and the fall: for asking whether a way the
     *  graph has no move for (a swim through a tunnel, a drop) can be made at all. */
    @Test
    void script() throws Exception {
        String script = System.getenv("KINE_SPIRAL_SCRIPT");
        Assumptions.assumeTrue(script != null, "KINE_SPIRAL_SCRIPT not set");
        load();
        String[] parts = script.split("\\|");
        String[] p0 = parts[0].split(",");
        double sx = Double.parseDouble(p0[0].trim()), sy = Double.parseDouble(p0[1].trim()), sz = Double.parseDouble(p0[2].trim());
        int r = 24;
        ai.rrw.kine.move.BlockGrid grid = map.grid((int) Math.floor(sx) - r, (int) Math.floor(sy) - r, (int) Math.floor(sz) - r, 2 * r + 1, 2 * r + 1, 2 * r + 1);
        ai.rrw.kine.move.GroundPhysics phys = new ai.rrw.kine.move.GroundPhysics(grid, new ai.rrw.kine.move.Body());
        ai.rrw.kine.move.MoveState s = Layer2Sim.standing(sx, sy, sz);
        int tick = 0;
        for (int i = 1; i < parts.length; i++) {
            String ph = parts[i].trim();
            int at = ph.indexOf('@'), star = ph.indexOf('*');
            String keyS = ph.substring(0, at);
            String[] view = ph.substring(at + 1, star).split(",");
            float yaw = Float.parseFloat(view[0].trim()), pitch = Float.parseFloat(view[1].trim());
            int ticks = Integer.parseInt(ph.substring(star + 1).trim());
            int keys = 0;
            for (char ch : keyS.toCharArray())
                keys |= switch (ch) {
                    case 'F' -> ai.rrw.kine.move.MoveState.FORWARD; case 'B' -> ai.rrw.kine.move.MoveState.BACKWARD;
                    case 'L' -> ai.rrw.kine.move.MoveState.LEFT; case 'R' -> ai.rrw.kine.move.MoveState.RIGHT;
                    case 'J' -> ai.rrw.kine.move.MoveState.JUMP; case 'S' -> ai.rrw.kine.move.MoveState.SHIFT;
                    case 'P' -> ai.rrw.kine.move.MoveState.SPRINT; default -> 0;
                };
            for (int t = 0; t < ticks; t++, tick++) {
                phys.step(s, keys, yaw, pitch);
                System.out.printf(Locale.ROOT, "tick %3d %-4s %s%s%s%s fall %.2f landed %.2f%n", tick, keyS, s, s.swimPose ? " swimpose" : "",
                    s.inWater ? " inwater" : "", s.eyesInWater ? " eyes" : "", s.fallDistance, s.landedFall);
            }
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

    /**
     * The walk table's floors against the real blocks (KINE_SPIRAL_AUDIT; RW 2026-10-16 02:25, a one-off check of the
     * floor summary over the whole map): for every column, the heights a player's box (0.6 wide, 1.8 high) can stand at
     * with its centre in the column, at the centre and at points 0.05 from its sides and corners, by the movement copy's
     * collision shapes (BlockGrid.collect: free above, something under the feet), against the table's GROUND floors
     * there (ColumnScan: the top of a collision in the middle 0.6 by 0.6, 1.8 free above it there). Kinds: MISS_CENTRE (a
     * real floor at the centre the table lacks), MISS_EDGE (real only off the centre, on a neighbour's block whose own
     * column has no floor there either: the pool's edge by the fence), PHANTOM (a table floor no position stands at), by the block under the feet (or, for
     * a phantom, the block in the way). Written to KINE_SPIRAL_AUDIT_OUT (default /tmp/spiral_audit.tsv); counts per kind
     * and block, and per failing leg (KINE_SPIRAL_AUDIT_LEGS: the legs whose area, the box round its two checkpoints and
     * 8 more, is counted).
     */
    /** Whether a box is in lava or a hazard (the table keeps no floor where the body would be in one). */
    static boolean noGo(ai.rrw.kine.move.BlockGrid g, double[] box) {
        for (int bx = (int) Math.floor(box[0]); bx <= (int) Math.floor(box[3] - 1e-9); bx++)
            for (int by = (int) Math.floor(box[1]); by <= (int) Math.floor(box[4] - 1e-9); by++)
                for (int bz = (int) Math.floor(box[2]); bz <= (int) Math.floor(box[5] - 1e-9); bz++) {
                    ai.rrw.kine.move.BlockInfo bi = g.get(bx, by, bz);
                    if (bi != null && (bi.fluid == ai.rrw.kine.move.BlockInfo.LAVA_FLUID || bi.is(ai.rrw.kine.move.BlockInfo.HAZARD))) return true;
                }
        return false;
    }

    /** Whether the table has a GROUND floor in column (x, z) within 0.07 of h. */
    static boolean tableHas(int x, int z, double h) {
        ChunkFloors c = view.chunk(x, z);
        if (c == null) return false;
        for (int j = 0; j < c.count(x & 15, z & 15); j++) {
            int idx = c.index(x & 15, z & 15, j);
            if (c.type(idx) == ChunkFloors.GROUND && Math.abs(c.h(idx) - h) < 0.07) return true;
        }
        return false;
    }

    static String blockName(int x, int y, int z) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(map.state(x, y, z).getBlock()).getPath();
    }

    @Test
    void floorAudit() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINE_SPIRAL_AUDIT") != null, "KINE_SPIRAL_AUDIT not set");
        load();
        long t0 = System.nanoTime();
        ai.rrw.kine.move.BlockGrid g = map.grid(SpiralMap.X0, SpiralMap.Y0, SpiralMap.Z0, SpiralMap.NX, SpiralMap.NY, SpiralMap.NZ);
        System.out.printf(Locale.ROOT, "audit: grid in %.1f s%n", (System.nanoTime() - t0) / 1e9);
        ai.rrw.kine.move.BlockGrid.Colliders cols = new ai.rrw.kine.move.BlockGrid.Colliders();
        double[] offs = {0.5, 0.05, 0.95};
        java.util.Map<String, int[]> counts = new java.util.TreeMap<>();
        java.util.Map<String, List<String>> examples = new java.util.HashMap<>();
        List<SpiralMap.Cp> cps = cps();
        String legsEnv = System.getenv().getOrDefault("KINE_SPIRAL_AUDIT_LEGS", "");
        List<int[]> legBoxes = new java.util.ArrayList<>();
        for (String part : legsEnv.split(",")) {
            if (part.isBlank()) continue;
            int k = index(cps, Integer.parseInt(part.trim()));
            SpiralMap.Cp a = cps.get(k), b = cps.get(k + 1);
            legBoxes.add(new int[]{a.n(), Math.min(a.x(), b.x()) - 8, Math.max(a.x(), b.x()) + 8, Math.min(a.z(), b.z()) - 8,
                Math.max(a.z(), b.z()) + 8, Math.min(a.y(), b.y()) - 3, Math.max(a.y(), b.y()) + 6});
        }
        java.util.Map<String, int[]> perLeg = new java.util.TreeMap<>();
        StringBuilder tsv = new StringBuilder("kind\tx\ty\tz\th\tblock\twhere\n");
        java.util.function.BiFunction<double[], Boolean, Boolean> any = (box, unused) -> { g.collect(box, cols); return cols.n > 0; };
        for (int x = SpiralMap.X0 + 1; x < SpiralMap.X1; x++)
            for (int z = SpiralMap.Z0 + 1; z < SpiralMap.Z1; z++) {
                // candidate heights: the tops of every shape the box can touch from this column
                java.util.TreeSet<Double> cand = new java.util.TreeSet<>();
                for (int bx = x - 1; bx <= x + 1; bx++)
                    for (int bz = z - 1; bz <= z + 1; bz++)
                        for (int y = SpiralMap.Y0; y < SpiralMap.Y1 - 2; y++) {
                            ai.rrw.kine.move.BlockInfo bi = g.get(bx, y, bz);
                            if (bi == null || bi.shape.empty) continue;
                            cand.add(y + bi.shape.top());
                            if (bi.shape.top() > 0.5 + 1e-9 && !bi.shape.block) cand.add(y + 0.5);
                        }
                java.util.TreeMap<Double, String> real = new java.util.TreeMap<>();   // height -> centre or edge, block under
                for (double h : cand) {
                    boolean centre = false, edge = false;
                    String under = null, edgeUnder = null;
                    for (double ox : offs)
                        for (double oz : offs) {
                            double px = x + ox, pz = z + oz;
                            double[] body = {px - 0.3, h + 1e-6, pz - 0.3, px + 0.3, h + 1.8, pz + 0.3};
                            if (any.apply(body, true) || noGo(g, body)) continue;
                            double[] feet = {px - 0.3, h - 1e-3, pz - 0.3, px + 0.3, h, pz + 0.3};
                            if (!any.apply(feet, true)) continue;
                            // the supports: by design out of the table when one hurts to step on (magma)
                            boolean hurts = false, ownSupport = false, coveredByNeighbour = false;
                            int sx = cols.x[0], sy = cols.y[0], sz = cols.z[0];
                            for (int i = 0; i < cols.n; i++) {
                                if (g.get(cols.x[i], cols.y[i], cols.z[i]).is(ai.rrw.kine.move.BlockInfo.STEP_HURTS)) hurts = true;
                                if (cols.x[i] == x && cols.z[i] == z) ownSupport = true;
                                else if (tableHas(cols.x[i], cols.z[i], h)) coveredByNeighbour = true;
                            }
                            if (hurts) continue;
                            if (ox == 0.5 && oz == 0.5) { centre = true; under = blockName(sx, sy, sz) + (ownSupport ? "" : "@nb"); }
                            else if (!ownSupport && !coveredByNeighbour) { edge = true; if (edgeUnder == null) edgeUnder = blockName(sx, sy, sz) + "@nb"; }
                        }
                    if (centre) real.put(h, "centre " + under);
                    else if (edge) real.put(h, "edge " + edgeUnder);
                }
                ChunkFloors c = view.chunk(x, z);
                int cnt = c == null ? 0 : c.count(x & 15, z & 15);
                java.util.List<Double> table = new java.util.ArrayList<>();
                for (int j = 0; j < cnt; j++) {
                    int idx = c.index(x & 15, z & 15, j);
                    if (c.type(idx) == ChunkFloors.GROUND) table.add((double) c.h(idx));
                }
                java.util.List<String[]> found = new java.util.ArrayList<>();
                for (var e : real.entrySet()) {
                    double h = e.getKey();
                    boolean inTable = table.stream().anyMatch(t -> Math.abs(t - h) < 0.07);
                    if (inTable) continue;
                    String[] w = e.getValue().split(" ", 2);
                    found.add(new String[]{w[0].equals("centre") ? "MISS_CENTRE" : "MISS_EDGE", Double.toString(h), w[1]});
                }
                for (double t : table) {
                    boolean isReal = real.entrySet().stream().anyMatch(e -> Math.abs(t - e.getKey()) < 0.07 && e.getValue().startsWith("centre"));
                    if (isReal) continue;
                    // the block in the way at the centre (or none: no support)
                    double px = x + 0.5, pz = z + 0.5;
                    double[] body = {px - 0.3, t + 1e-6, pz - 0.3, px + 0.3, t + 1.8, pz + 0.3};
                    String why;
                    double[] feetBox = {px - 0.3, t - 1e-3, pz - 0.3, px + 0.3, t, pz + 0.3};
                    if (any.apply(body, true)) why = "blocked by " + blockName(cols.x[0], cols.y[0], cols.z[0])
                        + (cols.x[0] == x && cols.z[0] == z ? "" : "@nb");
                    else if (noGo(g, body)) why = "in lava or a hazard";
                    else if (!any.apply(feetBox, true)) why = "no support, on " + blockName(x, (int) Math.floor(t - 1e-3), z);
                    else why = "stands, on " + blockName(cols.x[0], cols.y[0], cols.z[0]) + " (hurts to step on, or only off the centre)";
                    found.add(new String[]{"PHANTOM", Double.toString(t), why});
                }
                for (String[] f : found) {
                    String key = f[0] + " " + f[2];
                    counts.computeIfAbsent(key, kk -> new int[1])[0]++;
                    double h = Double.parseDouble(f[1]);
                    List<String> ex = examples.computeIfAbsent(key, kk -> new java.util.ArrayList<>());
                    if (ex.size() < 4) ex.add(String.format(Locale.ROOT, "%d,%.3f,%d", x, h, z));
                    tsv.append(f[0]).append('\t').append(x).append('\t').append((int) Math.floor(h)).append('\t').append(z).append('\t')
                        .append(String.format(Locale.ROOT, "%.4f", h)).append('\t').append(f[2]).append('\n');
                    for (int[] lb : legBoxes)
                        if (x >= lb[1] && x <= lb[2] && z >= lb[3] && z <= lb[4] && h >= lb[5] && h <= lb[6])
                            perLeg.computeIfAbsent("leg " + lb[0] + " " + f[0], kk -> new int[1])[0]++;
                }
            }
        java.nio.file.Files.writeString(Path.of(System.getenv().getOrDefault("KINE_SPIRAL_AUDIT_OUT", "/tmp/spiral_audit.tsv")), tsv);
        System.out.printf(Locale.ROOT, "audit: done in %.1f s%n", (System.nanoTime() - t0) / 1e9);
        for (var e : counts.entrySet())
            System.out.printf(Locale.ROOT, "audit %6d %s  e.g. %s%n", e.getValue()[0], e.getKey(), examples.get(e.getKey()));
        for (var e : perLeg.entrySet()) System.out.printf(Locale.ROOT, "audit %s: %d%n", e.getKey(), e.getValue()[0]);
    }

}
