package ai.rrw.kine.mixin;

import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The shulker's synced shell opening (0 shut, 30 peeking, 100 open), which decides whether arrows hurt it. */
@Mixin(Shulker.class)
public interface ShulkerAccessor {
    @Invoker("getRawPeekAmount")
    int invokeGetRawPeekAmount();
}
