package ai.rrw.kine.mixin.v1_21;

import ai.rrw.kine.VanillaActions;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client tick's point where this tick's rotation has gone to the server: the player sends its move packet from
 * its own tick (LocalPlayer.tick: sendPosition, or the rotation packet while riding), inside ClientLevel.tickEntities,
 * so just after that. 1.21 and 1.21.1 send no tick-end packet after it.
 */
@Mixin(Minecraft.class)
public class AfterRotationSentMixin {
    @Inject(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V",
                     shift = At.Shift.AFTER))
    private void kine$afterRotationSent(CallbackInfo ci) {
        VanillaActions.afterEntities((Minecraft) (Object) this);
    }
}
