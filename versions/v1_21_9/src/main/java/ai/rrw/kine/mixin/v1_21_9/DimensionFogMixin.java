package ai.rrw.kine.mixin.v1_21_9;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.v1_21_9.Fog;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.DimensionOrBossFogEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Nether fog: the fog of a foggy dimension or a boss fight (DimensionOrBossFogEnvironment, which 1.21.9 and 1.21.10
 * use in the Nether, whose dimension effects are foggy everywhere, and under a boss bar's world fog) cleared in the
 * Nether only, as 26.1 clears the Nether's open-air fog.
 */
@Mixin(DimensionOrBossFogEnvironment.class)
public class DimensionFogMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void kine$noNetherFog(FogData fog, Entity entity, BlockPos pos, ClientLevel level, float renderDistance,
                                  DeltaTracker deltaTracker, CallbackInfo ci) {
        if (Settings.noNetherFog && level.dimension() == Level.NETHER) Fog.clearFog(fog, renderDistance);
    }
}
