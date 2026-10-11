package ai.rrw.kine.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

/** Leave the world, showing a reason on the disconnect screen. */
public final class Disconnect {
    private Disconnect() {}

    public static void withMessage(Minecraft mc, Component reason) {
        ClientPacketListener cpl = mc.getConnection();
        if (cpl != null) cpl.getConnection().disconnect(reason);
    }
}
