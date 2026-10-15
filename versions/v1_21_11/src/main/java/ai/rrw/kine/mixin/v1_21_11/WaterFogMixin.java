package ai.rrw.kine.mixin.v1_21_11;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.v1_21_11.Fog;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No water fog: after the game sets up the fog under water, clear it (Visibility). */
@Mixin(WaterFogEnvironment.class)
public class WaterFogMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void kine$noWaterFog(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker,
                                 CallbackInfo ci) {
        if (Settings.noWaterFog) Fog.clearFog(fog, renderDistance);
    }
}
