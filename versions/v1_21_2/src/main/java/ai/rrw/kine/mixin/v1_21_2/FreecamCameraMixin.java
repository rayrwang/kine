package ai.rrw.kine.mixin.v1_21_2;

import ai.rrw.kine.view.Freecam;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: after the game has put the camera at your player (Camera.setup), put it at the freecam's place
 *  and facing instead, detached, so your player is drawn where it stands and no hand is drawn. */
@Mixin(Camera.class)
public abstract class FreecamCameraMixin {
    @Shadow private boolean detached;
    @Shadow protected abstract void setRotation(float yRot, float xRot);
    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"))
    private void kine$freecam(BlockGetter level, Entity entity, boolean detachedView, boolean mirror, float partialTicks, CallbackInfo ci) {
        if (!Freecam.active()) return;
        setRotation(Freecam.yaw(), Freecam.pitch());
        setPosition(Freecam.x(partialTicks), Freecam.y(partialTicks), Freecam.z(partialTicks));
        detached = true;
    }
}
