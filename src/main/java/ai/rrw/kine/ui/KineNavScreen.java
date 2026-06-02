package ai.rrw.kine.ui;

import ai.rrw.kine.autoflight.Nav;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class KineNavScreen extends Screen {

    private static final int GREEN = 0xFF44FF44, RING = 0xFF888888, LABEL = 0xFFFFFFFF, DIM = 0xFFAAAAAA;

    private Nav.Mode mode;
    private float heading;          // compass deg (0 = N); only meaningful when hasHeading
    private boolean hasHeading;     // has the dial been set this session / previously
    private EditBox xBox, zBox;
    private Button selBtn, manBtn, offBtn;
    private boolean draggingDial = false;

    private int dialCx, dialCy, dialR;   // heading dial geometry

    public KineNavScreen() {
        super(Component.literal("Kine nav"));
        this.mode = Nav.mode();
        this.hasHeading = Nav.hasHeading();
        this.heading = Nav.selectedHeading();
    }

    @Override
    protected void init() {
        int cx = this.width / 2, top = 36;
        int bw = 80, bh = 20, gap = 6, totalW = bw * 3 + gap * 2, bx = cx - totalW / 2;
        selBtn = Button.builder(Component.literal("Selected"), b -> setMode(Nav.Mode.SELECTED)).bounds(bx, top, bw, bh).build();
        manBtn = Button.builder(Component.literal("Managed"),  b -> setMode(Nav.Mode.MANAGED)).bounds(bx + bw + gap, top, bw, bh).build();
        offBtn = Button.builder(Component.literal("Off"),      b -> setMode(Nav.Mode.OFF)).bounds(bx + 2 * (bw + gap), top, bw, bh).build();
        addRenderableWidget(selBtn);
        addRenderableWidget(manBtn);
        addRenderableWidget(offBtn);

        dialR  = Math.max(54, Math.min(90, (this.height - top - bh - 110) / 2));
        dialCx = cx;
        dialCy = top + bh + 28 + dialR;

        int fw = 92, fh = 20, fy = top + bh + 40;
        xBox = new EditBox(this.font, cx - fw - 8, fy, fw, fh, Component.literal("X"));
        zBox = new EditBox(this.font, cx + 8,      fy, fw, fh, Component.literal("Z"));
        xBox.setMaxLength(8);
        zBox.setMaxLength(8);
        if (Nav.hasTarget()) {                 // only restore a previously-set destination; no default
            xBox.setValue(Integer.toString(Nav.targetX()));
            zBox.setValue(Integer.toString(Nav.targetZ()));
        }
        addRenderableWidget(xBox);
        addRenderableWidget(zBox);

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds(cx - 50, this.height - 28, 100, 20).build());

        updateVisibility();
    }

    private void setMode(Nav.Mode m) {
        this.mode = m;
        if (m == Nav.Mode.SELECTED) Nav.enterSelected();           // heading stays unset until you dial it
        else if (m == Nav.Mode.MANAGED) { Nav.enterManaged(); applyManaged(); }
        else Nav.off();
        updateVisibility();
    }

    private void applyManaged() {
        Integer x = parseInt(xBox.getValue()), z = parseInt(zBox.getValue());
        if (x != null && z != null) Nav.setTarget(x, z);           // only commits once both coords are valid
    }

    private void updateVisibility() {
        boolean man = mode == Nav.Mode.MANAGED;
        xBox.visible = man; xBox.active = man;
        zBox.visible = man; zBox.active = man;
        selBtn.active = mode != Nav.Mode.SELECTED;   // greyed = the current mode
        manBtn.active = mode != Nav.Mode.MANAGED;
        offBtn.active = mode != Nav.Mode.OFF;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (mode == Nav.Mode.SELECTED && inDial(event.x(), event.y())) {
            draggingDial = true;
            setHeadingFromMouse(event.x(), event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingDial) { setHeadingFromMouse(event.x(), event.y()); return true; }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingDial = false;
        return super.mouseReleased(event);
    }

    private boolean inDial(double mx, double my) {
        double dx = mx - dialCx, dy = my - dialCy;
        return dx * dx + dy * dy <= (dialR + 10) * (dialR + 10);
    }

    private void setHeadingFromMouse(double mx, double my) {
        double dx = mx - dialCx, dy = my - dialCy;
        if (dx == 0 && dy == 0) return;
        float h = (float) Math.toDegrees(Math.atan2(dx, -dy));   // up = 0 = N, right = 90 = E
        heading = (h % 360f + 360f) % 360f;
        hasHeading = true;
        Nav.setHeading(heading);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        g.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

        if (mode == Nav.Mode.SELECTED) {
            drawDial(g);
        } else if (mode == Nav.Mode.MANAGED) {
            g.text(this.font, "X", xBox.getX() - 10, xBox.getY() + 6, DIM, true);
            g.text(this.font, "Z", zBox.getX() - 10, zBox.getY() + 6, DIM, true);
            String hint = "Destination coordinates";
            g.text(this.font, hint, this.width / 2 - this.font.width(hint) / 2, xBox.getY() + 30, DIM, true);
        } else {
            String hint = "Navigation off";
            g.text(this.font, hint, this.width / 2 - this.font.width(hint) / 2, dialCy, DIM, true);
        }
    }

    private void drawDial(GuiGraphicsExtractor g) {
        int seg = 60, px = 0, py = 0;
        for (int i = 0; i <= seg; i++) {
            double a = i * 2 * Math.PI / seg;
            int x = dialCx + (int) Math.round(Math.sin(a) * dialR);
            int y = dialCy - (int) Math.round(Math.cos(a) * dialR);
            if (i > 0) line(g, px, py, x, y, RING);
            px = x; py = y;
        }
        g.text(this.font, "N", dialCx - 3, dialCy - dialR - 11, LABEL, true);
        g.text(this.font, "S", dialCx - 3, dialCy + dialR + 2,  LABEL, true);
        g.text(this.font, "E", dialCx + dialR + 5, dialCy - 4,  LABEL, true);
        g.text(this.font, "W", dialCx - dialR - 11, dialCy - 4, LABEL, true);

        if (hasHeading) {
            double hr = Math.toRadians(heading);
            int hx = dialCx + (int) Math.round(Math.sin(hr) * (dialR - 4));
            int hy = dialCy - (int) Math.round(Math.cos(hr) * (dialR - 4));
            line(g, dialCx, dialCy, hx, hy, GREEN);
        }
        g.fill(dialCx - 2, dialCy - 2, dialCx + 3, dialCy + 3, LABEL);

        String hs = hasHeading ? "HDG " + String.format("%03d", Math.round(heading) % 360) : "HDG ---";
        g.text(this.font, hs, dialCx - this.font.width(hs) / 2, dialCy + dialR + 16, hasHeading ? GREEN : DIM, true);
    }

    /** thin line via single-pixel fills (Bresenham) */
    private void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, err = dx - dy;
        while (true) {
            g.fill(x1, y1, x1 + 1, y1 + 1, color);
            if (x1 == x2 && y1 == y2) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x1 += sx; }
            if (e2 <  dx) { err += dx; y1 += sy; }
        }
    }

    @Override
    public void onClose() {
        if (mode == Nav.Mode.MANAGED) applyManaged();
        else if (mode == Nav.Mode.SELECTED && hasHeading) Nav.setHeading(heading);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return true; }   // freeze singleplayer while setting up nav mid-flight

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }
}
