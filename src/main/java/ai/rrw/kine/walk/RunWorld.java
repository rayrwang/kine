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
    int lastKeys;
    boolean arrived;
    int ticks;
    /** The first tick's heading, pitch and keys (what the executor does now if this run is chosen). */
    float firstYaw = Float.NaN, firstPitch;
    int firstKeys;
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
        if (trail != null) { w.trail = trail.clone(); w.trailN = trailN; }
        return w;
    }

    void record() {
        if (trail == null) return;
        if (trailN + 3 > trail.length) trail = java.util.Arrays.copyOf(trail, trail.length * 2);
        trail[trailN++] = (float) s.x; trail[trailN++] = (float) s.y; trail[trailN++] = (float) s.z;
    }
}
