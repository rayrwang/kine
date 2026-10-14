package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tightening over water (RW, 2026-10-13: "path tightneing over water doenst' seem to work"): a swim across open
 * water was kept step by step on the grid's eight directions, a staircase up to 13.7 blocks off the straight line over
 * 70 blocks in these scenes, and layer 2's swim, following its corridor, strayed up to 5.1 (measured 2026-10-14 on
 * 272c054). Now a run of swims is pulled taut like a run of walks, every
 * straight swim checked to stay over open water (the box grown by the margin), and the route's ground is sampled along
 * it so the corridor and the rails take in the whole line.
 */
public class SwimTightenTest {
    static final int TOP = 30;
    static final double MARGIN = 0.2;

    /** A sea from a rock at the origin to a rock at (gx, gz), with an optional island in between. */
    static TestWorld sea(int gx, int gz, boolean island) {
        TestWorld w = TestWorld.blocks(-16, -80, 95, 79);
        w.fill(-16, TOP - 4, -80, 95, TOP - 4, 79, TestWorld.STONE);
        w.fill(-16, TOP - 3, -80, 95, TOP, 79, TestWorld.WATER);
        w.fill(-1, TOP - 3, -1, 1, TOP, 1, TestWorld.STONE);
        w.fill(gx - 1, TOP - 3, gz - 1, gx + 1, TOP, gz + 1, TestWorld.STONE);
        if (island) {
            int cx = gx / 2, cz = gz / 2;
            w.fill(cx - 4, TOP - 3, cz - 4, cx + 4, TOP + 2, cz + 4, TestWorld.STONE);
        }
        return w;
    }

    static double lateral(double x, double z, double ax, double az, double bx, double bz) {
        double lx = bx - ax, lz = bz - az, len = Math.hypot(lx, lz);
        return ((x - ax) * lz - (z - az) * lx) / len;
    }

    static Route route(TestWorld w, int gx, int gz) {
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, Costs.layer2()).gaps(true).run(v.nodeAt(0, TOP + 1, 0), gx, gz, 1.0, Long.MAX_VALUE, 2_000_000, false);
        assertTrue(r.found, r.reason);
        return new Tighten(v, Costs.layer2(), MARGIN, true).route(r, gx, gz);
    }

    /** Every straight swim leg against the world's own blocks: under the player's box grown by the margin (less a
     *  hair), every column water at the top and nothing solid at head height. */
    static void checkSwimsAgainstWorld(TestWorld w, Route r) {
        for (int i = 1; i < r.n; i++) {
            if (r.move[i] != Route.SWIM) continue;
            double x0 = r.x[i - 1], z0 = r.z[i - 1], x1 = r.x[i], z1 = r.z[i];
            double len = Math.hypot(x1 - x0, z1 - z0);
            int n = Math.max(1, (int) Math.ceil(len / 0.05));
            double out = 0.3 + MARGIN - 0.01;
            for (int k = 0; k <= n; k++) {
                double x = x0 + (x1 - x0) * k / n, z = z0 + (z1 - z0) * k / n;
                for (int bx = (int) Math.floor(x - out); bx <= (int) Math.floor(x + out); bx++)
                    for (int bz = (int) Math.floor(z - out); bz <= (int) Math.floor(z + out); bz++) {
                        assertEquals(TestWorld.WATER, w.get(bx, TOP, bz), "swim leg " + i + " over " + bx + " " + bz + " at " + x + " " + z);
                        assertTrue(w.get(bx, TOP + 1, bz).empty(), "swim leg " + i + " under a block at " + bx + " " + bz);
                    }
            }
        }
    }

    @Test
    void openWaterIsSwumStraight() {
        StringBuilder bad = new StringBuilder();
        for (double deg : new double[]{0, 10, 20, 22.5, 30, 45, 60, -15}) {
            double rad = Math.toRadians(deg);
            int gx = (int) Math.round(70 * Math.cos(rad)), gz = (int) Math.round(70 * Math.sin(rad));
            TestWorld w = sea(gx, gz, false);
            Route r = route(w, gx, gz);
            int swims = 0;
            double routeLat = 0;
            for (int i = 0; i < r.n; i++) {
                if (r.move[i] == Route.SWIM) swims++;
                routeLat = Math.max(routeLat, Math.abs(lateral(r.x[i], r.z[i], 0.5, 0.5, gx + 0.5, gz + 0.5)));
            }
            double maxGap = 0;
            for (int k = 1; k < r.gn; k++) maxGap = Math.max(maxGap, Math.hypot(r.gx[k] - r.gx[k - 1], r.gz[k] - r.gz[k - 1]));
            Layer2Sim sim = new Layer2Sim(w);
            Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, TOP + 1, 0.5), -90f, gx, gz, 1500);
            double trackLat = 0;
            for (double[] q : o.path()) trackLat = Math.max(trackLat, Math.abs(lateral(q[0], q[2], 0.5, 0.5, gx + 0.5, gz + 0.5)));
            System.out.printf(Locale.ROOT, "KINEMEASURE sea deg=%.1f waypoints=%d swimLegs=%d route %.2f grid %.2f straight %.2f routeLateral=%.2f groundGap=%.2f | layer2 arrived=%b ticks=%d trackLateral=%.2f%n",
                deg, r.n, swims, r.length, r.gridLength, r.straightLength, routeLat, maxGap, o.arrived(), o.ticks(), trackLat);
            checkSwimsAgainstWorld(w, r);
            if (!o.arrived()) bad.append(deg).append(" not arrived; ");
            if (routeLat > 1.5) bad.append(deg).append(" route strays ").append(routeLat).append("; ");
            if (trackLat > 2.0) bad.append(deg).append(" track strays ").append(trackLat).append("; ");
            if (maxGap > 1.5) bad.append(deg).append(" ground gap ").append(maxGap).append("; ");
        }
        assertEquals("", bad.toString());
    }

    @Test
    void anIslandIsSwumRoundNotThrough() {
        for (double deg : new double[]{0, 20, 45}) {
            double rad = Math.toRadians(deg);
            int gx = (int) Math.round(70 * Math.cos(rad)), gz = (int) Math.round(70 * Math.sin(rad));
            TestWorld w = sea(gx, gz, true);
            Route r = route(w, gx, gz);
            System.out.printf(Locale.ROOT, "KINEMEASURE island deg=%.0f waypoints=%d route %.2f grid %.2f straight %.2f%n",
                deg, r.n, r.length, r.gridLength, r.straightLength);
            checkSwimsAgainstWorld(w, r);
            assertTrue(r.length <= r.gridLength + 1e-6, "no longer than the grid path");
        }
    }
}
