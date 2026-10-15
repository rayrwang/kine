package ai.rrw.kine.mixin.v1_21_6;

import ai.rrw.kine.Settings;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The open-air fog (AtmosphericFogEnvironment): No rain fog keeps the rain from pulling it in (the multiplier held at
 * 0, so it eases back when switched off). Before 1.21.9 setupFog eases the multiplier itself and then takes the fog
 * distances from it, so it is held at 0 in between. The Nether's fog and the boss bar's are another environment here
 * (DimensionOrBossFogMixin, FogRendererMixin).
 */
@Mixin(AtmosphericFogEnvironment.class)
public class AtmosphericFogMixin {
    @Shadow private float rainFogMultiplier;

    @Inject(method = "setupFog", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER,
        target = "Lnet/minecraft/client/renderer/fog/environment/AtmosphericFogEnvironment;rainFogMultiplier:F"))
    private void kine$noRainFog(CallbackInfo ci) {
        if (Settings.noRainFog) rainFogMultiplier = 0.0F;
    }
}
