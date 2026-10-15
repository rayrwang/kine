package ai.rrw.kine.mixin.v1_21_9;

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
 * its world fog; No distance fog takes away the fade at the edge of the render distance (renderDistanceStart and End,
 * which 1.21.10 writes straight into the fog buffer), so the world ends in a hard edge there.
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
    @ModifyVariable(method = "setupFog", at = @At("HEAD"), argsOnly = true)
    private float kine$noBossDarkening(float darkenWorldAmount) {
        return Settings.noBossFog ? 0.0F : darkenWorldAmount;
    }

    // GameRenderer.renderLevel passes "foggy": the dimension is foggy where the camera is (the Nether) or a boss bar
    // asks for world fog (the Ender Dragon). Foggy picks DimensionOrBossFogEnvironment over the open-air fog. No boss
    // fog keeps only the dimension's part, as 26.1 drops the boss bar's fog from the open-air fog; the sky stays
    // hidden under the boss bar as there (renderLevel's own copy of the flag).
    @ModifyVariable(method = "setupFog", at = @At("HEAD"), argsOnly = true)
    private boolean kine$noBossFog(boolean foggy) {
        if (!foggy || !Settings.noBossFog) return foggy;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return foggy;
        BlockPos pos = mc.gameRenderer.getMainCamera().getBlockPosition();
        return mc.level.effects().isFoggyAt(pos.getX(), pos.getZ());
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
