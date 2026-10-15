package ai.rrw.kine.mixin.v1_21_6;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.v1_21_6.Fog;
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
 * No Nether fog: before 1.21.9 the Nether's short fog is DimensionOrBossFogEnvironment's (the game picks it where the
 * dimension is foggy, which the Nether is everywhere, or a boss bar makes fog); in the Nether only, clear it after the
 * game sets it up, as 26.x clears the open-air fog there. The boss bar's part: FogRendererMixin.
 */
@Mixin(DimensionOrBossFogEnvironment.class)
public class DimensionOrBossFogMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void kine$noNetherFog(FogData fog, Entity entity, BlockPos pos, ClientLevel level, float renderDistance,
                                  DeltaTracker deltaTracker, CallbackInfo ci) {
        if (Settings.noNetherFog && level.dimension() == Level.NETHER) Fog.clearFog(fog, renderDistance);
    }
}
