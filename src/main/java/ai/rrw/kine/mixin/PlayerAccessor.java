package ai.rrw.kine.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The ticks since the player's last swing (attackStrengthTicker, declared in LivingEntity, counted by Player.tick),
 *  which the charge is computed from: the kill aura plans its swings in these ticks. */
@Mixin(LivingEntity.class)
public interface PlayerAccessor {
    @Accessor("attackStrengthTicker")
    int kine$getAttackStrengthTicker();
}
