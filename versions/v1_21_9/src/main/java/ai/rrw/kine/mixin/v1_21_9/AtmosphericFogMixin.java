package ai.rrw.kine.mixin.v1_21_9;

import ai.rrw.kine.Settings;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The open-air fog (AtmosphericFogEnvironment): No rain fog keeps the rain from pulling it in (the multiplier held at 0
 * right after the game eases it toward the rain, so it eases back when switched off). On 1.21.9 and 1.21.10 the
 * Nether's fog and the boss bar's are another environment (DimensionFogMixin, FogRendererMixin).
 */
@Mixin(AtmosphericFogEnvironment.class)
public class AtmosphericFogMixin {
    @Shadow private float rainFogMultiplier;

    // setupFog moves the multiplier toward this tick's rain, then reads it for the fog distances
    @Inject(method = "setupFog", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER,
        target = "Lnet/minecraft/client/renderer/fog/environment/AtmosphericFogEnvironment;rainFogMultiplier:F"))
    private void kine$noRainFog(CallbackInfo ci) {
        if (Settings.noRainFog) rainFogMultiplier = 0.0F;
    }
}
