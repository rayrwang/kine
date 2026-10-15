package ai.rrw.kine.mixin.v26_2;

import ai.rrw.kine.Settings;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No fire overlay (the flames over the screen while burning) and No underwater overlay (the dark water texture over
 *  the screen with your eyes in water): the game's two drawing calls skipped (ScreenEffectRenderer; 26.2 renamed them submitFire and submitWater). */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectMixin {
    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void kine$noFireOverlay(CallbackInfo ci) {
        if (Settings.noFireOverlay) ci.cancel();
    }

    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
    private static void kine$noUnderwaterOverlay(CallbackInfo ci) {
        if (Settings.noUnderwaterOverlay) ci.cancel();
    }
}
