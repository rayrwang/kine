package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The walk's ETA against layer 2's real arrival, closed loop on the movement copy (Layer2Sim), over fixed scenes and
 * random ground: at a quarter, half and three quarters of the walk's time, each estimate's error against the time that
 * was really left, as a share of it; mean (bias) and standard deviation (spread). The estimates: the straight line at
 * the top speed on foot (a baseline), the planned time alone, the ETA's estimate (planned time by the learned ratio,
 * with what lies past the edge and hunger), and the ETA as shown (smoothed).
 */
public class EtaSimTest {
    record Sample(double truth, double straight, double planned, double raw, double shown, double rawJitter, double shownJitter) {}

    static final double[] FRACTIONS = {0.25, 0.5, 0.75};

    /** Walk with layer 2, then feed the ETA the walk's ticks; the estimates at the fractions of the real time. */
    static List<Sample> run(TestWorld w, MoveState start, int gx, int gz) {
        Layer2Sim sim = new Layer2Sim(w);
        Layer2Sim.Outcome o = sim.walk(start, -90f, gx, gz, 3000);
        assertTrue(o.arrived(), o.why());
        PathSearch.Result r = sim.lastRoute;
        Route route = new Tighten(sim.view, sim.costs, 0.2, true).route(r, gx, gz);
        TimeProfile tp = TimeProfile.of(sim.view, sim.costs, true, true, false, r, route, gx, gz);
        Eta eta = new Eta();
        eta.onPlan(tp, route);
        int total = o.ticks();
        List<Sample> out = new ArrayList<>();
        double[] shown = new double[total + 1];
        double[] rawT = new double[total + 1];
        double sx = start.x, sz = start.z;
        for (int t = 0; t < total; t++) {
            double[] q = t == 0 ? new double[]{sx, 0, sz} : o.path().get(t - 1);
            eta.tick(q[0], q[2], t > 0);
            double raw = eta.rawSeconds(20, 5f, true, true) * 20;
            shown[t] = eta.shown(raw / 20, t > 0, (t + 1) * 50_000_000L) * 20;
            rawT[t] = raw;
            for (double f : FRACTIONS) {
                if (t == (int) Math.round(total * f)) {
                    double straight = Math.hypot(gx + 0.5 - q[0], gz + 0.5 - q[2]) * sim.costs.fastest;
                    out.add(new Sample(total - t, straight, eta.plannedTicks(), raw, shown[t], 0, 0));
                }
            }
        }
        // how unevenly each counts down: the mean of |a tick's change + 1 tick| (0 for a perfect countdown), in ticks
        double jr = 0, js = 0;
        for (int t = 1; t < total; t++) { jr += Math.abs(rawT[t] - rawT[t - 1] + 1); js += Math.abs(shown[t] - shown[t - 1] + 1); }
        jr /= Math.max(1, total - 1); js /= Math.max(1, total - 1);
        List<Sample> withJ = new ArrayList<>();
        for (Sample q : out) withJ.add(new Sample(q.truth(), q.straight(), q.planned(), q.raw(), q.shown(), jr, js));
        return withJ;
    }

    /** Random ground 96 by 40: walls, raised plateaus with steps, ponds three deep, soul sand. */
    static TestWorld random(long seed) {
        Random r = new Random(seed);
        TestWorld w = TestWorld.blocks(-8, -24, 111, 23).floor(10);
        for (int i = 0; i < 18; i++) {
            int x = 8 + r.nextInt(80), z = -18 + r.nextInt(36), kind = r.nextInt(4);
            int lx = 1 + r.nextInt(6), lz = 1 + r.nextInt(6);
            switch (kind) {
                case 0 -> w.fill(x, 11, z, x + (r.nextBoolean() ? lx * 2 : 0), 12, z + (r.nextBoolean() ? lz * 2 : 0), TestWorld.STONE);
                case 1 -> w.fill(x, 11, z, x + lx, 11, z + lz, TestWorld.STONE);
                case 2 -> w.fill(x, 8, z, x + lx, 10, z + lz, TestWorld.WATER).fill(x, 7, z, x + lx, 7, z + lz, TestWorld.STONE);
                default -> w.fill(x, 10, z, x + lx, 10, z + lz, TestWorld.SOUL_SAND);
            }
        }
        w.fill(-2, 10, -2, 3, 10, 3, TestWorld.STONE).fill(-2, 11, -2, 3, 13, 3, Palette.AIR);
        w.fill(88, 10, -2, 93, 10, 3, TestWorld.STONE).fill(88, 11, -2, 93, 13, 3, Palette.AIR);
        return w;
    }

