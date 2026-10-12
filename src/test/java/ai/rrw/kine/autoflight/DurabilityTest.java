package ai.rrw.kine.autoflight;

import ai.rrw.kine.autoflight.Durability.Wing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The elytra durability arithmetic: the floor, the wing order, END with the last wing's landing reserve. */
class DurabilityTest {

    static Wing wing(int left, int unbreaking) { return new Wing(left, unbreaking, false); }

    @Test
    void theFloorIsThreePointsLeft() {
        assertEquals(3, Durability.FLOOR);
        assertTrue(Durability.spent(3));
        assertTrue(Durability.spent(1));
        assertFalse(Durability.spent(4));
        assertEquals(0, Durability.usable(3));
        assertEquals(0, Durability.usable(0));
        assertEquals(1, Durability.usable(4));
        assertEquals(429, Durability.usable(432));                // a new elytra
        // enchanted or not, the same floor
        assertTrue(wing(3, 3).spent());
        assertEquals(0, wing(3, 3).usable());
        // one that takes no damage is never spent, whatever its number
        assertFalse(new Wing(2, 0, true).spent());
    }

    @Test
    void expectedSecondsAreUsablePointsTimesUnbreakingPlusOne() {
        assertEquals(429.0, wing(432, 0).seconds(), 0.0);
        assertEquals(4 * 429.0, wing(432, 3).seconds(), 0.0);   // the tool curve: a point lasts 4 s on average
        assertEquals(2 * 7.0, wing(10, 1).seconds(), 0.0);
        assertEquals(0.0, wing(3, 3).seconds(), 0.0);
        assertEquals(Double.POSITIVE_INFINITY, new Wing(432, 0, true).seconds(), 0.0);
    }

    @Test
    void theWornWingFirstThenTheFreshestSpare() {
        Wing worn = wing(50, 0), a = wing(20, 3), b = wing(300, 0), c = wing(3, 3), d = wing(300, 1);
        List<Wing> spares = List.of(a, b, c, d);
        assertEquals(1, Durability.freshest(spares));            // b and d tie at 297 usable: the first
        List<Wing> order = Durability.order(worn, spares);
        assertEquals(List.of(worn, b, d, a), order);              // c is spent: not flown
        // a spent worn wing still comes first (it is swapped out at once)
        assertEquals(List.of(wing(3, 0), a), Durability.order(wing(3, 0), List.of(c, a)));
        // no usable spare
        assertEquals(-1, Durability.freshest(List.of(c, wing(1, 0))));
        assertEquals(-1, Durability.freshest(List.of()));
    }

    @Test
    void reservePointsRoundUpTheLandingSeconds() {
        assertEquals(28, Durability.reservePoints(27.6));
        assertEquals(15, Durability.reservePoints(15.0));
        assertEquals(16, Durability.reservePoints(15.05));
        assertEquals(0, Durability.reservePoints(0.0));
    }

    @Test
    void endHoldsTheReserveOnTheLastWingAtItsRate() {
        // one wing, Unbreaking III, 100 points left (97 usable), a 25-point reserve: 97 x 4 - 25 x 4
        assertEquals(288.0, Durability.endurance(List.of(wing(100, 3)), 25), 0.0);
        // the worn wing unenchanted, the last one (the only spare) Unbreaking III: the reserve at the spare's rate
        List<Wing> order = Durability.order(wing(53, 0), List.of(wing(43, 3)));
        assertEquals(50 + 40 * 4 - 25 * 4, Durability.endurance(order, 25), 0.0);
        // the other way round: the reserve at 1 s a point
        order = Durability.order(wing(43, 3), List.of(wing(53, 0)));
        assertEquals(40 * 4 + 50 - 25, Durability.endurance(order, 25), 0.0);
        // no reserve
        assertEquals(97.0, Durability.endurance(List.of(wing(100, 0)), 0), 0.0);
    }

    @Test
    void aLastWingTooShortForTheReserveSpillsItOntoTheOneBefore() {
        // worn 53 left (50 usable, Unbreaking III), last spare 13 left (10 usable, none), reserve 25: the last wing
        // holds 10, the worn one the other 15 at its own rate
        List<Wing> order = Durability.order(wing(53, 3), List.of(wing(13, 0)));
        assertEquals(50 * 4 + 10 - (10 + 15 * 4), Durability.endurance(order, 25), 0.0);
        // fewer usable points than the reserve in all: land now
        assertEquals(0.0, Durability.endurance(List.of(wing(20, 3)), 25), 0.0);
        assertEquals(0.0, Durability.endurance(Durability.order(wing(10, 0), List.of(wing(10, 0))), 15), 0.0);
        // exactly the reserve left: END 0, the landing starts now
        assertEquals(0.0, Durability.endurance(List.of(wing(28, 3)), 25), 0.0);
        assertEquals(4.0, Durability.endurance(List.of(wing(29, 3)), 25), 0.0);
    }

    @Test
    void anUnbreakableWornWingIsInfinite() {
        assertEquals(Double.POSITIVE_INFINITY, Durability.endurance(List.of(new Wing(5, 0, true)), 25), 0.0);
        assertEquals(0.0, Durability.endurance(List.of(), 25), 0.0);
    }

    @Test
    void endZeroLeavesEnoughForTheLandingEvenAtWorst() {
        // Fly a stock in order, a roll a second as the game rolls it (each point lost with chance 1 / (U + 1)), swap at
        // the floor, and start the landing when END reaches 0. Then land, losing a point every second (the worst):
        // the wing worn at touchdown must still be at or above the floor, whatever the rolls (a stock that starts with
        // less than the reserve is not a test of the rule).
        Random rnd = new Random(7);
        int landingSeconds = 23;
        int reserve = Durability.reservePoints(landingSeconds);
        for (int trial = 0; trial < 2000; trial++) {
            int[] left = { 4 + rnd.nextInt(80), 4 + rnd.nextInt(80), 4 + rnd.nextInt(80) };
            int[] unb = { rnd.nextInt(4), rnd.nextInt(4), rnd.nextInt(4) };
            if (Durability.usable(left[0]) + Durability.usable(left[1]) + Durability.usable(left[2]) < reserve) continue;
            int worn = 0;
            for (int s = 0; s < 100000; s++) {
                List<Wing> spares = new java.util.ArrayList<>();
                int[] slot = new int[2];
                for (int i = 0, k = 0; i < 3; i++) if (i != worn) { spares.add(wing(left[i], unb[i])); slot[k++] = i; }
                if (Durability.spent(left[worn])) {
                    int f = Durability.freshest(spares);
                    if (f >= 0) { worn = slot[f]; continue; }
                }
                Wing w = wing(left[worn], unb[worn]);
                if (Durability.endurance(Durability.order(w, spares), reserve) <= 0) break;   // land now
                if (rnd.nextInt(unb[worn] + 1) == 0) left[worn]--;
            }
            // the landing, at worst a point a second, swapping at the floor as in flight
            for (int s = 0; s < landingSeconds; s++) {
                if (Durability.spent(left[worn])) {
                    int best = -1;
                    for (int i = 0; i < 3; i++) if (i != worn && Durability.usable(left[i]) > 0
                        && (best < 0 || Durability.usable(left[i]) > Durability.usable(left[best]))) best = i;
                    if (best < 0) break;
                    worn = best;
                }
                left[worn]--;
            }
            assertTrue(left[worn] >= Durability.FLOOR, "trial " + trial + ": below the floor at touchdown, worn " + left[worn]);
        }
    }
}
