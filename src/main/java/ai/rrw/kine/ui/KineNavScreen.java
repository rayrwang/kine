package ai.rrw.kine.ui;

import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.FlightDirector;
import ai.rrw.kine.autoflight.Nav;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudDraw;
import ai.rrw.kine.util.Compass;
import ai.rrw.kine.util.Readouts;
import ai.rrw.kine.walk.Planner;
import ai.rrw.kine.walk.Route;
import ai.rrw.kine.walk.Walk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The nav screen (N), two tabs: Elytra (the autopilot's altitude, Selected heading or Managed destination, what to do
 * on arrival) and Walk (the walking pathfinder's own destination, one X and Z per dimension, with Go and Stop and the
 * walk's state).
 */
public class KineNavScreen extends Screen {

    private static final int RING = 0xFF888888, LABEL = 0xFFFFFFFF, DIM = 0xFFAAAAAA;
    private static final int CURR = 0xFF44CCFF;   // cyan: where the player is pointing now

    private Nav.Mode mode;
    private float heading;          // compass deg (0 = N); only meaningful when hasHeading
    private boolean hasHeading;     // whether a heading has been dialled
    private EditBox xBox, zBox;
    private EditBox altBox;
    private Button altMinus, altPlus;
    private static final int ALT_STEP = 10;
    private Button selBtn, manBtn, offBtn;
    private Button useCurrentBtn;
    private Button holdBtn, tillDayBtn, landBtn;   // what to do on arriving (MANAGED)
    private int arrivalY;
    private boolean draggingDial = false;

    private int dialCx, dialCy, dialR;   // heading dial geometry

    /** The tab shown; remembered while the game runs. */
    private enum Tab { ELYTRA, WALK }
    private static Tab tab = Tab.ELYTRA;
    private Button elytraTab, walkTab;
    private EditBox walkX, walkZ;
    private Button walkHere, walkBtn;   // walkBtn: Go, Pause or Cancel, as the walk stands (RW)
    private int walkTop;

    public KineNavScreen() {
        super(Component.literal("Kine nav"));
        this.mode = Nav.mode();
        this.hasHeading = Nav.hasHeading();
        this.heading = Nav.selectedHeading();
    }

    @Override
    protected void init() {
        int cx = this.width / 2, top = 64;
        if (!Settings.navigation) tab = Tab.WALK;
        if (!Settings.walkPathfinder) tab = Tab.ELYTRA;
        elytraTab = addRenderableWidget(Button.builder(Component.literal("Elytra"), b -> setTab(Tab.ELYTRA)).bounds(8, 8, 56, 18).build());
        walkTab = addRenderableWidget(Button.builder(Component.literal("Walk"), b -> setTab(Tab.WALK)).bounds(68, 8, 56, 18).build());
        initWalk(cx);

        // altitude selector, always active, above the mode buttons
        int abh = 20, abw = 20, ebw = 56, agap = 4;
        int rowW = abw + agap + ebw + agap + abw;
        int ax = cx - rowW / 2, altY = 42;
        altMinus = Button.builder(Component.literal("-"), b -> stepAlt(-ALT_STEP)).bounds(ax, altY, abw, abh).build();
        altBox   = new AltitudeBox(this.font, ax + abw + agap, altY, ebw, abh, Component.literal("Target altitude"));
        altPlus  = Button.builder(Component.literal("+"), b -> stepAlt(ALT_STEP)).bounds(ax + abw + agap + ebw + agap, altY, abw, abh).build();
        altBox.setMaxLength(5);
        altBox.setValue(Integer.toString(FlightDirector.targetAltitude()));
        Tooltip altTip = Tooltip.create(Component.literal(
            "Target altitude: the lowest point of each dive. The autopilot climbs until it reaches it, then cruises at full speed with each dive ending just above it."));
        altBox.setTooltip(altTip);
        altMinus.setTooltip(altTip);
        altPlus.setTooltip(altTip);
        addRenderableWidget(altMinus);
        addRenderableWidget(altBox);
        addRenderableWidget(altPlus);

        int bw = 80, bh = 20, gap = 6, totalW = bw * 3 + gap * 2, bx = cx - totalW / 2;
        selBtn = Button.builder(Component.literal("Selected"), b -> setMode(Nav.Mode.SELECTED)).bounds(bx, top, bw, bh).build();
        manBtn = Button.builder(Component.literal("Managed"),  b -> setMode(Nav.Mode.MANAGED)).bounds(bx + bw + gap, top, bw, bh).build();
        offBtn = Button.builder(Component.literal("Off"),      b -> setMode(Nav.Mode.OFF)).bounds(bx + 2 * (bw + gap), top, bw, bh).build();
        addRenderableWidget(selBtn);
        addRenderableWidget(manBtn);
        addRenderableWidget(offBtn);

        int dialTop = top + bh + 22;                       // just below the mode buttons
        int avail   = (this.height - 30) - dialTop;        // room down to above the Done button
        // leaves room for the HDG label and the "use current" button, shrinking on short screens (GUI height
        // 240 to 265) rather than pushing the button onto Done
        dialR  = Math.max(20, Math.min(90, (avail - 50) / 2));
        dialCx = cx;
        dialCy = dialTop + dialR;

        int fw = 92, fh = 20, fy = top + bh + 40;
        xBox = new EditBox(this.font, cx - fw - 8, fy, fw, fh, Component.literal("X"));
        zBox = new EditBox(this.font, cx + 8,      fy, fw, fh, Component.literal("Z"));
        xBox.setMaxLength(9);                  // the world border's -29999984
        zBox.setMaxLength(9);
        if (Nav.hasTarget()) {                 // restore a destination already set; no default
            xBox.setValue(Integer.toString(Nav.targetX()));
            zBox.setValue(Integer.toString(Nav.targetZ()));
        }
        addRenderableWidget(xBox);
        addRenderableWidget(zBox);

        // what to do on reaching the destination: a row like the mode buttons, the current choice greyed out
        arrivalY = fy + 58;
        holdBtn    = arrivalButton(Nav.Arrival.HOLD, bx, arrivalY, bw, bh,
            "Circle over the destination until you change this or take over. Lands if the elytra runs short.");
        tillDayBtn = arrivalButton(Nav.Arrival.HOLD_TILL_DAY, bx + bw + gap, arrivalY, bw, bh,
            "Circle over the destination while monsters can spawn there (at night, or in a thunderstorm), then land. By day it lands at once.");
        landBtn    = arrivalButton(Nav.Arrival.LAND, bx + 2 * (bw + gap), arrivalY, bw, bh,
            "Land as soon as you arrive.");

        // sets the dial to where the player is pointing (one click instead of dragging)
        int useY = dialCy + dialR + 28;
        useCurrentBtn = Button.builder(Component.literal("Use current heading"), b -> useCurrentHeading())
            .bounds(cx - 75, useY, 150, 20).build();
        addRenderableWidget(useCurrentBtn);

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds(cx - 50, this.height - 28, 100, 20).build());

        updateVisibility();
    }

    private Button arrivalButton(Nav.Arrival a, int x, int y, int w, int h, String tip) {
        Button b = Button.builder(Component.literal(a.label), btn -> { Nav.setArrival(a); updateVisibility(); })
            .bounds(x, y, w, h).build();
        b.setTooltip(Tooltip.create(Component.literal(tip)));
        return addRenderableWidget(b);
    }

    private void setMode(Nav.Mode m) {
        this.mode = m;
        if (m == Nav.Mode.SELECTED) Nav.enterSelected();           // the heading stays unset until dialled
        else if (m == Nav.Mode.MANAGED) { Nav.enterManaged(); applyManaged(); }
        else Nav.off();
        updateVisibility();
    }

    private void applyManaged() {
        Integer x = parseInt(xBox.getValue()), z = parseInt(zBox.getValue());
        if (x == null || z == null) return;                        // commits only once both coordinates parse
        // and only a change: setting the same target again would cancel a landing in progress
        if (Nav.mode() == Nav.Mode.MANAGED && Nav.hasTarget() && x == Nav.targetX() && z == Nav.targetZ()) return;
        Nav.setTarget(x, z);
    }

    /** Commit the typed altitude; an empty box or a lone "-" is ignored. Called on Enter, when the box
     *  loses focus, before a step, and on close, not on every keystroke: typing 350 would otherwise fly
     *  to 3, then 35, where the game isn't paused. */
    private void applyAlt() {
        Integer v = parseInt(altBox.getValue());
        if (v != null) FlightDirector.setTargetAltitude(v);
        altBox.setValue(Integer.toString(FlightDirector.targetAltitude()));   // show the clamped value
    }

    /** The altitude box: commits on Enter and when it loses focus. */
    private final class AltitudeBox extends EditBox {
        AltitudeBox(Font font, int x, int y, int w, int h, Component name) { super(font, x, y, w, h, name); }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) { applyAlt(); return true; }
            return super.keyPressed(event);
        }

        @Override
        public void setFocused(boolean focused) {
            if (isFocused() && !focused) applyAlt();
            super.setFocused(focused);
        }
    }

    private void stepAlt(int d) {
        applyAlt();
        FlightDirector.setTargetAltitude(FlightDirector.targetAltitude() + d);
        altBox.setValue(Integer.toString(FlightDirector.targetAltitude()));   // show the clamped value
    }

    /** Open the nav screen on its Walk tab. */
    public static KineNavScreen walkTab() {
        tab = Tab.WALK;
        return new KineNavScreen();
    }

    private void setTab(Tab t) {
        if (t == tab) return;
        if (tab == Tab.ELYTRA) { applyAlt(); if (mode == Nav.Mode.MANAGED) applyManaged(); }
        else applyWalk();
        tab = t;
        updateVisibility();
    }

    // ---------------------------------------------------------------- the Walk tab

    private void initWalk(int cx) {
        walkTop = 46;
        int fw = 92, fh = 20, fy = walkTop + 18;
        walkX = new EditBox(this.font, cx - fw - 8, fy, fw, fh, Component.literal("Walk X"));
        walkZ = new EditBox(this.font, cx + 8, fy, fw, fh, Component.literal("Walk Z"));
        walkX.setMaxLength(9);
        walkZ.setMaxLength(9);
        int[] d = Walk.destination();
        if (d != null) { walkX.setValue(Integer.toString(d[0])); walkZ.setValue(Integer.toString(d[1])); }
        addRenderableWidget(walkX);
        addRenderableWidget(walkZ);
        walkHere = addRenderableWidget(Button.builder(Component.literal("Use current position"), b -> useCurrentPosition())
            .bounds(cx - 75, fy + 28, 150, 20).build());
        walkHere.setTooltip(Tooltip.create(Component.literal("Sets the destination to where you stand, to walk back here later.")));
        walkBtn = addRenderableWidget(Button.builder(Component.literal("Go"), b -> walkPress()).bounds(cx - 40, fy + 56, 80, 20).build());
        refreshWalkButton();
    }

    /** The one button: Go walks, Pause gives control back with the route still drawn, Cancel dismisses it. */
    private void walkPress() {
        switch (Walk.button()) {
            case GO -> walkGo();
            case PAUSE -> Walk.stop("");
            case CANCEL -> Walk.cancel();
        }
        refreshWalkButton();
    }

    private Walk.Button shownButton = null;

    private void refreshWalkButton() {
        Walk.Button b = Walk.button();
        if (b == shownButton) return;
        shownButton = b;
        walkBtn.setMessage(Component.literal(switch (b) { case GO -> "Go"; case PAUSE -> "Pause"; case CANCEL -> "Cancel"; }));
        walkBtn.setTooltip(Tooltip.create(Component.literal(switch (b) {
            case GO -> "Plans a route on foot and walks it. Any movement key pauses it.";
            case PAUSE -> "Gives you control back. The route stays drawn until you cancel.";
            case CANCEL -> "Clears the route, rails and destination pillar.";
        })));
    }

    private void useCurrentPosition() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        walkX.setValue(Integer.toString(mc.player.getBlockX()));
        walkZ.setValue(Integer.toString(mc.player.getBlockZ()));
        applyWalk();
    }

    /** Keep the typed destination (both boxes must parse). */
    private void applyWalk() {
        Integer x = parseInt(walkX.getValue()), z = parseInt(walkZ.getValue());
        if (x != null && z != null) Walk.setDestination(x, z);
    }

    private void walkGo() {
        applyWalk();
        if (Walk.destination() == null) return;
        Walk.go();
        this.onClose();   // walking needs the keys: with a screen open none are pressed
    }

    private void drawWalk(GuiGraphicsExtractor g) {
        int cx = this.width / 2;
        g.centeredText(this.font, Component.literal("Walk to (this dimension)"), cx, walkTop, LABEL);
        g.text(this.font, "X", walkX.getX() - 10, walkX.getY() + 6, DIM, true);
        g.text(this.font, "Z", walkZ.getX() - 10, walkZ.getY() + 6, DIM, true);
        refreshWalkButton();   // the walk can pause or arrive while the screen is open
        int y = walkBtn.getY() + 30;
        String st = switch (Walk.state()) {
            case IDLE -> "Not walking";
            case PLANNING -> "Planning";
            case WALKING, STOPPED, FAILED -> Walk.message();
            case ARRIVED -> "Arrived";
        };
        int color = Walk.state() == Walk.State.FAILED ? 0xFFFF6060 : Walk.active() ? HudColors.GREEN : DIM;
        g.centeredText(this.font, Component.literal(st), cx, y, color);
        Planner.Plan p = Walk.plan();
        Minecraft mc = Minecraft.getInstance();
        int[] d = Walk.destination();
        if (d != null && mc.player != null) {
            double dist = Math.hypot(d[0] + 0.5 - mc.player.getX(), d[1] + 0.5 - mc.player.getZ());
            g.centeredText(this.font, Component.literal(String.format(java.util.Locale.ROOT, "%.0f blocks away in a straight line", dist)), cx, y + 12, DIM);
        }
        int below = 24;
        if (Settings.walkReadouts && Walk.etaShowing()) {
            // the HUD's figures: along the route (straight on past where it stops short), and the arrival time
            String e = "Along the route " + Readouts.distance(Walk.distanceLeft()) + ", ETA " + Readouts.eta(Walk.etaSeconds());
            g.centeredText(this.font, Component.literal(e), cx, y + 24, HudColors.CYAN);
            below = 36;
        }
        if (p != null && (Walk.active() || Walk.showing())) {
            Route r = p.route;
            String a = String.format(java.util.Locale.ROOT, "Route %.1f blocks (grid %.1f)%s", r.length, r.gridLength,
                r.viaExit ? ", then straight on past what's loaded" : "");
            String b = String.format(java.util.Locale.ROOT, "Search %.0f ms, %,d places, weight %.2f", p.result.nanos / 1e6,
                p.result.expanded, p.result.weight);
            g.centeredText(this.font, Component.literal(a), cx, y + below, DIM);
            g.centeredText(this.font, Component.literal(b), cx, y + below + 12, DIM);
        }
        g.centeredText(this.font, Component.literal("Any movement key pauses the walk. Your view stays yours."), cx, y + below + 30, DIM);
    }

    private void updateVisibility() {
        boolean walk = tab == Tab.WALK;
        elytraTab.active = walk && Settings.navigation;   // the tab shown is greyed out, like the mode buttons
        walkTab.active = !walk && Settings.walkPathfinder;
        walkX.visible = walkX.active = walk;
        walkZ.visible = walkZ.active = walk;
        walkHere.visible = walkHere.active = walk;
        walkBtn.visible = walk; walkBtn.active = walk && Settings.walkPathfinder;
        altBox.visible = !walk; altMinus.visible = !walk; altPlus.visible = !walk;
        selBtn.visible = !walk; manBtn.visible = !walk; offBtn.visible = !walk;
        if (walk) {
            xBox.visible = false; xBox.active = false; zBox.visible = false; zBox.active = false;
            holdBtn.visible = false; tillDayBtn.visible = false; landBtn.visible = false;
            useCurrentBtn.visible = false; useCurrentBtn.active = false;
            return;
        }
        boolean man = mode == Nav.Mode.MANAGED;
        xBox.visible = man; xBox.active = man;
        zBox.visible = man; zBox.active = man;
        Nav.Arrival arrival = Nav.arrival();
        holdBtn.visible = man;    holdBtn.active = man && arrival != Nav.Arrival.HOLD;
        tillDayBtn.visible = man; tillDayBtn.active = man && arrival != Nav.Arrival.HOLD_TILL_DAY;
        landBtn.visible = man;    landBtn.active = man && arrival != Nav.Arrival.LAND;
        selBtn.active = mode != Nav.Mode.SELECTED;   // the current mode's button is greyed out
        manBtn.active = mode != Nav.Mode.MANAGED;
        offBtn.active = mode != Nav.Mode.OFF;
        boolean sel = mode == Nav.Mode.SELECTED;
        useCurrentBtn.visible = sel; useCurrentBtn.active = sel;   // only for dialling a heading
    }

    @Override
    public void tick() {
        super.tick();
        if (tab == Tab.WALK) refreshWalkButton();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (tab == Tab.ELYTRA && mode == Nav.Mode.SELECTED && inDial(event.x(), event.y())) {
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
        float h = (float) Math.toDegrees(Math.atan2(dx, -dy));   // up is 0 (north), right is 90 (east)
        heading = (h % 360f + 360f) % 360f;
        hasHeading = true;
        Nav.setHeading(heading);
    }

    /** Set the dial to where the player is pointing. Minecraft yaw (0 = south) to compass (0 = north). */
    private void useCurrentHeading() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        heading = Compass.headingF(mc.player.getYRot());
        hasHeading = true;
        Nav.setHeading(heading);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        g.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
        if (tab == Tab.WALK) { drawWalk(g); return; }
        g.centeredText(this.font, Component.literal("Target altitude"), this.width / 2, 30, LABEL);

        if (mode == Nav.Mode.SELECTED) {
            drawDial(g);
        } else if (mode == Nav.Mode.MANAGED) {
            g.text(this.font, "X", xBox.getX() - 10, xBox.getY() + 6, DIM, true);
            g.text(this.font, "Z", zBox.getX() - 10, zBox.getY() + 6, DIM, true);
            String hint = "Destination coordinates";
            g.text(this.font, hint, this.width / 2 - this.font.width(hint) / 2, xBox.getY() + 30, DIM, true);
            g.centeredText(this.font, Component.literal("At destination"), this.width / 2, arrivalY - 12, LABEL);
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
            if (i > 0) HudDraw.line(g, px, py, x, y, RING);
            px = x; py = y;
        }
        g.text(this.font, "N", dialCx - 3, dialCy - dialR - 11, LABEL, true);
        g.text(this.font, "S", dialCx - 3, dialCy + dialR + 2,  LABEL, true);
        g.text(this.font, "E", dialCx + dialR + 5, dialCy - 4,  LABEL, true);
        g.text(this.font, "W", dialCx - dialR - 11, dialCy - 4, LABEL, true);

        // a short cyan tick at the rim where the player is pointing now, to dial relative to it
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            double pr = Math.toRadians(Compass.headingF(mc.player.getYRot()));
            int ox = dialCx + (int) Math.round(Math.sin(pr) * (dialR + 2));
            int oy = dialCy - (int) Math.round(Math.cos(pr) * (dialR + 2));
            int ix = dialCx + (int) Math.round(Math.sin(pr) * (dialR - 10));
            int iy = dialCy - (int) Math.round(Math.cos(pr) * (dialR - 10));
            HudDraw.line(g, ox, oy, ix, iy, CURR);
        }

        if (hasHeading) {
            double hr = Math.toRadians(heading);
            int hx = dialCx + (int) Math.round(Math.sin(hr) * (dialR - 4));
            int hy = dialCy - (int) Math.round(Math.cos(hr) * (dialR - 4));
            HudDraw.line(g, dialCx, dialCy, hx, hy, HudColors.GREEN);
        }
        g.fill(dialCx - 2, dialCy - 2, dialCx + 3, dialCy + 3, LABEL);

        String hs = hasHeading ? "HDG " + String.format("%03d", Math.round(heading) % 360) : "HDG ---";
        g.text(this.font, hs, dialCx - this.font.width(hs) / 2, dialCy + dialR + 16, hasHeading ? HudColors.GREEN : DIM, true);
    }

    @Override
    public void onClose() {
        applyWalk();
        if (tab == Tab.ELYTRA) {
            applyAlt();
            if (mode == Nav.Mode.MANAGED) applyManaged();
            else if (mode == Nav.Mode.SELECTED && hasHeading) Nav.setHeading(heading);
        }
        super.onClose();
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }
}
