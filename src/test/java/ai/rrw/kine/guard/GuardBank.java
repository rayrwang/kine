package ai.rrw.kine.guard;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * Not a check: the bank of crash scenarios the guardian is measured on offline (in the movement copy, hands off),
 * printed as tables. Run with KINE_BANK=margin (the freeze margin: rescues that survive a real freeze, and how early
 * it fires), KINE_BANK=seeds (the rescues the search finds that beat the named moves, for feeding back).
 */
@Tag("bench")
class GuardBank {
    record Scene(String name, BlockGrid g, MoveState s, float yaw, float pitch) {}

    static MoveState at(double x, double y, double z, double vx, double vy, double vz) {
        return GuardLoop.gliding(x, y, z, vx, vy, vz);
    }

    /** About twenty ways to get hurt gliding hands off: walls met square and slanting, slow and fast, high and low;
     *  dives at several pitches and heights; low into cliffs; stalls with the nose held up at several heights; the end
     *  of a box canyon. Flat ground's top at 64. */
    static List<Scene> scenes() {
        List<Scene> l = new ArrayList<>();
        BlockGrid wall200 = Worlds.wall(200), wall120 = Worlds.wall(120), wall150 = Worlds.wall(150);
        for (float yaw : new float[]{0f, 30f, 55f}) l.add(new Scene("wallRest" + (int) yaw, wall200, at(0.5, 160, 0.5, 0, 0, 0), yaw, 20f));
        l.add(new Scene("wallFast", wall120, at(0.5, 110, 0.5, 0, -0.05, 1.5), 0f, 0f));
        l.add(new Scene("wallLow", wall150, at(0.5, 80, 0.5, 0, -0.05, 1.8), 0f, 0f));
        l.add(new Scene("wallSlant", wall150, at(0.5, 100, 0.5, -1.0, -0.05, 1.2), 40f, 2f));
        for (float pitch : new float[]{30f, 45f, 60f, 80f}) {
            l.add(new Scene("dive" + (int) pitch + "high", Worlds.flat(), at(0.5, 180, 0.5, 0, 0, 0), 0f, pitch));
            l.add(new Scene("dive" + (int) pitch + "low", Worlds.flat(), at(0.5, 130, 0.5, 0, 0, 0), 0f, pitch));
        }
        l.add(new Scene("cliffLow", Worlds.cliff(60, 120), at(0.5, 75, 0.5, 0, -0.05, 1.5), 0f, 0f));
        l.add(new Scene("cliffTall", Worlds.cliff(100, 160), at(0.5, 90, 0.5, 0, -0.05, 1.2), 0f, 3f));
        l.add(new Scene("stall100", Worlds.flat(), at(0.5, 164, 0.5, 0, 0.1, 0.1), 0f, -70f));
        l.add(new Scene("stall70", Worlds.flat(), at(0.5, 134, 0.5, 0, 0.1, 0.1), 0f, -70f));
        l.add(new Scene("stall100up", Worlds.flat(), at(0.5, 164, 0.5, 0, 0.2, 0.0), 0f, -90f));
        l.add(new Scene("stall80at50", Worlds.flat(), at(0.5, 144, 0.5, 0, 0.0, 0.3), 0f, -50f));
        l.add(new Scene("canyonEnd", Worlds.canyon(150), at(0.5, 100, 0.5, 0, -0.05, 1.5), 0f, 0f));
        return l;
    }

