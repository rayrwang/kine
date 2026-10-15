package ai.rrw.kine.mixin.v1_21_2;

import ai.rrw.kine.Settings;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Projectile glow's colour. On these releases the outline colour is the team colour LevelRenderer.renderEntities reads
 * for each glowing entity (later releases keep it in the entity's render state, where 26.1's EntityRendererMixin sets
 * it); a projectile's is red instead. EntityMixin makes projectiles glow.
 */
@Mixin(LevelRenderer.class)
public class ProjectileGlowMixin {
    // opaque red; the outline blur shader override keeps it from darkening at the edges
    @Unique private static final int KINE_GLOW_COLOR = 0xFFFF0000;

    @WrapOperation(method = "renderEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getTeamColor()I"))
    private int kine$colorProjectileGlow(Entity entity, Operation<Integer> original) {
        return Settings.projectileGlow && entity instanceof Projectile ? KINE_GLOW_COLOR : original.call(entity);
    }
}
