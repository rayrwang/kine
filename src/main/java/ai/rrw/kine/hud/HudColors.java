package ai.rrw.kine.hud;

/** HUD colours (ARGB), one definition each. */
public final class HudColors {
    private HudColors() {}

    public static final int GREEN   = 0xFF44FF44;   // nav readouts, altitude, ALT, AP engaged
    public static final int RED     = 0xFFFF3030;   // warnings, AP disengaged
    public static final int AMBER   = 0xFFFFC400;   // radio altimeter, range, endurance in its last minute, LAST ELYTRA, CLB and DESC
    public static final int YELLOW  = 0xFFFFEE00;   // ALT* (capturing the target altitude)
    public static final int WHITE   = 0xFFFFFFFF;
    public static final int MAGENTA = 0xFFFF00FF;   // flight director bars
    public static final int CYAN    = 0xFF3CE6FF;   // the walk: WALK, and its destination, distance and ETA

    /** The big flashing warnings alternate between red and amber every 400 ms. */
    public static int warningFlash() {
        boolean flash = (System.currentTimeMillis() / 400) % 2 == 0;
        return flash ? 0xFFFF2020 : 0xFFFFD000;
    }
}
