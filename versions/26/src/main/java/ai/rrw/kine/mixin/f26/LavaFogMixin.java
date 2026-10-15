package ai.rrw.kine.mixin.f26;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.f26.Fog26;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.LavaFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No lava fog: after the game sets up the fog inside lava, clear it (Visibility). */
@Mixin(LavaFogEnvironment.class)
public class LavaFogMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void kine$noLavaFog(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker,
                                CallbackInfo ci) {
        if (Settings.noLavaFog) Fog26.clearFog(fog, renderDistance);
    }
}
