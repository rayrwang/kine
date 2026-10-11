package ai.rrw.kine.util;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/** How long an elytra can still glide. */
public final class ElytraLife {
    private ElytraLife() {}

    /** Gliding seconds left on an elytra: one durability point per second, times (Unbreaking + 1) (elytra
     *  follow the tool curve for Unbreaking, not the armour one). An elytra stops gliding with 1 point
     *  left, so that point doesn't count. 0 for anything that isn't a damageable elytra. */
    public static double flightSeconds(ItemStack s) {
        if (!s.is(Items.ELYTRA) || !s.isDamageableItem()) return 0.0;
        int usable = Math.max(0, s.getMaxDamage() - 1 - s.getDamageValue());
        int unb = Enchant.level(s, Enchantments.UNBREAKING);
        return (double) usable * (unb + 1);
    }
}
