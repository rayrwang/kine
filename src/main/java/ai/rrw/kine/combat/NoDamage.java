package ai.rrw.kine.combat;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.Rules;
import ai.rrw.kine.mixin.ShulkerAccessor;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonSittingPhase;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Targets an arrow or a trident (both AbstractArrow) can hit but not hurt right now, by the 26.1.2 hurt rules.
 * Every case reads state the server syncs to the client.
 */
final class NoDamage {
    private NoDamage() {}

    /**
     * Why an arrow or trident of damage type {@code type}, shot from {@code from}, would do the target no
     * damage now, or null if it would hurt it. {@code piercing}: the arrow has Piercing, which shields don't stop.
     */
    static String reason(Entity e, Vec3 from, Holder<DamageType> type, boolean piercing) {
        if (e instanceof EnderDragonPart part) e = part.parentMob;
        // EnderMan.hurtServer: any projectile teleports it away instead
        if (Compat.isEnderman(e) && !(Rules.ENDERMAN_RIDING_TAKES_PROJECTILES && e.isPassenger())) return "teleports";
        // Entity.deflection: the deflects_projectiles tag (the breeze) sends projectiles back
        if (Compat.deflectsProjectiles(e)) return "deflects";
        // WitherBoss.hurtServer: nothing hurts it while it spawns, and arrows don't from half health down
        if (e instanceof WitherBoss w) {
            if (w.getInvulnerableTicks() > 0) return "invulnerable";
            if (w.isPowered()) return "armoured";
        }
        // Shulker.hurtServer: arrows don't hurt it with its shell shut
        if (e instanceof Shulker s && ((ShulkerAccessor) s).invokeGetRawPeekAmount() == 0) return "shell closed";
        // Creaking.hurtServer: one bound to a creaking heart only flinches
        if (Compat.heartBoundCreaking(e)) return "invulnerable";
        // AbstractDragonSittingPhase.onHurt: a perched dragon sets arrows alight and takes nothing
        if (e instanceof EnderDragon d && d.getPhaseManager().getCurrentPhase() instanceof AbstractDragonSittingPhase)
            return "perched";
        // Player.isInvulnerableTo: creative players take no ordinary damage
        if (e instanceof Player o && Compat.isCreative(o)) return "creative";
        if (!piercing && e instanceof LivingEntity le && Compat.shieldStops(le, from, type)) return "shield up";
        return null;
    }
}
