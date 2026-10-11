package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.autoflight.FlightDirector;
import ai.rrw.kine.autoflight.HoldModel;
import ai.rrw.kine.autoflight.Landing;
import ai.rrw.kine.autoflight.Nav;
import ai.rrw.kine.autoflight.Takeoff;
import ai.rrw.kine.util.Compass;
import ai.rrw.kine.util.KineTime;
import ai.rrw.kine.util.Readouts;
import ai.rrw.kine.util.Smoothing;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * The navigation display: the heading arc across the top, the annunciator rows above the crosshair
 * (the nav block with HDG or COORD and DIST and ETA, the vertical mode on its left, AP on its right),
 * the insufficient-durability warning, and the beam over a managed target. Reads Nav, FlightDirector
 * and RangeEndurance; changes nothing but its own ETA smoothing.
 */
public final class NavHud {
    private NavHud() {}

    private static final double ARC_HALF_DEG   = 24.0;       // the arc spans this far each side of the apex (circle deg)
    private static final double ARC_VIS_HDG    = 60.0;       // compass degrees shown each side of the current heading
    private static final int    ARC_BOX_TOP    = 4;          // gap from the top of the screen to the heading box
    private static final int    COLUMN_BODY    = 0x4044FF44; // translucent green beam
    private static final int    COLUMN_CORE    = 0x9044FF44; // brighter centre line
    private static final double ETA_TC         = 12.0;   // time constant (s) that smooths the porpoise wobble out of the ETA
    private static final float  BEAM_NEAR_W    = 0.05f;  // the beam is cut where it passes this close in front of the camera
    private static final float  BEAM_MARGIN    = 2.0f;   // and this many half-screens from the centre, well off screen

    private static double etaShown = Double.NaN;  // smoothed ETA on display (s)
    private static long   etaNanos = 0L;          // wall-clock time of the last ETA update

