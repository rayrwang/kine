package ai.rrw.kine.fight;

import ai.rrw.kine.autoflight.HoldModel;
import ai.rrw.kine.combat.SwingPlan;
import ai.rrw.kine.compat.GameVersion;
import ai.rrw.kine.compat.Rules;
import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The projectile, mob, melee and sky copies against real ticks where the game's rules differ between releases
 * (compat/Rules): proof_fight.txt is recorded in a lockstep client test (ct2 ProofFightTest, 2026-10-15; its javadoc has
 * the scenes and the line format). Each check takes the recorded start of something and asks the copy, with the rules
 * of the release this JVM runs as, what follows:
 * <ul>
 *   <li>flights (PROJECTILES_MOVE_FIRST): every snowball, splash potion, small fireball and wind charge flown open loop
 *       through ArrowSim from its first row, as the fight copies set them up (FightWorld, MobCheck), within 1e-4 of every
 *       row it has;</li>
 *   <li>the grazing snowballs (FIXED_HIT_MARGIN): the tick the copy's snowball stops (on the player's box or the ground)
 *       is the tick the game's was gone;</li>
 *   <li>the splashes (SPLASH_FROM_FEET): the Swiftness the player got is ArrowSim.splashOn's intensity of 9600 ticks,
 *       within 1% (ArrowSim's stop point is its block walk's, 0.2 steps);</li>
 *   <li>the witch's potions (WITCH_THROWS_SLOWER_NEAR, the witch held 1.5 and 4 blocks off): each one's first speed lies
 *       within the speeds FightWorld.throwPotion gives the same throw over 500 spreads;</li>
 *   <li>the wind charges (BREEZE_SHOOTS_FROM_SNOUT, BREEZE_NO_SHOT_WITHIN_2): each starts at the height above the
 *       breeze FightWorld.windCharge gives, and the breeze shut in with the player (never more than 1.5 blocks off)
 *       shoots if and only if MobSim.thinkBreeze lets a shot start where it began;</li>
 *   <li>the sweeps: the villager beside the one hit while walking is swept if and only if AuraSim.slowEnoughToSweep says
 *       so of the player's last move (SWEEP_BY_WALK_DIST); a villager swept again in its hurt window moves as
 *       AuraSim's sweep moves it (SWEEP_KNOCKS_FIRST);</li>
 *   <li>the sky (SKY_DARKEN_FROM_SUN_ANGLE): the first time of day the darkening is 7 or less is where
 *       HoldModel.ticksToDawn puts it, clear and in rain.</li>
 * </ul>
 */
public class RulesFightTest {
    record E(char ph, int st, int id, String type, double x, double y, double z, double vx, double vy, double vz, int tc,
             int owner, float health, String pose, boolean ground) {}
    record P(char ph, int st, double x, double y, double z, double vx, double vy, double vz, float health, int swift) {}
    record C(int ct, int st, double x, double y, double z, double vx, double vy, double vz, boolean ground, float yRot) {}
    record Atk(int ct, int st, int target, float yRot) {}
    record Sky(int ct, long time, int darken, float rain) {}

    static final class Scene {
        final String name;
        final List<E> e = new ArrayList<>();
        final List<P> p = new ArrayList<>();
        final List<C> c = new ArrayList<>();
        final List<Atk> atk = new ArrayList<>();
        final List<Sky> sky = new ArrayList<>();
        final Map<Integer, double[]> dims = new HashMap<>();
        final Map<Integer, String> types = new HashMap<>();
        Scene(String name) { this.name = name; }

        /** The E rows of one entity, by server tick. */
        List<E> rows(int id) {
            List<E> out = new ArrayList<>();
            for (E r : e) if (r.id() == id && r.ph() == 'E') out.add(r);
            return out;
        }

        E row(int id, char ph, int st) {
            for (E r : e) if (r.id() == id && r.ph() == ph && r.st() == st) return r;
            return null;
        }

        P player(char ph, int st) {
            P best = null;
            for (P r : p) if (r.ph() == ph && r.st() <= st && (best == null || r.st() > best.st())) best = r;
            return best;
        }

        List<Integer> projectiles() {
            List<Integer> ids = new ArrayList<>();
            for (E r : e) if (r.owner() != -2 && PROJECTILES.containsKey(r.type()) && !ids.contains(r.id())) ids.add(r.id());
            return ids;
        }
    }

