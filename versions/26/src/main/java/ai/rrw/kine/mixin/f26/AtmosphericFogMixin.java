package ai.rrw.kine.mixin.f26;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.f26.Fog26;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The open-air fog (AtmosphericFogEnvironment): No Nether fog clears it in the Nether only (the Nether's dimension sets
 * it from 10 to 96 blocks); No rain fog keeps the rain from pulling it in (the multiplier held at 0, so it eases back
 * when switched off); No boss fog ignores the boss bar's world fog (the Ender Dragon fight).
 */
@Mixin(AtmosphericFogEnvironment.class)
public class AtmosphericFogMixin {
    @Shadow private float rainFogMultiplier;

    @Inject(method = "updateRainFogState", at = @At("TAIL"))
    private void kine$noRainFog(CallbackInfo ci) {
        if (Settings.noRainFog) rainFogMultiplier = 0.0F;
    }

    @Redirect(method = "setupFog", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;shouldCreateWorldFog()Z"))
    private boolean kine$noBossFog(BossHealthOverlay overlay) {
        return !Settings.noBossFog && overlay.shouldCreateWorldFog();
    }

    @Inject(method = "setupFog", at = @At("TAIL"))
    private void kine$noNetherFog(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker,
                                  CallbackInfo ci) {
        if (Settings.noNetherFog && level.dimension() == Level.NETHER) Fog26.clearFog(fog, renderDistance);
    }
}
