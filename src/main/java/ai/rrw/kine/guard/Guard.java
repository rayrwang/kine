package ai.rrw.kine.guard;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.MoveState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * The elytra guardian's judgment, free of game types (RW 2026-10-14, DECISIONS.md): each tick it flies the path the
 * player will take if it does nothing, in the movement copy with the game's damage rules ({@link GlideSim}), and acts
 * only if that path takes damage, at the last moment a rescue still works with a margin for lag.
 *
 * <p>{@link #watch} (no rescue under way): the do-nothing path for {@link Config#horizon} ticks; if it takes damage,
 * whether waiting one more tick would still leave a rescue that survives a freeze of the controls of
 * {@link Config#freeze} ticks at its start (the next chance: one more tick of doing nothing, the look then held through
 * the freeze, then one of the named moves rescues). If it would, it waits; if not, it fires now with the best rescue a
 * search finds from here (RW's ruling 2: "last moment with reasonable margin"). A rescue that still takes damage is
 * flown only if it saves at least {@link Config#mitigate} points over doing nothing.
 *
 * <p>{@link #steer} (a rescue under way): hand back once the rescue has settled into its glide and a hands-off glide
 * from here stays damage-free for {@link Config#handback} ticks, well past the 1.5 s AUTOPILOT OFF warning; otherwise
 * keep the rescue, or switch to a clearly better one found from here.
 *
 * <p>The search (ruling 4): the named moves ({@link Maneuver#seeds}, plus any fed back from test scenarios in
 * {@link Config#extraSeeds}) are tried first, then variations of the best few until the time budget runs out. Scored
 * damage first (no damage in the move or in the hands-off glide after it, then the least damage, as late as
 * possible), then the end's safety: heading back along the pilot's own recent track ({@link Track#cost}), open air
 * ahead along the final heading, and speed.
 */
public final class Guard {

    public static final class Config {
        /** Ticks of the do-nothing path looked at. */
        public int horizon = 160;
        /** Ticks of frozen controls at its start a rescue must survive to wait another tick (ruling 2's margin). */
        public int freeze = 20;
        /** Ticks a hands-off glide must stay damage-free to hand back (the warning is 30). */
        public int handback = 80;
        /** Most ticks a rescue's move is flown before its hands-off tail. */
        public int maxManeuver = 140;
        /** Ticks of a rescue's final glide flown before its tail. */
        public int settle = 10;
        /** Time budgets: the rescue search when firing and while steering, and the can-it-wait check. */
        public long fireNanos = 25_000_000L, steerNanos = 12_000_000L, checkNanos = 15_000_000L;
        /** A rescue that still takes damage is flown only if it saves this much. */
        public double mitigate = 2.0;
        /** A new rescue replaces the one under way only if its cost is lower by this much. */
        public double hysteresis = 0.3;
        /** Weights of the end's safety in a full rescue's cost. */
        public double wTrack = 1.0, wOpen = 1.0, wSpeed = 0.5, wUnsettled = 0.5, wHeight = 0.5;
        /** Rescues fed back from the test scenarios, tried after the named moves. */
        public List<Maneuver> extraSeeds = List.of();
        /** Offline only: stop each search after this many variations instead of at its time budget (0: by time), so a
         *  bank of scenarios gives the same numbers on any machine. */
        public int variations = 0;

        public Config copy() {
            Config c = new Config();
            c.horizon = horizon; c.freeze = freeze; c.handback = handback; c.maxManeuver = maxManeuver; c.settle = settle;
            c.fireNanos = fireNanos; c.steerNanos = steerNanos; c.checkNanos = checkNanos; c.mitigate = mitigate;
            c.hysteresis = hysteresis; c.wTrack = wTrack; c.wOpen = wOpen; c.wSpeed = wSpeed; c.wUnsettled = wUnsettled;
            c.wHeight = wHeight;
            c.extraSeeds = extraSeeds; c.variations = variations;
            return c;
        }
    }

    /** A rollout: the first damage (its amount, tick and kinds; tick -1 for none), the ticks flown, where it ended and
     *  the look there. */
    public static final class Outcome {
        public double damage;
        public int damageTick = -1, kinds, ticks;
        public MoveState end;
        public float yaw, pitch;

        public boolean hurt() { return damageTick >= 0; }

        @Override public String toString() {
            return hurt() ? String.format(Locale.ROOT, "%.1f damage (%s) at tick %d", damage, kindNames(kinds), damageTick)
                          : "no damage in " + ticks + " ticks";
        }
    }

    /** A rescue tried: the move as it starts, how it went, how it ended. */
    public static final class Candidate {
        public final Maneuver plan;
        public final Outcome move, tail;
        public final boolean full, settled;
        public final double cost, trackCost, openCost, speedCost, open, endSpeed, heightLost;

        Candidate(Maneuver plan, Outcome move, Outcome tail, boolean settled, double cost, double trackCost,
                  double openCost, double speedCost, double open, double endSpeed, double heightLost) {
            this.plan = plan; this.move = move; this.tail = tail; this.settled = settled;
            this.full = !move.hurt() && tail != null && !tail.hurt();
            this.cost = cost; this.trackCost = trackCost; this.openCost = openCost; this.speedCost = speedCost;
            this.open = open; this.endSpeed = endSpeed; this.heightLost = heightLost;
        }

        @Override public String toString() {
            String how = full ? String.format(Locale.ROOT, "full, %d ticks, height lost %.1f, end speed %.2f, open %.0f, cost %.3f "
                    + "(track %.2f open %.2f speed %.2f)%s", move.ticks, heightLost, endSpeed, open, cost, trackCost, openCost,
                    speedCost, settled ? "" : " unsettled")
                : move.hurt() ? "move " + move : "tail " + tail;
            return plan + ": " + how;
        }
    }

    public enum Kind { SAFE, WAIT, NO_HELP, FIRE, STEER, HANDBACK }

    /** The judgment for one tick: what to do, the rescue to fly (FIRE, STEER), the do-nothing path's damage (WAIT, FIRE,
     *  NO_HELP), and the work done. */
    public record Verdict(Kind kind, Candidate rescue, Outcome danger, int candidates, long steps, long nanos) {}

    private final GlideSim sim;
    private final Config cfg;
    private final Track track;
    private final List<Maneuver> seeds;
    private long steps = 0;
    private int candidates = 0;
    private final float[] out = new float[2];

    public Guard(GlideSim sim, Config cfg, Track track) {
        this.sim = sim; this.cfg = cfg; this.track = track == null ? Track.NONE : track;
        List<Maneuver> l = new ArrayList<>(Maneuver.seeds());
        l.addAll(cfg.extraSeeds);
        this.seeds = l;
    }

    public Config config() { return cfg; }
    public GlideSim sim() { return sim; }

    /** Fly `p` (a copy is made) from the state and look for at most `ticks`, stopping at the first damage. */
    public Outcome fly(MoveState s0, float yaw, float pitch, Pilot pilot, int ticks) {
        Pilot p = pilot.copy();
        MoveState s = s0.copy();
        float y = yaw, pt = pitch;
        Outcome o = new Outcome();
        int t;
        for (t = 1; t <= ticks; t++) {
            p.look(s, y, pt, out);
            y = out[0]; pt = out[1];
            double d = sim.step(s, y, pt, p.boost());
            steps++;
            p.after(s);
            if (d > 0.0) { o.damage = d; o.damageTick = t; o.kinds = sim.lastKinds; break; }
        }
        o.ticks = Math.min(t, ticks);
        o.end = s; o.yaw = y; o.pitch = pt;
        return o;
    }

    /** Try a rescue from the state and look (it starts from its first phase unless it is one under way). */
    public Candidate evaluate(Maneuver m, MoveState s0, float yaw, float pitch) {
        candidates++;
        Maneuver run = m.copy();
        MoveState s = s0.copy();
        float y = yaw, p = pitch;
        Outcome mo = new Outcome();
        int glide = 0, t;
        for (t = 1; t <= cfg.maxManeuver; t++) {
            run.look(s, y, p, out);
            y = out[0]; p = out[1];
            double d = sim.step(s, y, p, false);
            steps++;
            run.after(s);
            if (d > 0.0) { mo.damage = d; mo.damageTick = t; mo.kinds = sim.lastKinds; break; }
            if (run.gliding() && ++glide >= cfg.settle) break;
        }
        mo.ticks = Math.min(t, cfg.maxManeuver);
        mo.end = s; mo.yaw = y; mo.pitch = p;
        boolean settled = run.gliding();
        if (mo.hurt()) {
            double cost = 1.0e6 + mo.damage * 1000.0 - mo.damageTick;
            return new Candidate(m, mo, null, settled, cost, 1, 1, 1, 0, 0, s0.y - s.y);
        }
        Outcome tail = fly(s, y, p, Pilot.HOLD, cfg.handback);
        double speed = Math.sqrt(s.vx * s.vx + s.vz * s.vz);
        double heading = speed > Maneuver.TRACK_MIN_SPEED ? Pilot.trackYaw(s.vx, s.vz) : y;
        double open = openAhead(sim.grid(), s.x, s.y, s.z, heading);
        double tc = track.cost(s.x, s.y, s.z, heading, cfg.wHeight), oc = 1.0 - open / OPEN_REACH;
        double sc = 1.0 - Math.min(Math.sqrt(speed * speed + s.vy * s.vy), 1.5) / 1.5;
        double cost;
        if (tail.hurt()) cost = 1.0e5 + (cfg.handback - tail.damageTick);
        else cost = cfg.wTrack * tc + cfg.wOpen * oc + cfg.wSpeed * sc + (settled ? 0.0 : cfg.wUnsettled);
        return new Candidate(m, mo, tail, settled, cost, tc, oc, sc, open, speed, s0.y - s.y);
    }

    static final double OPEN_REACH = 96.0;

    /** Blocks of open air along `yaw` from (x, y, z), sinking 1 in 8 as a glide does, out to OPEN_REACH: the first
     *  cell that blocks motion or hurts (two cells tall, the gliding box's height and a margin) ends it. */
    static double openAhead(BlockGrid g, double x, double y, double z, double yaw) {
        double r = Math.toRadians(yaw), hx = -Math.sin(r), hz = Math.cos(r);
        for (double d = 1.0; d <= OPEN_REACH; d += 1.0) {
            double px = x + hx * d, py = y + 0.3 - d / 8.0, pz = z + hz * d;
            int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
            if (blocks(g.get(bx, by, bz)) || blocks(g.get(bx, by + 1, bz))) return d - 1.0;
        }
        return OPEN_REACH;
    }

    private static boolean blocks(BlockInfo b) {
        return !b.shape.empty || b.is(BlockInfo.HAZARD);
    }

    /** The search: the named moves (and any fed back), the one under way if any, then variations of the best until the
     *  budget runs out. The named moves are all tried even past the budget. */
    public Candidate search(MoveState s0, float yaw, float pitch, Candidate incumbent, long budgetNanos, long seed) {
        long t0 = System.nanoTime(), deadline = t0 + budgetNanos;
        List<Candidate> pool = new ArrayList<>();
        if (incumbent != null) pool.add(incumbent);
        for (Maneuver m : seeds) pool.add(evaluate(m.fresh(), s0, yaw, pitch));
        pool.sort(Comparator.comparingDouble(c -> c.cost));
        SplittableRandom r = new SplittableRandom(seed);
        for (int n = 0; cfg.variations > 0 ? n < cfg.variations : System.nanoTime() < deadline; n++) {
            double used = cfg.variations > 0 ? n / (double) cfg.variations : (System.nanoTime() - t0) / (double) budgetNanos;
            Candidate parent = pool.get(Math.min(pool.size() - 1, (int) (r.nextDouble() * r.nextDouble() * 4)));
            Candidate c = evaluate(parent.plan.mutate(r, Math.max(0.15, 1.0 - used)), s0, yaw, pitch);
            int at = 0;
            while (at < pool.size() && pool.get(at).cost <= c.cost) at++;
            if (at < 12) { pool.add(at, c); if (pool.size() > 12) pool.remove(pool.size() - 1); }
        }
        return pool.get(0);
    }

    /** The first named move that rescues fully from here (the can-it-wait check), or null; `first` is tried first. */
    public Candidate firstFull(MoveState s0, float yaw, float pitch, String first, long budgetNanos) {
        long deadline = cfg.variations > 0 ? Long.MAX_VALUE : System.nanoTime() + budgetNanos;
        if (first != null)
            for (Maneuver m : seeds)
                if (m.name.equals(first)) {
                    Candidate c = evaluate(m.fresh(), s0, yaw, pitch);
                    if (c.full) return c;
                }
        for (Maneuver m : seeds) {
            if (m.name.equals(first)) continue;
            if (System.nanoTime() > deadline) return null;
            Candidate c = evaluate(m.fresh(), s0, yaw, pitch);
            if (c.full) return c;
        }
        return null;
    }

    /** Name of the last move that passed the can-it-wait check: tried first next time. */
    public String lastWaitSeed;

    /**
     * Watching, no rescue under way. `s0` is the state at the end of a tick, (yaw, pitch) the look in effect, and
     * `doNothing` whoever flies the coming ticks if the guardian doesn't act (the held look, the autopilot, the takeoff).
     */
    public Verdict watch(MoveState s0, float yaw, float pitch, Pilot doNothing, long seed) {
        long t0 = System.nanoTime();
        steps = 0; candidates = 0;
        Outcome danger = fly(s0, yaw, pitch, doNothing, cfg.horizon);
        if (!danger.hurt()) return new Verdict(Kind.SAFE, null, danger, 0, steps, System.nanoTime() - t0);
        // the next chance: a tick more of doing nothing, then the controls frozen, then a rescue
        Outcome next = fly(s0, yaw, pitch, doNothing, 1);
        if (!next.hurt()) {
            Outcome frozen = fly(next.end, next.yaw, next.pitch, Pilot.HOLD, cfg.freeze);
            if (!frozen.hurt()) {
                Candidate c = firstFull(frozen.end, frozen.yaw, frozen.pitch, lastWaitSeed, cfg.checkNanos);
                if (c != null) {
                    lastWaitSeed = c.plan.name;
                    return new Verdict(Kind.WAIT, c, danger, candidates, steps, System.nanoTime() - t0);
                }
            }
        }
        Candidate best = search(s0, yaw, pitch, null, cfg.fireNanos, seed);
        // a rescue whose move takes damage helps only if it saves enough; one whose move is clean and whose hands-off
        // glide after it would hurt is flown (the steering goes on from there)
        if (best.move.hurt() && best.move.damage > danger.damage - cfg.mitigate)
            return new Verdict(Kind.NO_HELP, best, danger, candidates, steps, System.nanoTime() - t0);
        return new Verdict(Kind.FIRE, best, danger, candidates, steps, System.nanoTime() - t0);
    }

    /** Rescuing. `current` is the rescue under way (as it stands now); the look is the one in effect. The track is the
     *  pilot's own from before the rescue (the rescue's path is not "the way you came"). */
    public Verdict steer(MoveState s0, float yaw, float pitch, Maneuver current, long seed) {
        long t0 = System.nanoTime();
        steps = 0; candidates = 0;
        // hand back once the rescue's move is done and a hands-off glide from here stays clear past the warning; or,
        // mid-move and not turning, once a hands-off glide stays clear twice as long (the danger is long gone)
        boolean done = current == null || current.gliding();
        if ((done || settled(s0, yaw, current)) && pitch >= HANDBACK_PITCH_UP && pitch <= HANDBACK_PITCH_DOWN) {
            Outcome hands = fly(s0, yaw, pitch, Pilot.HOLD, done ? cfg.handback : 2 * cfg.handback);
            if (!hands.hurt()) return new Verdict(Kind.HANDBACK, null, hands, candidates, steps, System.nanoTime() - t0);
        }
        Candidate inc = current == null ? null : evaluate(current, s0, yaw, pitch);
        Candidate best = search(s0, yaw, pitch, inc, cfg.steerNanos, seed);
        // keep the rescue under way unless it no longer rescues and another does, or another is clearly better: the
        // best of many tries scores low by chance, and a rescue that changes its mind every tick turns in circles
        if (inc != null && best != inc && !(best.full && !inc.full || best.cost < inc.cost - cfg.hysteresis)) best = inc;
        return new Verdict(Kind.STEER, best, null, candidates, steps, System.nanoTime() - t0);
    }

    /** The look within SETTLED_YAW of the track: not turning. */
    static boolean settled(MoveState s, float yaw, Maneuver current) {
        double speed = Math.sqrt(s.vx * s.vx + s.vz * s.vz);
        return speed > Maneuver.TRACK_MIN_SPEED && Math.abs(Pilot.wrap(yaw - Pilot.trackYaw(s.vx, s.vz))) <= SETTLED_YAW;
    }

    static final double SETTLED_YAW = 20.0;
    /** A hand-back leaves the pilot in a glide, not a dive or a zoom: the look's pitch within these. */
    static final double HANDBACK_PITCH_UP = -20.0, HANDBACK_PITCH_DOWN = 25.0;

    static String kindNames(int k) {
        List<String> l = new ArrayList<>();
        if ((k & GlideSim.WALL) != 0) l.add("wall");
        if ((k & GlideSim.FALL) != 0) l.add("fall");
        if ((k & GlideSim.HAZARD) != 0) l.add("hazard");
        if ((k & GlideSim.VOID) != 0) l.add("void");
        return String.join("+", l);
    }
}
