package ai.rrw.kine.fight;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The skeleton, creeper and spider models against real mobs: mob_traces.txt.gz was recorded in a lockstep client test
 * (clienttest MobTraceTest, run trace1, 2026-10-12, no lag): every server tick each mob and arrow and the player, and
 * every client tick the player's own state, in eight scenes (a skeleton 10 blocks off a still player, one against a
 * strafing player, a bogged; a creeper reaching a still player, one fleeing and coming back, one stepped back from;
 * a spider; a zombie villager). The checks are MobCheck's.
 */
class MobModelTest {
    static Map<String, MobCheck.Scene> readOther() throws Exception {
        try (InputStream in = new GZIPInputStream(MobModelTest.class.getResourceAsStream("/move/mob_traces_other.txt.gz"))) {
            return MobCheck.read(in);
        }
    }

    static Map<String, MobCheck.Scene> read() throws Exception {
        try (InputStream in = new GZIPInputStream(MobModelTest.class.getResourceAsStream("/move/mob_traces.txt.gz"))) {
            return MobCheck.read(in);
        }
    }

    @Test
    void skeletonsShootOnTheRealTicksAndTheirArrowsFlyAndHitAsTheRealOnes() throws Exception {
        Map<String, MobCheck.Scene> sc = read();
        for (String name : new String[]{"sk_still10", "sk_strafe", "bogged10"}) {
            double[] r = MobCheck.bow(sc.get(name), name.startsWith("bogged") ? 70 : 40);
            assertTrue(r[1] >= 3, name + ": shots " + r[1]);
            assertEquals(r[1], r[0], name + ": every real shot matched");
            assertEquals(0.0, r[2], name + ": shot ticks exact");
            assertTrue(r[3] < 1e-4, name + ": flight error " + r[3]);
            assertEquals(r[5], r[4], name + ": every arrow's hit or miss and its tick");
            assertTrue(r[6] < 5e-4, name + ": strafe push mean error " + r[6]);
        }
    }

    @Test
    void aCreepersBlastAndPushAreTheRealOnes() throws Exception {
        Map<String, MobCheck.Scene> sc = read();
        for (String name : new String[]{"cr_still", "cr_far4"}) {
            MobCheck.Scene s = sc.get(name);
            List<MobCheck.M> rows = s.mobs.values().iterator().next();
            MobCheck.M last = rows.get(rows.size() - 1);
            int blast = last.t() + 1;
            MobCheck.P p = s.player.get(blast), q = s.player.get(blast - 1);
            double[] b = Blast.onPlayer(last.x(), last.y(), last.z(), 3.0, MobCheck.box(p), p.y() + 1.62, MobCheck.flat());
            assertNotNull(b);
            assertEquals(q.health() - p.health(), b[0], 0.05, name + ": damage");
            // the client's first move after the blast: the push added to a player at rest
            MobCheck.C prev = null, first = null;
            for (MobCheck.C c : s.client) {
                if (prev != null && c.t() > blast && Math.abs(c.y() - prev.y()) > 1e-6) { first = c; break; }
                prev = c;
            }
            assertNotNull(first);
            assertEquals(b[1], first.x() - prev.x(), 1e-3, name + ": push x");
            assertEquals(b[2] - 0.0784, first.y() - prev.y(), 1e-3, name + ": push y");
            assertEquals(b[3], first.z() - prev.z(), 1e-3, name + ": push z");
        }
    }

    @Test
    void aCreepersFuseLightsNoLaterThanTheRealOneAndAtMostATickSooner() throws Exception {
        Map<String, MobCheck.Scene> sc = read();
        for (String name : new String[]{"cr_still", "cr_flee2", "cr_far4"}) {
            MobCheck.Scene s = sc.get(name);
            List<MobCheck.M> rows = s.mobs.values().iterator().next();
            int st = MobCheck.firstMove(rows);
            MobSim cl = MobCheck.melee(rows.get(st), "creeper");
            SplittableRandom rnd = new SplittableRandom(3);
            int copy = -1, real = -1;
            for (int k = st + 1; k < rows.size(); k++) {
                MobCheck.M r = rows.get(k), qq = rows.get(k - 1);
                MobCheck.P p = s.player.get(r.t());
                if (copy < 0) {
                    cl.x = qq.x(); cl.y = qq.y(); cl.z = qq.z(); cl.vx = qq.vx(); cl.vy = qq.vy(); cl.vz = qq.vz(); cl.onGround = qq.ground();
                    int d0 = cl.swellDir;
                    if (!cl.swellTick()) cl.thinkCreeper(p.x(), p.y(), p.z(), MobCheck.box(p), rnd);
                    if (cl.swellDir > 0 && d0 <= 0) copy = r.t();
                }
                if (real < 0 && r.swellDir() > 0 && qq.swellDir() <= 0) real = r.t();
            }
            assertTrue(real > 0 && copy > 0, name);
            assertTrue(copy <= real && real - copy <= 1, name + ": copy " + copy + " real " + real);
        }
    }

