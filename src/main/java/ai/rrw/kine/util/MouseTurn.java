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

    /** A turn made, in counts, so it can be undone exactly as a vanilla client would turn back. */
    public record Turn(int countsX, int countsY) {
        public static final Turn NONE = new Turn(0, 0);
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
        int kx = (int) Math.round(Mth.wrapDegrees(yaw - p.getYRot()) / deg);
        int ky = (int) Math.round((Mth.clamp(pitch, -90f, 90f) - p.getXRot()) / deg);
        // stay inside the pitch limits, where the game would clamp the turn and undo() couldn't return exactly
        if (p.getXRot() + ky * deg > 90.0) ky--;
        if (p.getXRot() + ky * deg < -90.0) ky++;
        if (kx == 0 && ky == 0) return Turn.NONE;
        p.turn(kx * sens, ky * sens);
        return new Turn(kx, ky);
    }

    /** Turn back by the same counts. */
    public static void undo(LocalPlayer p, Turn t) {
        if (t.countsX() == 0 && t.countsY() == 0) return;
        double sens = sens(Minecraft.getInstance(), p);
        p.turn(-t.countsX() * sens, -t.countsY() * sens);
    }
}
