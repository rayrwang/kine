package ai.rrw.kine.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The clutch's aim point on the surface under the hitbox. */
class WaterClutchTest {

    @Test
    void aimsInsideALedgeEdgeNotOnIt() {
        // the hitbox centre is past the edge at 0.5: aim 0.1 inside it, not at it
        assertEquals(0.4, WaterClutch.aimInside(0.8, 0.0, 0.5), 1e-12);
        assertEquals(0.1, WaterClutch.aimInside(-0.3, 0.0, 0.5), 1e-12);
    }

    @Test
    void aimsAtTheCentreWhenItIsWellInside() {
        assertEquals(0.25, WaterClutch.aimInside(0.25, 0.0, 0.5), 1e-12);
    }

    @Test
    void aNarrowSurfaceIsAimedAtItsMiddle() {
        // less than 0.2 of a surface under the hitbox: its middle
        assertEquals(0.5, WaterClutch.aimInside(0.9, 0.45, 0.55), 1e-12);
        // a fence post's top (0.375 to 0.625) with the hitbox centre off to one side: 0.1 inside its near end
        assertEquals(0.475, WaterClutch.aimInside(0.0, 0.375, 0.625), 1e-12);
    }
}
