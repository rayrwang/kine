package ai.rrw.kine.ui;

import ai.rrw.kine.Settings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class KineSettingsScreen extends Screen {

    private final Screen parent;

    public KineSettingsScreen(Screen parent) {
        super(Component.literal("kine settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = 300, h = 20, step = h + 2;
        int x = this.width / 2 - w / 2;
        int y = 32;

        toggle(x, y + 0 * step, w, h, "Display speed", DESC_SPEED,
            () -> Settings.displaySpeed, v -> Settings.displaySpeed = v);
        toggle(x, y + 1 * step, w, h, "Display ground speed", DESC_GROUND,
            () -> Settings.displayGroundSpeed, v -> Settings.displayGroundSpeed = v);
        toggle(x, y + 2 * step, w, h, "Display flight directors", DESC_FLIGHT,
            () -> Settings.displayFlightDirectors, v -> Settings.displayFlightDirectors = v);
        toggle(x, y + 3 * step, w, h, "Fall prevention", DESC_FALL,
            () -> Settings.fallPrevention, v -> Settings.fallPrevention = v);
        toggle(x, y + 4 * step, w, h, "Display mob healths", DESC_HEALTH,
            () -> Settings.displayMobHealths, v -> Settings.displayMobHealths = v);
        toggle(x, y + 5 * step, w, h, "Display mob names", DESC_NAMES,
            () -> Settings.displayMobNames, v -> Settings.displayMobNames = v);
        toggle(x, y + 6 * step, w, h, "Projectile targeting reticle", DESC_RETICLE,
            () -> Settings.projectileReticle, v -> Settings.projectileReticle = v);
        toggle(x, y + 7 * step, w, h, "Auto aim", DESC_AUTOAIM,
            () -> Settings.autoAim, v -> Settings.autoAim = v);

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds(this.width / 2 - 100, y + 8 * step + 8, 200, h).build());
    }

    private void toggle(int x, int y, int w, int h, String name, String desc,
                        BooleanSupplier get, Consumer<Boolean> set) {
        Button b = Button.builder(label(name, get.getAsBoolean()), btn -> {
            boolean nv = !get.getAsBoolean();
            set.accept(nv);
            btn.setMessage(label(name, nv));
            Settings.save();
        }).bounds(x, y, w, h).tooltip(Tooltip.create(Component.literal(desc))).build();
        addRenderableWidget(b);
    }

    private static Component label(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        Settings.save();
        this.minecraft.setScreen(parent);
    }

    private static final String DESC_SPEED =
        "Shows your total movement speed in m/s (blocks per second), including vertical motion, "
        + "measured from your real per-tick position change rather than the game's under-reported "
        + "velocity. Handy while falling or flying. Drawn centred above the hotbar.";
    private static final String DESC_GROUND =
        "Shows horizontal speed only, ignoring up/down motion, in m/s. This is the number that "
        + "governs elytra travel distance and your walking/sprinting pace. Drawn just below the "
        + "total-speed line.";
    private static final String DESC_FLIGHT =
        "While elytra-flying, overlays magenta guidance bars showing the pitch to hold for the "
        + "energy-pumping climb: dive to build speed, snap the nose up, then ease it back down. "
        + "Follow the bars to gain altitude with no rockets. Guidance only \u2014 it never moves you.";
    private static final String DESC_FALL =
        "Stops you walking off ledges that would cause fall damage, the way sneaking does, but "
        + "automatically and only when the drop ahead is actually dangerous. Safe drops and "
        + "deliberate jumps are unaffected. Ignores water/hay landings and slow/feather falling.";
    private static final String DESC_HEALTH =
        "Floats each creature's current and maximum health (e.g. \"14 / 20\") above it, sized by "
        + "distance and visible through walls. Useful for tracking damage mid-fight.";
    private static final String DESC_NAMES =
        "Floats each creature's name above it, next to the health readout. Off by default because "
        + "it adds clutter, especially on busy servers.";
    private static final String DESC_RETICLE =
        "Draws a ring at the exact spot your readied projectile will land \u2014 bow, crossbow, "
        + "trident, snowball, egg, ender pearl, potion, or xp bottle \u2014 accounting for gravity, "
        + "drag, launch spread, and your own movement. The ring turns green when it covers a valid "
        + "target.";
    private static final String DESC_AUTOAIM =
        "Automatically steers your view so weapon shots (bow, crossbow, trident) land on the target "
        + "nearest your reticle, leading moving targets by predicting where they'll be when the shot "
        + "arrives. Nudge the mouse to break the lock; it pauses briefly so you can look away. Thrown "
        + "utility items are never auto-aimed. This is an aimbot \u2014 expect a ban anywhere that "
        + "doesn't allow client mods.";
}
