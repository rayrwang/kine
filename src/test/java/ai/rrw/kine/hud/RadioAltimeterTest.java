package ai.rrw.kine.hud;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.*;

/** The radio altimeter's scan down a column of blocks. */
class RadioAltimeterTest {

    // a column: block height -> the top of that block above its base (1 for a full block, 0.5 a bottom slab,
    // 1.5 a fence); air elsewhere
    static IntToDoubleFunction column(Map<Integer, Double> blocks) {
        return y -> blocks.getOrDefault(y, Double.NaN);
    }

    @Test
    void theGroundUnderfootReadsZero() {
        assertEquals(0, RadioAltimeter.agl(64.0, -64, column(Map.of(63, 1.0))));    // a full block
        assertEquals(0, RadioAltimeter.agl(64.5, -64, column(Map.of(64, 0.5))));    // a bottom slab (read 1)
        assertEquals(0, RadioAltimeter.agl(65.5, -64, column(Map.of(64, 1.5))));    // on a fence (read 1)
        assertEquals(0, RadioAltimeter.agl(64.0, -64, column(Map.of(64, 1.0, 63, 1.0))));   // feet in tall grass
        // a one-slab bridge over a ravine 40 deep (read 41)
        assertEquals(0, RadioAltimeter.agl(64.5, -64, column(Map.of(64, 0.5, 24, 1.0))));
    }

    @Test
    void heightAboveTheSurface() {
        assertEquals(10, RadioAltimeter.agl(74.8, -64, column(Map.of(64, 0.5))));   // 10.3 above a slab's top
        assertEquals(7, RadioAltimeter.agl(70.0, -64, column(Map.of(62, 1.0))));    // water: the whole block
        assertEquals(-1, RadioAltimeter.agl(200.0, 0, column(Map.of())));           // the void: unknown
    }
}
