package ai.rrw.kine.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

/**
 * Clicks in the player's own inventory without drawing its screen. A vanilla client opens its inventory
 * without a packet, clicks, and on closing sends one close packet; on Linux and Windows closing also
 * re-reads the held keys (MouseHandler.grabMouse, KeyMapping.setAll), so a quick open, click and close
 * between two ticks leaves the keys as they were. Callers click between ticks (at the end of a client tick)
 * and then call {@link #close}.
 */
public final class InventoryClicks {
    private InventoryClicks() {}

    /** Whether the player's inventory could be opened and clicked now: no screen up, no container open,
     *  nothing on the cursor, and not riding something whose inventory the server opens instead. */
    public static boolean available(Minecraft mc, LocalPlayer p) {
        return mc.screen == null && mc.gameMode != null && !mc.gameMode.isServerControlledInventory()
            && p.containerMenu == p.inventoryMenu && p.inventoryMenu.getCarried().isEmpty();
    }

    /** The packet a vanilla client sends when it closes its inventory (LocalPlayer.closeContainer). */
    public static void close(Minecraft mc, LocalPlayer p) {
        if (mc.getConnection() != null) mc.getConnection().send(new ServerboundContainerClosePacket(p.inventoryMenu.containerId));
    }
}
