package ai.rrw.kine.mixin.f26;

import ai.rrw.kine.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The lightmap after the game fills it (LightmapRenderStateExtractor.extract), on every update it makes. Fullbright:
 * a white ambient base, so every block and sky light level comes out at full brightness (lightmap.fsh adds the sky
 * and block light on top of the ambient colour and clamps at 1); block face shading is drawn elsewhere and stays.
 * No darkness:
 * the brightness back to the brightness option and no pulse, as if the Darkness effect weren't there. No boss fog:
 * no darkening of the world by a boss bar (the Wither's).
 */
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "extract", at = @At("TAIL"))
    private void kine$lightmap(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
        if (!renderState.needsUpdate) return;
        if (Settings.fullbright) renderState.ambientColor = LightmapRenderStateExtractor.WHITE;
        if (Settings.noDarkness) {
            renderState.brightness = Math.max(0.0F, minecraft.options.gamma().get().floatValue());
            renderState.darknessEffectScale = 0.0F;
        }
        if (Settings.noBossFog) renderState.bossOverlayWorldDarkening = 0.0F;
    }
}
