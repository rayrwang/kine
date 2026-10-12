package ai.rrw.kine.mixin;

import ai.rrw.kine.Settings;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FogRenderer.setupFog: No boss fog takes away the boss bar's darkening of the fog colour (its darkenWorldAmount);
 * No distance fog takes away the fade at the edge of the render distance (renderDistanceStart and End), so the
 * world ends in a hard edge there.
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
    @ModifyVariable(method = "setupFog", at = @At("HEAD"), argsOnly = true)
    private float kine$noBossDarkening(float darkenWorldAmount) {
        return Settings.noBossFog ? 0.0F : darkenWorldAmount;
    }

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void kine$noDistanceFog(CallbackInfoReturnable<FogData> cir) {
        if (Settings.noDistanceFog) {
            FogData fog = cir.getReturnValue();
            fog.renderDistanceStart = Float.MAX_VALUE;
            fog.renderDistanceEnd = Float.MAX_VALUE;
        }
    }
}
