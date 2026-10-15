package ai.rrw.kine.mixin.v1_21_2;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.v1_21_2.Fog;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * LevelRenderer.renderLevel's foggy flag, which gives the fog of a foggy place (FogRenderer.setupFog) and leaves the
 * sky out: the dimension's (the Nether's) or a boss bar's world fog (the Ender Dragon fight). No Nether fog clears
 * both in the Nether, as 26.1 clears its atmospheric environment there; No boss fog ignores the boss bar's.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererFogMixin {
    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;isFoggyAt(II)Z"))
    private boolean kine$noNetherFog(boolean foggy) {
        return foggy && !Fog.netherFogCleared();
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;shouldCreateWorldFog()Z"))
    private boolean kine$noBossFog(boolean fog) {
        return fog && !Settings.noBossFog && !Fog.netherFogCleared();
    }
}
