package ai.rrw.kine.mixin;

import ai.rrw.kine.VanillaActions;
import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.combat.KillAura;
import ai.rrw.kine.combat.WaterClutch;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Three points in the client tick. Its start, before anything else, whatever screen is open: the autopilot and the
 * water clutch finish the turns their frames have been making toward this tick's rotation, and the kill aura turns to
 * the rotation it holds for the server. Just before handleKeybinds, where a vanilla client
 * attacks and uses items (after this tick's crosshair pick, before the player moves and sends its rotation). The third,
 * once the player has sent this tick's rotation and before the tick-end packet, moved between releases: it is each
 * epoch's AfterRotationSentMixin (versions/README.md).
 */
@Mixin(Minecraft.class)
public class MinecraftTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void kine$tickStart(CallbackInfo ci) {
        ai.rrw.kine.view.Freecam.onTickStart((Minecraft) (Object) this);   // first: in freecam your keys move the camera
        ai.rrw.kine.walk.Walk.onTickStart((Minecraft) (Object) this);   // then your keys end a walk before anything turns
        ai.rrw.kine.autoflight.Guardian.onTickStart((Minecraft) (Object) this);   // the guardian's judgment and rescue look first
        Autopilot.onTickStart((Minecraft) (Object) this);
        WaterClutch.onTickStart((Minecraft) (Object) this);
        KillAura.onTickStart((Minecraft) (Object) this);
    }

    @Inject(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;handleKeybinds()V"))
    private void kine$beforeKeybinds(CallbackInfo ci) {
        VanillaActions.beforeKeybinds((Minecraft) (Object) this);
    }

}
