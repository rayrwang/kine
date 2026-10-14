package ai.rrw.kine.fight;

import ai.rrw.kine.combat.Ballistics;
import ai.rrw.kine.combat.BowTargets;
import ai.rrw.kine.combat.HitChance;
import ai.rrw.kine.combat.SwingPlan;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * The bow aura's target-choice study in the model world (FightWorld: the mob models checked against real ticks, and the
 * kill aura's model), many fights per policy, as a copy simulation beside the lockstep tests. The player stands still
 * with a netherite sword (the kill aura's model swings at what comes into reach) and a bow; the bow side is this
 * bench's own copy of BowAura's rules: it stands down while a mob is in reach or could walk into reach before the shot
 * is off (KillAura.meleeTicks: its closing speed, at least half its speed attribute a tick for a melee mob), draws 20
 * ticks, looses when the shot's chance is at least 0.5 (0.3 after 20 ticks ready), then waits 3 ticks (the release and
 * the server's word); the target from BowTargets' policy every 4 ticks, the chance from HitChance (256 rolls, the
 * ground a block). The arrow is flown tick by tick in the world (the game's spread roll, drag and gravity, the hit
 * margin by age) against the mobs as they move, and a hit does ceil(speed x 2) plus the crit, through the mob's armour
 * and hurt window, with 0.4 of knockback along the arrow. Left out: lag, the turn (the shot leaves along the solve).
 *
 * <p>Run: java -cp (test runtime classpath) ai.rrw.kine.fight.BowStudy [seeds] [scenes...]; prints per scene and policy the
 * mean health lost, ticks to kill all (cap 1200), shots, and fights not ended.
 */
public final class BowStudy {
    static final String[] POLICIES = System.getProperty("policies") != null ? System.getProperty("policies").split(",")
        : new String[]{"NONE", "MELEE", "NEAREST", "RANGED_FIRST", "LEAST_HEALTH", "MOST_DAMAGE", "THREAT", "RANGED_THEN_THREAT", "SHARED_THREAT"};

    // ---------------------------------------------------------------- mobs

    static MobSim zombie(double x, double z) {
        MobSim m = new MobSim();
        m.kind = MobSim.Kind.MELEE; m.speed = 0.23; m.attackDamage = 3.0; m.health = 20; m.maxHealth = 20; m.armour = 2;
        m.follows = true;
        place(m, x, z);
        return m;
    }

    static MobSim skeleton(double x, double z) {
        MobSim m = new MobSim();
        m.kind = MobSim.Kind.BOW; m.speed = 0.25; m.health = 20; m.maxHealth = 20; m.armour = 0; m.height = 1.99;
        m.bowInterval = 40; m.arrowBase = 2.0 + 2 * 0.11 + 0.57425; m.seeTime = 20; m.strafeTime = 0;
        m.drawTicks = -1; m.attackTime = 20;
        place(m, x, z);
        return m;
    }

    static MobSim creeper(double x, double z) {
        MobSim m = new MobSim();
        m.kind = MobSim.Kind.CREEPER; m.speed = 0.25; m.height = 1.7; m.attackDamage = 0; m.health = 20; m.maxHealth = 20;
        m.armour = 0; m.blastRadius = 3.0; m.maxSwell = 30; m.follows = true;
        place(m, x, z);
        return m;
    }

    static void place(MobSim m, double x, double z) {
        m.x = x; m.z = z; m.y = 64; m.floorY = 64; m.onGround = true;
        m.drawnX = x; m.drawnY = 64; m.drawnZ = z; m.delay = 0;
        m.yRot = (float) AuraSim.yawTo(x, z, 0.5, 0.5);
        m.active = true; m.sinceCheck = 20; m.hasPath = true; m.walking = true; m.pathIn = 0;
        m.pathX = 0.5; m.pathZ = 0.5; m.pathTargetX = 0.5; m.pathTargetY = 64; m.pathTargetZ = 0.5;
    }

    /** A scene: "zombie:3,skeleton:2" at distances rMin to rMax, random angles, seeded. */
    static FightWorld scene(String spec, double rMin, double rMax, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        FightWorld w = new FightWorld();
        MoveState p = new MoveState();
        p.x = 0.5; p.y = 64; p.z = 0.5; p.onGround = true; p.vy = -0.0784000015258789;
        p.hasSupport = true; p.supX = 0; p.supY = 63; p.supZ = 0;
        w.player = p;
        w.health = 1000; w.maxHealth = 1000;
        w.aura = new AuraSim();
        w.aura.weapon = new SwingPlan.Weapon(8.0, 12.500001F, true, 0.0);
        List<MobSim> ms = new ArrayList<>();
        int id = 1;
        for (String part : spec.split(",")) {
            String type = part.substring(0, part.indexOf(':'));
            int n = Integer.parseInt(part.substring(part.indexOf(':') + 1));
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * rnd.nextDouble(), r = rMin + (rMax - rMin) * rnd.nextDouble();
                double x = 0.5 - Math.sin(a) * r, z = 0.5 + Math.cos(a) * r;
                MobSim m = switch (type) {
                    case "skeleton" -> skeleton(x, z);
                    case "creeper" -> creeper(x, z);
                    default -> zombie(x, z);
                };
                m.id = id++;
                ms.add(m);
            }
        }
        w.mobs = ms.toArray(new MobSim[0]);
        return w;
    }

    // ---------------------------------------------------------------- the bow, copied from BowAura's rules

    static final Ballistics.Spec FULL = new Ballistics.Spec(0.05, 0.99F, 3.0, 0.0, false, true, true);
    static final List<HitChance.Box> GROUND = List.of(new HitChance.Box(-200, 0, -200, 200, 64, 200));

    /** A player arrow in flight in the model world. */
    static final class Arrow {
        double x, y, z, vx, vy, vz;
        int age = 0;
        boolean alive = true;
    }

    static double eyeToBox(double ex, double ey, double ez, MobSim m) {
        double hw = m.width / 2;
        double dx = Math.max(Math.max(m.x - hw - ex, 0.0), ex - (m.x + hw));
        double dy = Math.max(Math.max(m.y - ey, 0.0), ey - (m.y + m.height));
        double dz = Math.max(Math.max(m.z - hw - ez, 0.0), ez - (m.z + hw));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** KillAura.meleeTicks in the model. */
    static double meleeTicks(FightWorld w) {
        MoveState p = w.player;
        double ex = p.x, ey = p.y + 1.62, ez = p.z, best = Double.POSITIVE_INFINITY;
        for (MobSim m : w.mobs) {
            if (!m.alive) continue;
            double gap = eyeToBox(ex, ey, ez, m) - 3.0;
            if (gap <= 0) return 0.0;
            if (gap <= 0.5) { best = Math.min(best, 1.0); continue; }
            double dx = m.x - p.x, dz = m.z - p.z, d = Math.sqrt(dx * dx + dz * dz);
            double closing = d < 1e-6 ? 0 : -(m.lastDx * dx + m.lastDz * dz) / d;
            if (m.kind != MobSim.Kind.BOW) closing = Math.max(closing, 0.5 * m.speed);
            if (closing > 1e-3) best = Math.min(best, Math.max(1.0, gap / closing));
        }
        return best;
    }

    static BowTargets.Kind kindOf(MobSim m) {
        return m.kind == MobSim.Kind.CREEPER ? BowTargets.Kind.CREEPER : m.kind == MobSim.Kind.BOW ? BowTargets.Kind.RANGED : BowTargets.Kind.MELEE;
    }

    static HitChance.Target target(MobSim m, double lead) {
        double hw = m.width / 2;
        HitChance.Box b = new HitChance.Box(m.x - hw + m.lastDx * lead, m.y, m.z - hw + m.lastDz * lead,
                                            m.x + hw + m.lastDx * lead, m.y + m.height, m.z + hw + m.lastDz * lead);
        return new HitChance.Target(b, m.lastDx, 0, m.lastDz);
    }

    /** The solve for a full-power shot at the mob from the player's eye less 0.1, `lead` ticks on. */
    static Ballistics.Solution solve(FightWorld w, MobSim m, double lead) {
        MoveState p = w.player;
        Vec3 spawn = new Vec3(p.x, p.y + 1.62 - 0.1, p.z);
        Vec3 c = new Vec3(m.x + m.lastDx * lead, m.y + m.height / 2, m.z + m.lastDz * lead);
        return Ballistics.solve(spawn, c, Ballistics.Course.straight(new Vec3(m.lastDx, 0, m.lastDz)), 0.0, FULL, Vec3.ZERO, true);
    }

    static double chance(FightWorld w, MobSim m, double lead, int rolls) {
        Ballistics.Solution s = solve(w, m, lead);
        if (Math.abs(s.miss()) > 0.5) return 0.0;
        MoveState p = w.player;
        Vec3 d = Ballistics.launchDir(s.yaw(), s.pitch(), FULL);
        HitChance.Shot shot = new HitChance.Shot(p.x, p.y + 1.62 - 0.1, p.z, d.x, d.y, d.z, 3.0, 1.0, 0, 0, 0);
        return HitChance.chance(shot, target(m, lead), GROUND, rolls, 200);
    }

    static double threatDps(MobSim m) {
        return switch (m.kind) {
            case BOW -> 3.5 * 0.7 / 40.0;
            case CREEPER -> 0.0;
            default -> m.attackDamage / 20.0;
        };
    }

    static double arriveTicks(FightWorld w, MobSim m) {
        MoveState p = w.player;
        double gap = Math.hypot(m.x - p.x, m.z - p.z) - (m.kind == MobSim.Kind.CREEPER ? 3.0 : 1.5);
        if (gap <= 0) return 0.0;
        double dx = m.x - p.x, dz = m.z - p.z, d = Math.sqrt(dx * dx + dz * dz);
        double closing = -(m.lastDx * dx + m.lastDz * dz) / d;
        return closing > 1e-3 ? gap / closing : Double.POSITIVE_INFINITY;
    }

    record Result(double lost, int ticks, int left, int shots, int hits) {}

    static Result fight(FightWorld w, String policy, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed ^ 0x5DEECE66DL);
        GroundPhysics phys = new GroundPhysics(FightBench.flat(60), new Body());
        boolean bowOn = !policy.equals("NONE") && !policy.equals("MELEE");
        w.aura.on = !policy.equals("NONE");
        BowTargets.Policy pol = bowOn ? BowTargets.Policy.parse(policy, BowTargets.Policy.THREAT) : null;
        double h0 = w.health;
        int draw = -1, wait = 0, target = -1, lastChoose = -100, readySince = -1, shots = 0, hits = 0, t;
        List<Arrow> arrows = new ArrayList<>();
        for (t = 0; t < 1200 && w.alive() > 0 && w.health > 0; t++) {
            if (bowOn) {
                double mt = meleeTicks(w);
                int toShot = draw < 0 ? 20 : Math.max(0, 20 - draw);
                if (mt <= toShot + 2) { draw = -1; target = -1; readySince = -1; }   // the melee aura's: cancel (or no start)
                else {
                    if (wait > 0) wait--;
                    MobSim cur = target >= 0 ? byId(w, target) : null;
                    if (cur == null || !cur.alive || t - lastChoose >= 4) {
                        lastChoose = t;
                        target = choose(w, pol, target);
                        cur = target >= 0 ? byId(w, target) : null;
                    }
                    if (cur == null) { draw = -1; readySince = -1; }
                    else if (draw < 0 && wait == 0) draw = 0;
                    if (draw >= 0 && cur != null) {
                        draw++;
                        if (draw >= 20) {
                            if (readySince < 0) readySince = t;
                            double c = chance(w, cur, 1, 256);
                            double need = t - readySince >= 20 ? BowTargets.VIABLE : 0.5;
                            if (c >= need) {
                                arrows.add(loose(w, cur, rnd));
                                shots++;
                                draw = -1; wait = 3; readySince = -1; target = -1;
                            }
                        }
                    }
                }
            }
            w.tick(phys, 0, rnd);
            for (Arrow a : arrows) if (a.alive && fly(w, a, rnd)) hits++;
            arrows.removeIf(a -> !a.alive);
        }
        return new Result(h0 - w.health, t, w.alive(), shots, hits);
    }

    static MobSim byId(FightWorld w, int id) {
        for (MobSim m : w.mobs) if (m.id == id) return m;
        return null;
    }

    static int choose(FightWorld w, BowTargets.Policy pol, int current) {
        List<BowTargets.Cand> cs = new ArrayList<>();
        MoveState p = w.player;
        for (MobSim m : w.mobs) {
            if (!m.alive) continue;
            double ex = p.x, ey = p.y + 1.62, ez = p.z;
            if (eyeToBox(ex, ey, ez, m) <= 3.5) continue;   // the melee aura's
            double dist = Math.sqrt(MobSim.sq(m.x - ex) + MobSim.sq(m.y + m.height / 2 - ey) + MobSim.sq(m.z - ez));
            if (dist > 48) continue;
            double c = chance(w, m, 2, 256);
            double dmg = BowTargets.arrowDamage(Math.max(0.5, 3.0 - 0.01 * dist), 0, m.armour, m.toughness);
            cs.add(new BowTargets.Cand(m.id, kindOf(m), dist, m.health, c, threatDps(m), 15.0, true, arriveTicks(w, m), dmg));
        }
        int i = BowTargets.pick(cs, pol, current);
        return i < 0 ? -1 : cs.get(i).id();
    }

    /** The shot: along the solve plus the game's spread roll (triangle on each axis), from the eye less 0.1. */
    static Arrow loose(FightWorld w, MobSim m, SplittableRandom rnd) {
        Ballistics.Solution s = solve(w, m, 1);
        Vec3 d = Ballistics.launchDir(s.yaw(), s.pitch(), FULL);
        double a = HitChance.SPREAD;
        Arrow ar = new Arrow();
        MoveState p = w.player;
        ar.x = p.x; ar.y = p.y + 1.62 - 0.1; ar.z = p.z;
        ar.vx = (d.x + a * (rnd.nextDouble() - rnd.nextDouble())) * 3.0;
        ar.vy = (d.y + a * (rnd.nextDouble() - rnd.nextDouble())) * 3.0;
        ar.vz = (d.z + a * (rnd.nextDouble() - rnd.nextDouble())) * 3.0;
        return ar;
    }

    /** One tick of the arrow (AbstractArrow.tick: the move tested against each mob's box, grown by the margin of its age,
     *  then drag and gravity); a hit hurts the mob. Returns whether it hit. */
    static boolean fly(FightWorld w, Arrow ar, SplittableRandom rnd) {
        ar.age++;
        double nx = ar.x + ar.vx, ny = ar.y + ar.vy, nz = ar.z + ar.vz;
        if (ny < 64) { ar.alive = false; return false; }   // into the ground
        double margin = HitChance.margin(ar.age);
        MobSim best = null; double bestS = 2;
        for (MobSim m : w.mobs) {
            if (!m.alive) continue;
            double hw = m.width / 2 + margin;
            HitChance.Box b = new HitChance.Box(m.x - hw, m.y - margin, m.z - hw, m.x + hw, m.y + m.height + margin, m.z + hw);
            double s = b.clip(ar.x, ar.y, ar.z, ar.vx, ar.vy, ar.vz);
            if (!Double.isNaN(s) && s < bestS) { bestS = s; best = m; }
        }
        if (best != null) {
            double speed = Math.sqrt(ar.vx * ar.vx + ar.vy * ar.vy + ar.vz * ar.vz);
            int dmg = (int) Math.ceil(speed * 2.0);
            dmg += rnd.nextInt(dmg / 2 + 2);   // a full draw's crit
            hurt(best, dmg, ar.vx, ar.vz);
            ar.alive = false;
            return true;
        }
        ar.x = nx; ar.y = ny; ar.z = nz;
        ar.vx *= 0.99; ar.vy *= 0.99; ar.vz *= 0.99; ar.vy -= 0.05;
        if (ar.age > 200) ar.alive = false;
        return false;
    }

    /** LivingEntity.hurtServer on the mob: its window, armour, knockback 0.4 along the arrow on a full hit. */
    static void hurt(MobSim m, double raw, double dirX, double dirZ) {
        double through;
        boolean full = m.freshIn <= 0;
        if (!full) {
            if (raw <= m.lastHurt) return;
            through = raw - m.lastHurt;
            m.lastHurt = raw;
        } else {
            through = raw;
            m.lastHurt = raw;
            m.freshIn = SwingPlan.WINDOW;
        }
        double taken = Math.min(m.health, SwingPlan.afterArmour(through, m.armour, m.toughness));
        m.health -= taken;
        if (m.health <= 1e-9) { m.alive = false; m.health = 0; }
        if (full) m.knockback(0.4, -dirX, -dirZ);
    }

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 40;
        String[][] scenes = {
            {"mix", "zombie:3,skeleton:2", "14", "26"},
            {"crsk", "creeper:1,skeleton:1,zombie:1", "14", "24"},
            {"crowd", "zombie:5,skeleton:2,creeper:1", "10", "30"},
            {"sk3", "skeleton:3", "12", "26"},
            {"zc", "zombie:3,creeper:2", "12", "24"},
            {"far", "zombie:2,skeleton:2,creeper:1", "20", "40"},
        };
        java.util.Set<String> only = args.length > 1 ? java.util.Set.of(java.util.Arrays.copyOfRange(args, 1, args.length)) : null;
        System.out.printf(Locale.ROOT, "%-6s %-13s %8s %8s %8s %7s %6s %6s%n", "scene", "policy", "lost", "ticks", "unended", "shots", "hit%", "lostSE");
        for (String[] sc : scenes) {
            if (only != null && !only.contains(sc[0])) continue;
            for (String pol : POLICIES) {
                double lost = 0, lost2 = 0, ticks = 0; int unended = 0, shots = 0, hits = 0;
                for (int s = 0; s < seeds; s++) {
                    FightWorld w = scene(sc[1], Double.parseDouble(sc[2]), Double.parseDouble(sc[3]), 1000L * s + sc[0].hashCode());
                    Result r = fight(w, pol, s);
                    lost += r.lost(); lost2 += r.lost() * r.lost(); ticks += r.ticks();
                    if (r.left() > 0) unended++;
                    shots += r.shots(); hits += r.hits();
                }
                double mean = lost / seeds, se = Math.sqrt(Math.max(0, lost2 / seeds - mean * mean) / seeds);
                System.out.printf(Locale.ROOT, "%-6s %-13s %8.2f %8.1f %8d %7.1f %6.0f %6.2f%n", sc[0], pol, mean, ticks / seeds, unended,
                    shots / (double) seeds, shots == 0 ? Double.NaN : 100.0 * hits / shots, se);
            }
        }
    }
}