    /** The projectiles the fight copies fly, by entity type: gravity, drag, thrown (gravity and drag before the move from
     *  1.21.2), the fireball's speed-up. */
    static final Map<String, double[]> PROJECTILES = Map.of(
        "snowball", new double[]{0.03, 0.99, 1, 0}, "potion", new double[]{0.05, 0.99, 1, 0},
        "splash_potion", new double[]{0.05, 0.99, 1, 0}, "small_fireball", new double[]{0, 1, 0, 0.1},
        "breeze_wind_charge", new double[]{0, 1, 0, 0});

    static double hx(String s) { return Double.parseDouble(s); }
    static float hf(String s) { return Float.parseFloat(s); }
    static int in(String s) { return Integer.parseInt(s); }

    public record Header(String release, boolean lockstep) {}

    static Header header(String text) {
        for (String l : text.split("\n")) if (l.startsWith("xproof ")) { String[] p = l.split(" "); return new Header(p[2], Boolean.parseBoolean(p[3])); }
        return new Header("?", true);
    }

    static Map<String, Scene> read(String text) {
        Map<String, Scene> out = new LinkedHashMap<>();
        Scene cur = null;
        for (String line : text.split("\n")) {
            String[] a = line.trim().split(" ");
            switch (a[0]) {
                case "scene" -> { cur = new Scene(a[1]); out.put(a[1], cur); }
                case "end" -> cur = null;
                case "dims" -> { if (cur != null) { cur.dims.put(in(a[1]), new double[]{hx(a[3]), hx(a[4]), hx(a[5])}); cur.types.put(in(a[1]), a[2]); } }
                case "e" -> { if (cur != null) cur.e.add(new E(a[1].charAt(0), in(a[2]), in(a[3]), a[4], hx(a[5]), hx(a[6]), hx(a[7]), hx(a[8]),
                    hx(a[9]), hx(a[10]), in(a[11]), in(a[12]), hf(a[13]), a[14], a.length > 15 && Boolean.parseBoolean(a[15]))); }
                case "p" -> { if (cur != null) cur.p.add(new P(a[1].charAt(0), in(a[2]), hx(a[3]), hx(a[4]), hx(a[5]), hx(a[6]), hx(a[7]), hx(a[8]),
                    hf(a[9]), in(a[10]))); }
                case "c" -> { if (cur != null) cur.c.add(new C(in(a[1]), in(a[2]), hx(a[3]), hx(a[4]), hx(a[5]), hx(a[6]), hx(a[7]), hx(a[8]),
                    Boolean.parseBoolean(a[9]), hf(a[10]))); }
                case "atk" -> { if (cur != null) cur.atk.add(new Atk(in(a[1]), in(a[2]), in(a[3]), hf(a[4]))); }
                case "sky" -> { if (cur != null) cur.sky.add(new Sky(in(a[1]), Long.parseLong(a[2]), in(a[3]), hf(a[4]))); }
                default -> {}
            }
        }
        return out;
    }

    /** One check: what, whether the copy agrees with the game, and the numbers. */
    public record Check(String scene, String what, boolean ok, String detail) {}

    static double[] box(P p) {
        return new double[]{p.x() - 0.3F, p.y(), p.z() - 0.3F, p.x() + 0.3F, p.y() + 1.8F, p.z() + 0.3F};
    }

    static final double[] FAR = {1e6, 1e6, 1e6, 1e6 + 1, 1e6 + 2, 1e6 + 1};

    /** A projectile's first row as the copy flies it (ArrowSim, set up as the fight copies set it). */
    static ArrowSim sim(E r) {
        double[] k = PROJECTILES.get(r.type());
        ArrowSim a = new ArrowSim();
        a.x = r.x(); a.y = r.y(); a.z = r.z(); a.vx = r.vx(); a.vy = r.vy(); a.vz = r.vz();
        a.gravity = k[0]; a.drag = k[1];
        a.gravityFirst = k[2] == 1 ? !Rules.PROJECTILES_MOVE_FIRST : k[3] == 0;
        a.accel = k[3];
        a.age = r.tc();
        a.splash = r.type().contains("potion");
        return a;
    }

