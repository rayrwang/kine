package ai.rrw.kine.mixin.v1_21_2;

import ai.rrw.kine.compat.v1_21_2.ServerFall;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.21.4: the server finished the player's use of an item (entity event 9), just before a chorus fruit's teleport
 *  (ServerFall.usedItem); read before the game clears the item in use. */
@Mixin(Player.class)
public class ServerFallChorusMixin {
    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void kine$usedItem(byte event, CallbackInfo ci) {
        if (event == 9) ServerFall.usedItem((Player) (Object) this);
    }
}
