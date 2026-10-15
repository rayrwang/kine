package ai.rrw.kine.mixin.f26;

import ai.rrw.kine.Settings;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The game draws no sky while the camera is in lava or powder snow (LevelRenderer.addSkyPass); with that fog switched
 * off the sky is drawn as in open air, or the space past the clear fog would be empty.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererSkyMixin {
    @Redirect(method = "addSkyPass", at = @At(value = "FIELD",
        target = "Lnet/minecraft/client/renderer/state/level/CameraRenderState;fogType:Lnet/minecraft/world/level/material/FogType;"))
    private FogType kine$skyThroughClearedFog(CameraRenderState cameraState) {
        FogType t = cameraState.fogType;
        if (t == FogType.LAVA && Settings.noLavaFog || t == FogType.POWDER_SNOW && Settings.noPowderSnowFog) return FogType.ATMOSPHERIC;
        return t;
    }
}
