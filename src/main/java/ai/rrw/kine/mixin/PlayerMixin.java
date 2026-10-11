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

    // Declares Player's own method so the injected code can call it.
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
        // Any drop of more than 3 blocks counts as dangerous, whatever is below (water and hay too).
        // canFallAtLeast tests a box reaching 1e-7 below the given depth, so a drop of exactly 3 is allowed.
        return this.canFallAtLeast(delta.x, delta.z, 3.0);
    }
}
