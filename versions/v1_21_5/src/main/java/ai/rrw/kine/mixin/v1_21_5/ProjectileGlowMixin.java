package ai.rrw.kine.mixin.v1_21_5;

import ai.rrw.kine.Settings;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Projectile glow's colour: before 1.21.6 the outline colour isn't part of the entity's render state but taken from
 * its team colour as LevelRenderer.renderEntities draws a glowing entity into the outline buffer, so a glowing
 * projectile gets red there (the equivalent of 26.x's EntityRendererMixin). Only when already glowing (EntityMixin
 * makes projectiles glow). A WrapOperation, so it stacks with another mod's change of the same call.
 */
@Mixin(LevelRenderer.class)
public class ProjectileGlowMixin {
    // opaque red; the outline blur shader override keeps it from darkening at the edges
    private static final int KINE_GLOW_COLOR = 0xFFFF0000;

    @WrapOperation(method = "renderEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getTeamColor()I"))
    private int kine$colorProjectileGlow(Entity entity, Operation<Integer> original) {
        int color = original.call(entity);
        return Settings.projectileGlow && entity instanceof Projectile ? KINE_GLOW_COLOR : color;
    }
}
