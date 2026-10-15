package ai.rrw.kine.mixin.v26_3;

import ai.rrw.kine.VanillaActions;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client tick's point where this tick's rotation has gone to the server and the tick-end packet hasn't. From 26.3
 * the player no longer sends its move packet from its own tick: Minecraft.tick calls LocalPlayer.sendChanges after
 * the entities and block entities tick (profiler section "playerSync"), so just after that call. (Hooked after
 * tickEntities as on 26.2, the kill aura would undo its turn before the server saw it.)
 */
@Mixin(Minecraft.class)
public class AfterRotationSentMixin {
    @Inject(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;sendChanges()V",
                     shift = At.Shift.AFTER))
    private void kine$afterRotationSent(CallbackInfo ci) {
        VanillaActions.afterEntities((Minecraft) (Object) this);
    }
}
