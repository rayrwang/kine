package ai.rrw.kine.mixin.v1_21;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.v1_21.Fog;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FogRenderer on 1.21 and 1.21.1, where every fog is set. setupColor: No boss fog takes away the boss bar's
 * darkening of the fog colour (its darkenWorldAmount, the second float). setupFog: after the game has set the fog
 * (once for the sky, once for the terrain), the water, lava, powder snow and distance settings change it
 * (compat.v1_21.Fog; on 26.1 WaterFogMixin, LavaFogMixin, PowderSnowFogMixin and FogRendererMixin's distance part).
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
    @ModifyVariable(method = "setupColor", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private static float kine$noBossDarkening(float darkenWorldAmount) {
        return Settings.noBossFog ? 0.0F : darkenWorldAmount;
    }

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void kine$visibility(Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean foggy,
                                        float partialTick, CallbackInfo ci) {
        Fog.afterSetupFog(camera, mode, renderDistance, foggy);
    }
}
