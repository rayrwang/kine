package ai.rrw.kine.fight;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Shape;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * The mob models against real ticks recorded in a lockstep client test (clienttest MobTraceTest, mob_traces.txt): each
 * mob replayed from the tick its goal starts, open loop, against the player's recorded positions, and its shots,
 * arrows, swell, blasts and leaps compared with the real ones. Run: java -cp (test classpath) ai.rrw.kine.fight.MobCheck
 * path/to/mob_traces.txt. The tests (MobModelTest) run the same checks on a copy of a recorded dump.
 */
public final class MobCheck {
    record M(int t, int id, String type, double x, double y, double z, double vx, double vy, double vz, float yRot,
             boolean ground, double health, int hurt, boolean using, int usingTicks, boolean aggressive, int swellDir,
             double swelling) {}
    record A(int t, int id, double x, double y, double z, double vx, double vy, double vz, int owner, int age) {}
    record P(int t, double x, double y, double z, double vx, double vy, double vz, double health, int invul) {}
    record C(int t, int ct, double x, double y, double z, double vx, double vy, double vz, double health) {}
    record F(int t, int id, double x, double y, double z, int warmup) {}
    record CM(int t, int id, double x, double y, double z, double tx, double ty, double tz, boolean using, int usingTicks,
              boolean aggressive, int swellDir, double swelling, boolean swinging) {}

    static final class Scene {
        final String name;
        final Map<Integer, List<M>> mobs = new LinkedHashMap<>();
        final Map<Integer, List<A>> arrows = new LinkedHashMap<>();
        final TreeMap<Integer, P> player = new TreeMap<>();
        final List<C> client = new ArrayList<>();
        final Map<Integer, List<CM>> clientMobs = new LinkedHashMap<>();
        final Map<Integer, List<F>> fangs = new LinkedHashMap<>();
        Scene(String name) { this.name = name; }
    }

    static Map<String, Scene> read(InputStream in) throws IOException {
        Map<String, Scene> out = new LinkedHashMap<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Scene cur = null;
        String line;
        while ((line = r.readLine()) != null) {
            String[] a = line.split(" ");
            switch (a[0]) {
                case "scene" -> { cur = new Scene(a[1]); out.put(a[1], cur); }
                case "m" -> { if (cur != null) cur.mobs.computeIfAbsent(i(a[2]), k -> new ArrayList<>()).add(new M(i(a[1]), i(a[2]), a[3],
                    d(a[4]), d(a[5]), d(a[6]), d(a[7]), d(a[8]), d(a[9]), (float) d(a[10]), b(a[11]), d(a[12]), i(a[13]), b(a[14]), i(a[15]),
                    b(a[16]), i(a[17]), d(a[18]))); }
                case "a" -> { if (cur != null) cur.arrows.computeIfAbsent(i(a[2]), k -> new ArrayList<>()).add(new A(i(a[1]), i(a[2]),
                    d(a[3]), d(a[4]), d(a[5]), d(a[6]), d(a[7]), d(a[8]), i(a[9]), i(a[10]))); }
                case "p" -> { if (cur != null) { P p = new P(i(a[1]), d(a[2]), d(a[3]), d(a[4]), d(a[5]), d(a[6]), d(a[7]), d(a[8]), i(a[9])); cur.player.put(p.t(), p); } }
                case "c" -> { if (cur != null) cur.client.add(new C(i(a[1]), i(a[2]), d(a[3]), d(a[4]), d(a[5]), d(a[6]), d(a[7]), d(a[8]), d(a[9]))); }
                case "cm" -> { if (cur != null) cur.clientMobs.computeIfAbsent(i(a[2]), k -> new ArrayList<>()).add(new CM(i(a[1]), i(a[2]),
                    d(a[3]), d(a[4]), d(a[5]), d(a[6]), d(a[7]), d(a[8]), b(a[9]), i(a[10]), b(a[11]), i(a[12]), d(a[13]), b(a[14]))); }
                case "f" -> { if (cur != null) cur.fangs.computeIfAbsent(i(a[2]), k -> new ArrayList<>()).add(new F(i(a[1]), i(a[2]),
                    d(a[3]), d(a[4]), d(a[5]), i(a[6]))); }
                default -> {}
            }
        }
        return out;
    }

    static int i(String s) { return Integer.parseInt(s); }
    static double d(String s) { return Double.parseDouble(s); }
    static boolean b(String s) { return Boolean.parseBoolean(s); }

    static double[] box(P p) {
        return new double[]{p.x() - 0.3F, p.y(), p.z() - 0.3F, p.x() + 0.3F, p.y() + 1.8F, p.z() + 0.3F};
    }

    /** A flat world: grass at y -61 (top -60) over the test arena. */
    static BlockGrid flat() {
        BlockGrid g = new BlockGrid(-40, -64, -40, 81, 12, 81);
        BlockInfo grass = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
        for (int x = -40; x <= 40; x++) for (int z = -40; z <= 40; z++) g.set(x, -61, z, grass);
        return g;
    }

    static final List<String> report = new ArrayList<>();

    static void out(String s) {
        report.add(s);
        System.out.println(s);
    }

