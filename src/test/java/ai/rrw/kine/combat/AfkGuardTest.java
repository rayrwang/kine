package ai.rrw.kine.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What the AFK guard counts as a key input. */
class AfkGuardTest {

    @Test
    void aHeldKeyIsInput() {
        assertTrue(AfkGuard.keyInput(false, true, true));
        assertFalse(AfkGuard.keyInput(false, false, false));
    }

    @Test
    void aToggleLatchedOnIsNotInputButAPressIs() {
        // Toggle Sprint pressed once long ago: down stays latched, and that alone is nobody at the keys
        assertFalse(AfkGuard.keyInput(true, true, true));
        assertFalse(AfkGuard.keyInput(true, false, false));
        // a press flips it, either way
        assertTrue(AfkGuard.keyInput(true, true, false));
        assertTrue(AfkGuard.keyInput(true, false, true));
    }
}
