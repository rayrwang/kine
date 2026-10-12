package ai.rrw.kine.combat;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.util.InventoryClicks;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;

/**
 * Keeps a totem of undying in the offhand. When the offhand holds none (a totem just saved you, or none was there)
 * and one is carried, the inventory "opens" as a vanilla client opens it (InventoryClicks: a tick with every key
 * released), and at that tick's end the totem's slot is swapped with the offhand: the click a player makes by
 * hovering the totem and pressing the swap-offhand key (SWAP with button 40, the offhand's inventory index). Whatever
 * was in the offhand goes where the totem was. Survival and adventure only: creative and spectator can't die.
 */
public final class AutoTotem {
    private AutoTotem() {}

    private static final int OFFHAND = 40;       // the offhand's index in the player's inventory (SWAP's button)
    private static final int RETRY_TICKS = 10;   // after a swap, wait this long before trying again (lag, a refused click)

    private static boolean pending = false;      // an opening asked for, to swap at its end
    private static int wait = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoTotem::tick);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || !Settings.autoTotem) { pending = false; return; }
        if (pending) {
            if (InventoryClicks.isOpenTick()) { pending = false; swapIn(mc, p); }
            return;
        }
        if (wait > 0) { wait--; return; }
        if (!needsTotem(p) || totemSlot(p.inventoryMenu) < 0) return;
        if (mc.gameMode != null && InventoryClicks.available(mc, p) && !InventoryClicks.isOpenTick()) {
            InventoryClicks.requestOpen();
            pending = true;
        }
    }

    private static boolean needsTotem(LocalPlayer p) {
        return p.isAlive() && !p.getAbilities().instabuild && !p.isSpectator()
            && !p.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
    }

    // inventory menu slots 9 to 35 (main inventory) and 36 to 44 (hotbar)
    private static int totemSlot(AbstractContainerMenu menu) {
        for (int i = 9; i <= 44; i++) if (menu.slots.get(i).getItem().is(Items.TOTEM_OF_UNDYING)) return i;
        return -1;
    }

    private static void swapIn(Minecraft mc, LocalPlayer p) {
        AbstractContainerMenu menu = p.inventoryMenu;
        if (mc.gameMode == null || !InventoryClicks.available(mc, p) || !needsTotem(p)) return;
        int src = totemSlot(menu);
        if (src < 0 || !InventoryClicks.claimBatch()) return;   // none left, or another feature clicked in this opening
        mc.gameMode.handleContainerInput(menu.containerId, src, OFFHAND, ContainerInput.SWAP, p);
        InventoryClicks.closeAsVanilla(mc, p);
        wait = RETRY_TICKS;
        Kine.LOGGER.info("Kine auto totem: a totem into the offhand from slot {}", src);
    }
}
