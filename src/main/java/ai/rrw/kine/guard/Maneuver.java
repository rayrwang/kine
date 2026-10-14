package ai.rrw.kine.guard;

import ai.rrw.kine.move.MoveState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * A rescue: a short program of phases, each a look held a set number of degrees off the track (the horizontal
 * velocity's heading) at a set pitch, then a glide along the track. Looking off the track is how an elytra turns
 * (the velocity swings a tenth of the way toward the look each tick, LivingEntity.updateFallFlyingMovement), so a
 * phase with an offset is a steady turn; one with none and the nose up is a flare, nose down a dive. A phase ends after
 * its ticks or, with a turn amount, once the track has come that far round since the rescue began. The look moves
 * toward each tick's target by at most {@link #MAX_TURN} degrees of mouse travel, a fast human flick, so every rotation
 * the server sees is one a hand could make. As a {@link Pilot} it steers by the state it is given each tick (closed
 * loop), in a rollout and in the game alike.
 */
public final class Maneuver implements Pilot {
    /** Look travel per tick, under the 30 degrees of check.py's R7 with room for whole-count rounding. */
    public static final double MAX_TURN = 28.0;
    /** Below this horizontal speed (blocks a tick) the track has no heading worth steering by: the look's is used. */
    static final double TRACK_MIN_SPEED = 0.05;

    /** One phase: look `offset` degrees right of the track (the yaw growing; negative left) at `pitch` (nose down
     *  positive), for at most `ticks`, or until the track has turned `turn` degrees the offset's way since the start
     *  (when turn > 0). */
    public record Phase(int ticks, double offset, double pitch, double turn) {
        @Override public String toString() {
            return String.format(Locale.ROOT, "%d:%.0f/%.0f%s", ticks, offset, pitch, turn > 0 ? "/" + Math.round(turn) : "");
        }
    }

    public final String name;
    public final Phase[] phases;
    public final double glidePitch;

    // where it is: the phase and its ticks, the track at the start and how far it has turned since
    private int phase = 0, inPhase = 0;
    private boolean started = false;
    private double lastTrack, turned;

    public Maneuver(String name, double glidePitch, Phase... phases) {
        this.name = name;
        this.phases = phases;
        this.glidePitch = glidePitch;
    }

    /** The same program from its start. */
    public Maneuver fresh() { return new Maneuver(name, glidePitch, phases); }

    @Override
    public Maneuver copy() {
        Maneuver m = new Maneuver(name, glidePitch, phases);
        m.phase = phase; m.inPhase = inPhase; m.started = started; m.lastTrack = lastTrack; m.turned = turned;
        return m;
    }

    /** Past its phases: gliding along the track. */
    public boolean gliding() { return phase >= phases.length; }

    /** Degrees the track has turned since the start (positive right). */
    public double turned() { return turned; }

    @Override
    public void look(MoveState s, float yaw, float pitch, float[] out) {
        double speed = Math.sqrt(s.vx * s.vx + s.vz * s.vz);
        double track = speed > TRACK_MIN_SPEED ? Pilot.trackYaw(s.vx, s.vz) : yaw;
        if (!started) { started = true; lastTrack = track; turned = 0.0; }
        else { turned += Pilot.wrap(track - lastTrack); lastTrack = track; }
        while (phase < phases.length) {
            Phase p = phases[phase];
            boolean done = inPhase >= p.ticks()
                || p.turn() > 0.0 && p.offset() != 0.0 && Math.signum(p.offset()) * turned >= p.turn();
            if (!done) break;
            phase++; inPhase = 0;
        }
        double toYaw, toPitch;
        if (phase < phases.length) {
            Phase p = phases[phase];
            toYaw = track + p.offset(); toPitch = p.pitch();
            inPhase++;
        } else {
            toYaw = track; toPitch = glidePitch;
        }
        Pilot.toward(yaw, pitch, toYaw, toPitch, MAX_TURN, out);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(name).append(" [");
        for (int i = 0; i < phases.length; i++) b.append(i > 0 ? " " : "").append(phases[i]);
        return b.append(String.format(Locale.ROOT, "] glide %.0f", glidePitch)).toString();
    }

    // ---- the named moves the search starts from (RW 2026-10-14 00:17:30: turn back 180, flare, dive then pull out,
    // turns of other angles; the search finds what is best) ----

    /** The starting points: turn back 180 either way (a steady bank, a hard one, one diving to keep speed, one
     *  climbing), turns of 45, 90 and 135, the shapes fed back from the search, a flare (nose up) soft and hard, a dive
     *  to regain speed then a pull out, flare and turn together, and levelling off. */
    public static List<Maneuver> seeds() {
        List<Maneuver> l = new ArrayList<>();
        for (int side = -1; side <= 1; side += 2) {
            String sd = side < 0 ? "L" : "R";
            l.add(new Maneuver("back" + sd, 5, new Phase(90, side * 45, 0, 165)));
            l.add(new Maneuver("backHard" + sd, 5, new Phase(90, side * 70, 5, 165)));
            l.add(new Maneuver("backDive" + sd, 5, new Phase(90, side * 45, 20, 165)));
            l.add(new Maneuver("backClimb" + sd, 5, new Phase(90, side * 45, -15, 165)));
            l.add(new Maneuver("turn45" + sd, 5, new Phase(60, side * 30, 0, 45)));
            l.add(new Maneuver("turn90" + sd, 5, new Phase(60, side * 40, 0, 90)));
            l.add(new Maneuver("turn135" + sd, 5, new Phase(80, side * 45, 0, 135)));
            l.add(new Maneuver("flareTurn" + sd, 5, new Phase(60, side * 45, -25, 90)));
        }
        // fed back (RW 00:18:28): the rescues a long search found better than the named moves at the firing states of the
        // offline crash bank (GuardBank, KINE_BANK=seeds, 2026-10-14), each with its mirror image. Mostly climbing
        // turns back with the nose well up, which trade the speed for height while coming round; on held-out scenes
        // they cut the best starting point's cost by 10% (0.538 to 0.483), by 2% after the search's variations.
        double[][] fed = {
            {8.2, 79, 29.1, -59.9, 170.9}, {3.8, 73, 27.8, -56.9, 180.1}, {0.3, 96, 66.6, 75.7, 131.1},
            {1.2, 83, 16.4, 30.4, 174.4}, {16.0, 110, 29.3, -52.2, 173.8}, {6.6, 118, 135.0, -56.1, 105.7},
            {-0.6, 114, 135.0, 79.5, 105.8}, {16.7, 118, 64.2, 32.1, 164.5},
        };
        for (int i = 0; i < fed.length; i++)
            for (int side = -1; side <= 1; side += 2) {
                double[] f = fed[i];
                l.add(new Maneuver("fed" + (i + 1) + (side < 0 ? "L" : "R"), f[0], new Phase((int) f[1], side * f[2], f[3], f[4])));
            }
        l.add(new Maneuver("flare", 5, new Phase(10, 0, -35, 0), new Phase(15, 0, -5, 0)));
        l.add(new Maneuver("flareHard", 5, new Phase(8, 0, -60, 0), new Phase(20, 0, 0, 0)));
        l.add(new Maneuver("divePull", 5, new Phase(12, 0, 45, 0), new Phase(12, 0, -30, 0)));
        l.add(new Maneuver("divePullLong", 5, new Phase(25, 0, 60, 0), new Phase(15, 0, -35, 0)));
        l.add(new Maneuver("level", 3, new Phase(20, 0, 0, 0)));
        return l;
    }

    /** A variation for the search: each number moved by up to `scale` of its usual range, the side kept. */
    public Maneuver mutate(SplittableRandom r, double scale) {
        Phase[] ps = new Phase[phases.length];
        for (int i = 0; i < phases.length; i++) {
            Phase p = phases[i];
            int ticks = Math.max(2, Math.min(120, p.ticks() + (int) Math.round((r.nextDouble() * 2 - 1) * 20 * scale)));
            double off = p.offset();
            if (off != 0.0 || r.nextDouble() < 0.2 * scale) {
                double side = off != 0.0 ? Math.signum(off) : (r.nextBoolean() ? 1 : -1);
                off = side * Math.max(5.0, Math.min(135.0, Math.abs(off) + (r.nextDouble() * 2 - 1) * 30 * scale));
            }
            double pitch = Math.max(-80.0, Math.min(80.0, p.pitch() + (r.nextDouble() * 2 - 1) * 30 * scale));
            double turn = p.turn() > 0.0 ? Math.max(20.0, Math.min(200.0, p.turn() + (r.nextDouble() * 2 - 1) * 40 * scale)) : 0.0;
            ps[i] = new Phase(ticks, off, pitch, turn);
        }
        double glide = Math.max(-10.0, Math.min(20.0, glidePitch + (r.nextDouble() * 2 - 1) * 8 * scale));   // inside the hand-back band
        return new Maneuver(name.endsWith("*") ? name : name + "*", glide, ps);
    }
}
