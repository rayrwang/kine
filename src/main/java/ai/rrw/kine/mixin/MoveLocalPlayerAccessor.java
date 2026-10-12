package ai.rrw.kine.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** LocalPlayer.sprintTriggerTime (the double-tap sprint window left), for the movement copy (move/). */
@Mixin(LocalPlayer.class)
public interface MoveLocalPlayerAccessor {
    @Accessor("sprintTriggerTime")
    int kine$sprintTriggerTime();
}
