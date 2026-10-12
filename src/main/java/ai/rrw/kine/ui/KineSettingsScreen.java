package ai.rrw.kine.ui;

import ai.rrw.kine.Settings;
import ai.rrw.kine.hud.ThinOutlines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Kine's settings (K). A switch for the whole mod at the top, one on each section's header, and a button per
 * feature: on/off, or for the elytra, speed and altitude displays off, elytra only or always. A feature
 * whose section or the whole mod is switched off greys out and keeps its own setting. Hovering a row shows
 * its description on the right.
 */
public class KineSettingsScreen extends Screen {

    private static final int MARGIN  = 16;
    private static final int DIVIDER = 0xFF555555;
    private static final int HEADER  = 0xFFBBBBBB;
    private static final int ROW_H    = 20;
    private static final int ROW_STEP = 22;
    private static final int GAP      = 8;     // between sections
    private static final int SWITCH_W = 44;    // a section header's on/off button

    private final Screen parent;

    // layout: every scrolling widget with its content-space y and the text shown when it's hovered
    private static final int ALL = -2, OPTION = -1;   // Row.switchOf: the whole mod, a feature, else a section's index
    private record Row(Button button, int baseY, String desc, Opt opt, int switchOf) {}
    private final List<Row> rows = new ArrayList<>();
    private int[] baseHeaderY;
    private int rowX, colW, headH;
    private int viewTop, viewBottom, contentHeight, maxScroll, scrollOffset;
    private int descTextTop;   // top of the description text on the right, below the reset button

    public KineSettingsScreen(Screen parent) {
        super(Component.literal("Kine settings"));
        this.parent = parent;
    }

    private record Opt(String name, String desc, Settings.Option option) {
        static Opt of(String n, String d, Settings.Option o) { return new Opt(n, d, o); }
    }

    private record Section(Settings.Section section, Opt[] opts) {}

    // Descriptions: concise, most important first, similar lengths.
    private static final String DESC_SPEED =
        "Shows your total speed in m/s above the hotbar, vertical motion included, measured from your actual "
        + "movement each tick.";
    private static final String DESC_GROUND =
        "Shows your horizontal speed in m/s above the hotbar, ignoring vertical motion. Range and ETA "
        + "estimates use this speed.";
    private static final String DESC_HEALTH =
        "Shows the current and maximum health above every mob, player and armour stand within 30 blocks, "
        + "scaled by distance and visible through walls.";
    private static final String DESC_NAMES =
        "Shows the name of every mob, player and armour stand within 30 blocks at the middle of its body, "
        + "in smaller text, visible through walls.";

    private static final String DESC_FLIGHT =
        "While gliding high enough to arm the autopilot, magenta bars show the pitch and heading it would "
        + "fly, with the climb mode beside them. Guidance only.";
    private static final String DESC_FPV =
        "Marks where your motion is taking you, not where you are looking; put it on the spot you want to "
        + "reach. Shows while gliding or after falling a few blocks.";
    private static final String DESC_PROFILE =
        "While gliding, a side-on cutaway in the top-right corner shows the terrain ahead out to your "
        + "render distance. With the autopilot engaged it follows and traces the path it predicts, turns included.";
    private static final String DESC_RANGE =
        "While wearing an elytra, shows how long and how far you can still glide on every elytra you carry, "
        + "keeping back what a landing from your height needs. Range uses your recent average speed, less 10%.";
    private static final String DESC_RIBBON =
        "While the autopilot is engaged, draws the path it predicts as two rails in the world: blue seen "
        + "from above, orange from below.";
    private static final String DESC_TERRAIN =
        "The autopilot reads the terrain ahead: it climbs over hills, turns around obstacles, banks away "
        + "from close walls, and hands back control if boxed in. It only arms when it finds a safe path.";

    private static final String DESC_ELYTRA =
        "Leave this on; turn it off only if a bug of ours keeps getting in your way. Swaps in a fresh elytra "
        + "when the worn one is down to 3 durability. When the last one runs low, the autopilot lands; flying "
        + "by hand, you're warned, and logged out if you don't respond.";
    private static final String DESC_FALL =
        "Sneaks for you at the edge of a drop of more than three blocks, so you stop there. Jump to get past "
        + "it. Water or hay below still counts as a drop.";
    private static final String DESC_CLUTCH =
        "When you fall far enough to get hurt, sneaks, places water where you will land and scoops it back "
        + "up. Uses a bucket from your hotbar or offhand, or moves one up from your inventory. Survival mode only.";
    private static final String DESC_AFK =
        "Logs you out if you take damage after fifteen seconds with no input. Autopilot camera motion "
        + "doesn't count as input; an open menu does while the game window is in front.";

