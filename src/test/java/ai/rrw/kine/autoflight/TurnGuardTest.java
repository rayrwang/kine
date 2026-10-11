package ai.rrw.kine.autoflight;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The planner's per-call column cache (TurnGuard.cached). */
class TurnGuardTest {

    @Test
    void theCacheGivesEachColumnsHeightAndAsksForItOnce() {
        Map<Long, Integer> asks = new HashMap<>();
        Terrain2D column = (x, z) -> {
            assertEquals(Math.floor(x), x, 0.0, "asked with whole-block coordinates");
            assertEquals(Math.floor(z), z, 0.0, "asked with whole-block coordinates");
            asks.merge(((long) x << 32) ^ ((long) z & 0xffffffffL), 1, Integer::sum);
            if ((int) x % 7 == 0) return Double.NaN;             // unloaded columns stay unseen
            return 64 + ((int) x * 31 + (int) z * 17) % 200;
        };
        Terrain2D cached = TurnGuard.cached(column);
        Random rnd = new Random(1);
        for (int i = 0; i < 200_000; i++) {                       // far more columns than the first table holds
            double x = (rnd.nextDouble() - 0.5) * 600, z = (rnd.nextDouble() - 0.5) * 600 + 1.0e6;
            double want = column.heightAt(Math.floor(x), Math.floor(z));
            asks.merge(((long) Math.floor(x) << 32) ^ ((long) Math.floor(z) & 0xffffffffL), -1, Integer::sum);
            double got = cached.heightAt(x, z);
            if (Double.isNaN(want)) assertTrue(Double.isNaN(got), "unloaded at " + x + ", " + z);
            else assertEquals(want, got, 0.0, "at " + x + ", " + z);
        }
        for (int n : asks.values()) assertEquals(1, n, "each column is asked for once");
    }
}
