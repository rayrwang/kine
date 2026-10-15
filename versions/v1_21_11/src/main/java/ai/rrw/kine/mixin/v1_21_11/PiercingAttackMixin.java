package ai.rrw.kine.mixin.v1_21_11;

import ai.rrw.kine.combat.KillAura;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A spear's stab sent (MultiPlayerGameMode.piercingAttack, 1.21.11+): an attack for the kill aura, as
 *  MultiPlayerGameModeMixin's entity attack. */
@Mixin(MultiPlayerGameMode.class)
public class PiercingAttackMixin {
    @Inject(method = "piercingAttack", at = @At("HEAD"))
    private void kine$stabSent(CallbackInfo ci) {
        KillAura.onAttackSent();
    }
}
