package ai.rrw.kine.ui;

import ai.rrw.kine.Settings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class KineSettingsScreen extends Screen {

    private static final int MARGIN  = 16;
    private static final int DIVIDER = 0xFF555555;
    private static final int HEADER  = 0xFFBBBBBB;
    private static final int ROW_H    = 20;
    private static final int ROW_STEP = 22;
    private static final int GAP      = 8;     // between sections
    private static final int MODE_DIV = 10;    // space for divider under the mode row

    private final Screen parent;

    // layout
    private int rowX, colW, headH;
    private int viewTop, viewBottom, contentHeight, maxScroll, scrollOffset;
    private int baseModeDivY;
    private int[] baseHeaderY;
    private int[] baseRowY;       // content-space y of each toggle row
    private Opt[] rowOpt;
    private Button[] rowButton;

    public KineSettingsScreen(Screen parent) {
        super(Component.literal("Kine settings"));
        this.parent = parent;
    }

    // ---- option model ----
    private record Opt(String name, String desc, BooleanSupplier get, Consumer<Boolean> set,
                       String onText, String offText) {
        static Opt of(String n, String d, BooleanSupplier g, Consumer<Boolean> s) {
            return new Opt(n, d, g, s, "ON", "OFF");
        }
        static Opt mode(String n, String d, BooleanSupplier g, Consumer<Boolean> s, String on, String off) {
            return new Opt(n, d, g, s, on, off);
        }
    }

    private record Section(String title, Opt[] opts) {}

    // ---- descriptions ----
    private static final String DESC_FLIGHTMODE =
        "Sets what the autopilot and elytra flight directors optimize for. MAX CLIMB tunes the "
        + "dive/pull-up cycle to gain the most altitude (~25 m/s ground speed, climbing ~1 block/s). "
        + "MAX SPEED holds altitude while cruising as fast as possible (~33 m/s). The active mode is "
        + "shown by the HUD annunciator above the director bars.";
    private static final String DESC_SPEED =
        "Shows your total movement speed in m/s (blocks per second), including vertical motion, "
        + "measured from your real per-tick position change rather than the game's under-reported "
        + "velocity. Handy while falling or flying. Drawn centred above the hotbar.";
    private static final String DESC_GROUND =
        "Shows horizontal speed only, ignoring up/down motion, in m/s. This is the number that "
        + "governs elytra travel distance and your walking/sprinting pace. Drawn just below the "
        + "total-speed line.";
    private static final String DESC_HEALTH =
        "Floats each creature's current and maximum health (e.g. \"14 / 20\") above it, sized by "
        + "distance and visible through walls. Useful for tracking damage mid-fight.";
    private static final String DESC_NAMES =
        "Floats each creature's name above it, next to the health readout. Off by default because "
        + "it adds clutter, especially on busy servers.";
    private static final String DESC_FLIGHT =
        "While elytra-flying, overlays magenta guidance bars showing the pitch to hold for the "
        + "energy-pumping technique: dive to build speed, snap the nose up, then ease it back down. "
        + "Guidance only \u2014 it never moves you.";
    private static final String DESC_ELYTRA =
        "Leave this on unless a bug keeps kicking you \u2014 that's the only good reason to turn it "
        + "off. While elytra-flying it warns when durability is too low to safely glide down from your "
        + "current altitude (higher = larger reserve, with healthy margins). On autopilot, if you don't "
        + "take control within 5s of the warning, it disconnects you \u2014 logging out before the "
        + "elytra breaks so you don't fall to your death. Flying manually, it only warns, never "
        + "disconnects.";
    private static final String DESC_CRASH =
        "While elytra-flying, sheds speed when you're about to hit a wall, ceiling, or ground \u2014 "
        + "halting you short of walls and cushioning descents so you land safely instead of dying on "
        + "impact. It only ever removes velocity heading into a surface, never turning or throwing you, "
        + "so a false trigger just briefly slows you. Works within your client's control; a server with "
        + "strict movement anti-cheat could override it.";
    private static final String DESC_FALL =
        "Stops you walking off ledges that would cause fall damage, the way sneaking does, but "
        + "automatically and only when the drop ahead is actually dangerous. Safe drops and "
        + "deliberate jumps are unaffected. Ignores water/hay landings and slow/feather falling.";
    private static final String DESC_RETICLE =
        "Draws a ring at the exact spot your readied projectile will land \u2014 bow, crossbow, "
        + "trident, snowball, egg, ender pearl, potion, or xp bottle \u2014 accounting for gravity, "
        + "drag, launch spread, and your own movement. The ring turns green when it covers a valid "
        + "target.";
    private static final String DESC_GLOW =
        "Outlines every projectile with a bright glow \u2014 in flight and after it lands \u2014 "
        + "visible through walls, so arrows and thrown items are easy to follow. Applies to all "
        + "projectiles, including those thrown by others.";
    private static final String DESC_AUTOAIM =
        "Automatically steers your view so weapon shots (bow, crossbow, trident) land on the target "
        + "nearest your reticle, leading moving targets by predicting where they'll be when the shot "
        + "arrives. Nudge the mouse to break the lock; it pauses briefly so you can look away. Thrown "
        + "utility items are never auto-aimed. This is an aimbot \u2014 expect a ban anywhere that "
        + "doesn't allow client mods.";
    private static final String DESC_DODGE =
        "Watches for incoming projectiles (arrows, tridents, fireballs and the like) and, when one is "
        + "on course to hit you within about a second, sidesteps you off its line at roughly sprint "
        + "speed. It can't beat a point-blank or very fast shot \u2014 there isn't time to clear the "
        + "hitbox \u2014 and an opponent who aims where you'll dodge to can still connect. Moves you "
        + "with normal-looking speed, so it's lower risk than the aimbot, but it's still a movement mod.";

    // top-of-menu mode selector (functional toggle, not enable/disable)
    private static final Opt FLIGHT_MODE = Opt.mode("Autopilot / flight directors", DESC_FLIGHTMODE,
        () -> Settings.flightMaxSpeed, v -> Settings.flightMaxSpeed = v, "MAX SPEED", "MAX CLIMB");

    private static final Section[] SECTIONS = {
        new Section("Heads-up display", new Opt[]{
            Opt.of("Display speed", DESC_SPEED, () -> Settings.displaySpeed, v -> Settings.displaySpeed = v),
            Opt.of("Display ground speed", DESC_GROUND, () -> Settings.displayGroundSpeed, v -> Settings.displayGroundSpeed = v),
            Opt.of("Display mob healths", DESC_HEALTH, () -> Settings.displayMobHealths, v -> Settings.displayMobHealths = v),
            Opt.of("Display mob names", DESC_NAMES, () -> Settings.displayMobNames, v -> Settings.displayMobNames = v),
        }),
        new Section("Flight & safety", new Opt[]{
            Opt.of("Display flight directors", DESC_FLIGHT, () -> Settings.displayFlightDirectors, v -> Settings.displayFlightDirectors = v),
            Opt.of("Elytra durability failsafe", DESC_ELYTRA, () -> Settings.elytraDuraFailsafe, v -> Settings.elytraDuraFailsafe = v),
            Opt.of("Terrain crash protection", DESC_CRASH, () -> Settings.crashProtection, v -> Settings.crashProtection = v),
            Opt.of("Fall prevention", DESC_FALL, () -> Settings.fallPrevention, v -> Settings.fallPrevention = v),
        }),
        new Section("Combat", new Opt[]{
            Opt.of("Projectile targeting reticle", DESC_RETICLE, () -> Settings.projectileReticle, v -> Settings.projectileReticle = v),
            Opt.of("Projectile glow", DESC_GLOW, () -> Settings.projectileGlow, v -> Settings.projectileGlow = v),
            Opt.of("Auto aim", DESC_AUTOAIM, () -> Settings.autoAim, v -> Settings.autoAim = v),
            Opt.of("Projectile dodge", DESC_DODGE, () -> Settings.projectileDodge, v -> Settings.projectileDodge = v),
        }),
    };

    @Override
    protected void init() {
        rowX = MARGIN;
        colW = Math.min(280, this.width / 2 - MARGIN - 8);
        headH = this.font.lineHeight + 6;
        viewTop = 30;
        int doneH = 18;
        viewBottom = this.height - doneH - 12;

        int nOpts = 1;
        for (Section s : SECTIONS) nOpts += s.opts().length;
        rowOpt = new Opt[nOpts];
        baseRowY = new int[nOpts];
        rowButton = new Button[nOpts];
        baseHeaderY = new int[SECTIONS.length];

        int cY = 0, idx = 0;
        // mode selector at the top, then a divider
        rowOpt[idx] = FLIGHT_MODE; baseRowY[idx] = cY;
        rowButton[idx] = addToggle(rowX, viewTop + cY, colW, ROW_H, FLIGHT_MODE);
        idx++; cY += ROW_STEP;
        baseModeDivY = cY + 1; cY += MODE_DIV;

        for (int s = 0; s < SECTIONS.length; s++) {
            baseHeaderY[s] = cY;
            cY += headH;
            for (Opt opt : SECTIONS[s].opts()) {
                rowOpt[idx] = opt; baseRowY[idx] = cY;
                rowButton[idx] = addToggle(rowX, viewTop + cY, colW, ROW_H, opt);
                idx++; cY += ROW_STEP;
            }
            cY += GAP;
        }
        contentHeight = cY;
        maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        layout();

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds(rowX, this.height - doneH - 6, colW, doneH).build());
    }

    /** Reposition rows for the current scroll offset and hide any not fully inside the viewport. */
    private void layout() {
        for (int i = 0; i < rowButton.length; i++) {
            int sy = viewTop + baseRowY[i] - scrollOffset;
            rowButton[i].setY(sy);
            rowButton[i].visible = sy >= viewTop && sy + ROW_H <= viewBottom;
        }
    }

    private Button addToggle(int x, int y, int w, int h, Opt opt) {
        Button b = Button.builder(label(opt, opt.get().getAsBoolean()), btn -> {
            boolean nv = !opt.get().getAsBoolean();
            opt.set().accept(nv);
            btn.setMessage(label(opt, nv));
            Settings.save();
        }).bounds(x, y, w, h).build();
        addRenderableWidget(b);
        return b;
    }

    private static Component label(Opt opt, boolean on) {
        return Component.literal(opt.name() + ": " + (on ? opt.onText() : opt.offText()));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (maxScroll > 0) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (dy * 18)));
            layout();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        layout();
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        // section headers + dividers, clipped to the scrolling viewport
        graphics.enableScissor(0, viewTop, this.width, viewBottom);
        int my = viewTop + baseModeDivY - scrollOffset;
        graphics.fill(rowX, my, rowX + colW, my + 1, DIVIDER);
        for (int s = 0; s < SECTIONS.length; s++) {
            int hy = viewTop + baseHeaderY[s] - scrollOffset;
            graphics.text(this.font, Component.literal(SECTIONS[s].title()), rowX, hy, HEADER);
            int dy = hy + this.font.lineHeight + 2;
            graphics.fill(rowX, dy, rowX + colW, dy + 1, DIVIDER);
        }
        graphics.disableScissor();

        // scrollbar
        if (maxScroll > 0) {
            int barX = rowX + colW + 4, viewH = viewBottom - viewTop;
            int thumbH = Math.max(20, viewH * viewH / contentHeight);
            int thumbY = viewTop + (int) ((long) (viewH - thumbH) * scrollOffset / maxScroll);
            graphics.fill(barX, viewTop, barX + 2, viewBottom, 0xFF2A2A2A);
            graphics.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFF8A8A8A);
        }

        // right-side description panel for the hovered row
        int descX = rowX + colW + MARGIN;
        int descW = this.width - descX - MARGIN;
        if (descW < 80) return;

        Opt hovered = null;
        for (int i = 0; i < rowOpt.length; i++) {
            int sy = viewTop + baseRowY[i] - scrollOffset;
            if (rowButton[i].visible && mouseX >= rowX && mouseX <= rowX + colW
                && mouseY >= sy && mouseY <= sy + ROW_H) {
                hovered = rowOpt[i];
                break;
            }
        }

        String text = hovered != null ? hovered.desc() : "Hover an option for details.";
        int color = hovered != null ? 0xFFFFFFFF : 0xFF909090;
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), descW);
        int ty = viewTop;
        for (FormattedCharSequence line : lines) {
            graphics.text(this.font, line, descX, ty, color);
            ty += this.font.lineHeight + 2;
        }
    }

    @Override
    public void onClose() {
        Settings.save();
        this.minecraft.setScreen(parent);
    }
}
