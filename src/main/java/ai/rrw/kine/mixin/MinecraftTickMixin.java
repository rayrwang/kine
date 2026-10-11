package ai.rrw.kine.mixin;

import ai.rrw.kine.VanillaActions;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two points in the client tick. Just before handleKeybinds, where a vanilla client attacks and uses items
 * (after this tick's crosshair pick, before the player moves and sends its rotation). And just after the
 * entities tick, once the player has sent this tick's rotation and before the tick-end packet.
 */
@Mixin(Minecraft.class)
public class MinecraftTickMixin {
    @Inject(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;handleKeybinds()V"))
    private void kine$beforeKeybinds(CallbackInfo ci) {
        VanillaActions.beforeKeybinds((Minecraft) (Object) this);
    }

    @Inject(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V",
                     shift = At.Shift.AFTER))
    private void kine$afterEntities(CallbackInfo ci) {
        VanillaActions.afterEntities((Minecraft) (Object) this);
    }
}
