package ai.rrw.kine.mixin.v1_21;

import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.combat.KillAura;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server set the player's rotation: a teleport (handleMovePlayer: /tp, an ender pearl, a gateway, an anticheat
 * setback; 1.21 and 1.21.1 have no rotation-only packet). The autopilot and the kill aura turn from the rotation the
 * server now has, so their next turn stays a human one instead of snapping back to where they were. On the main
 * thread only: the handler first hands itself over to it (PacketUtils.ensureRunningOnSameThread), so the tail runs
 * once, there.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void kine$teleported(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Autopilot.onRotationSet();
        KillAura.onRotationSet(mc.player);
    }
}
