package ai.rrw.kine.mixin;

import ai.rrw.kine.Settings;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class PlayerMixin {

    // Re-declares Minecraft's own private method so our code can call it.
    // No body — the real one stays in Player; this is just a handle.
    @Shadow protected abstract boolean canFallAtLeast(double deltaX, double deltaZ, double minHeight);

    @ModifyExpressionValue(
        method = "maybeBackOffFromEdge",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/world/entity/player/Player;isStayingOnGroundSurface()Z")
    )
    private boolean kine$protectWhenDangerous(boolean original, @Local(argsOnly = true) Vec3 delta) {
        return original || kine$dangerousFallAhead(delta);
    }

    @Unique
    private boolean kine$dangerousFallAhead(Vec3 delta) {
        if (!Settings.fallPrevention) return false;
        // DELIBERATELY NAIVE first pass: treat any 3+ block drop as dangerous.
        return this.canFallAtLeast(delta.x, delta.z, 3.0);
    }
}
