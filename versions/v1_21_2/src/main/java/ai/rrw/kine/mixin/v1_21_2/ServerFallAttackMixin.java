package ai.rrw.kine.mixin.v1_21_2;

import ai.rrw.kine.compat.v1_21_2.ServerFall;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.21.4: an attack sent, which the server may answer with a mace smash's reset of the player's fall distance
 *  (ServerFall.attack). Before the packet: the server takes the attack before this tick's move. */
@Mixin(MultiPlayerGameMode.class)
public class ServerFallAttackMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void kine$smash(Player player, Entity target, CallbackInfo ci) {
        ServerFall.attack(player, target);
    }
}
