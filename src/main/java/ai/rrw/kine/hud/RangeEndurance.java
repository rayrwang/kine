package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.ElytraGuard;
import ai.rrw.kine.autoflight.FlightDirector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Estimates how long (endurance) and how far (range) you can elytra-fly on everything you're
 * carrying. Counts the worn elytra plus every spare in the inventory, offhand, and inside any
 * shulker boxes; applies each elytra's Unbreaking (which multiplies flight time per durability
 * point); and, if any elytra has Mending, adds the durability that bottles of enchanting can repair.
 * Reserves are then held back aviation-style: a 5% contingency plus a final reserve sized to glide
 * down safely from the current altitude (the durability failsafe's reserve), so the readout reaches
 * zero just as that failsafe would trigger. Range uses the active flight-director profile's mean
 * ground speed, so it changes with MAX SPEED / MAX CLIMB.
 *
 * All estimates: Unbreaking and XP-per-bottle are averages, so treat the numbers as ballpark.
 */
public class RangeEndurance {

    private static final double XP_PER_BOTTLE = 7.0; // experience bottle gives 3-11, ~7 average
    private static final double DUR_PER_XP = 2.0;     // Mending repairs 2 durability per XP point
    // Reserves, modelled on aviation practice: a 5% contingency on the trip, plus a "final reserve"
    // sized to glide down safely from the current altitude (the same reserve the durability failsafe
    // uses, so the readout reaches zero just as that failsafe would fire).
    private static final double CONTINGENCY = 0.05;

    private static final int AMBER = 0xFFFFC400;       // matches the radio altimeter

    private static double enduranceSec = 0;
    private static boolean show = false;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(RangeEndurance::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "range_endurance"),
            RangeEndurance::render);
    }

    private static final class Acc {
        double flightSeconds = 0;
        int    xpBottles = 0;
        boolean hasMending = false;
        int    mendUnbreaking = 0;   // best Unbreaking among Mending elytras
    }

    private static void tick(Minecraft mc) {
        show = false;
        if (!Settings.displayRangeEndurance) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;

        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA)) return;   // only while actually wearing an elytra

        Acc acc = new Acc();
        process(chest, acc);
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) process(s, acc);
        process(p.getItemBySlot(EquipmentSlot.OFFHAND), acc);

        if (acc.hasMending && acc.xpBottles > 0) {
            double repairDur = acc.xpBottles * XP_PER_BOTTLE * DUR_PER_XP;
            acc.flightSeconds += repairDur * (acc.mendUnbreaking + 1);
        }

        double reserve = ElytraGuard.landingReserveSeconds(Math.max(0, RadioAltimeter.agl()));
        enduranceSec = Math.max(0, acc.flightSeconds * (1.0 - CONTINGENCY) - reserve);
        show = true;
    }

    private static void process(ItemStack s, Acc acc) {
        if (s.isEmpty()) return;
        if (s.is(Items.ELYTRA) && s.isDamageableItem()) {
            int usable = Math.max(0, s.getMaxDamage() - 1 - s.getDamageValue());
            int unb = level(s, Enchantments.UNBREAKING);
            acc.flightSeconds += (double) usable * (unb + 1);   // Unbreaking ~ (L+1)x flight time
            if (level(s, Enchantments.MENDING) > 0) {
                acc.hasMending = true;
                acc.mendUnbreaking = Math.max(acc.mendUnbreaking, unb);
            }
        } else if (s.is(Items.EXPERIENCE_BOTTLE)) {
            acc.xpBottles += s.getCount();
        } else {
            ItemContainerContents box = s.get(DataComponents.CONTAINER);   // shulker boxes etc.
            if (box != null) box.nonEmptyItemCopyStream().forEach(inner -> process(inner, acc));
        }
    }

    private static int level(ItemStack s, ResourceKey<Enchantment> key) {
        ItemEnchantments ench = s.getEnchantments();
        for (Holder<Enchantment> h : ench.keySet()) {
            if (h.is(key)) return ench.getLevel(h);
        }
        return 0;
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!show) return;
        Minecraft mc = Minecraft.getInstance();
        double rangeBlocks = enduranceSec * FlightDirector.averageGroundSpeed();

        String end = "END " + fmtTime(enduranceSec);
        String rng = "RNG " + fmtDist(rangeBlocks);

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int cx = W / 2;
        int y = H - 60;                       // between the radio-altimeter and speed lines
        int half = 91;                        // ~half the hotbar width, out toward its edges

        g.text(mc.font, end, cx - half - mc.font.width(end) / 2, y, AMBER, true);
        g.text(mc.font, rng, cx + half - mc.font.width(rng) / 2, y, AMBER, true);
    }

    private static String fmtTime(double seconds) {
        int t = (int) Math.round(seconds);
        int m = t / 60, s = t % 60;
        return m > 0 ? String.format("%dm %02ds", m, s) : String.format("%ds", s);
    }

    private static String fmtDist(double blocks) {
        return blocks >= 1000 ? String.format("%.1f km", blocks / 1000.0)
                              : String.format("%.0f m", blocks);
    }
}
