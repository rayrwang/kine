package ai.rrw.kine.mixin.v1_21_2;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The sprint key as LocalPlayer.aiStep reads it on 1.21.2 to 1.21.4 (its two calls to the sprint key mapping's isDown:
 * the double-tap window and the held key; from 1.21.5 it reads the input's key record): the sprint in the key record
 * the input made this tick, with kine's presses (KeyboardInputMixin), so kine's sprint reaches the game as a held sprint
 * key would. Without kine's presses the two are the same: KeyboardInput.tick reads the key mapping earlier in aiStep.
 */
@Mixin(LocalPlayer.class)
public class SprintKeyMixin {
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean kine$sprintKey(KeyMapping key, Operation<Boolean> isDown) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (key == Minecraft.getInstance().options.keySprint && self.input instanceof KeyboardInput) return self.input.keyPresses.sprint();
        return isDown.call(key);
    }
}
