package ai.rrw.kine.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** LivingEntity's synced flags (bit 1: using an item, as the server last said), for the bow aura: a new draw waits until
 *  the server's word on the last one has come, or that word would stop the new draw on the client (LocalPlayer
 *  .onSyncedDataUpdated). */
@Mixin(LivingEntity.class)
public interface LivingFlagsAccessor {
    @Accessor("DATA_LIVING_ENTITY_FLAGS")
    static EntityDataAccessor<Byte> kine$livingFlags() {
        throw new AssertionError();
    }
}
