package ai.rrw.kine.mixin.v26_2;

import ai.rrw.kine.view.Freecam;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Freecam: cull terrain as the game does for a spectator, so a camera inside solid ground still sees the sections
 *  around it. From 26.2 that rule lives in Camera.extractRenderState (smart culling off for a spectator whose camera is
 *  in a solid block), no longer in LevelRenderer.update. A MixinExtras ModifyExpressionValue, not a Redirect, so it
 *  stacks with another mod's change to the same call. */
@Mixin(Camera.class)
public class FreecamCullMixin {
    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
    private boolean kine$cullAsSpectator(boolean spectator) {
        return spectator || Freecam.active();
    }
}
