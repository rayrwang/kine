package ai.rrw.kine.mixin.v1_21_6;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.v1_21_6.Fog;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.PowderedSnowFogEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No powder snow fog: after the game sets up the fog inside powder snow, clear it (Visibility). */
@Mixin(PowderedSnowFogEnvironment.class)
public class PowderSnowFogMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void kine$noPowderSnowFog(FogData fog, Entity entity, BlockPos pos, ClientLevel level, float renderDistance,
                                      DeltaTracker deltaTracker, CallbackInfo ci) {
        if (Settings.noPowderSnowFog) Fog.clearFog(fog, renderDistance);
    }
}
