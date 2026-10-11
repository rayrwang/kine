package ai.rrw.kine.fireworks;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudLayout;
import ai.rrw.kine.mixin.FireworkRocketAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import org.joml.Matrix3x2fStack;

import java.util.OptionalInt;

/**
 * Firework fuel for elytra flight: the FUEL readout and the burn bar.
 *
 * The readout is the burn of every usable rocket you carry (inventory and offhand, not shulker boxes), counted
 * in flight-duration-1 rockets (their mean burn, 26.5 ticks, is one unit): the number alone, in green, with a
 * rocket icon after it, centred on the END and RNG line. Rockets with firework stars are left out, since their explosions hurt the flyer. Both
 * show as the "Firework fuel" setting says: off, while you wear an elytra, or always.
 *
 * The burn bar shows, while a rocket is boosting you, how far through its burn it is: bright up to the
 * shortest burn the rocket can have, then fading with the chance it is still burning, a mark at the mean,
 * and the ticks burned so far filled in. The server rolls each rocket's length and never sends it, so the
 * bar shows the odds, not the end.
 */
public final class FireworkFuel {
    private FireworkFuel() {}

    private static final int AMBER = HudColors.AMBER;   // the burn bar's burned part, as END and RNG
    private static final int READOUT = HudColors.GREEN;  // the fuel number, set apart from END and RNG
    private static final int BAR_W = 61, BAR_H = 3;
    private static final int BAR_BELOW_CROSSHAIR = 16;
    private static final float ICON_SCALE = 0.625f;   // the 16 px item icon drawn at text height
    private static ItemStack rocketIcon;

    private static double fuelSeconds = 0;
    private static boolean showFuel = false;
    private static int burnDuration = -1;   // flight duration of the rocket on the bar, -1 when none is boosting you
    private static int burnElapsed = 0;     // ticks it has burned

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FireworkFuel::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "firework_fuel"),
            FireworkFuel::render);
    }

    private static void tick(Minecraft mc) {
        showFuel = false;
        burnDuration = -1;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (!Settings.shows(Settings.displayFireworkFuel)) return;

        RocketBurn.Fuel fuel = new RocketBurn.Fuel();
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) add(fuel, s);
        add(fuel, p.getItemBySlot(EquipmentSlot.OFFHAND));
        fuelSeconds = fuel.seconds();
        showFuel = fuelSeconds > 0;

        // the rocket boosting you carries your id as its synced target; with two, show the one that can last longest
        if (!p.isFallFlying()) return;
        int longest = Integer.MIN_VALUE;
        for (FireworkRocketEntity r : mc.level.getEntitiesOfClass(FireworkRocketEntity.class, p.getBoundingBox().inflate(4.0))) {
            OptionalInt to = r.getEntityData().get(FireworkRocketAccessor.kine$attachedToTarget());
            if (to.isEmpty() || to.getAsInt() != p.getId()) continue;
            int d = duration(r.getItem());
            int left = RocketBurn.maxTicks(d) - r.tickCount;
            if (left > longest) {
                longest = left;
                burnDuration = d;
                burnElapsed = r.tickCount;
            }
        }
    }

    private static void add(RocketBurn.Fuel fuel, ItemStack s) {
        if (!s.is(Items.FIREWORK_ROCKET)) return;
        Fireworks fw = s.get(DataComponents.FIREWORKS);
        fuel.add(fw == null ? 0 : fw.flightDuration(), s.getCount(), fw != null && !fw.explosions().isEmpty());
    }

    // rounded down, like RNG: a whole number from 10 up, one decimal below
    private static String units(double u) {
        return u >= 10 ? Long.toString((long) Math.floor(u)) : String.format("%.1f", Math.floor(u * 10) / 10);
    }

    private static int duration(ItemStack s) {
        Fireworks fw = s.get(DataComponents.FIREWORKS);
        return fw == null ? 0 : fw.flightDuration();
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int cx = W / 2;

        if (showFuel) {
            String s = units(fuelSeconds / RocketBurn.meanSeconds(1));
            int icon = Math.round(16 * ICON_SCALE);
            int x = cx - (mc.font.width(s) + 1 + icon) / 2, y = HudLayout.rangeEnduranceRow(H);   // centred on the END / RNG line, the icon after the number
            g.text(mc.font, s, x, y, READOUT, true);
            if (rocketIcon == null) rocketIcon = new ItemStack(Items.FIREWORK_ROCKET);
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(x + mc.font.width(s) + 1, y - 1);
            pose.scale(ICON_SCALE, ICON_SCALE);
            g.fakeItem(rocketIcon, 0, 0);
            pose.popMatrix();
        }
        if (burnDuration >= 0) drawBar(g, cx, H / 2 + BAR_BELOW_CROSSHAIR);
    }

    // One column per slice of the longest possible burn: amber where burned, otherwise white with its
    // opacity set by the chance the rocket is still burning at that tick.
    private static void drawBar(GuiGraphicsExtractor g, int cx, int y) {
        int max = RocketBurn.maxTicks(burnDuration);
        int x0 = cx - BAR_W / 2;
        for (int i = 0; i < BAR_W; i++) {
            int t = (int) ((i + 0.5) * max / BAR_W);
            int color;
            if (t < burnElapsed) color = AMBER;
            else {
                double still = RocketBurn.stillBurning(burnDuration, t);
                int alpha = (int) Math.round(0x30 + (0xD0 - 0x30) * still);
                color = (alpha << 24) | 0xFFFFFF;
            }
            g.fill(x0 + i, y, x0 + i + 1, y + BAR_H, color);
        }
        int mean = x0 + (int) Math.round(RocketBurn.meanTicks(burnDuration) / max * BAR_W);
        g.fill(mean, y - 1, mean + 1, y + BAR_H + 1, 0xFFFFFFFF);
    }
}
