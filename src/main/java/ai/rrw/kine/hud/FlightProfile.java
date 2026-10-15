package ai.rrw.kine.hud;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.Gui2D;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.autoflight.PathPrediction;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;

/**
 * Side-on flight profile, a small cutaway in the top-right corner: the blocks ahead (map colours, sky
 * blue for open air, a darker fill for air below the surface so caves read as caves), and while the
 * autopilot is engaged a dark navy trace of its predicted path ({@link PathPrediction}, as the rails
 * show it). While engaged the cutaway follows that path, turns included, unrolled flat; otherwise it
 * looks straight along the ground track. The terrain shows whenever you are gliding. Instrument only.
 *
 * <p>The horizontal axis spans the render distance and the vertical axis uses the same scale, so the
 * picture is undistorted; the window slides to keep you 62% of the way up. Distance ticks run along
 * the bottom, altitude ticks up the left.
 */
public final class FlightProfile {
    private FlightProfile() {}

    // geometry (GUI-scaled pixels, one pixel per cell)
    private static final int    COLS    = 120;
    private static final int    ROWS    = 120;
    private static final int    PLOT_W  = COLS;
    private static final int    PLOT_H  = ROWS;
    private static final double TOP_FRAC = 0.382;                // you sit 38.2% down from the top
    private static final int    PLAYER_ROW = (int) Math.round(TOP_FRAC * ROWS);

    private static final int PAD_L = 22, PAD_T = 2, PAD_B = 11, MARGIN_PX = 6;

    private static final int RECOMPUTE = 6;

    // colours (ARGB unless noted)
    private static final int AIR      = 0xFF8FB8E0;
    private static final int CAVE     = 0xFF1B2330;
    private static final int UNKNOWN  = 0xFF454B52;
    private static final int NO_COLOR = 0x6B6B6B;                // rgb only
    private static final int PATH_RGB = 0x0A1F70;                // dark navy (rgb; alpha from AA coverage)
    private static final int PLAYER   = 0xFFFFD23F;
    private static final int LABEL    = 0xFFC8D0D8;

    private static boolean  have = false;
    private static int[]    cells;                               // COLS*ROWS, index c*ROWS + r, r = 0 at the window top
    private static double[] pathY;                               // per column: plot-space row of the trace (float)
    private static boolean[] hasPath;
    private static double   winTop, winBottom;
    private static double   rangeX = 240.0;                      // horizontal span (blocks), the render distance
    private static int      ticks = 0;

