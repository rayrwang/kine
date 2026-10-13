package ai.rrw.kine.fight;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The zombie model (MobSim) against real zombies: zombie_traces.txt was recorded in a lockstep client test
 * (clienttest ZombieTest, run zombie3, 2026-10-12): every server tick each zombie's position, velocity and health and
 * the player's position, health and hurt window, in five scenes (a still player, one walking away, one strafing, three
 * zombies from three sides, one zombie against the kill aura).
 */
class ZombieModelTest {
    record Z(int t, double x, double y, double z, double vx, double vy, double vz, boolean ground, double health) {}
    record P(int t, double x, double y, double z, double health, int invul) {}
    static final class Scene { final Map<Integer, List<Z>> z = new LinkedHashMap<>(); final Map<Integer, P> p = new HashMap<>(); final List<P> ps = new ArrayList<>(); }

    static Map<String, Scene> read() throws Exception {
        Map<String, Scene> out = new LinkedHashMap<>();
        try (var in = ZombieModelTest.class.getResourceAsStream("/move/zombie_traces.txt")) {
            assertNotNull(in);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            Scene cur = null;
            String line;
            while ((line = r.readLine()) != null) {
                String[] a = line.split(" ");
                switch (a[0]) {
                    case "scene" -> { cur = new Scene(); out.put(a[1], cur); }
                    case "z" -> cur.z.computeIfAbsent(Integer.parseInt(a[2]), k -> new ArrayList<>()).add(new Z(Integer.parseInt(a[1]),
                        d(a[3]), d(a[4]), d(a[5]), d(a[6]), d(a[7]), d(a[8]), Boolean.parseBoolean(a[9]), d(a[10])));
                    case "p" -> { P p = new P(Integer.parseInt(a[1]), d(a[2]), d(a[3]), d(a[4]), d(a[5]), Integer.parseInt(a[6])); cur.p.put(p.t(), p); cur.ps.add(p); }
                    default -> {}
                }
            }
        }
        return out;
    }

    static double d(String s) { return Double.parseDouble(s); }

    static double[] box(P p) {
        return new double[]{p.x() - 0.3F, p.y(), p.z() - 0.3F, p.x() + 0.3F, p.y() + 1.8F, p.z() + 0.3F};
    }

    /** The ticks the player took a full hit (its health fell with its hurt window at 10 or below). */
    static List<Integer> hits(Scene s) {
        List<Integer> h = new ArrayList<>();
        for (int i = 1; i < s.ps.size(); i++)
            if (s.ps.get(i).health() < s.ps.get(i - 1).health() - 0.01 && s.ps.get(i - 1).invul() <= 10) h.add(s.ps.get(i).t());
        return h;
    }

    @Test
    void theModelNeverAttacksLaterThanAZombieThatChasesFromTheStart() throws Exception {
        // walkaway1 and strafe1 chase from the first tick they move; the model's first attack is within 2 ticks
        for (String name : new String[]{"walkaway1", "strafe1"}) {
            Scene s = read().get(name);
            List<Z> rows = s.z.values().iterator().next();
            int start = -1;
            for (int i = 1; i < rows.size(); i++)
                if (Math.hypot(rows.get(i).x() - rows.get(i - 1).x(), rows.get(i).z() - rows.get(i - 1).z()) > 0.03) { start = i - 1; break; }
            assertTrue(start >= 0);
            int realFirst = hits(s).get(0);
            Z r0 = rows.get(start);
            MobSim m = sim(r0);
            int modelFirst = -1;
            for (int i = start + 1; i < rows.size() && modelFirst < 0; i++) {
                P p = s.p.get(rows.get(i).t());
                if (m.think(p.x(), p.y(), p.z(), box(p), new SplittableRandom(1))) modelFirst = rows.get(i).t();
                m.move();
            }
            assertTrue(Math.abs(modelFirst - realFirst) <= 2, name + ": model " + modelFirst + " real " + realFirst);
        }
    }

    @Test
    void theApproachTracksTheRealZombie() throws Exception {
        Scene s = read().get("walkaway1");
        List<Z> rows = s.z.values().iterator().next();
        int start = -1;
        for (int i = 1; i < rows.size(); i++)
            if (Math.hypot(rows.get(i).x() - rows.get(i - 1).x(), rows.get(i).z() - rows.get(i - 1).z()) > 0.03) { start = i - 1; break; }
        MobSim m = sim(rows.get(start));
        double worst = 0;
        for (int i = start + 1; i < rows.size() && i < start + 60; i++) {
            P p = s.p.get(rows.get(i).t());
            m.think(p.x(), p.y(), p.z(), box(p), new SplittableRandom(3));
            m.move();
            worst = Math.max(worst, Math.hypot(m.x - rows.get(i).x(), m.z - rows.get(i).z()));
        }
        assertTrue(worst < 0.35, "worst " + worst);
    }

    @Test
    void knockbackFromTheKillAuraFollowsTheRealArc() throws Exception {
        Scene s = read().get("aura1");
        List<Z> rows = s.z.values().iterator().next();
        int checked = 0;
        for (int i = 1; i + 12 < rows.size(); i++) {
            if (!(rows.get(i).health() < rows.get(i - 1).health() - 0.01) || rows.get(i).health() <= 0) continue;   // a killing hit: no AI after it
            Z before = rows.get(i - 1);
            P p = s.p.get(rows.get(i).t());
            MobSim m = sim(before);
            m.active = true; m.hasPath = true;
            m.pathX = Math.floor(p.x()) + 0.5; m.pathZ = Math.floor(p.z()) + 0.5;
            m.pathTargetX = p.x(); m.pathTargetY = p.y(); m.pathTargetZ = p.z(); m.pathIn = 100;
            m.walking = true; m.op = MobSim.OP_MOVE;   // walking on toward the player, as its goal would have it
            // this zombie's random knockback resistance (0 to 0.05 at spawn, not sent to the client) came out near 0;
            // the model uses the worst case 0.05 in play, which flies 0.02 a tick shorter at first
            m.knockbackResistance = 0.0;
            m.knockback(0.4, p.x() - m.x, p.z() - m.z);
            double worst = 0;
            for (int j = 0; j < 9; j++) {   // up to its landing
                m.move();
                Z r = rows.get(i + j);
                worst = Math.max(worst, Math.max(Math.hypot(m.x - r.x(), m.z - r.z()), Math.abs(m.y - r.y())));
            }
            assertTrue(worst < 0.03, "hit at " + rows.get(i).t() + ": worst " + worst);
            checked++;
        }
        assertTrue(checked >= 2, "hits checked: " + checked);
    }

    static MobSim sim(Z r) {
        MobSim m = new MobSim();
        m.x = r.x(); m.y = r.y(); m.z = r.z(); m.vx = r.vx(); m.vy = r.vy(); m.vz = r.vz();
        m.onGround = r.ground(); m.floorY = -60; m.attackCooldown = 0; m.active = true; m.sinceCheck = 0;
        return m;
    }
}
