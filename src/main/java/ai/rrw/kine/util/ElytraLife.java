package ai.rrw.kine.util;

import ai.rrw.kine.autoflight.Durability;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.List;

/** The elytra a player carries, read for the durability arithmetic ({@link Durability}). */
public final class ElytraLife {
    private ElytraLife() {}

    /** The inventory menu's slots spares are taken from: 9 to 44 the main inventory and the hotbar, 45 the offhand.
     *  Not shulker boxes, which can't be opened in flight (RW, 06-02). */
    public static final int FIRST_SPARE_SLOT = 9;

    /** An elytra as a wing (its points left, the tooltip's number; its Unbreaking; whether it takes no damage), or
     *  null for anything else. */
    public static Durability.Wing wing(ItemStack s) {
        if (!s.is(Items.ELYTRA)) return null;
        return new Durability.Wing(s.getMaxDamage() - s.getDamageValue(), Enchant.level(s, Enchantments.UNBREAKING),
            !s.isDamageableItem());   // Unbreakable: the glide check, nextDamageWillBreak, is never true
    }

    /** A spare the failsafe can swap in, and so one END counts: an elytra that wears. An Unbreakable spare is left
     *  out, as before the rebuild: the swap never takes it, and it adds nothing to END. */
    public static boolean isSpare(ItemStack s) {
        return s.is(Items.ELYTRA) && s.isDamageableItem();
    }

    /** The spares in the inventory menu (slots 9 to 45), in slot order; their slot numbers go into `slots` if it is
     *  not null. */
    public static List<Durability.Wing> spares(AbstractContainerMenu menu, List<Integer> slots) {
        List<Durability.Wing> out = new ArrayList<>();
        for (int i = FIRST_SPARE_SLOT; i < menu.slots.size(); i++) {
            ItemStack st = menu.slots.get(i).getItem();
            if (!isSpare(st)) continue;
            out.add(wing(st));
            if (slots != null) slots.add(i);
        }
        return out;
    }
}