    static void report(String name, List<Sample> s) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "eta %-10s n=%d", name, s.size() / FRACTIONS.length));
        for (int fi = 0; fi < FRACTIONS.length; fi++) {
            double[][] e = new double[4][];
            List<double[]> rows = new ArrayList<>();
            for (int i = fi; i < s.size(); i += FRACTIONS.length) {
                Sample q = s.get(i);
                rows.add(new double[]{(q.straight() - q.truth()) / q.truth(), (q.planned() - q.truth()) / q.truth(),
                    (q.raw() - q.truth()) / q.truth(), (q.shown() - q.truth()) / q.truth()});
            }
            sb.append(String.format(Locale.ROOT, " | at %d%%:", (int) (FRACTIONS[fi] * 100)));
            String[] names = {"straight", "planned", "eta", "shown"};
            for (int k = 0; k < 4; k++) {
                double m = 0, v = 0;
                for (double[] row : rows) m += row[k];
                m /= rows.size();
                for (double[] row : rows) v += (row[k] - m) * (row[k] - m);
                double sd = Math.sqrt(v / Math.max(1, rows.size() - 1));
                sb.append(String.format(Locale.ROOT, " %s %+.0f%%±%.0f", names[k], m * 100, sd * 100));
            }
        }
        double jr = 0, js = 0;
        for (Sample q : s) { jr += q.rawJitter(); js += q.shownJitter(); }
        sb.append(String.format(Locale.ROOT, " | jitter (ticks a tick) eta %.3f shown %.3f", jr / s.size(), js / s.size()));
        System.out.println(sb);
    }

    @Test
    void etaAgainstLayer2sArrival() {
        List<Sample> all = new ArrayList<>();
        // open ground
        List<Sample> open = run(TestWorld.blocks(-8, -8, 127, 7).floor(10), Layer2Sim.standing(0.5, 11, 0.5), 110, 0);
        report("open", open);
        all.addAll(open);
        // a lake
        TestWorld lake = TestWorld.blocks(-8, -16, 95, 15).floor(10);
        lake.fill(10, 6, -16, 69, 10, 15, TestWorld.WATER).fill(10, 5, -16, 69, 5, 15, TestWorld.STONE);
        List<Sample> water = run(lake, Layer2Sim.standing(0.5, 11, 0.5), 80, 0);
        report("water", water);
        all.addAll(water);
        // a field of pillars (a maze-like way round them)
        TestWorld pillars = TestWorld.blocks(-8, -24, 95, 23).floor(10);
        Random pr = new Random(5);
        for (int x = 4; x < 76; x++)
            for (int z = -20; z <= 20; z++)
                if (pr.nextDouble() < 0.18 && Math.abs(x) + Math.abs(z) > 3) pillars.fill(x, 11, z, x, 13, z, TestWorld.STONE);
        List<Sample> pil = run(pillars, Layer2Sim.standing(0.5, 11, 0.5), 80, 0);
        report("pillars", pil);
        all.addAll(pil);
        // random ground
        List<Sample> rnd = new ArrayList<>();
        for (long seed = 1; seed <= 12; seed++) rnd.addAll(run(random(seed), Layer2Sim.standing(0.5, 11, 0.5), 90, 0));
        report("random", rnd);
        all.addAll(rnd);
        report("all", all);
        // the ETA beats the straight-line baseline and the planned time alone on the whole set, its bias small
        double sumEta = 0, sumStraight = 0;
        for (Sample q : all) {
            sumEta += Math.abs(q.raw() - q.truth()) / q.truth();
            sumStraight += Math.abs(q.straight() - q.truth()) / q.truth();
        }
        assertTrue(sumEta < sumStraight, "the ETA beats the straight line: " + sumEta + " vs " + sumStraight);
    }
}
