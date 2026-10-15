package ai.rrw.kine.mixin.v1_21_5;

import ai.rrw.kine.Settings;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The fog of a foggy place (LevelRenderer.renderLevel: the dimension's effects are foggy here, the Nether's, or a boss
 * bar asks for world fog, the Ender Dragon's), which on 1.21.5 also leaves out the sky: No Nether fog clears it in the
 * Nether only (a boss bar's too, as 26.x's clearing comes after it); No boss fog ignores the boss bar's world fog.
 * Equivalent of 26.x's AtmosphericFogMixin, whose rain part 1.21.5 doesn't need (no rain fog).
 */
@Mixin(LevelRenderer.class)
public class LevelRendererFogMixin {
    @Shadow private ClientLevel level;

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;isFoggyAt(II)Z"))
    private boolean kine$noNetherFog(boolean foggy) {
        return foggy && !kine$netherFogCleared();
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;shouldCreateWorldFog()Z"))
    private boolean kine$noBossFog(boolean fog) {
        return fog && !Settings.noBossFog && !kine$netherFogCleared();
    }

    @Unique
    private boolean kine$netherFogCleared() {
        return Settings.noNetherFog && level != null && level.dimension() == Level.NETHER;
    }
}
