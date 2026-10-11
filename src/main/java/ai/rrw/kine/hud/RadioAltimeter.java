package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

public class RadioAltimeter {

    private static int agl = -1;                  // blocks above terrain; -1 = unknown

    public static int agl() { return agl; }

    public static void register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "radalt"),
            RadioAltimeter::render
        );
        ClientTickEvents.END_CLIENT_TICK.register(RadioAltimeter::tick);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { agl = -1; return; }

        int x = p.getBlockX(), z = p.getBlockZ();
        int start = (int) Math.floor(p.getY()) - 1;          // first block below feet
        int bottom = mc.level.getMinY();                     // scan down to the world floor
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int groundY = Integer.MIN_VALUE;                     // sentinel: nothing found yet
        for (int y = start; y >= bottom; y--) {
            pos.set(x, y, z);
            if (!mc.level.getBlockState(pos).isAir()) { groundY = y; break; }
        }
        if (groundY == Integer.MIN_VALUE) { agl = -1; return; }   // no ground in the whole column (void): unknown, hidden
        agl = Math.max(0, (int) Math.round(p.getY() - (groundY + 1)));   // surface is groundY+1
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (agl < 0 || !Settings.shows(Settings.displayHeightAboveGround)) return;
        Minecraft mc = Minecraft.getInstance();

        String text = "" + agl;
        int w = mc.font.width(text);
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int x = W / 2 - w / 2;
        int y = HudLayout.radioAltimeterRow(H);
        g.text(mc.font, text, x, y, HudColors.AMBER, true);
    }
}
