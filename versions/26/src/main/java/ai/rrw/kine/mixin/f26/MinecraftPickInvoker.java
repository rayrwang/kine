package ai.rrw.kine.mixin.f26;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft.pick: what the crosshair is on (26.x; 1.21.x picks in GameRenderer). */
@Mixin(Minecraft.class)
public interface MinecraftPickInvoker {
    @Invoker("pick")
    void invokePick(float partialTicks);
}
