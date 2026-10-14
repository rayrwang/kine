package ai.rrw.kine.autoflight;

import ai.rrw.kine.guard.GlideSim;
import ai.rrw.kine.guard.Guard;
import ai.rrw.kine.guard.Pilot;
import ai.rrw.kine.guard.Track;
import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.Shape;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The autopilot's own commands as the guardian rolls them out (ApModel) must not predict damage where the autopilot
 * is safe, or the guardian would fire on every landing and cruise: the landing law from many heights and speeds
 * touches down with none in the movement copy; the porpoise at cruise over flat ground takes none; and the guardian,
 * watching each of these hands off, never fires.
 */
class ApModelTest {
    static final BlockInfo STONE = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
    static final BlockGrid FLAT = new BlockGrid(0, 0, 0, 0, 0, 0) {
        @Override public BlockInfo get(int x, int y, int z) { return y < 64 ? STONE : BlockInfo.AIR; }
    };

    static MoveState gliding(double x, double y, double z, double vx, double vy, double vz) {
        MoveState s = new MoveState();
        s.x = x; s.y = y; s.z = z; s.vx = vx; s.vy = vy; s.vz = vz; s.fallFlying = true; s.glidePose = true;
        return s;
    }

    static ApModel landing(double sx, double top, double sz) {
        ApModel m = new ApModel();
        m.mode = ApModel.LANDING;
        m.landing = new LandingModel.Law();
        m.spotX = sx; m.spotTop = top; m.spotZ = sz;
        return m;
    }

    @Test
    void theLandingLawTouchesDownWithNoDamageAndTheGuardianStaysOut() {
        GlideSim sim = new GlideSim(FLAT, new Body(), -64, 3.0);
        Guard.Config cfg = new Guard.Config();
        cfg.variations = 20;
        int cases = 0, fires = 0;
        double worst = 0;
        float[] out = new float[2];
        for (double h : new double[]{8, 20, 40, 80, 150, 250})
            for (double speed : new double[]{0.3, 1.0, 1.8})
                for (double dist : new double[]{0, 30, 120}) {
                    MoveState s = gliding(0.5 - dist, 64 + h, 0.5, speed, -0.2, 0);
                    ApModel m = landing(0.5, 64, 0.5);
                    float yaw = -90f, pitch = 5f;
                    double dmg = 0;
                    boolean fired = false;
                    for (int t = 0; t < 3000 && s.fallFlying; t++) {
                        if (t % 5 == 0 && !fired) {
                            Guard.Verdict v = new Guard(sim, cfg, Track.NONE).watch(s, yaw, pitch, m, t);
                            if (v.kind() == Guard.Kind.FIRE) { fired = true; fires++;
                                System.out.printf(Locale.ROOT, "LANDING fire h %.0f speed %.1f dist %.0f at t %d: %s%n", h, speed, dist, t, v.danger()); }
                        }
                        m.look(s, yaw, pitch, out);
                        yaw = out[0]; pitch = out[1];
                        dmg += sim.step(s, yaw, pitch, false);
                        m.after(s);
                    }
                    worst = Math.max(worst, dmg);
                    cases++;
                    if (dmg > 0) System.out.printf(Locale.ROOT, "LANDING hurt h %.0f speed %.1f dist %.0f: %.1f%n", h, speed, dist, dmg);
                }
        System.out.printf(Locale.ROOT, "LANDING %d landings, worst damage %.1f, guardian fires %d%n", cases, worst, fires);
        assertEquals(0.0, worst, "every landing touches down unhurt");
        assertEquals(0, fires, "the guardian never fires on a landing");
    }

    @Test
    void thePorpoiseAtCruiseOverFlatGroundIsLeftAlone() {
        GlideSim sim = new GlideSim(FLAT, new Body(), -64, 3.0);
        Guard.Config cfg = new Guard.Config();
        cfg.variations = 20;
        for (boolean climbing : new boolean[]{true, false}) {
            ApModel m = new ApModel();
            m.mode = ApModel.NAV; m.selected = true; m.selectedYaw = 0;
            if (climbing) m.law.restartClimb(); else m.law.restartHold();
            m.floor = 200;
            MoveState s = gliding(0.5, climbing ? 150 : 260, 0.5, 0, -0.2, 1.5);
            float yaw = 0f, pitch = 0f;
            float[] out = new float[2];
            int fires = 0;
            for (int t = 0; t < 3000; t++) {
                if (t % 10 == 0 && new Guard(sim, cfg, Track.NONE).watch(s, yaw, pitch, m, t).kind() == Guard.Kind.FIRE) fires++;
                m.look(s, yaw, pitch, out);
                yaw = out[0]; pitch = out[1];
                assertEquals(0.0, sim.step(s, yaw, pitch, false), "the porpoise hurt at t " + t);
                m.after(s);
            }
            assertEquals(0, fires, (climbing ? "climb" : "hold") + ": the guardian fired");
        }
    }
}
