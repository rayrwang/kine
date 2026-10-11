package ai.rrw.kine.fireworks;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** A rocket's burn against the game's roll (FireworkRocketEntity): lifetime 10*(1+d) + nextInt(6) + nextInt(7),
 *  and it boosts for lifetime + 1 ticks. */
class RocketBurnTest {

    @Test
    void burnTable() {
        int[][] table = { {1, 21, 32}, {2, 31, 42}, {3, 41, 52}, {0, 11, 22} };
        for (int[] row : table) {
            assertEquals(row[1], RocketBurn.minTicks(row[0]), "min " + row[0]);
            assertEquals(row[2], RocketBurn.maxTicks(row[0]), "max " + row[0]);
        }
        assertEquals(26.5, RocketBurn.meanTicks(1));
        assertEquals(36.5, RocketBurn.meanTicks(2));
        assertEquals(46.5, RocketBurn.meanTicks(3));
    }

    @Test
    void stillBurningIsTheGamesDistribution() {
        for (int d = 0; d <= 3; d++) {
            assertEquals(1.0, RocketBurn.stillBurning(d, RocketBurn.minTicks(d) - 1));
            assertEquals(0.0, RocketBurn.stillBurning(d, RocketBurn.maxTicks(d)));
            double prev = 1.0, expected = 0;
            for (int t = 0; t <= RocketBurn.maxTicks(d) + 2; t++) {
                double p = RocketBurn.stillBurning(d, t);
                assertTrue(p <= prev + 1e-12, "never rises, " + d + " at " + t);
                prev = p;
                expected += p;   // E[burn] = sum over t >= 0 of P(burn > t)
            }
            assertEquals(RocketBurn.meanTicks(d), expected, 1e-9);

            Random r = new Random(42 + d);
            long sum = 0; int n = 200_000, lo = Integer.MAX_VALUE, hi = 0;
            for (int i = 0; i < n; i++) {
                int burn = 10 * (1 + d) + r.nextInt(6) + r.nextInt(7) + 1;
                sum += burn; lo = Math.min(lo, burn); hi = Math.max(hi, burn);
            }
            assertEquals(RocketBurn.minTicks(d), lo);
            assertEquals(RocketBurn.maxTicks(d), hi);
            assertEquals(RocketBurn.meanTicks(d), (double) sum / n, 0.03);
        }
    }

    @Test
    void fuelLeavesOutStarRockets() {
        RocketBurn.Fuel f = new RocketBurn.Fuel();
        f.add(3, 64, false);
        f.add(1, 10, true);    // with firework stars: left out
        f.add(1, 2, false);
        assertEquals(64 * 2.325 + 2 * 1.325, f.seconds(), 1e-9);
    }
}
