package ai.rrw.kine.mixin;

import ai.rrw.kine.Settings;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The full-screen overlays the HUD draws (Gui.extractCameraOverlays): No pumpkin overlay skips the carved pumpkin's
 * blur, No frost overlay the powder snow frost, No portal overlay the nether portal's purple swirl.
 */
@Mixin(Gui.class)
public class GuiOverlayMixin {
    @Inject(method = "extractTextureOverlay", at = @At("HEAD"), cancellable = true)
    private void kine$noTextureOverlay(GuiGraphicsExtractor graphics, Identifier texture, float alpha, CallbackInfo ci) {
        String path = texture.getPath();
        if (Settings.noPumpkinOverlay && path.equals("textures/misc/pumpkinblur.png")
            || Settings.noFrostOverlay && path.equals("textures/misc/powder_snow_outline.png")) ci.cancel();
    }

    @Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void kine$noPortalOverlay(CallbackInfo ci) {
        if (Settings.noPortalOverlay) ci.cancel();
    }
}
