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
        w.health = 200;
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
            w.mobs[i] = m;
        }
        return w;
    }

    record Outcome(double lost, int ticks, int left, double msPerReplan, double maxMs) {}

    static Outcome fight(FightWorld truth, GroundPhysics phys, boolean search, boolean ideal, double slider, RecedingSearch.Effort e, long seed) {
        SplittableRandom real = new SplittableRandom(seed ^ 0x5DEECE66DL);
        FightModel model = new FightModel(phys, slider, ideal);
        RecedingSearch<FightWorld> rs = new RecedingSearch<>(model);
        int[] plan = null;
        double h0 = truth.health;
        long nanos = 0, maxNanos = 0;
        int replans = 0, t;
        for (t = 0; t < 1200 && truth.alive() > 0 && truth.health > 0; t++) {
            if (search) {
                RecedingSearch.Result r = rs.plan(truth, plan, plan == null ? 0 : 1, e, seed * 1000003L + t);
                nanos += r.stats().nanos();
                maxNanos = Math.max(maxNanos, r.stats().nanos());
                replans++;
                plan = r.plan();
                int action = plan == null ? Moves.HOLD : plan[0];
                if (action != Moves.HOLD) truth.segmentYaw = FightModel.headingFor(truth, Moves.direction(action));
                final int act = action;
                truth.tick(phys, FightModel.chooser(truth, act, ideal), real);
            } else {
                truth.tick(phys, 0, real);
            }
        }
        return new Outcome(h0 - truth.health, t, truth.alive(), replans == 0 ? 0 : nanos / 1e6 / replans, maxNanos / 1e6);
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
        double[] sum = new double[6];
        boolean ideal = args.length > 8 && args[8].equals("ideal");
        System.out.printf(Locale.ROOT, "mobs %d arc %.0f slider %.2f effort %s%n", n, arc, slider, e);
        for (int s = 0; s < seeds; s++) {
            Outcome a = fight(scene(n, s, arc), phys, false, false, slider, e, s);
            Outcome b = fight(scene(n, s, arc), phys, true, false, slider, e, s);
            Outcome c = ideal ? fight(scene(n, s, arc), phys, true, true, slider, e, s) : b;
            System.out.printf(Locale.ROOT, "seed %2d  aura alone: lost %5.1f in %4d ticks (%d left)   with search: lost %5.1f in %4d ticks (%d left), %.1f ms a replan (max %.1f)%s%n",
                s, a.lost(), a.ticks(), a.left(), b.lost(), b.ticks(), b.left(), b.msPerReplan(), b.maxMs(),
                ideal ? String.format(Locale.ROOT, "   ideal heading: lost %5.1f in %4d ticks", c.lost(), c.ticks()) : "");
            sum[0] += a.lost(); sum[1] += a.ticks(); sum[2] += b.lost(); sum[3] += b.ticks(); sum[4] += c.lost(); sum[5] += c.ticks();
        }
        System.out.printf(Locale.ROOT, "MEAN aura alone: lost %.2f, %.0f ticks;  with search: lost %.2f, %.0f ticks%s%n",
            sum[0] / seeds, sum[1] / seeds, sum[2] / seeds, sum[3] / seeds,
            ideal ? String.format(Locale.ROOT, ";  ideal heading: lost %.2f, %.0f ticks", sum[4] / seeds, sum[5] / seeds) : "");
    }
}
