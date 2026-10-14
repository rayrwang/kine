package ai.rrw.kine.combat;

import ai.rrw.kine.combat.BowAura.InFlight;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The bow aura's count of its own shots on their way (BowAura.pending, afterHurtWindow). */
class BowAuraInFlightTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();   // BowAura's statics hold items
    }

    @Test
    void pendingCountsOnlyShotsStillOnTheirWayToThatMob() {
        List<InFlight> shots = List.of(new InFlight(1, 110, 7.0), new InFlight(1, 95, 6.0), new InFlight(2, 120, 8.0));
        assertEquals(7.0, BowAura.pending(1, 100, shots), 1e-9);   // the one that landed at 95 has done its part
        assertEquals(8.0, BowAura.pending(2, 100, shots), 1e-9);
        assertEquals(0.0, BowAura.pending(3, 100, shots), 1e-9);
    }

    @Test
    void aHitInTheHurtWindowOfAnotherCountsOnlyItsExcess() {
        List<InFlight> shots = List.of(new InFlight(1, 110, 8.0));
        assertEquals(0.0, BowAura.afterHurtWindow(1, 115, 8.0, shots), 1e-9);    // 5 ticks after: nothing more
        assertEquals(11.0, BowAura.afterHurtWindow(1, 115, 19.0, shots), 1e-9);  // a bigger hit: its excess
        assertEquals(8.0, BowAura.afterHurtWindow(1, 120, 8.0, shots), 1e-9);    // 10 ticks after: in full
        assertEquals(8.0, BowAura.afterHurtWindow(2, 112, 8.0, shots), 1e-9);    // another mob
    }
}
