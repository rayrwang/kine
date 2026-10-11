package ai.rrw.kine.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.OptionalInt;

/** The synced id of the entity a firework rocket is attached to: the gliding player it is boosting. */
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketAccessor {
    @Accessor("DATA_ATTACHED_TO_TARGET")
    static EntityDataAccessor<OptionalInt> kine$attachedToTarget() {
        throw new AssertionError();
    }
}
