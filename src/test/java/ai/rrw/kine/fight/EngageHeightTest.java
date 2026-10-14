package ai.rrw.kine.fight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The engage test's height guard: a mob far above or below you, as from the top of a tower, doesn't engage. */
class EngageHeightTest {
    @Test
    void aMobAtTheFootOfATowerDoesNotEngage() {
        assertFalse(FightSnapshot.engagesAt(MobSim.Kind.BOW, 14.0, -12.0));
        assertFalse(FightSnapshot.engagesAt(MobSim.Kind.MELEE, 7.0, -6.0));
        assertFalse(FightSnapshot.engagesAt(MobSim.Kind.MELEE, 7.0, 5.0));
    }

    @Test
    void aMobWithinFourBlocksUpOrDownStillEngagesAtItsDistance() {
        assertTrue(FightSnapshot.engagesAt(MobSim.Kind.MELEE, 6.0, 0.0));
        assertTrue(FightSnapshot.engagesAt(MobSim.Kind.MELEE, 6.0, -4.0));
        assertTrue(FightSnapshot.engagesAt(MobSim.Kind.MELEE, 6.0, 4.0));
        assertTrue(FightSnapshot.engagesAt(MobSim.Kind.BOW, 15.0, 2.0));
        assertFalse(FightSnapshot.engagesAt(MobSim.Kind.MELEE, 9.0, 0.0));
        assertFalse(FightSnapshot.engagesAt(MobSim.Kind.BOW, 17.0, 0.0));
    }
}
