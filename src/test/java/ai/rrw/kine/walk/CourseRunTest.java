package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Kine Parkour course walked in the copy from its start toward its far target (RW 2026-10-13), as the game's walk
 * does it: layer 1's way to the floor nearest the target (the course's jumps it has moves for), and, at its end, with
 * hops on (Hop.pick, Walk's far-target fallback), walking without a route to the next floor past it, then layer 1
 * again. Each leg's end and the course jump reached; where and why it stops. KINE_COURSE_DIR (parkour_world) set;
 * KINE_COURSE_HOPS=0 for layer 1 alone; KINE_COURSE_MAXX (blocks, default 420: the copy's world is 48 high, the course
 * from y 70 up). World y 70 is the copy's 0.
 */
public class CourseRunTest {
    static final int DY = 70;

    @Test
    void theCourseFromItsStart() throws Exception {
        String dir = System.getenv("KINE_COURSE_DIR");
        if (dir == null) return;
        boolean hops = !"0".equals(System.getenv("KINE_COURSE_HOPS"));
        int maxX = Integer.parseInt(System.getenv().getOrDefault("KINE_COURSE_MAXX", "420"));
        JsonObject course = JsonParser.parseString(Files.readString(Path.of(dir, "stations.json"))).getAsJsonObject().getAsJsonObject("course");
        JsonArray jumps = course.getAsJsonArray("jumps");
        JsonArray target = course.getAsJsonArray("target");
        int tx = target.get(0).getAsInt(), tz = target.get(2).getAsInt();
        int z0 = -100, z1 = -20;
        TestWorld w = TestWorld.blocks(-16, z0, maxX + 16, z1);
        Pattern fill = Pattern.compile("fill (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (\\S+)");
        Pattern set = Pattern.compile("setblock (-?\\d+) (-?\\d+) (-?\\d+) (\\S+)");
        for (String f : new String[]{"cmds_course_pool.txt", "cmds_course.txt"})
            for (String line : Files.readAllLines(Path.of(dir, f))) {
                Matcher m = fill.matcher(line);
                if (m.matches()) {
                    Kind k = CourseStartTest.kind(m.group(7));
                    int ax = Integer.parseInt(m.group(1)), ay = Integer.parseInt(m.group(2)), az = Integer.parseInt(m.group(3));
                    int bx = Integer.parseInt(m.group(4)), by = Integer.parseInt(m.group(5)), bz = Integer.parseInt(m.group(6));
                    for (int x = Math.max(-16, Math.min(ax, bx)); x <= Math.min(maxX + 16, Math.max(ax, bx)); x++)
                        for (int y = Math.min(ay, by); y <= Math.max(ay, by); y++)
                            for (int z = Math.max(z0, Math.min(az, bz)); z <= Math.min(z1, Math.max(az, bz)); z++)
                                if (y - DY >= 0 && y - DY < 48) w.set(x, y - DY, z, k == null ? Palette.AIR : k);
                    continue;
                }
                m = set.matcher(line);
                if (m.find()) {
                    int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2)), z = Integer.parseInt(m.group(3));
                    if (x > maxX + 16 || y - DY < 0 || y - DY >= 48) continue;
                    w.set(x, y - DY, z, CourseStartTest.kind(m.group(4)));
                }
            }
        // the course's landings and stand points (copy coordinates): a jump is done when the walk stands on one
        List<int[]> done = new ArrayList<>();
        for (JsonElement el : jumps) {
            JsonObject j = el.getAsJsonObject();
            JsonArray g = j.getAsJsonArray("goal"), st = j.getAsJsonArray("stand");
            done.add(new int[]{j.get("k").getAsInt(), g.get(0).getAsInt(), g.get(1).getAsInt() - 1 - DY, g.get(2).getAsInt(),
                st.get(0).getAsInt(), st.get(1).getAsInt() - DY, st.get(2).getAsInt()});
        }
        Layer2Sim sim = new Layer2Sim(w);
        sim.parkour = true; sim.direct = true; sim.recipes = true; sim.freeJumps = true; sim.acceptNearest = true;
        JsonArray s1 = jumps.get(0).getAsJsonObject().getAsJsonArray("start"), g1 = jumps.get(0).getAsJsonObject().getAsJsonArray("goal");
        MoveState s = Layer2Sim.standing(s1.get(0).getAsInt() + 0.5, s1.get(1).getAsInt() - DY, s1.get(2).getAsInt() + 0.5);
        float yaw = Recipe.yawTo(s.x, s.z, g1.get(0).getAsInt() + 0.5, g1.get(2).getAsInt() + 0.5);
        int best = 0, ticks = 0;
        double bestGoal = Double.MAX_VALUE;
        Set<Long> tried = new HashSet<>();
        String why = "";
        int failedHere = 0;
        for (int leg = 0; leg < 400; leg++) {
            // layer 1's way as far as it goes
            sim.forceDirect = false;
            sim.goalY = Double.NaN;
            Layer2Sim.Outcome o = sim.walk(s, yaw, tx, tz, 900);
            ticks += o.ticks();
            MoveState e = end(o, s);
            best = Math.max(best, reached(o, done));
            System.out.printf(Locale.ROOT, "leg %d route: %s to %s, %d ticks, at %.1f %.1f %.1f, course done %d%s%n", leg,
                o.arrived() ? "arrived" : "stopped (" + o.why() + ")", sim.lastTarget == null ? "-" : sim.lastTarget[0] + "," + sim.lastTarget[1],
                o.ticks(), e.x, e.y + DY, e.z, best, fellFrom(s, e) ? " FELL" : "");
            if (fellFrom(s, e)) { why = "fell on layer 1's way"; break; }
            s = e;
            bestGoal = Math.min(bestGoal, Math.hypot(tx + 0.5 - s.x, tz + 0.5 - s.z));
            if (!hops) { why = "at the floor nearest the target (no hops)"; break; }
            // the next floor past here, without a route
            long t = Hop.pick(sim.view, s.x, s.y, s.z, tx, tz, bestGoal, tried, 3.0, Walk.HOP_REACH);
            if (t == View.NONE) { why = "no floor past here to try"; break; }
            tried.add(t);
            int hx = View.nodeX(t), hz = View.nodeZ(t);
            double hh = sim.view.chunk(hx, hz).h(sim.view.index(t));
            sim.forceDirect = true;
            sim.goalY = hh;
            Layer2Sim.Outcome h = sim.walk(s, Recipe.yawTo(s.x, s.z, hx + 0.5, hz + 0.5), hx, hz, 400);
            ticks += h.ticks();
            MoveState he = end(h, s);
            best = Math.max(best, reached(h, done));
            boolean fell = fellFrom(s, he);
            System.out.printf(Locale.ROOT, "leg %d hop to %d %.1f %d: %s, %d ticks, %d recipes so far, at %.1f %.1f %.1f, course done %d%s%n", leg,
                hx, hh + DY, hz, h.arrived() ? "arrived" : "not (" + h.why() + ")", h.ticks(), sim.recipesFound, he.x, he.y + DY, he.z, best, fell ? " FELL" : "");
            if (fell) { why = "fell on a hop (to " + hx + " " + (int) Math.floor(hh + DY) + " " + hz + ")"; break; }
            if (h.arrived()) {
                s = he; failedHere = 0;
                bestGoal = Math.min(bestGoal, Math.hypot(tx + 0.5 - s.x, tz + 0.5 - s.z));
            } else if (++failedHere >= 3) { why = "three hops from here made no way"; break; }
            else s = he;
        }
        System.out.printf(Locale.ROOT, "course run (hops %b): %d of %d jumps done, %d ticks (%.0f s): %s; next %s%n", hops, best,
            jumps.size(), ticks, ticks / 20.0, why, best < jumps.size() ? jumps.get(best).getAsJsonObject().get("id").getAsString() : "the finish");
    }

    /** KINE_COURSE_AT="x,y,z" (world): one walk from there toward the target, layer 1's way as the game takes it, with
     *  its path every 5 ticks (KINE_COURSE_DEBUG: ticks to print layer 2's scores at). */
    @Test
    void fromAPoint() throws Exception {
        String dir = System.getenv("KINE_COURSE_DIR"), at = System.getenv("KINE_COURSE_AT");
        if (dir == null || at == null) return;
        String[] p = at.split(",");
        double x = Double.parseDouble(p[0]), y = Double.parseDouble(p[1]), z = Double.parseDouble(p[2]);
        int maxX = (int) x + 60;
        TestWorld w = TestWorld.blocks((int) x - 60, -100, maxX + 16, -20);
        Pattern fill = Pattern.compile("fill (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (\\S+)");
        Pattern set = Pattern.compile("setblock (-?\\d+) (-?\\d+) (-?\\d+) (\\S+)");
        for (String f : new String[]{"cmds_course_pool.txt", "cmds_course.txt"})
            for (String line : Files.readAllLines(Path.of(dir, f))) {
                Matcher m = fill.matcher(line);
                if (m.matches()) {
                    Kind k = CourseStartTest.kind(m.group(7));
                    int ax = Integer.parseInt(m.group(1)), ay = Integer.parseInt(m.group(2)), az = Integer.parseInt(m.group(3));
                    int bx = Integer.parseInt(m.group(4)), by = Integer.parseInt(m.group(5)), bz = Integer.parseInt(m.group(6));
                    for (int xx = Math.max((int) x - 60, Math.min(ax, bx)); xx <= Math.min(maxX + 16, Math.max(ax, bx)); xx++)
                        for (int yy = Math.min(ay, by); yy <= Math.max(ay, by); yy++)
                            for (int zz = Math.max(-100, Math.min(az, bz)); zz <= Math.min(-20, Math.max(az, bz)); zz++)
                                if (yy - DY >= 0 && yy - DY < 48) w.set(xx, yy - DY, zz, k == null ? Palette.AIR : k);
                    continue;
                }
                m = set.matcher(line);
                if (m.find()) {
                    int xx = Integer.parseInt(m.group(1)), yy = Integer.parseInt(m.group(2)), zz = Integer.parseInt(m.group(3));
                    if (xx < (int) x - 60 || xx > maxX + 16 || yy - DY < 0 || yy - DY >= 48) continue;
                    w.set(xx, yy - DY, zz, CourseStartTest.kind(m.group(4)));
                }
            }
        if (System.getenv("KINE_COURSE_SEARCH") != null) {
            View v = w.view();
            long st = v.nodeAt((int) Math.floor(x), y - DY, (int) Math.floor(z));
            PathSearch.Result r = new PathSearch(v, new Costs()).gaps(true).run(st, 940, -60, 1.0, Long.MAX_VALUE, 2_000_000, false);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.path.length; i++) sb.append(' ').append(r.moves[i]).append('@').append(View.nodeX(r.path[i])).append(',')
                .append(String.format(Locale.ROOT, "%.2f", r.pathY[i] + DY)).append(',').append(View.nodeZ(r.path[i]));
            System.out.println("search from " + at + ": nearest " + r.nearest + " exhausted " + r.exhausted + " expanded " + r.expanded + " path" + sb);
            for (int[] dd : new int[][]{{0, 0}, {0, -1}, {0, 1}, {1, 0}, {-1, 0}}) {
                int cx = (int) Math.floor(x) + dd[0], cz = (int) Math.floor(z) + dd[1];
                ChunkFloors c = v.chunk(cx, cz);
                StringBuilder f = new StringBuilder();
                for (int i = 0; i < c.count(cx & 15, cz & 15); i++) {
                    int idx = c.index(cx & 15, cz & 15, i);
                    f.append(String.format(Locale.ROOT, " [type %d h %.2f head %.2f clearTop %.2f flags %d]", c.type(idx), c.h(idx) + DY, c.head(idx), c.clearTop(idx) + DY, c.flags(idx)));
                }
                System.out.println("  column " + cx + "," + cz + ":" + f);
            }
            return;
        }
        Layer2Sim sim = new Layer2Sim(w);
        sim.parkour = true; sim.direct = true; sim.recipes = true; sim.freeJumps = true; sim.acceptNearest = true;
        if (System.getenv("KINE_COURSE_DEBUG") != null) for (String t : System.getenv("KINE_COURSE_DEBUG").split(",")) sim.debugTicks.add(Integer.parseInt(t));
        MoveState s = Layer2Sim.standing(x, y - DY, z);
        s.onGround = false; s.hasSupport = false;
        Layer2Sim.Outcome o = sim.walk(s, -90f, 940, -60, 300);
        for (int i = 0; i < o.path().size(); i += 5) { double[] q = o.path().get(i);
            System.out.printf(Locale.ROOT, "   t%d %.2f %.2f %.2f flags %d keys %d%n", i, q[0], q[1] + DY, q[2], (int) q[3], (int) q[4]); }
        System.out.printf(Locale.ROOT, "from %s: arrived %b (%s) in %d ticks, to %s; route %s%n", at, o.arrived(), o.why(), o.ticks(),
            sim.lastTarget == null ? "-" : sim.lastTarget[0] + "," + sim.lastTarget[1], sim.lastRoute == null ? "-" : java.util.Arrays.toString(sim.lastRoute.moves));
    }

    static MoveState end(Layer2Sim.Outcome o, MoveState from) {
        if (o.path().isEmpty()) return from;
        double[] p = o.path().get(o.path().size() - 1);
        return Layer2Sim.standing(p[0], p[1], p[2]);
    }

    static boolean fellFrom(MoveState a, MoveState b) {
        return b.y < a.y - 4.0;
    }

    /** The highest course jump whose landing or stand point the walk stood on. */
    static int reached(Layer2Sim.Outcome o, List<int[]> done) {
        int best = 0;
        for (double[] p : o.path()) {
            if (((int) p[3] & 4) == 0) continue;   // on the ground
            int bx = (int) Math.floor(p[0]), bz = (int) Math.floor(p[2]);
            for (int[] d : done) {
                boolean onLanding = bx == d[1] && bz == d[3] && p[1] > d[2] && p[1] < d[2] + 2.6;
                boolean onStand = bx == d[4] && bz == d[6] && p[1] > d[5] && p[1] < d[5] + 2.6;
                if ((onLanding || onStand) && d[0] > best) best = d[0];
            }
        }
        return best;
    }
}
