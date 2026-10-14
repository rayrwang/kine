package ai.rrw.kine.guard;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.MoveState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The guardian's loop offline, as the game runs it: each tick the judgment from the state at the end of the last one
 * (watch, or steer while rescuing), then the tick flown by whoever has the controls: the pilot (`human`), or the
 * rescue. After a hand-back the pilot flies again (hands off, for an AFK pilot: the trip-off's dead-man would log him
 * out 1.5 s later). A freeze holds the look for some ticks from some tick after the guardian fires.
 */
final class GuardLoop {
    static final class Run {
        int fireTick = -1, fireLead = -1, handbackTick = -1, damageTick = -1, kinds, verdictsFired, maxSteps;
        double damage, heightLost, turned, minY = Double.MAX_VALUE, startY;
        long maxNanos;
        String rescue = "", firstRescue = "";
        final List<String> log = new ArrayList<>();
        MoveState end;

        boolean fired() { return fireTick >= 0; }

        @Override public String toString() {
            return String.format(Locale.ROOT, "fire %d (lead %d) handback %d (%d ticks) damage %.1f%s height lost %.1f turned %.0f maxJudge %.1f ms %s",
                fireTick, fireLead, handbackTick, handbackTick >= 0 && fireTick >= 0 ? handbackTick - fireTick : -1, damage,
                damageTick >= 0 ? " at " + damageTick + " (" + Guard.kindNames(kinds) + ")" : "", heightLost, turned,
                maxNanos / 1e6, firstRescue);
        }
    }

    static MoveState gliding(double x, double y, double z, double vx, double vy, double vz) {
        MoveState s = new MoveState();
        s.x = x; s.y = y; s.z = z; s.vx = vx; s.vy = vy; s.vz = vz;
        s.fallFlying = true; s.glidePose = true;
        return s;
    }

    /**
     * Fly `ticks` from `s0` with the look (yaw, pitch). `human` flies when the guardian doesn't; `doNothing` is what the
     * guardian assumes he will do (null: the held look). A freeze of `freezeLen` ticks starts `freezeAfter` ticks after
     * the guardian fires (-1 for none). `stopAtDamage` ends the run at the first damage.
     */
    static Run run(BlockGrid g, MoveState s0, float yaw, float pitch, Pilot human, Pilot doNothing, Guard.Config cfg,
                   int ticks, int freezeAfter, int freezeLen, boolean stopAtDamage) {
        GlideSim sim = new GlideSim(g, new Body(), -64, 3.0);
        Track.Ring ring = new Track.Ring();
        double sp = Math.hypot(s0.vx, s0.vz);
        for (int k = Track.TICKS; k >= 1; k--)                // the way it came: straight back along the velocity
            ring.add(s0.x - s0.vx * k, s0.y - s0.vy * k * 0.2, s0.z - s0.vz * k);
        MoveState s = s0.copy();
        float y = yaw, p = pitch;
        Pilot flying = human;
        Maneuver active = null;
        Run r = new Run();
        r.startY = s.y;
        float[] out = new float[2];
        String lastWait = null;
        double turnedStart = 0;
        for (int t = 0; t < ticks; t++) {
            Guard gd = new Guard(sim, cfg, ring.snapshot());
            gd.lastWaitSeed = lastWait;
            Guard.Verdict v;
            if (active == null) {
                v = gd.watch(s, y, p, doNothing != null ? doNothing : Pilot.HOLD, t);
                lastWait = gd.lastWaitSeed;
                if (v.kind() == Guard.Kind.FIRE) {
                    active = v.rescue().plan.copy();
                    r.fireTick = t; r.fireLead = v.danger().damageTick; r.verdictsFired++;
                    r.firstRescue = v.rescue().toString();
                    r.log.add("t" + t + " FIRE " + v.danger() + " -> " + v.rescue());
                    turnedStart = Pilot.trackYaw(s.vx, s.vz);
                }
            } else {
                Maneuver cur = active.copy();
                v = gd.steer(s, y, p, cur, t);
                if (v.kind() == Guard.Kind.HANDBACK) {
                    r.handbackTick = t;
                    r.turned = Pilot.wrap(Pilot.trackYaw(s.vx, s.vz) - turnedStart);
                    r.log.add("t" + t + " HANDBACK");
                    active = null;
                } else if (v.rescue().plan != cur) {
                    r.log.add("t" + t + " SWITCH -> " + v.rescue());
                    active = v.rescue().plan.copy();
                }
            }
            r.maxNanos = Math.max(r.maxNanos, v.nanos());
            r.maxSteps = (int) Math.max(r.maxSteps, v.steps());
            Pilot who = active != null ? active : (r.handbackTick >= 0 ? Pilot.HOLD : human);
            boolean frozen = freezeAfter >= 0 && r.fireTick >= 0 && t >= r.fireTick + freezeAfter && t < r.fireTick + freezeAfter + freezeLen;
            who.look(s, y, p, out);
            if (!frozen) { y = out[0]; p = out[1]; }
            double d = sim.step(s, y, p, who.boost());
            who.after(s);
            if (who != human && r.handbackTick < 0 && active != null && human != Pilot.HOLD) { /* the pilot's own state waits */ }
            if (active == null) ring.add(s.x, s.y, s.z);   // the pilot's track: not the rescue's own path
            r.minY = Math.min(r.minY, s.y);
            if (d > 0 && r.damageTick < 0) { r.damageTick = t; r.kinds = sim.lastKinds; }
            r.damage += d;
            if (d > 0 && stopAtDamage) break;
        }
        r.heightLost = r.startY - r.minY;
        r.end = s;
        return r;
    }
}
