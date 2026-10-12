package ai.rrw.kine.mixin;

import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.autoflight.Takeoff;
import ai.rrw.kine.combat.WaterClutch;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the autopilot's, the takeoff's and the water clutch's per-frame control once per frame, after the mouse has
 * turned the player and before the frame is drawn: every frame, whether or not the HUD is hidden (F1), so a pilot's
 * mouse input is seen this frame and the autopilot's turn shows this frame.
 */
@Mixin(Minecraft.class)
public class MinecraftFrameMixin {
    @Inject(method = "runTick",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/MouseHandler;handleAccumulatedMovement()V",
                     shift = At.Shift.AFTER))
    private void kine$autopilotFrame(boolean advanceGameTime, CallbackInfo ci) {
        Autopilot.onFrame((Minecraft) (Object) this);
        Takeoff.onFrame((Minecraft) (Object) this);
        WaterClutch.onFrame((Minecraft) (Object) this);
    }
}