    public static void register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "nav"),
            NavHud::render);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int cx = W / 2, cy = H / 2;
        int lh = mc.font.lineHeight;

        // heading arc across the top, shown whatever the autopilot state
        if (Settings.shows(Settings.displayHeadingArc)) drawHeadingArc(g, mc, cx, cy);
        boolean readouts = Settings.shows(Settings.displayNavReadouts);

        int durY  = HudLayout.warningRow(H, lh);
        int etaY  = HudLayout.annunciatorLower(H, lh);
        int lineY = HudLayout.annunciatorUpper(H, lh);

        Nav.Mode mode = Nav.mode();
        boolean landing = Nav.landing();
        if (readouts && mode == Nav.Mode.MANAGED && Nav.hasTarget()) drawTargetColumn(g, mc, W, H);

        // the centred nav block shows unless nav is off or the too-low warning has the slot
        boolean navCenter = readouts && mode != Nav.Mode.OFF && !(FlightDirector.isTooLow() && !landing);

        String top = null, line2 = null;
        double dist = 0;
        if (navCenter) {
            top = (mode == Nav.Mode.SELECTED)
                ? (Nav.hasHeading() ? "HDG " + Readouts.heading(Math.round(Nav.selectedHeading())) : "HDG ---")
                : (Nav.hasTarget()  ? "COORD " + Nav.targetX() + " " + Nav.targetZ()               : "COORD --");
            if (mode == Nav.Mode.MANAGED && Nav.hasTarget()) {
                double hdx = (Nav.targetX() + 0.5) - p.getX(), hdz = (Nav.targetZ() + 0.5) - p.getZ();
                dist = Math.sqrt(hdx * hdx + hdz * hdz);
                line2 = landing ? "LANDING"
                    : Nav.holding() ? holdLine(mc)
                    : "DIST " + Readouts.distance(dist) + "    ETA " + Readouts.eta(smoothEta(dist / RangeEndurance.speedEstimate()));
            }
        }
        int halfCenter = (top == null) ? 0
            : Math.max(mc.font.width(top), line2 == null ? 0 : mc.font.width(line2)) / 2;

        // vertical mode annunciator: two rows left of the nav block, shown whenever the director is armed
        drawModeAnnunciator(g, mc, cx, lineY, etaY, halfCenter);

        // autopilot status on the right, mirroring the vertical mode block; shown whatever the autopilot state.
        // Green during a rocket takeoff too (RW): it is flying the aircraft and hands over to the autopilot.
        if (readouts && Settings.autopilot) {
            int apX = HudLayout.apLeftEdge(cx, halfCenter);
            boolean flying = Autopilot.isEngaged() || Takeoff.active();
            g.text(mc.font, "AP", apX, lineY, flying ? HudColors.GREEN : HudColors.RED, true);
        }

        if (!navCenter) return;

        g.text(mc.font, top, cx - mc.font.width(top) / 2, lineY, HudColors.GREEN, true);
        if (line2 == null) return;   // SELECTED, or no destination yet: no distance, ETA or range
        g.text(mc.font, line2, cx - mc.font.width(line2) / 2, etaY, HudColors.GREEN, true);

        // If the elytra can't take us that far, flag it on the warning row.
        double range = RangeEndurance.rangeMeters();
        if (!landing && range >= 0 && range < dist && !Takeoff.usesWarningRow() && !Landing.valid()) {
            String wd = "INSUFFICIENT DURABILITY";
            g.text(mc.font, wd, cx - mc.font.width(wd) / 2, durY, HudColors.RED, true);
        }
    }

    // Heading arc: a shallow arc of a large circle centred on the crosshair, near the top of the screen. The
    // compass card (ticks every 10 degrees, labels every 30) turns under a fixed apex, the current heading
    // shows boxed above the apex, and a green bug marks a selected heading, clamped to the arc's ends.
    private static void drawHeadingArc(GuiGraphicsExtractor g, Minecraft mc, int cx, int cy) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        Font font = mc.font;
        int lh = font.lineHeight;
        double hdg = Compass.heading(p.getYRot());

        int boxH  = lh + 2;
        int apexY = ARC_BOX_TOP + boxH + 4;
        double R  = cy - apexY;                     // circle centred on the crosshair; apex just under the box
        if (R < 20) return;
        double scale = ARC_HALF_DEG / ARC_VIS_HDG;  // circle-deg per compass-deg

        for (int h = 0; h < 360; h += 10) {
            double d = Mth.wrapDegrees((float) (h - hdg));
            if (Math.abs(d) > ARC_VIS_HDG) continue;
            double a = Math.toRadians(d * scale), sin = Math.sin(a), cos = Math.cos(a);
            boolean major = (h % 30 == 0);
            int tlen = major ? 7 : 4;
            int xo = cx + (int) Math.round(R * sin),          yo = cy - (int) Math.round(R * cos);
            int xi = cx + (int) Math.round((R - tlen) * sin), yi = cy - (int) Math.round((R - tlen) * cos);
            HudDraw.line(g, xi, yi, xo, yo, HudColors.WHITE);
            if (major) {
                String lbl = headingLabel(h);
                double lr = R - tlen - 2;                       // just inside the tick
                int lx = cx + (int) Math.round(lr * sin) - font.width(lbl) / 2;
                int ly = cy - (int) Math.round(lr * cos);       // hangs inboard (toward the centre), below the tick
                g.text(font, lbl, lx, ly, HudColors.WHITE, true);
            }
        }

        // current-heading readout, boxed, just above the apex
        String hs = Readouts.heading(((int) Math.round(hdg)) % 360);
        int bw = font.width(hs) + 8, bx = cx - bw / 2;
        g.fill(bx, ARC_BOX_TOP, bx + bw, ARC_BOX_TOP + boxH, 0x90000000);
        g.outline(bx, ARC_BOX_TOP, bw, boxH, HudColors.WHITE);   // outline takes width/height, not x2/y2
        g.text(font, hs, cx - font.width(hs) / 2, ARC_BOX_TOP + (boxH - lh) / 2 + 1, HudColors.WHITE, true);

        // green selected-heading bug, a small triangle on the arc, clamped to the visible edges
        if (Nav.mode() == Nav.Mode.SELECTED && Nav.hasHeading()) {
            double d = Math.max(-ARC_VIS_HDG, Math.min(ARC_VIS_HDG, Mth.wrapDegrees((float) (Nav.selectedHeading() - hdg))));
            double a = Math.toRadians(d * scale), sin = Math.sin(a), cos = Math.cos(a);
            int tx = cx + (int) Math.round(R * sin), ty = cy - (int) Math.round(R * cos);   // tip on the arc
            double bcx = cx + (R + 7) * sin, bcy = cy - (R + 7) * cos;                       // base centre, just outside
            double tgx = cos, tgy = sin;                                                     // tangent to the arc here
            int b1x = (int) Math.round(bcx - 4 * tgx), b1y = (int) Math.round(bcy - 4 * tgy);
            int b2x = (int) Math.round(bcx + 4 * tgx), b2y = (int) Math.round(bcy + 4 * tgy);
            HudDraw.line(g, tx, ty, b1x, b1y, HudColors.GREEN);
            HudDraw.line(g, tx, ty, b2x, b2y, HudColors.GREEN);
            HudDraw.line(g, b1x, b1y, b2x, b2y, HudColors.GREEN);
        }
    }

    private static String headingLabel(int h) {
        switch (h) {
            case 0:   return "N";
            case 90:  return "E";
            case 180: return "S";
            case 270: return "W";
            default:  return Integer.toString(h / 10);
        }
    }

    /** Vertical mode annunciator (CLB, DESC, ALT* or ALT over the target altitude), left of the nav block. */
    private static void drawModeAnnunciator(GuiGraphicsExtractor g, Minecraft mc, int cx, int lineY, int etaY, int halfCenter) {
        if (!Settings.shows(Settings.displayFlightDirectors) || !FlightDirector.isActive()) return;
        int vm = FlightDirector.verticalMode();
        String l1; int col;
        if      (vm == FlightDirector.VM_CLB)      { l1 = "CLB";  col = HudColors.AMBER;  }   // climbing to target
        else if (vm == FlightDirector.VM_DESC)     { l1 = "DESC"; col = HudColors.AMBER;  }   // descending to target
        else if (vm == FlightDirector.VM_ALT_STAR) { l1 = "ALT*"; col = HudColors.YELLOW; }   // capturing the target
        else                                       { l1 = "ALT";  col = HudColors.GREEN;  }   // holding target
        String l2 = Integer.toString(FlightDirector.targetAltitude());
        int blockW = Math.max(mc.font.width(l1), mc.font.width(l2));
        int x = HudLayout.modeRightEdge(cx, halfCenter) - blockW;
        g.text(mc.font, l1, x + (blockW - mc.font.width(l1)) / 2, lineY, col, true);
        g.text(mc.font, l2, x + (blockW - mc.font.width(l2)) / 2, etaY, col, true);
    }

    /** A beam over the target column from the ground to the top of the build height, and the landing block's top
     *  face outlined in green, projected to the screen and drawn as a HUD element, so they show through terrain
     *  like Baritone's goal beam. The ground is the landing spot's surface, or else the column's top where the
     *  client has its chunk; with neither, the beam runs from the bottom of the world and no face is drawn. Only
     *  the part in view is drawn. */
    private static void drawTargetColumn(GuiGraphicsExtractor g, Minecraft mc, int W, int H) {
        if (mc.level == null) return;
        // while landing on a known spot (a safe column found near the target, or the aimed one), the beam stands on it
        boolean spot = Nav.hasSpot();
        int bx = spot ? Mth.floor(Nav.spotX()) : Nav.targetX(), bz = spot ? Mth.floor(Nav.spotZ()) : Nav.targetZ();
        double ground = spot ? Nav.spotTop() : columnTop(mc.level, bx, bz);
        double baseY = Double.isNaN(ground) ? mc.level.getMinY() : ground, topY = mc.level.getMaxY() + 1;
        Camera cam = mc.gameRenderer.getMainCamera();
        Matrix4f vp = cam.getViewRotationProjectionMatrix(new Matrix4f());
        Vec3 c = cam.position();
        double wx = bx + 0.5, wz = bz + 0.5;
        if (!Double.isNaN(ground)) drawTopFace(g, vp, c, bx, ground, bz, W, H);

        Vector4f[] seg = clipSegment(ScreenProjection.clip(vp, c, wx, baseY, wz), ScreenProjection.clip(vp, c, wx, topY, wz));
        if (seg == null) return;   // no part of the beam in view
        float[] base = ScreenProjection.toScreen(seg[0], W, H);
        float[] top  = ScreenProjection.toScreen(seg[1], W, H);

        double ddx = wx - c.x, ddz = wz - c.z;
        double dist = Math.sqrt(ddx * ddx + ddz * ddz);
        int width = (int) Math.max(2, Math.min(16, 600.0 / Math.max(1.0, dist)));
        HudDraw.alongSegment(g, base[0], base[1], top[0], top[1], 1f, hl -> {
            g.fill(-hl, -width / 2, hl, width / 2, COLUMN_BODY);
            g.fill(-hl, -1, hl, 1, COLUMN_CORE);
        });
    }

    // The top of a column where the client has its chunk (the MOTION_BLOCKING heightmap, as the landing spot search
    // reads it), else NaN.
    private static double columnTop(ClientLevel level, int x, int z) {
        if (!level.hasChunkAt(new BlockPos(x, level.getMinY(), z))) return Double.NaN;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    // The block's top face at height y, its four edges in green.
    private static void drawTopFace(GuiGraphicsExtractor g, Matrix4f vp, Vec3 c, int x, double y, int z, int W, int H) {
        double[][] k = {{x, z}, {x + 1, z}, {x + 1, z + 1}, {x, z + 1}};
        for (int i = 0; i < 4; i++) {
            double[] a = k[i], b = k[(i + 1) % 4];
            Vector4f[] seg = clipSegment(ScreenProjection.clip(vp, c, a[0], y, a[1]), ScreenProjection.clip(vp, c, b[0], y, b[1]));
            if (seg == null) continue;
            float[] p0 = ScreenProjection.toScreen(seg[0], W, H), p1 = ScreenProjection.toScreen(seg[1], W, H);
            HudDraw.alongSegment(g, p0[0], p0[1], p1[0], p1[1], 0f, hl -> g.fill(-hl - 1, -1, hl + 1, 1, HudColors.GREEN));
        }
    }

    /**
     * The part of the segment a-b (clip space, before the perspective divide) in front of the camera and within
     * BEAM_MARGIN half-screens of the centre, as its two ends in clip space, or null if no part is. Cut here, a
     * beam with one end behind the camera still shows (close to the target and in the landing spiral the top end
     * is), and its ends stay at sane pixel coordinates.
     */
    static Vector4f[] clipSegment(Vector4f a, Vector4f b) {
        float t0 = 0f, t1 = 1f;
        for (int i = 0; i < 5; i++) {   // each plane as a linear function of the point, at least 0 inside
            float da = inside(i, a), db = inside(i, b);
            if (da < 0 && db < 0) return null;
            if (da < 0) t0 = Math.max(t0, da / (da - db));
            else if (db < 0) t1 = Math.min(t1, da / (da - db));
        }
        if (t0 > t1) return null;
        return new Vector4f[]{ new Vector4f(a).lerp(b, t0), new Vector4f(a).lerp(b, t1) };
    }

    private static float inside(int plane, Vector4f v) {
        return switch (plane) {
            case 0 -> v.w - BEAM_NEAR_W;
            case 1 -> BEAM_MARGIN * v.w + v.x;
            case 2 -> BEAM_MARGIN * v.w - v.x;
            case 3 -> BEAM_MARGIN * v.w + v.y;
            default -> BEAM_MARGIN * v.w - v.y;
        };
    }

    // While circling over the destination: HOLDING, and for a hold till day when it will land: the time until surface
    // spawning stops at dawn (an estimate from the clock and the rain; the landing itself waits for the live light),
    // or STORM while a thunderstorm keeps it dark enough all day.
    private static String holdLine(Minecraft mc) {
        if (Nav.arrival() != Nav.Arrival.HOLD_TILL_DAY || mc.level == null) return "HOLDING";
        if (mc.level.isThundering()) return "HOLDING, STORM";
        int ticks = HoldModel.ticksToDawn(mc.level.getOverworldClockTime(), mc.level.getRainLevel(1.0F));
        return "HOLDING, LAND IN " + KineTime.format(ticks / 20.0);
    }

    /** Forget the smoothed ETA, so a new destination starts fresh instead of easing from the old one. */
    public static void resetEta() { etaShown = Double.NaN; etaNanos = 0L; }

    /**
     * Smooths the displayed ETA. The raw figure (distance / cruise speed) is right on average but counts
     * down unevenly, since ground speed surges in the dive and drops at the top of each porpoise. A
     * complementary filter counts the real clock down at one second per second, then drifts toward the
     * raw value with time constant ETA_TC. It snaps on a big jump (new target, teleport) or after a
     * render gap of over a second (a screen open, F1).
     */
    private static double smoothEta(double raw) {
        long now = System.nanoTime();
        double dt = (etaNanos == 0L) ? 0.0 : (now - etaNanos) / 1.0e9;
        etaNanos = now;
        if (Double.isNaN(etaShown) || dt <= 0 || dt > 1.0 || Math.abs(raw - etaShown) > Math.max(30.0, raw * 0.5)) {
            etaShown = raw;
        } else {
            etaShown = Math.max(0.0, etaShown - dt);                  // real-clock countdown
            etaShown = Smoothing.toward(etaShown, raw, dt, ETA_TC);
        }
        return etaShown;
    }
}
