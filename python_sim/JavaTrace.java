import ai.rrw.kine.autoflight.FlightModel3D;
import ai.rrw.kine.autoflight.HoldModel;
import ai.rrw.kine.autoflight.LandingModel;
import ai.rrw.kine.autoflight.LandingTime;
import ai.rrw.kine.autoflight.PorpoiseLaw;
import ai.rrw.kine.autoflight.TakeoffModel;
import ai.rrw.kine.fireworks.RocketHit;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Flies the mod's own compiled flight classes and prints what they do, one CSV line per sample, for
 * validate_against_java.py to compare with kine_flight.py. Run from the repository root after a build:
 *   java -cp build/classes/java/main python_sim/JavaTrace.java
 * (validate_against_java.py does both). Every case here has a twin in validate_against_java.py.
 */
public class JavaTrace {
    static void p(String c, Object... v) {
        StringBuilder b = new StringBuilder(c);
        for (Object o : v) b.append(',').append(o instanceof Double d ? String.format(Locale.ROOT, "%.17g", d) : o);
        System.out.println(b);
    }

    public static void main(String[] a) throws Exception {
        // 1. A climb from 200 toward 350, straight along +z: the law's climbs, then its capture of the hold.
        var s = new FlightModel3D.State(0, 200, 0, 0, -0.5, 1.5, 350, 20, true);
        s.cmd = 40;
        for (int t = 1; t <= 4000; t++) {
            FlightModel3D.step(s, 0.0);
            p("climb", t, s.y, s.z, s.vy, s.pitch, s.cmd, s.phase, s.climbing);
        }
        // 2. A turn toward a fixed heading (as FlightModel3DTest's second golden trace).
        s = new FlightModel3D.State(0, 300, 0, 0, -0.5, 1.5, 300, 20, false);
        s.cmd = 38;
        for (int t = 1; t <= 600; t++) {
            FlightModel3D.step(s, 90.0);
            p("turn", t, s.x, s.y, s.z, s.pitch);
        }
        // 3. The holding circle over a fix at the origin, from steady straight flight (as HoldModelTest).
        s = new FlightModel3D.State(0, 400, 0, 0, -0.3, 1.5, 350, 30, false);
        s.cmd = PorpoiseLaw.ALT_HOLD.dive();
        for (int t = 0; t < 4000; t++) FlightModel3D.step(s, 0.0);
        s.x = 0; s.z = 0;
        for (int t = 1; t <= 3000; t++) {
            FlightModel3D.stepLook(s, HoldModel.lookYaw(s.x, s.z, 0, 0, FlightModel3D.velYaw(s.vx, s.vz), s.climbing));
            p("hold", t, s.x, s.y, s.z, s.pitch);
        }
        // 4. An aimed landing from cruise height, 200 out.
        var r = LandingModel.simulate(new LandingModel.Law(), 0, 350, -200, 0, 0, 1.5, 0, 10, 3.5, 64, 40.5, 4000, 4000);
        p("landing_end", r.touched(), r.offset(), r.damage(), r.ticks());
        for (int i = 0; i < r.count(); i++) p("landing", i, r.xs()[i], r.ys()[i], r.zs()[i]);
        // 5. A rocket takeoff from flat ground at 64, 27-tick burns.
        var plan = TakeoffModel.fromGround(0.5, 64, 0.5, 0, 350, 27, (x, z) -> 64.0, 319, 3000);
        p("takeoff_end", plan.rockets(), plan.reachesCruise(), plan.ticks(), plan.count());
        for (int i = 0; i < plan.count(); i++) p("takeoff", i, plan.xs()[i], plan.ys()[i], plan.zs()[i]);
        // 6. The rocket self-hit: the life at the hit, by yaw, pitch, arm, at 1.7 blocks a tick.
        Method hitTick = RocketHit.class.getDeclaredMethod("hitTick", double.class, double.class, boolean.class, double.class);
        hitTick.setAccessible(true);
        for (double pitch : new double[] {-85, -70}) for (int arm = 0; arm < 2; arm++) for (int yaw = 0; yaw < 360; yaw++)
            p("hit", yaw, pitch, arm, hitTick.invoke(null, (double) yaw, pitch, arm == 1, 1.7));
        double[] pitches = {-85, -80, -75, -70, -65};
        for (int w = 0; w < 360; w += 7) p("safeyaw", w, (double) RocketHit.safeYaw(w, pitches, true));
        // 7. The landing-time table.
        for (double h = 0; h < 2300; h += 7.3) p("landtime", h, LandingTime.seconds(h));
    }
}
