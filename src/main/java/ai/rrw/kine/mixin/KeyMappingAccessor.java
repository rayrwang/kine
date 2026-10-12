package ai.rrw.kine.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A key's presses not yet taken by the game's keybind step, to see a click coming without taking it, and to hold one
 *  back for a later tick (the kill aura, while the rotation it sends is turned away from your view). */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("clickCount")
    int kine$getClickCount();

    @Accessor("clickCount")
    void kine$setClickCount(int count);

    /** The key it is bound to (combat movement reads sneak and sprint as physical presses). */
    @Accessor("key")
    InputConstants.Key kine$getKey();
}
