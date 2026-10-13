package ai.rrw.kine.combat;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The turning course (a flier on a steady curve, the ender dragon) and the aim solve's lead along it. */
class DragonCourseTest {
    static final Ballistics.Spec ARROW = new Ballistics.Spec(0.05, 0.99, 3.0, 0.0, false, true);

    // the move across of each tick, the last one turned by `turn`, summed step by step
    static Vec3 stepped(Vec3 motion, double turn, int n) {
        double x = 0, z = 0, mx = motion.x, mz = motion.z, c = Math.cos(turn), s = Math.sin(turn);
        for (int i = 0; i < n; i++) {
            double nx = mx * c - mz * s, nz = mx * s + mz * c;
            mx = nx; mz = nz;
            x += mx; z += mz;
        }
        return new Vec3(x, motion.y * n, z);
    }

    @Test
    void theCurveIsTheSumOfTurnedMoves() {
        Vec3 m = new Vec3(0.6, 0.05, 0.2);
        for (double deg : new double[]{-17.5, -4, 0.3, 4, 12}) {
            Ballistics.Course c = Ballistics.Course.turning(m, Math.toRadians(deg));
            for (int n = 0; n <= 60; n++) {
                Vec3 a = c.at(n), b = stepped(m, Math.toRadians(deg), n);
                assertEquals(b.x, a.x, 1e-9, deg + " deg, tick " + n);
                assertEquals(b.y, a.y, 1e-9);
                assertEquals(b.z, a.z, 1e-9);
            }
            Vec3 half = c.at(10.5), lo = c.at(10), hi = c.at(11);
            assertEquals((lo.x + hi.x) / 2, half.x, 1e-12);
            assertEquals((lo.z + hi.z) / 2, half.z, 1e-12);
        }
    }

    @Test
    void noTurnIsStraight() {
        Vec3 m = new Vec3(0.7, -0.1, 0.3);
        Ballistics.Course c = Ballistics.Course.turning(m, 0.0), s = Ballistics.Course.straight(m);
        for (double t : new double[]{0, 1, 2.5, 30}) assertEquals(s.at(t), c.at(t));
    }

    @Test
    void aWholeCircleComesBack() {
        Ballistics.Course c = Ballistics.Course.turning(new Vec3(0.8, 0, 0), 2 * Math.PI / 90);
        Vec3 back = c.at(90);
        assertEquals(0, back.x, 1e-9);
        assertEquals(0, back.z, 1e-9);
    }

    // closest the arrow's tick-k move comes to the target where it is at tick k, over the flight
    static double miss(Vec3 eye, Ballistics.Solution sol, Vec3 centre, Ballistics.Course course) {
        List<Vec3> path = Ballistics.simulate(eye, Ballistics.launchVel(sol.yaw(), sol.pitch(), ARROW, Vec3.ZERO, true), ARROW, null).path();
        double best = Double.MAX_VALUE;
        for (int k = 1; k < path.size(); k++) {
            Vec3 tgt = centre.add(course.at(k)), p0 = path.get(k - 1), seg = path.get(k).subtract(p0);
            double u = Math.max(0, Math.min(1, tgt.subtract(p0).dot(seg) / seg.lengthSqr()));
            best = Math.min(best, tgt.subtract(p0.add(seg.scale(u))).length());
        }
        return best;
    }

    @Test
    void theSolveLeadsAlongTheCurve() {
        Vec3 eye = new Vec3(0, 70, 0);
        Vec3 centre = new Vec3(0, 90, 40);
        for (double deg : new double[]{3, 6, 10}) {
            Ballistics.Course curve = Ballistics.Course.turning(new Vec3(0.7, 0.0, 0.0), Math.toRadians(deg));
            double onCurve = miss(eye, Ballistics.solve(eye, centre, curve, 0.0, ARROW, Vec3.ZERO, true), centre, curve);
            double straightLead = miss(eye, Ballistics.solve(eye, centre, Ballistics.Course.straight(curve.motion()), 0.0,
                                                             ARROW, Vec3.ZERO, true), centre, curve);
            assertTrue(onCurve < 0.3, deg + " deg: led along the curve, misses by " + onCurve);
            assertTrue(straightLead > onCurve + 0.5, deg + " deg: a straight lead misses by " + straightLead + " against " + onCurve);
        }
    }
}
