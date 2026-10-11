package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;

public class MobHealthHud {

    public static void register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "mob_health"),
            MobHealthHud::render
        );
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!Settings.displayMobHealths && !Settings.displayMobNames) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Matrix4f viewProj = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Vec3 camPos = camera.position();

        float pt = delta.getGameTimeDeltaPartialTick(false);
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        Matrix3x2fStack pose = graphics.pose();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living == mc.player) continue;

            double ex = living.xOld + (living.getX() - living.xOld) * pt;
            double ey = living.yOld + (living.getY() - living.yOld) * pt;
            double ez = living.zOld + (living.getZ() - living.zOld) * pt;
            double height = living.getBbHeight();
            double headY = ey + height + 0.5; // above the head
            double midY  = ey + height * 0.5; // body center

            double dx = ex - camPos.x, dy = midY - camPos.y, dz = ez - camPos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < 30) { // TODO adjust in settings
                float scale = (float) (6.0 / dist);
                scale = Math.max(0.25f, Math.min(scale, 1.5f));

                String health = String.format("%.0f / %.0f", living.getHealth(), living.getMaxHealth());
                String name = living.getName().getString();

                if (Settings.displayMobHealths) drawLabel(graphics, pose, mc.font, viewProj, camPos, screenW, screenH, ex, headY, ez, health, scale);
                if (Settings.displayMobNames) drawLabel(graphics, pose, mc.font, viewProj, camPos, screenW, screenH, ex, midY,  ez, name, scale * 0.65f);
            }
        }
    }

    private static void drawLabel(GuiGraphicsExtractor graphics, Matrix3x2fStack pose, Font font,
        Matrix4f viewProj, Vec3 camPos, int screenW, int screenH,
        double wx, double wy, double wz, String text, float scale) {
        float[] s = ScreenProjection.project(viewProj, camPos, wx, wy, wz, screenW, screenH, 0.0f);
        if (s == null) return; // behind the camera
        float sx = s[0], sy = s[1];

        int w = font.width(text);
        pose.pushMatrix();
        pose.translate(sx, sy);
        pose.scale(scale, scale);
        graphics.text(font, text, -w / 2, -font.lineHeight / 2, 0xFFFFFFFF, true); // centered on the point
        pose.popMatrix();
    }
}