    private static final String DESC_RETICLE =
        "Draws a ring where your bow, crossbow, trident or thrown item will land, allowing for gravity, drag "
        + "and your own motion. Turns green and boxes the target when it covers one.";
    private static final String DESC_GLOW =
        "Outlines every projectile in red, visible through walls, in flight and after it lands, including "
        + "other players' projectiles.";
    private static final String DESC_AUTOAIM =
        "Turns your view so bow, crossbow and trident shots hit the target nearest your aim, and stays on it "
        + "as it moves, leading it. Move the mouse to break the lock. An aimbot: servers may ban it.";
    private static final String DESC_DODGE =
        "Presses movement keys to step you off a projectile's line when it is about to hit you. On foot only; "
        + "can't dodge point-blank or very fast shots. Lower ban risk than the aimbot.";
    private static final String DESC_AURA =
        "Attacks hostile mobs in reach, and neutral ones like endermen after they hit you (piglins also while "
        + "you wear no gold). Hits mobs about to reach you first, and times swings for the most damage. Your view "
        + "stays put; the server sees turns no faster than a quick flick. Servers may ban it.";
    private static final String DESC_WEAPON =
        "While the kill aura fights, holds the best sword or axe for the target, moving one up from your "
        + "inventory if needed (into an empty hotbar slot, else in place of the largest stack).";
    private static final String DESC_MINING =
        "While you break a block, shows how far through it you are and the time left, just below the "
        + "crosshair.";
    private static final String DESC_HEADING =
        "A compass arc across the top of the screen with your heading boxed above its middle, and a green "
        + "bug at the heading selected in the nav screen.";
    private static final String DESC_ALTITUDE =
        "Your height (Y level) in green, level with the crosshair to its right, clear of the flight "
        + "director bars.";
    private static final String DESC_AGL =
        "Your height above whatever is straight below you, in amber above the hotbar. Water and leaves "
        + "count as ground; over the void it hides.";
    private static final String DESC_NAV_READOUTS =
        "The selected heading or destination with its distance and ETA, the autopilot status (AP), a beam "
        + "over the destination, and a warning if your elytras can't make the distance.";
    private static final String DESC_AUTOPILOT =
        "Press J while gliding high enough to engage the autopilot, which flies the efficient climb and "
        + "cruise for you. Off: J does nothing.";
    private static final String DESC_TAKEOFF =
        "Standing with an elytra on and rockets in hand, left click to take off: straight up, a 70 degree rocket "
        + "climb, then the autopilot at its cruise level, using the fewest rockets. Needs open sky above you.";
    private static final String DESC_LANDING =
        "Gliding by hand, a green square marks a safe spot you look at; left click and the autopilot lands there: "
        + "a fast spiral down, a brake well above the ground, a slow finish. Walls give their top or their foot.";
    private static final String DESC_NAV =
        "Press N for the nav screen: fly a heading or to coordinates, with landing at the destination. "
        + "Off: N does nothing and the autopilot keeps its heading.";
    private static final String DESC_OUTLINES =
        "Keeps the red projectile outlines thin and at full colour. It changes every outline in the game "
        + "while on; switching it reloads the game's resources.";
    private static final String DESC_FUEL =
        "Counts your rockets' burn in flight-duration-1 rockets, a green number with a rocket icon beside END "
        + "and RNG, and while one boosts you, a bar of how far through its burn it likely is.";
    private static final String DESC_ALL =
        "Switches every Kine feature off at once, and back on, keeping each one's own setting. K still opens "
        + "this menu.";
    private static final String DESC_SECTION =
        "Switches every feature in this section off at once, and back on, keeping each one's own setting.";
    private static final String HINT =
        "Hover an option for details. ELYTRA ONLY shows a display only while you wear an elytra; ALWAYS "
        + "shows it everywhere.";


