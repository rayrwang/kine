package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.combat.AfkGuard;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudDraw;
import ai.rrw.kine.hud.HudLayout;
import ai.rrw.kine.hud.RangeEndurance;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The elytra durability failsafe (one switch, Safety section). While gliding, by hand or on the autopilot:
 * <ul>
 *   <li>when the worn elytra is down to the floor (3 points left, {@link Durability#FLOOR}), it swaps in the freshest
 *       spare, the one with the most usable points; after a swap onto the last usable one, LAST ELYTRA shows for 5 s;</li>
 *   <li>when END reaches 0 ({@link RangeEndurance#endurance}), only the landing reserve is left: on the autopilot, Nav
 *       lands near here ({@link #landNow}) under a red warning; flying by hand, a red warning, and with no input for
 *       5 s (input as {@link AfkGuard} counts it) a disconnect, the only durability disconnect.</li>
 * </ul>
 */
public class ElytraGuard {

    private static final int CHEST_SLOT      = 6;     // chestplate slot index in the player inventory menu
    private static final int SWAP_COOLDOWN   = 40;    // ticks between swap attempts
    private static final int LAST_WING_TICKS = 100;   // LAST ELYTRA shows this long after the swap onto the last wing
    static final int         NO_INPUT_TICKS  = 100;   // flying by hand at END 0: disconnect after 5 s with no input (RW)

    private enum Warn { NONE, LANDING, MANUAL }

    private static Warn warn = Warn.NONE;     // read by the HUD
    private static int warnTicks = 0;         // ticks the manual warning has been up
    private static int lastWingTicks = 0;     // LAST ELYTRA's time left
    private static int swapCooldown = 0;
    private static boolean swapRequested = false;   // the next tick is the inventory's opening for a swap

    /** END has reached 0 with the failsafe on: the landing must start now. Nav reads it to land on the autopilot and
     *  to end a hold. */
    public static boolean landNow() {
        return Settings.elytraDuraFailsafe && RangeEndurance.endurance() <= 0;
    }

    /** LAST ELYTRA is showing (a swap onto the last usable wing within the last 5 s). */
    public static boolean lastElytra() { return lastWingTicks > 0; }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ElytraGuard::tick);
        HudElementRegistry.attachElementAfter(
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "flightdir"), // draw on top of the flight director bars
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "elytra_guard"),
            ElytraGuard::render);
    }

    private static void tick(Minecraft mc) {
        if (mc.isPaused()) return;   // singleplayer paused: the elytra wears no further, so the countdowns wait too
        if (lastWingTicks > 0) lastWingTicks--;
        // A swap was asked for last tick, so this is the tick the inventory "opened" in: its end is when the clicks go.
        if (swapRequested) {
            swapRequested = false;
            if (InventoryClicks.isOpenTick() && finishSwap(mc)) return;   // fresh elytra on; re-evaluate next tick
        }
        if (!Settings.elytraDuraFailsafe) { calm(); swapCooldown = 0; lastWingTicks = 0; return; }
        LocalPlayer p = mc.player;
        // not gliding, or dead (a body keeps gliding until it lands): nothing to guard
        if (p == null || mc.level == null || !p.isFallFlying() || p.isDeadOrDying()) { calm(); swapCooldown = 0; return; }
        Durability.Wing worn = ElytraLife.wing(p.getItemBySlot(EquipmentSlot.CHEST));
        if (worn == null) { calm(); return; }

        // the worn wing at its floor: swap in the freshest spare. The next tick plays the inventory's opening, and the
        // swap goes at its end.
        if (swapCooldown > 0) swapCooldown--;
        else if (worn.spent() && mc.gameMode != null && InventoryClicks.available(mc, p) && !InventoryClicks.isOpenTick()
                && canTakeOff(p) && freshestSlot(p.inventoryMenu) >= 0) {
            InventoryClicks.requestOpen();
            swapRequested = true;
        }

        // END 0. In water (a landing on water: the glide goes on there, LivingEntity.canGlide doesn't check for it)
        // there is nothing to warn of.
        if (!(RangeEndurance.endurance() <= 0) || p.isInWater()) { calm(); return; }
        // on the autopilot, landing or about to (Nav waits for terrain below to land on)
        if (Autopilot.isEngaged() && (Nav.landing() || Settings.navigation)) { warn = Warn.LANDING; warnTicks = 0; return; }
        // Flying by hand (or on an autopilot that can't land: navigation switched off in the settings)
        warn = Warn.MANUAL;
        warnTicks++;
        if (idle() >= NO_INPUT_TICKS) disconnect(mc);
    }

    private static void calm() { warn = Warn.NONE; warnTicks = 0; }

    // Ticks without input while the manual warning has been up. AfkGuard counts at the end of each tick after this
    // one, so its count is last tick's.
    private static int idle() { return Math.min(warnTicks, AfkGuard.idleTicks()); }

    /**
     * Swap the freshest spare in with three container clicks (pick up the spare, swap it into the chest slot, put the
     * spent wing in the spare's slot), only with no screen or container open and an empty cursor, so no item is
     * stranded. As a vanilla client does it: the tick before plays the inventory's opening (every key up, no action),
     * the clicks go at that tick's end, between ticks, and the close does what closing the inventory does. The close
     * goes even when the swap is no longer wanted, since the inventory was "opened". Returns whether the spare is now
     * worn (the clicks predict the server's result).
     */
    private static boolean finishSwap(Minecraft mc) {
        LocalPlayer p = mc.player;
        MultiPlayerGameMode gm = mc.gameMode;
        if (p == null || gm == null || !InventoryClicks.available(mc, p)) return false;   // a screen or container is open, or the cursor is busy
        if (!InventoryClicks.claimBatch()) return false;   // another feature clicked in this opening (and closed it)
        AbstractContainerMenu menu = p.inventoryMenu;
        boolean swapped = false;
        Durability.Wing worn = ElytraLife.wing(p.getItemBySlot(EquipmentSlot.CHEST));
        int slot = (p.isFallFlying() && worn != null && worn.spent() && canTakeOff(p)) ? freshestSlot(menu) : -1;
        if (slot >= 0) {
            ItemStack spare = menu.slots.get(slot).getItem().copy();
            int cid = menu.containerId;
            gm.handleContainerInput(cid, slot,       0, ContainerInput.PICKUP, p);  // cursor <- spare
            gm.handleContainerInput(cid, CHEST_SLOT, 0, ContainerInput.PICKUP, p);  // chest <-> cursor (the cursor holds the worn wing)
            gm.handleContainerInput(cid, slot,       0, ContainerInput.PICKUP, p);  // worn wing -> the emptied slot
            swapCooldown = SWAP_COOLDOWN;   // taken or not, no retry at once
            swapped = ItemStack.matches(p.getItemBySlot(EquipmentSlot.CHEST), spare);
            if (swapped) {
                boolean last = freshestSlot(menu) < 0;
                if (last) lastWingTicks = LAST_WING_TICKS;   // never with one wing only: that takes no swap (RW)
                Kine.LOGGER.info("Kine: swapped in a fresh elytra ({} durability left{})",
                    spare.getMaxDamage() - spare.getDamageValue(), last ? ", the last one" : "");
            } else {
                Kine.LOGGER.warn("Kine: elytra swap did not take");
            }
        }
        InventoryClicks.closeAsVanilla(mc, p);
        return swapped;
    }

    // Whether the worn wing can come off: not with Curse of Binding outside creative (ArmorSlot.mayPickup), where
    // the swap's clicks would do nothing.
    private static boolean canTakeOff(LocalPlayer p) {
        return p.inventoryMenu.slots.get(CHEST_SLOT).mayPickup(p);
    }

    // The inventory menu slot of the freshest usable spare (Durability.freshest), or -1.
    private static int freshestSlot(AbstractContainerMenu menu) {
        List<Integer> slots = new ArrayList<>();
        int i = Durability.freshest(ElytraLife.spares(menu, slots));
        return i < 0 ? -1 : slots.get(i);
    }

    private static void disconnect(Minecraft mc) {
        warnTicks = 0;
        Kine.LOGGER.warn("Kine: elytra durability failsafe, disconnecting (worn out in flight by hand, no input for {} s)",
            NO_INPUT_TICKS / 20);
        Disconnect.withMessage(mc, Component.literal("Kine: elytra durability failsafe\n\nYour elytra were down to the "
            + "landing reserve while you flew by hand, and there was no input for " + NO_INPUT_TICKS / 20 + " seconds."));
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        if (lastWingTicks > 0 && Settings.elytraDuraFailsafe) {
            String s = "LAST ELYTRA";
            g.text(font, s, W / 2 - font.width(s) / 2, HudLayout.noticeRow(H, font.lineHeight), HudColors.AMBER, true);
        }
        if (warn == Warn.NONE) return;
        int cy = HudLayout.bigWarningTop(H);
        int big = (int) (font.lineHeight * 2.0f);
        HudDraw.scaledText(g, font, "LOW DURABILITY", W / 2, cy, HudColors.RED, 2.0f, false);
        if (warn == Warn.LANDING) {
            HudDraw.scaledText(g, font, "LANDING", W / 2, cy + big + 4, HudColors.RED, 2.0f, false);
            return;
        }
        HudDraw.scaledText(g, font, "LAND NOW", W / 2, cy + big + 4, HudColors.RED, 2.0f, false);
        int secs = Math.max(0, (NO_INPUT_TICKS - idle() + 19) / 20);
        HudDraw.scaledText(g, font, "disconnect in " + KineTime.format(secs) + " without input",
            W / 2, cy + 2 * big + 14, HudColors.WHITE, 1.0f, false);
    }
}
