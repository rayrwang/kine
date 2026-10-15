package ai.rrw.kine.mixin.v1_21;

import ai.rrw.kine.VanillaActions;
import ai.rrw.kine.compat.Input;
import ai.rrw.kine.compat.v1_21.InputSprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets features press keys for the player: after the keyboard is read each tick (KeyboardInput.tick, called by
 * LocalPlayer.aiStep before the player moves), VanillaActions may change the keys, and the impulses are made again
 * from them as KeyboardInput makes them (not normalised on these releases; scaled by the sneak speed when the game
 * asked for slow movement). The input has no sprint here: the sprint key's state is kept beside it (InputSprint) and
 * SprintKeyMixin hands it to aiStep where the game reads the sprint key. The player then moves, sprints and sends its
 * packets from these keys, as it would if they were held.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends net.minecraft.client.player.Input implements InputSprint {
    @Shadow @Final private net.minecraft.client.Options options;

    @Unique private boolean kine$sprint;

    @Override
    public boolean kine$sprint() { return kine$sprint; }

    @Inject(method = "tick", at = @At("RETURN"))
    private void kine$pressKeys(boolean slow, float sneakSpeed, CallbackInfo ci) {
        kine$sprint = options.keySprint.isDown();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.input != (Object) this) return;
        Input read = new Input(up, down, left, right, jumping, shiftKeyDown, kine$sprint);
        Input keys = VanillaActions.adjustInput(mc.player, read);
        if (keys.equals(read)) return;
        up = keys.forward();
        down = keys.backward();
        left = keys.left();
        right = keys.right();
        jumping = keys.jump();
        shiftKeyDown = keys.shift();
        kine$sprint = keys.sprint();
        forwardImpulse = impulse(up, down);
        leftImpulse = impulse(left, right);
        if (slow) {
            leftImpulse *= sneakSpeed;
            forwardImpulse *= sneakSpeed;
        }
    }

    // KeyboardInput.calculateImpulse
    @Unique
    private static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
    }
}
