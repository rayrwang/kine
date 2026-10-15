package ai.rrw.kine.mixin.f26;

import ai.rrw.kine.Settings;
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

    // opaque red; the outline blur shader override keeps it from darkening at the edges
    private static final int KINE_GLOW_COLOR = 0xFFFF0000;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void kine$colorProjectileGlow(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        if (Settings.projectileGlow && state.outlineColor != 0 && entity instanceof Projectile) {
            state.outlineColor = KINE_GLOW_COLOR;   // only when already glowing (EntityMixin makes projectiles glow)
        }
    }
}
