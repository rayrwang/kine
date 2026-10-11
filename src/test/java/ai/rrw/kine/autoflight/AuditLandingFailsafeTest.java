package ai.rrw.kine.autoflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * From the cross-cutting audit of 2026-10-11 (finding 1), adapted to the fix: the durability failsafe against the
 * autopilot's own aimed landing.
 *
 * <p>An aimed landing (Landing.beforeKeybinds -> Nav.landAt + Autopilot.engageForLanding) flies with the autopilot
 * engaged and Nav.landing() true. This flies the landing as LandingModel.simulate does, applies the failsafe's rule
 * (warning = flight seconds left below ElytraGuard.landingReserveSeconds(height above the spot), the grace counted by
 * ElytraGuard.graceAfter), and lets the elytra lose one point a second as LivingEntity.updateFallFlying damages it.
 * The property: a landing that touches down with flight time to spare is not cut off by the failsafe, while the same
 * warning with the autopilot only cruising still disconnects after the grace.
 */
class AuditLandingFailsafeTest {

    /** Ticks until the failsafe fires (-1: never) and until touchdown, flying the landing law down from 286 above
     *  the spot with 59 s of flight left; `landing` is what Nav.landing() reads throughout. */
    static int[] fly(boolean landing) {
        double secs = 60 - 1;                           // ElytraLife.flightSeconds at 60 durability, no Unbreaking
        LandingModel.Law law = new LandingModel.Law();
        double x = 0.5, y = 286, z = 0.5 - 100, yaw = 0, pitch = 10;
        double[] v = { 0, -0.2, 1.5 };
        int grace = 0, firedAt = -1, touchdown = -1;
        for (int t = 1; t <= 6000; t++) {
            law.update(x, y, z, v[1], 0.5, 0, 0.5);
            yaw += Math.max(-LandingModel.YAW_RATE, Math.min(LandingModel.YAW_RATE, FlightModel3D.wrap180(law.yaw - yaw)));
            pitch = FlightModel3D.ease(pitch, law.pitch);
            FlightModel3D.physicsStep(yaw, pitch, v);
            x += v[0]; y += v[1]; z += v[2];
            if (t % 20 == 0) secs -= 1;
            boolean warning = secs < ElytraGuard.landingReserveSeconds(Math.max(0, y));
            grace = ElytraGuard.graceAfter(grace, warning, true, landing);
            if (grace >= ElytraGuard.GRACE_TICKS && firedAt < 0) firedAt = t;
            if (y <= 0) { touchdown = t; break; }
        }
        assertTrue(touchdown > 0 && secs > 0, "the landing touches down with flight time left: tick " + touchdown + ", " + secs + " s left");
        return new int[] { firedAt, touchdown };
    }

    @Test
    void theFailsafeDoesNotCutOffALandingThatTouchesDownInTime() {
        assertTrue(60 - 1 < ElytraGuard.landingReserveSeconds(286), "the warning is up when the landing starts");
        int[] r = fly(true);
        assertEquals(-1, r[0], "the failsafe disconnected at tick " + r[0] + ", " + (r[1] - r[0]) + " ticks before a safe touchdown");
    }

    @Test
    void withoutALandingTheGraceStillRunsOut() {
        assertEquals(ElytraGuard.GRACE_TICKS, fly(false)[0], "the autopilot only cruising: the failsafe fires after the grace");
    }

    @Test
    void theGraceHoldsDuringALandingAndStartsOverWhenThePilotTakesOver() {
        assertEquals(41, ElytraGuard.graceAfter(40, true, true, false));
        assertEquals(40, ElytraGuard.graceAfter(40, true, true, true), "holds during the autopilot's landing");
        assertEquals(0, ElytraGuard.graceAfter(40, true, false, true), "the pilot flies");
        assertEquals(0, ElytraGuard.graceAfter(40, false, true, false), "the warning cleared");
    }
}
