package ai.rrw.kine.mixin;

import ai.rrw.kine.view.Freecam;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Freecam: the mouse turns the freecam, not your player (MouseHandler.turnPlayer's one call to LocalPlayer.turn). */
@Mixin(MouseHandler.class)
public class FreecamMouseMixin {
    @Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void kine$freecamTurn(LocalPlayer player, double dx, double dy) {
        if (Freecam.active()) Freecam.turn(dx, dy);
        else player.turn(dx, dy);
    }
}
