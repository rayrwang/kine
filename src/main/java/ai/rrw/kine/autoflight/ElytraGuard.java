package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudDraw;
import ai.rrw.kine.hud.HudLayout;
import ai.rrw.kine.hud.RadioAltimeter;
import ai.rrw.kine.util.Disconnect;
import ai.rrw.kine.util.ElytraLife;
import ai.rrw.kine.util.InventoryClicks;
import ai.rrw.kine.util.KineTime;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class ElytraGuard {

    // tuning, with generous margins
    private static final double DESCENT_RATE = 6.0;    // assumed safe descent rate (blocks/sec) when budgeting
    private static final double BASE_MARGIN  = 15.0;   // flat reserve (sec) for approach / mistakes
    static final int            GRACE_TICKS  = 100;    // 5 s for the pilot to take control before the failsafe fires
    private static final int    ALMOST_BREAK_DUR = 3;  // at or under this, swap to any wing with more life
    private static final int    CRITICAL_DUR = 2;      // disconnect at once at or under this durability (about to break)
    private static final int    CHEST_SLOT   = 6;      // chestplate slot index in the player inventory menu
    private static final int    SWAP_COOLDOWN = 40;    // ticks between auto-swap attempts

    private static boolean warning = false;   // read by the HUD
    private static int graceTicks = 0;
    private static int swapCooldown = 0;
    private static boolean swapRequested = false;   // the next tick is the inventory's opening for a swap
    private static double  swapMinSeconds;          // the flight time the swap's wing must clear (from the request)

    /** The land-now warning is up: the worn elytra's flight time is below the reserve for gliding down. */
    public static boolean warning() { return warning; }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ElytraGuard::tick);
        HudElementRegistry.attachElementAfter(
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "flightdir"), // draw on top of the flight director bars
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "elytra_guard"),
            ElytraGuard::render);
    }

    private static void tick(Minecraft mc) {
        if (mc.isPaused()) return;   // singleplayer paused: the elytra wears no further, so the grace waits too
        // A swap was asked for last tick, so this is the tick the inventory "opened" in: its end is when the clicks go.
        if (swapRequested) {
            swapRequested = false;
            if (InventoryClicks.isOpenTick() && finishSwap(mc)) {
                graceTicks = 0;
                return;   // fresh elytra equipped; re-evaluate next tick
            }
        }
        warning = false;
        if (!Settings.elytraDuraFailsafe) { graceTicks = 0; return; }

        LocalPlayer p = mc.player;
        // not gliding, or dead (a body keeps gliding until it lands): nothing to guard
        if (p == null || mc.level == null || !p.isFallFlying() || p.isDeadOrDying()) { graceTicks = 0; swapCooldown = 0; return; }

        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || !chest.isDamageableItem()) { graceTicks = 0; return; }

        int remaining = chest.getMaxDamage() - chest.getDamageValue();   // raw durability points left
        double secondsLeft = ElytraLife.flightSeconds(chest);
        // Height above ground from the radio altimeter. Over the void it returns -1; the failsafe then uses
        // the full height above the world floor, so it warns and disconnects early over a bottomless drop.
        int rawAgl = RadioAltimeter.agl();
        double agl = (rawAgl >= 0) ? rawAgl : Math.max(0, p.getY() - mc.level.getMinY());
        double reserve = landingReserveSeconds(agl);
        warning = secondsLeft < reserve;

        // Two swap tiers with a gap between them:
        //  - at the warning, only swap to a wing that clears it (otherwise keep the warning, so the pilot lands)
        //  - once almost broken, swap to any wing with more flight time
        if (warning) {
            if (swapCooldown > 0) {
                swapCooldown--;
            } else {
                double minTargetSeconds = (remaining <= ALMOST_BREAK_DUR)
                    ? secondsLeft + 0.001                                 // any wing with strictly more flight time
                    : reserve;                                            // must clear the warning
                // a wing worth swapping to: the next tick plays the inventory's opening, and the swap goes at its end
                if (mc.gameMode != null && InventoryClicks.available(mc, p) && !InventoryClicks.isOpenTick()
                        && canTakeOff(p) && bestSpare(p.inventoryMenu, minTargetSeconds) >= 0) {
                    InventoryClicks.requestOpen();
                    swapRequested = true; swapMinSeconds = minTargetSeconds;
                    return;   // the grace waits for the swap, as it did when the swap went at once
                }
            }
        }

        graceTicks = graceAfter(graceTicks, warning, Autopilot.isEngaged(), Nav.landing());
        // CRITICAL_DUR counts raw points, since the wing breaks at the same point whatever its Unbreaking. It
        // still fires during the autopilot's own landing.
        if (warning && Autopilot.isEngaged() && (graceTicks >= GRACE_TICKS || remaining <= CRITICAL_DUR)) bailOut(mc);
    }

    /** The grace count after a tick: it counts while the warning is up and the autopilot flies, and starts over
     *  once the pilot takes control or the warning clears. During the autopilot's own landing it holds: the
     *  autopilot is doing what the warning asks. */
    static int graceAfter(int graceTicks, boolean warning, boolean engaged, boolean landing) {
        if (!warning || !engaged) return 0;   // pilot took control, or we're safe again
        return landing ? graceTicks : graceTicks + 1;
    }

    /**
     * Hot-swap to the spare elytra with the most flight time left (ElytraLife.flightSeconds), but only if
     * that best wing clears {@code swapMinSeconds} of flight. The request sets the bar: enough to clear
     * the land-immediately warning in normal use, or just longer-lived than the current wing when almost
     * broken. Swaps with three container clicks (pick up the spare, swap it into the chest slot, put the old
     * wing in the spare's slot), only with no screen or container open and an empty cursor, so no item is
     * stranded. As a vanilla client does it: the tick before plays the inventory's opening (every key up, no
     * action), the clicks go at that tick's end, between ticks, and the close does what closing the inventory
     * does. The close goes even when the swap is no longer wanted, since the inventory was "opened". Returns
     * whether the spare is now worn: the clicks predict the server's result, and a swap that did nothing must not
     * reset the failsafe's grace.
     */
    private static boolean finishSwap(Minecraft mc) {
        LocalPlayer p = mc.player;
        MultiPlayerGameMode gm = mc.gameMode;
        if (p == null || gm == null || !InventoryClicks.available(mc, p)) return false;   // a screen or container is open, or the cursor is busy
        if (!InventoryClicks.claimBatch()) return false;   // another feature clicked in this opening (and closed it)
        AbstractContainerMenu menu = p.inventoryMenu;
        boolean swapped = false;
        int bestSlot = (p.isFallFlying() && canTakeOff(p)) ? bestSpare(menu, swapMinSeconds) : -1;
        if (bestSlot >= 0) {
            ItemStack spare = menu.slots.get(bestSlot).getItem().copy();
            int cid = menu.containerId;
            gm.handleContainerInput(cid, bestSlot,   0, ContainerInput.PICKUP, p);  // cursor <- spare
            gm.handleContainerInput(cid, CHEST_SLOT, 0, ContainerInput.PICKUP, p);  // chest <-> cursor (the cursor holds the worn wing)
            gm.handleContainerInput(cid, bestSlot,   0, ContainerInput.PICKUP, p);  // worn wing -> the emptied slot
            swapCooldown = SWAP_COOLDOWN;   // taken or not, no retry at once
            swapped = ItemStack.matches(p.getItemBySlot(EquipmentSlot.CHEST), spare);
            if (swapped) Kine.LOGGER.info("Kine: auto-swapped to a fresher elytra ({} s of flight)", (int) ElytraLife.flightSeconds(spare));
            else Kine.LOGGER.warn("Kine: elytra auto-swap did not take");
        }
        InventoryClicks.closeAsVanilla(mc, p);
        return swapped;
    }

    // Whether the worn wing can come off: not with Curse of Binding outside creative (ArmorSlot.mayPickup), where
    // the swap's clicks would do nothing.
    private static boolean canTakeOff(LocalPlayer p) {
        return p.inventoryMenu.slots.get(CHEST_SLOT).mayPickup(p);
    }

    // The inventory menu slot of the spare elytra with the most flight time left, if it clears minSeconds; else -1.
    private static int bestSpare(AbstractContainerMenu menu, double minSeconds) {
        int bestSlot = -1;
        double bestSeconds = -1.0;
        for (int i = 9; i < menu.slots.size(); i++) {          // 9 to 45: main inventory, hotbar, offhand
            ItemStack st = menu.slots.get(i).getItem();
            if (st.is(Items.ELYTRA) && st.isDamageableItem()) {
                double secs = ElytraLife.flightSeconds(st);
                if (secs > bestSeconds) { bestSeconds = secs; bestSlot = i; }
            }
        }
        return (bestSlot >= 0 && bestSeconds >= minSeconds) ? bestSlot : -1;   // nothing with enough flight time left
    }


    /** Durability (about one second per point) to keep in reserve for gliding down from this height,
     *  with a margin that grows with height. */
    public static double landingReserveSeconds(double agl) {
        double descentSeconds = agl / DESCENT_RATE;
        double altitudeFactor = 1.5 + agl / 1000.0;
        return descentSeconds * altitudeFactor + BASE_MARGIN;
    }

    private static void bailOut(Minecraft mc) {
        graceTicks = 0;
        Autopilot.disengage();
        Kine.LOGGER.warn("Kine: elytra durability failsafe, disconnecting to avoid a fall death");
        Disconnect.withMessage(mc, Component.literal("Kine: low elytra durability failsafe"));
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!warning) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        int color = HudColors.warningFlash();
        int cy = HudLayout.bigWarningTop(H);
        int big = (int) (font.lineHeight * 2.0f);

        HudDraw.scaledText(g, font, "LOW DURABILITY",   W / 2, cy,            color, 2.0f, false);
        HudDraw.scaledText(g, font, "LAND IMMEDIATELY", W / 2, cy + big + 4,  color, 2.0f, false);

        if (Autopilot.isEngaged() && !Nav.landing()) {   // during its own landing the grace holds: no countdown
            int secs = Math.max(0, (GRACE_TICKS - graceTicks + 19) / 20);
            HudDraw.scaledText(g, font, "autopilot disconnect in " + KineTime.format(secs) + ", take control",
                W / 2, cy + 2 * big + 14, HudColors.WHITE, 1.0f, false);
        }
    }

}
