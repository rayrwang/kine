package ai.rrw.kine.autoflight;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** An aimed landing borrows the destination and gives the trip's back when it ends. Nav's state is static,
 *  so each test sets the trip it starts from. */
class NavTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();   // Nav's hazard list holds blocks
    }

    // an aimed landing on the spot (120, 80) from 20 blocks out and 80 up
    static void aim() { Nav.landAt(120, 70, 80, 100.5, 150, 80.5, -0.5); }
    static void aim2() { Nav.landAt(-40, 64, 300, -60.5, 140, 300.5, -0.5); }

    static void assertTarget(int x, int z) {
        assertTrue(Nav.hasTarget());
        assertEquals(x, Nav.targetX());
        assertEquals(z, Nav.targetZ());
    }

    @Test
    void theSpotIsFlownToAndTheTripComesBackAtTouchdown() {
        Nav.setTarget(5000, -3000);
        aim();
        assertTarget(120, 80);   // the HUD and the steering follow the spot
        Nav.touchedDown();
        assertTarget(5000, -3000);
        float toTrip = (float) Math.toDegrees(Math.atan2(-5000.5, -2999.5));
        assertEquals(toTrip, Nav.desiredYaw(0, 0), 1e-3);   // managed again, flying to the trip's destination
    }

    @Test
    void aSelectedHeadingComesBackAfterTheLanding() {
        Nav.setHeading(90);
        boolean hadTarget = Nav.hasTarget();
        int x = Nav.targetX(), z = Nav.targetZ();
        aim();
        Nav.touchedDown();
        assertEquals(Mth.wrapDegrees(90f + 180f), Nav.desiredYaw(0, 0), 1e-3);   // SELECTED at 090 again
        assertEquals(90f, Nav.selectedHeading(), 0f);
        assertEquals(hadTarget, Nav.hasTarget());
        assertEquals(x, Nav.targetX());
        assertEquals(z, Nav.targetZ());
    }

    @Test
    void aSecondAimKeepsTheFirstTrip() {
        Nav.setTarget(5000, -3000);
        aim();
        aim2();
        assertTarget(-40, 300);
        Nav.touchedDown();
        assertTarget(5000, -3000);
    }

    @Test
    void leavingTheLandingFromTheNavScreenLeavesNoSpotBehind() {
        Nav.setTarget(5000, -3000);
        aim();
        Nav.enterSelected();
        Nav.enterManaged();
        assertTarget(5000, -3000);   // not the aimed spot

        aim();
        Nav.off();
        Nav.enterManaged();
        assertTarget(5000, -3000);
    }

    @Test
    void aNewDestinationDuringTheLandingReplacesTheTrip() {
        Nav.setTarget(5000, -3000);
        aim();
        Nav.setTarget(-200, 400);
        assertTarget(-200, 400);
        Nav.touchedDown();   // the landing already ended: nothing comes back over the new destination
        assertTarget(-200, 400);
    }
}
