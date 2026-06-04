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

    // ---- descriptions (kept short and uniform; most important first) ----
    private static final String DESC_SPEED =
        "Total movement speed in m/s, including vertical motion, measured from your real per-tick position "
        + "change rather than the game's under-reported velocity. Drawn above the hotbar.";
    private static final String DESC_GROUND =
        "Horizontal speed only in m/s, ignoring vertical motion. This is the number that governs elytra "
        + "travel distance and your walking or sprinting pace.";
    private static final String DESC_HEALTH =
        "Floats each creature's current and maximum health above it, scaled by distance and visible through "
        + "walls. Useful for tracking damage during a fight.";
    private static final String DESC_NAMES =
        "Floats each creature's name above it, beside the health readout. Off by default because it adds "
        + "clutter, especially on busy servers.";

    private static final String DESC_FLIGHT =
        "While elytra flying, magenta bars show the pitch to hold for the energy-pumping technique: dive to "
        + "build speed, snap the nose up, then ease it down. It is guidance only and never moves you.";
    private static final String DESC_FPV =
        "Marks where your velocity is actually taking you, not where you are looking. Place it on the spot "
        + "you want to reach to steer there. Shows while flying or falling. Instrument only.";
    private static final String DESC_PROFILE =
        "Side-on cutaway in the top-right corner: a blue trace of where your held attitude would carry you "
        + "over the next 240 blocks, against the terrain below. Where they meet is where you land.";
    private static final String DESC_RANGE =
        "While wearing an elytra, estimates remaining flight time and distance from every elytra you can "
        + "reach and your recent average speed. Holds back a reserve to glide down safely.";
    private static final String DESC_RIBBON =
        "Draws the autopilot's predicted path as two blue rails at ground level, so you can see where it "
        + "will carry you and how it clears terrain. Shown only while the autopilot is engaged.";
    private static final String DESC_TERRAIN =
        "Lets the elytra autopilot climb to clear rising terrain instead of holding a fixed "
        + "altitude, resuming once it drops away. Disengages if a climb can't clear what's ahead.";

    private static final String DESC_ELYTRA =
        "While elytra flying, warns when durability is too low to glide down safely and hot-swaps to a fresh "
        + "spare if you have one. As a last resort it logs you out before the wing breaks. Best left on.";
    private static final String DESC_CRASH =
        "While elytra flying, sheds speed before you hit a wall, ceiling, or ground so you land safely "
        + "instead of dying. It only removes velocity heading into a surface, never turning you.";
    private static final String DESC_FALL =
        "Stops you walking off ledges with a dangerous drop, like sneaking but automatic. Safe drops, "
        + "deliberate jumps, and water or hay landings are unaffected.";
    private static final String DESC_CLUTCH =
        "While falling hard with a water bucket in your hotbar or offhand, drops water where you will land "
        + "and scoops it back once you are safe. Won't run while gliding or where water evaporates.";
    private static final String DESC_AFK =
        "If you take damage after fifteen seconds with no input, logs you out before something kills you "
        + "unattended. Autopilot camera motion doesn't count, and an open menu counts as present.";

    private static final String DESC_RETICLE =
        "Draws a ring where your readied projectile will land (bow, trident, snowball, pearl, and so on), "
        + "accounting for gravity, drag, and your own motion. Turns green when it covers a valid target.";
    private static final String DESC_GLOW =
        "Outlines every projectile with a glow visible through walls, in flight and after it lands, so "
        + "arrows and thrown items are easy to follow. Includes projectiles thrown by others.";
    private static final String DESC_AUTOAIM =
        "Steers your view so bow, crossbow, and trident shots land on the target nearest your reticle, "
        + "leading moving targets. Nudge the mouse to break lock. An aimbot, bannable where mods aren't allowed.";
    private static final String DESC_DODGE =
        "Watches for incoming projectiles and sidesteps you off their line at sprint speed when one is about "
        + "to hit. Can't beat point-blank or very fast shots. A movement mod, lower risk than the aimbot.";
    private static final String DESC_AURA =
        "Automatically melees nearby hostile and angry-neutral mobs with whatever is in hand, without "
        + "turning your view and without hitting through walls. Spams crowds, charges up on single targets, "
        + "and sweeps clusters. A combat cheat, bannable where mods aren't allowed.";
    private static final String DESC_WEAPON =
        "While the kill aura fights, equips the best sword or axe for the target by damage, speed, and "
        + "enchantments (Sharpness, Smite, Bane, Sweeping Edge), pulling one from your inventory if needed. "
        + "Rearranges your hotbar.";

    private static final Section[] SECTIONS = {
        new Section("Heads-up display", new Opt[]{
            Opt.of("Display speed", DESC_SPEED, () -> Settings.displaySpeed, v -> Settings.displaySpeed = v),
            Opt.of("Display ground speed", DESC_GROUND, () -> Settings.displayGroundSpeed, v -> Settings.displayGroundSpeed = v),
            Opt.of("Display mob healths", DESC_HEALTH, () -> Settings.displayMobHealths, v -> Settings.displayMobHealths = v),
            Opt.of("Display mob names", DESC_NAMES, () -> Settings.displayMobNames, v -> Settings.displayMobNames = v),
        }),
        new Section("Elytra flight", new Opt[]{
            Opt.of("Flight directors", DESC_FLIGHT, () -> Settings.displayFlightDirectors, v -> Settings.displayFlightDirectors = v),
            Opt.of("Flight path vector", DESC_FPV, () -> Settings.displayFlightPathVector, v -> Settings.displayFlightPathVector = v),
            Opt.of("Flight profile map", DESC_PROFILE, () -> Settings.displayFlightProfile, v -> Settings.displayFlightProfile = v),
            Opt.of("Range & endurance", DESC_RANGE, () -> Settings.displayRangeEndurance, v -> Settings.displayRangeEndurance = v),
            Opt.of("Flight path rails", DESC_RIBBON, () -> Settings.flightRibbon, v -> Settings.flightRibbon = v),
            Opt.of("Terrain avoidance", DESC_TERRAIN, () -> Settings.terrainAvoidance, v -> Settings.terrainAvoidance = v),
        }),
        new Section("Safety", new Opt[]{
            Opt.of("Elytra durability failsafe", DESC_ELYTRA, () -> Settings.elytraDuraFailsafe, v -> Settings.elytraDuraFailsafe = v),
            Opt.of("Elytra crash protection", DESC_CRASH, () -> Settings.crashProtection, v -> Settings.crashProtection = v),
            Opt.of("Fall prevention", DESC_FALL, () -> Settings.fallPrevention, v -> Settings.fallPrevention = v),
            Opt.of("Water bucket clutch", DESC_CLUTCH, () -> Settings.waterBucketClutch, v -> Settings.waterBucketClutch = v),
            Opt.of("AFK damage protection", DESC_AFK, () -> Settings.afkDamageProtection, v -> Settings.afkDamageProtection = v),
        }),
        new Section("Combat", new Opt[]{
            Opt.of("Projectile targeting reticle", DESC_RETICLE, () -> Settings.projectileReticle, v -> Settings.projectileReticle = v),
            Opt.of("Projectile glow", DESC_GLOW, () -> Settings.projectileGlow, v -> Settings.projectileGlow = v),
            Opt.of("Auto aim", DESC_AUTOAIM, () -> Settings.autoAim, v -> Settings.autoAim = v),
            Opt.of("Projectile dodge", DESC_DODGE, () -> Settings.projectileDodge, v -> Settings.projectileDodge = v),
            Opt.of("Kill aura", DESC_AURA, () -> Settings.killAura, v -> Settings.killAura = v),
            Opt.of("Auto weapon", DESC_WEAPON, () -> Settings.autoWeapon, v -> Settings.autoWeapon = v),
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
