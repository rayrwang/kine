package ai.rrw.kine.mixin.v1_21;

import ai.rrw.kine.Settings;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A glowing entity's outline colour on 1.21 and 1.21.1: LevelRenderer.renderLevel asks Entity.getTeamColor for it,
 * only for an entity drawn glowing. Projectile glow makes a projectile's red (26.1: EntityRendererMixin on the render
 * state's outlineColor).
 */
@Mixin(LevelRenderer.class)
public class GlowColorMixin {
    // opaque red; the outline blur shader override keeps it from darkening at the edges
    private static final int KINE_GLOW_COLOR = 0xFFFF0000;

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getTeamColor()I"))
    private int kine$colorProjectileGlow(Entity entity, Operation<Integer> teamColor) {
        if (Settings.projectileGlow && entity instanceof Projectile) return KINE_GLOW_COLOR;   // EntityMixin makes them glow
        return teamColor.call(entity);
    }
}