    private static final Section[] SECTIONS = {
        new Section(Settings.Section.HUD, new Opt[]{
            Opt.of("Display mob healths", DESC_HEALTH, Settings.DISPLAY_MOB_HEALTHS),
            Opt.of("Display mob names", DESC_NAMES, Settings.DISPLAY_MOB_NAMES),
            Opt.of("Block breaking progress", DESC_MINING, Settings.DISPLAY_MINING_PROGRESS),
            Opt.of("Display speed", DESC_SPEED, Settings.DISPLAY_SPEED),
            Opt.of("Display ground speed", DESC_GROUND, Settings.DISPLAY_GROUND_SPEED),
        }),
        new Section(Settings.Section.FLIGHT, new Opt[]{
            Opt.of("Heading arc", DESC_HEADING, Settings.DISPLAY_HEADING_ARC),
            Opt.of("Altitude", DESC_ALTITUDE, Settings.DISPLAY_ALTITUDE),
            Opt.of("Height above ground", DESC_AGL, Settings.DISPLAY_HEIGHT_ABOVE_GROUND),
            Opt.of("Nav & AP readouts", DESC_NAV_READOUTS, Settings.DISPLAY_NAV_READOUTS),
            Opt.of("Flight directors", DESC_FLIGHT, Settings.DISPLAY_FLIGHT_DIRECTORS),
            Opt.of("Flight path vector", DESC_FPV, Settings.DISPLAY_FLIGHT_PATH_VECTOR),
            Opt.of("Flight profile map", DESC_PROFILE, Settings.DISPLAY_FLIGHT_PROFILE),
            Opt.of("Range & endurance", DESC_RANGE, Settings.DISPLAY_RANGE_ENDURANCE),
            Opt.of("Firework fuel", DESC_FUEL, Settings.DISPLAY_FIREWORK_FUEL),
            Opt.of("Flight path rails", DESC_RIBBON, Settings.FLIGHT_PATH_RAILS),
            Opt.of("Autopilot", DESC_AUTOPILOT, Settings.AUTOPILOT),
            Opt.of("Rocket takeoff", DESC_TAKEOFF, Settings.TAKEOFF),
            Opt.of("Aimed landing", DESC_LANDING, Settings.LANDING),
            Opt.of("Navigation", DESC_NAV, Settings.NAVIGATION),
            Opt.of("Terrain avoidance", DESC_TERRAIN, Settings.TERRAIN_AVOIDANCE),
        }),
        new Section(Settings.Section.SAFETY, new Opt[]{
            Opt.of("Elytra durability failsafe", DESC_ELYTRA, Settings.ELYTRA_DURA_FAILSAFE),
            Opt.of("Fall prevention", DESC_FALL, Settings.FALL_PREVENTION),
            Opt.of("Water bucket clutch", DESC_CLUTCH, Settings.WATER_BUCKET_CLUTCH),
            Opt.of("AFK damage protection", DESC_AFK, Settings.AFK_DAMAGE_PROTECTION),
        }),
        // ordered by ban risk, the riskiest last: client-side only, then movement keys, then fighting
        new Section(Settings.Section.COMBAT, new Opt[]{
            Opt.of("Projectile targeting reticle", DESC_RETICLE, Settings.PROJECTILE_RETICLE),
            Opt.of("Projectile glow", DESC_GLOW, Settings.PROJECTILE_GLOW),
            Opt.of("Thin projectile outlines", DESC_OUTLINES, Settings.THIN_OUTLINES),
            Opt.of("Projectile dodge", DESC_DODGE, Settings.PROJECTILE_DODGE),
            Opt.of("Auto weapon", DESC_WEAPON, Settings.AUTO_WEAPON),
            Opt.of("Auto aim", DESC_AUTOAIM, Settings.AUTO_AIM),
            Opt.of("Kill aura", DESC_AURA, Settings.KILL_AURA),
        }),
    };

