package ai.rrw.kine.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

/**
 * Clicks in the player's own inventory without drawing its screen. A vanilla client opens its inventory without
 * a packet, clicks, and on closing sends one close packet. Callers click between ticks (at the end of a client
 * tick) and then close.
 *
 * <p>A vanilla client can open its inventory only inside a tick (handleKeybinds), and opening it releases every key
 * (Minecraft.setScreen, KeyMapping.releaseAll): in that tick its input packet shows no keys held, sprinting stops,
 * an item in use is let go, and nothing is attacked or used. {@link #requestOpen} plays that tick; the clicks then
 * go at its end, and {@link #closeAsVanilla} does what closing the screen does. Was (the morning of 2026-10-11): a
 * quick open, click and close between two ticks, which no vanilla client can do (the open is inside a tick).
 */
public final class InventoryClicks {
    private InventoryClicks() {}

    private static long tick = 0, openTick = Long.MIN_VALUE;

    /** Counts client ticks, for the open tick. */
    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> tick++);
    }

    /** Whether the player's inventory could be opened and clicked now: no screen up, no container open,
     *  nothing on the cursor, and not riding something whose inventory the server opens instead. */
    public static boolean available(Minecraft mc, LocalPlayer p) {
        return mc.screen == null && mc.gameMode != null && !mc.gameMode.isServerControlledInventory()
            && p.containerMenu == p.inventoryMenu && p.inventoryMenu.getCarried().isEmpty();
    }

    /** Make the next client tick the one a vanilla client opens its inventory in (VanillaActions plays it). */
    public static void requestOpen() { openTick = tick + 1; }

    /** Whether this client tick is a requested open tick; its end is when the clicks go. */
    public static boolean isOpenTick() { return tick == openTick; }

    /** At the start of the open tick, before the game's keybind step: what opening the screen does. */
    public static void playOpen() {
        KeyMapping.releaseAll();   // handleKeybinds then lets go of an item in use and stops a block break, as it would
    }

    /** The packet a vanilla client sends when it closes its inventory (LocalPlayer.closeContainer). */
    public static void close(Minecraft mc, LocalPlayer p) {
        if (mc.getConnection() != null) mc.getConnection().send(new ServerboundContainerClosePacket(p.inventoryMenu.containerId));
    }

    /** The close packet plus what closing a screen does on the client: the held keys read again (not on macOS),
     *  toggled keys restored, and no attack until the tick after next (MouseHandler.grabMouse, Minecraft.setScreen). */
    public static void closeAsVanilla(Minecraft mc, LocalPlayer p) {
        close(mc, p);
        if (InputQuirks.RESTORE_KEY_STATE_AFTER_MOUSE_GRAB) KeyMapping.setAll();
        KeyMapping.restoreToggleStatesOnScreenClosed();
        mc.missTime = 10000;
    }
}
