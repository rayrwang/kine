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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto weapon for the kill aura: scores every sword and axe in the hotbar and main inventory against the mobs in reach
 * and equips the best, moving it into the hotbar if it is in the main inventory.
 *
 * <p>The score is the damage a tick the kill aura's plan can sustain with the weapon against these mobs
 * (SwingPlan.rate): the better of its best swing cycle (one mob: full-charge hits; a crowd: quick hits and a charged
 * one, so a heavy, slow axe gains in a crowd, its quick hits being bigger) and, for a sword, full-charge sweeps around
 * the mob with the most neighbours. The damage is the weapon's own with the player's base 1 (and Strength or Weakness),
 * plus Sharpness {@code 0.5*lvl + 0.5}, and Smite or Bane of Arthropods {@code 2.5*lvl} on the mobs they hurt, after
 * each mob's armour. It switches only when the best beats the held weapon by SWITCH_MARGIN, so it doesn't flip
 * between near-equal weapons (a switch also starts the charge again).
 *
 * <p>Moving a weapon from the main inventory uses a hotbar slot: an empty one first, else the largest
 * stack, as the water-bucket clutch does. That number-key swap is an inventory click, made as a player
 * would: one tick with the inventory "opened" (every key up, InventoryClicks.requestOpen), the swap at that
 * tick's end (between ticks, where a vanilla client clicks in its inventory screen), then the close as a
 * vanilla client closes it; the slot is selected the tick after. Selecting a hotbar slot is a number-key
 * press: the game sends the change itself before the next attack.
 */
public final class WeaponSelect {
    private WeaponSelect() {}

    private static final double SWITCH_MARGIN = 1.10;   // switch only if the best scores 10% over what is held

