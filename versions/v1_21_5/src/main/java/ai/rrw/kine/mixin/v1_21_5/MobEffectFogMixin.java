package ai.rrw.kine.mixin.v1_21_5;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.v1_21_5.Fog;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * No darkness and No blindness, the fog part: the effect's fog function is passed over (as if the entity didn't have
 * the effect), so neither its short fog nor its darkening of the fog colour applies (FogRenderer asks
 * getPriorityFogFunction for both); the next one applies instead, as on 26.x. The other parts: LightmapMixin (the
 * Darkness dimming and pulse), CameraSkyMixin (the hidden sky).
 */
@Mixin(FogRenderer.class)
public abstract class MobEffectFogMixin {
    // Blindness's, then Darkness's (their classes are package-private, so they are taken from the game's own list)
    @Shadow @Final private static List<Object> MOB_EFFECT_FOG;

    @Inject(method = "getPriorityFogFunction", at = @At("HEAD"), cancellable = true)
    private static void kine$noEffectFog(Entity entity, float partialTick, CallbackInfoReturnable<Object> cir) {
        if (Settings.noDarkness || Settings.noBlindness) {
            int i = Fog.effectFog(entity);
            cir.setReturnValue(i < 0 ? null : MOB_EFFECT_FOG.get(i));
        }
    }
}
