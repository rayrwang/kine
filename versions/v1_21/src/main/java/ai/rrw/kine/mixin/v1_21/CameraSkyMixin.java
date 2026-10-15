package ai.rrw.kine.mixin.v1_21;

import ai.rrw.kine.Settings;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game hides the sky under Blindness or Darkness (LevelRenderer.doesMobEffectBlockSky); with No blindness or No
 * darkness on, that effect no longer hides it.
 */
@Mixin(LevelRenderer.class)
public abstract class CameraSkyMixin {
    @Inject(method = "doesMobEffectBlockSky", at = @At("HEAD"), cancellable = true)
    private void kine$skyThroughEffects(Camera camera, CallbackInfoReturnable<Boolean> cir) {
        if ((Settings.noDarkness || Settings.noBlindness) && camera.getEntity() instanceof LivingEntity living) {
            cir.setReturnValue(!Settings.noBlindness && living.hasEffect(MobEffects.BLINDNESS)
                || !Settings.noDarkness && living.hasEffect(MobEffects.DARKNESS));
        }
    }
}
