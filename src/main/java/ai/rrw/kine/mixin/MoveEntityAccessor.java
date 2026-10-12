package ai.rrw.kine.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Entity.onGroundNoBlocks, part of the support-block state the movement copy (move/) starts from. */
@Mixin(Entity.class)
public interface MoveEntityAccessor {
    @Accessor("onGroundNoBlocks")
    boolean kine$onGroundNoBlocks();
}
