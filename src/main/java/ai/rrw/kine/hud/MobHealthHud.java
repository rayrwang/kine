package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;

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

        int y = 4;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;   // skip non-mobs
            if (living == mc.player) continue;                        // skip yourself
            if (living.distanceTo(mc.player) > 32.0) continue;        // only nearby

            String text = String.format("%s: %.0f / %.0f",
                living.getName().getString(), living.getHealth(), living.getMaxHealth());
            graphics.text(mc.font, text, 4, y, 0xFFFFFFFF, true);
            y += mc.font.lineHeight + 1;
        }
    }
}
