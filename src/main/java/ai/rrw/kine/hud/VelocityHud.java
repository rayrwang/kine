package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public class VelocityHud {

  public static void register() {
    HudElementRegistry.attachElementAfter(
      VanillaHudElements.MISC_OVERLAYS,
      Identifier.fromNamespaceAndPath(Kine.MOD_ID, "velocity"),
      VelocityHud::render
    );
  }

  private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
    Minecraft mc = Minecraft.getInstance();
    LocalPlayer player = mc.player;
    if (player == null) return;

    Vec3 v = player.getDeltaMovement();
    double speed = Math.sqrt(v.x * v.x + v.z * v.z) * 20.0; // blocks per second

    String text = String.format("%.1f m/s", speed);

    int margin = 4;
    int textWidth = mc.font.width(text);
    int x = mc.getWindow().getGuiScaledWidth() - textWidth - margin;
    int y = margin;

    graphics.text(mc.font, text, x, y, 0xFFFFFFFF, true);
  }
}
