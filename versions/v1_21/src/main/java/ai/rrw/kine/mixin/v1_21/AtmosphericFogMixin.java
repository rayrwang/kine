package ai.rrw.kine.mixin.v1_21;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.v1_21.Fog;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The open-air fog of 1.21 and 1.21.1, decided in LevelRenderer.renderLevel: a place is foggy (FogRenderer.setupFog's
 * short fog, and no sky) where the dimension's effects say so (the Nether everywhere) or a boss bar asks for world fog
 * (the Ender Dragon fight). No Nether fog clears it in the Nether only; No boss fog ignores the boss bar's. And the
 * clouds, drawn with the terrain's fog, keep the render-distance fade when No distance fog takes it from the terrain
 * (compat.v1_21.Fog).
 */
@Mixin(LevelRenderer.class)
public class AtmosphericFogMixin {
    @Shadow private ClientLevel level;

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;isFoggyAt(II)Z"))
    private boolean kine$noNetherFog(boolean foggy) {
        return foggy && !(Settings.noNetherFog && level != null && level.dimension() == Level.NETHER);
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;shouldCreateWorldFog()Z"))
    private boolean kine$noBossFog(boolean fog) {
        return fog && !Settings.noBossFog;
    }

    // around the call, not inside renderClouds: Fabric cancels renderClouds at its head for a dimension's own cloud
    // renderer, and the fog must come back either way
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V"))
    private void kine$cloudsFade(LevelRenderer self, PoseStack poseStack, Matrix4f modelView, Matrix4f projection, float partialTick,
                                 double x, double y, double z, Operation<Void> renderClouds) {
        boolean fade = Fog.cloudsFade();
        try {
            renderClouds.call(self, poseStack, modelView, projection, partialTick, x, y, z);
        } finally {
            if (fade) Fog.afterClouds();
        }
    }
}
