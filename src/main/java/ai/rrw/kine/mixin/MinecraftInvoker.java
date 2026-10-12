package ai.rrw.kine.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The game's own crosshair pick, attack and use, so automated actions send exactly the packets a vanilla
 * click sends for the rotation the player has; and the right-click delay, to tell when a held use fires (and to hold one
 * off for a tick).
 */
@Mixin(Minecraft.class)
public interface MinecraftInvoker {
    @Accessor("rightClickDelay")
    int kine$getRightClickDelay();

    @Accessor("rightClickDelay")
    void kine$setRightClickDelay(int ticks);

    @Invoker("pick")
    void invokePick(float partialTicks);

    @Invoker("startAttack")
    boolean invokeStartAttack();

    @Invoker("startUseItem")
    void invokeStartUseItem();
}
