package ai.rrw.kine.mixin.v1_21_2;

import ai.rrw.kine.VanillaActions;
import ai.rrw.kine.compat.Input;
import ai.rrw.kine.compat.v1_21_2.EpochImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets features press keys for the player: after the keyboard is read each tick, VanillaActions may change
 * the key presses, and the impulses are rebuilt from them as KeyboardInput builds them. The player then moves
 * and sends its input packet from these keys, as it would if they were held.
 *
 * <p>The tick is tick(isMovingSlowly, sneakingSpeed) on 1.21.2 and 1.21.3, which slows a sneaking or crawling player's
 * impulses inside it, and tick() on 1.21.4, whose LocalPlayer.aiStep slows them after; the handler takes no arguments
 * so it fits both, and on the older two slows the rebuilt impulses the same way, from the player that passed them.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("RETURN"))
    private void kine$pressKeys(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.input != (Object) this) return;
        Input read = EpochImpl.kineKeys(this.keyPresses);
        Input keys = VanillaActions.adjustInput(player, read);
        if (keys.equals(read)) return;
        this.keyPresses = EpochImpl.gameKeys(keys);
        float forward = impulse(keys.forward(), keys.backward());
        float left = impulse(keys.left(), keys.right());
        if (EpochImpl.SLOWDOWN_IN_INPUT_TICK && player.isMovingSlowly()) {
            float f = (float) player.getAttributeValue(Attributes.SNEAKING_SPEED);
            forward *= f;
            left *= f;
        }
        this.forwardImpulse = forward;
        this.leftImpulse = left;
    }

    // KeyboardInput.calculateImpulse
    @Unique
    private static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
    }
}
