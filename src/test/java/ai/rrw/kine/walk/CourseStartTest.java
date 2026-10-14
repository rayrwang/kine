package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Kine Parkour course from its start (RW 2026-10-13 21:10: "it doesnt even move at a llf rom start", Parkour on, the
 * Walk tab at X 941 Y 168 Z -59): the course's blocks and pool (parkour_world/cmds_course*.txt) loaded out to a radius
 * round the start, as a client with that render distance has them, and the search from the start to the far target
 * (Y given and blank), as the walk runs it: what it returns. KINE_COURSE_DIR set (parkour_world); KINE_COURSE_RADIUS
 * (blocks loaded past the start, default 160). World y 60 is the copy's 0 (its world is 48 high); a stone floor there
 * stands for the superflat ground far below (more than the 24 blocks the walk table knows above a floor).
 */
public class CourseStartTest {
    static final int DY = 60;

    static final Kind BUBBLE = Palette.add("bubble_column_up", List.of(), 0.6F, 1.0F, Kind.WATER | Kind.AVOID);

    static Kind kind(String block) {
        String id = block.replace("minecraft:", "").split("\\[")[0];
        if (id.equals("bubble_column")) return BUBBLE;
        if (id.equals("soul_sand")) return TestWorld.SOUL_SAND;
        if (id.equals("air")) return null;
        Kind k = Layer2StationTest.kindOf(block);
        return k != null ? k : TestWorld.STONE;
    }

    @Test
    void fromTheStart() throws Exception {
        String dir = System.getenv("KINE_COURSE_DIR");
        if (dir == null) return;
        int radius = Integer.parseInt(System.getenv().getOrDefault("KINE_COURSE_RADIUS", "160"));
        int sx = 10, sz = -60, x1 = sx + radius;
        TestWorld w = TestWorld.blocks(-16, sz - radius, x1, sz + radius);
        w.fill(-16, 0, sz - radius, x1, 0, sz + radius, TestWorld.STONE);   // the ground, far below
        Pattern fill = Pattern.compile("fill (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (\\S+)");
        Pattern set = Pattern.compile("setblock (-?\\d+) (-?\\d+) (-?\\d+) (\\S+)");
        int placed = 0;
        for (String f : new String[]{"cmds_course_pool.txt", "cmds_course.txt"})
            for (String line : Files.readAllLines(Path.of(dir, f))) {
                Matcher m = fill.matcher(line);
                if (m.matches()) {
                    int ax = Integer.parseInt(m.group(1)), ay = Integer.parseInt(m.group(2)), az = Integer.parseInt(m.group(3));
                    int bx = Integer.parseInt(m.group(4)), by = Integer.parseInt(m.group(5)), bz = Integer.parseInt(m.group(6));
                    Kind k = kind(m.group(7));
                    for (int x = Math.max(-16, Math.min(ax, bx)); x <= Math.min(x1, Math.max(ax, bx)); x++)
                        for (int y = Math.min(ay, by); y <= Math.max(ay, by); y++)
                            for (int z = Math.max(sz - radius, Math.min(az, bz)); z <= Math.min(sz + radius, Math.max(az, bz)); z++) {
                                if (y - DY < 0 || y - DY >= 48) continue;
                                if (k == null) w.set(x, y - DY, z, Palette.AIR); else w.set(x, y - DY, z, k);
                            }
                    continue;
                }
                m = set.matcher(line);
                if (m.find()) {
                    int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2)), z = Integer.parseInt(m.group(3));
                    if (x > x1 || y - DY < 0 || y - DY >= 48) continue;
                    w.set(x, y - DY, z, kind(m.group(4)));
                    placed++;
                }
            }
        View v = w.view();
        long start = v.nodeAt(sx, Integer.parseInt(System.getenv().getOrDefault("KINE_COURSE_START_Y", "97")) - DY, sz);
        System.out.printf(Locale.ROOT, "course start: %d blocks placed out to x %d; start node %s%n", placed, x1, start == View.NONE ? "NONE" : View.nodeX(start) + "," + View.nodeZ(start));
        for (double gy : new double[]{Double.parseDouble(System.getenv().getOrDefault("KINE_COURSE_TARGET_Y", "164")) - DY, Double.NaN})
            for (boolean gaps : new boolean[]{true, false})
                for (double wt : Planner.WEIGHTS) {
                PathSearch.Result r = new PathSearch(v, new Costs()).gaps(gaps).goalY(gy).run(start, 941, -59, wt, Long.MAX_VALUE, 2_000_000, false);
                if (wt == 1.0 && gaps && r.path.length > 0) {
                    StringBuilder p = new StringBuilder();
                    for (int i = 0; i < r.path.length; i++) p.append(' ').append(View.nodeX(r.path[i])).append(',').append(View.nodeZ(r.path[i])).append(':').append(r.moves[i]);
                    System.out.println("    path" + p);
                }
                StringBuilder end = new StringBuilder();
                if (r.path.length > 0) {
                    long last = r.path[r.path.length - 1];
                    end.append(View.nodeX(last)).append(',').append(View.nodeZ(last));
                }
                System.out.printf(Locale.ROOT, "  weight %.2f goalY %s gaps %b: found %b partial %b exhausted %b nearest %b viaExit %b moves %d end %s expanded %d reason \"%s\"%n",
                    wt, Double.isNaN(gy) ? "blank" : String.valueOf(gy + DY), gaps, r.found, r.partial, r.exhausted, r.nearest, r.viaExit,
                    r.path.length, end, r.expanded, r.reason);
            }
    }
}
