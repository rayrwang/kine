package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;

/**
 * One simulated run of layer 2: the player's movement state (the exact copy's), the rotation sent to the server (which
 * turns toward the action's heading by at most a human's 30 degrees a tick), the heading and pitch the current action
 * fixed when it started, and what the run has done: arrived, the ticks it took, where it went (for the display).
 */
final class RunWorld {
    final MoveState s;
    float sentYaw, sentPitch;
    /** The heading and pitch of the action held now, fixed at its first tick (NaN before any). */
    float segYaw = Float.NaN, segPitch = 0f;
    /** A free action's movement heading (its view may turn 45 degrees off it for a strafe); NaN for the others. */
    float segHeading = Float.NaN, firstHeading = Float.NaN;
    int lastKeys;
    boolean arrived;
    int ticks;
    /** The first tick's heading, pitch and keys (what the executor does now if this run is chosen). */
    float firstYaw = Float.NaN, firstPitch;
    int firstKeys;
    /** Parkour off (RunModel.gapCheck): the height it last left the ground from, and the lowest floor under it since
     *  that it couldn't walk back out of (NaN for none). */
    double takeoffY = Double.NaN, gapFloor = Double.NaN, gapValue = Double.NaN;
    /** The gait setting's Walk and Sprint (RunModel.paceCheck): the height a counted jump left the ground from (NaN:
     *  none in the air now), and 1 once the ground under it fell away more than a jump's height (a gap: the jump was
     *  needed). */
    double paceFrom = Double.NaN, paceDeep = 0;
    /** The feet each tick, for the display (null unless recorded). */
    float[] trail;
    int trailN;

    RunWorld(MoveState s, float sentYaw, float sentPitch) {
        this.s = s;
        this.sentYaw = sentYaw;
        this.sentPitch = sentPitch;
    }

    RunWorld copy() {
        RunWorld w = new RunWorld(s.copy(), sentYaw, sentPitch);
        w.segYaw = segYaw; w.segPitch = segPitch; w.lastKeys = lastKeys; w.arrived = arrived; w.ticks = ticks;
        w.firstYaw = firstYaw; w.firstPitch = firstPitch; w.firstKeys = firstKeys;
        w.segHeading = segHeading; w.firstHeading = firstHeading;
        w.takeoffY = takeoffY; w.gapFloor = gapFloor; w.gapValue = gapValue;
        w.paceFrom = paceFrom; w.paceDeep = paceDeep;
        if (trail != null) { w.trail = trail.clone(); w.trailN = trailN; }
        return w;
    }

    void record() {
        if (trail == null) return;
        if (trailN + 3 > trail.length) trail = java.util.Arrays.copyOf(trail, trail.length * 2);
        trail[trailN++] = (float) s.x; trail[trailN++] = (float) s.y; trail[trailN++] = (float) s.z;
    }
}
