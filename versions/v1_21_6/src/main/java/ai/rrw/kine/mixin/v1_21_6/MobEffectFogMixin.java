package ai.rrw.kine.mixin.v1_21_6;

import ai.rrw.kine.Settings;
import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.DarknessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.MobEffectFogEnvironment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No darkness and No blindness, the fog part: the effect's fog environment never applies, so neither its short fog
 * nor its darkening of the fog colour (FogRenderer asks isApplicable for both). The other parts: LightmapMixin (the
 * Darkness dimming and pulse), CameraSkyMixin (the hidden sky).
 */
@Mixin(MobEffectFogEnvironment.class)
public abstract class MobEffectFogMixin {
    @Inject(method = "isApplicable", at = @At("HEAD"), cancellable = true)
    private void kine$noEffectFog(FogType fogType, Entity entity, CallbackInfoReturnable<Boolean> cir) {
        Object self = this;
        if (Settings.noDarkness && self instanceof DarknessFogEnvironment
            || Settings.noBlindness && self instanceof BlindnessFogEnvironment) cir.setReturnValue(false);
    }
}
