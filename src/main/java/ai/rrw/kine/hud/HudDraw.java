package ai.rrw.kine.hud;

import ai.rrw.kine.compat.Gui2D;
import net.minecraft.client.gui.Font;

import java.util.function.IntConsumer;

/** Drawing helpers the GUI renderer lacks: scaled centred text, one-pixel lines, segment-aligned fills. */
public final class HudDraw {
    private HudDraw() {}

    /** Text scaled about (cx, y) and centred horizontally on cx; `middle` centres it vertically on y too,
     *  otherwise y is its top. */
    public static void scaledText(Gui2D g, Font font, String text, int cx, int y, int color,
                                  float scale, boolean middle) {
        g.push();
        g.translate(cx, y);
        g.scale(scale, scale);
        g.text(font, text, -font.width(text) / 2, middle ? -font.lineHeight / 2 : 0, color, true);
        g.pop();
    }

    /** One-pixel line (Bresenham), since the GUI renderer only fills axis-aligned rectangles. */
    public static void line(Gui2D g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx - dy;
        while (true) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 <  dx) { err += dx; y0 += sy; }
        }
    }

    /** Run `draw` with the pose turned to lie along the segment from (x1, y1) to (x2, y2), centred on its
     *  midpoint; `draw` receives half the segment's length in whole pixels and fills from -half to half.
     *  Nothing is drawn for a segment shorter than minLen. */
    public static void alongSegment(Gui2D g, float x1, float y1, float x2, float y2,
                                    float minLen, IntConsumer draw) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < minLen) return;
        g.push();
        g.translate((x1 + x2) / 2f, (y1 + y2) / 2f);
        g.rotate((float) Math.atan2(dy, dx));
        draw.accept(Math.round(len / 2f));
        g.pop();
    }
}