    /** Scenes not used to find the fed-back seeds, to measure their gain out of sample: other incidences, speeds,
     *  heights and pitches, a narrower cliff run, a wider canyon. */
    static List<Scene> heldOut() {
        List<Scene> l = new ArrayList<>();
        BlockGrid wall180 = Worlds.wall(180), wall130 = Worlds.wall(130);
        for (float yaw : new float[]{15f, 45f}) l.add(new Scene("hoWallRest" + (int) yaw, wall180, at(0.5, 150, 0.5, 0, 0, 0), yaw, 25f));
        l.add(new Scene("hoWallFast", wall130, at(0.5, 95, 0.5, 0, -0.05, 1.7), 0f, 2f));
        l.add(new Scene("hoWallLow", wall130, at(0.5, 72, 0.5, 0.3, -0.05, 1.6), -10f, 0f));
        for (float pitch : new float[]{50f, 70f}) l.add(new Scene("hoDive" + (int) pitch, Worlds.flat(), at(0.5, 150, 0.5, 0, 0, 0), 0f, pitch));
        l.add(new Scene("hoCliff", Worlds.cliff(80, 140), at(0.5, 82, 0.5, 0, -0.05, 1.4), 0f, 1f));
        l.add(new Scene("hoStall85", Worlds.flat(), at(0.5, 150, 0.5, 0, 0.1, 0.15), 0f, -75f));
        l.add(new Scene("hoStall60", Worlds.flat(), at(0.5, 124, 0.5, 0, 0.1, 0.1), 0f, -65f));
        l.add(new Scene("hoCanyon", Worlds.of((x, y, z) -> y < 64 || (Math.abs(x) > 18 || z >= 170) && y < 160 ? Worlds.STONE
            : ai.rrw.kine.move.BlockInfo.AIR), at(0.5, 105, 0.5, 0, -0.05, 1.4), 0f, 0f));
        return l;
    }

    static Guard.Config cfg(int freeze) {
        Guard.Config c = new Guard.Config();
        c.freeze = freeze;
        c.variations = 60;
        return c;
    }

    /** The state where the guardian fires in a scene (hands off, the default margin), with its look; null if it never
     *  fires in `ticks`. */
    static Object[] fireState(Scene sc, Guard.Config cfg, int ticks) {
        GlideSim sim = new GlideSim(sc.g(), new ai.rrw.kine.move.Body(), -64, 3.0);
        Track.Ring ring = new Track.Ring();
        MoveState s = sc.s().copy();
        for (int k = Track.TICKS; k >= 1; k--) ring.add(s.x - s.vx * k, s.y, s.z - s.vz * k);
        float y = sc.yaw(), p = sc.pitch();
        for (int t = 0; t < ticks; t++) {
            Guard g = new Guard(sim, cfg, ring.snapshot());
            Guard.Verdict v = g.watch(s, y, p, Pilot.HOLD, t);
            if (v.kind() == Guard.Kind.FIRE) return new Object[]{s.copy(), y, p, ring.snapshot(), v.danger().damageTick};
            if (sim.step(s, y, p, false) > 0) return null;
            ring.add(s.x, s.y, s.z);
        }
        return null;
    }

