package ai.rrw.kine.mixin.v1_21_9;

import ai.rrw.kine.Settings;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The game draws no sky while the camera is in lava or powder snow (LevelRenderer.addSkyPass); with that fog switched
 * off the sky is drawn as in open air, or the space past the clear fog would be empty.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererSkyMixin {
    @ModifyExpressionValue(method = "addSkyPass", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;getFluidInCamera()Lnet/minecraft/world/level/material/FogType;"))
    private FogType kine$skyThroughClearedFog(FogType t) {
        if (t == FogType.LAVA && Settings.noLavaFog || t == FogType.POWDER_SNOW && Settings.noPowderSnowFog) return FogType.ATMOSPHERIC;
        return t;
    }
}
