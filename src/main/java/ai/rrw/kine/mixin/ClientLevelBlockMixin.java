package ai.rrw.kine.mixin;

import ai.rrw.kine.walk.WalkTable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the walking pathfinder's table and the elytra guardian's block copy about every block change in the client's
 *  world: Level.setBlock calls setBlocksDirty whenever a block's state changes (from the server's updates or the
 *  client's own predictions). */
@Mixin(ClientLevel.class)
public class ClientLevelBlockMixin {
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void kine$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        WalkTable.blockChanged((ClientLevel) (Object) this, pos.getX(), pos.getY(), pos.getZ());
        ai.rrw.kine.autoflight.GuardWorld.blockChanged((ClientLevel) (Object) this, pos.getX(), pos.getY(), pos.getZ());
    }
}
