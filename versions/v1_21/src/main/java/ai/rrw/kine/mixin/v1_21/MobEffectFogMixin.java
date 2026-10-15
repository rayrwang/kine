package ai.rrw.kine.mixin.v1_21;

import ai.rrw.kine.Settings;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;
import java.util.function.Predicate;

/**
 * No darkness and No blindness, the fog part: the effect is left out of FogRenderer.getPriorityFogFunction's pick
 * (the first effect fog whose effect the camera entity has), so neither its short fog (setupFog) nor its darkening of
 * the fog colour (setupColor) applies, and the next one does, as 26.1's MobEffectFogMixin. The other parts:
 * LightmapMixin (the Darkness dimming and pulse), CameraSkyMixin (the hidden sky).
 */
@Mixin(FogRenderer.class)
public class MobEffectFogMixin {
    // [BlindnessFogFunction, DarknessFogFunction], in that order (their interface is package-private, so they are
    // told apart by their place in the game's list)
    @Shadow @Final private static List<Object> MOB_EFFECT_FOG;

    @ModifyArg(method = "getPriorityFogFunction", at = @At(value = "INVOKE",
        target = "Ljava/util/stream/Stream;filter(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;"))
    private static Predicate<Object> kine$noEffectFog(Predicate<Object> applies) {
        return fog -> applies.test(fog)
            && !(Settings.noBlindness && fog == MOB_EFFECT_FOG.get(0) || Settings.noDarkness && fog == MOB_EFFECT_FOG.get(1));
    }
}