    public static void register() {
        Compat.hud("flight_profile", FlightProfile::render);
        ClientTickEvents.END_CLIENT_TICK.register(FlightProfile::tick);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !Settings.shows(Settings.displayFlightProfile) || !p.isFallFlying()) {
            have = false; return;
        }
        if (cells == null) { cells = new int[COLS * ROWS]; pathY = new double[COLS]; hasPath = new boolean[COLS]; }
        if (have && (++ticks % RECOMPUTE) != 0) return;
        compute(mc, p);
    }

    private static void compute(Minecraft mc, LocalPlayer p) {
        ClientLevel level = mc.level;
        double pX = p.getX(), pY = p.getY(), pZ = p.getZ();

        // X spans the render distance; Y uses the same blocks per pixel (square cells).
        rangeX = Math.max(32, mc.options.getEffectiveRenderDistance() * 16);
        double bpp = rangeX / PLOT_W;
        double span = PLOT_H * bpp;
        winTop = pY + TOP_FRAC * span;          // slide the window to keep you at TOP_FRAC
        winBottom = winTop - span;

        // ground-track unit vector, the direction the cutaway looks along when there is no prediction
        double dx = pX - p.xOld, dz = pZ - p.zOld, len = Math.hypot(dx, dz);
        double trackX, trackZ;
        if (len > 1.0e-4) { trackX = dx / len; trackZ = dz / len; }
        else { double yr = Math.toRadians(p.getYRot()); trackX = -Math.sin(yr); trackZ = Math.cos(yr); }

        // Column c shows the world at horizontal distance (c + 0.5) * colStep along the path: along the
        // predicted path while there is one (it is drawn too), straight along the ground track otherwise,
        // and straight on from the prediction's end if it ends before the plot does.
        double colStep = rangeX / COLS;
        double[] colX = new double[COLS], colZ = new double[COLS];
        Arrays.fill(hasPath, false);
        int col = 0;
        int n = Autopilot.isEngaged() ? PathPrediction.count() : 0;
        double walked = 0.0, nextD = colStep * 0.5, lastX = 0.0, lastZ = 0.0;
        for (int k = 1; k < n && col < COLS; k++) {
            double x = PathPrediction.x(k), z = PathPrediction.z(k);
            double seg = Math.hypot(x - lastX, z - lastZ);
            if (seg > 1.0e-9) { trackX = (x - lastX) / seg; trackZ = (z - lastZ) / seg; }
            while (col < COLS && walked + seg >= nextD) {
                double f = seg > 1.0e-9 ? (nextD - walked) / seg : 0.0;
                colX[col] = lastX + (x - lastX) * f; colZ[col] = lastZ + (z - lastZ) * f;
                double y = PathPrediction.y(k - 1) + (PathPrediction.y(k) - PathPrediction.y(k - 1)) * f;
                pathY[col] = (winTop - (pY + y)) / span * ROWS;   // plot row (fractional; may be off the plot)
                hasPath[col] = true;
                col++; nextD += colStep;
            }
            walked += seg; lastX = x; lastZ = z;
        }
        for (; col < COLS; col++, nextD += colStep) {               // beyond the prediction: straight on
            colX[col] = lastX + trackX * (nextD - walked); colZ[col] = lastZ + trackZ * (nextD - walked);
        }

        // rasterise each column top-down from the world's blocks
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int c = 0; c < COLS; c++) {
            int wx = (int) Math.floor(pX + colX[c]), wz = (int) Math.floor(pZ + colZ[c]);
            boolean seenSolid = false;
            for (int r = 0; r < ROWS; r++) {
                int idx = c * ROWS + r;
                pos.set(wx, rowToY(r), wz);
                if (!level.hasChunkAt(pos)) { cells[idx] = UNKNOWN; continue; }
                BlockState bs = level.getBlockState(pos);
                if (bs.isAir()) {
                    cells[idx] = seenSolid ? CAVE : AIR;
                } else {
                    MapColor m = bs.getMapColor(level, pos);
                    cells[idx] = 0xFF000000 | ((m == null || m == MapColor.NONE) ? NO_COLOR : m.col);
                    seenSolid = true;
                }
            }
        }
        have = true;
    }

    private static void render(Gui2D g, DeltaTracker delta) {
        if (!have || cells == null) return;
        Font font = Minecraft.getInstance().font;

        int panelW = PAD_L + PLOT_W, panelH = PAD_T + PLOT_H + PAD_B;
        int ox = g.guiWidth() - panelW - MARGIN_PX, oy = MARGIN_PX;
        int plotX = ox + PAD_L, plotY = oy + PAD_T;

        // terrain cells, merging vertical runs of one colour into one fill
        for (int c = 0; c < COLS; c++) {
            int sx = plotX + c, base = c * ROWS;
            int runStart = 0, runColor = cells[base];
            for (int r = 1; r < ROWS; r++) {
                int col = cells[base + r];
                if (col != runColor) {
                    g.fill(sx, plotY + runStart, sx + 1, plotY + r, runColor);
                    runStart = r; runColor = col;
                }
            }
            g.fill(sx, plotY + runStart, sx + 1, plotY + ROWS, runColor);
        }

        // predicted trace, an anti-aliased polyline, only while engaged
        for (int c = 0; c < COLS - 1; c++) {
            if (!hasPath[c] || !hasPath[c + 1]) continue;
            aaLine(g, plotX, plotY, c + 0.5, pathY[c], c + 1.5, pathY[c + 1]);
        }

        // you-are-here marker at the left edge, pinned at TOP_FRAC
        int psy = plotY + PLAYER_ROW;
        g.fill(plotX, psy - 2, plotX + 3, psy + 3, PLAYER);

        // small numeric ticks, shadowed since there is no panel behind them: distance along the bottom,
        int ayTick = plotY + PLOT_H, ayText = plotY + PLOT_H + 4;
        xTick(g, font, plotX, ayTick, ayText, 0.0,          "0",                                 0);
        xTick(g, font, plotX, ayTick, ayText, rangeX / 2,   String.valueOf((int) (rangeX / 2)),  1);
        xTick(g, font, plotX, ayTick, ayText, rangeX,       String.valueOf((int) rangeX),        2);
        // altitude up the left at round heights for the current scale
        double span = winTop - winBottom;
        int step = niceStep(span / 4.0);
        long first = (long) Math.ceil(winBottom / step) * step;
        for (long wy = first; wy <= winTop; wy += step) {
            int sy = plotY + (int) Math.round((winTop - wy) / span * ROWS);
            if (sy < plotY || sy > plotY + PLOT_H) continue;
            g.fill(plotX - 3, sy, plotX, sy + 1, LABEL);
            String lab = String.valueOf(wy);
            g.text(font, lab, plotX - 5 - font.width(lab), sy - 3, LABEL, true);
        }
    }

    /** A distance tick + number on the x-axis. align: 0 left, 1 centre, 2 right (keeps text inside the plot). */
    private static void xTick(Gui2D g, Font font, int plotX, int ayTick, int ayText,
                              double d, String lab, int align) {
        int sx = plotX + (int) Math.round(d / rangeX * PLOT_W);
        g.fill(sx, ayTick, sx + 1, ayTick + 3, LABEL);
        int w = font.width(lab);
        int tx = (align == 0) ? sx : (align == 1) ? sx - w / 2 : sx - w;
        g.text(font, lab, tx, ayText, LABEL, true);
    }

    /** A round altitude step near the target so ~4 gridlines fall in the window. */
    private static int niceStep(double target) {
        int[] nice = {8, 16, 32, 64, 128, 256};
        int best = nice[0];
        double bestErr = Double.MAX_VALUE;
        for (int n : nice) {
            double e = Math.abs(n - target);
            if (e < bestErr) { bestErr = e; best = n; }
        }
        return best;
    }

    // anti-aliased line (Wu's algorithm): two coverage-weighted pixels per step, clipped to the plot
    private static void aaLine(Gui2D g, int plotX, int plotY,
                               double x0, double y0, double x1, double y1) {
        boolean steep = Math.abs(y1 - y0) > Math.abs(x1 - x0);
        if (steep) { double t; t = x0; x0 = y0; y0 = t; t = x1; x1 = y1; y1 = t; }
        if (x0 > x1) { double t; t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t; }
        double dx = x1 - x0, dy = y1 - y0, grad = (dx == 0.0) ? 1.0 : dy / dx;
        int xs = (int) Math.round(x0), xe = (int) Math.round(x1);
        double intery = y0 + grad * (xs - x0);
        for (int x = xs; x <= xe; x++) {
            int yi = (int) Math.floor(intery);
            double f = intery - yi;
            plot(g, plotX, plotY, steep, x, yi,     1.0 - f);
            plot(g, plotX, plotY, steep, x, yi + 1, f);
            intery += grad;
        }
    }

    private static void plot(Gui2D g, int plotX, int plotY, boolean steep, int u, int v, double cov) {
        if (cov <= 0.02) return;
        int sx = steep ? v : u, sy = steep ? u : v;          // undo the steep swap
        if (sx < 0 || sx >= PLOT_W || sy < 0 || sy >= PLOT_H) return;
        int a = (int) Math.round(Math.min(1.0, cov) * 255.0) & 0xFF;
        int X = plotX + sx, Y = plotY + sy;
        g.fill(X, Y, X + 1, Y + 1, (a << 24) | PATH_RGB);
    }

    /** Cell row -> the world-Y at its centre (for sampling). */
    private static int rowToY(int r) {
        double span = winTop - winBottom;
        return (int) Math.round(winTop - (r + 0.5) / ROWS * span);
    }
}
