package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3x2fStack;

public class ElytraGuard {

    // --- tuning (all deliberately conservative; healthy margins) ---
    private static final double DESCENT_RATE = 6.0;    // assumed safe descent rate (blocks/sec) when budgeting
    private static final double BASE_MARGIN  = 15.0;   // flat reserve (sec) for approach / mistakes
    private static final int    SCAN_CAP     = 384;    // how far down we scan for ground
    private static final int    GRACE_TICKS  = 100;    // 5 s for the pilot to take control before the failsafe fires
    private static final int    CRITICAL_DUR = 4;      // bail instantly at/under this durability (about to break)

    private static boolean warning = false;   // read by the HUD
    private static int graceTicks = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ElytraGuard::tick);
        HudElementRegistry.attachElementAfter(
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "flightdir"), // draw on top of the flight director bars
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "elytra_guard"),
            ElytraGuard::render);
    }

    private static void tick(Minecraft mc) {
        warning = false;
        if (!Settings.elytraDuraFailsafe) { graceTicks = 0; return; }

        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isFallFlying()) { graceTicks = 0; return; }

        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || !chest.isDamageableItem()) { graceTicks = 0; return; }

        int remaining = chest.getMaxDamage() - chest.getDamageValue();   // ~seconds of flight left
        double agl = altitude(mc, p);
        warning = remaining < neededDurability(agl);

        if (warning && Autopilot.isEngaged()) {
            graceTicks++;
            if (graceTicks >= GRACE_TICKS || remaining <= CRITICAL_DUR) bailOut(mc);
        } else {
            graceTicks = 0;   // pilot took control, or we're safe again
        }
    }

    // durability (≈seconds) we want in reserve to glide down from this altitude, with a margin that
    // thickens as you go higher.
    private static double neededDurability(double agl) {
        double descentSeconds = agl / DESCENT_RATE;
        double altitudeFactor = 1.5 + agl / 1000.0;
        return descentSeconds * altitudeFactor + BASE_MARGIN;
    }

    // straight-down distance to the first solid block (blocks above ground)
    private static double altitude(Minecraft mc, LocalPlayer p) {
        int x = p.getBlockX(), z = p.getBlockZ();
        int start = (int) Math.floor(p.getY()) - 1;
        int limit = Math.max(mc.level.getMinY(), start - SCAN_CAP);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = start; y >= limit; y--) {
            pos.set(x, y, z);
            if (!mc.level.getBlockState(pos).isAir()) return Math.max(0, p.getY() - (y + 1));
        }
        return p.getY() - limit;   // no ground within scan (e.g. over the void) -> treat as very high
    }

    private static void bailOut(Minecraft mc) {
        graceTicks = 0;
        Autopilot.disengage();
        Kine.LOGGER.warn("kine: elytra durability failsafe — disconnecting to avoid a fall death");
        ClientPacketListener cpl = mc.getConnection();
        if (cpl != null) {
            cpl.getConnection().disconnect(Component.literal("Kine: low elytra durability failsafe"));
        }
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!warning) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        boolean flash = (System.currentTimeMillis() / 400) % 2 == 0;
        int color = flash ? 0xFFFF2020 : 0xFFFFD000;
        int cy = H / 3;
        int big = (int) (font.lineHeight * 2.0f);

        drawCentered(g, font, "LOW DURABILITY",   W / 2, cy,            color, 2.0f);
        drawCentered(g, font, "LAND IMMEDIATELY", W / 2, cy + big + 4,  color, 2.0f);

        if (Autopilot.isEngaged()) {
            int secs = Math.max(0, (GRACE_TICKS - graceTicks + 19) / 20);
            drawCentered(g, font, "autopilot disconnect in " + secs + "s \u2014 take control",
                W / 2, cy + 2 * big + 14, 0xFFFFFFFF, 1.0f);
        }
    }

    private static void drawCentered(GuiGraphicsExtractor g, Font font, String text,
                                     int cx, int cy, int color, float scale) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        pose.scale(scale, scale);
        g.text(font, text, -font.width(text) / 2, 0, color, true);
        pose.popMatrix();
    }
}
