package ai.rrw.kine.ui;

import ai.rrw.kine.Settings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

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
    private int descTextTop;   // top of the description text on the right, below the reset button

    public KineSettingsScreen(Screen parent) {
        super(Component.literal("Kine settings"));
        this.parent = parent;
    }

    // option model
    private record Opt(String name, String desc, Settings.Toggle toggle) {
        static Opt of(String n, String d, Settings.Toggle t) { return new Opt(n, d, t); }
        boolean get() { return toggle.get().getAsBoolean(); }
        void set(boolean v) { toggle.set().accept(v); }
    }

    private record Section(String title, Opt[] opts) {}

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
        + "fly, with your altitude and climb mode beside them. Guidance only.";
    private static final String DESC_FPV =
        "Marks where your motion is taking you, not where you are looking; put it on the spot you want to "
        + "reach. Shows while gliding or after falling a few blocks.";
    private static final String DESC_PROFILE =
        "While gliding, a side-on cutaway in the top-right corner shows the terrain ahead out to your "
        + "render distance. With the autopilot engaged it follows and traces the path it predicts, turns included.";
    private static final String DESC_RANGE =
        "While wearing an elytra, shows how long and how far you can still glide on every elytra you carry, "
        + "keeping a reserve for gliding down. Range uses your recent average speed.";
    private static final String DESC_RIBBON =
        "While the autopilot is engaged, draws the path it predicts as two rails in the world: blue seen "
        + "from above, orange from below.";
    private static final String DESC_TERRAIN =
        "The autopilot reads the terrain ahead: it climbs over hills, turns around obstacles, banks away "
        + "from close walls, and hands back control if boxed in. It only arms when it finds a safe path.";

    private static final String DESC_ELYTRA =
        "The only reason you should ever disable this is if you keep getting kicked due to a bug which is "
        + "our fault. Warns when durability is too low to glide down safely and swaps in a fresher spare. "
        + "On autopilot it logs you out before the wing breaks.";
    private static final String DESC_CRASH =
        "While gliding, slows you before you hit the ground, a wall or a ceiling. It only removes speed "
        + "heading into a surface and never turns you.";
    private static final String DESC_FALL =
        "Stops you walking off a drop of more than three blocks, like sneaking does. Jump to get past it. "
        + "Water or hay below still counts as a drop.";
    private static final String DESC_CLUTCH =
        "When you fall far enough to get hurt, places water where you will land and scoops it back up. "
        + "Uses a bucket from your hotbar or offhand, or moves one up from your inventory.";
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
        "Turns your view so bow, crossbow and trident shots hit the target nearest your reticle, leading "
        + "moving targets. Move the mouse to break the lock. An aimbot: servers may ban it.";
    private static final String DESC_DODGE =
        "Pushes you sideways at sprint speed when a projectile is about to hit you. Can't dodge point-blank "
        + "or very fast shots. Lower ban risk than the aimbot.";
    private static final String DESC_AURA =
        "Attacks hostile mobs in reach, and neutral ones like endermen after they hit you (piglins also while "
        + "you wear no gold), without turning your view. Spams crowds, else waits for full charge. Servers may ban it.";
    private static final String DESC_WEAPON =
        "While the kill aura fights, holds the best sword or axe for the target, moving one up from your "
        + "inventory if needed (into an empty hotbar slot, else in place of the largest stack).";

    private static final Section[] SECTIONS = {
        new Section("Heads-up display", new Opt[]{
            Opt.of("Display speed", DESC_SPEED, Settings.DISPLAY_SPEED),
            Opt.of("Display ground speed", DESC_GROUND, Settings.DISPLAY_GROUND_SPEED),
            Opt.of("Display mob healths", DESC_HEALTH, Settings.DISPLAY_MOB_HEALTHS),
            Opt.of("Display mob names", DESC_NAMES, Settings.DISPLAY_MOB_NAMES),
        }),
        new Section("Elytra flight", new Opt[]{
            Opt.of("Flight directors", DESC_FLIGHT, Settings.DISPLAY_FLIGHT_DIRECTORS),
            Opt.of("Flight path vector", DESC_FPV, Settings.DISPLAY_FLIGHT_PATH_VECTOR),
            Opt.of("Flight profile map", DESC_PROFILE, Settings.DISPLAY_FLIGHT_PROFILE),
            Opt.of("Range & endurance", DESC_RANGE, Settings.DISPLAY_RANGE_ENDURANCE),
            Opt.of("Flight path rails", DESC_RIBBON, Settings.FLIGHT_PATH_RAILS),
            Opt.of("Terrain avoidance", DESC_TERRAIN, Settings.TERRAIN_AVOIDANCE),
        }),
        new Section("Safety", new Opt[]{
            Opt.of("Elytra durability failsafe", DESC_ELYTRA, Settings.ELYTRA_DURA_FAILSAFE),
            Opt.of("Elytra crash protection", DESC_CRASH, Settings.CRASH_PROTECTION),
            Opt.of("Fall prevention", DESC_FALL, Settings.FALL_PREVENTION),
            Opt.of("Water bucket clutch", DESC_CLUTCH, Settings.WATER_BUCKET_CLUTCH),
            Opt.of("AFK damage protection", DESC_AFK, Settings.AFK_DAMAGE_PROTECTION),
        }),
        new Section("Combat", new Opt[]{
            Opt.of("Projectile targeting reticle", DESC_RETICLE, Settings.PROJECTILE_RETICLE),
            Opt.of("Projectile glow", DESC_GLOW, Settings.PROJECTILE_GLOW),
            Opt.of("Auto aim", DESC_AUTOAIM, Settings.AUTO_AIM),
            Opt.of("Projectile dodge", DESC_DODGE, Settings.PROJECTILE_DODGE),
            Opt.of("Kill aura", DESC_AURA, Settings.KILL_AURA),
            Opt.of("Auto weapon", DESC_WEAPON, Settings.AUTO_WEAPON),
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

        // reset to defaults, pinned to the top right of the scrolling area (it doesn't scroll)
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
            rowButton[i].setMessage(label(rowOpt[i], rowOpt[i].get()));
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
        Button b = Button.builder(label(opt, opt.get()), btn -> {
            boolean nv = !opt.get();
            opt.set(nv);
            btn.setMessage(label(opt, nv));
            Settings.save();
        }).bounds(x, y, w, h).build();
        addRenderableWidget(b);
        return b;
    }

    private static Component label(Opt opt, boolean on) {
        return Component.literal(opt.name() + ": " + (on ? "ON" : "OFF"));
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

        // description of the hovered option, on the right
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
