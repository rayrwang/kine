package ai.rrw.kine.util;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/** How long an elytra can still glide. */
public final class ElytraLife {
    private ElytraLife() {}

    /** Gliding seconds left on an elytra: one durability point per second, times (Unbreaking + 1) (elytra
     *  follow the tool curve for Unbreaking, not the armour one). An elytra stops gliding with 1 point
     *  left, so that point doesn't count. Infinite for an elytra that can't take damage (Unbreakable), which
     *  glides for ever; 0 for anything that isn't an elytra. */
    public static double flightSeconds(ItemStack s) {
        if (!s.is(Items.ELYTRA)) return 0.0;
        if (!s.isDamageableItem()) return Double.POSITIVE_INFINITY;   // nextDamageWillBreak, the glide check, is never true
        int usable = Math.max(0, s.getMaxDamage() - 1 - s.getDamageValue());
        int unb = Enchant.level(s, Enchantments.UNBREAKING);
        return (double) usable * (unb + 1);
    }
}