    @Override
    protected void init() {
        // the outline pack can also be switched in the game's resource pack screen: take its state as the setting
        if (Settings.enabled() && Settings.sectionOn(Settings.Section.COMBAT)
            && Settings.thinOutlines != ThinOutlines.selected(this.minecraft)) Settings.THIN_OUTLINES.cycle();

        rowX = MARGIN;
        colW = Math.min(280, this.width / 2 - MARGIN - 8);
        headH = ROW_H + 4;
        viewTop = 30;
        int doneH = 18;
        viewBottom = this.height - doneH - 12;
        rows.clear();
        baseHeaderY = new int[SECTIONS.length];

        int cY = 0;
        Button all = Button.builder(allLabel(), b -> { Settings.setEnabled(!Settings.enabled()); changed(); })
            .bounds(rowX, viewTop, colW, ROW_H).build();
        rows.add(new Row(addRenderableWidget(all), cY, DESC_ALL, null, ALL));
        cY += ROW_STEP + GAP;
        for (int s = 0; s < SECTIONS.length; s++) {
            Settings.Section sec = SECTIONS[s].section();
            baseHeaderY[s] = cY;
            Button sw = Button.builder(onOff(Settings.sectionOn(sec)), b -> { Settings.setSectionOn(sec, !Settings.sectionOn(sec)); changed(); })
                .bounds(rowX + colW - SWITCH_W, viewTop + cY, SWITCH_W, ROW_H).build();
            rows.add(new Row(addRenderableWidget(sw), cY, DESC_SECTION, null, s));
            cY += headH;
            for (Opt opt : SECTIONS[s].opts()) {
                Button b = Button.builder(label(opt), btn -> { opt.option().cycle(); changed(); })
                    .bounds(rowX, viewTop + cY, colW, ROW_H).build();
                rows.add(new Row(addRenderableWidget(b), cY, opt.desc(), opt, OPTION));
                cY += ROW_STEP;
            }
            cY += GAP;
        }
        contentHeight = cY;
        maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        refresh();
        layout();

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds(rowX, this.height - doneH - 6, colW, doneH).build());

        // reset to defaults, pinned to the top right of the scrolling area (it doesn't scroll)
        int resetH = 18, resetW = 150;
        int minResetX = rowX + colW + 8;
        int resetX = this.width - MARGIN - resetW;
        if (resetX < minResetX) { resetX = minResetX; resetW = Math.max(60, this.width - MARGIN - minResetX); }
        descTextTop = viewTop + resetH + 6;
        addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), b -> { Settings.resetDefaults(); changed(); })
            .bounds(resetX, viewTop, resetW, resetH).build());
    }

    /** After any change: relabel every button, and switch the outline pack if its effective setting moved. */
    private void changed() {
        refresh();
        ThinOutlines.sync(this.minecraft);
    }

    private void refresh() {
        for (Row r : rows) {
            if (r.switchOf() == ALL) {
                r.button().setMessage(allLabel());
            } else if (r.switchOf() >= 0) {
                r.button().setMessage(onOff(Settings.sectionOn(SECTIONS[r.switchOf()].section())));
                r.button().active = Settings.enabled();
            } else {
                r.button().setMessage(label(r.opt()));
                r.button().active = Settings.enabled() && Settings.sectionOn(r.opt().option().section);
            }
        }
    }

    /** Reposition rows for the current scroll offset and hide any not fully inside the viewport. */
    private void layout() {
        for (Row r : rows) {
            int sy = viewTop + r.baseY() - scrollOffset;
            r.button().setY(sy);
            r.button().visible = sy >= viewTop && sy + ROW_H <= viewBottom;
        }
    }

    private static Component allLabel() {
        return Component.literal("All features: " + (Settings.enabled() ? "ON" : "OFF"));
    }

    private static Component onOff(boolean on) {
        return Component.literal(on ? "ON" : "OFF");
    }

    private static Component label(Opt opt) {
        return Component.literal(opt.name() + ": " + opt.option().label());
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
            int ty = hy + (ROW_H - this.font.lineHeight) / 2;
            boolean on = Settings.enabled() && Settings.sectionOn(SECTIONS[s].section());
            graphics.text(this.font, Component.literal(SECTIONS[s].section().title), rowX, ty, on ? HEADER : 0xFF777777);
            int dy = hy + ROW_H + 1;
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

        // description of the hovered row, on the right
        int descX = rowX + colW + MARGIN;
        int descW = this.width - descX - MARGIN;
        if (descW < 80) return;

        String hovered = null;
        for (Row r : rows) {
            Button b = r.button();
            if (b.visible && mouseX >= b.getX() && mouseX <= b.getX() + b.getWidth()
                && mouseY >= b.getY() && mouseY <= b.getY() + ROW_H) { hovered = r.desc(); break; }
        }

        String text = hovered != null ? hovered : HINT;
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