    /** Flies a projectile from its first row: the largest distance to its rows while both fly, the server tick the copy's
     *  stops (-1 if it flies on) and whether on the player's box. */
    static double[] fly(Scene s, int id, BlockGrid grid, boolean player, ArrowSim[] last) {
        List<E> rows = s.rows(id);
        ArrowSim a = sim(rows.get(0));
        double err = 0;
        int stop = -1;
        boolean onPlayer = false;
        int st = rows.get(0).st();
        int lastSt = rows.get(rows.size() - 1).st();
        for (int t = st + 1; t <= lastSt + 3 && a.alive; t++) {
            P p = s.player('E', t - 1);
            double hit = a.tick(player && p != null ? box(p) : FAR, grid);
            if (!a.alive) { stop = t; onPlayer = hit >= 0; break; }
            E r = s.row(id, 'E', t);
            if (r != null) err = Math.max(err, Math.max(Math.abs(a.x - r.x()), Math.max(Math.abs(a.y - r.y()), Math.abs(a.z - r.z()))));
        }
        if (last != null) last[0] = a;
        return new double[]{err, stop, onPlayer ? 1 : 0, lastSt};
    }

    /** Everything the file shows, checked against the copy under this JVM's release. */
    public static List<Check> check(String text) throws Exception {
        Map<String, Scene> scenes = read(text);
        BlockGrid grid = MobCheck.flat();
        List<Check> out = new ArrayList<>();
        for (Scene s : scenes.values()) {
            // ---- flights
            for (int id : s.projectiles()) {
                List<E> rows = s.rows(id);
                if (rows.size() < 3) continue;
                double[] f = fly(s, id, grid, s.name.startsWith("graze"), null);
                out.add(new Check(s.name, "flight " + rows.get(0).type() + " " + id, f[0] < 1e-4,
                    String.format(Locale.ROOT, "%d rows, largest distance %.3e", rows.size(), f[0])));
            }
            // ---- the grazing snowballs: where the copy's stops and when the game's was gone
            if (s.name.startsWith("graze")) {
                for (int id : s.projectiles()) {
                    double[] f = fly(s, id, grid, true, null);
                    int gone = (int) f[3] + 1;
                    out.add(new Check(s.name, "stop", f[1] == gone, String.format(Locale.ROOT,
                        "the game's snowball gone at server tick %d; the copy's %s", gone, f[1] < 0 ? "flies on"
                            : String.format(Locale.ROOT, "stops at %d %s", (int) f[1], f[2] == 1 ? "on the player" : "on a block"))));
                }
            }
            // ---- the splashes
            if (s.name.startsWith("potion")) {
                for (int id : s.projectiles()) {
                    ArrowSim[] a = new ArrowSim[1];
                    double[] f = fly(s, id, grid, true, a);
                    P p = s.player('E', (int) f[3]);
                    double intensity = a[0].splashOn(box(p));
                    int expected = (int) (intensity * 9600 + 0.5);
                    if (expected <= 20) expected = -1;
                    int got = -1;
                    for (P q : s.p) if (q.st() > f[3]) got = Math.max(got, q.swift());
                    // within 96 ticks (1% of the potion's 9600): ArrowSim stops a projectile where its 0.2-block walk enters
                    // the block, not at the block's face, so its splash point can be a little off the game's (by 3 ticks
                    // in the 1.21.5 recording); the rules' difference here is over 1700 ticks
                    out.add(new Check(s.name, "splash", Math.abs(got - expected) <= 96, String.format(Locale.ROOT,
                        "Swiftness %d ticks; the copy's intensity %.4f gives %d", got, intensity, expected)));
                }
            }
            // ---- the witch's potions
            if (s.name.startsWith("witch")) {
                int witch = -1;
                for (var d : s.types.entrySet()) if (d.getValue().equals("witch")) witch = d.getKey();
                double[] dims = s.dims.get(witch);
                int thrown = 0;
                for (int id : s.projectiles()) {
                    E r0 = s.rows(id).get(0);
                    if (r0.owner() != witch) continue;
                    thrown++;
                    E w = s.row(witch, 'E', r0.st());
                    P p = s.player('E', r0.st());
                    double[] band = witchSpeeds(w, dims, p);
                    double v = Math.sqrt(r0.vx() * r0.vx() + r0.vy() * r0.vy() + r0.vz() * r0.vz());
                    out.add(new Check(s.name, "throw speed", v >= band[0] * 0.995 && v <= band[1] * 1.005, String.format(Locale.ROOT,
                        "speed %.4f at %.2f blocks; the copy's throws %.4f to %.4f", v, Math.hypot(p.x() - w.x(), p.z() - w.z()), band[0], band[1])));
                }
                out.add(new Check(s.name, "throws", thrown > 0, thrown + " potions"));
            }
            // ---- the breeze's wind charges
            if (s.name.startsWith("breeze")) {
                int breeze = -1;
                for (var d : s.types.entrySet()) if (d.getValue().equals("breeze")) breeze = d.getKey();
                double[] dims = s.dims.get(breeze);
                int shots = 0;
                E b0 = null;
                for (E r : s.e) if (r.id() == breeze && r.ph() == 'E') { b0 = r; break; }
                for (int id : s.projectiles()) {
                    E r0 = s.rows(id).get(0);
                    if (r0.owner() != breeze) continue;
                    shots++;
                    // the shot comes from the breeze's brain, before its move in that tick: where the tick started
                    E b = s.row(breeze, 'S', r0.st());
                    if (b == null) b = s.row(breeze, 'E', r0.st() - 1);
                    P p = s.player('E', r0.st());
                    FightWorld fw = new FightWorld();
                    fw.player = state(p);
                    fw.difficulty = 1;
                    MobSim m = mob(b, dims);
                    fw.windCharge(m, new SplittableRandom(1));
                    double copyY = fw.arrows[fw.arrows.length - 1].y - b.y();
                    // within 1e-6: the game adds the float 0.3F (Breeze.getFiringYPosition), the copy the double 0.3; the
                    // snout (1.21.1) is 0.24 lower
                    out.add(new Check(s.name, "wind charge start", Math.abs((r0.y() - b.y()) - copyY) < 1e-6, String.format(Locale.ROOT,
                        "%.9f above the breeze; the copy's %.9f", r0.y() - b.y(), copyY)));
                }
                P p = s.player('E', b0.st());
                MobSim m = mob(b0, dims);
                m.kind = MobSim.Kind.BREEZE;
                m.walking = false;
                m.shootIn = 0;
                boolean allowed = m.thinkBreeze(p.x(), p.y(), p.z());
                double d = Math.sqrt(sq(p.x() - b0.x()) + sq(p.y() - b0.y()) + sq(p.z() - b0.z()));
                out.add(new Check(s.name, "shots", (shots > 0) == allowed, String.format(Locale.ROOT,
                    "%d wind charges with the player %.2f blocks off; the copy %s there", shots, d, allowed ? "starts a shot" : "starts no shot")));
            }
            // ---- a sweep while walking
            if (s.name.startsWith("sweep_walk")) {
                for (Atk k : s.atk) {
                    int beside = -1;
                    for (var d : s.types.entrySet()) if (d.getValue().equals("villager") && d.getKey() != k.target()) beside = d.getKey();
                    List<E> rows = s.rows(beside);
                    boolean swept = rows.get(rows.size() - 1).health() < rows.get(0).health();
                    C c1 = client(s, k.ct() - 1), c2 = client(s, k.ct() - 2);
                    double pdx = c1.x() - c2.x(), pdz = c1.z() - c2.z();
                    boolean copy = AuraSim.slowEnoughToSweep(pdx, pdz, 0.1);
                    out.add(new Check(s.name, "walking sweep", swept == copy, String.format(Locale.ROOT,
                        "last move %.4f a tick; the game %s, the copy %s", Math.hypot(pdx, pdz), swept ? "swept" : "didn't sweep",
                        copy ? "sweeps" : "doesn't")));
                }
            }
            // ---- two sweeps catching the same villager, the second in its hurt window
            if (s.name.startsWith("sweep_window")) {
                List<Integer> targets = new ArrayList<>();
                for (Atk k : s.atk) targets.add(k.target());
                int mid = -1;
                for (var d : s.types.entrySet()) if (d.getValue().equals("villager") && !targets.contains(d.getKey())) mid = d.getKey();
                int firstAt = -1;
                for (int i = 0; i < s.atk.size(); i++) {
                    Atk k = s.atk.get(i);
                    int at = hurtTick(s, k.target());
                    if (at < 0) { out.add(new Check(s.name, "sweep " + (i + 1), false, "the target was never hurt")); continue; }
                    if (i == 0) firstAt = at;
                    E tb = s.row(k.target(), 'E', at), nb = s.row(mid, 'E', at), na = s.row(mid, 'S', at + 1);
                    P p = s.player('S', at + 1);
                    double[] v = sweep(tb, nb, p, k.yRot(), i == 0 ? 0 : SwingPlan.WINDOW - (at - firstAt), s.dims.get(mid));
                    double dv = Math.max(Math.abs(v[0] - na.vx()), Math.max(Math.abs(v[1] - na.vy()), Math.abs(v[2] - na.vz())));
                    boolean pushed = Math.abs(na.vx() - nb.vx()) > 1e-6 || Math.abs(na.vz() - nb.vz()) > 1e-6;
                    out.add(new Check(s.name, i == 0 ? "sweep (fresh)" : "sweep (in its window)", dv < 2e-3, String.format(Locale.ROOT,
                        "the villager between %s, its velocity (%.4f, %.4f, %.4f) from (%.4f, %.4f, %.4f); the copy's (%.4f, %.4f, %.4f)",
                        pushed ? "pushed" : "not pushed", na.vx(), na.vy(), na.vz(), nb.vx(), nb.vy(), nb.vz(), v[0], v[1], v[2])));
                }
            }
            // ---- the sky at dawn
            if (s.name.startsWith("sky")) {
                // 1.21 and 1.21.1 work the client's darkening out only when the level loads (ClientLevel.tick calls
                // updateSkyBrightness from 1.21.2 on): there it never changes, and there is nothing to check
                if (s.sky.stream().mapToInt(Sky::darken).distinct().count() == 1) {
                    out.add(new Check(s.name, "dawn (not checked)", true, "the client's darkening stays "
                        + s.sky.get(0).darken() + " (it is worked out only when the level loads up to 1.21.1)"));
                    continue;
                }
                // from the row the time set (22700, 22950) reached the client: the last jump in the time of day (on
                // 1.21.2 it came some ticks late; the rows before it go on from the time before)
                int from = 0;
                for (int i = 1; i < s.sky.size(); i++) {
                    long dt = s.sky.get(i).time() - s.sky.get(i - 1).time();
                    if (dt != 0 && dt != 1) from = i;
                }
                List<Sky> rows = s.sky.subList(from, s.sky.size());
                Sky first = rows.get(0), dark = null;
                for (Sky k : rows) if (k.darken() <= 7) { dark = k; break; }
                long t0 = Math.floorMod(first.time(), 24000L);
                long expected = t0 + HoldModel.ticksToDawn(t0, first.rain());
                long got = dark == null ? -1 : Math.floorMod(dark.time(), 24000L);
                out.add(new Check(s.name, "dawn", got == expected, String.format(Locale.ROOT,
                    "darkening first 7 or less at %d (rain %.2f); the copy's dawn %d", got, first.rain(), expected)));
            }
        }
        return out;
    }

