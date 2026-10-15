package ai.rrw.kine.mixin.v1_21;

import ai.rrw.kine.compat.v1_21.Hud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Kine's HUD elements (compat.v1_21.Hud), drawn at the end of the game's camera overlays, the first layer of the HUD
 *  (Gui.renderCameraOverlays, which has one return): above the vignette and the pumpkin, frost and portal overlays,
 *  under the crosshair, the hotbar and everything after, as Fabric's elements after MISC_OVERLAYS on later releases. */
@Mixin(Gui.class)
public class HudMixin {
    @Inject(method = "renderCameraOverlays", at = @At("TAIL"))
    private void kine$hud(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        Hud.draw(graphics, delta);
    }
}
