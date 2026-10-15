package ai.rrw.kine.mixin.v1_21_5;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.v1_21_5.Fog;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FogRenderer (1.21.5's fog, compat.v1_21_5.Fog): No water fog, No lava fog and No powder snow fog clear that fog;
 * No distance fog takes away the fade at the edge of the render distance, so the world ends in a hard edge there;
 * No boss fog takes away the boss bar's darkening of the fog colour (computeFogColor's darkenWorldAmount).
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
    @Shadow private static boolean fogEnabled;

    // computeFogColor(camera, partialTick, level, renderDistance, darkenWorldAmount): the second float
    @ModifyVariable(method = "computeFogColor", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private static float kine$noBossDarkening(float darkenWorldAmount) {
        return Settings.noBossFog ? 0.0F : darkenWorldAmount;
    }

    @Inject(method = "setupFog", at = @At("RETURN"), cancellable = true)
    private static void kine$visibility(Camera camera, FogRenderer.FogMode mode, Vector4f color, float renderDistance, boolean foggy,
                                        float partialTick, CallbackInfoReturnable<FogParameters> cir) {
        if (fogEnabled) cir.setReturnValue(Fog.setupFog(cir.getReturnValue(), camera, mode, renderDistance, foggy));
    }
}
