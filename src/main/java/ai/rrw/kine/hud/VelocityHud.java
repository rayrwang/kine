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

    String[] lines = {
        String.format("Speed: %.1f m/s", speed),
        String.format("Ground speed: %.1f m/s", groundSpeed)
    };

    float scale = 2f; // TODO adjust using setting

    // Render the text
    int margin = 4;
    int right = mc.getWindow().getGuiScaledWidth() - margin;
    int maxW = 0;
    for (String line : lines) {
      maxW = Math.max(maxW, mc.font.width(line));
    }
    Matrix3x2fStack matrices = graphics.pose();
    matrices.pushMatrix();
    matrices.scale(scale, scale);
    int x = (int) (right / scale - maxW);          // one shared left edge for both lines
    for (int i = 0; i < lines.length; i++) {
      int y = (int) (margin / scale + i * mc.font.lineHeight);
      graphics.text(mc.font, lines[i], x, y, 0xFFFFFFFF, true);
    }
    matrices.popMatrix();
  }
}
