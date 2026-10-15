package ai.rrw.kine.mixin.v1_21_6;

import ai.rrw.kine.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * FogRenderer.setupFog: No boss fog takes away the boss bar's darkening of the fog colour (its darkenWorldAmount) and
 * its fog: before 1.21.9 GameRenderer passes whether the place is foggy (the dimension there, or a boss bar that makes
 * fog), and a foggy place gets DimensionOrBossFogEnvironment's short fog instead of the open-air one; with No boss fog
 * only the dimension counts, as GameRenderer reads it. The sky stays as the game draws it during a boss fight (26.x
 * keeps it too). No distance fog takes away the fade at the edge of the render distance (renderDistanceStart and End,
 * which these releases write straight into the fog buffer), so the world ends in a hard edge there.
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
    @ModifyVariable(method = "setupFog", at = @At("HEAD"), argsOnly = true)
    private float kine$noBossDarkening(float darkenWorldAmount) {
        return Settings.noBossFog ? 0.0F : darkenWorldAmount;
    }

    @ModifyVariable(method = "setupFog", at = @At("HEAD"), argsOnly = true)
    private boolean kine$noBossFog(boolean foggy) {
        if (!foggy || !Settings.noBossFog) return foggy;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return foggy;
        BlockPos at = mc.gameRenderer.getMainCamera().getBlockPosition();
        return mc.level.effects().isFoggyAt(at.getX(), at.getZ());
    }

    // updateBuffer(buffer, offset, colour, environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd,
    // skyEnd, cloudEnd)
    @ModifyArg(method = "setupFog", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"),
        index = 5)
    private float kine$noDistanceFogStart(float renderDistanceStart) {
        return Settings.noDistanceFog ? Float.MAX_VALUE : renderDistanceStart;
    }

    @ModifyArg(method = "setupFog", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"),
        index = 6)
    private float kine$noDistanceFogEnd(float renderDistanceEnd) {
        return Settings.noDistanceFog ? Float.MAX_VALUE : renderDistanceEnd;
    }
}
