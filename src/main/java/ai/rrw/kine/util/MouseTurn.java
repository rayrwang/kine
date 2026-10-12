package ai.rrw.kine.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Turns the player as the mouse does: by a whole number of mouse counts on each axis, at the current
 * sensitivity. MouseHandler.turnPlayer scales a count by {@code (s*0.6 + 0.2)^3 * 8} (without the 8 while
 * scoping) and Entity.turn by 0.15 more, so at the default sensitivity a count is 0.15 degrees. A turn here
 * goes through Entity.turn with the same numbers, so every rotation the server receives is one a vanilla
 * client could send from the last one.
 */
public final class MouseTurn {
    private MouseTurn() {}

    /** A turn made: its counts, and how far it moved the view (degrees), which is less than the counts ask for
     *  when the game clamps the turn (a boat holds its passenger within 105 degrees of its heading). */
    public record Turn(int countsX, int countsY, float movedYaw, float movedPitch) {
        public static final Turn NONE = new Turn(0, 0, 0f, 0f);
    }

    /** The turn per mouse count before Entity.turn's 0.15, as MouseHandler.turnPlayer computes it. */
    public static double sens(Minecraft mc, LocalPlayer p) {
        double ss = mc.options.sensitivity().get() * 0.6F + 0.2F;
        double mod = ss * ss * ss;
        boolean scoping = mc.options.getCameraType().isFirstPerson() && p.isScoping();
        return scoping ? mod : mod * 8.0;
    }

    /** Turn toward (yaw, pitch) by the nearest whole number of counts on each axis. */
    public static Turn toward(LocalPlayer p, float yaw, float pitch) {
        double sens = sens(Minecraft.getInstance(), p);
        double deg = sens * 0.15;
        int kx = yawCounts(Mth.wrapDegrees(yaw - p.getYRot()), deg);
        int ky = pitchCounts(Mth.clamp(pitch, -90f, 90f) - p.getXRot(), p.getXRot(), deg);
        if (kx == 0 && ky == 0) return Turn.NONE;
        float yaw0 = p.getYRot(), pitch0 = p.getXRot();
        p.turn(kx * sens, ky * sens);
        return new Turn(kx, ky, p.getYRot() - yaw0, p.getXRot() - pitch0);
    }

    /** Turn back by as far as the turn moved the view, in whole counts at the sensitivity now. The view returns to
     *  where it was (within half a count) even when a boat's clamp cut the turn short, or a spyglass started since
     *  has made a count smaller, where turning back by the same counts would leave it moved; a turn the game made
     *  in between (a boat turning its passenger) stays. */
    public static void undo(LocalPlayer p, Turn t) {
        if (t.movedYaw() == 0f && t.movedPitch() == 0f) return;
        double sens = sens(Minecraft.getInstance(), p);
        double deg = sens * 0.15;
        int kx = yawCounts(-t.movedYaw(), deg);
        int ky = pitchCounts(-t.movedPitch(), p.getXRot(), deg);
        if (kx != 0 || ky != 0) p.turn(kx * sens, ky * sens);
    }

    /** Turn by whole counts on each axis at the current sensitivity, the pitch counts kept inside the pitch limits. */
    public static Turn by(LocalPlayer p, int countsX, int countsY) {
        double sens = sens(Minecraft.getInstance(), p);
        int ky = pitchCounts(countsY * sens * 0.15, p.getXRot(), sens * 0.15);
        if (countsX == 0 && ky == 0) return Turn.NONE;
        float yaw0 = p.getYRot(), pitch0 = p.getXRot();
        p.turn(countsX * sens, ky * sens);
        return new Turn(countsX, ky, p.getYRot() - yaw0, p.getXRot() - pitch0);
    }

    /** The whole counts (yaw, pitch) nearest a turn from the rotation now to (yaw, pitch). */
    public static int[] countsTo(LocalPlayer p, float yaw, float pitch) {
        double deg = sens(Minecraft.getInstance(), p) * 0.15;
        return new int[]{yawCounts(Mth.wrapDegrees(yaw - p.getYRot()), deg),
                         pitchCounts(Mth.clamp(pitch, -90f, 90f) - p.getXRot(), p.getXRot(), deg)};
    }

    /** Mouse travel between two rotations, in degrees: sqrt(yaw^2 + pitch^2), the yaw the short way round. */
    public static double travel(double yaw0, double pitch0, double yaw1, double pitch1) {
        return Math.hypot(Mth.wrapDegrees(yaw1 - yaw0), pitch1 - pitch0);
    }

    /** The rotation reached going from (fromYaw, fromPitch) toward (toYaw, toPitch) by at most maxTurn degrees of mouse
     *  travel, along the straight line between them. */
    public static float[] limit(float fromYaw, float fromPitch, float toYaw, float toPitch, double maxTurn) {
        double dy = Mth.wrapDegrees(toYaw - fromYaw), dp = toPitch - fromPitch;
        double len = Math.hypot(dy, dp);
        if (len <= maxTurn) return new float[]{fromYaw + (float) dy, toPitch};
        double f = maxTurn / len;
        return new float[]{fromYaw + (float) (dy * f), fromPitch + (float) (dp * f)};
    }

    /** The whole turns (a multiple of 360 degrees) that Mth.wrapDegrees takes off a yaw offset beyond half a turn. */
    public static float wholeTurns(float yaw) {
        return Math.round((yaw - Mth.wrapDegrees(yaw)) / 360.0F) * 360.0F;
    }

    /** The whole counts nearest a yaw turn of dYaw degrees, at deg degrees a count. */
    static int yawCounts(double dYaw, double deg) {
        return (int) Math.round(dYaw / deg);
    }

    /** The whole counts nearest a pitch turn of dPitch degrees from pitchNow, at deg degrees a count, kept inside the
     *  pitch limits, where the game would clamp the turn and it couldn't be turned back exactly. */
    static int pitchCounts(double dPitch, float pitchNow, double deg) {
        int ky = (int) Math.round(dPitch / deg);
        if (pitchNow + ky * deg > 90.0) ky--;
        if (pitchNow + ky * deg < -90.0) ky++;
        return ky;
    }
}
