package ai.rrw.kine.mixin.v1_21_9;

import ai.rrw.kine.compat.v1_21_9.World;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kine's drawing in the world (WorldDraw passes) at the end of the level's main pass: the frame pass LevelRenderer.
 * addMainPass adds (its lambda, method_62214), just before its last buffer flush, after the translucent terrain, the
 * tripwires and the block outline. That is where Fabric's WorldRenderEvents.END_MAIN runs on 1.21.10 and 1.21.11
 * (1.21.11's kine draws there); 1.21.9's Fabric API has no world render events, so kine hooks the point itself on
 * both releases. The lambda flushes the buffer source three times, the same on both; the third is the last.
 */
@Mixin(LevelRenderer.class)
public class MainPassEndMixin {
    @Inject(method = "method_62214", at = @At(value = "INVOKE", ordinal = 2,
        target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V"))
    private void kine$endMain(CallbackInfo ci) {
        World.endMain();
    }
}
