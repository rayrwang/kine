package ai.rrw.kine.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Auto eat's choice of when and what (the game's numbers, Foods.java: saturation = nutrition x modifier x 2). */
class AutoEatTest {
    static final AutoEat.Food STEAK = new AutoEat.Food(0, 8, 12.8f, -1);
    static final AutoEat.Food GOLDEN_CARROT = new AutoEat.Food(1, 6, 14.4f, -1);
    static final AutoEat.Food BREAD = new AutoEat.Food(2, 5, 6.0f, -1);
    static final AutoEat.Food KELP = new AutoEat.Food(3, 1, 0.6f, -1);
    static final AutoEat.Food RAW_CHICKEN = new AutoEat.Food(4, 2, 1.2f, 0);
    static final AutoEat.Food ROTTEN_FLESH = new AutoEat.Food(5, 4, 0.8f, 1);

    @Test
    void waitsUntilTheBestFoodFitsWhole() {
        List<AutoEat.Food> c = List.of(STEAK, GOLDEN_CARROT, BREAD);
        assertNull(AutoEat.choose(c, 13, 0f, false));          // 13 + 8 > 20
        assertEquals(STEAK, AutoEat.choose(c, 12, 0f, false));  // steak, the best (8 + 12.8), fits exactly
    }

    @Test
    void saturationMustFitToo() {
        List<AutoEat.Food> c = List.of(GOLDEN_CARROT);
        assertEquals(GOLDEN_CARROT, AutoEat.choose(c, 14, 1f, false));   // 1 + 14.4 within the new 20
        assertNull(AutoEat.choose(c, 14, 6f, false));                     // 6 + 14.4 over 20: 0.4 wasted
    }

    @Test
    void noSnackingOnSmallFoodWhileABetterOneIsCarried() {
        assertNull(AutoEat.choose(List.of(STEAK, KELP), 19, 0f, false));
        assertEquals(KELP, AutoEat.choose(List.of(KELP), 19, 0f, false));
    }

    @Test
    void heartsMissingEatsWheneverItCanTakingTheMostSaturation() {
        List<AutoEat.Food> c = List.of(STEAK, GOLDEN_CARROT, BREAD, KELP);
        assertEquals(GOLDEN_CARROT, AutoEat.choose(c, 19, 0f, true));   // 1 + 14.4 against steak's 1 + 12.8
        assertNull(AutoEat.choose(c, 20, 0f, true));                    // a full bar can't eat (golden apples aside)
        assertEquals(STEAK, AutoEat.choose(c, 10, 0f, true));           // 8 + 12.8, the most it can take in now
    }

    @Test
    void tooHungryToSprintEatsAtOnceWasteOrNot() {
        // the golden carrot at hunger 2 wastes saturation (capped at the new 8), but eating now beats waiting
        assertEquals(GOLDEN_CARROT, AutoEat.choose(List.of(GOLDEN_CARROT), 2, 0f, false));
        assertEquals(STEAK, AutoEat.choose(List.of(STEAK, GOLDEN_CARROT), 2, 0f, false));   // 8 + 10 against 6 + 8
    }

    @Test
    void badFoodOnlyAsALastResort() {
        assertNull(AutoEat.choose(List.of(ROTTEN_FLESH, RAW_CHICKEN), 10, 0f, false));
        assertNull(AutoEat.choose(List.of(ROTTEN_FLESH, RAW_CHICKEN), 19, 0f, true));   // hurt, but healing already runs at 19
        assertEquals(RAW_CHICKEN, AutoEat.choose(List.of(ROTTEN_FLESH, RAW_CHICKEN), 6, 0f, false));   // the least harmful
        assertEquals(BREAD, AutoEat.choose(List.of(ROTTEN_FLESH, BREAD), 6, 0f, false));   // never while good food is left
    }

    static final AutoEat.Food GOLDEN_APPLE = new AutoEat.Food(6, 4, 9.6f, -1, true);

