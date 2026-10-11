package ai.rrw.kine.mixin;

import ai.rrw.kine.VanillaActions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets features press keys for the player: after the keyboard is read each tick, VanillaActions may change
 * the key presses, and the move vector is rebuilt from them as KeyboardInput builds it. The player then moves
 * and sends its input packet from these keys, as it would if they were held.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("RETURN"))
    private void kine$pressKeys(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.input != (Object) this) return;
        Input keys = VanillaActions.adjustInput(mc.player, this.keyPresses);
        if (keys.equals(this.keyPresses)) return;
        this.keyPresses = keys;
        this.moveVector = new Vec2(impulse(keys.left(), keys.right()), impulse(keys.forward(), keys.backward())).normalized();
    }

    // KeyboardInput.calculateImpulse
    @Unique
    private static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
    }
}