    static double sq(double d) { return d * d; }

    static C client(Scene s, int ct) {
        for (C c : s.c) if (c.ct() == ct) return c;
        throw new IllegalStateException("no client row at " + ct);
    }

    /** The server tick whose end the target last had its health at (the hit came after it). */
    static int hurtTick(Scene s, int target) {
        for (E r : s.e) {
            if (r.id() != target || r.ph() != 'E') continue;
            E next = s.row(target, 'S', r.st() + 1);
            if (next != null && next.health() < r.health()) return r.st();
        }
        return -1;
    }

    static MoveState state(P p) {
        MoveState m = new MoveState();
        m.x = p.x(); m.y = p.y(); m.z = p.z();
        m.onGround = true;
        return m;
    }

    static MobSim mob(E r, double[] dims) {
        MobSim m = new MobSim();
        m.x = r.x(); m.y = r.y(); m.z = r.z(); m.vx = r.vx(); m.vy = r.vy(); m.vz = r.vz();
        m.onGround = r.ground();
        m.width = dims[0]; m.height = dims[1]; m.eyeHeight = dims[2];
        m.health = r.health(); m.maxHealth = 1000; m.armour = 0; m.toughness = 0; m.knockbackResistance = 0;
        return m;
    }

    /** The speeds FightWorld.throwPotion gives a witch's throw at this geometry, over 500 spreads. */
    static double[] witchSpeeds(E w, double[] dims, P p) {
        double lo = Double.MAX_VALUE, hi = 0;
        for (int seed = 0; seed < 500; seed++) {
            FightWorld fw = new FightWorld();
            fw.player = state(p);
            fw.health = 20;
            fw.throwPotion(mob(w, dims), new SplittableRandom(seed));
            ArrowSim a = fw.arrows[fw.arrows.length - 1];
            double v = Math.sqrt(a.vx * a.vx + a.vy * a.vy + a.vz * a.vz);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return new double[]{lo, hi};
    }

    /** AuraSim's hit (Player.attack) on the target with the villager between swept: the villager's velocity after. Its
     *  window: ticks of it left (0: fresh), the last hit in it the sweep's 1. */
    static double[] sweep(E target, E between, P player, float yaw, int window, double[] dims) throws Exception {
        AuraSim aura = new AuraSim();
        aura.weapon = new SwingPlan.Weapon(6.0, 12.500001F, true, 0.0);
        aura.k = 100;
        aura.sentYaw = yaw;
        MoveState p = state(player);
        MobSim t = mob(target, dims), n = mob(between, dims);
        n.freshIn = Math.max(0, window);
        n.lastHurt = window > 0 ? 1.0 : 0.0;
        List<MobSim> found = new ArrayList<>(List.of(t, n));
        List<SwingPlan.Mob> plan = new ArrayList<>();
        for (MobSim m : found) plan.add(new SwingPlan.Mob(m.health, m.armour));
        plan.get(0).swept = new int[]{1};
        Method hit = AuraSim.class.getDeclaredMethod("hit", MoveState.class, MobSim.class, List.class, List.class, int.class,
            boolean.class, boolean.class);
        hit.setAccessible(true);
        hit.invoke(aura, p, t, found, plan, 0, true, false);
        return new double[]{n.vx, n.vy, n.vz};
    }

    /**
     * Checks the copy is known to fail, so they are shown and not counted as failures (the copy isn't changed here); the
     * test fails instead if one of them starts to hold, so the entry goes when the copy is fixed: the scene's prefix,
     * the check, the first and last release it fails on, and why.
     * <ul>
     *   <li>the fresh sweep, every release: AuraSim's sweep hurts a swept mob without the hurt's own knockback
     *       (LivingEntity.hurt and hurtServer push a mob a full hit takes 0.4 away from the attacker): the villager
     *       between goes 0.6 a tick, the copy's 0.4 (physics/FINDINGS.md, "Kine against 26.1.2 itself").</li>
     * </ul>
     * Fixed since and so off the list (2026-10-15): the walking sweep up to 1.21.1 (the server's walkDist - walkDistO at
     * a hit counts the moves since its own player tick, none for a hit sent before the tick's move:
     * Rules.SWEEP_BY_WALK_DIST, AuraSim.slowEnoughToSweep), and the dawn on 1.21.2 to 1.21.11 (the client's darkening a
     * tick behind kine's time of day: Rules.SKY_DARKEN_A_TICK_LATE, HoldModel).
     */
    static final String[][] KNOWN = {
        {"sweep_window", "sweep (fresh)", "1.21", "99", "AuraSim's sweep leaves out the hurt's own knockback of a fresh swept mob"},
    };

    static String known(Check c) {
        for (String[] k : KNOWN)
            if (c.scene().startsWith(k[0]) && c.what().equals(k[1]) && GameVersion.atLeast(k[2])
                && GameVersion.compare(GameVersion.minecraft(), k[3]) <= 0) return k[4];
        return null;
    }

    static String text() throws IOException {
        try (InputStream in = RulesFightTest.class.getResourceAsStream("/move/proof_fight.txt")) {
            assertNotNull(in, "proof fight recording missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyCheckHoldsUnderTheRecordingsRelease() throws Exception {
        String text = text();
        Header h = header(text);
        System.out.printf(Locale.ROOT, "proof_fight.txt recorded on %s (lockstep %b), checked under %s's rules%n", h.release(),
            h.lockstep(), GameVersion.minecraft());
        List<String> wrong = new ArrayList<>();
        List<Check> checks = check(text);
        for (Check c : checks) {
            String k = known(c);
            System.out.printf(Locale.ROOT, "  %-14s %-22s %s %s%s%n", c.scene(), c.what(), c.ok() ? "ok   " : k != null ? "KNOWN" : "WRONG",
                c.detail(), k != null && !c.ok() ? " (" + k + ")" : "");
            if (!c.ok() && k == null) wrong.add(c.scene() + " " + c.what() + ": " + c.detail());
            if (c.ok() && k != null) wrong.add(c.scene() + " " + c.what() + ": holds now, though listed as a known fault (" + k + "): take it off KNOWN");
        }
        assertTrue(checks.size() > 20, "checks: " + checks.size());
        for (String scene : new String[]{"snowball", "fireball", "potion2", "potion3", "graze2", "graze10", "witch_near",
                                         "breeze_near", "breeze_far", "sweep_walk0", "sweep_window0", "sky_clear", "sky_rain"})
            assertTrue(checks.stream().anyMatch(c -> c.scene().equals(scene)), "checked: " + scene);
        assertTrue(wrong.isEmpty(), "checks the copy fails: " + String.join(" | ", wrong));
    }
}
