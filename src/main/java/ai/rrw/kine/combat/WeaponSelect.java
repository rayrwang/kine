package ai.rrw.kine.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Auto weapon selection for the kill aura. Scores every sword and axe in the hotbar and main inventory by
 * its expected damage against the current target and equips the best one, pulling it out of the main
 * inventory into the hotbar if that's where it lives.
 *
 * <p>The score is full-charge DPS: per-hit damage times attack speed, which is exactly the aura's
 * charge-limited rate. Per-hit damage is the weapon's own attack-damage modifier (plus the player's base 1)
 * with the enchantment bonuses added from the vanilla formulas -- Sharpness ({@code 0.5*lvl + 0.5}) always,
 * and Smite / Bane of Arthropods ({@code 2.5*lvl}) only when the target's type tag says it's susceptible, so
 * a Smite sword is preferred against skeletons but not against spiders. Attack speed comes from the weapon's
 * own modifier, so a fast sword is correctly rated above a slow axe of equal per-hit damage. Against a crowd
 * a sword also earns a sweep term (bigger with Sweeping Edge). A switch only happens when the best option
 * beats what's held by a margin, so it won't flip-flop between near-equal weapons.
 *
 * <p>The cost: pulling from the main inventory swaps the weapon into a hotbar slot via a container click,
 * which reorganises the hotbar (an empty slot first, else the largest stack as the least-valuable item -- the same heuristic the water-bucket clutch uses).
 */
public final class WeaponSelect {
    private WeaponSelect() {}

    private static final double SWITCH_MARGIN = 1.10;   // switch only if the best scores >=10% over what's held

    /**
     * Picks and equips the best weapon. Returns true if the held slot changed this tick (the caller should
     * let the change reach the server and resume attacking next tick).
     */
    static boolean choose(Minecraft mc, LocalPlayer p, LivingEntity target, int targetCount) {
        Inventory inv = p.getInventory();
        NonNullList<ItemStack> items = inv.getNonEquipmentItems();   // 0-8 hotbar, 9-35 main inventory
        int sel = inv.getSelectedSlot();

        double heldScore = score(inv.getSelectedItem(), target, targetCount);
        int bestIdx = -1;
        double bestScore = heldScore;
        int n = Math.min(items.size(), 36);
        for (int i = 0; i < n; i++) {
            double s = score(items.get(i), target, targetCount);
            if (s > bestScore) { bestScore = s; bestIdx = i; }
        }
        if (bestIdx < 0) return false;                                  // nothing strictly better than what's held
        if (heldScore > 0.0 && bestScore < heldScore * SWITCH_MARGIN) return false;   // improvement too small to bother

        if (bestIdx < Inventory.SELECTION_SIZE) {                       // best is already in the hotbar
            if (bestIdx == sel) return false;
            selectSlot(mc, p, bestIdx);
            return true;
        }

        // Best lives in the main inventory -> number-key swap it onto the hotbar via the player menu.
        AbstractContainerMenu menu = p.inventoryMenu;
        if (p.containerMenu != menu || !menu.getCarried().isEmpty()) return false;   // a screen is open or the cursor is busy
        if (menu.getSlot(bestIdx).getItem() != items.get(bestIdx)) return false;     // menu-layout sanity; abort rather than corrupt
        int h = displaceHotbarSlot(items);                              // empty slot first, else the largest stack
        mc.gameMode.handleContainerInput(menu.containerId, bestIdx, h, ContainerInput.SWAP, p);   // bucket-clutch's number-key swap
        selectSlot(mc, p, h);
        return true;
    }

    private static void selectSlot(Minecraft mc, LocalPlayer p, int slot) {
        p.getInventory().setSelectedSlot(slot);
        if (mc.getConnection() != null) mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
    }

    /** Hotbar slot to swap a weapon into: an empty slot if there is one, otherwise the largest stack as the
     *  least-valuable thing to displace -- the same heuristic the water-bucket clutch uses when staging. */
    private static int displaceHotbarSlot(NonNullList<ItemStack> items) {
        int target = 0, biggest = -1;
        for (int hb = 0; hb < Inventory.SELECTION_SIZE; hb++) {
            ItemStack st = items.get(hb);
            if (st.isEmpty()) return hb;
            if (st.getCount() > biggest) { biggest = st.getCount(); target = hb; }
        }
        return target;
    }

    /** Expected full-charge DPS of a melee weapon against this target; 0 for anything that isn't a sword or axe. */
    private static double score(ItemStack stack, LivingEntity target, int targetCount) {
        if (stack.isEmpty()) return 0.0;
        boolean sword = stack.is(h -> h.is(ItemTags.SWORDS));
        boolean axe   = stack.is(h -> h.is(ItemTags.AXES));
        if (!sword && !axe) return 0.0;

        double[] ds = {1.0, 4.0};   // player base attack damage / attack speed; the item adds its own modifiers
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.value() == Attributes.ATTACK_DAMAGE.value()) ds[0] += mod.amount();
            else if (attr.value() == Attributes.ATTACK_SPEED.value()) ds[1] += mod.amount();
        });
        double base = ds[0];
        double attackSpeed = Math.max(0.1, ds[1]);

        double ench = 0.0;
        int sharp = level(stack, Enchantments.SHARPNESS);
        if (sharp > 0) ench += 0.5 * sharp + 0.5;
        if (target != null) {
            int smite = level(stack, Enchantments.SMITE);
            if (smite > 0 && target.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_SMITE)) ench += 2.5 * smite;
            int bane = level(stack, Enchantments.BANE_OF_ARTHROPODS);
            if (bane > 0 && target.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) ench += 2.5 * bane;
        }

        double dps = (base + ench) * attackSpeed;

        if (sword && targetCount > 1) {     // sweep AoE value against a cluster
            int se = level(stack, Enchantments.SWEEPING_EDGE);
            double sweepPerNeighbor = 1.0 + base * se / (se + 1.0);
            dps += sweepPerNeighbor * Math.min(targetCount - 1, 4) * attackSpeed;
        }
        return dps;
    }

    private static int level(ItemStack s, ResourceKey<Enchantment> key) {
        ItemEnchantments e = s.getEnchantments();
        for (Holder<Enchantment> h : e.keySet()) if (h.is(key)) return e.getLevel(h);
        return 0;
    }
}
