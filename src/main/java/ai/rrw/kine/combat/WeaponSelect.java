package ai.rrw.kine.combat;

import ai.rrw.kine.util.Enchant;
import ai.rrw.kine.util.Hotbar;
import ai.rrw.kine.util.InventoryClicks;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Auto weapon for the kill aura: scores every sword and axe in the hotbar and main inventory against the
 * current target and equips the best, moving it into the hotbar if it is in the main inventory.
 *
 * <p>The score is damage per second at full charge, the aura's rate when it waits for a full charge:
 * per-hit damage (the weapon's attack damage plus the player's base 1, plus Sharpness
 * {@code 0.5*lvl + 0.5}, plus Smite or Bane of Arthropods {@code 2.5*lvl} when the target's type is
 * susceptible) times attack speed. Against a crowd a sword also scores its sweep (more with Sweeping
 * Edge). It switches only when the best beats the held weapon by SWITCH_MARGIN, so it doesn't flip
 * between near-equal weapons.
 *
 * <p>Moving a weapon from the main inventory uses a hotbar slot: an empty one first, else the largest
 * stack, as the water-bucket clutch does. That number-key swap is an inventory click, so it waits for the
 * end of the tick (between ticks, where a vanilla client clicks in its inventory screen) and is followed by
 * the inventory close packet; the slot is selected the tick after. Selecting a hotbar slot is a number-key
 * press: the game sends the change itself before the next attack.
 */
public final class WeaponSelect {
    private WeaponSelect() {}

    private static final double SWITCH_MARGIN = 1.10;   // switch only if the best scores 10% over what is held

    private static int pendingFrom = -1, pendingTo = -1;   // a swap from the main inventory to make at the tick's end

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(WeaponSelect::endTick);
    }

    // The swap chosen this tick, made between ticks, then the close packet; the slot is selected next tick.
    private static void endTick(Minecraft mc) {
        int from = pendingFrom, to = pendingTo;
        pendingFrom = pendingTo = -1;
        LocalPlayer p = mc.player;
        if (from < 0 || p == null || mc.gameMode == null || !InventoryClicks.available(mc, p)) return;
        AbstractContainerMenu menu = p.inventoryMenu;
        mc.gameMode.handleContainerInput(menu.containerId, from, to, ContainerInput.SWAP, p);   // number-key swap
        InventoryClicks.close(mc, p);
        p.getInventory().setSelectedSlot(to);
    }

    /**
     * Picks and equips the best weapon. Returns true if it changed or will change the held item (the caller
     * then waits a tick before attacking).
     */
    static boolean choose(Minecraft mc, LocalPlayer p, LivingEntity target, int targetCount) {
        Inventory inv = p.getInventory();
        NonNullList<ItemStack> items = inv.getNonEquipmentItems();   // 0 to 8 hotbar, 9 to 35 main inventory
        int sel = inv.getSelectedSlot();

        double heldScore = score(inv.getSelectedItem(), target, targetCount);
        int bestIdx = -1;
        double bestScore = heldScore;
        int n = Math.min(items.size(), 36);
        for (int i = 0; i < n; i++) {
            double s = score(items.get(i), target, targetCount);
            if (s > bestScore) { bestScore = s; bestIdx = i; }
        }
        if (bestIdx < 0) return false;                                  // nothing better than what is held
        if (heldScore > 0.0 && bestScore < heldScore * SWITCH_MARGIN) return false;   // improvement too small to bother

        if (bestIdx < Inventory.SELECTION_SIZE) {                       // best is already in the hotbar
            if (bestIdx == sel) return false;
            selectSlot(mc, p, bestIdx);
            return true;
        }

        // The best is in the main inventory: number-key swap it into the hotbar through the player menu, at the
        // end of this tick.
        AbstractContainerMenu menu = p.inventoryMenu;
        if (!InventoryClicks.available(mc, p)) return false;                         // a screen or container is open, or the cursor is busy
        if (menu.getSlot(bestIdx).getItem() != items.get(bestIdx)) return false;     // menu slot doesn't match the inventory index
        pendingFrom = bestIdx;
        pendingTo = Hotbar.slotToDisplace(items::get);                  // empty slot first, else the largest stack
        return true;
    }

    private static void selectSlot(Minecraft mc, LocalPlayer p, int slot) {
        p.getInventory().setSelectedSlot(slot);
    }

    /** Damage per second at full charge against this target; 0 for anything but a sword or axe. */
    private static double score(ItemStack stack, LivingEntity target, int targetCount) {
        if (stack.isEmpty()) return 0.0;
        boolean sword = stack.is(h -> h.is(ItemTags.SWORDS));
        boolean axe   = stack.is(h -> h.is(ItemTags.AXES));
        if (!sword && !axe) return 0.0;

        double[] ds = {1.0, 4.0};   // the player's base attack damage and attack speed; the item adds its modifiers
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.value() == Attributes.ATTACK_DAMAGE.value()) ds[0] += mod.amount();
            else if (attr.value() == Attributes.ATTACK_SPEED.value()) ds[1] += mod.amount();
        });
        double base = ds[0];
        double attackSpeed = Math.max(0.1, ds[1]);

        int sharp = Enchant.level(stack, Enchantments.SHARPNESS);
        int smite = 0, bane = 0;
        if (target != null) {
            if (target.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_SMITE))
                smite = Enchant.level(stack, Enchantments.SMITE);
            if (target.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS))
                bane = Enchant.level(stack, Enchantments.BANE_OF_ARTHROPODS);
        }
        int sweeping = Enchant.level(stack, Enchantments.SWEEPING_EDGE);
        return damagePerSecond(sword, base, attackSpeed, sharp, smite, bane, sweeping, targetCount);
    }

    /**
     * Damage per second at full charge: (base + enchantment bonus) times attack speed, plus a sword's
     * sweep against a cluster. Smite and bane are the levels that apply to this target (0 if it isn't
     * susceptible). The bonuses are the vanilla formulas: Sharpness 0.5 * level + 0.5, Smite and Bane
     * of Arthropods 2.5 * level; the sweep hits up to 4 neighbours for 1 + base * level / (level + 1).
     */
    static double damagePerSecond(boolean sword, double base, double attackSpeed, int sharpness, int smite,
                                  int bane, int sweeping, int targetCount) {
        double ench = 0.0;
        if (sharpness > 0) ench += 0.5 * sharpness + 0.5;
        if (smite > 0) ench += 2.5 * smite;
        if (bane > 0) ench += 2.5 * bane;

        double dps = (base + ench) * attackSpeed;

        if (sword && targetCount > 1) {     // sweep value against a cluster
            double sweepPerNeighbor = 1.0 + base * sweeping / (sweeping + 1.0);
            dps += sweepPerNeighbor * Math.min(targetCount - 1, 4) * attackSpeed;
        }
        return dps;
    }

}
