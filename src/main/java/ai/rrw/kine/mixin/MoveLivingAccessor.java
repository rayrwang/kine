package ai.rrw.kine.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** LivingEntity.noJumpDelay (ticks before a held jump jumps again), for the movement copy (move/). */
@Mixin(LivingEntity.class)
public interface MoveLivingAccessor {
    @Accessor("noJumpDelay")
    int kine$noJumpDelay();
}
