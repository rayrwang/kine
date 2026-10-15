package ai.rrw.kine.mixin.v1_21;

import ai.rrw.kine.compat.v1_21.InputSprint;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The sprint key as LocalPlayer.aiStep reads it (its two calls to the sprint key mapping's isDown: the double-tap
 * window and the held key): the one the input read this tick, with kine's presses (KeyboardInputMixin), so kine's
 * sprint reaches the game as a held sprint key would. On 26.1 the input's key record carries it.
 */
@Mixin(LocalPlayer.class)
public class SprintKeyMixin {
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean kine$sprintKey(KeyMapping key, Operation<Boolean> isDown) {
        if (key == Minecraft.getInstance().options.keySprint && ((LocalPlayer) (Object) this).input instanceof InputSprint s)
            return s.kine$sprint();
        return isDown.call(key);
    }
}
