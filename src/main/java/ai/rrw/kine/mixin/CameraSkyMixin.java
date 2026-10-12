package ai.rrw.kine.mixin;

import ai.rrw.kine.Settings;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game hides the sky under Blindness or Darkness (Camera.extractRenderState); with No blindness or No darkness on,
 * that effect no longer hides it.
 */
@Mixin(Camera.class)
public abstract class CameraSkyMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void kine$skyThroughEffects(CameraRenderState cameraState, float cameraEntityPartialTicks, CallbackInfo ci) {
        if ((Settings.noDarkness || Settings.noBlindness) && ((Camera) (Object) this).entity() instanceof LivingEntity living) {
            cameraState.entityRenderState.doesMobEffectBlockSky =
                !Settings.noBlindness && living.hasEffect(MobEffects.BLINDNESS)
                || !Settings.noDarkness && living.hasEffect(MobEffects.DARKNESS);
        }
    }
}