    @Test
    void walkersWalkAtTheirRealSpeed() throws Exception {
        Map<String, MobCheck.Scene> sc = read();
        String[][] cases = {{"zv_still", "zombie"}, {"sp_still", "spider"}, {"cr_still", "creeper"}};
        for (String[] c : cases) {
            List<MobCheck.M> rows = sc.get(c[0]).mobs.values().iterator().next();
            double top = 0;
            for (int k = 1; k < rows.size(); k++)
                if (rows.get(k).ground() && rows.get(k - 1).ground())
                    top = Math.max(top, Math.hypot(rows.get(k).x() - rows.get(k - 1).x(), rows.get(k).z() - rows.get(k - 1).z()));
            double model = MobCheck.melee(rows.get(0), c[1]).walkSpeed();
            assertEquals(model, top, 0.001, c[0] + ": real " + top + " model " + model);
        }
    }

    /** mob_traces_other.txt.gz: run trace3_other (lockstep, no lag): a vindicator, a piglin brute, a hoglin, a pillager
     *  and a witch against a still player. */
    @Test
    void theOtherWalkersWalkAtTheirRealSpeed() throws Exception {
        Map<String, MobCheck.Scene> sc = readOther();
        String[][] cases = {{"vindicator_still", "vindicator"}, {"piglin_brute_still", "piglin_brute"}, {"hoglin_still", "hoglin"}};
        for (String[] c : cases) {
            List<MobCheck.M> rows = sc.get(c[0]).mobs.values().iterator().next();
            double top = 0;
            for (int k = 1; k < rows.size(); k++)
                if (rows.get(k).ground() && rows.get(k - 1).ground())
                    top = Math.max(top, Math.hypot(rows.get(k).x() - rows.get(k - 1).x(), rows.get(k).z() - rows.get(k - 1).z()));
            assertEquals(MobCheck.melee(rows.get(0), c[1]).walkSpeed(), top, 0.001, c[0]);
        }
    }

    @Test
    void aPillagersArrowsAndAWitchsPotionsFlyAsTheRealOnes() throws Exception {
        Map<String, MobCheck.Scene> sc = readOther();
        for (String name : new String[]{"pill_still", "witch_still"}) {
            MobCheck.Scene s = sc.get(name);
            int flown = 0;
            for (List<MobCheck.A> as : s.arrows.values()) {
                MobCheck.A a0 = as.get(0);
                ArrowSim sim = new ArrowSim();
                sim.x = a0.x(); sim.y = a0.y(); sim.z = a0.z(); sim.vx = a0.vx(); sim.vy = a0.vy(); sim.vz = a0.vz();
                if (name.startsWith("witch")) { sim.gravity = 0.05; sim.drag = 0.99; sim.gravityFirst = true; sim.splash = true; }
                double err = 0;
                int k;
                for (k = 1; k < as.size() && sim.alive; k++) {
                    MobCheck.A a = as.get(k);
                    MobCheck.P p = s.player.get(a.t());
                    if (sim.tick(MobCheck.box(p), MobCheck.flat()) >= 0 || !sim.alive) break;
                    err = Math.max(err, Math.sqrt(MobCheck.sq(sim.x - a.x()) + MobCheck.sq(sim.y - a.y()) + MobCheck.sq(sim.z - a.z())));
                }
                if (k > 3 && err < 1e-4) flown++;
            }
            assertTrue(flown >= 3, name + ": projectiles flown as the real ones " + flown);
        }
        // the witch throws every 60 ticks
        MobCheck.Scene w = sc.get("witch_still");
        List<Integer> t = new java.util.ArrayList<>();
        for (List<MobCheck.A> as : w.arrows.values()) t.add(as.get(0).t());
        t.sort(Integer::compare);
        for (int k = 1; k < t.size(); k++) assertEquals(60, t.get(k) - t.get(k - 1));
    }

    /** mob_traces_slime.txt.gz: run trace4_slime (lockstep, no lag), the server's rows only: a size 4 slime, a size 4
     *  and a size 1 magma cube against a still player, with the size 2 and size 1 splits of the scene before (killed as
     *  each scene began). The flights of the lone slimes replay to within 1e-3; every hit the player took comes from a
     *  copy touching it, at its damage; no wait between hops is shorter than the copy's shortest. */
    @Test
    void slimesHopAndHurtAsTheRealOnes() throws Exception {
        Map<String, MobCheck.Scene> sc;
        try (InputStream in = new GZIPInputStream(MobModelTest.class.getResourceAsStream("/move/mob_traces_slime.txt.gz"))) {
            sc = MobCheck.read(in);
        }
        for (String name : new String[]{"sl4_still", "sl2_still", "mc4_still", "mc1_still"}) {
            double[] r = MobCheck.slimes(sc.get(name));
            assertEquals(0.0, r[1], name + ": a wait shorter than the copy's shortest");
            assertEquals(0.0, r[2], name + ": a hit with no copy touching");
            assertEquals(0.0, r[4], 0.01, name + ": damage");
        }
        assertTrue(MobCheck.slimes(sc.get("sl4_still"))[0] < 1e-3, "the lone slime's flights");
    }

