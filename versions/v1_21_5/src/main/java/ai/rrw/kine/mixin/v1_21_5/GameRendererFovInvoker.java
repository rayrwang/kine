package ai.rrw.kine.mixin.v1_21_5;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** GameRenderer.getFov: the field of view a frame is drawn with (Compat.viewProjection). */
@Mixin(GameRenderer.class)
public interface GameRendererFovInvoker {
    @Invoker("getFov")
    float kine$getFov(Camera camera, float partialTick, boolean useFovSetting);
}
