package ai.rrw.kine.mixin;

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
 * The lightmap after the game fills it (LightmapRenderStateExtractor.extract), on every update it makes. No darkness:
 * the brightness back to the brightness option and no pulse, as if the Darkness effect weren't there. No boss fog:
 * no darkening of the world by a boss bar (the Wither's).
 */
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "extract", at = @At("TAIL"))
    private void kine$lightmap(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
        if (!renderState.needsUpdate) return;
        if (Settings.noDarkness) {
            renderState.brightness = Math.max(0.0F, minecraft.options.gamma().get().floatValue());
            renderState.darknessEffectScale = 0.0F;
        }
        if (Settings.noBossFog) renderState.bossOverlayWorldDarkening = 0.0F;
    }
}