    /** RW 00:18:28: rescues the search finds better than the named moves are fed back as starting points. At each
     *  scene's firing state: the best named move, and the best of a long search (400 variations); a winner by more than
     *  0.05 is kept. Printed as Java for Maneuver's fed-back list, then the gain: the best seed's cost at each firing state
     *  and the can-it-wait lead, before and after. */
    @Test
    void seeds() {
        if (!"seeds".equals(System.getenv("KINE_BANK"))) return;
        List<Scene> sc = scenes();
        Guard.Config base = cfg(20);
        List<Maneuver> winners = new ArrayList<>();
        List<Object[]> states = new ArrayList<>();
        for (Scene s : sc) {
            Object[] f = fireState(s, base, 700);
            states.add(f);
            if (f == null) { System.out.println("SEEDS " + s.name() + ": never fires"); continue; }
            GlideSim sim = new GlideSim(s.g(), new ai.rrw.kine.move.Body(), -64, 3.0);
            Guard.Config seedsOnly = cfg(20); seedsOnly.variations = 0; seedsOnly.fireNanos = 0;
            Guard.Config longer = cfg(20); longer.variations = 400;
            Guard.Candidate a = new Guard(sim, seedsOnly, (Track) f[3]).search((MoveState) f[0], (float) f[1], (float) f[2], null, 0, 1);
            Guard.Candidate b = new Guard(sim, longer, (Track) f[3]).search((MoveState) f[0], (float) f[1], (float) f[2], null, 0, 1);
            boolean win = b.full && b.cost < a.cost - 0.05;
            System.out.printf(Locale.ROOT, "SEEDS %-12s best named %.3f (%s) searched %.3f%s%n", s.name(), a.cost, a.plan.name, b.cost,
                win ? " WIN " + b.plan : "");
            if (win) winners.add(b.plan);
        }
        StringBuilder java = new StringBuilder("SEEDS fed back:\n");
        int i = 1;
        for (Maneuver m : winners) {
            java.append(String.format(Locale.ROOT, "            new Maneuver(\"fed%d\", %.1f", i++, m.glidePitch));
            for (Maneuver.Phase ph : m.phases)
                java.append(String.format(Locale.ROOT, ", new Phase(%d, %.1f, %.1f, %.1f)", ph.ticks(), ph.offset(), ph.pitch(), ph.turn()));
            java.append("),\n");
        }
        System.out.println(java);
        // the gain: the named moves alone, then with the winners, at every firing state and in the can-it-wait lead
        Guard.Config with = cfg(20); with.extraSeeds = winners;
        double before = 0, after = 0; int n = 0;
        for (int k = 0; k < sc.size(); k++) {
            Object[] f = states.get(k);
            if (f == null) continue;
            GlideSim sim = new GlideSim(sc.get(k).g(), new ai.rrw.kine.move.Body(), -64, 3.0);
            Guard.Config a = cfg(20); a.variations = 0; a.fireNanos = 0;
            Guard.Config b = with.copy(); b.variations = 0; b.fireNanos = 0;
            before += new Guard(sim, a, (Track) f[3]).search((MoveState) f[0], (float) f[1], (float) f[2], null, 0, 1).cost;
            after += new Guard(sim, b, (Track) f[3]).search((MoveState) f[0], (float) f[1], (float) f[2], null, 0, 1).cost;
            n++;
        }
        System.out.printf(Locale.ROOT, "SEEDS gain: mean best-seed cost at the firing states %.3f -> %.3f (%d scenes, %d fed back)%n",
            before / n, after / n, n, winners.size());
        // out of sample
        double hoBefore = 0, hoAfter = 0; int hn = 0;
        for (Scene h : heldOut()) {
            Object[] f = fireState(h, base, 700);
            if (f == null) { System.out.println("SEEDS held out " + h.name() + ": never fires"); continue; }
            GlideSim sim = new GlideSim(h.g(), new ai.rrw.kine.move.Body(), -64, 3.0);
            Guard.Config a = cfg(20); a.variations = 0; a.fireNanos = 0;
            Guard.Config b = with.copy(); b.variations = 0; b.fireNanos = 0;
            Guard.Candidate ca = new Guard(sim, a, (Track) f[3]).search((MoveState) f[0], (float) f[1], (float) f[2], null, 0, 1);
            Guard.Candidate cb = new Guard(sim, b, (Track) f[3]).search((MoveState) f[0], (float) f[1], (float) f[2], null, 0, 1);
            Guard.Config c = cfg(20); c.variations = 60;
            Guard.Config d = with.copy(); d.variations = 60;
            Guard.Candidate cc = new Guard(sim, c, (Track) f[3]).search((MoveState) f[0], (float) f[1], (float) f[2], null, 0, 1);
            Guard.Candidate cd = new Guard(sim, d, (Track) f[3]).search((MoveState) f[0], (float) f[1], (float) f[2], null, 0, 1);
            System.out.printf(Locale.ROOT, "SEEDS held out %-12s named only %.3f (%s) with fed %.3f (%s); after 60 variations %.3f -> %.3f%n",
                h.name(), ca.cost, ca.plan.name, cb.cost, cb.plan.name, cc.cost, cd.cost);
            hoBefore += ca.cost; hoAfter += cb.cost; hn++;
        }
        System.out.printf(Locale.ROOT, "SEEDS gain out of sample: mean best-seed cost %.3f -> %.3f (%d scenes)%n", hoBefore / hn, hoAfter / hn, hn);
        int leadBefore = 0, leadAfter = 0, m = 0;
        for (int k = 0; k < sc.size(); k++) {
            Object[] f1 = fireState(sc.get(k), base, 700), f2 = fireState(sc.get(k), with, 700);
            if (f1 == null || f2 == null) continue;
            leadBefore += (int) f1[4]; leadAfter += (int) f2[4]; m++;
        }
        System.out.printf(Locale.ROOT, "SEEDS gain: mean lead (ticks from firing to the do-nothing damage) %.1f -> %.1f over %d scenes%n",
            leadBefore / (double) m, leadAfter / (double) m, m);
    }

