package ai.rrw.kine.fight;

import ai.rrw.kine.move.GameMath;
import ai.rrw.kine.move.MoveState;

/**
 * The combat movement's actions and how one becomes keys. An action is a way to go in the world and a gait: one of 16
 * compass headings, or straight away from the nearest mob, or away from the middle of the crowd; walking, sprinting or
 * sprint-jumping; or standing still. The keys are the movement keys whose direction, at the rotation the server gets
 * this tick (the kill aura's when it is on), is nearest the heading: one of the eight W/A/S/D directions, so up to
 * 22.5 degrees off. Sprinting needs W among them (LocalPlayer.canStartSprinting reads a forward impulse), so a heading
 * more than 67.5 degrees off the rotation is walked. The simulation and the executor both use {@link #keys}, so the
 * plan is scored on what will really be pressed.
 */
public final class Moves {
    private Moves() {}

    public static final int HEADINGS = 16;
    public static final int AWAY_NEAREST = 16, AWAY_CROWD = 17;
    public static final int DIRECTIONS = 18;
    public static final int WALK = 0, SPRINT = 1, SPRINT_JUMP = 2, GAITS = 3;
    /** The last action: no keys. */
    public static final int HOLD = DIRECTIONS * GAITS;
    public static final int COUNT = HOLD + 1;

    public static int direction(int action) { return action == HOLD ? -1 : action / GAITS; }
    public static int gait(int action) { return action == HOLD ? -1 : action % GAITS; }
    public static int action(int direction, int gait) { return direction * GAITS + gait; }

    /** The world yaw of a compass heading (Minecraft yaw: 0 = +z south, 90 = -x west). */
    public static float headingYaw(int h) {
        return h * (360.0F / HEADINGS);
    }

    // the eight key combinations and the yaw each walks at, relative to the rotation (A is -90, D is +90)
    private static final int[] COMBO = {
        MoveState.FORWARD, MoveState.FORWARD | MoveState.RIGHT, MoveState.RIGHT, MoveState.BACKWARD | MoveState.RIGHT,
        MoveState.BACKWARD, MoveState.BACKWARD | MoveState.LEFT, MoveState.LEFT, MoveState.FORWARD | MoveState.LEFT};
    private static final float[] COMBO_YAW = {0, 45, 90, 135, 180, -135, -90, -45};

    /** The keys that go nearest the world yaw `want` at the rotation `yaw`, in this gait. */
    public static int keys(float want, float yaw, int gait) {
        float rel = GameMath.wrapDegrees(want - yaw);
        int best = 0;
        float bestErr = Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            float err = Math.abs(GameMath.wrapDegrees(rel - COMBO_YAW[i]));
            if (err < bestErr) { bestErr = err; best = i; }
        }
        int k = COMBO[best];
        if (gait != WALK && (k & MoveState.FORWARD) != 0) {
            k |= MoveState.SPRINT;
            if (gait == SPRINT_JUMP) k |= MoveState.JUMP;
        }
        return k;
    }

    /** The world yaw of the keys at this rotation (where they really walk), or NaN for none. */
    public static float walkYaw(int keys, float yaw) {
        double x = ((keys & MoveState.LEFT) != 0 ? 1 : 0) - ((keys & MoveState.RIGHT) != 0 ? 1 : 0);
        double z = ((keys & MoveState.FORWARD) != 0 ? 1 : 0) - ((keys & MoveState.BACKWARD) != 0 ? 1 : 0);
        if (x == 0 && z == 0) return Float.NaN;
        double rel = Math.toDegrees(Math.atan2(-x, z));   // A (x = 1) walks at -90
        return GameMath.wrapDegrees((float) (yaw + rel));
    }

    public static String name(int action) {
        if (action == HOLD) return "hold";
        int d = direction(action), g = gait(action);
        String dir = d == AWAY_NEAREST ? "away-nearest" : d == AWAY_CROWD ? "away-crowd" : "h" + (int) headingYaw(d);
        return dir + (g == WALK ? "-walk" : g == SPRINT ? "-sprint" : "-sprintjump");
    }
}
