package ai.rrw.kine.mixin.v1_21_2;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.v1_21_2.Fog;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * FogRenderer, where these releases make their fog (compat.v1_21_2.Fog says how it maps to 26.1's environments):
 * <ul>
 * <li>setupFog: the water, lava, powder snow and render-distance fog as kine's settings leave them (Fog.fog);</li>
 * <li>getPriorityFogFunction: No blindness and No darkness, the fog part: that effect's fog function never applies,
 *     so neither its short fog nor its darkening of the fog colour (setupFog and computeFogColor both ask here). The
 *     other parts: LightmapMixin (the Darkness dimming and pulse), CameraSkyMixin (the hidden sky);</li>
 * <li>computeFogColor: No boss fog takes away the boss bar's darkening of the fog colour (its darkenWorldAmount).</li>
 * </ul>
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
    @Shadow private static boolean fogEnabled;
    /** The effects' fog functions in the game's order: Blindness's, then Darkness's (the type is the game's own, private). */
    @Shadow @Final private static List<Object> MOB_EFFECT_FOG;

    @Inject(method = "setupFog", at = @At("HEAD"), cancellable = true)
    private static void kine$fog(Camera camera, FogRenderer.FogMode mode, Vector4f color, float renderDistance, boolean foggy,
                                 float partialTick, CallbackInfoReturnable<FogParameters> cir) {
        FogParameters fog = Fog.fog(fogEnabled, camera, mode, color, renderDistance, foggy);
        if (fog != null) cir.setReturnValue(fog);
    }

    @Inject(method = "getPriorityFogFunction", at = @At("HEAD"), cancellable = true)
    private static void kine$noEffectFog(Entity entity, float partialTick, CallbackInfoReturnable<Object> cir) {
        if (!(Settings.noBlindness || Settings.noDarkness)) return;
        if (!(entity instanceof LivingEntity living)) return;   // the game's answer, none
        cir.setReturnValue(Fog.blindnessFog(living) ? MOB_EFFECT_FOG.get(0) : Fog.darknessFog(living) ? MOB_EFFECT_FOG.get(1) : null);
    }

    // computeFogColor(camera, partialTick, level, renderDistance, darkenWorldAmount): the second float
    @ModifyVariable(method = "computeFogColor", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private static float kine$noBossDarkening(float darkenWorldAmount) {
        return Settings.noBossFog ? 0.0F : darkenWorldAmount;
    }
}
