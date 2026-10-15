package ai.rrw.kine.mixin.v1_21_5;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.v1_21_5.Fog;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No distance fog leaves the clouds their fade: 1.21.5 draws them with the shader fog the pass before left set (the
 * terrain fog), and 26.x fades them by their own distance, which No distance fog doesn't touch. So while they are
 * drawn, the fog is the terrain fog as it was before No distance fog (Fog.cloudFog).
 */
@Mixin(CloudRenderer.class)
public class CloudFogMixin {
    @Unique private static FogParameters kine$saved;

    @Inject(method = "render", at = @At("HEAD"))
    private void kine$cloudFog(int color, CloudStatus status, float height, Vec3 camera, float ticks, CallbackInfo ci) {
        if (Settings.noDistanceFog && Fog.cloudFog != null) {
            kine$saved = RenderSystem.getShaderFog();
            RenderSystem.setShaderFog(Fog.cloudFog);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void kine$restoreFog(CallbackInfo ci) {
        if (kine$saved != null) {
            RenderSystem.setShaderFog(kine$saved);
            kine$saved = null;
        }
    }
}