    @Test
    void margin() {
        if (!"margin".equals(System.getenv("KINE_BANK"))) return;
        List<Scene> sc = scenes();
        int[] margins = {0, 5, 10, 15, 20, 25};
        int[] freezes = {0, 10, 15, 20, 25};
        System.out.println("BANK margin: " + sc.size() + " scenes; per margin M (the can-it-wait freeze), rescued with no damage "
            + "when a freeze of F ticks holds the controls from the tick it fires; the lead (ticks from firing to the do-nothing "
            + "path's damage); ticks to hand back, height lost (median over scenes with F 0)");
        for (int m : margins) {
            int[][] ok = new int[freezes.length][1];
            int[] leads = new int[sc.size()], back = new int[sc.size()];
            double[] lost = new double[sc.size()];
            String[] fails = new String[freezes.length];
            Arrays.fill(fails, "");
            List<int[]> jobs = new ArrayList<>();
            for (int i = 0; i < sc.size(); i++) for (int f = 0; f < freezes.length; f++) jobs.add(new int[]{i, f});
            GuardLoop.Run[] runs = new GuardLoop.Run[jobs.size()];
            IntStream.range(0, jobs.size()).parallel().forEach(j -> {
                Scene s = sc.get(jobs.get(j)[0]);
                int fz = freezes[jobs.get(j)[1]];
                runs[j] = GuardLoop.run(s.g(), s.s(), s.yaw(), s.pitch(), Pilot.HOLD, null, cfg(m), 700, fz > 0 ? 0 : -1, fz, true);
            });
            for (int j = 0; j < jobs.size(); j++) {
                int i = jobs.get(j)[0], f = jobs.get(j)[1];
                GuardLoop.Run r = runs[j];
                boolean saved = r.damage == 0.0;
                if (saved) ok[f][0]++;
                else fails[f] += " " + sc.get(i).name() + (r.fired() ? "" : "(no fire)");
                if (f == 0) {
                    leads[i] = r.fireLead;
                    back[i] = r.handbackTick >= 0 ? r.handbackTick - r.fireTick : -1;
                    lost[i] = r.heightLost;
                }
            }
            StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "M %2d:", m));
            for (int f = 0; f < freezes.length; f++) b.append(String.format(Locale.ROOT, "  F%-2d %2d/%d", freezes[f], ok[f][0], sc.size()));
            int[] ls = Arrays.stream(leads).filter(x -> x >= 0).sorted().toArray();
            int[] bs = Arrays.stream(back).filter(x -> x >= 0).sorted().toArray();
            b.append(String.format(Locale.ROOT, "  lead median %d min %d max %d; hand back median %d max %d",
                ls.length > 0 ? ls[ls.length / 2] : -1, ls.length > 0 ? ls[0] : -1, ls.length > 0 ? ls[ls.length - 1] : -1,
                bs.length > 0 ? bs[bs.length / 2] : -1, bs.length > 0 ? bs[bs.length - 1] : -1));
            System.out.println(b);
            for (int f = 0; f < freezes.length; f++) if (!fails[f].isEmpty()) System.out.println("      F" + freezes[f] + " hurt:" + fails[f]);
        }
    }
}
