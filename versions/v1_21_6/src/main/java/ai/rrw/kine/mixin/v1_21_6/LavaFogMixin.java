package ai.rrw.kine.mixin.v1_21_6;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.v1_21_6.Fog;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.LavaFogEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No lava fog: after the game sets up the fog inside lava, clear it (Visibility). */
@Mixin(LavaFogEnvironment.class)
public class LavaFogMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void kine$noLavaFog(FogData fog, Entity entity, BlockPos pos, ClientLevel level, float renderDistance,
                                DeltaTracker deltaTracker, CallbackInfo ci) {
        if (Settings.noLavaFog) Fog.clearFog(fog, renderDistance);
    }
}
