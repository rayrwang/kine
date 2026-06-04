package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.autoflight.FlightDirector;
import ai.rrw.kine.autoflight.FlightModel;
import ai.rrw.kine.autoflight.TerrainGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * Side-on flight profile -- a small Terraria-style cutaway pinned to the top-right corner: the real blocks
 * along the ground track ahead of you (block map colours, sky-blue for open air, a darker fill for air
 * enclosed below the surface so caves read as caves), with a smooth dark-blue trace of the autopilot's
 * predicted path over them.
 *
 * <p>The trace is the autopilot's committed trajectory -- the same {@link FlightModel} rollout seeded by
 * {@link TerrainGuard#seedFromPlayer} that draws the blue rails -- so it's drawn only while the autopilot is
 * engaged. The terrain panel shows whenever you're gliding. Pure instrument: reads and draws, steers nothing.
 *
 * <p>The horizontal axis spans the whole loaded render distance (so the map reaches exactly as far as you can
 * see), and the vertical axis uses the SAME blocks-per-pixel scale, so the picture is undistorted. The
 * vertical window slides to keep you ~62% up from the bottom. No panel or border -- just the raster, the
 * trace, and small numeric ticks (distance ahead along the bottom, altitude up the left).
 */
public final class FlightProfile {
    private FlightProfile() {}

    // ---- geometry (gui-scaled px; 1 px per cell) ----
    private static final int    COLS    = 120;
    private static final int    ROWS    = 120;
    private static final int    CELL    = 1;
    private static final int    PLOT_W  = COLS * CELL;
    private static final int    PLOT_H  = ROWS * CELL;
    private static final double TOP_FRAC = 0.382;                // you sit 38.2% down from the top (~62% up)
    private static final int    PLAYER_ROW = (int) Math.round(TOP_FRAC * ROWS);

    private static final int PAD_L = 22, PAD_T = 2, PAD_B = 11, MARGIN_PX = 6;

    // ---- projection ----
    private static final int MAX_STEPS = 900;                    // rollout cap (ticks) -- covers a 32-chunk render
    private static final int RECOMPUTE = 6;

    // ---- colours (ARGB unless noted) ----
    private static final int AIR      = 0xFF8FB8E0;
    private static final int CAVE     = 0xFF1B2330;
    private static final int UNKNOWN  = 0xFF454B52;
    private static final int NO_COLOR = 0x6B6B6B;                // rgb only
    private static final int PATH_RGB = 0x0A1F70;                // dark navy (rgb; alpha from AA coverage)
    private static final int PLAYER   = 0xFFFFD23F;
    private static final int LABEL    = 0xFFC8D0D8;

    private static boolean  have = false;
    private static int[]    cells;                               // COLS*ROWS, idx = c*ROWS + r, r=0 is window top
    private static double[] pathY;                               // per column: plot-space row of the trace (float)
    private static boolean[] hasPath;
    private static double   winTop, winBottom;
    private static double   rangeX = 240.0;                      // current x-span (blocks) = render distance
    private static int      ticks = 0;

    public static void register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "flight_profile"),
            FlightProfile::render);
        ClientTickEvents.END_CLIENT_TICK.register(FlightProfile::tick);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !Settings.displayFlightProfile || !p.isFallFlying()) {
            have = false; return;
        }
        if (cells == null) { cells = new int[COLS * ROWS]; pathY = new double[COLS]; hasPath = new boolean[COLS]; }
        if (have && (++ticks % RECOMPUTE) != 0) return;
        compute(mc, p);
    }

    private static void compute(Minecraft mc, LocalPlayer p) {
        ClientLevel level = mc.level;
        double pX = p.getX(), pY = p.getY(), pZ = p.getZ();

        // X spans the entire loaded render distance; Y uses the same blocks-per-pixel scale (square cells).
        rangeX = Math.max(32, mc.options.getEffectiveRenderDistance() * 16);
        double bpp = rangeX / PLOT_W;
        double span = PLOT_H * bpp;
        winTop = pY + TOP_FRAC * span;          // slide the window to keep you at TOP_FRAC; scale is fixed by render
        winBottom = winTop - span;

        // ground-track unit vector (the direction the strip looks along)
        double dx = pX - p.xOld, dz = pZ - p.zOld, len = Math.hypot(dx, dz);
        double trackX, trackZ;
        if (len > 1.0e-4) { trackX = dx / len; trackZ = dz / len; }
        else { double yr = Math.toRadians(p.getYRot()); trackX = -Math.sin(yr); trackZ = Math.cos(yr); }

        // The trace: only when engaged, pulled from the predicted trajectory (same source as the blue rails).
        boolean engaged = Autopilot.isEngaged();
        java.util.Arrays.fill(hasPath, false);
        if (engaged) {
            FlightModel.State s = TerrainGuard.seedFromPlayer(p, FlightDirector.floorAltitude());
            double colStep = rangeX / COLS, nextD = colStep * 0.5;
            int col = 0;
            for (int k = 1; k <= MAX_STEPS && col < COLS; k++) {
                FlightModel.step(s);                              // s.x = along-track distance, s.y = altitude
                while (col < COLS && s.x >= nextD) {
                    pathY[col] = (winTop - s.y) / span * ROWS;    // plot-space row (float; may sit off-plot)
                    hasPath[col] = true;
                    col++; nextD += colStep;
                }
            }
        }

        // rasterize each column top-down along the straight ground track, sampling the real world block
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double colStep = rangeX / COLS;
        for (int c = 0; c < COLS; c++) {
            double d = (c + 0.5) * colStep;
            int wx = (int) Math.floor(pX + trackX * d), wz = (int) Math.floor(pZ + trackZ * d);
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

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!have || cells == null) return;
        Font font = Minecraft.getInstance().font;

        int panelW = PAD_L + PLOT_W, panelH = PAD_T + PLOT_H + PAD_B;
        int ox = g.guiWidth() - panelW - MARGIN_PX, oy = MARGIN_PX;
        int plotX = ox + PAD_L, plotY = oy + PAD_T;

        // terrain cells -- merge vertical runs of equal colour into one fill. No panel, no border: the raster
        // is the map.
        for (int c = 0; c < COLS; c++) {
            int sx = plotX + c * CELL, base = c * ROWS;
            int runStart = 0, runColor = cells[base];
            for (int r = 1; r < ROWS; r++) {
                int col = cells[base + r];
                if (col != runColor) {
                    g.fill(sx, plotY + runStart * CELL, sx + CELL, plotY + r * CELL, runColor);
                    runStart = r; runColor = col;
                }
            }
            g.fill(sx, plotY + runStart * CELL, sx + CELL, plotY + ROWS * CELL, runColor);
        }

        // predicted trace -- a smooth (anti-aliased) polyline, only when engaged
        for (int c = 0; c < COLS - 1; c++) {
            if (!hasPath[c] || !hasPath[c + 1]) continue;
            aaLine(g, plotX, plotY, c + 0.5, pathY[c], c + 1.5, pathY[c + 1]);
        }

        // you-are-here marker at the left edge, pinned at TOP_FRAC
        int psy = plotY + PLAYER_ROW * CELL;
        g.fill(plotX, psy - 2, plotX + 3, psy + 3, PLAYER);

        // small numeric ticks (shadowed, since there's no panel behind them): distance along the bottom...
        int ayTick = plotY + PLOT_H, ayText = plotY + PLOT_H + 4;
        xTick(g, font, plotX, ayTick, ayText, 0.0,          "0",                                 0);
        xTick(g, font, plotX, ayTick, ayText, rangeX / 2,   String.valueOf((int) (rangeX / 2)),  1);
        xTick(g, font, plotX, ayTick, ayText, rangeX,       String.valueOf((int) rangeX),        2);
        // ...altitude up the left, at round heights spaced to the current scale
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
    private static void xTick(GuiGraphicsExtractor g, Font font, int plotX, int ayTick, int ayText,
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

    // ---- smooth line (Wu): two coverage-weighted pixels per step, clipped to the plot ----
    private static void aaLine(GuiGraphicsExtractor g, int plotX, int plotY,
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

    private static void plot(GuiGraphicsExtractor g, int plotX, int plotY, boolean steep, int u, int v, double cov) {
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
