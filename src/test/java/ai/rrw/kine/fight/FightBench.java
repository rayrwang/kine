package ai.rrw.kine.fight;

import ai.rrw.kine.combat.SwingPlan;
import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.Shape;
import ai.rrw.kine.search.RecedingSearch;

import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Whole fights in the model world, for tuning the combat movement before the lockstep tests: zombies walk in on flat
 * ground; the kill aura alone (no keys) against the aura with the search moving the player, replanning every tick.
 * The "real" fight is the same model with its own random stream. Run with java -cp (test runtime classpath)
 * ai.rrw.kine.fight.FightBench [mobs] [seeds] [slider] [arc].
 */
public final class FightBench {
    static BlockGrid flat(int r) {
        BlockGrid g = new BlockGrid(-r, 58, -r, 2 * r + 1, 12, 2 * r + 1);
        BlockInfo stone = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) g.set(x, 63, z, stone);
        return g;
    }

    static FightWorld scene(int n, long seed, double arcDeg) {
        SplittableRandom rnd = new SplittableRandom(seed);
        FightWorld w = new FightWorld();
        MoveState p = new MoveState();
        p.x = 0.5; p.y = 64; p.z = 0.5; p.onGround = true; p.vy = -0.0784000015258789;
        p.hasSupport = true; p.supX = 0; p.supY = 63; p.supZ = 0;
        w.player = p;
        w.health = Double.parseDouble(System.getProperty("hp", "200"));
        w.maxHealth = 200;
        w.aura = new AuraSim();
        w.aura.weapon = new SwingPlan.Weapon(8.0, 12.500001F, true, 0.0);
        w.mobs = new MobSim[n];
        for (int i = 0; i < n; i++) {
            MobSim m = new MobSim();
            double a = Math.toRadians(arcDeg * (rnd.nextDouble() - 0.5)), r = 6 + 4 * rnd.nextDouble();
            m.x = 0.5 - Math.sin(a) * r; m.z = 0.5 + Math.cos(a) * r; m.y = 64; m.floorY = 64;
            m.attackCooldown = 0;
            m.pathIn = rnd.nextInt(10);
            m.drawnX = m.x; m.drawnY = m.y; m.drawnZ = m.z; m.delay = Integer.getInteger("delay", 4);   // the aura sees it as the client shows it
            if (i < Integer.getInteger("babies", 0)) { m.speed = 0.23 * 1.5; m.width = 0.3; m.height = 0.975; }   // -Dbabies=n: the first n are babies
            if (i < Integer.getInteger("creepers", 0)) {   // -Dcreepers=n: the first n are creepers
                m.kind = MobSim.Kind.CREEPER; m.speed = 0.25; m.height = 1.7; m.attackDamage = 0; m.health = 20; m.maxHealth = 20;
                m.armour = 0; m.blastRadius = 3.0; m.maxSwell = 30;
            }
            int slime = Integer.getInteger("slime", 0);   // -Dslime=size: slimes of that size (-Dmagma=true: magma cubes)
            if (slime > 0) {
                boolean magma = Boolean.getBoolean("magma");
                m.kind = MobSim.Kind.SLIME; m.magma = magma; m.slimeSize = slime; m.touchDamage = magma || slime > 1;
                m.speed = 0.2F + 0.1F * slime; m.jumpPower = 0.42F + (magma ? slime * 0.1F : 0.0F);
                m.width = 0.52F * slime; m.height = 0.52F * slime;
                m.attackDamage = m.touchDamage ? slime + (magma ? 2 : 0) : 0;
                m.health = slime * slime; m.maxHealth = m.health; m.armour = magma ? slime * 3 : 0;
                m.walking = false; m.jumpDelay = rnd.nextInt(10); m.fullTick = rnd.nextBoolean();
                m.yRot = (float) AuraSim.yawTo(m.x, m.z, 0.5, 0.5);
            }
            w.mobs[i] = m;
        }
        return w;
    }

    /** Creeper counts over a bench run (all fights with the search): fuses lit, ticks within 3 of a creeper not
     *  swelling, blasts, the closest approach to a creeper (3D). */
    static int[] fuses = new int[2], in3 = new int[2], blasts = new int[2];
    static double[] creeperClosest = {Double.MAX_VALUE, Double.MAX_VALUE};

    record Outcome(double lost, int ticks, int left, double msPerReplan, double maxMs, int stepsIn, int ticksInTwo, double closest) {}

    /** Whether a mob's attack box (its box grown sideways by its reach) meets the player's box at these positions. */
    static boolean inReach(double px, double py, double pz, MobSim m, double mx, double mz) {
        double hw = m.width / 2 + MobSim.REACH;
        return mx - hw < px + 0.3 && mx + hw > px - 0.3 && m.y < py + 1.8 && m.y + m.height > py && mz - hw < pz + 0.3 && mz + hw > pz - 0.3;
    }

    static Outcome fight(FightWorld truth, GroundPhysics phys, boolean search, boolean ideal, double slider, RecedingSearch.Effort e, long seed) {
        SplittableRandom real = new SplittableRandom(seed ^ 0x5DEECE66DL);
        FightModel model = new FightModel(phys, slider, ideal);
        RecedingSearch<FightWorld> rs = new RecedingSearch<>(model);
        int[] plan = null;
        double h0 = truth.health;
        long nanos = 0, maxNanos = 0;
        int replans = 0, t;
        int stepsIn = 0, inTwo = 0;
        double closest = Double.MAX_VALUE;
        int lag = Integer.getInteger("obsLag", 0);   // ticks the planner's view of the mobs trails the truth (0: it sees the truth)
        boolean[] lit = new boolean[truth.mobs.length], creeperSeen = new boolean[truth.mobs.length];
        String[] log = new String[40];
        double[][] hist = new double[truth.mobs.length][2 * 64];
        for (t = 0; t < 1200 && truth.alive() > 0 && truth.health > 0; t++) {
            for (int i = 0; i < truth.mobs.length; i++) { hist[i][(t % 64) * 2] = truth.mobs[i].x; hist[i][(t % 64) * 2 + 1] = truth.mobs[i].z; }
            double px0 = truth.player.x, py0 = truth.player.y, pz0 = truth.player.z;
            double[] mx0 = new double[truth.mobs.length], mz0 = new double[truth.mobs.length];
            for (int i = 0; i < truth.mobs.length; i++) { mx0[i] = truth.mobs[i].x; mz0[i] = truth.mobs[i].z; }
            if (search) {
                FightWorld view = truth;
                if (lag > 0) {   // what the client would know: each mob where it was lag ticks ago, carried on at its velocity then
                    view = truth.copy();
                    view.lag = lag;   // and the planner knows the lag (the snapshot's from the ping)
                    for (int i = 0; i < view.mobs.length; i++) {
                        MobSim m = view.mobs[i];
                        int a = Math.max(0, t - lag), b = Math.max(0, t - lag - 1);
                        double x1 = hist[i][(a % 64) * 2], z1 = hist[i][(a % 64) * 2 + 1], x0 = hist[i][(b % 64) * 2], z0 = hist[i][(b % 64) * 2 + 1];
                        m.x = x1 + (x1 - x0) * lag; m.z = z1 + (z1 - z0) * lag;
                        m.vx = (x1 - x0) * 0.546; m.vz = (z1 - z0) * 0.546;
                        m.active = true; m.sinceCheck = 20; m.hasPath = true; m.pathIn = 0;
                        m.pathX = Math.floor(truth.player.x) + 0.5; m.pathZ = Math.floor(truth.player.z) + 0.5;
                        m.pathTargetX = truth.player.x; m.pathTargetY = truth.player.y; m.pathTargetZ = truth.player.z;
                    }
                }
                RecedingSearch.Result r = rs.plan(view, plan, plan == null ? 0 : 1, e, seed * 1000003L + t);
                nanos += r.stats().nanos();
                maxNanos = Math.max(maxNanos, r.stats().nanos());
                replans++;
                plan = r.plan();
                int action = plan == null ? Moves.HOLD : plan[0];
                if (action != Moves.HOLD) truth.segmentYaw = FightModel.headingFor(truth, Moves.direction(action));
                truth.planHeading = action == Moves.HOLD ? Float.NaN : truth.segmentYaw;   // the real aura's sprint out reads it
                final int act = action;
                truth.tick(phys, FightModel.chooser(truth, act, ideal), real);
            } else {
                truth.tick(phys, 0, real);
            }
            // creepers: fuses lit, ticks within 3 of one not swelling (its fuse trigger), blasts
            if (Boolean.getBoolean("blastlog") && search) {
                StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "t%d p %.2f,%.2f hp %.1f k %d:", t, truth.player.x, truth.player.z, truth.health, truth.aura.k));
                for (MobSim m : truth.mobs)
                    if (m.kind == MobSim.Kind.CREEPER && m.alive)
                        sb.append(String.format(Locale.ROOT, " [d%.2f sw%d dir%d hp%.0f]", Math.sqrt(MobSim.sq(m.x - truth.player.x) + MobSim.sq(m.z - truth.player.z)), m.swell, m.swellDir, m.health));
                log[t % log.length] = sb.toString();
            }
            for (int i = 0; i < truth.mobs.length; i++) {
                MobSim m = truth.mobs[i];
                if (m.kind != MobSim.Kind.CREEPER) continue;
                int mode = search ? 1 : 0;
                if (m.exploded && !creeperSeen[i] && Boolean.getBoolean("blastlog") && search) {
                    System.out.println("BLAST at t" + t + " (seed " + seed + "):");
                    for (int k = Math.max(0, t - 34); k <= t; k++) System.out.println("  " + log[k % log.length]);
                }
                if (m.exploded && !creeperSeen[i]) { blasts[mode]++; creeperSeen[i] = true; }
                if (!m.alive) continue;
                if (m.swellDir > 0 && !lit[i]) fuses[mode]++;
                lit[i] = m.swellDir > 0;
                double dx = m.x - truth.player.x, dy = m.y - truth.player.y, dz = m.z - truth.player.z;
                if (dx * dx + dy * dy + dz * dz < 9.0 && m.swellDir <= 0) in3[mode]++;
                creeperClosest[mode] = Math.min(creeperClosest[mode], Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            // the player's own move into a mob's reach (mob where it was), ticks in reach of two or more, closest approach
            int in = 0;
            for (int i = 0; i < truth.mobs.length; i++) {
                MobSim m = truth.mobs[i];
                if (!m.alive) continue;
                if (!inReach(px0, py0, pz0, m, mx0[i], mz0[i]) && inReach(truth.player.x, truth.player.y, truth.player.z, m, mx0[i], mz0[i])
                    && (!m.active || m.attackCooldown <= 1)) stepsIn++;   // into the reach of a mob whose attack is ready
                if (inReach(truth.player.x, truth.player.y, truth.player.z, m, m.x, m.z)) in++;
                closest = Math.min(closest, Math.hypot(m.x - truth.player.x, m.z - truth.player.z));
            }
            if (in >= 2) inTwo++;
        }
        return new Outcome(h0 - truth.health, t, truth.alive(), replans == 0 ? 0 : nanos / 1e6 / replans, maxNanos / 1e6, stepsIn, inTwo, closest);
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        double slider = args.length > 2 ? Double.parseDouble(args[2]) : 0.25;
        double arc = args.length > 3 ? Double.parseDouble(args[3]) : 360;
        RecedingSearch.Effort e = new RecedingSearch.Effort();
        e.beam = args.length > 4 ? Integer.parseInt(args[4]) : 4;
        e.depth = args.length > 5 ? Integer.parseInt(args[5]) : 4;
        e.hold = args.length > 6 ? Integer.parseInt(args[6]) : 5;
        e.draws = args.length > 7 ? Integer.parseInt(args[7]) : 2;
        e.budgetNanos = 1_000_000_000L;
        GroundPhysics phys = new GroundPhysics(flat(40), new Body());
        double[] sum = new double[9];
        boolean ideal = args.length > 8 && args[8].equals("ideal");
        System.out.printf(Locale.ROOT, "mobs %d arc %.0f slider %.2f effort %s%n", n, arc, slider, e);
        for (int s = 0; s < seeds; s++) {
            Outcome a = fight(scene(n, s, arc), phys, false, false, slider, e, s);
            int b0 = blasts[1];
            Outcome b = fight(scene(n, s, arc), phys, true, false, slider, e, s);
            if (blasts[1] > b0) System.out.printf(Locale.ROOT, "seed %2d: %d blasts with the search%n", s, blasts[1] - b0);
            Outcome c = ideal ? fight(scene(n, s, arc), phys, true, true, slider, e, s) : b;
            System.out.printf(Locale.ROOT, "seed %2d  aura alone: lost %5.1f in %4d ticks (%d left)   with search: lost %5.1f in %4d ticks (%d left), %.1f ms a replan (max %.1f); steps into reach %d, ticks in reach of 2+ %d, closest %.2f%s%n",
                s, a.lost(), a.ticks(), a.left(), b.lost(), b.ticks(), b.left(), b.msPerReplan(), b.maxMs(), b.stepsIn(), b.ticksInTwo(), b.closest(),
                ideal ? String.format(Locale.ROOT, "   ideal heading: lost %5.1f in %4d ticks", c.lost(), c.ticks()) : "");
            sum[0] += a.lost(); sum[1] += a.ticks(); sum[2] += b.lost(); sum[3] += b.ticks(); sum[4] += c.lost(); sum[5] += c.ticks();
            sum[6] += b.stepsIn(); sum[7] += b.ticksInTwo(); sum[8] += b.closest();
        }
        if (Integer.getInteger("creepers", 0) > 0)
            for (int mode = 0; mode < 2; mode++)
                System.out.printf(Locale.ROOT, "CREEPERS %s: fuses lit %d, ticks within 3 of one not swelling %d, blasts %d, closest %.2f%n",
                    mode == 0 ? "aura alone" : "with search", fuses[mode], in3[mode], blasts[mode], creeperClosest[mode]);
        System.out.printf(Locale.ROOT, "MEAN aura alone: lost %.2f, %.0f ticks;  with search: lost %.2f, %.0f ticks, steps into reach %.1f, ticks in reach of 2+ %.1f, closest %.2f%s%n",
            sum[0] / seeds, sum[1] / seeds, sum[2] / seeds, sum[3] / seeds, sum[6] / seeds, sum[7] / seeds, sum[8] / seeds,
            ideal ? String.format(Locale.ROOT, ";  ideal heading: lost %.2f, %.0f ticks", sum[4] / seeds, sum[5] / seeds) : "");
    }
}
