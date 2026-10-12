package ai.rrw.kine.hud;

/**
 * Where the HUD's text goes, in one place. Around the crosshair: the flight director's bars travel a
 * quarter of the screen height either way; just above that travel is the warning row (TOO LOW,
 * INSUFFICIENT DURABILITY), a line below it a short notice (LAST ELYTRA), and above it two annunciator
 * rows: the nav block in the middle, the vertical mode to its left and AP to its right. The big
 * warnings start a third of the way down. Above the hotbar, from the top: speed lines, range and
 * endurance, radio altimeter.
 */
public final class HudLayout {
    private HudLayout() {}

    /** How far the flight director's bars may move from the crosshair (pixels). */
    public static int directorTravel(int h) { return (int) (h * 0.25f); }

    /** The warning row, just above the bars' travel. */
    public static int warningRow(int h, int lineHeight) { return h / 2 - directorTravel(h) - lineHeight - 6; }

    /** A short notice in the warning row's style, a line below it so it never covers the row (LAST ELYTRA). */
    public static int noticeRow(int h, int lineHeight) { return warningRow(h, lineHeight) + lineHeight + 2; }

    /** The lower annunciator row (DIST and ETA, the target altitude). */
    public static int annunciatorLower(int h, int lineHeight) { return warningRow(h, lineHeight) - lineHeight - 2; }

    /** The upper annunciator row (HDG or COORD, the vertical mode, AP). */
    public static int annunciatorUpper(int h, int lineHeight) { return annunciatorLower(h, lineHeight) - lineHeight - 2; }

    private static final int SIDE_GAP = 100;   // fixed offset from the centre for the side annunciators

    /** Right edge of the vertical mode block: a fixed offset left of centre, so it doesn't slide as the
     *  nav block's width changes; further left only if a wide nav line needs the room. */
    public static int modeRightEdge(int cx, int halfNav) { return Math.min(cx - SIDE_GAP, cx - halfNav - 12); }

    /** Left edge of AP, mirroring the vertical mode block. */
    public static int apLeftEdge(int cx, int halfNav) { return Math.max(cx + SIDE_GAP, cx + halfNav + 12); }

    /** Top of the big warnings (AUTOPILOT OFF, LOW DURABILITY). */
    public static int bigWarningTop(int h) { return h / 3; }

    /** The mining readout, just below the crosshair. */
    public static int miningRow(int h) { return h / 2 + 12; }

    /** Top of the vanilla health and hunger row; the speed lines sit above it. */
    public static int healthBarsTop(int h) { return h - 39; }

    /** The radio altimeter, right above the health and hunger row. */
    public static int radioAltimeterRow(int h) { return h - 50; }

    /** Range and endurance, between the radio altimeter and the speed lines. */
    public static int rangeEnduranceRow(int h) { return h - 60; }

    /** Half the hotbar's width: range and endurance sit this far either side of centre. */
    public static final int HOTBAR_HALF = 91;
}
