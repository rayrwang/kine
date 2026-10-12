package ai.rrw.kine.mixin;

import ai.rrw.kine.view.Freecam;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: no first-person hand (the game draws it whenever the camera mode is first person, wherever the camera is). */
@Mixin(GameRenderer.class)
public class FreecamHandMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void kine$noHandInFreecam(CallbackInfo ci) {
        if (Freecam.active()) ci.cancel();
    }
}
