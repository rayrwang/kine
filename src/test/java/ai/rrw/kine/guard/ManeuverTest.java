package ai.rrw.kine.guard;

import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** The rescue program: never more than a fast flick a tick; a turn ends where the track has come round; then a glide
 *  along the track; and the judgment's verdicts on simple cases. */
class ManeuverTest {
    @Test
    void everyMoveStaysUnderAFlickATick() {
        GlideSim sim = new GlideSim(Worlds.flat(), new Body(), -64, 3.0);
        SplittableRandom r = new SplittableRandom(7);
        float[] out = new float[2];
        for (Maneuver seed : Maneuver.seeds())
            for (int v = 0; v < 6; v++) {
                Maneuver m = v == 0 ? seed.fresh() : seed.mutate(r, 1.0);
                MoveState s = GuardLoop.gliding(0.5, 200, 0.5, 0, -0.3, 1.4);
                float yaw = 0f, pitch = -70f + 20 * v;
                for (int t = 0; t < 150; t++) {
                    m.look(s, yaw, pitch, out);
                    double travel = Math.hypot(Pilot.wrap(out[0] - yaw), out[1] - pitch);
                    assertTrue(travel <= Maneuver.MAX_TURN + 1e-3, m + " turned " + travel + " in a tick");
                    assertTrue(out[1] >= -90f && out[1] <= 90f);
                    yaw = out[0]; pitch = out[1];
                    sim.step(s, yaw, pitch, false);
                    m.after(s);
                }
            }
    }

    @Test
    void aTurnBackEndsTurnedRoundThenGlidesAlongTheTrack() {
        GlideSim sim = new GlideSim(Worlds.flat(), new Body(), -64, 3.0);
        Maneuver m = new Maneuver("test", 5, new Maneuver.Phase(200, 45, 0, 170));
        MoveState s = GuardLoop.gliding(0.5, 250, 0.5, 0, -0.1, 1.5);
        float yaw = 0f, pitch = 0f;
        float[] out = new float[2];
        int t;
        for (t = 0; t < 200 && !m.gliding(); t++) {
            m.look(s, yaw, pitch, out); yaw = out[0]; pitch = out[1];
            sim.step(s, yaw, pitch, false);
        }
        assertTrue(m.gliding(), "the turn ended");
        assertTrue(m.turned() >= 170 && m.turned() < 190, "turned " + m.turned());
        for (int k = 0; k < 10; k++) { m.look(s, yaw, pitch, out); yaw = out[0]; pitch = out[1]; sim.step(s, yaw, pitch, false); }
        assertEquals(0.0, Pilot.wrap(yaw - Pilot.trackYaw(s.vx, s.vz)), 1.0, "looks along the track");
        assertEquals(5.0, pitch, 1e-3, "at the glide pitch");
    }

    @Test
    void verdicts() {
        Guard.Config c = new Guard.Config();
        c.variations = 30;
        // open sky high up: nothing to do
        Guard g = new Guard(new GlideSim(Worlds.flat(), new Body(), -64, 3.0), c, Track.NONE);
        assertEquals(Guard.Kind.SAFE, g.watch(GuardLoop.gliding(0.5, 300, 0.5, 0, 0, 1.5), 0f, 2f, Pilot.HOLD, 1).kind());
        // a wall far ahead: damage in the horizon but time to wait
        g = new Guard(new GlideSim(Worlds.wall(200), new Body(), -64, 3.0), c, Track.NONE);
        Guard.Verdict far = g.watch(GuardLoop.gliding(0.5, 100, 0.5, 0, 0, 1.5), 0f, 2f, Pilot.HOLD, 1);
        assertEquals(Guard.Kind.WAIT, far.kind(), "danger " + far.danger());
        // the same wall close: fire, with a rescue that takes no damage
        Guard.Verdict near = g.watch(GuardLoop.gliding(0.5, 100, 165, 0, 0, 1.5), 0f, 2f, Pilot.HOLD, 1);
        assertEquals(Guard.Kind.FIRE, near.kind());
        assertTrue(near.rescue().full, "rescue " + near.rescue());
        // falling into the void, nothing to land on: no rescue helps, the controls are left alone
        g = new Guard(new GlideSim(Worlds.of((x, y, z) -> ai.rrw.kine.move.BlockInfo.AIR), new Body(), -64, 3.0), c, Track.NONE);
        assertEquals(Guard.Kind.NO_HELP, g.watch(GuardLoop.gliding(0.5, -110, 0.5, 0, -2.5, 0.1), 0f, 80f, Pilot.HOLD, 1).kind());
        // hands off is clean and the rescue is done: hand back
        g = new Guard(new GlideSim(Worlds.flat(), new Body(), -64, 3.0), c, Track.NONE);
        Maneuver done = new Maneuver("done", 5);
        assertEquals(Guard.Kind.HANDBACK, g.steer(GuardLoop.gliding(0.5, 300, 0.5, 0, 0, 1.2), 0f, 5f, done, 1).kind());
        // but not nose down in a dive
        assertEquals(Guard.Kind.STEER, g.steer(GuardLoop.gliding(0.5, 300, 0.5, 0, -1, 1.2), 0f, 40f, done, 1).kind());
    }
}
