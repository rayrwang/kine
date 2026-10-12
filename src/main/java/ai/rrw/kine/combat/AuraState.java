package ai.rrw.kine.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** The kill aura's view of the fight, for the combat movement's simulation (fight/): the held weapon as SwingPlan sees
 *  it and the enchantment bonus against a mob, as KillAura computes them. */
public final class AuraState {
    private AuraState() {}

    /** The held weapon: damage, charge delay, sword or not, sweep ratio (KillAura.weapon). */
    public static SwingPlan.Weapon weapon(LocalPlayer p) {
        SwingPlan.Weapon w = WeaponSelect.weaponOf(p, p.getMainHandItem());
        return new SwingPlan.Weapon(w.damage(), p.getCurrentItemAttackStrengthDelay(), w.sword(), w.sweepRatio());
    }

    /** What the held weapon's enchantments add against this mob at full charge. */
    public static double bonus(ItemStack held, LivingEntity target) {
        return WeaponSelect.bonus(held, target);
    }
}
