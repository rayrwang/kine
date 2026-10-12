package ai.rrw.kine.mixin;

import ai.rrw.kine.view.Freecam;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Freecam: cull terrain as the game does for a spectator (LevelRenderer.update passes isSpectator to cullTerrain),
 *  so a camera inside solid ground still sees the sections around it instead of the occlusion graph hiding them. */
@Mixin(LevelRenderer.class)
public class FreecamCullMixin {
    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
    private boolean kine$cullAsSpectator(LocalPlayer player) {
        return Freecam.active() || player.isSpectator();
    }
}