    static String rel(List<Integer> t, int t0) {
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0; k < t.size(); k++) sb.append(k > 0 ? "," : "").append(t.get(k) - t0);
        return sb.append("]").toString();
    }

    // ------------------------------------------------------------------ skeletons

    /** The bow goal against a real skeleton: shot ticks, the walk in and strafe speed, each arrow's flight from its
     *  real launch, the aim against the launch, hits, damage, and the client's view of the draw. Returns
     *  {shots matched, shots real, worst shot tick error, worst flight error, arrows whose hit (or miss) and its tick the
     *  copy gets right, arrows, the strafe push's mean error}. */
    static double[] bow(Scene s, int interval) {
        double[] res = new double[7];
        for (var e : s.mobs.entrySet()) {
            List<M> rows = e.getValue();
            int st = -1;
            for (int k = 0; k < rows.size(); k++) if (rows.get(k).aggressive()) { st = k; break; }
            if (st < 0) { out(s.name + ": never aggressive"); continue; }
            M r0 = rows.get(st);
            int t0 = r0.t();
            // real shots: arrows owned by it, the tick they first appear
            List<Integer> realShots = new ArrayList<>();
            List<A> launches = new ArrayList<>();
            for (List<A> as : s.arrows.values()) {
                A a = as.get(0);
                if (a.owner() == e.getKey() && a.t() >= t0) { realShots.add(a.t()); launches.add(a); }
            }
            realShots.sort(Integer::compare);
            launches.sort((x, y) -> Integer.compare(x.t(), y.t()));
            // the model from the goal's first tick (its first tick already ran: the draw started, seeTime 1)
            MobSim m = new MobSim();
            m.kind = MobSim.Kind.BOW; m.speed = 0.25; m.width = 0.6; m.height = 1.99; m.eyeHeight = 1.74;
            m.x = r0.x(); m.y = r0.y(); m.z = r0.z(); m.vx = r0.vx(); m.vy = r0.vy(); m.vz = r0.vz(); m.floorY = -60;
            m.onGround = r0.ground(); m.yRot = r0.yRot();
            m.drawTicks = r0.using() ? r0.usingTicks() : -1; m.attackTime = -1; m.seeTime = 1; m.strafeTime = -1;
            m.bowInterval = interval;
            List<Integer> modelShots = new ArrayList<>();
            double walkErr = 0, strafeSpeedReal = 0, strafeSpeedModel = 0;
            int strafeN = 0;
            SplittableRandom rnd = new SplittableRandom(5);
            for (int k = st + 1; k < rows.size(); k++) {
                M r = rows.get(k);
                P p = s.player.get(r.t());
                if (p == null) continue;
                boolean shot = m.thinkBow(p.x(), p.y(), p.z(), rnd);
                if (shot) modelShots.add(r.t());
                // follow the real skeleton's position (open loop only for its timing), to read the strafe speed
                double mx0 = m.x, mz0 = m.z;
                m.move();
                if (r.t() - t0 < 19) walkErr = Math.max(walkErr, Math.hypot(m.x - r.x(), m.z - r.z()));
                if (m.op == MobSim.OP_STRAFE && k > st + 25 && r.ground() && rows.get(k - 1).ground()) {
                    // the push this tick: the move less the velocity it started with (the last tick's, after drag)
                    M q = rows.get(k - 1);
                    double px = (r.x() - q.x()) - q.vx(), pz = (r.z() - q.z()) - q.vz();
                    strafeSpeedReal += Math.hypot(px, pz);
                    double ex = m.x - mx0 - q.vx(), ez = m.z - mz0 - q.vz();
                    strafeSpeedModel += Math.hypot(ex, ez);

                    // the push's direction against the facing: forward and right parts (the model's are +-0.5 each)
                    double yr = Math.toRadians(r.yRot()), sin = Math.sin(yr), cos = Math.cos(yr);
                    double fwd = -px * sin + pz * cos, right = px * cos + pz * sin;
                    if (strafeN < 3) out(String.format(Locale.ROOT, "  strafe push at t+%d: forward %.4f right %.4f (model +-%.4f each)", r.t() - t0, fwd, right, 0.5 * 0.25 * 0.25));
                    strafeN++;
                }
                m.x = r.x(); m.y = r.y(); m.z = r.z(); m.vx = r.vx(); m.vy = r.vy(); m.vz = r.vz(); m.onGround = r.ground();
            }
            out(String.format(Locale.ROOT, "%s mob %d: shots real %s model %s (ticks after its goal starts)", s.name, e.getKey(),
                rel(realShots, t0), rel(modelShots, t0)));
            int matched = 0, worst = 0;
            for (int k = 0; k < Math.min(realShots.size(), modelShots.size()); k++) {
                matched++;
                worst = Math.max(worst, Math.abs(realShots.get(k) - modelShots.get(k)));
            }
            res[0] += matched; res[1] += realShots.size(); res[2] = Math.max(res[2], worst);
            if (strafeN > 0) res[6] = Math.max(res[6], Math.abs(strafeSpeedReal - strafeSpeedModel) / strafeN);
            if (strafeN > 0) out(String.format(Locale.ROOT, "%s mob %d: walk in (first 19 ticks) worst position error %.3f; strafing push real %.4f model %.4f a tick (%d ticks)",
                s.name, e.getKey(), walkErr, strafeSpeedReal / strafeN, strafeSpeedModel / strafeN, strafeN));
            // each arrow: its flight from its real launch, the aim, a hit and its damage
            BlockGrid g = flat();
            for (A a0 : launches) {
                List<A> as = s.arrows.get(a0.id());
                ArrowSim sim = new ArrowSim();
                sim.x = a0.x(); sim.y = a0.y(); sim.z = a0.z(); sim.vx = a0.vx(); sim.vy = a0.vy(); sim.vz = a0.vz();
                double flightErr = 0;
                int hitAt = -1;
                for (int k = 1; k < as.size(); k++) {
                    A a = as.get(k);
                    P p = s.player.get(a.t());
                    if (p == null || !sim.alive) break;
                    double dmg = sim.tick(box(p), g);
                    if (dmg >= 0) { hitAt = a.t(); break; }
                    if (Math.abs(a.vx()) + Math.abs(a.vz()) < 1e-6) break;   // stuck in the ground
                    flightErr = Math.max(flightErr, Math.sqrt(sq(sim.x - a.x()) + sq(sim.y - a.y()) + sq(sim.z - a.z())));
                }
                if (hitAt < 0 && sim.alive) {   // the arrow's rows ended (it hit or stuck): try the next tick's player
                    A last = as.get(as.size() - 1);
                    P p = s.player.get(last.t() + 1);
                    if (p != null) { double dmg = sim.tick(box(p), g); if (dmg >= 0) hitAt = last.t() + 1; }
                }
                res[3] = Math.max(res[3], flightErr);
                // aim: the model's direction (no spread) from the real skeleton then against the real launch
                M sk = null;
                for (M r : rows) if (r.t() == a0.t()) sk = r;
                P p0 = s.player.get(a0.t());
                String aim = "";
                if (sk != null && p0 != null) {
                    double ay = sk.y() + 1.74 - 0.1F;
                    double xd = p0.x() - sk.x(), yd = p0.y() + 0.6 - ay, zd = p0.z() - sk.z(), dist = Math.hypot(xd, zd);
                    yd += dist * 0.2F;
                    double len = Math.sqrt(xd * xd + yd * yd + zd * zd);
                    double sp = Math.sqrt(sq(a0.vx()) + sq(a0.vy()) + sq(a0.vz()));
                    double dot = (xd * a0.vx() + yd * a0.vy() + zd * a0.vz()) / len / sp;
                    aim = String.format(Locale.ROOT, " aim off %.2f deg, launch speed %.3f, launch at %.2f %.2f %.2f (model %.2f %.2f %.2f)",
                        Math.toDegrees(Math.acos(Math.min(1, dot))), sp, a0.x(), a0.y(), a0.z(), sk.x(), ay, sk.z());
                }
                // the real hit: the player's health fell the tick the arrow stopped
                A last = as.get(as.size() - 1);
                int realHit = -1;
                double lost = 0;
                for (int t = last.t(); t <= last.t() + 2; t++) {
                    P p = s.player.get(t), q = s.player.get(t - 1);
                    if (p != null && q != null && p.health() < q.health() - 0.01 && q.invul() <= 10) { realHit = t; lost = q.health() - p.health(); break; }
                }
                res[5]++;
                if ((realHit < 0) == (hitAt < 0) && (realHit < 0 || realHit == hitAt)) res[4]++;
                String dmgRange = "";
                if (hitAt >= 0) {
                    double sp = Math.sqrt(sq(sim.vx) + sq(sim.vy) + sq(sim.vz));
                    dmgRange = String.format(Locale.ROOT, " model damage %d to %d (pessimistic %d)", (int) Math.ceil(sp * 1.64575), (int) Math.ceil(sp * 2.79425), (int) Math.ceil(sp * 2.79425));
                }
                out(String.format(Locale.ROOT, "%s arrow %d (shot t+%d): flight error %.2e; hit real %s model %s%s%s", s.name, a0.id(), a0.t() - t0,
                    flightErr, realHit < 0 ? "no" : "t+" + (realHit - t0) + " lost " + lost, hitAt < 0 ? "no" : "t+" + (hitAt - t0), dmgRange, aim));
            }
            // the client's view of the draw: its count against the server's at the same tick
            List<CM> cms = s.clientMobs.get(e.getKey());
            if (cms != null) {
                int n = 0; double lag = 0;
                Map<Integer, M> byT = new TreeMap<>();
                for (M r : rows) byT.put(r.t(), r);
                for (CM c : cms) {
                    M r = byT.get(c.t());
                    if (r == null || !r.using() || !c.using()) continue;
                    lag += r.usingTicks() - c.usingTicks(); n++;
                }
                if (n > 0) out(String.format(Locale.ROOT, "%s mob %d: the client's draw count trails the server's by %.2f ticks (%d samples)", s.name, e.getKey(), lag / n, n));
            }
        }
        return res;
    }


    // ------------------------------------------------------------------ melee walkers, creepers, spiders

    /** The tick a mob first moves (its goal has started), as an index into its rows, or -1. */
    static int firstMove(List<M> rows) {
        for (int k = 1; k < rows.size(); k++)
            if (Math.hypot(rows.get(k).x() - rows.get(k - 1).x(), rows.get(k).z() - rows.get(k - 1).z()) > 0.03) return k - 1;
        return -1;
    }

    /** The ticks the player took a full hit (its health fell with its hurt window at 10 or below). */
    static List<Integer> fullHits(Scene s) {
        List<Integer> h = new ArrayList<>();
        P prev = null;
        for (P p : s.player.values()) {
            if (prev != null && p.health() < prev.health() - 0.01 && prev.invul() <= 10) h.add(p.t());
            prev = p;
        }
        return h;
    }

    static MobSim melee(M r0, String type) {
        MobSim m = new MobSim();
        m.kind = MobSim.Kind.MELEE;
        m.speed = 0.23; m.width = 0.6; m.height = 1.95;
        switch (type) {
            case "wither_skeleton" -> { m.speed = 0.25; m.speedModifier = 1.2; m.width = 0.7; m.height = 2.4; }
            case "zombie_baby" -> { m.speed = 0.23 * 1.5; m.width = 0.3; m.height = 0.975; }
            case "creeper" -> { m.kind = MobSim.Kind.CREEPER; m.speed = 0.25; m.height = 1.7; }
            case "spider" -> { m.kind = MobSim.Kind.LEAPER; m.follows = true; m.speed = 0.3; m.width = 1.4; m.height = 0.9; }
            case "cave_spider" -> { m.kind = MobSim.Kind.LEAPER; m.follows = true; m.speed = 0.3; m.width = 0.7; m.height = 0.5; }
            case "vindicator" -> { m.speed = 0.35; }
            case "piglin_brute", "piglin" -> { m.speed = 0.35; m.follows = true; }
            case "hoglin", "zoglin" -> { m.speed = 0.3; m.width = 1.3964844; m.height = 1.4; m.follows = true; m.attackInterval = 40; }
            case "enderman" -> { m.speed = 0.45; m.height = 2.9; }   // 0.3 and the attacking modifier 0.15
            case "zombified_piglin" -> { m.speed = 0.23; }
            case "silverfish", "endermite" -> { m.speed = 0.25; m.width = 0.4; m.height = 0.3; }
            default -> {}
        }
        m.x = r0.x(); m.y = r0.y(); m.z = r0.z(); m.vx = r0.vx(); m.vy = r0.vy(); m.vz = r0.vz();
        m.onGround = r0.ground(); m.floorY = -60; m.attackCooldown = 0; m.active = true; m.sinceCheck = 0;
        return m;
    }

    /** A walker that hits in reach (the zombie family, the wither skeleton) or a spider: from the tick it first moves,
     *  replayed open loop in several draws of its random path timing, against the player's recorded positions: its
     *  position error, its attack ticks against the player's full hits, its walking speed; for a spider its leaps.
     *  Returns {worst position error in the first 40 ticks of draw 0, first attack error in ticks}. */
    static double[] walker(Scene s, String type) {
        double[] res = {0, 0};
        for (var e : s.mobs.entrySet()) {
            List<M> rows = e.getValue();
            int st = firstMove(rows);
            if (st < 0) { out(s.name + ": never moved"); continue; }
            M r0 = rows.get(st);
            int t0 = r0.t();
            List<Integer> real = new ArrayList<>();
            for (int h : fullHits(s)) if (h > t0) real.add(h);
            // its steady walking speed: the median of its ground moves over 0.05 a tick
            List<Double> sp = new ArrayList<>();
            for (int k = st + 1; k < rows.size(); k++) {
                M a = rows.get(k), b = rows.get(k - 1);
                double d = Math.hypot(a.x() - b.x(), a.z() - b.z());
                if (a.ground() && b.ground() && d > 0.05) sp.add(d);
            }
            sp.sort(Double::compare);
            double steady = sp.isEmpty() ? 0 : sp.get(sp.size() - 1);
            double worst40 = 0;
            List<Integer> model0 = null;
            int leapsReal = 0;
            for (int k = st + 1; k < rows.size(); k++) if (rows.get(k).vy() > 0.3 && rows.get(k - 1).ground()) leapsReal++;
            int leapsModel = 0;
            for (int draw = 0; draw < 8; draw++) {
                MobSim m = melee(r0, type);
                SplittableRandom rnd = new SplittableRandom(draw * 7919L + 1);
                List<Integer> st2 = new ArrayList<>();
                for (int k = st + 1; k < rows.size(); k++) {
                    M r = rows.get(k);
                    P p = s.player.get(r.t());
                    if (p == null) continue;
                    if (m.kind == MobSim.Kind.CREEPER) {
                        if (m.swellTick()) break;
                        m.thinkCreeper(p.x(), p.y(), p.z(), box(p), rnd);
                    } else {
                        boolean wasLeap = m.leaping;
                        if (m.think(p.x(), p.y(), p.z(), box(p), rnd)) st2.add(r.t());
                        if (m.leaping && !wasLeap && draw == 0) leapsModel++;
                    }
                    m.move();
                    if (draw == 0 && r.t() - t0 <= 40) worst40 = Math.max(worst40, Math.hypot(m.x - r.x(), m.z - r.z()));
                }
                if (draw == 0) model0 = st2;
            }
            res[0] = Math.max(res[0], worst40);
            if (!real.isEmpty() && model0 != null && !model0.isEmpty()) res[1] = Math.max(res[1], Math.abs(real.get(0) - model0.get(0)));
            double model = melee(r0, type).walkSpeed();
            out(String.format(Locale.ROOT, "%s mob %d (%s): walks at most %.4f a tick on the ground (%d moves; model %.4f); worst position error in its first 40 ticks %.3f; attacks real %s model %s%s",
                s.name, e.getKey(), type, steady, sp.size(), model, worst40, rel(real, t0), model0 == null ? "[]" : rel(model0, t0),
                type.contains("spider") ? String.format(Locale.ROOT, "; leaps real %d, model (draw 0) %d", leapsReal, leapsModel) : ""));
            if (type.contains("spider")) leaps(s, rows, t0);
        }
        return res;
    }

    /** Each real leap against LeapAtTargetGoal.start: the velocity it set from the velocity before and the player then. */
    static void leaps(Scene s, List<M> rows, int t0) {
        for (int k = 1; k < rows.size(); k++) {
            M r = rows.get(k), q = rows.get(k - 1);
            if (!(r.vy() > 0.3 && q.ground())) continue;
            P p = s.player.get(r.t());
            if (p == null) continue;
            // the goal set (dx, dz) / d x 0.4 + v x 0.2 and vy 0.4 before the move; the row is after the move and its drag
            double dx = p.x() - q.x(), dz = p.z() - q.z(), d = Math.hypot(dx, dz);
            double ex = dx / d * 0.4 + q.vx() * 0.2, ez = dz / d * 0.4 + q.vz() * 0.2;
            double mx = r.x() - q.x(), mz = r.z() - q.z(), my = r.y() - q.y();
            out(String.format(Locale.ROOT, "  leap at t+%d from %.2f blocks: its move %.4f %.4f %.4f, the goal's push %.4f %.4f 0.4000",
                r.t() - t0, Math.sqrt(d * d + sq(p.y() - q.y())), mx, my, mz, ex, ez));
        }
    }

    /** A creeper from the tick it first moves: its swell (the real count, swelling x 28) against the model's tick by
     *  tick, the blast's tick, the player's loss against Blast at the real positions, and the player's knocked move on
     *  the client against the push. */
    static void creeper(Scene s) {
        for (var e : s.mobs.entrySet()) {
            List<M> rows = e.getValue();
            int st = firstMove(rows);
            if (st < 0) { out(s.name + ": never moved"); continue; }
            M r0 = rows.get(st);
            int t0 = r0.t();
            StringBuilder realS = new StringBuilder(), modelS = new StringBuilder();
            MobSim m = melee(r0, "creeper");
            SplittableRandom rnd = new SplittableRandom(3);
            int modelBlast = -1, realBlast = -1;
            M lastRow = rows.get(rows.size() - 1);
            int prevSwell = 0;
            for (int k = st + 1; k < rows.size(); k++) {
                M r = rows.get(k);
                P p = s.player.get(r.t());
                if (p == null) continue;
                int rs = (int) Math.round(r.swelling() * 28.0);
                if (modelBlast < 0) {
                    if (m.swellTick()) modelBlast = r.t();
                    else { m.thinkCreeper(p.x(), p.y(), p.z(), box(p), rnd); m.move(); }
                }
                if (rs != prevSwell || m.swell != (modelBlast < 0 ? m.swell : -1)) {
                    if ((r.t() - t0) % 5 == 0 || rs == 1 || rs == 28) {
                        realS.append(String.format(Locale.ROOT, " t+%d:%d", r.t() - t0, rs));
                        modelS.append(String.format(Locale.ROOT, " t+%d:%d", r.t() - t0, modelBlast < 0 ? m.swell : 30));
                    }
                }
                prevSwell = rs;
            }
            if (s.mobs.get(e.getKey()).size() > 0 && lastRow.t() < s.player.lastKey() - 2) realBlast = lastRow.t() + 1;
            // closed loop: the copy's swell and goals, its position put back to the real creeper's each tick (as each
            // replan's snapshot does), so only the swell rules are tested
            MobSim cl = melee(r0, "creeper");
            SplittableRandom rnd2 = new SplittableRandom(3);
            int clBlast = -1, clStart = -1, clBack = -1, realStart = -1, realBack = -1;
            for (int k = st + 1; k < rows.size() && clBlast < 0; k++) {
                M r = rows.get(k), q = rows.get(k - 1);
                P p = s.player.get(r.t());
                if (p == null) continue;
                cl.x = q.x(); cl.y = q.y(); cl.z = q.z(); cl.vx = q.vx(); cl.vy = q.vy(); cl.vz = q.vz(); cl.onGround = q.ground();
                int dir0 = cl.swellDir;
                if (cl.swellTick()) { clBlast = r.t(); break; }
                cl.thinkCreeper(p.x(), p.y(), p.z(), box(p), rnd2);
                if (cl.swellDir > 0 && dir0 <= 0 && clStart < 0) clStart = r.t();
                if (cl.swellDir < 0 && dir0 > 0 && clBack < 0) clBack = r.t();
                if (r.swellDir() > 0 && q.swellDir() <= 0 && realStart < 0) realStart = r.t();
                if (r.swellDir() < 0 && q.swellDir() > 0 && realBack < 0) realBack = r.t();
            }
            for (int k = st + 1; k < rows.size(); k++) {
                M r = rows.get(k), q = rows.get(k - 1);
                if (r.swellDir() > 0 && q.swellDir() <= 0 && realStart < 0) realStart = r.t();
                if (r.swellDir() < 0 && q.swellDir() > 0 && realBack < 0) realBack = r.t();
            }
            out(String.format(Locale.ROOT, "%s creeper %d (closed loop): fuse lit real %s copy %s; turned back real %s copy %s; blast real %s copy %s",
                s.name, e.getKey(), realStart < 0 ? "never" : "t+" + (realStart - t0), clStart < 0 ? "never" : "t+" + (clStart - t0),
                realBack < 0 ? "never" : "t+" + (realBack - t0), clBack < 0 ? "never" : "t+" + (clBack - t0),
                realBlast < 0 ? "none" : "t+" + (realBlast - t0), clBlast < 0 ? "none" : "t+" + (clBlast - t0)));
            out(String.format(Locale.ROOT, "%s creeper %d: swell real%s", s.name, e.getKey(), realS));
            out(String.format(Locale.ROOT, "%s creeper %d: swell model%s", s.name, e.getKey(), modelS));
            out(String.format(Locale.ROOT, "%s creeper %d: blast real %s model %s", s.name, e.getKey(),
                realBlast < 0 ? "none" : "t+" + (realBlast - t0), modelBlast < 0 ? "none" : "t+" + (modelBlast - t0)));
            if (realBlast >= 0) {
                P p = s.player.get(realBlast), q = s.player.get(realBlast - 1);
                double[] pb = box(p);
                double[] b = Blast.onPlayer(lastRow.x(), lastRow.y(), lastRow.z(), 3.0, pb, p.y() + 1.62, flat());
                double lost = q == null ? 0 : q.health() - p.health();
                out(String.format(Locale.ROOT, "%s creeper %d: player %.2f blocks away (feet); lost %.2f, Blast %s",
                    s.name, e.getKey(), Math.sqrt(sq(p.x() - lastRow.x()) + sq(p.y() - lastRow.y()) + sq(p.z() - lastRow.z())), lost,
                    b == null ? "out of range" : String.format(Locale.ROOT, "%.2f damage, push %.4f %.4f %.4f", b[0], b[1], b[2], b[3])));
                // the client's moves after the blast
                StringBuilder cs = new StringBuilder();
                C prev = null;
                for (C c : s.client) {
                    if (c.t() >= realBlast - 1 && c.t() <= realBlast + 4 && prev != null)
                        cs.append(String.format(Locale.ROOT, " [t+%d move %.4f %.4f %.4f v %.4f %.4f %.4f]", c.t() - t0, c.x() - prev.x(), c.y() - prev.y(), c.z() - prev.z(), c.vx(), c.vy(), c.vz()));
                    prev = c;
                }
                out(String.format(Locale.ROOT, "%s creeper %d: client after the blast%s", s.name, e.getKey(), cs));
            }
            // the client's view of the swell against the server's
            List<CM> cms = s.clientMobs.get(e.getKey());
            if (cms != null) {
                Map<Integer, M> byT = new TreeMap<>();
                for (M r : rows) byT.put(r.t(), r);
                double lag = 0; int n = 0;
                for (CM c : cms) {
                    M r = byT.get(c.t());
                    if (r == null || r.swelling() <= 0 || c.swelling() <= 0 || r.swellDir() < 0) continue;
                    lag += Math.round(r.swelling() * 28) - Math.round(c.swelling() * 28); n++;
                }
                if (n > 0) out(String.format(Locale.ROOT, "%s creeper %d: the client's swell trails the server's by %.2f ticks (%d samples)", s.name, e.getKey(), lag / n, n));
            }
        }
    }


    /** A pillager: its shots (the arrows it fires) against the crossbow goal's timing (charge 25, then 20 to 39 random:
     *  45 to 64 ticks from the start of a charge to the shot), its walking speed (0.2698 at full speed, 0.0674 at half
     *  while charging), and its arrows' flight and hits as for skeletons. */
    static void crossbow(Scene s) {
        for (var e : s.mobs.entrySet()) {
            List<M> rows = e.getValue();
            List<Integer> shots = new ArrayList<>();
            for (List<A> as : s.arrows.values()) if (as.get(0).owner() == e.getKey()) shots.add(as.get(0).t());
            shots.sort(Integer::compare);
            // the charge starts: its using flag rising
            List<Integer> charges = new ArrayList<>();
            for (int k = 1; k < rows.size(); k++) if (rows.get(k).using() && !rows.get(k - 1).using()) charges.add(rows.get(k).t());
            StringBuilder sb = new StringBuilder();
            for (int c : charges) {
                int next = -1;
                for (int sh : shots) if (sh > c) { next = sh; break; }
                sb.append(String.format(Locale.ROOT, " %d", next < 0 ? -1 : next - c));
            }
            double top = 0;
            for (int k = 1; k < rows.size(); k++)
                if (rows.get(k).ground() && rows.get(k - 1).ground())
                    top = Math.max(top, Math.hypot(rows.get(k).x() - rows.get(k - 1).x(), rows.get(k).z() - rows.get(k - 1).z()));
            out(String.format(Locale.ROOT, "%s pillager %d: %d shots; from each charge's start to its shot (model 45 to 64):%s; walks at most %.4f a tick (model %.4f)",
                s.name, e.getKey(), shots.size(), sb, top, 0.35 * 0.35 / (1 - MobSim.GROUND_FRICTION)));
            BlockGrid g = flat();
            for (List<A> as : s.arrows.values()) {
                A a0 = as.get(0);
                if (a0.owner() != e.getKey()) continue;
                ArrowSim sim = new ArrowSim();
                sim.x = a0.x(); sim.y = a0.y(); sim.z = a0.z(); sim.vx = a0.vx(); sim.vy = a0.vy(); sim.vz = a0.vz(); sim.base = 2.0;
                double err = 0; int hitAt = -1;
                for (int k = 1; k < as.size() && sim.alive; k++) {
                    A a = as.get(k);
                    P p = s.player.get(a.t());
                    if (p == null) break;
                    double dmg = sim.tick(box(p), g);
                    if (dmg >= 0) { hitAt = a.t(); break; }
                    err = Math.max(err, Math.sqrt(sq(sim.x - a.x()) + sq(sim.y - a.y()) + sq(sim.z - a.z())));
                }
                A last = as.get(as.size() - 1);
                if (hitAt < 0 && sim.alive) { P p = s.player.get(last.t() + 1); if (p != null && sim.tick(box(p), g) >= 0) hitAt = last.t() + 1; }
                int realHit = -1; double lost = 0;
                for (int t = last.t(); t <= last.t() + 2; t++) {
                    P p = s.player.get(t), q = s.player.get(t - 1);
                    if (p != null && q != null && p.health() < q.health() - 0.01 && q.invul() <= 10) { realHit = t; lost = q.health() - p.health(); break; }
                }
                out(String.format(Locale.ROOT, "  arrow %d: flight error %.2e; hit real %s copy %s; speed %.3f", a0.id(), err,
                    realHit < 0 ? "no" : (realHit - a0.t()) + " ticks, lost " + lost, hitAt < 0 ? "no" : (hitAt - a0.t()) + " ticks, damage " + (int) Math.ceil(Math.sqrt(sq(sim.vx) + sq(sim.vy) + sq(sim.vz)) * 2.0),
                    Math.sqrt(sq(a0.vx()) + sq(a0.vy()) + sq(a0.vz()))));
            }
        }
    }


    /** A witch: its throws (the potions it makes) against every 60 ticks, each potion's flight from its real throw,
     *  and the player's loss where a potion broke against the copy's splash (harming 6 at full intensity). */
    static void witch(Scene s) {
        for (var e : s.mobs.entrySet()) {
            List<Integer> throwsAt = new ArrayList<>();
            for (List<A> as : s.arrows.values()) if (as.get(0).owner() == e.getKey()) throwsAt.add(as.get(0).t());
            throwsAt.sort(Integer::compare);
            StringBuilder gaps = new StringBuilder();
            for (int k = 1; k < throwsAt.size(); k++) gaps.append(' ').append(throwsAt.get(k) - throwsAt.get(k - 1));
            out(String.format(Locale.ROOT, "%s witch %d: %d throws, between them (copy 60):%s", s.name, e.getKey(), throwsAt.size(), gaps));
            BlockGrid g = flat();
            for (List<A> as : s.arrows.values()) {
                A a0 = as.get(0);
                if (a0.owner() != e.getKey()) continue;
                ArrowSim sim = new ArrowSim();
                sim.x = a0.x(); sim.y = a0.y(); sim.z = a0.z(); sim.vx = a0.vx(); sim.vy = a0.vy(); sim.vz = a0.vz();
                sim.gravity = 0.05; sim.drag = 0.99; sim.gravityFirst = true; sim.splash = true; sim.splashDamage = 6.0;
                double err = 0; double in = 0; int end = -1;
                for (int k = 1; k < as.size() && sim.alive; k++) {
                    A a = as.get(k);
                    P p = s.player.get(a.t());
                    if (p == null) break;
                    double dmg = sim.tick(box(p), g);
                    if (dmg >= 0) { in = 1.0; end = a.t(); break; }
                    if (!sim.alive) { in = sim.splashOn(box(p)); end = a.t(); break; }
                    err = Math.max(err, Math.sqrt(sq(sim.x - a.x()) + sq(sim.y - a.y()) + sq(sim.z - a.z())));
                }
                A last = as.get(as.size() - 1);
                if (end < 0 && sim.alive) {
                    P p = s.player.get(last.t() + 1);
                    if (p != null) { double dmg = sim.tick(box(p), g); in = dmg >= 0 ? 1.0 : sim.alive ? 0 : sim.splashOn(box(p)); end = last.t() + 1; }
                }
                double lost = 0;
                for (int t = last.t(); t <= last.t() + 2; t++) {
                    P p = s.player.get(t), q = s.player.get(t - 1);
                    if (p != null && q != null && p.health() < q.health() - 0.01) { lost = q.health() - p.health(); break; }
                }
                out(String.format(Locale.ROOT, "  potion %d (thrown t%d): flight error %.2e; broke real t%d copy %s; copy's intensity %.2f (harming would take %d); player lost %.1f then",
                    a0.id(), a0.t(), err, last.t() + 1, end < 0 ? "never" : "t" + end, in, (int) (in * 6 + 0.5), lost));
            }
        }
    }

    // ------------------------------------------------------------------ slimes and magma cubes

    /** A slime or magma cube copy at a recorded row, of the given size. */
    static MobSim slime(M r, boolean magma, int size) {
        MobSim m = new MobSim();
        m.kind = MobSim.Kind.SLIME;
        m.magma = magma;
        m.slimeSize = size;
        m.touchDamage = magma || size > 1;
        m.speed = 0.2F + 0.1F * size;
        m.jumpPower = 0.42F + (magma ? size * 0.1F : 0.0F);
        m.width = 0.52F * size; m.height = 0.52F * size;
        m.attackDamage = m.touchDamage ? size + (magma ? 2 : 0) : 0;
        m.x = r.x(); m.y = r.y(); m.z = r.z(); m.vx = r.vx(); m.vy = r.vy(); m.vz = r.vz();
        m.yRot = r.yRot(); m.onGround = r.ground(); m.floorY = -60;
        m.walking = false;
        return m;
    }

    /** Whether a mob's server tick is a full goal tick (Mob.serverAiStep: its tickCount at most 1, or tickCount + id
     *  even), the only ticks the slime's move control is asked to move, so the only ticks it counts its wait down and
     *  hops. */
    static boolean fullGoalTick(int tickCount, int id) {
        return tickCount <= 1 || (tickCount + id) % 2 == 0;
    }

    /** Slimes and magma cubes: the waits between hops against the copy's range (counted down on full goal ticks, so
     *  twice the wait in ticks, give or take one), each hop's flight replayed from the tick before it against the
     *  player's recorded positions, the turn a tick, and the player's contact hits against the copy's touch test (a
     *  scene holds the one summoned, the newest id, and in the older recordings the splits of the last scene's, killed
     *  as it began: each mob's size is read from its health on its first row).
     *
     *  <p>Full goal ticks come every other tick, so every hop of a mob comes on ticks of one parity. The summoned mob's
     *  rows start with its first tick (tickCount 1: summoned at rest on the floor, its first move has no fall in it yet,
     *  so its first row is off the ground and its second the landing), so its full goal ticks are known from its id: each of its hops must
     *  come on one, the first on the first one after the landing (the move control's jumpDelay starts at 0, as the
     *  copy's), 0 or 1 ground ticks after it by the id's parity. The copy takes the next tick as full (FightSnapshot),
     *  so its first wait is 0, never longer than the game's. Which parity a mob gets differs between the families only
     *  through the ids: up to 26.1.2 Entity takes its id from one counter in the JVM, which the client's copy of each
     *  entity also draws from in singleplayer, so in these recordings the server's ids go up by two and are all odd
     *  (first wait 0); from 26.2 ServerLevel.getNextEntityId hands them out and the client's copies take 0
     *  (Level.getNextEntityId) until the packet's id is set, so both parities come (first wait 0 or 1). A split's age
     *  isn't known, so its first wait is only held to the copy's range when it isn't 0 (it may have landed just before
     *  its first row).
     *  Returns {worst flight error, waits shorter than the copy's shortest or a summoned mob's first wait other than
     *  its goal ticks give, hits no copy touched, ticks a copy touched with the window open and no hit, damage errors,
     *  hops off a full goal tick (off the parity of the mob's other hops, for a split)}. */
    static double[] slimes(Scene s) {
        double[] res = new double[6];
        Map<Integer, MobSim> ref = new LinkedHashMap<>();
        Map<Integer, Map<Integer, M>> at = new HashMap<>();
        for (var e : s.mobs.entrySet()) {
            M r0 = e.getValue().get(0);
            boolean magma = r0.type().contains("magma");
            if (!magma && !r0.type().contains("slime")) continue;
            int size = (int) Math.round(Math.sqrt(r0.health()));
            ref.put(e.getKey(), slime(r0, magma, size));
            Map<Integer, M> byT = new HashMap<>();
            for (M r : e.getValue()) byT.put(r.t(), r);
            at.put(e.getKey(), byT);
        }
        int newest = ref.keySet().stream().max(Integer::compare).orElse(-1);
        for (var e : ref.entrySet()) {
            List<M> rows = s.mobs.get(e.getKey());
            MobSim r = e.getValue();
            int id = e.getKey(), t0 = rows.get(0).t();
            boolean summoned = id == newest;   // its first row is its tickCount 1
            int lo = r.minJumpDelay(), hi = (29 * (r.magma ? 4 : 1)) / (r.touchDamage ? 3 : 1);
            List<Integer> waits = new ArrayList<>();
            int landed = -1, hops = 0, outside = 0, below = 0, offFull = 0, firstRule = -1, parity = -1;
            double worst = 0, turn = 0;
            for (int k = 1; k < rows.size(); k++) {
                M prev = rows.get(k - 1), q0 = rows.get(k);
                turn = Math.max(turn, Math.abs(ai.rrw.kine.move.GameMath.wrapDegrees(q0.yRot() - prev.yRot())));
                if (!prev.ground() && q0.ground()) landed = q0.t();
                boolean hop = prev.ground() && q0.y() - prev.y() > 0.3;
                if (!hop) continue;
                hops++;
                if (summoned ? !fullGoalTick(q0.t() - t0 + 1, id) : parity >= 0 && (q0.t() & 1) != parity) offFull++;
                if (parity < 0) parity = q0.t() & 1;
                if (landed >= 0) {
                    int d = q0.t() - landed - 1;   // ground ticks between: the countdown runs on every other one
                    waits.add(d);
                    if (summoned && waits.size() == 1) {
                        // the first, after the landing as the scene began: on the first full goal tick after it
                        int full = landed + 1;
                        while (!fullGoalTick(full - t0 + 1, id)) full++;
                        firstRule = full - landed - 1;
                        if (d != firstRule) below++;
                    } else {
                        if (d < 2 * lo || d > 2 * hi + 1) outside++;
                        if (d < 2 * lo && (summoned || waits.size() > 1 || d > 0)) below++;   // sooner than the copy's shortest
                    }
                }
                // the flight: the copy from the row before (on the ground, its wait run out, this a full tick)
                MobSim m = slime(prev, r.magma, r.slimeSize);
                m.jumpDelay = 0; m.fullTick = true;
                SplittableRandom rnd = new SplittableRandom(1);
                double w = 0;
                for (int j = k; j < rows.size(); j++) {
                    M q = rows.get(j);
                    P p = s.player.get(q.t());
                    if (p == null) break;
                    m.thinkSlime(p.x(), p.z(), rnd);
                    if (j > k) m.jumpDelay = 99;   // one hop only
                    m.move();
                    w = Math.max(w, Math.sqrt(sq(m.x - q.x()) + sq(m.y - q.y()) + sq(m.z - q.z())));
                    if (j > k && q.ground()) break;
                }
                worst = Math.max(worst, w);
            }
            res[0] = Math.max(res[0], worst);
            res[1] += below;
            res[5] += offFull;
            out(String.format(Locale.ROOT, "%s %s size %d (%d%s): %d hops, %d off a full goal tick; ground ticks between %s (%scopy %d to %d, about 2x %d to %d), %d outside; flight error %.2e; turn at most %.1f a tick (copy 10)",
                s.name, r.magma ? "magma cube" : "slime", r.slimeSize, id, summoned ? ", summoned" : "", hops, offFull, waits,
                summoned ? "the first by its goal ticks " + firstRule + ", then " : "", 2 * lo, 2 * hi + 1, lo, hi, outside, worst, turn));
        }
        // contact hits: each full hit of the player against the copies' touch test at that tick and the tick before
        List<Integer> hits = fullHits(s);
        int unexplained = 0, falseTouch = 0;
        double dmgErr = 0;
        StringBuilder bad = new StringBuilder();
        for (P p : s.player.values()) {
            P before = s.player.get(p.t() - 1);
            if (before == null) continue;
            boolean hit = hits.contains(p.t());
            double best = Double.NaN;
            boolean any = false;
            for (var e : ref.entrySet()) {
                M r = at.get(e.getKey()).get(p.t()), r1 = at.get(e.getKey()).get(p.t() - 1);
                MobSim c = e.getValue();
                if (!c.touchDamage || r == null) continue;
                boolean touch = slime(r, c.magma, c.slimeSize).touches(box(p))
                    || r1 != null && slime(r1, c.magma, c.slimeSize).touches(box(before));
                if (!touch) continue;
                any = true;
                if (hit) {
                    double err = Math.abs(before.health() - p.health() - c.attackDamage);
                    best = Double.isNaN(best) ? err : Math.min(best, err);
                }
            }
            if (hit && !any) { unexplained++; bad.append(" t").append(p.t()); }
            if (hit && any) dmgErr = Math.max(dmgErr, best);
            if (!hit && any && before.invul() <= 10) falseTouch++;
        }
        res[2] += unexplained; res[3] += falseTouch; res[4] = Math.max(res[4], dmgErr);
        out(String.format(Locale.ROOT, "%s contact: %d full hits; %d with no copy touching%s; worst damage error %.2f; %d ticks a copy touched with the window open and no hit",
            s.name, hits.size(), unexplained, bad, dmgErr, falseTouch));
        return res;
    }

    // ------------------------------------------------------------------ drowned with tridents

    /** Drowned throwing tridents: the ticks between throws (copy 40), each trident's flight from its first row as an
     *  arrow flies (AbstractArrow), its hit against the player's recorded box, and the damage taken (copy 8). Returns
     *  {worst flight error, hits the copy got wrong}. */
    static double[] tridents(Scene s) {
        double[] res = new double[2];
        BlockGrid g = flat();
        for (var e : s.mobs.entrySet()) {
            List<Integer> at = new ArrayList<>();
            List<List<A>> mine = new ArrayList<>();
            for (List<A> as : s.arrows.values()) if (as.get(0).owner() == e.getKey()) { at.add(as.get(0).t()); mine.add(as); }
            if (mine.isEmpty()) continue;
            at.sort(Integer::compare);
            mine.sort((a, b) -> Integer.compare(a.get(0).t(), b.get(0).t()));
            StringBuilder gaps = new StringBuilder();
            for (int k = 1; k < at.size(); k++) gaps.append(' ').append(at.get(k) - at.get(k - 1));
            out(String.format(Locale.ROOT, "%s drowned %d: %d throws, between them (copy 40):%s", s.name, e.getKey(), at.size(), gaps));
            for (List<A> as : mine) {
                A a0 = as.get(0);
                ArrowSim sim = new ArrowSim();
                sim.x = a0.x(); sim.y = a0.y(); sim.z = a0.z(); sim.vx = a0.vx(); sim.vy = a0.vy(); sim.vz = a0.vz();
                sim.fixed = 8.0; sim.age = a0.age();
                double err = 0; int hitAt = -1;
                for (int k = 1; k < as.size() && sim.alive; k++) {
                    A a = as.get(k);
                    P p = s.player.get(a.t());
                    if (p == null) break;
                    if (sim.tick(box(p), g) >= 0) { hitAt = a.t(); break; }
                    if (Math.abs(a.vx()) + Math.abs(a.vz()) < 1e-6) break;   // stuck in the ground
                    err = Math.max(err, Math.sqrt(sq(sim.x - a.x()) + sq(sim.y - a.y()) + sq(sim.z - a.z())));
                }
                A last = as.get(as.size() - 1);
                if (hitAt < 0 && sim.alive) {
                    P p = s.player.get(last.t() + 1);
                    if (p != null && sim.tick(box(p), g) >= 0) hitAt = last.t() + 1;
                }
                // the real hit: a trident that hits bounces back (its velocity turns round) and the player loses health
                int realAt = -1;
                for (int k = 1; k < as.size(); k++) {
                    A a = as.get(k), q = as.get(k - 1);
                    if (a.vx() * q.vx() + a.vz() * q.vz() < 0) { realAt = a.t(); break; }
                }
                double lost = 0;
                for (int t = realAt < 0 ? a0.t() : realAt - 1; realAt >= 0 && t <= realAt + 1; t++) {
                    P p = s.player.get(t), q = s.player.get(t - 1);
                    if (p != null && q != null && p.health() < q.health() - 0.01) { lost = q.health() - p.health(); break; }
                }
                if ((realAt >= 0) != (hitAt >= 0) || realAt >= 0 && Math.abs(realAt - hitAt) > 1) res[1]++;
                res[0] = Math.max(res[0], err);
                out(String.format(Locale.ROOT, "  trident %d (t%d): flight error %.2e; copy %s; real %s, player lost %.1f",
                    a0.id(), a0.t(), err, hitAt >= 0 ? "hit t" + hitAt : "missed", realAt >= 0 ? "hit t" + realAt : "missed", lost));
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ evokers and vexes

    /** Evokers: each cast (the swell column: casting) against its fangs: made 18 ticks after the cast began (copy), where
     *  the copy puts them (from the evoker and the player at that tick), and each fang's bite (its warmup plus 8 after
     *  it is made) against the player's loss when it stood in the fang's box. Returns {casts whose fangs came off the
     *  copy's tick by more than 1, worst fang position error, bites the copy got wrong}. */
    static double[] evokers(Scene s) {
        double[] res = new double[3];
        for (var e : s.mobs.entrySet()) {
            List<M> rows = e.getValue();
            if (!rows.get(0).type().contains("evoker")) continue;
            Map<Integer, M> at = new HashMap<>();
            for (M r : rows) at.put(r.t(), r);
            List<Integer> rises = new ArrayList<>();
            for (int k = 1; k < rows.size(); k++)
                if (rows.get(k).swellDir() == 1 && rows.get(k - 1).swellDir() != 1) rises.add(rows.get(k).t());
            // the fangs made each tick
            Map<Integer, List<F>> made = new java.util.TreeMap<>();
            for (List<F> fs : s.fangs.values()) made.computeIfAbsent(fs.get(0).t(), k -> new ArrayList<>()).add(fs.get(0));
            StringBuilder sb = new StringBuilder();
            for (int rise : rises) {
                Integer when = null;
                for (int t : made.keySet()) if (t >= rise && t < rise + 40) { when = t; break; }
                sb.append(" [cast t").append(rise).append(when == null ? " no fangs" : " fangs +" + (when - rise) + " (" + made.get(when).size() + ")").append(']');
                if (when == null) continue;
                if (Math.abs(when - rise - 18) > 1) res[0]++;
                // where the copy puts them, from the evoker's and the player's positions in the tick they were made
                M ev = at.getOrDefault(when - 1, at.get(when));
                P p = s.player.get(when);
                if (ev == null || p == null) continue;
                FightWorld w = new FightWorld();
                w.player = new ai.rrw.kine.move.MoveState();
                w.player.x = p.x(); w.player.y = p.y(); w.player.z = p.z();
                MobSim m = new MobSim(); m.x = ev.x(); m.y = ev.y(); m.z = ev.z();
                w.castFangs(m);
                double worst = 0;
                for (F f : made.get(when)) {
                    double best = Double.MAX_VALUE;
                    for (double[] c : w.fangs) best = Math.min(best, Math.hypot(c[0] - f.x(), c[2] - f.z()));
                    worst = Math.max(worst, best);
                }
                res[1] = Math.max(res[1], worst);
                // the bites: a fang whose box held the player at its bite tick against the player's loss then
                for (F f : made.get(when)) {
                    int bite = f.t() + f.warmup() + 8;
                    P b = s.player.get(bite), b0 = s.player.get(bite - 1);
                    if (b == null || b0 == null) continue;
                    double[] pb = box(b0);
                    boolean in = f.x() - 0.45 < pb[3] && f.x() + 0.45 > pb[0] && f.y() < pb[4] && f.y() + 0.8 > pb[1] && f.z() - 0.45 < pb[5] && f.z() + 0.45 > pb[2];
                    boolean lost = b.health() < b0.health() - 0.01;
                    if (in && !lost && b0.invul() <= 10) res[2]++;
                    if (in) sb.append(String.format(Locale.ROOT, " bite t%d %s", bite, lost ? "took " + (b0.health() - b.health()) : "nothing"));
                }
                sb.append(String.format(Locale.ROOT, " (positions within %.3f)", worst));
            }
            out(String.format(Locale.ROOT, "%s evoker %d: %d casts (copy: fangs +18):%s", s.name, e.getKey(), rises.size(), sb));
        }
        return res;
    }

    /** Vexes: every tick it charges (the swell column), one step of the copy from the row before against the next row,
     *  and the player's full hits against the copy's touch test. Returns {worst position error, steps, hits with no
     *  copy touching}. */
    static double[] vexes(Scene s) {
        double[] res = new double[3];
        List<Integer> hits = fullHits(s);
        for (var e : s.mobs.entrySet()) {
            List<M> rows = e.getValue();
            if (!rows.get(0).type().contains("vex")) continue;
            double worst = 0;
            int steps = 0, unexplained = 0;
            Map<Integer, M> at = new HashMap<>();
            for (M r : rows) at.put(r.t(), r);
            for (int k = 1; k < rows.size(); k++) {
                M a = rows.get(k - 1), b = rows.get(k);
                if (a.swellDir() != 1 || b.swellDir() != 1 || b.t() != a.t() + 1) continue;
                P p = s.player.get(b.t());
                if (p == null) continue;
                MobSim m = new MobSim();
                m.kind = MobSim.Kind.VEX; m.width = 0.4; m.height = 0.8;
                m.x = a.x(); m.y = a.y(); m.z = a.z(); m.vx = a.vx(); m.vy = a.vy(); m.vz = a.vz();
                m.thinkVex(p.x(), p.y(), p.z(), new double[]{1e9, 1e9, 1e9, 1e9, 1e9, 1e9});
                m.move();
                worst = Math.max(worst, Math.sqrt(sq(m.x - b.x()) + sq(m.y - b.y()) + sq(m.z - b.z())));
                steps++;
            }
            for (int h : hits) {
                boolean any = false;
                for (int lagT = 0; lagT <= 1 && !any; lagT++) {
                    M r = at.get(h - lagT);
                    P p = s.player.get(h - lagT);
                    if (r == null || p == null) continue;
                    double[] pb = box(p);
                    any = r.x() - 0.2 < pb[3] && r.x() + 0.2 > pb[0] && r.y() < pb[4] && r.y() + 0.8 > pb[1] && r.z() - 0.2 < pb[5] && r.z() + 0.2 > pb[2];
                }
                if (!any) unexplained++;
            }
            res[0] = Math.max(res[0], worst); res[1] += steps; res[2] += unexplained;
            out(String.format(Locale.ROOT, "%s vex %d: %d charging steps, worst one-step error %.2e; %d player hits, %d with no copy touching",
                s.name, e.getKey(), steps, worst, hits.size(), unexplained));
        }
        return res;
    }

    // ------------------------------------------------------------------ breezes

    /** Breezes: the recording's swell column holds its SHOOTING pose. Each wind charge: its launch after the pose began
     *  (copy 15), its flight from its first row (straight, 0.7 a tick), its hit against the player's box, the player's
     *  loss (copy 1), and the burst's push from where it stopped (Blast, radius 3) against the client's velocity after.
     *  Returns {worst flight error, launches off the copy's tick by more than 1, hits the copy got wrong}. */
    static double[] breezes(Scene s) {
        double[] res = new double[3];
        BlockGrid g = flat();
        for (var e : s.mobs.entrySet()) {
            List<M> rows = e.getValue();
            if (!rows.get(0).type().contains("breeze")) continue;
            List<Integer> rises = new ArrayList<>();
            for (int k = 1; k < rows.size(); k++)
                if (rows.get(k).swellDir() == 1 && rows.get(k - 1).swellDir() != 1) rises.add(rows.get(k).t());
            List<List<A>> mine = new ArrayList<>();
            for (List<A> as : s.arrows.values()) if (as.get(0).owner() == e.getKey()) mine.add(as);
            mine.sort((a, b) -> Integer.compare(a.get(0).t(), b.get(0).t()));
            out(String.format(Locale.ROOT, "%s breeze %d: %d SHOOTING poses, %d wind charges", s.name, e.getKey(), rises.size(), mine.size()));
            for (List<A> as : mine) {
                A a0 = as.get(0);
                int rise = -1;
                for (int r : rises) if (r <= a0.t()) rise = r;
                int after = rise < 0 ? -1 : a0.t() - rise;
                if (rise >= 0 && Math.abs(after - 15) > 1) res[1]++;
                ArrowSim sim = new ArrowSim();
                sim.x = a0.x(); sim.y = a0.y(); sim.z = a0.z(); sim.vx = a0.vx(); sim.vy = a0.vy(); sim.vz = a0.vz();
                sim.gravity = 0; sim.drag = 1; sim.gravityFirst = true; sim.fixed = 1.0; sim.burst = 3.0; sim.age = a0.age();
                double err = 0; int hitAt = -1;
                for (int k = 1; k < as.size() && sim.alive; k++) {
                    A a = as.get(k);
                    P p = s.player.get(a.t());
                    if (p == null) break;
                    if (sim.tick(box(p), g) >= 0) { hitAt = a.t(); break; }
                    err = Math.max(err, Math.sqrt(sq(sim.x - a.x()) + sq(sim.y - a.y()) + sq(sim.z - a.z())));
                }
                A last = as.get(as.size() - 1);
                if (hitAt < 0 && sim.alive) {
                    P p = s.player.get(last.t() + 1);
                    if (p != null && sim.tick(box(p), g) >= 0) hitAt = last.t() + 1;
                }
                double lost = 0;
                for (int t = last.t(); t <= last.t() + 2; t++) {
                    P p = s.player.get(t), q = s.player.get(t - 1);
                    if (p != null && q != null && p.health() < q.health() - 0.01) { lost = q.health() - p.health(); break; }
                }
                if ((lost > 0) != (hitAt >= 0)) res[1 + 1]++;
                res[0] = Math.max(res[0], err);
                String push = "";
                P p = s.player.get(last.t() + 1);
                if (p != null && !Double.isNaN(sim.endX) && !sim.alive) {
                    double[] b = Blast.onPlayer(sim.endX, sim.endY, sim.endZ, 3.0, box(p), p.y() + 1.62, g);
                    push = b == null ? "; burst out of reach" : String.format(Locale.ROOT, "; copy's push %.3f %.3f %.3f", b[1], b[2], b[3]);
                    StringBuilder cs = new StringBuilder();
                    C prev = null;
                    for (C c : s.client) {
                        if (c.t() >= last.t() && c.t() <= last.t() + 4 && prev != null)
                            cs.append(String.format(Locale.ROOT, " [t%d v %.3f %.3f %.3f]", c.t(), c.vx(), c.vy(), c.vz()));
                        prev = c;
                    }
                    push += "; client" + cs;
                }
                out(String.format(Locale.ROOT, "  wind charge %d (t%d, %s after the pose; copy 15): flight error %.2e; copy %s; player lost %.1f%s",
                    a0.id(), a0.t(), after < 0 ? "?" : String.valueOf(after), err, hitAt >= 0 ? "hit t" + hitAt : "missed", lost, push));
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ phantoms

    /** Phantoms: the recording holds each tick's attack phase (the swell column, 1 swooping) and its move control's
     *  speed (the swelling column). Every tick it swoops, one step of the copy (thinkPhantom and move) from the row
     *  before, against the next row: position, speed and yaw; the target is the player's middle at the tick or the one
     *  before (the goal sets it every other tick). And the player's full hits against the copy's hit test. Returns
     *  {worst position error, worst speed error, hits with no copy touching, steps, steps off by more than 1e-3}. The
     *  misses (1% in trace7) are the first ticks of a swoop, when the move control still flies to the circle's
     *  point. */
    static double[] phantoms(Scene s) {
        double[] res = new double[5];
        List<Integer> hits = fullHits(s);
        for (var e : s.mobs.entrySet()) {
            List<M> rows = e.getValue();
            if (!rows.get(0).type().contains("phantom")) continue;
            double worst = 0, worstSpeed = 0, worstYaw = 0;
            int steps = 0, swoops = 0;
            for (int k = 1; k < rows.size(); k++) {
                M a = rows.get(k - 1), b = rows.get(k);
                if (b.t() != a.t() + 1) continue;
                if (b.swellDir() == 1 && a.swellDir() == 0) swoops++;
                if (a.swellDir() != 1 || b.swellDir() != 1) continue;
                double best = Double.MAX_VALUE, bestSpeed = 0, bestYaw = 0;
                for (int lagT = 0; lagT <= 1; lagT++) {
                    P p = s.player.get(b.t() - lagT);
                    if (p == null) continue;
                    MobSim m = new MobSim();
                    m.kind = MobSim.Kind.PHANTOM; m.width = 0.9; m.height = 0.5; m.floorY = -1000;
                    m.x = a.x(); m.y = a.y(); m.z = a.z(); m.vx = a.vx(); m.vy = a.vy(); m.vz = a.vz();
                    m.yRot = a.yRot(); m.flySpeed = a.swelling(); m.swoop = true;
                    m.thinkPhantom(p.x(), p.y(), p.z(), new double[]{1e9, 1e9, 1e9, 1e9, 1e9, 1e9});
                    m.move();
                    double err = Math.sqrt(sq(m.x - b.x()) + sq(m.y - b.y()) + sq(m.z - b.z()));
                    if (err < best) { best = err; bestSpeed = Math.abs(m.flySpeed - b.swelling()); bestYaw = Math.abs(ai.rrw.kine.move.GameMath.wrapDegrees(m.yRot - b.yRot())); }
                }
                if (best == Double.MAX_VALUE) continue;
                steps++;
                if (best > 1e-3) res[4]++;
                worst = Math.max(worst, best); worstSpeed = Math.max(worstSpeed, bestSpeed); worstYaw = Math.max(worstYaw, bestYaw);
            }
            // hits: each full hit of the player against the copy's test on the real positions (this tick or the one before)
            int unexplained = 0;
            Map<Integer, M> at = new HashMap<>();
            for (M r : rows) at.put(r.t(), r);
            for (int h : hits) {
                boolean any = false;
                for (int lagT = 0; lagT <= 1 && !any; lagT++) {
                    M r = at.get(h - lagT);
                    P p = s.player.get(h - lagT);
                    if (r == null || p == null) continue;
                    double[] pb = box(p);
                    double hw = 0.45 + 0.2;
                    any = r.x() - hw < pb[3] && r.x() + hw > pb[0] && r.y() - 0.2 < pb[4] && r.y() + 0.5 + 0.2 > pb[1] && r.z() - hw < pb[5] && r.z() + hw > pb[2];
                }
                if (!any) unexplained++;
            }
            res[0] = Math.max(res[0], worst); res[1] = Math.max(res[1], worstSpeed); res[2] += unexplained; res[3] += steps;
            out(String.format(Locale.ROOT, "%s phantom %d: %d swoops, %d swooping steps: worst position error %.2e, speed %.2e, yaw %.2f; %d player hits, %d with no copy touching",
                s.name, e.getKey(), swoops, steps, worst, worstSpeed, worstYaw, hits.size(), unexplained));
        }
        return res;
    }

    // ------------------------------------------------------------------ blazes

    /** Blazes: the charged flag (the trace's swell column) and the fireballs against the copy's 60-tick charge, 6
     *  ticks between fireballs and 178-tick cycle; each fireball's start and flight replayed (AbstractHurtingProjectile)
     *  against the player's recorded positions, its hit and the damage the player took. Returns {worst flight error,
     *  worst start error, hits the copy got wrong}. */
    static double[] blazes(Scene s) {
        double[] res = new double[4];
        BlockGrid g = flat();
        for (var e : s.mobs.entrySet()) {
            List<M> rows = e.getValue();
            Map<Integer, M> at = new HashMap<>();
            for (M r : rows) at.put(r.t(), r);
            List<Integer> rises = new ArrayList<>(), falls = new ArrayList<>(), shots = new ArrayList<>();
            for (int k = 1; k < rows.size(); k++) {
                if (rows.get(k).swellDir() == 1 && rows.get(k - 1).swellDir() == 0) rises.add(rows.get(k).t());
                if (rows.get(k).swellDir() == 0 && rows.get(k - 1).swellDir() == 1) falls.add(rows.get(k).t());
            }
            List<List<A>> balls = new ArrayList<>();
            for (List<A> as : s.arrows.values()) if (as.get(0).owner() == e.getKey()) { balls.add(as); shots.add(as.get(0).t()); }
            balls.sort((a, b) -> Integer.compare(a.get(0).t(), b.get(0).t()));
            shots.sort(Integer::compare);
            StringBuilder sb = new StringBuilder();
            for (int rise : rises) {
                sb.append(" [charge t").append(rise);
                List<Integer> rel = new ArrayList<>();
                for (int sh : shots) if (sh >= rise && sh < rise + 100) { sb.append(" +").append(sh - rise); rel.add(sh - rise); }
                boolean whole = rows.get(rows.size() - 1).t() >= rise + 80;   // the recording saw the whole volley
                if (whole && !rel.equals(List.of(60, 66, 72))) res[3]++;
                for (int f : falls) if (f > rise && f < rise + 100) { sb.append(" off +").append(f - rise); break; }
                sb.append(']');
            }
            for (int k = 1; k < rises.size(); k++) sb.append(" cycle ").append(rises.get(k) - rises.get(k - 1));
            out(String.format(Locale.ROOT, "%s blaze %d: %d charges, %d fireballs (copy: first +60, then +66 +72, off +78, cycle 178):%s",
                s.name, e.getKey(), rises.size(), shots.size(), sb));
            for (List<A> as : balls) {
                A a0 = as.get(0);
                // the start: from the blaze's row before (its goal runs before its move), at its middle and 0.5 up, the
                // real direction at speed 0.1, then one tick of flight if it flies the tick it is made
                M b = at.get(a0.t() - 1);
                double startErr = Double.NaN, startErr0 = Double.NaN;
                if (b != null) {
                    double l = Math.sqrt(a0.vx() * a0.vx() + a0.vy() * a0.vy() + a0.vz() * a0.vz());
                    ArrowSim st = new ArrowSim();
                    st.x = b.x(); st.y = b.y() + 0.9 + 0.5; st.z = b.z();
                    st.vx = a0.vx() / l * 0.1; st.vy = a0.vy() / l * 0.1; st.vz = a0.vz() / l * 0.1;
                    st.gravity = 0; st.drag = 1; st.accel = 0.1;
                    startErr0 = Math.sqrt(sq(st.x - a0.x()) + sq(st.y - a0.y()) + sq(st.z - a0.z()));
                    st.inertia();
                    startErr = Math.sqrt(sq(st.x + st.vx - a0.x()) + sq(st.y + st.vy - a0.y()) + sq(st.z + st.vz - a0.z()));
                    res[1] = Math.max(res[1], startErr0);
                }
                // aim: the real direction against straight at the player's middle from the start
                P p0 = s.player.get(a0.t());
                double spread = Double.NaN;
                if (b != null && p0 != null) {
                    double xd = p0.x() - b.x(), yd = p0.y() + 0.9 - (b.y() + 0.9), zd = p0.z() - b.z();
                    double l1 = Math.sqrt(xd * xd + yd * yd + zd * zd), l2 = Math.sqrt(a0.vx() * a0.vx() + a0.vy() * a0.vy() + a0.vz() * a0.vz());
                    spread = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (xd * a0.vx() + yd * a0.vy() + zd * a0.vz()) / l1 / l2))));
                }
                ArrowSim sim = new ArrowSim();
                sim.x = a0.x(); sim.y = a0.y(); sim.z = a0.z(); sim.vx = a0.vx(); sim.vy = a0.vy(); sim.vz = a0.vz();
                sim.gravity = 0; sim.drag = 1; sim.accel = 0.1; sim.fixed = 5; sim.age = a0.age();
                double err = 0; int hitAt = -1;
                for (int k = 1; k < as.size() && sim.alive; k++) {
                    A a = as.get(k);
                    P p = s.player.get(a.t());
                    if (p == null || a.age() == as.get(k - 1).age()) break;   // out of the ticking area: it stops
                    double dmg = sim.tick(box(p), g);
                    if (dmg >= 0) { hitAt = a.t(); break; }
                    err = Math.max(err, Math.sqrt(sq(sim.x - a.x()) + sq(sim.y - a.y()) + sq(sim.z - a.z())));
                }
                A last = as.get(as.size() - 1);
                if (hitAt < 0 && sim.alive) {
                    P p = s.player.get(last.t() + 1);
                    if (p != null && sim.tick(box(p), g) >= 0) hitAt = last.t() + 1;
                }
                double lost = 0;
                int lostAt = -1;
                for (int t = last.t(); t <= last.t() + 2; t++) {
                    P p = s.player.get(t), q = s.player.get(t - 1);
                    if (p != null && q != null && p.health() < q.health() - 0.01) { lost = q.health() - p.health(); lostAt = t; break; }
                }
                boolean realHit = lost > 0, copyHit = hitAt >= 0;
                P q = hitAt >= 0 ? s.player.get(hitAt - 1) : null;
                boolean window = q != null && q.invul() > 10;   // a hit inside the hurt window takes nothing
                if (realHit != copyHit && !(copyHit && window)) res[2]++;
                res[0] = Math.max(res[0], err);
                out(String.format(Locale.ROOT, "  fireball %d (t%d): start error %.2e (flown at once) / %.2e (not); off straight aim by %.1f degrees; flight error %.2e; copy %s%s, real: lost %.1f %s",
                    a0.id(), a0.t(), startErr, startErr0, spread, err, copyHit ? "hit t" + hitAt : "missed", copyHit && window ? " (in the hurt window)" : "", lost, lostAt < 0 ? "" : "at t" + lostAt));
            }
        }
        return res;
    }

    static double sq(double v) { return v * v; }

    public static void main(String[] args) throws IOException {
        Map<String, Scene> sc;
        try (InputStream in = Files.newInputStream(Path.of(args[0]))) { sc = read(in); }
        for (Scene s : sc.values()) {
            String n = s.name;
            if (n.startsWith("sk_") || n.startsWith("stray")) bow(s, 40);
            else if (n.startsWith("bogged") || n.startsWith("parched")) bow(s, 70);
            else if (n.startsWith("wsk")) walker(s, "wither_skeleton");
            else if (n.startsWith("cr_")) creeper(s);
            else if (n.startsWith("sp_")) walker(s, "spider");
            else if (n.startsWith("csp")) walker(s, "cave_spider");
            else if (n.startsWith("baby")) walker(s, "zombie_baby");
            else if (n.startsWith("drowned_tri")) tridents(s);
            else if (n.startsWith("husk") || n.startsWith("zv") || n.startsWith("drowned")) walker(s, "zombie");
            else if (n.startsWith("pill")) crossbow(s);
            else if (n.startsWith("witch")) witch(s);
            else if (n.startsWith("sl") || n.startsWith("mc")) slimes(s);
            else if (n.startsWith("bl_")) blazes(s);
            else if (n.startsWith("ph_")) phantoms(s);
            else if (n.startsWith("br_")) breezes(s);
            else if (n.startsWith("ev_")) { evokers(s); vexes(s); }
            else if (n.startsWith("vex_")) vexes(s);
            else if (n.endsWith("_still")) walker(s, n.substring(0, n.length() - "_still".length()));
        }
    }
}
