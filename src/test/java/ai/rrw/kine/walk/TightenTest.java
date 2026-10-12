package ai.rrw.kine.walk;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static ai.rrw.kine.walk.TestWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The tightening: straight where the ground allows, never through a wall, over a hole, off a drop of more than 3 or
 * near a hazard, and never longer than the grid path. The checks here read the made-up world's blocks directly, not
 * the table or the sweep the tightening uses.
 */
public class TightenTest {
    static final double MARGIN = 0.2;

    static Route route(TestWorld w, int sx, int sz, int gx, int gz) {
        View v = w.view();
        PathSearch.Result r = new PathSearch(v, new Costs()).run(v.nodeAt(sx, 11, sz), gx, gz, 1.0, Long.MAX_VALUE, 2_000_000, false);
        assertTrue(r.found, r.reason);
        return new Tighten(v, new Costs(), MARGIN, true).route(r, gx, gz);
    }

    /** Walk each straight leg of the route in small steps and check it against the world's own blocks: the player's
     *  box (grown by the margin, less a hair) meets no full block above the ground's step and no hazard, and the box
     *  shrunk by the margin has a block under it at most 3 below the leg's starting height. */
    static void checkAgainstWorld(TestWorld w, Route r) {
        for (int i = 1; i < r.n; i++) {
            if (r.move[i] != Route.WALK) continue;
            double x0 = r.x[i - 1], z0 = r.z[i - 1], x1 = r.x[i], z1 = r.z[i];
            double top = Math.max(r.y[i - 1], r.y[i]);
            double len = Math.hypot(x1 - x0, z1 - z0);
            int n = Math.max(1, (int) Math.ceil(len / 0.05));
            for (int k = 0; k <= n; k++) {
                double x = x0 + (x1 - x0) * k / n, z = z0 + (z1 - z0) * k / n;
                double out = 0.3 + MARGIN - 0.01, in = Math.max(0.05, 0.3 - MARGIN) - 0.01;
                for (int bx = (int) Math.floor(x - out); bx <= (int) Math.floor(x + out); bx++)
                    for (int bz = (int) Math.floor(z - out); bz <= (int) Math.floor(z + out); bz++) {
                        for (int y = (int) Math.floor(top + 0.6); y <= (int) Math.floor(top + 1.8); y++) {
                            Kind kk = w.get(bx, y, bz);
                            assertFalse(kk == STONE, "leg " + i + " meets stone at " + bx + " " + y + " " + bz);
                        }
                        for (int y = (int) Math.floor(top - 4); y <= (int) Math.floor(top + 1.8); y++)
                            assertFalse(w.get(bx, y, bz).blocksBody(), "leg " + i + " near a hazard at " + bx + " " + y + " " + bz);
                    }
                boolean support = false;
                for (int bx = (int) Math.floor(x - in); bx <= (int) Math.floor(x + in); bx++)
                    for (int bz = (int) Math.floor(z - in); bz <= (int) Math.floor(z + in); bz++)
                        for (int y = (int) Math.floor(top); y >= (int) Math.floor(top - 3.01); y--)
                            if (!w.get(bx, y, bz).empty() && w.get(bx, y, bz) != WATER) support = true;
                assertTrue(support, "leg " + i + " over a hole or a big drop at " + x + " " + z);
            }
        }
    }

    @Test
    void openGroundIsOneStraightLine() {
        TestWorld w = blocks(-4, -4, 70, 40).floor(10);
        Route r = route(w, 0, 0, 60, 25);
        assertEquals(2, r.n, "one straight leg");
        assertEquals(r.straightLength, r.length, 1e-9);
        assertTrue(r.gridLength >= r.length - 1e-9);
        System.out.printf("KINEMEASURE open diagonal: grid %.3f tight %.3f straight %.3f%n", r.gridLength, r.length, r.straightLength);
        checkAgainstWorld(w, r);
    }

    @Test
    void roundAWallsEnd() {
        TestWorld w = blocks(0, 0, 47, 47).floor(10);
        w.fill(20, 11, 0, 20, 13, 30, STONE);
        Route r = route(w, 5, 5, 40, 5);
        assertTrue(r.n >= 3, "bends round the end");
        assertTrue(r.length <= r.gridLength + 1e-9);
        checkAgainstWorld(w, r);
        // the taut line at the margin: round the wall's end grown by half the player's width and the margin (0.5), so
        // through the grown wall's two corners (19.5, 31.5) and (21.5, 31.5)
        double g = 0.3 + MARGIN;
        double taut = Math.hypot(20 - g - 5.5, 31 + g - 5.5) + (1 + 2 * g) + Math.hypot(40.5 - (21 + g), 31 + g - 5.5);
        System.out.printf("KINEMEASURE wall corner: grid %.3f tight %.3f taut at the margin %.3f%n", r.gridLength, r.length, taut);
        assertTrue(r.length < taut * 1.005, "tightened " + r.length + " against taut " + taut);
    }

    @Test
    void neverOverAHoleOrOffABigDropOrNearAHazard() {
        TestWorld w = blocks(0, 0, 47, 47).floor(10);
        w.fill(15, 6, 8, 15, 10, 20, Palette.AIR);              // a slot 1 wide, 5 deep, across the straight line
        w.fill(25, 11, 0, 47, 15, 9, STONE);                   // a block 5 high: its top floor at 16 ...
        w.fill(25, 11, 10, 47, 15, 13, STONE);                 // ... with its south edge a 5 drop at z = 14
        w.fill(30, 10, 16, 33, 10, 18, LAVA);                  // lava in the floor near the line
        w.set(36, 11, 15, CACTUS).set(36, 12, 15, CACTUS);
        Route r = route(w, 5, 14, 44, 14);
        assertTrue(r.length <= r.gridLength + 1e-9);
        checkAgainstWorld(w, r);
    }

    @Test
    void neverLongerThanTheGridPath() {
        for (long seed = 1; seed <= 40; seed++) {
            TestWorld w = SearchTest.randomWorld(seed);
            Random rnd = new Random(seed * 31);
            int sx = rnd.nextInt(24), sz = rnd.nextInt(24), gx = rnd.nextInt(24), gz = rnd.nextInt(24);
            w.fill(sx, 11, sz, sx, 12, sz, Palette.AIR).set(sx, 10, sz, STONE);
            View v = w.view();
            PathSearch.Result res = new PathSearch(v, new Costs()).run(v.nodeAt(sx, 11, sz), gx, gz, 1.0, Long.MAX_VALUE, 1_000_000, false);
            if (!res.found) continue;
            Route r = new Tighten(v, new Costs(), MARGIN, true).route(res, gx, gz);
            assertTrue(r.length <= r.gridLength + 1e-9, "seed " + seed + ": " + r.length + " > " + r.gridLength);
            // and it still visits the grid path's special moves in order
            int specials = 0, kept = 0;
            for (byte m : res.moves) if (m != Graph.WALK && m != Graph.DROP && m != Graph.START) specials++;
            for (byte m : r.move) if (m != Graph.WALK && m != Graph.DROP && m != Graph.START) kept++;
            assertEquals(specials, kept, "seed " + seed);
        }
    }
}
