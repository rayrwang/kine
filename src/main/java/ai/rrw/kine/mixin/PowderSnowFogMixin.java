package ai.rrw.kine.mixin;

import ai.rrw.kine.Settings;
import ai.rrw.kine.view.Visibility;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.PowderedSnowFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No powder snow fog: after the game sets up the fog inside powder snow, clear it (Visibility). */
@Mixin(PowderedSnowFogEnvironment.class)
public class PowderSnowFogMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void kine$noPowderSnowFog(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker,
                                      CallbackInfo ci) {
        if (Settings.noPowderSnowFog) Visibility.clearFog(fog, renderDistance);
    }
}
