package ai.rrw.kine.mixin;

import ai.rrw.kine.combat.KillAura;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the kill aura when the game sends an attack (an entity attack; a spear's stab, on the releases that have
 * spears, in the family folders' PiercingAttackMixin), so it knows its startAttack attacked without reading the
 * charge, which is already 0 the tick after the held item changes.
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void kine$attackSent(CallbackInfo ci) {
        KillAura.onAttackSent();
    }
}
