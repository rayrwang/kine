package ai.rrw.kine.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    // fully-opaque ARGB; bright orange-red. Tweak this for a different glow color.
    private static final int KINE_GLOW_COLOR = 0xFFFF4500;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void kine$colorProjectileGlow(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        if (state.outlineColor != 0 && entity instanceof Projectile) {
            state.outlineColor = KINE_GLOW_COLOR;   // only when already glowing (our EntityMixin set it)
        }
    }
}
