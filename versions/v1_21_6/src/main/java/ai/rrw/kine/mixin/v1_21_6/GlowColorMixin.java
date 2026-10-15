package ai.rrw.kine.mixin.v1_21_6;

import ai.rrw.kine.Settings;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Glowing projectiles in red (26.x and 1.21.11: EntityRendererMixin, on the render state's outline colour). Before
 * 1.21.9 the entity render state has no outline colour: LevelRenderer.renderEntities asks each glowing entity for its
 * team colour and sets the outline buffer to it, so the colour is changed there. A MixinExtras WrapOperation, so it
 * stacks on what another mod makes of the same call.
 */
@Mixin(LevelRenderer.class)
public class GlowColorMixin {

    // red (the outline buffer takes the alpha as 255); the outline blur shader override keeps it from darkening at the
    // edges
    private static final int KINE_GLOW_COLOR = 0xFF0000;

    @WrapOperation(method = "renderEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;getTeamColor()I"))
    private int kine$colorProjectileGlow(Entity entity, Operation<Integer> original) {
        // only glowing entities are asked here (EntityMixin makes projectiles glow)
        if (Settings.projectileGlow && entity instanceof Projectile) return KINE_GLOW_COLOR;
        return original.call(entity);
    }
}
