package ai.rrw.kine.compat.v1_21_2;

import ai.rrw.kine.compat.Rules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 1.21.4: the local player's fall distance as the server holds it (Compat.fallDistance). This release's client never
 * counts its own player's fall (Entity.move skips checkFallDamage for an entity the client controls,
 * Rules.CLIENT_SKIPS_FALL_CHECK), so the player's fallDistance stays 0; the server counts it from the move packets.
 * Kine keeps the server's count here, and leaves the entity's own field at 0, which the client's movement reads
 * (Player.isAboveGround, the sneak edge stop).
 *
 * <p>What changes the server's count, and how it is followed (ServerFallMixin and the other ServerFall mixins):
 * <ul>
 * <li>each move packet (ServerGamePacketListenerImpl.handleMovePlayer): the server's own move of the player, which
 *     resets the count when it goes a block or more through a fall-resetting block or water; then doCheckFallDamage,
 *     whose LivingEntity.checkFallDamage resets it on touching water when the player wasn't in water, and whose
 *     Entity.checkFallDamage resets it on the ground and adds the drop off it; then a reset when the packet moved up.
 *     Followed at the client's move of its player, the move the packet then carries (where the client skips
 *     checkFallDamage);</li>
 * <li>the player's tick on the server, which the client runs too for its player: resetFallDistance (in water,
 *     climbing, slow falling or levitation, flying, riding, cobweb, powder snow and sweet berries (makeStuckInBlock),
 *     inside a bubble column, sliding down honey), the halving in lava (Entity.baseTick), and an elytra glide's cap at
 *     1 while it isn't diving (checkSlowFallDistance, by the client's velocity, which the server's own simulation of
 *     the glide follows). Followed where the client runs them;</li>
 * <li>the server only: a mace's smash hit resets the attacker's count (MaceItem.postHurtEnemy), followed when the
 *     client sends the attack; an ender pearl's and a chorus fruit's teleport reset it, followed when the teleport
 *     arrives (a pearl of the player's next to where it lands it, or a chorus fruit just eaten).</li>
 * </ul>
 * Other teleports (commands, a server's setback) leave the server's count as it is, and so does this. A new local
 * player (a respawn, another dimension) starts from 0.
 */
public final class ServerFall {
    private ServerFall() {}

    /** Only 1.21.4 skips the client's count; 1.21.2 and 1.21.3 count it on the client as the server does. */
    public static final boolean ACTIVE = Rules.CLIENT_SKIPS_FALL_CHECK;

    private static LocalPlayer player;
    private static float fall;
    private static double fromX, fromY, fromZ;
    private static int chorusTick = Integer.MIN_VALUE;

    /** The local player, whose count this is; a new one starts from 0. */
    private static boolean tracked(Entity e) {
        if (!ACTIVE || !(e instanceof LocalPlayer p)) return false;
        if (p != player) {
            player = p;
            fall = 0.0F;
        }
        return true;
    }

    /** The server's fall distance for the local player. */
    public static float of(LocalPlayer p) {
        return tracked(p) ? fall : 0.0F;
    }

    // the client's move of its player (Entity.move): where it starts, and the point where 1.21.4 skips checkFallDamage

    public static void moveStart(Entity e) {
        if (!tracked(e)) return;
        fromX = e.getX();
        fromY = e.getY();
        fromZ = e.getZ();
    }

    public static void moved(Entity e) {
        if (!tracked(e)) return;
        double dx = e.getX() - fromX, dy = e.getY() - fromY, dz = e.getZ() - fromZ;
        // Entity.move on the server: a move of a block or more through a fall-resetting block or water resets it
        if (fall != 0.0F && dx * dx + dy * dy + dz * dz >= 1.0) {
            Vec3 from = new Vec3(fromX, fromY, fromZ);
            HitResult hit = e.level().clip(new ClipContext(from, e.position(), ClipContext.Block.FALLDAMAGE_RESETTING, ClipContext.Fluid.WATER, e));
            if (hit.getType() != HitResult.Type.MISS) fall = 0.0F;
        }
        // LivingEntity.checkFallDamage: not in water before the move, in water now
        if (!e.isInWater() && touchesWater(e)) fall = 0.0F;
        // Entity.checkFallDamage with the packet's on-ground flag (the client's after the move) and its drop
        if (e.onGround()) fall = 0.0F;
        else if (dy < 0.0) fall -= (float) dy;
        // handleMovePlayer: a packet that moved up resets it
        if (dy > 0.0) fall = 0.0F;
    }

    /** Entity.updateFluidHeightAndDoFluidPushing's test for water, without its push: water reaching the box (shrunk by
     *  0.001) somewhere. */
    private static boolean touchesWater(Entity e) {
        if (e.touchingUnloadedChunk()) return false;
        AABB box = e.getBoundingBox().deflate(0.001);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(box.minX); x < Mth.ceil(box.maxX); x++)
            for (int y = Mth.floor(box.minY); y < Mth.ceil(box.maxY); y++)
                for (int z = Mth.floor(box.minZ); z < Mth.ceil(box.maxZ); z++) {
                    pos.set(x, y, z);
                    FluidState fluid = e.level().getFluidState(pos);
                    if (fluid.is(FluidTags.WATER) && y + fluid.getHeight(e.level(), pos) >= box.minY) return true;
                }
        return false;
    }

    // the player's tick, which the client runs too

    /** Entity.resetFallDistance. */
    public static void reset(Entity e) {
        if (tracked(e)) fall = 0.0F;
    }

    /** Entity.baseTick in lava: halved each tick. */
    public static void inLava(Entity e) {
        if (tracked(e)) fall *= 0.5F;
    }

    /** Entity.checkSlowFallDistance (an elytra glide, LivingEntity.updateFallFlying). */
    public static void slowFall(Entity e) {
        if (tracked(e) && e.getDeltaMovement().y() > -0.5 && fall > 1.0F) fall = 1.0F;
    }

    // the server only

    /** An attack sent (MultiPlayerGameMode.attack): Player.attack on the server, when the hit lands on a living target
     *  with a mace, calls MaceItem.postHurtEnemy, which resets a smash attacker's count (MaceItem.canSmashAttack: more
     *  than 1.5 and not gliding). Whether the hit lands is the server's; it is taken to. */
    public static void attack(Player attacker, Entity target) {
        if (!tracked(attacker)) return;
        Entity hit = target instanceof EnderDragonPart part ? part.parentMob : target;
        if (!(hit instanceof LivingEntity) || !target.isAttackable()) return;
        if (attacker.getMainHandItem().getItem() instanceof MaceItem && fall > 1.5F && !attacker.isFallFlying()) fall = 0.0F;
    }

    /** The server finished the player's use of an item (entity event 9, Player.handleEntityEvent), before the teleport
     *  a chorus fruit makes (ServerPlayer.completeUsingItem sends the event, then eats). */
    public static void usedItem(Entity e) {
        if (!tracked(e)) return;
        Consumable food = ((LivingEntity) e).getUseItem().get(DataComponents.CONSUMABLE);
        if (food != null && food.onConsumeEffects().stream().anyMatch(effect -> effect instanceof TeleportRandomlyConsumeEffect))
            chorusTick = e.tickCount;
    }

    /** The server moved the player (ClientPacketListener.handleMovePlayer). An ender pearl's landing
     *  (ThrownEnderpearl.onHit) and a chorus fruit's teleport (TeleportRandomlyConsumeEffect) reset the count; the
     *  pearl, still on the client as the teleport arrives (the server removes it after), lies next to where it puts
     *  the player. */
    public static void teleported(LocalPlayer p) {
        if (!tracked(p)) return;
        if (p.tickCount - chorusTick <= 1) { fall = 0.0F; return; }
        AABB near = p.getBoundingBox().inflate(PEARL_REACH);
        if (!p.level().getEntitiesOfClass(ThrownEnderpearl.class, near, pearl -> pearl.getOwner() == p).isEmpty()) fall = 0.0F;
    }

    /** How far from the player's new position its pearl may be: the pearl's last position on the client is up to a
     *  tick's flight (thrown at 1.5 blocks a tick, faster falling) from where it hit. */
    private static final double PEARL_REACH = 4.0;
}
