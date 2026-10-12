package ai.rrw.kine.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** MouseTurn's turn and undo on a model of the game's own turning: Entity.turn, and AbstractBoat.clampRotation
 *  for a boat's passenger (26.1.2), in float as the game does it. */
class MouseTurnTest {

    static final double SENS = Math.pow(0.5 * 0.6F + 0.2F, 3) * 8.0;           // MouseTurn.sens at the default sensitivity
    static final double SCOPED = Math.pow(0.5 * 0.6F + 0.2F, 3);              // the same while scoping (no 8)

    static float wrap(float a) {                                               // Mth.wrapDegrees(float)
        float n = a % 360.0F;
        if (n >= 180.0F) n -= 360.0F;
        if (n < -180.0F) n += 360.0F;
        return n;
    }

    static final class Player {
        float yRot, xRot;
        Float boatYaw;                                                         // riding a boat with this heading, or null

        void turn(double xo, double yo) {                                      // Entity.turn
            xRot = Math.max(-90.0F, Math.min(90.0F, xRot + (float) yo * 0.15F));
            yRot = yRot + (float) xo * 0.15F;
            if (boatYaw != null) clampRotation();                              // vehicle.onPassengerTurned
        }

        void clampRotation() {                                                 // AbstractBoat.clampRotation
            float delta = wrap(yRot - boatYaw);
            float target = Math.max(-105.0F, Math.min(105.0F, delta));
            yRot = yRot + target - delta;
        }
    }

    /** MouseTurn.toward on the model; returns the moved yaw and pitch it records. */
    static float[] toward(Player p, float yaw, float pitch, double sens) {
        double deg = sens * 0.15;
        int kx = MouseTurn.yawCounts(wrap(yaw - p.yRot), deg);
        int ky = MouseTurn.pitchCounts(Math.max(-90f, Math.min(90f, pitch)) - p.xRot, p.xRot, deg);
        float y0 = p.yRot, x0 = p.xRot;
        if (kx != 0 || ky != 0) p.turn(kx * sens, ky * sens);
        return new float[]{p.yRot - y0, p.xRot - x0};
    }

    /** MouseTurn.undo on the model. */
    static void undo(Player p, float[] moved, double sens) {
        double deg = sens * 0.15;
        int kx = MouseTurn.yawCounts(-moved[0], deg);
        int ky = MouseTurn.pitchCounts(-moved[1], p.xRot, deg);
        if (kx != 0 || ky != 0) p.turn(kx * sens, ky * sens);
    }

    @Test
    void onFootTheViewComesBack() {
        Player p = new Player();
        p.yRot = 12.34f; p.xRot = 5.0f;
        float[] moved = toward(p, 150f, -20f, SENS);
        assertTrue(Math.abs(moved[0]) > 100f);
        undo(p, moved, SENS);
        assertEquals(12.34f, p.yRot, 1e-4);
        assertEquals(5.0f, p.xRot, 1e-4);
    }

    @Test
    void aBoatsClampDoesNotLeaveTheViewMoved() {
        // facing ahead in a boat, a target behind and to the right: the boat stops the turn at 105 degrees,
        // and turning back by the counts asked for would overshoot to -45
        Player p = new Player();
        p.boatYaw = 0f; p.yRot = 0f; p.xRot = 0f;
        for (int tick = 0; tick < 4; tick++) {
            float[] moved = toward(p, 150f, 0f, SENS);
            assertEquals(105f, p.yRot, 0.1f);
            undo(p, moved, SENS);
            assertEquals(0f, p.yRot, SENS * 0.15 / 2);
        }
    }

    @Test
    void aSpyglassStartedMeanwhileDoesNotLeaveTheViewMoved() {
        // the turn at the normal count size, the undo after a spyglass came up (a count is 1/8 the size)
        Player p = new Player();
        float[] moved = toward(p, 60f, 20f, SENS);
        undo(p, moved, SCOPED);
        assertEquals(0f, p.yRot, SCOPED * 0.15 / 2);
        assertEquals(0f, p.xRot, SCOPED * 0.15 / 2);
    }

    @Test
    void aTurnTheGameMadeMeanwhileStays() {
        // the boat turns its passenger 3 degrees during the tick (AbstractBoat.positionRider): the view keeps that
        Player p = new Player();
        p.boatYaw = 0f; p.yRot = 0f; p.xRot = 0f;
        float[] moved = toward(p, 60f, 0f, SENS);
        p.boatYaw += 3f;
        p.yRot += 3f;
        undo(p, moved, SENS);
        assertEquals(3f, p.yRot, SENS * 0.15 / 2);
    }

    @Test
    void pitchStaysInsideTheLimits() {
        double deg = SENS * 0.15;
        int ky = MouseTurn.pitchCounts(90.0 - 89.95, 89.95f, deg);   // a whole count would pass 90
        assertTrue(89.95f + ky * deg <= 90.0);
        ky = MouseTurn.pitchCounts(-90.0 + 89.95, -89.95f, deg);
        assertTrue(-89.95f + ky * deg >= -90.0);
    }

    @Test
    void aTurnIsCutToTheLimitAlongTheStraightLine() {
        // within the limit: all the way, the yaw the short way round
        float[] r = MouseTurn.limit(170f, 0f, -170f, 10f, 30.0);
        assertEquals(190f, r[0], 1e-4);
        assertEquals(10f, r[1], 1e-4);
        // 90 to the side and 40 down: cut to 30 of travel in the same direction
        r = MouseTurn.limit(0f, 0f, 90f, 40f, 30.0);
        assertEquals(30.0, MouseTurn.travel(0, 0, r[0], r[1]), 1e-4);
        assertEquals(40.0 / 90.0, r[1] / r[0], 1e-5);
        assertEquals(Math.hypot(20, 5), MouseTurn.travel(355, 0, 15, 5), 1e-9);
    }

    @Test
    void wholeTurnsKeepTheYawSentContinuous() {
        assertEquals(0f, MouseTurn.wholeTurns(10f));
        assertEquals(0f, MouseTurn.wholeTurns(-179.9f));
        assertEquals(360f, MouseTurn.wholeTurns(182.25f));
        assertEquals(-360f, MouseTurn.wholeTurns(-200f));
        assertEquals(720f, MouseTurn.wholeTurns(890f));
        assertEquals(1080f, MouseTurn.wholeTurns(900f));   // Mth.wrapDegrees maps to [-180, 180): 900 = 1080 - 180
        // The kill aura round a crowd, against Grim on a local server (2026-10-12): the rotation sent 182.25 off a view
        // at 0. Held as 182.25 - 360 off a view moved by +360, the next tick's 29.55 further sends 211.80, a turn of
        // 29.55; the offset alone wrapped sent -148.20, a raw jump of 330.45 (Grim's AimModulo360).
        float view = 0f, applied = 182.25f;
        float whole = MouseTurn.wholeTurns(applied);
        float nextSent = (view + whole) + (applied - whole) + 29.55f;
        assertEquals(211.8f, nextSent, 1e-3);
        assertEquals(29.55f, nextSent - (view + applied), 1e-3);
    }
}
