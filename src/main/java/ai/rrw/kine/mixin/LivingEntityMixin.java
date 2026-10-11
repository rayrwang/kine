package ai.rrw.kine.mixin;

import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.CrashProtection;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {

    // updateFallFlyingMovement returns this tick's velocity; it is clamped before it is stored and moved,
    // so the reduced speed is also next tick's starting speed, which the wall damage formula compares against.
    @ModifyExpressionValue(
        method = "travelFallFlying(Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/world/entity/LivingEntity;updateFallFlyingMovement(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;")
    )
    private Vec3 kine$crashProtect(Vec3 movement) {
        if ((Object) this != Minecraft.getInstance().player) return movement;  // local player only
        if (!Settings.crashProtection) return movement;
        return CrashProtection.clamp((LivingEntity) (Object) this, movement);
    }
}
