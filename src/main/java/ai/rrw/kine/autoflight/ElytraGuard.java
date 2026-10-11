package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudDraw;
import ai.rrw.kine.hud.HudLayout;
import ai.rrw.kine.hud.RadioAltimeter;
import ai.rrw.kine.util.Disconnect;
import ai.rrw.kine.util.ElytraLife;
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
    private static final int    GRACE_TICKS  = 100;    // 5 s for the pilot to take control before the failsafe fires
    private static final int    ALMOST_BREAK_DUR = 3;  // at or under this, swap to any wing with more life
    private static final int    CRITICAL_DUR = 2;      // disconnect at once at or under this durability (about to break)
    private static final int    CHEST_SLOT   = 6;      // chestplate slot index in the player inventory menu
    private static final int    SWAP_COOLDOWN = 40;    // ticks between successful auto-swaps

    private static boolean warning = false;   // read by the HUD
    private static int graceTicks = 0;
    private static int swapCooldown = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ElytraGuard::tick);
        HudElementRegistry.attachElementAfter(
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "flightdir"), // draw on top of the flight director bars
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "elytra_guard"),
            ElytraGuard::render);
    }

    private static void tick(Minecraft mc) {
        warning = false;
        if (!Settings.elytraDuraFailsafe) { graceTicks = 0; return; }

        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isFallFlying()) { graceTicks = 0; swapCooldown = 0; return; }

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
                if (tryAutoSwap(mc, p, minTargetSeconds)) {
                    swapCooldown = SWAP_COOLDOWN;
                    graceTicks = 0;
                    return;   // fresh elytra equipped; re-evaluate next tick
                }
            }
        }

        if (warning && Autopilot.isEngaged()) {
            graceTicks++;
            // CRITICAL_DUR counts raw points, since the wing breaks at the same point whatever its Unbreaking
            if (graceTicks >= GRACE_TICKS || remaining <= CRITICAL_DUR) bailOut(mc);
        } else {
            graceTicks = 0;   // pilot took control, or we're safe again
        }
    }

    /**
     * Hot-swap to the spare elytra with the most flight time left (ElytraLife.flightSeconds), but only if
     * that best wing clears {@code minTargetSeconds} of flight. The caller sets the bar: enough to clear
     * the land-immediately warning in normal use, or just longer-lived than the current wing when almost
     * broken. Swaps with three container clicks (pick up the spare, swap it into the chest slot, put the old
     * wing in the spare's slot), only with no container open and an empty cursor, so no item is stranded.
     */
    private static boolean tryAutoSwap(Minecraft mc, LocalPlayer p, double minTargetSeconds) {
        MultiPlayerGameMode gm = mc.gameMode;
        if (gm == null) return false;
        AbstractContainerMenu menu = p.containerMenu;
        if (menu != p.inventoryMenu) return false;        // a chest or other container is open
        if (!menu.getCarried().isEmpty()) return false;   // something is on the cursor

        int bestSlot = -1;
        double bestSeconds = -1.0;
        for (int i = 9; i < menu.slots.size(); i++) {          // 9 to 45: main inventory, hotbar, offhand
            ItemStack st = menu.slots.get(i).getItem();
            if (st.is(Items.ELYTRA) && st.isDamageableItem()) {
                double secs = ElytraLife.flightSeconds(st);
                if (secs > bestSeconds) { bestSeconds = secs; bestSlot = i; }
            }
        }
        if (bestSlot < 0 || bestSeconds < minTargetSeconds) return false;  // nothing with enough flight time left

        int cid = menu.containerId;
        gm.handleContainerInput(cid, bestSlot,   0, ContainerInput.PICKUP, p);  // cursor <- spare
        gm.handleContainerInput(cid, CHEST_SLOT, 0, ContainerInput.PICKUP, p);  // chest <-> cursor (the cursor holds the worn wing)
        gm.handleContainerInput(cid, bestSlot,   0, ContainerInput.PICKUP, p);  // worn wing -> the emptied slot
        Kine.LOGGER.info("Kine: auto-swapped to a fresher elytra ({} s of flight)", (int) bestSeconds);
        return true;
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

        if (Autopilot.isEngaged()) {
            int secs = Math.max(0, (GRACE_TICKS - graceTicks + 19) / 20);
            HudDraw.scaledText(g, font, "autopilot disconnect in " + KineTime.format(secs) + ", take control",
                W / 2, cy + 2 * big + 14, HudColors.WHITE, 1.0f, false);
        }
    }

}
