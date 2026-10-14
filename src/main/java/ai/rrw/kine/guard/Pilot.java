package ai.rrw.kine.guard;

import ai.rrw.kine.move.MoveState;

/**
 * Whoever sets the look each tick in a rollout: the pilot's hands held still, the autopilot's laws, the rocket takeoff,
 * or a rescue. Each tick {@link #look} gives the look for the coming tick's move from the state before it (the game
 * turns the view at the start of a tick, then moves the player), {@link #boost} whether a rocket pushes after that
 * move, and {@link #after} sees the state the move left (where a law reads its altitude and speed). Stateful, so a
 * rollout runs on a {@link #copy}.
 */
public interface Pilot {
    /** The look for the coming tick: out[0] yaw, out[1] pitch (degrees, the game's), from the state and the look now. */
    void look(MoveState s, float yaw, float pitch, float[] out);

    /** Whether a rocket pushes the player after this tick's move. */
    default boolean boost() { return false; }

    /** After the tick's move. */
    default void after(MoveState s) {}

    Pilot copy();

    /** Hands off: the look held as it is (a pilot who doesn't touch the mouse, or a frozen client). */
    Pilot HOLD = new Pilot() {
        @Override public void look(MoveState s, float yaw, float pitch, float[] out) { out[0] = yaw; out[1] = pitch; }
        @Override public Pilot copy() { return this; }
        @Override public String toString() { return "hold"; }
    };

    /** The look moved from (yaw, pitch) toward (toYaw, toPitch) by at most maxTurn degrees of mouse travel (the yaw the
     *  short way round), along the straight line between them, as a hand moving the mouse there would. */
    static void toward(float yaw, float pitch, double toYaw, double toPitch, double maxTurn, float[] out) {
        double dy = wrap(toYaw - yaw), dp = Math.max(-90.0, Math.min(90.0, toPitch)) - pitch;
        double len = Math.hypot(dy, dp);
        double f = len <= maxTurn ? 1.0 : maxTurn / len;
        out[0] = (float) (yaw + dy * f);
        out[1] = (float) (pitch + dp * f);
    }

    static double wrap(double a) {
        a %= 360.0;
        if (a >= 180.0) a -= 360.0;
        if (a < -180.0) a += 360.0;
        return a;
    }

    /** The yaw of a horizontal velocity (the game's: 0 is +z, 90 is -x). */
    static double trackYaw(double vx, double vz) { return Math.toDegrees(Math.atan2(-vx, vz)); }
}
