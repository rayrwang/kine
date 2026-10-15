package ai.rrw.kine.mixin.v1_21_2;

import ai.rrw.kine.compat.v1_21_2.ServerFall;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The local player's fall distance as the server counts it, on 1.21.4 (compat.v1_21_2.ServerFall says what changes it
 * and why): the client's move of its player, from where it starts to the point where 1.21.4 skips checkFallDamage,
 * and the resets, the halving in lava and the glide's cap the client runs for its player anyway. Each only reads the
 * game; on 1.21.2 and 1.21.3 each does nothing.
 */
@Mixin(Entity.class)
public class ServerFallMixin {
    @Inject(method = "move", at = @At("HEAD"))
    private void kine$moveStart(CallbackInfo ci) {
        ServerFall.moveStart((Entity) (Object) this);
    }

    // just after the move, before the on-block lookup that feeds checkFallDamage (skipped on 1.21.4 for this player)
    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getOnPosLegacy()Lnet/minecraft/core/BlockPos;"))
    private void kine$moved(CallbackInfo ci) {
        ServerFall.moved((Entity) (Object) this);
    }

    @Inject(method = "resetFallDistance", at = @At("HEAD"))
    private void kine$reset(CallbackInfo ci) {
        ServerFall.reset((Entity) (Object) this);
    }

    @Inject(method = "checkSlowFallDistance", at = @At("HEAD"))
    private void kine$slowFall(CallbackInfo ci) {
        ServerFall.slowFall((Entity) (Object) this);
    }

    // Entity.baseTick: `if (isInLava()) { lavaHurt(); fallDistance *= 0.5F; }`
    @Inject(method = "baseTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;lavaHurt()V", shift = At.Shift.AFTER))
    private void kine$inLava(CallbackInfo ci) {
        ServerFall.inLava((Entity) (Object) this);
    }
}
