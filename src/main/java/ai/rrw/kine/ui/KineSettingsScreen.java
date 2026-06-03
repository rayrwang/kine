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

    private final Screen parent;

    // layout
    private int rowX, colW, headH;
    private int viewTop, viewBottom, contentHeight, maxScroll, scrollOffset;
    private int[] baseHeaderY;
    private int[] baseRowY;       // content-space y of each toggle row
    private Opt[] rowOpt;
    private Button[] rowButton;
    private int descTextTop;   // where the right-hand description text starts (below the reset button)

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
    }

    private record Section(String title, Opt[] opts) {}

    // ---- descriptions ----
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
    private static final String DESC_RANGE =
        "While wearing an elytra, estimates flight endurance (time) and range (distance) from "
        + "everything you can reach in the air: the worn elytra plus every spare in your inventory and "
        + "offhand (not shulker boxes \u2014 you'd have to land). Accounts for each elytra's Unbreaking. "
        + "Range uses your own recent average flight speed, so it tracks how you actually fly. Reserves "
        + "are held back aviation-style: a 5% contingency plus a final reserve to glide down safely from "
        + "your current height, so it hits zero right as the durability failsafe would. Shown near the hotbar.";
    private static final String DESC_FLIGHT =
        "While elytra-flying, overlays magenta guidance bars showing the pitch to hold for the "
        + "energy-pumping technique: dive to build speed, snap the nose up, then ease it back down. "
        + "They appear only with enough clear air below to complete a dive. Guidance only \u2014 it "
        + "never moves you.";
    private static final String DESC_FPV =
        "The winged ring marks where your velocity is actually taking you, not where you're looking: "
        + "below the centre means descending, off to one side means drifting that way. Put it on the "
        + "spot you want to reach. Shows while flying or in a real fall. Instrument only.";
    private static final String DESC_ELYTRA =
        "Leave this on unless a bug keeps kicking you \u2014 that's the only good reason to turn it "
        + "off. While elytra-flying it warns when durability is too low to safely glide down from your "
        + "current altitude (higher = larger reserve, with healthy margins). When that warning trips, "
        + "if you've got a spare elytra fresh enough to clear it, it hot-swaps automatically; if not, "
        + "the warning stays up so you land. Once a wing is about to break it'll swap to any fresher "
        + "spare you have left, just to keep you airborne. If nothing's left to swap to, then on "
        + "autopilot \u2014 if you don't take control within 5s \u2014 it disconnects you, logging out "
        + "before the elytra breaks so you don't fall to your death. Flying manually, it only warns.";
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

    private static final String DESC_AFK =
        "If you take any damage while you've given no input \u2014 no movement keys, mouse, or open screen "
        + "\u2014 for fifteen seconds, you're logged out before something can kill you unattended. The "
        + "disconnect screen names what hit you. The autopilot moving the camera doesn't count as input, "
        + "so it still protects you on a hands-off flight; while a menu is open you're treated as present.";

    private static final String DESC_CLUTCH =
        "Auto water-bucket clutch. While you're free-falling hard enough to get hurt and have a water "
        + "bucket in your hotbar or offhand, it drops water on the spot you're about to land and scoops "
        + "it back up once you're safe \\u2014 sneaking as it places, so slabs, stairs and fences get the "
        + "water on top rather than waterlogged. Never runs while gliding; a broken elytra is just falling. "
        + "Doesn't work where water evaporates (the Nether).";

    private static final String DESC_TERRAIN =
        "Experimental. Lets the elytra autopilot climb to clear rising terrain ahead instead of holding a "
        + "fixed altitude: each tick it rolls the flight model forward over the ground ahead and picks the "
        + "lowest hold altitude that keeps a safe margin, resuming your set altitude once the ground drops "
        + "away. It only flies straight and only adjusts altitude \u2014 if a climb can't clear what's ahead, "
        + "or it can't see far enough (low render distance), it disengages and hands control back to you. "
        + "Needs the autopilot engaged; off by default.";

    private static final String DESC_RIBBON =
        "Draws the autopilot's predicted path ahead of (and a little behind) you as two blue rails at "
        + "ground level, so you can see where the porpoise will carry you and how it clears terrain. The "
        + "rails are the actual rolled-out trajectory, so they also reveal any drift between the model and "
        + "real flight. Only shown while the autopilot is engaged.";

    private static final Section[] SECTIONS = {
        new Section("Heads-up display", new Opt[]{
            Opt.of("Display speed", DESC_SPEED, () -> Settings.displaySpeed, v -> Settings.displaySpeed = v),
            Opt.of("Display ground speed", DESC_GROUND, () -> Settings.displayGroundSpeed, v -> Settings.displayGroundSpeed = v),
            Opt.of("Display mob healths", DESC_HEALTH, () -> Settings.displayMobHealths, v -> Settings.displayMobHealths = v),
            Opt.of("Display mob names", DESC_NAMES, () -> Settings.displayMobNames, v -> Settings.displayMobNames = v),
            Opt.of("Range & endurance", DESC_RANGE, () -> Settings.displayRangeEndurance, v -> Settings.displayRangeEndurance = v),
        }),
        new Section("Flight & safety", new Opt[]{
            Opt.of("Display flight directors", DESC_FLIGHT, () -> Settings.displayFlightDirectors, v -> Settings.displayFlightDirectors = v),
            Opt.of("Terrain avoidance", DESC_TERRAIN, () -> Settings.terrainAvoidance, v -> Settings.terrainAvoidance = v),
            Opt.of("Flight path rails", DESC_RIBBON, () -> Settings.flightRibbon, v -> Settings.flightRibbon = v),
            Opt.of("Flight path vector", DESC_FPV, () -> Settings.displayFlightPathVector, v -> Settings.displayFlightPathVector = v),
            Opt.of("Elytra durability failsafe", DESC_ELYTRA, () -> Settings.elytraDuraFailsafe, v -> Settings.elytraDuraFailsafe = v),
            Opt.of("Terrain crash protection", DESC_CRASH, () -> Settings.crashProtection, v -> Settings.crashProtection = v),
            Opt.of("Fall prevention", DESC_FALL, () -> Settings.fallPrevention, v -> Settings.fallPrevention = v),
            Opt.of("AFK damage protection", DESC_AFK, () -> Settings.afkDamageProtection, v -> Settings.afkDamageProtection = v),
            Opt.of("Water bucket clutch", DESC_CLUTCH, () -> Settings.waterBucketClutch, v -> Settings.waterBucketClutch = v),
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

        int nOpts = 0;
        for (Section s : SECTIONS) nOpts += s.opts().length;
        rowOpt = new Opt[nOpts];
        baseRowY = new int[nOpts];
        rowButton = new Button[nOpts];
        baseHeaderY = new int[SECTIONS.length];

        int cY = 0, idx = 0;
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

        // reset-to-defaults, pinned to the top-right of the scrolling area (doesn't scroll)
        int resetH = 18, resetW = 150;
        int minResetX = rowX + colW + 8;
        int resetX = this.width - MARGIN - resetW;
        if (resetX < minResetX) { resetX = minResetX; resetW = Math.max(60, this.width - MARGIN - minResetX); }
        descTextTop = viewTop + resetH + 6;
        addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), b -> resetAll())
            .bounds(resetX, viewTop, resetW, resetH).build());
    }

    /** Restore defaults and refresh every toggle label to match. */
    private void resetAll() {
        Settings.resetDefaults();
        for (int i = 0; i < rowButton.length; i++) {
            rowButton[i].setMessage(label(rowOpt[i], rowOpt[i].get().getAsBoolean()));
        }
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
        int ty = descTextTop;
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
