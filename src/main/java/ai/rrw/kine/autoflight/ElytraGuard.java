package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.util.KineTime;
import ai.rrw.kine.Settings;
import ai.rrw.kine.hud.RadioAltimeter;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.joml.Matrix3x2fStack;

public class ElytraGuard {

    // --- tuning (all deliberately conservative; healthy margins) ---
    private static final double DESCENT_RATE = 6.0;    // assumed safe descent rate (blocks/sec) when budgeting
    private static final double BASE_MARGIN  = 15.0;   // flat reserve (sec) for approach / mistakes
    private static final int    GRACE_TICKS  = 100;    // 5 s for the pilot to take control before the failsafe fires
    private static final int    ALMOST_BREAK_DUR = 3;  // at/under this, swap to ANY fresher wing — just don't die
    private static final int    CRITICAL_DUR = 2;      // bail instantly at/under this durability (about to break)
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
        int unbreaking = level(chest, Enchantments.UNBREAKING);
        double secondsLeft = remaining * (unbreaking + 1.0);   // Unbreaking ~ (L+1)x flight time per point (elytra follows the tool curve, not the weaker armor one)
        // Altitude above the nearest ground, from the shared radio-altimeter scan. Over the void it
        // returns -1 (no ground in the column); here the failsafe's policy is to treat that as
        // conservatively high — the full height above the world floor — so it warns and bails early
        // rather than letting the wing break over a bottomless drop.
        int rawAgl = RadioAltimeter.agl();
        double agl = (rawAgl >= 0) ? rawAgl : Math.max(0, p.getY() - mc.level.getMinY());
        double reserve = landingReserveSeconds(agl);
        warning = secondsLeft < reserve;

        // Two swap tiers, with a deliberate dead zone between them:
        //  - at the land-immediately threshold, only swap to a wing fresh enough to clear the warning
        //    (otherwise leave the warning up so the pilot actually lands)
        //  - once almost broken, swap to ANY wing with more flight time — the only priority left is not dying
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
            // CRITICAL_DUR stays a raw-point "physically about to break" check — correct regardless of Unbreaking
            if (graceTicks >= GRACE_TICKS || remaining <= CRITICAL_DUR) bailOut(mc);
        } else {
            graceTicks = 0;   // pilot took control, or we're safe again
        }
    }

    /**
     * Hot-swap to the spare elytra with the most flight time left (durability x Unbreaking), but only if
     * that best wing clears {@code minTargetSeconds} of flight. The caller sets the bar: enough to clear
     * the land-immediately warning in normal use, or just longer-lived than the current wing when almost
     * broken. Swaps via three synchronous container clicks (pick up spare, swap into chest, drop old into
     * the vacated slot). Only runs with no container screen open and an empty cursor, so it can't strand an item.
     */
    private static boolean tryAutoSwap(Minecraft mc, LocalPlayer p, double minTargetSeconds) {
        MultiPlayerGameMode gm = mc.gameMode;
        if (gm == null) return false;
        AbstractContainerMenu menu = p.containerMenu;
        if (menu != p.inventoryMenu) return false;        // a chest/other screen is open — don't touch
        if (!menu.getCarried().isEmpty()) return false;   // something on the cursor — bail

        int bestSlot = -1;
        double bestSeconds = -1.0;
        for (int i = 9; i < menu.slots.size(); i++) {          // 9..45: main inventory, hotbar, offhand
            if (i == CHEST_SLOT) continue;
            ItemStack st = menu.slots.get(i).getItem();
            if (st.is(Items.ELYTRA) && st.isDamageableItem()) {
                double secs = (st.getMaxDamage() - st.getDamageValue()) * (level(st, Enchantments.UNBREAKING) + 1.0);
                if (secs > bestSeconds) { bestSeconds = secs; bestSlot = i; }
            }
        }
        if (bestSlot < 0 || bestSeconds < minTargetSeconds) return false;  // nothing with enough flight time left

        int cid = menu.containerId;
        gm.handleContainerInput(cid, bestSlot,   0, ContainerInput.PICKUP, p);  // cursor <- spare
        gm.handleContainerInput(cid, CHEST_SLOT, 0, ContainerInput.PICKUP, p);  // chest <-> cursor (cursor now old)
        gm.handleContainerInput(cid, bestSlot,   0, ContainerInput.PICKUP, p);  // old -> vacated slot
        Kine.LOGGER.info("kine: auto-swapped to a fresher elytra (~{}s of flight)", (int) bestSeconds);
        return true;
    }

    private static int level(ItemStack s, ResourceKey<Enchantment> key) {
        ItemEnchantments ench = s.getEnchantments();
        for (Holder<Enchantment> h : ench.keySet()) {
            if (h.is(key)) return ench.getLevel(h);
        }
        return 0;
    }

    // durability (≈seconds) we want in reserve to glide down from this altitude, with a margin that
    // thickens as you go higher.
    public static double landingReserveSeconds(double agl) {
        double descentSeconds = agl / DESCENT_RATE;
        double altitudeFactor = 1.5 + agl / 1000.0;
        return descentSeconds * altitudeFactor + BASE_MARGIN;
    }

    // straight-down distance to the first solid block (blocks above ground)
    private static void bailOut(Minecraft mc) {
        graceTicks = 0;
        Autopilot.disengage();
        Kine.LOGGER.warn("kine: elytra durability failsafe — disconnecting to avoid a fall death");
        ClientPacketListener cpl = mc.getConnection();
        if (cpl != null) {
            cpl.getConnection().disconnect(Component.literal("Kine: low elytra durability failsafe"));
        }
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!warning) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        boolean flash = (System.currentTimeMillis() / 400) % 2 == 0;
        int color = flash ? 0xFFFF2020 : 0xFFFFD000;
        int cy = H / 3;
        int big = (int) (font.lineHeight * 2.0f);

        drawCentered(g, font, "LOW DURABILITY",   W / 2, cy,            color, 2.0f);
        drawCentered(g, font, "LAND IMMEDIATELY", W / 2, cy + big + 4,  color, 2.0f);

        if (Autopilot.isEngaged()) {
            int secs = Math.max(0, (GRACE_TICKS - graceTicks + 19) / 20);
            drawCentered(g, font, "autopilot disconnect in " + KineTime.format(secs) + " \u2014 take control",
                W / 2, cy + 2 * big + 14, 0xFFFFFFFF, 1.0f);
        }
    }

    private static void drawCentered(GuiGraphicsExtractor g, Font font, String text,
                                     int cx, int cy, int color, float scale) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        pose.scale(scale, scale);
        g.text(font, text, -font.width(text) / 2, 0, color, true);
        pose.popMatrix();
    }
}