    private static int pendingFrom = -1;                    // a weapon to swap in from the main inventory at the open tick's end
    private static ItemStack pendingStack = ItemStack.EMPTY;  // and the weapon, to check it is still there then

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(WeaponSelect::endTick);
    }

    // At the end of the tick the inventory "opened" in: the swap chosen, made between ticks, then the close as a
    // vanilla client closes it; the slot is selected next tick. One batch a tick: if the clutch (earlier in the
    // tick's end) clicked in this opening, the weapon waits for the aura's next pick.
    private static void endTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) { pendingFrom = -1; pendingStack = ItemStack.EMPTY; return; }
        if (pendingFrom < 0 || !InventoryClicks.isOpenTick()) return;   // nothing to do, or the open tick is still to come
        int from = pendingFrom;
        ItemStack want = pendingStack;
        pendingFrom = -1; pendingStack = ItemStack.EMPTY;
        if (mc.gameMode == null || !InventoryClicks.available(mc, p) || !InventoryClicks.claimBatch()) return;
        AbstractContainerMenu menu = p.inventoryMenu;
        int to = -1;
        if (!want.isEmpty() && ItemStack.matches(menu.getSlot(from).getItem(), want)) {   // the weapon is still there
            to = Hotbar.slotToDisplace(p.getInventory().getNonEquipmentItems()::get);   // chosen now, after anything moved since
            mc.gameMode.handleContainerInput(menu.containerId, from, to, ContainerInput.SWAP, p);   // number-key swap
        }
        InventoryClicks.closeAsVanilla(mc, p);   // the opened inventory closes, clicked in or not
        if (to >= 0) p.getInventory().setSelectedSlot(to);
    }

    /**
     * Picks and equips the best weapon against these mobs (the kill aura's plan view of them, in the same order).
     * Returns true if it changed or will change the held item (the caller then waits a tick before attacking).
     * @param canSweep the player is on the ground, not sprinting and slow enough for a sword's sweep
     */
    static boolean choose(Minecraft mc, LocalPlayer p, List<LivingEntity> targets, List<SwingPlan.Mob> mobs, boolean canSweep) {
        Inventory inv = p.getInventory();
        NonNullList<ItemStack> items = inv.getNonEquipmentItems();   // 0 to 8 hotbar, 9 to 35 main inventory
        int sel = inv.getSelectedSlot();

        double heldScore = score(p, inv.getSelectedItem(), targets, mobs, canSweep);
        int bestIdx = -1;
        double bestScore = heldScore;
        int n = Math.min(items.size(), 36);
        for (int i = 0; i < n; i++) {
            double s = score(p, items.get(i), targets, mobs, canSweep);
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
        // end of the next tick, which plays the inventory's opening.
        AbstractContainerMenu menu = p.inventoryMenu;
        if (!InventoryClicks.available(mc, p)) return false;                         // a screen or container is open, or the cursor is busy
        if (menu.getSlot(bestIdx).getItem() != items.get(bestIdx)) return false;     // menu slot doesn't match the inventory index
        pendingFrom = bestIdx;
        pendingStack = items.get(bestIdx);
        InventoryClicks.requestOpen();
        return true;
    }

    private static void selectSlot(Minecraft mc, LocalPlayer p, int slot) {
        p.getInventory().setSelectedSlot(slot);
    }

    /** The damage a tick the aura's plan can sustain with this stack against these mobs; 0 for anything but a sword
     *  or axe. */
    private static double score(LocalPlayer p, ItemStack stack, List<LivingEntity> targets, List<SwingPlan.Mob> mobs,
                                boolean canSweep) {
        if (stack.isEmpty() || !(stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES))) return 0.0;
        SwingPlan.Weapon w = weaponOf(p, stack);
        List<SwingPlan.Mob> with = new ArrayList<>();
        for (int i = 0; i < mobs.size(); i++) {
            SwingPlan.Mob o = mobs.get(i);
            SwingPlan.Mob m = new SwingPlan.Mob(o.health, o.armour);
            m.toughness = o.toughness;
            m.swept = o.swept;
            m.bonus = bonus(stack, targets.get(i));
            with.add(m);
        }
        return SwingPlan.rate(w, with, canSweep && w.sword());
    }

    /**
     * A stack as a weapon: attack damage with the player's base 1 and the item's modifiers, plus Strength (3 a level) or
     * Weakness (-4 a level) as the server adds them; the charge delay from the player's base speed 4 and the item's
     * modifier (Player.getCurrentItemAttackStrengthDelay: 20 / speed); whether it sweeps (a sword); and its sweep ratio
     * (Sweeping Edge: level / (level + 1)).
     */
    static SwingPlan.Weapon weaponOf(LocalPlayer p, ItemStack stack) {
        double[] ds = {1.0, 4.0};   // the player's base attack damage and attack speed; the item adds its modifiers
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.value() == Attributes.ATTACK_DAMAGE.value()) ds[0] += mod.amount();
            else if (attr.value() == Attributes.ATTACK_SPEED.value()) ds[1] += mod.amount();
        });
        MobEffectInstance strength = p.getEffect(MobEffects.STRENGTH), weakness = p.getEffect(MobEffects.WEAKNESS);
        if (strength != null) ds[0] += 3.0 * (strength.getAmplifier() + 1);
        if (weakness != null) ds[0] -= 4.0 * (weakness.getAmplifier() + 1);
        float delay = (float) (1.0 / Math.max(0.1, ds[1]) * 20.0);
        int sweeping = Enchant.level(stack, Enchantments.SWEEPING_EDGE);
        return new SwingPlan.Weapon(Math.max(0.0, ds[0]), delay, stack.is(ItemTags.SWORDS), sweeping / (sweeping + 1.0));
    }

    /** The damage a stack's enchantments add against this mob at full charge. */
    static double bonus(ItemStack stack, LivingEntity target) {
        if (stack.isEmpty()) return 0.0;
        boolean undead = target.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_SMITE);
        boolean arthropod = target.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS);
        return bonus(Enchant.level(stack, Enchantments.SHARPNESS), undead ? Enchant.level(stack, Enchantments.SMITE) : 0,
                     arthropod ? Enchant.level(stack, Enchantments.BANE_OF_ARTHROPODS) : 0);
    }

    /** Enchantments.java: Sharpness 0.5 * level + 0.5; Smite and Bane of Arthropods 2.5 * level (pass 0 for a mob
     *  they don't hurt). */
    static double bonus(int sharpness, int smite, int bane) {
        double b = 0.0;
        if (sharpness > 0) b += 0.5 * sharpness + 0.5;
        if (smite > 0) b += 2.5 * smite;
        if (bane > 0) b += 2.5 * bane;
        return b;
    }

}
