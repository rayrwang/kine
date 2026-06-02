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

    private static final int MARGIN = 16;

    private final Screen parent;
    private int rowX, rowY = 32, rowH = 20, rowStep = 22, colW;   // layout, set in init()

    public KineSettingsScreen(Screen parent) {
        super(Component.literal("kine settings"));
        this.parent = parent;
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

    private record Opt(String name, String desc, BooleanSupplier get, Consumer<Boolean> set) {}

    private static final Opt[] OPTS = {
        new Opt("Display speed", DESC_SPEED,
            () -> Settings.displaySpeed, v -> Settings.displaySpeed = v),
        new Opt("Display ground speed", DESC_GROUND,
            () -> Settings.displayGroundSpeed, v -> Settings.displayGroundSpeed = v),
        new Opt("Display flight directors", DESC_FLIGHT,
            () -> Settings.displayFlightDirectors, v -> Settings.displayFlightDirectors = v),
        new Opt("Elytra durability failsafe", DESC_ELYTRA,
            () -> Settings.elytraDuraFailsafe, v -> Settings.elytraDuraFailsafe = v),
        new Opt("Terrain crash protection", DESC_CRASH,
            () -> Settings.crashProtection, v -> Settings.crashProtection = v),
        new Opt("Fall prevention", DESC_FALL,
            () -> Settings.fallPrevention, v -> Settings.fallPrevention = v),
        new Opt("Display mob healths", DESC_HEALTH,
            () -> Settings.displayMobHealths, v -> Settings.displayMobHealths = v),
        new Opt("Display mob names", DESC_NAMES,
            () -> Settings.displayMobNames, v -> Settings.displayMobNames = v),
        new Opt("Projectile targeting reticle", DESC_RETICLE,
            () -> Settings.projectileReticle, v -> Settings.projectileReticle = v),
        new Opt("Projectile glow", DESC_GLOW,
            () -> Settings.projectileGlow, v -> Settings.projectileGlow = v),
        new Opt("Auto aim", DESC_AUTOAIM,
            () -> Settings.autoAim, v -> Settings.autoAim = v),
        new Opt("Projectile dodge", DESC_DODGE,
            () -> Settings.projectileDodge, v -> Settings.projectileDodge = v),
    };

    @Override
    protected void init() {
        rowX = MARGIN;
        colW = Math.min(280, this.width / 2 - MARGIN - 8);

        for (int i = 0; i < OPTS.length; i++) {
            addToggle(rowX, rowY + i * rowStep, colW, rowH, OPTS[i]);
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds(rowX, rowY + OPTS.length * rowStep + 8, colW, rowH).build());
    }

    private void addToggle(int x, int y, int w, int h, Opt opt) {
        Button b = Button.builder(label(opt.name(), opt.get().getAsBoolean()), btn -> {
            boolean nv = !opt.get().getAsBoolean();
            opt.set().accept(nv);
            btn.setMessage(label(opt.name(), nv));
            Settings.save();
        }).bounds(x, y, w, h).build();
        addRenderableWidget(b);
    }

    private static Component label(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

        int descX = rowX + colW + MARGIN;
        int descW = this.width - descX - MARGIN;
        if (descW < 80) return;   // window too narrow for a side panel

        int hovered = -1;
        for (int i = 0; i < OPTS.length; i++) {
            int by = rowY + i * rowStep;
            if (mouseX >= rowX && mouseX <= rowX + colW && mouseY >= by && mouseY <= by + rowH) {
                hovered = i;
                break;
            }
        }

        String text = hovered >= 0 ? OPTS[hovered].desc() : "Hover an option for details.";
        int color = hovered >= 0 ? 0xFFFFFFFF : 0xFF909090;

        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), descW);
        int ty = rowY;
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