    /** mob_traces_blaze.txt.gz: run trace5_blaze (lockstep, no lag), the server's rows only: a blaze 10 blocks from a
     *  still player, and one 3 blocks off. Every volley comes 60, 66 and 72 ticks after the charge; each fireball is
     *  made where the copy makes it and flies from the next tick within 1e-3 of the real one; every hit is right. */
    @Test
    void blazesChargeAndFireAsTheRealOnes() throws Exception {
        Map<String, MobCheck.Scene> sc;
        try (InputStream in = new GZIPInputStream(MobModelTest.class.getResourceAsStream("/move/mob_traces_blaze.txt.gz"))) {
            sc = MobCheck.read(in);
        }
        for (String name : new String[]{"bl_still", "bl_near"}) {
            double[] r = MobCheck.blazes(sc.get(name));
            assertTrue(r[0] < 1e-3, name + ": flight " + r[0]);
            assertTrue(r[1] < 1e-6, name + ": start " + r[1]);
            assertEquals(0.0, r[2], name + ": hits");
            assertEquals(0.0, r[3], name + ": volley ticks");
        }
    }

    /** mob_traces_phantom.txt.gz: run trace7_phantom (lockstep, no lag), the server's rows with each tick's attack
     *  phase and move control speed: a phantom swooping at a still player and at one strafing. 99% of the swooping
     *  ticks replay within 1e-3 blocks in one step (the rest are a swoop's first ticks); every hit the player took came
     *  from a copy touching it. */
    @Test
    void phantomsSwoopAsTheRealOnes() throws Exception {
        Map<String, MobCheck.Scene> sc;
        try (InputStream in = new GZIPInputStream(MobModelTest.class.getResourceAsStream("/move/mob_traces_phantom.txt.gz"))) {
            sc = MobCheck.read(in);
        }
        for (String name : new String[]{"ph_still", "ph_side"}) {
            double[] r = MobCheck.phantoms(sc.get(name));
            assertTrue(r[3] > 200, name + ": steps " + r[3]);
            assertTrue(r[4] <= 0.02 * r[3], name + ": steps off " + r[4] + " of " + r[3]);
            assertEquals(0.0, r[2], name + ": hits with no copy touching");
        }
    }

    /** mob_traces_trident.txt.gz: run trace6_trident (lockstep, no lag): a drowned with a trident 8 and 14 blocks from
     *  a still player. A throw every 40 ticks; every trident flies within 1e-4 of the copy; every hit right. */
    @Test
    void tridentsFlyAndHitAsTheRealOnes() throws Exception {
        Map<String, MobCheck.Scene> sc;
        try (InputStream in = new GZIPInputStream(MobModelTest.class.getResourceAsStream("/move/mob_traces_trident.txt.gz"))) {
            sc = MobCheck.read(in);
        }
        for (String name : new String[]{"drowned_tri8", "drowned_tri14"}) {
            double[] r = MobCheck.tridents(sc.get(name));
            assertTrue(r[0] < 1e-4, name + ": flight " + r[0]);
            assertEquals(0.0, r[1], name + ": hits");
        }
    }

    /** mob_traces_illager.txt.gz: run trace9_illager (lockstep, no lag): an evoker 8 and 2 blocks from a still player
     *  and a vex with a sword. Fangs come 18 ticks after the cast where the copy puts them; a vex's charge replays in
     *  one step within 1e-4; every hit explained. */
    @Test
    void evokersAndVexesAsTheRealOnes() throws Exception {
        Map<String, MobCheck.Scene> sc;
        try (InputStream in = new GZIPInputStream(MobModelTest.class.getResourceAsStream("/move/mob_traces_illager.txt.gz"))) {
            sc = MobCheck.read(in);
        }
        for (String name : new String[]{"ev_8", "ev_2"}) {
            double[] r = MobCheck.evokers(sc.get(name));
            assertEquals(0.0, r[0], name + ": fang ticks");
            assertTrue(r[1] < 0.01, name + ": fang positions " + r[1]);
            assertEquals(0.0, r[2], name + ": bites");
        }
        double[] v = MobCheck.vexes(sc.get("vex_6"));
        assertTrue(v[1] > 30, "steps " + v[1]);
        assertTrue(v[0] < 1e-4, "vex " + v[0]);
        assertEquals(0.0, v[2], "vex hits");
    }
}