    @Test
    void goldenApplesOnlyWithHeartsMissing() {
        List<AutoEat.Food> c = List.of(STEAK, GOLDEN_APPLE);
        assertEquals(GOLDEN_APPLE, AutoEat.choose(c, 20, 5f, true, false, false));   // a full bar: only the apple can be eaten
        assertNull(AutoEat.choose(c, 20, 5f, true, true, false));                    // its regeneration still running
        assertNull(AutoEat.choose(c, 20, 5f, false, false, false));                  // nothing missing
        assertEquals(STEAK, AutoEat.choose(c, 12, 0f, false, false, false));         // never for hunger alone
        assertNull(AutoEat.choose(List.of(GOLDEN_APPLE), 4, 0f, false, false, false));
        assertEquals(STEAK, AutoEat.choose(c, 10, 0f, true, false, false));          // hurt, hungry: 8 + 12.8 beats 4 + 9.6
    }

    @Test
    void badFoodInARowWhileItFits() {
        List<AutoEat.Food> c = List.of(ROTTEN_FLESH, RAW_CHICKEN);
        assertNull(AutoEat.choose(c, 10, 0f, false, false, false));                  // not to start
        assertEquals(RAW_CHICKEN, AutoEat.choose(c, 10, 0f, false, false, true));    // but straight after one, on
        assertEquals(RAW_CHICKEN, AutoEat.choose(c, 18, 0f, false, false, true));    // 18 + 2 fits
        assertNull(AutoEat.choose(List.of(ROTTEN_FLESH), 17, 0f, false, false, true));   // 17 + 4 would waste
        assertEquals(BREAD, AutoEat.choose(List.of(ROTTEN_FLESH, BREAD), 6, 0f, false, false, true));   // good food ends it
    }

    static final AutoEat.Food SPIDER_EYE = new AutoEat.Food(7, 2, 3.2f, 3);

    @Test
    void hurtWithOnlyBadFoodEatsTheHungerOnesBelow18() {
        List<AutoEat.Food> c = List.of(ROTTEN_FLESH, RAW_CHICKEN, SPIDER_EYE);
        assertEquals(RAW_CHICKEN, AutoEat.choose(c, 10, 0f, true));                 // healing stops below 18
        assertEquals(RAW_CHICKEN, AutoEat.choose(c, 17, 0f, true));                 // 17 + 2 fits
        assertNull(AutoEat.choose(c, 18, 0f, true));                                // healing already runs
        assertNull(AutoEat.choose(List.of(SPIDER_EYE), 10, 0f, true));             // poison costs hearts
        assertEquals(ROTTEN_FLESH, AutoEat.choose(List.of(ROTTEN_FLESH, SPIDER_EYE), 12, 0f, true));
        assertNull(AutoEat.choose(List.of(ROTTEN_FLESH), 17, 0f, true));           // 17 + 4 would waste
    }

    static final AutoEat.Food POISON_POTATO = new AutoEat.Food(8, 2, 1.2f, 2);
    static final AutoEat.Food STEW = new AutoEat.Food(9, 6, 7.2f, 4);

    @Test
    void poisonAndStewOnlyWhenStarving() {
        List<AutoEat.Food> c = List.of(SPIDER_EYE, POISON_POTATO, STEW);
        assertNull(AutoEat.choose(c, 6, 0f, false, false, false, 20f));               // only sprinting at stake
        assertNull(AutoEat.choose(c, 3, 0f, true, false, false, 20f));                // hurt, but poison costs hearts
        assertEquals(POISON_POTATO, AutoEat.choose(c, 0, 0f, false, false, false, 20f));   // starvation: the least harmful
        assertEquals(STEW, AutoEat.choose(List.of(STEW), 0, 0f, false, false, false, 10f));
        assertNull(AutoEat.choose(List.of(STEW), 0, 0f, false, false, false, 8f));   // 4 hearts or less: Wither could kill
        assertEquals(RAW_CHICKEN, AutoEat.choose(List.of(SPIDER_EYE, RAW_CHICKEN), 6, 0f, false, false, false, 20f));
        assertNull(AutoEat.choose(List.of(SPIDER_EYE), 10, 0f, false, false, true, 20f));   // a run goes on with Hunger foods only
    }

    @Test
    void gainIsWhatTheGameKeeps() {
        assertEquals(2 + 12.8, AutoEat.gain(STEAK, 18, 0f), 1e-6);   // hunger capped at 20
        assertEquals(6 + 8.0, AutoEat.gain(GOLDEN_CARROT, 2, 0f), 1e-4);   // saturation capped at the new hunger, 8
    }
}
