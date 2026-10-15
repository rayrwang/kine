package ai.rrw.kine.mixin.v1_21_6;

import ai.rrw.kine.Settings;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The lightmap as the game fills it (LightTexture.updateLightTexture), on every update it makes. Fullbright: the
 * dimension's ambient light at 1, so lightmap.fsh's get_brightness takes every block and sky light level to full and
 * the block light alone comes out white (it clamps at 1); block face shading is drawn elsewhere and stays. No
 * darkness: the Darkness effect's blend factor at 0, so the brightness stays at the brightness option and there is no
 * pulse, as if the effect weren't there. No boss fog: no darkening of the world by a boss bar (the Wither's).
 */
@Mixin(LightTexture.class)
public class LightmapMixin {
    @ModifyExpressionValue(method = "updateLightTexture", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/dimension/DimensionType;ambientLight()F"))
    private float kine$fullbright(float ambientLight) {
        return Settings.fullbright ? 1.0F : ambientLight;
    }

    @ModifyExpressionValue(method = "updateLightTexture", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;getEffectBlendFactor(Lnet/minecraft/core/Holder;F)F"))
    private float kine$noDarkness(float darkness) {
        return Settings.noDarkness ? 0.0F : darkness;
    }

    @ModifyExpressionValue(method = "updateLightTexture", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;getDarkenWorldAmount(F)F"))
    private float kine$noBossDarkening(float darken) {
        return Settings.noBossFog ? 0.0F : darken;
    }
}
