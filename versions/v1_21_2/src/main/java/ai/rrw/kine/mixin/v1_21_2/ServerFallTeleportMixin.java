package ai.rrw.kine.mixin.v1_21_2;

import ai.rrw.kine.compat.v1_21_2.ServerFall;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.21.4: the server moved the player; after an ender pearl's or a chorus fruit's teleport the server's fall distance
 *  is 0 (ServerFall.teleported). On the main thread only (handleMovePlayer hands itself over to it first). */
@Mixin(ClientPacketListener.class)
public class ServerFallTeleportMixin {
    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void kine$teleported(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) ServerFall.teleported(mc.player);
    }
}
