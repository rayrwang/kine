package ai.rrw.kine.mixin.v1_21;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** GameRenderer.getFov: the field of view a frame is drawn with (Compat.viewProjection); a double on these releases. */
@Mixin(GameRenderer.class)
public interface GameRendererFovInvoker {
    @Invoker("getFov")
    double kine$getFov(Camera camera, float partialTick, boolean useFovSetting);
}
