package ai.rrw.kine.mixin.v1_21_2;

import ai.rrw.kine.compat.v1_21_2.EpochImpl;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kine's HUD elements (Compat.hud): drawn right after the game's camera overlays (Gui.renderCameraOverlays, the first
 * layer of the HUD, hidden with it by F1), before the crosshair, as 26.1's Fabric HUD registry places them after
 * MISC_OVERLAYS. 1.21.2's and 1.21.3's Fabric API have only HudRenderCallback, which draws after the whole HUD.
 */
@Mixin(Gui.class)
public class GuiHudMixin {
    @Inject(method = "renderCameraOverlays", at = @At("TAIL"))
    private void kine$hud(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        EpochImpl.drawHud(graphics, delta);
    }
}
