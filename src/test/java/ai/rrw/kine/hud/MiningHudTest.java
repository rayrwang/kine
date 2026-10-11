package ai.rrw.kine.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The mining readout's text. */
class MiningHudTest {

    @Test
    void anUnbreakableBlockHasNoTimeLeft() {
        // bedrock: the game's progress rate is 0, so no time left can be given (it read "0.0s left")
        assertEquals("0%   -- left", MiningHud.text(0.0, 0.0));
        // a long break rolls up to minutes: 0.5 left at 0.0001 a tick is 5000 ticks, 250 s
        assertEquals("50%   4m 10s left", MiningHud.text(0.5, 0.0001));
    }
}
