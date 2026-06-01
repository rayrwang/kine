package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

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

    double dx = player.getX() - player.xOld;
    double dy = player.getY() - player.yOld;
    double dz = player.getZ() - player.zOld;
    double speed = Math.sqrt(dx*dx + dy*dy + dz*dz) * 20.0;
    double groundSpeed = Math.sqrt(dx*dx + dz*dz) * 20.0;

    java.util.List<String> lines = new java.util.ArrayList<>();
    if (Settings.displaySpeed)       lines.add(String.format("Speed: %.1f m/s", speed));
    if (Settings.displayGroundSpeed) lines.add(String.format("Ground speed: %.1f m/s", groundSpeed));
    if (lines.isEmpty()) return;

    float scale = 1f; // TODO adjust using setting

    // Render the text
    // TODO fix conflict with item names display
    int screenW = mc.getWindow().getGuiScaledWidth();
    int screenH = mc.getWindow().getGuiScaledHeight();

    int barsTop = screenH - 39; // top of the vanilla health/hunger row
    float lineH = mc.font.lineHeight * scale; // one line's height, in screen pixels

    Matrix3x2fStack matrices = graphics.pose();
    matrices.pushMatrix();
    matrices.scale(scale, scale);
    for (int i = 0; i < lines.size(); i++) {
      int w = mc.font.width(lines.get(i));
      int x = (int) (screenW / (2f * scale) - w / 2f); // center this line
      int y = (int) ((barsTop - 2.5*lineH - (lines.size() - i) * lineH) / scale);
      graphics.text(mc.font, lines.get(i), x, y, 0xFFFFFFFF, true);
    }
    matrices.popMatrix();
  }
}
