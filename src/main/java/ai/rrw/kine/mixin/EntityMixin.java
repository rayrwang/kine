package ai.rrw.kine.mixin;

import ai.rrw.kine.Settings;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void kine$glowProjectiles(CallbackInfoReturnable<Boolean> cir) {
        if (Settings.projectileGlow && (Object) this instanceof Projectile) {
            cir.setReturnValue(true);
        }
    }
}
