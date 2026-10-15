package ai.rrw.kine.mixin.v1_21_2;

import ai.rrw.kine.compat.v1_21_2.Fog;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No distance fog takes the terrain's fade at the render distance away, and these releases draw the clouds with the
 * terrain's fog; 26.1 fades the clouds at a distance of their own, which the setting leaves. So the clouds are drawn
 * with the fog the terrain would have had (Fog.cloudFog), and the fog is put back after.
 */
@Mixin(CloudRenderer.class)
public class CloudFogMixin {
    @Unique private FogParameters kine$terrainFog;

    @Inject(method = "render", at = @At("HEAD"))
    private void kine$cloudFade(CallbackInfo ci) {
        FogParameters fog = Fog.cloudFog();
        if (fog == null) return;
        kine$terrainFog = RenderSystem.getShaderFog();
        RenderSystem.setShaderFog(fog);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void kine$putBack(CallbackInfo ci) {
        if (kine$terrainFog == null) return;
        RenderSystem.setShaderFog(kine$terrainFog);
        kine$terrainFog = null;
    }
}
