package ai.rrw.kine.mixin.v1_21_11;

import ai.rrw.kine.Settings;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * FogRenderer.setupFog: No boss fog takes away the boss bar's darkening of the fog colour (its darkenWorldAmount);
 * No distance fog takes away the fade at the edge of the render distance (renderDistanceStart and End, which 1.21.11
 * writes straight into the fog buffer), so the world ends in a hard edge there.
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
    @ModifyVariable(method = "setupFog", at = @At("HEAD"), argsOnly = true)
    private float kine$noBossDarkening(float darkenWorldAmount) {
        return Settings.noBossFog ? 0.0F : darkenWorldAmount;
    }

    // updateBuffer(buffer, offset, colour, environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd,
    // skyEnd, cloudEnd)
    @ModifyArg(method = "setupFog", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"),
        index = 5)
    private float kine$noDistanceFogStart(float renderDistanceStart) {
        return Settings.noDistanceFog ? Float.MAX_VALUE : renderDistanceStart;
    }

    @ModifyArg(method = "setupFog", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"),
        index = 6)
    private float kine$noDistanceFogEnd(float renderDistanceEnd) {
        return Settings.noDistanceFog ? Float.MAX_VALUE : renderDistanceEnd;
    }
}
