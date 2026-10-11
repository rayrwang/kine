package ai.rrw.kine.combat;

import ai.rrw.kine.mixin.ShulkerAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonSittingPhase;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
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
        if (e instanceof EnderMan) return "teleports";
        // Entity.deflection: the deflects_projectiles tag (the breeze) sends projectiles back
        if (e.is(EntityTypeTags.DEFLECTS_PROJECTILES)) return "deflects";
        // WitherBoss.hurtServer: nothing hurts it while it spawns, and arrows don't from half health down
        if (e instanceof WitherBoss w) {
            if (w.getInvulnerableTicks() > 0) return "invulnerable";
            if (w.isPowered()) return "armoured";
        }
        // Shulker.hurtServer: arrows don't hurt it with its shell shut
        if (e instanceof Shulker s && ((ShulkerAccessor) s).invokeGetRawPeekAmount() == 0) return "shell closed";
        // Creaking.hurtServer: one bound to a creaking heart only flinches
        if (e instanceof Creaking c && c.isHeartBound()) return "invulnerable";
        // AbstractDragonSittingPhase.onHurt: a perched dragon sets arrows alight and takes nothing
        if (e instanceof EnderDragon d && d.getPhaseManager().getCurrentPhase() instanceof AbstractDragonSittingPhase)
            return "perched";
        // Player.isInvulnerableTo: creative players take no ordinary damage
        if (e instanceof Player o && o.isCreative()) return "creative";
        if (!piercing && e instanceof LivingEntity le && shieldBlocks(le, from, type)) return "shield up";
        return null;
    }

    // LivingEntity.applyItemBlocking: a raised shield, past its block delay, stops an unpiercing arrow coming from
    // within its blocking angle of where the holder's head faces. The game measures that angle to the arrow's
    // position as it hits; the shooter's position stands in for it.
    private static boolean shieldBlocks(LivingEntity le, Vec3 from, Holder<DamageType> type) {
        ItemStack blocking = le.getItemBlockingWith();
        if (blocking == null) return false;
        BlocksAttacks ba = blocking.get(DataComponents.BLOCKS_ATTACKS);
        if (ba == null || ba.bypassedBy().map(t -> t.contains(type)).orElse(false)) return false;
        Vec3 view = le.calculateViewVector(0.0F, le.getYHeadRot());
        Vec3 to = from.subtract(le.position());
        to = new Vec3(to.x, 0.0, to.z).normalize();
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, to.dot(view))));
        // BlocksAttacks.DamageReduction.resolve: a reduction blocks base + factor * damage, so factor 1 stops all
        for (BlocksAttacks.DamageReduction r : ba.damageReductions())
            if (angle <= Math.toRadians(r.horizontalBlockingAngle()) && r.factor() >= 1.0F
                && r.type().map(t -> t.contains(type)).orElse(true)) return true;
        return false;
    }
}
