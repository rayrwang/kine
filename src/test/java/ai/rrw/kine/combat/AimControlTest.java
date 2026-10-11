package ai.rrw.kine.combat;

import ai.rrw.kine.combat.HitChance.Box;
import ai.rrw.kine.combat.HitChance.Shot;
import ai.rrw.kine.combat.HitChance.Target;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Auto aim's turn toward the solved aim, on a moving target, in whole mouse counts. */
class AimControlTest {
    static final double SPAWN_Y = (double) 1.62F - (double) 0.1F;
    static final Vec3 SPAWN = new Vec3(0, SPAWN_Y, 0);
    static final Ballistics.Spec ARROW = new Ballistics.Spec(0.05, 0.99F, 3.0, 0.0, false, true);
    static final List<Box> GROUND = List.of(new Box(-4000, -1, -4000, 4000, 0, 4000));
    static final double COUNT = 0.15;   // degrees per mouse count at the default sensitivity

    static float[] yawPitch(Vec3 d) {
        d = d.normalize();
        return new float[]{ (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) Math.toDegrees(Math.asin(-d.y)) };
    }

    // one axis turned by the nearest whole number of counts, as MouseTurn does
    static float turn(float view, float step, double count) {
        return (float) (view + Math.round(step / count) * count);
    }

    // A player (0.6 by 1.8) strafing at 0.28 blocks a tick across the line of fire, 20 blocks out, tracked for 40
    // ticks. Returns the view after the last turn, the solution of that tick and the target box then.
    record Tracked(float yaw, float pitch, float[] solution, Box box, double worstGap) {}

    static Tracked track(boolean feedForward) {
        Vec3 feet0 = new Vec3(-5, 0, 20), v = new Vec3(0.28, 0, 0);
        float yaw = 0, pitch = 0, lastYaw = 0, lastPitch = 0;
        float[] s = null;
        double worst = 0;
        Box box = null;
        for (int n = 0; n < 40; n++) {
            Vec3 feet = feet0.add(v.scale(n));
            box = Box.ofFeet(feet.x, feet.y, feet.z, 0.6, 1.8);
            Vec3 c = new Vec3(box.centerX(), box.centerY(), box.centerZ());
            s = yawPitch(Ballistics.solveAim(SPAWN, c, v, ARROW, Vec3.ZERO, true).subtract(SPAWN));
            float dYaw, dPitch;
            if (feedForward) {
                dYaw = AimControl.step(s[0] - yaw, n > 0 ? s[0] - lastYaw : 0f, COUNT);
                dPitch = AimControl.step(s[1] - pitch, n > 0 ? s[1] - lastPitch : 0f, COUNT);
            } else {   // the ease alone, as before
                dYaw = (s[0] - yaw) * AimControl.SMOOTH;
                dPitch = (s[1] - pitch) * AimControl.SMOOTH;
            }
            yaw = turn(yaw, dYaw, COUNT);
            pitch = turn(pitch, dPitch, COUNT);
            lastYaw = s[0]; lastPitch = s[1];
            if (n >= 15) worst = Math.max(worst, Math.max(Math.abs(yaw - s[0]), Math.abs(pitch - s[1])));
        }
        return new Tracked(yaw, pitch, s, box, worst);
    }

    @Test
    void aStrafingTargetIsTrackedToWithinHalfACount() {
        Tracked ff = track(true), ease = track(false);
        assertTrue(ff.worstGap() <= COUNT / 2 + 1e-3, "feed-forward gap " + ff.worstGap());
        assertTrue(ease.worstGap() > 1.0, "the ease alone trails by about 1.9 times the motion, got " + ease.worstGap());
    }

    @Test
    void theTrackedShotHitsTheMovingTarget() {
        Tracked t = track(true);
        Vec3 d = Ballistics.launchDir(t.yaw(), t.pitch(), ARROW);
        Shot shot = new Shot(0, SPAWN_Y, 0, d.x, d.y, d.z, 3.0, 1.0, 0, 0, 0);
        assertTrue(HitChance.hits(shot, 0, 0, 0, new Target(t.box(), 0.28, 0, 0), GROUND, 400),
                   "a shot with no spread, released along the tracked view, hits the strafing player");
    }

    @Test
    void aSmallGapIsClosedInWholeCountsNotLeftAsADeadZone() {
        double count = 0.15 * 8 * Math.pow(1.0 * 0.6 + 0.2, 3);   // the highest sensitivity: 0.61 degrees a count
        float gap = 0.8f;
        assertEquals(0, Math.round(gap * AimControl.SMOOTH / count), "the ease alone rounds to no turn");
        float step = AimControl.step(gap, 0f, count);
        assertEquals(gap, step, 1e-6);
        assertTrue(Math.abs(gap - turn(0, step, count)) <= count / 2 + 1e-6);
    }

    @Test
    void oneTicksTurnIsCapped() {
        assertEquals(AimControl.MAX_STEP, AimControl.step(40f, 0f, COUNT));
        assertEquals(-AimControl.MAX_STEP, AimControl.step(-40f, -30f, COUNT));
    }
}
