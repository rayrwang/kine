package ai.rrw.kine.mixin.v1_21_11;

import ai.rrw.kine.view.Freecam;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Freecam: cull terrain as the game does for a spectator (LevelRenderer.renderLevel passes isSpectator to cullTerrain),
 *  so a camera inside solid ground still sees the sections around it instead of the occlusion graph hiding them.
 *  A MixinExtras ModifyExpressionValue, not a Redirect: Carpet redirects the same call (creative no-clip), and two
 *  Redirects on one call crash the game at startup; this one stacks on whatever another mod made of it. */
@Mixin(LevelRenderer.class)
public class FreecamCullMixin {
    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
    private boolean kine$cullAsSpectator(boolean spectator) {
        return spectator || Freecam.active();
    }
}
