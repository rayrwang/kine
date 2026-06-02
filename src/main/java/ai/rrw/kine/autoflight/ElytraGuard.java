package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3x2fStack;

public class ElytraGuard {

    // --- tuning (all deliberately conservative; healthy margins) ---
    private static final double DESCENT_RATE = 6.0;    // assumed safe descent rate (blocks/sec) when budgeting
    private static final double BASE_MARGIN  = 15.0;   // flat reserve (sec) for approach / mistakes
    private static final int    SCAN_CAP     = 384;    // how far down we scan for ground
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

        int remaining = chest.getMaxDamage() - chest.getDamageValue();   // ~seconds of flight left
        double agl = altitude(mc, p);
        warning = remaining < landingReserveSeconds(agl);

        // Two swap tiers, with a deliberate dead zone between them:
        //  - at the land-immediately threshold, only swap to a wing fresh enough to clear the warning
        //    (otherwise leave the warning up so the pilot actually lands)
        //  - once almost broken, swap to ANY fresher wing — the only priority left is not dying
        if (warning) {
            if (swapCooldown > 0) {
                swapCooldown--;
            } else {
                int minTarget = (remaining <= ALMOST_BREAK_DUR)
                    ? remaining + 1                                       // any strictly-fresher wing
                    : (int) Math.ceil(landingReserveSeconds(agl));        // must clear the warning
                if (tryAutoSwap(mc, p, minTarget)) {
                    swapCooldown = SWAP_COOLDOWN;
                    graceTicks = 0;
                    return;   // fresh elytra equipped; re-evaluate next tick
                }
            }
        }

        if (warning && Autopilot.isEngaged()) {
            graceTicks++;
            if (graceTicks >= GRACE_TICKS || remaining <= CRITICAL_DUR) bailOut(mc);
        } else {
            graceTicks = 0;   // pilot took control, or we're safe again
        }
    }

    /**
     * Hot-swap to the best spare elytra in the inventory/offhand, but only if that best wing has at
     * least {@code minTargetRemaining} durability left. The caller sets the bar: high enough to clear
     * the land-immediately warning in normal use, or just one-better when almost broken. Swaps via
     * three synchronous container clicks (pick up spare, swap into chest, drop old into the vacated
     * slot). Only runs with no container screen open and an empty cursor, so it can't strand an item.
     */
    private static boolean tryAutoSwap(Minecraft mc, LocalPlayer p, int minTargetRemaining) {
        MultiPlayerGameMode gm = mc.gameMode;
        if (gm == null) return false;
        AbstractContainerMenu menu = p.containerMenu;
        if (menu != p.inventoryMenu) return false;        // a chest/other screen is open — don't touch
        if (!menu.getCarried().isEmpty()) return false;   // something on the cursor — bail

        int bestSlot = -1, bestRemaining = -1;
        for (int i = 9; i < menu.slots.size(); i++) {          // 9..45: main inventory, hotbar, offhand
            if (i == CHEST_SLOT) continue;
            ItemStack st = menu.slots.get(i).getItem();
            if (st.is(Items.ELYTRA) && st.isDamageableItem()) {
                int rem = st.getMaxDamage() - st.getDamageValue();
                if (rem > bestRemaining) { bestRemaining = rem; bestSlot = i; }
            }
        }
        if (bestSlot < 0 || bestRemaining < minTargetRemaining) return false;  // nothing good enough

        int cid = menu.containerId;
        gm.handleContainerInput(cid, bestSlot,   0, ContainerInput.PICKUP, p);  // cursor <- spare
        gm.handleContainerInput(cid, CHEST_SLOT, 0, ContainerInput.PICKUP, p);  // chest <-> cursor (cursor now old)
        gm.handleContainerInput(cid, bestSlot,   0, ContainerInput.PICKUP, p);  // old -> vacated slot
        Kine.LOGGER.info("kine: auto-swapped to a fresher elytra ({} durability)", bestRemaining);
        return true;
    }

    // durability (≈seconds) we want in reserve to glide down from this altitude, with a margin that
    // thickens as you go higher.
    public static double landingReserveSeconds(double agl) {
        double descentSeconds = agl / DESCENT_RATE;
        double altitudeFactor = 1.5 + agl / 1000.0;
        return descentSeconds * altitudeFactor + BASE_MARGIN;
    }

    // straight-down distance to the first solid block (blocks above ground)
    private static double altitude(Minecraft mc, LocalPlayer p) {
        int x = p.getBlockX(), z = p.getBlockZ();
        int start = (int) Math.floor(p.getY()) - 1;
        int limit = Math.max(mc.level.getMinY(), start - SCAN_CAP);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = start; y >= limit; y--) {
            pos.set(x, y, z);
            if (!mc.level.getBlockState(pos).isAir()) return Math.max(0, p.getY() - (y + 1));
        }
        return p.getY() - limit;   // no ground within scan (e.g. over the void) -> treat as very high
    }

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
            drawCentered(g, font, "autopilot disconnect in " + secs + "s \u2014 take control",
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
