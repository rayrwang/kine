package ai.rrw.kine.combat;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.combat.Ballistics.Spec;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudDraw;
import ai.rrw.kine.hud.ScreenProjection;
import ai.rrw.kine.util.Enchant;
import ai.rrw.kine.util.MouseTurn;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public class ProjectileTargeting {

    // simulation and reticle
    private static final double SPREAD_MULT = 2.0;
    private static final double ANG_PER_UNC = 0.0172275;
    private static final double MIN_R       = 0.15;
    private static final int    RING_SEGS   = 48;
    private static final float  NEAR_W      = 0.05f; // clip-space w to clip segments at (keeps the divide finite)
    private static final int    LINE_PX     = 1;     // reticle line thickness in GUI pixels
    private static final float  MIN_SEG_PX2 = 1.0f;  // shortest segment drawn, squared pixels (bridges thin ellipse tips)

    // aim assist (the turn itself: AimControl)
    private static final float  MOUSE_EPS      = 0.4f;   // mouse drift (deg) that releases the lock
    private static final int    SETTLE_TICKS   = 10;     // the view must rest this long (half a second) before a target is picked up
    // A target is picked up when the view is this close to aiming at it (aimGap): for a mob on the ground, the
    // reticle ring's own angular radius plus the mob's half width as seen, the reticle's receptive field; in the air,
    // AIR_ACQUIRE_DEG, wider, since a flying mob is hard to hold the crosshair on.
    private static final double RING_DEG       = Math.toDegrees(ANG_PER_UNC * SPREAD_MULT);   // about 2 degrees
    private static final double AIR_ACQUIRE_DEG = 6.0;
    private static final double HOLD_DEG       = 10.0;   // a held target is kept while the view stays this close
    private static final int    ARC_STEPS      = 16;     // points along aimGap's arc
    private static final double REACH_MISS     = 0.5;    // blocks: a solved shot that passes further from the led point can't reach it
    private static final double AIM_RANGE      = 256.0;  // farthest target picked up or kept (blocks); a bow reaches about 117 on the level, 164 from 80 up
    private static final int    RETICLE_TICKS  = 300;    // longest simulated flight for the ring (a long shot from a height takes 90 or more)
    private static final double SEARCH_COS     = Math.cos(Math.toRadians(60.0));   // cut before the solve: a long shot aims up to about 45 degrees above its target
    // How much older the client's view of a target is than the last position the server sent: a living entity is
    // drawn 2 ticks behind it (InterpolationHandler's 3 steps, at the 2 or 3 tick update interval).
    private static final double INTERP_TICKS   = 2.0;
    // How far below a falling target its floor is looked for (blocks): first near (a target on the ground or in a jump,
    // which rises 1.25, finds it there), then far (a fall from rest takes about 46 ticks to drop 64 blocks).
    private static final double FLOOR_NEAR     = 4.0;
    private static final double FLOOR_FAR      = 64.0;

    // colours
    private static final int COLOR_RING   = 0xFFFFFFFF;
    private static final int COLOR_ENTITY = 0xFF55FF55;
    private static final int COLOR_NO_DAMAGE = HudColors.AMBER;   // a target the shot can't hurt


    // reticle state, written each tick and read each frame
    private static boolean valid = false;
    private static Vec3 impact = Vec3.ZERO;
    private static Vec3 normal = new Vec3(0, 1, 0);
    private static double radius = 0;
    private static AABB hitBox = null;
    private static int hitId = -1;   // the shown target's entity id, for the hit chance; -1 when none
    private static String noDamage = null;   // why the shot can't hurt the shown target (NoDamage), or null
    private static boolean ringOnTarget = false;   // the ring covers the shown target

    // aim state
    private static boolean aiming = false;
    private static float lastYaw, lastPitch;          // the view at the end of the last tick, after any turn of ours
    private static boolean haveLast = false;
    private static int stillTicks = 0;                // ticks the pilot has left the view alone
    private static Entity lock = null;                // the target auto aim holds, kept from tick to tick
    private static float lastSolYaw, lastSolPitch;    // its solved aim last tick, for the turn's feed-forward
    private static boolean haveLastSol = false;
    private static final Map<Integer, Ballistics.Course> courses = new HashMap<>();   // each target's course, by entity id, worked out once a tick

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ProjectileTargeting::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "projectile_reticle"),
            ProjectileTargeting::render);
    }

    // which projectile, its flight parameters, and the item it comes from
    // spec: the shot as released now; aim: the shot auto aim steers for (a bow being drawn: its full draw, so the
    // view moves once to where the finished draw needs it instead of following the draw's changing arc)
    private record Held(Spec spec, Spec aim, ItemStack item) {
        Held(Spec spec, ItemStack item) { this(spec, spec, item); }
    }

    private static Held heldShot(LocalPlayer p) {
        if (p.isUsingItem()) {
            ItemStack use = p.getUseItem();
            if (use.is(Items.BOW)) {
                float t = p.getTicksUsingItem() / 20f;
                float pow = (t * t + 2f * t) / 3f;
                if (pow > 1f) pow = 1f;
                if (pow < 0.1f) return null;
                return new Held(new Spec(0.05, 0.99F, pow * 3.0, 0.0, false, true),   // arrow
                                new Spec(0.05, 0.99F, 3.0, 0.0, false, true), use);
            }
            if (use.is(Items.TRIDENT)) {
                // TridentItem.releaseUsing: thrown only after 10 ticks of use, never with Riptide, not if it would break
                if (p.getTicksUsingItem() < TridentItem.THROW_THRESHOLD_TIME
                    || EnchantmentHelper.getTridentSpinAttackStrength(use, p) > 0.0F || use.nextDamageWillBreak()) return null;
                return new Held(new Spec(0.05, 0.99F, 2.5, 0.0, false, true), use);
            }
        }
        Spec s = specForHeld(p.getMainHandItem());
        if (s != null) return new Held(s, p.getMainHandItem());
        s = specForHeld(p.getOffhandItem());
        return s != null ? new Held(s, p.getOffhandItem()) : null;
    }

    private static Spec specForHeld(ItemStack stack) {
        var it = stack.getItem();
        // gravity from each entity's getDefaultGravity, drag ThrowableProjectile's 0.99F, launch from the item's use
        if (it == Items.SNOWBALL || it == Items.EGG || it == Items.ENDER_PEARL)
            return new Spec(0.03, 0.99F, 1.5, 0.0, true, false);
        if (it == Items.SPLASH_POTION || it == Items.LINGERING_POTION)
            return new Spec(0.05, 0.99F, 0.5, -20.0, true, false);   // AbstractThrownPotion
        if (it == Items.EXPERIENCE_BOTTLE)
            return new Spec(0.07, 0.99F, 0.7, -20.0, true, false);   // ThrownExperienceBottle
        if (it == Items.CROSSBOW && CrossbowItem.isCharged(stack)) {
            ChargedProjectiles cp = stack.get(DataComponents.CHARGED_PROJECTILES);
            if (cp != null && cp.contains(Items.FIREWORK_ROCKET)) return null; // firework physics differ
            return new Spec(0.05, 0.99F, 3.15, 0.0, false, true, false);   // arrow, fired along the view without the shooter's motion (CrossbowItem.shootProjectile)
        }
        return null;
    }

    // The shooter's motion is the per-tick position change: getKnownMovement() and getDeltaMovement()
    // read low for the local player. Zero for a weapon that doesn't pass it on.
    private static Vec3 shooterMotion(LocalPlayer p, Spec spec) {
        if (!spec.inherits()) return Vec3.ZERO;
        return new Vec3(p.getX() - p.xOld, p.getY() - p.yOld, p.getZ() - p.zOld);
    }

    private static Vec3 launchVel(LocalPlayer p, double yaw, double pitch, Spec spec) {
        return Ballistics.launchVel(yaw, pitch, spec, shooterMotion(p, spec), p.onGround());
    }

    // What the projectile can hit (Projectile.canHitEntity): anything live and pickable, so end crystals, boats,
    // minecarts, item frames and paintings as well as mobs, players and dragon parts. Arrows and tridents pass
    // through players the shooter's team can't harm (AbstractArrow.canHitEntity). The shooter's own vehicle and
    // those riding it are passed through until the flight has left them (collider).
    private static Predicate<Entity> hittable(LocalPlayer self, boolean arrow) {
        return e -> {
            if (!e.canBeHitByProjectile()) return false;
            return !(arrow && e instanceof Player other && !self.canHarmPlayer(other));
        };
    }

    // What auto aim and the reticle's shown target choose from: the hittable mobs, players and dragon parts
    // (dragon hitboxes aren't LivingEntity), never the shooter's own mount or someone riding with it.
    private static Predicate<Entity> targetable(LocalPlayer self, Predicate<Entity> hittable) {
        return hittable.and(e -> (e instanceof LivingEntity || e instanceof EnderDragonPart) && !self.isPassengerOfSameVehicle(e));
    }

    // Your own pets, which auto aim never picks (RW 2026-10-11: "make it not shoot your own dog"): a tamed wolf, cat,
    // parrot or nautilus whose owner is you (TamableAnimal syncs its owner to the client), and any tamed horse, donkey,
    // mule, llama or camel, since its owner is never sent (AbstractHorse keeps it on the server), so it may be yours.
    static boolean ownPet(LocalPlayer self, Entity e) {
        if (e instanceof TamableAnimal t) {
            EntityReference<LivingEntity> owner = t.getOwnerReference();
            return owner != null && owner.getUUID().equals(self.getUUID());
        }
        return e instanceof AbstractHorse h && h.isTamed();
    }

    // the damage an arrow or trident from this item does, for the shield rules
    private static Holder<DamageType> damageType(ClientLevel level, ItemStack item) {
        return level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                    .getOrThrow(item.is(Items.TRIDENT) ? DamageTypes.TRIDENT : DamageTypes.ARROW);
    }

    // A hittable entity and its motion per tick, gathered once a tick for the simulated flights (its course, which
    // reads the floor under it, only once a flight comes near it).
    private record Mover(Entity e, Vec3 motion) {}

    private static List<Mover> movers(ClientLevel level, Entity self, Vec3 eye, Predicate<Entity> hittable) {
        List<Mover> out = new ArrayList<>();
        for (Entity e : level.entitiesForRendering())
            if (e != self && hittable.test(e) && e.position().distanceToSqr(eye) <= AIM_RANGE * AIM_RANGE)
                out.add(new Mover(e, motion(e)));
        return out;
    }

    // Stops a simulated flight at the first block or entity (the reticle). Each entity is met where it will be when
    // the projectile gets there, along its course (course) from where it will be when the shot spawns (`lead` ticks
    // on), as the hit chance models it: a led shot at a moving mob meets it there, not where it stands now.
    // As in the game, the shooter's own vehicle and those riding it can't be hit until the flight has left them.
    private static Ballistics.Collider<Entity> collider(ClientLevel level, Entity self, List<Mover> movers, double lead) {
        List<Entity> ride = self.getRootVehicle().getSelfAndPassengers().filter(Entity::isPickable).toList();
        boolean[] left = {false};   // the flight has left the ride; reset at each flight's first tick
        return (pos, next, tick) -> {
            // Projectile.checkLeftOwner, at the start of each tick: left once the projectile's box (an arrow's or
            // trident's 0.5; a thrown item's 0.25 is near enough), swept along the tick's move and widened by 1,
            // touches none of the ride, the shooter included
            if (tick == 1) left[0] = false;
            if (!left[0]) {
                AABB swept = new AABB(pos.x - 0.25, pos.y, pos.z - 0.25, pos.x + 0.25, pos.y + 0.5, pos.z + 0.25)
                    .expandTowards(next.subtract(pos)).inflate(1.0);
                left[0] = ride.stream().noneMatch(e -> swept.intersects(e.getBoundingBox()));
            }
            BlockHitResult bhr = level.clip(new ClipContext(pos, next,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
            Vec3 segEnd = bhr.getType() != HitResult.Type.MISS ? bhr.getLocation() : next;

            AABB seg = new AABB(pos, segEnd).inflate(0.5);   // the widest margin is 0.3
            Entity ne = null; Vec3 np = null; double best = Double.MAX_VALUE;
            for (Mover m : movers) {
                if (!left[0] && ride.contains(m.e())) continue;
                // across first, where the course goes straight on, so only an entity near the flight reads its floor
                AABB b0 = m.e().getBoundingBox();
                double t = lead + tick, hx = m.motion().x * t, hz = m.motion().z * t;
                if (b0.maxX + hx < seg.minX || b0.minX + hx > seg.maxX || b0.maxZ + hz < seg.minZ || b0.minZ + hz > seg.maxZ) continue;
                AABB bb = b0.move(course(level, m.e()).at(t));
                if (!bb.intersects(seg)) continue;
                Optional<Vec3> h = entityHit(level, self, bb, pos, segEnd, HitChance.margin(tick));
                if (h.isPresent()) {
                    double d = pos.distanceToSqr(h.get());
                    if (d < best) { best = d; ne = m.e(); np = h.get(); }
                }
            }
            if (ne != null) return new Ballistics.Hit<>(np, null, ne);
            if (bhr.getType() != HitResult.Type.MISS) return new Ballistics.Hit<>(bhr.getLocation(), bhr.getDirection().getUnitVec3(), null);
            return null;
        };
    }

    // ProjectileUtil.getManyEntityHitResult: the move hits the box, or else the box widened by the margin (none
    // for a projectile's first two ticks, then 0.05 more a tick up to 0.3), provided the line from that graze
    // point toward the box centre reaches the box before any block.
    private static Optional<Vec3> entityHit(ClientLevel level, Entity self, AABB bb, Vec3 from, Vec3 to, double margin) {
        Optional<Vec3> exact = bb.clip(from, to);
        if (exact.isPresent() || margin <= 0) return exact;
        Optional<Vec3> graze = bb.inflate(margin).clip(from, to);
        if (graze.isEmpty()) return graze;
        Vec3 toward = bb.getCenter();
        BlockHitResult wall = level.clip(new ClipContext(graze.get(), toward,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
        if (wall.getType() != HitResult.Type.MISS) toward = wall.getLocation();
        return bb.clip(graze.get(), toward);
    }

    // the entity nearest the reticle centre p, among those the spread disc (radius r) overlaps
    private static Entity bestTarget(Entity self, ClientLevel level, Vec3 p, double r, Predicate<Entity> which) {
        Entity best = null; double bd = Double.MAX_VALUE;
        for (Entity e : level.getEntities(self, new AABB(p, p).inflate(r + 2.0), which)) {
            if (e.getBoundingBox().inflate(r).contains(p.x, p.y, p.z)) {
                double d = e.getBoundingBox().getCenter().distanceToSqr(p);
                if (d < bd) { bd = d; best = e; }
            }
        }
        return best;
    }

    // The target auto aim picks up: the one the reticle covers, else the one the view is nearest to aiming at
    // (aimGap), within its pickup width (acquireDeg), in line of sight and in reach of the shot being aimed. All the
    // client's entities are looked at (a box query this wide would walk thousands of empty sections).
    private static Entity acquire(LocalPlayer p, ClientLevel level, Vec3 eye, Vec3 spawn, Spec aim, double lead,
                                  Entity covered, Predicate<Entity> hurtable) {
        if (covered != null && visible(level, p, eye, covered)) return covered;
        Vec3 look = p.getLookAngle();
        Entity best = null; double bestRatio = 1.0;   // gap as a share of the target's own pickup width
        for (Entity e : level.entitiesForRendering()) {
            if (e == p || !hurtable.test(e)) continue;
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist < 1e-3 || dist > AIM_RANGE || to.scale(1.0 / dist).dot(look) < SEARCH_COS) continue;
            if (!visible(level, p, eye, e)) continue;
            Ballistics.Solution s = solve(p, spawn, e, aim, lead);
            if (Math.abs(s.miss()) > REACH_MISS) continue;   // beyond the weapon's reach
            double ratio = aimGap(look, to.scale(1.0 / dist), Ballistics.dirOf(s.yaw(), s.pitch())) / acquireDeg(level, e, dist);
            if (ratio < bestRatio) { bestRatio = ratio; best = e; }
        }
        return best;
    }

    // How close the view must come to aiming at a target to pick it up, degrees. On the ground (or in water), the
    // reticle's receptive field: the ring's angular radius plus the target's half width as seen from here, so the
    // crosshair on the mob picks it up and a few degrees beside it doesn't. In the air (no block just under its
    // feet), wider. Not onGround(): the client learns that only from movement packets, so a mob standing still
    // reads as airborne.
    private static double acquireDeg(ClientLevel level, Entity e, double dist) {
        boolean airborne = !e.isInWater() && level.noBlockCollision(e, e.getBoundingBox().move(0, -0.25, 0));
        if (airborne) return AIR_ACQUIRE_DEG;
        return RING_DEG + Math.toDegrees(Math.atan(e.getBbWidth() / 2 / dist));
    }

    // How far the view is from aiming at a target, degrees: its distance to the arc from the target's centre to its
    // solved aim. Pointing at the target, at the aim point or anywhere between counts as on it: a far target's shot
    // needs the view well above it (about 7 degrees at 40 blocks), and the view travels along this arc as auto aim
    // lifts it.
    private static double aimGap(Vec3 look, Vec3 toTarget, Vec3 solved) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i <= ARC_STEPS; i++) {
            double t = i / (double) ARC_STEPS;
            Vec3 d = toTarget.scale(1 - t).add(solved.scale(t));
            double len = d.length();
            if (len > 1e-9) best = Math.min(best, Math.toDegrees(Math.acos(Mth.clamp(look.dot(d) / len, -1.0, 1.0))));
        }
        return best;
    }

    // Whether auto aim keeps its target: still there, still one the shot can hurt, in range and in line of sight.
    // (The caller also lets it go once the view is more than HOLD_DEG off its solved aim.)
    private static boolean holds(Entity e, LocalPlayer p, ClientLevel level, Vec3 eye, Predicate<Entity> hurtable) {
        if (e == null || e.isRemoved() || !e.isAlive() || e.level() != level || !hurtable.test(e)) return false;
        return e.getBoundingBox().getCenter().distanceTo(eye) <= AIM_RANGE && visible(level, p, eye, e);
    }

    // In view: a clear line from the eye to the target's centre or to its eyes (a target behind a low wall shows
    // its head). The reticle's ring can reach through a thin wall to a target behind it; this keeps those out.
    private static boolean visible(ClientLevel level, LocalPlayer p, Vec3 eye, Entity e) {
        return losClear(level, p, eye, e.getBoundingBox().getCenter()) || losClear(level, p, eye, e.getEyePosition());
    }

    private static boolean losClear(ClientLevel level, Entity self, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self)).getType() == HitResult.Type.MISS;
    }

    // A target's motion per tick (a dragon part moves with its parent).
    private static Vec3 motion(Entity target) {
        Entity m = target instanceof EnderDragonPart part ? part.parentMob : target;
        return new Vec3(m.getX() - m.xOld, m.getY() - m.yOld, m.getZ() - m.zOld);
    }

    // How a target moves on, for every lead (the solve, the reticle's flights, the hit chance): straight on at its
    // motion, falling back down for one gravity acts on. Once a tick per target.
    private static Ballistics.Course course(Level level, Entity target) {
        return courses.computeIfAbsent(target.getId(), id -> {
            Vec3 v = motion(target);
            if (!(target instanceof LivingEntity le) || !falls(le)) return Ballistics.Course.straight(v);
            return new Ballistics.Course(v, true, le.getGravity(), le.hasEffect(MobEffects.SLOW_FALLING), floor(level, le));
        });
    }

    // Whether gravity moves a target as LivingEntity.travelInAir does (26.1.2), so that its lead comes back down rather
    // than going on in a straight line. Not: no gravity (the NoGravity flag, synced; fliers under FlyingMoveControl and
    // vexes set it); an elytra glide (travelFallFlying); water or lava (travelInFluid); riding (the mount moves it); a
    // ladder or vine (handleOnClimbable caps the fall, and a climb rises at a steady rate); levitation; mobs that fly by
    // a travel of their own without gravity (phantoms, ghasts, happy ghasts and allays: travelFlying), bees and parrots
    // (FlyingAnimal); mobs that damp their own fall each tick (bats, blazes, the wither); the ender dragon; and creative
    // players, who may be flying (the server doesn't send other players' abilities). Effects of other entities aren't
    // sent either, so levitation and slow falling are seen only on the player's own mount.
    private static boolean falls(LivingEntity e) {
        if (e.getGravity() <= 0 || e.isFallFlying() || e.isInWater() || e.isInLava() || e.isPassenger() || e.onClimbable()
            || e.hasEffect(MobEffects.LEVITATION)) return false;
        if (e instanceof Phantom || e instanceof Ghast || e instanceof HappyGhast || e instanceof Allay || e instanceof FlyingAnimal
            || e instanceof Bat || e instanceof Blaze || e instanceof WitherBoss || e instanceof EnderDragon) return false;
        return !(e instanceof Player pl && pl.isCreative());
    }

    // The floor under a falling target: how far (0 or less) its box can move straight down before it lands, by the
    // game's own collision (Entity.collideBoundingBox, as Entity.move uses it); negative infinity if there is none within
    // FLOOR_FAR. Read under the target where it is now, not under its led position: a jump or a short fall lasts 10 to
    // 30 ticks, in which a running target covers a few blocks and mostly lands about as high as it left, while reading
    // along the led path would cost a read for every tick of flight for every target, and the straight lead across is
    // itself a guess past a few blocks.
    private static double floor(Level level, LivingEntity e) {
        AABB box = e.getBoundingBox();
        double near = Entity.collideBoundingBox(e, new Vec3(0, -FLOOR_NEAR, 0), box, level, List.of()).y;
        if (near > -FLOOR_NEAR) return near;
        double far = Entity.collideBoundingBox(e, new Vec3(0, -FLOOR_FAR, 0), box, level, List.of()).y;
        return far > -FLOOR_FAR ? far : Double.NEGATIVE_INFINITY;
    }

    // A bow or trident fires on a release, which carries no rotation; a crossbow on a use packet, which does.
    private static boolean firedByRelease(ItemStack item) {
        return item.is(Items.BOW) || item.is(Items.TRIDENT);
    }

    // Ticks between the target position the client shows and the target's position when the shot spawns, before
    // the flight itself: the drawing lag, the ticks from this tick's turn to the shot it aims (Ballistics.ticksToShot),
    // and the round trip (the server's measure of it, from the player list).
    private static double leadTicks(Minecraft mc, LocalPlayer p, ItemStack item) {
        double t = INTERP_TICKS + Ballistics.ticksToShot(firedByRelease(item));
        ClientPacketListener conn = mc.getConnection();
        PlayerInfo info = conn != null ? conn.getPlayerInfo(p.getUUID()) : null;
        if (info != null) t += info.getLatency() / 50.0;
        return t;
    }

    // Ballistic solve with target lead, for a target entity, from where it will be when the shot spawns, at the box
    // centre moved to the best point AimAdvisor last found for it.
    private static Ballistics.Solution solve(LocalPlayer p, Vec3 spawn, Entity target, Spec spec, double lead) {
        Ballistics.Course c = course(p.level(), target);
        Vec3 center = target.getBoundingBox().getCenter().add(c.at(lead))
                            .add(AimAdvisor.aimOffset(target, spawn, p.level().getGameTime()));
        return Ballistics.solve(spawn, center, c, lead, spec, shooterMotion(p, spec), p.onGround());
    }

    private static void dropLock() { aiming = false; lock = null; haveLastSol = false; }

    // A vanilla client turns only by the mouse, which turns the view only with the window focused and no screen
    // open (MouseHandler.handleAccumulatedMovement; a screen, the pause menu included, releases the mouse), so
    // auto aim turns, and holds a target, only then too.
    private static boolean canTurn(Minecraft mc) {
        return mc.screen == null && mc.getOverlay() == null && mc.isWindowActive() && !mc.isPaused();
    }

    // Whether the pilot moved the view since the last tick (any turn but ours, which is recorded after it is made):
    // that lets go of a held target, and a new one is picked up only once the view has rested SETTLE_TICKS, so
    // sweeping the crosshair across a mob doesn't grab the view.
    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) { haveLast = false; stillTicks = 0; }
        else {
            boolean moved = haveLast && (Math.abs(Mth.wrapDegrees(p.getYRot() - lastYaw)) > MOUSE_EPS
                                         || Math.abs(p.getXRot() - lastPitch) > MOUSE_EPS);
            stillTicks = moved ? 0 : Math.min(stillTicks + 1, SETTLE_TICKS);
            if (moved) dropLock();
        }
        aimTick(mc);
        if (mc.player != null) { lastYaw = mc.player.getYRot(); lastPitch = mc.player.getXRot(); haveLast = true; }
    }

    private static void aimTick(Minecraft mc) {
        LocalPlayer p = mc.player; ClientLevel level = mc.level;
        if (p == null || level == null) { dropLock(); valid = false; return; }
        if (!Settings.projectileReticle && !Settings.autoAim) { dropLock(); valid = false; return; }

        valid = false; hitBox = null; hitId = -1; noDamage = null;
        courses.clear();
        Held held = heldShot(p);
        if (held == null) { dropLock(); return; }
        Spec spec = held.spec();

        Vec3 eye = p.getEyePosition();
        // where the server spawns the shot this tick's view aims (a bow or trident: one tick of the player's motion on)
        Vec3 spawn = Ballistics.spawnPoint(eye, shooterMotion(p, spec), firedByRelease(held.item()));

        // Weapons fire arrows or tridents. Some targets they hit take no damage (NoDamage); the reticle still
        // shows those, and auto aim leaves them alone, as it does your own pets (ownPet).
        Predicate<Entity> hittable = hittable(p, spec.weapon());
        Predicate<Entity> targets = targetable(p, hittable);
        Holder<DamageType> type = damageType(level, held.item());
        boolean piercing = Enchant.level(held.item(), Enchantments.PIERCING) > 0;
        Predicate<Entity> hurtable = targets.and(e -> NoDamage.reason(e, spawn, type, piercing) == null && !ownPet(p, e));
        double lead = spec.weapon() ? leadTicks(mc, p, held.item()) : 0;
        Ballistics.Collider<Entity> collider = collider(level, p, movers(level, p, eye, hittable), lead);

        // aim assist, weapons only (bow, crossbow, trident), with target lead
        Entity locked = null, pending = null;   // pending: the target about to be picked up, shown while the view settles
        if (!spec.weapon() || !Settings.autoAim || !canTurn(mc)) dropLock();
        else {
            // The held target stays held from tick to tick; leading it moves the shot off where it stands now, so
            // what the reticle covers can't be what keeps it. Otherwise pick one up.
            Entity tgt = holds(lock, p, level, eye, hurtable) ? lock : null;
            boolean kept = tgt != null;
            if (!kept) {
                Ballistics.Flight<Entity> s0 = Ballistics.simulate(spawn, launchVel(p, p.getYRot(), p.getXRot(), spec), spec, collider, RETICLE_TICKS);
                Entity covered = null;
                if (s0.impact() != null) {
                    double r0 = Math.max(MIN_R, eye.distanceTo(s0.impact()) * ANG_PER_UNC * SPREAD_MULT);
                    covered = s0.entity() != null && hurtable.test(s0.entity()) ? s0.entity()
                            : bestTarget(p, level, s0.impact(), r0, hurtable);
                }
                tgt = acquire(p, level, eye, spawn, held.aim(), lead, covered, hurtable);
                // picked up only once the view has rested; until then it is shown, not turned to
                if (stillTicks < SETTLE_TICKS) { pending = tgt; tgt = null; }
            }
            Ballistics.Solution sol = tgt == null ? null : solve(p, spawn, tgt, held.aim(), lead);
            // a held target whose aim jumps away (a teleport) or that goes behind you is let go, not chased
            if (kept && aimGap(p.getLookAngle(), tgt.getBoundingBox().getCenter().subtract(eye).normalize(),
                               Ballistics.dirOf(sol.yaw(), sol.pitch())) > HOLD_DEG) sol = null;
            if (sol == null) dropLock();
            else if (Math.abs(sol.miss()) > REACH_MISS) {
                // held, but out of reach: hold the view still rather than lob toward it
                aiming = true;
                lock = tgt; locked = tgt; haveLastSol = false;
            } else {
                float yaw = (float) sol.yaw(), pitch = (float) sol.pitch();
                boolean same = tgt == lock && haveLastSol;
                double count = MouseTurn.sens(mc, p) * 0.15;   // degrees per mouse count
                float dYaw = AimControl.step(Mth.wrapDegrees(yaw - p.getYRot()), same ? Mth.wrapDegrees(yaw - lastSolYaw) : 0f, count);
                float dPitch = AimControl.step(pitch - p.getXRot(), same ? pitch - lastSolPitch : 0f, count);
                // turned as a mouse would, by whole counts, so the rotation sent is one a vanilla client could send
                MouseTurn.toward(p, p.getYRot() + dYaw, Mth.clamp(p.getXRot() + dPitch, -90f, 90f));
                aiming = true;
                lock = tgt; locked = tgt;
                lastSolYaw = yaw; lastSolPitch = pitch; haveLastSol = true;
            }
        }

        // final reticle, after any aim correction this tick
        Ballistics.Flight<Entity> sf = Ballistics.simulate(spawn, launchVel(p, p.getYRot(), p.getXRot(), spec), spec, collider, RETICLE_TICKS);
        Entity tgtF = locked != null ? locked : pending;
        double r = 0;
        boolean onTarget = false;
        if (sf.impact() != null) {
            r = Math.max(MIN_R, eye.distanceTo(sf.impact()) * ANG_PER_UNC * SPREAD_MULT);
            if (tgtF == null) tgtF = sf.entity() != null ? sf.entity() : bestTarget(p, level, sf.impact(), r, targets);
            onTarget = tgtF != null && (sf.entity() == tgtF || tgtF.getBoundingBox()
                .move(course(level, tgtF).at(lead + sf.ticks())).inflate(r).contains(sf.impact()));
        }
        // the hit chance of the shot as aimed now, and the best point to aim at while auto aim holds a target;
        // for a target the shot can't hurt, the reason instead
        String why = spec.weapon() && tgtF != null ? NoDamage.reason(tgtF, spawn, type, piercing) : null;
        if (spec.weapon() && tgtF != null && why == null)
            AimAdvisor.update(level, spawn, Ballistics.launchDir(p.getYRot(), p.getXRot(), spec), spec, held.aim(),
                              shooterMotion(p, spec), p.onGround(), tgtF, course(level, tgtF), locked != null, lead);
        // the target's box and readout show whether or not the ring has somewhere to land (a held target picked up
        // by the crosshair, while the shot as aimed now still falls short or flies off)
        hitBox = tgtF != null ? tgtF.getBoundingBox() : null;
        hitId = tgtF != null && spec.weapon() ? tgtF.getId() : -1;
        noDamage = why;
        ringOnTarget = onTarget;
        if (sf.impact() == null) return;
        impact = sf.impact(); normal = sf.normal();
        radius = r;
        valid = true;
    }

    // Drawn as a HUD overlay rather than in the world: the ring and target box are computed in world space
    // in tick() and projected here, so they show on top of terrain (there is no line render type without
    // depth testing to draw them in the world).
    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if ((!valid && hitBox == null) || !Settings.projectileReticle) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        Matrix4f vp = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Vec3 cam = camera.position();
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        int targetColor = noDamage != null ? COLOR_NO_DAMAGE : COLOR_ENTITY;
        if (valid) drawRing(g, vp, cam, W, H, ringOnTarget ? targetColor : COLOR_RING);
        if (hitBox != null) boxProjected(g, vp, cam, W, H, hitBox, targetColor);

        // beside the crosshair: why the shot can't hurt the target, or else its hit chance as aimed now
        if (noDamage != null) g.text(mc.font, noDamage, W / 2 + 6, H / 2 + 4, COLOR_NO_DAMAGE, true);
        else if (hitId >= 0 && mc.level != null) {
            double c = AimAdvisor.chance(hitId, mc.level.getGameTime());
            if (!Double.isNaN(c)) g.text(mc.font, Math.round(c * 100) + "%", W / 2 + 6, H / 2 + 4, COLOR_ENTITY, true);
        }
    }

    // The spread ring on the impact surface: green when it covers the shown target, else white.
    private static void drawRing(GuiGraphicsExtractor g, Matrix4f vp, Vec3 cam, int W, int H, int ringColor) {
        Vec3 n = normal.normalize();
        Vec3 u = (Math.abs(n.y) < 0.99 ? n.cross(new Vec3(0, 1, 0)) : n.cross(new Vec3(1, 0, 0))).normalize();
        Vec3 w = n.cross(u).normalize();
        Vec3 ic = impact.add(n.scale(0.01));
        // Walk the ring, drawing a segment only once the projected point has moved at least a pixel. At
        // shallow angles the ellipse's tips bunch into sub-pixel steps that would round to nothing and
        // split the ring into two arcs.
        float[] anchor = null;
        for (int i = 0; i <= RING_SEGS; i++) {
            double a = (Math.PI * 2 * i) / RING_SEGS;
            Vec3 p = ic.add(u.scale(Math.cos(a) * radius)).add(w.scale(Math.sin(a) * radius));
            float[] s = ScreenProjection.project(vp, cam, p.x, p.y, p.z, W, H, NEAR_W);
            if (s == null) { anchor = null; continue; }        // point behind the camera: break the ring
            if (anchor == null) { anchor = s; continue; }
            float ddx = s[0] - anchor[0], ddy = s[1] - anchor[1];
            if (ddx * ddx + ddy * ddy >= MIN_SEG_PX2) {        // long enough to draw
                line2d(g, anchor[0], anchor[1], s[0], s[1], ringColor);
                anchor = s;
            }                                                  // else keep the anchor and bridge to a later point
        }
    }

    private static void boxProjected(GuiGraphicsExtractor g, Matrix4f vp, Vec3 cam, int W, int H, AABB bb, int color) {
        Vec3[] c = {
            new Vec3(bb.minX, bb.minY, bb.minZ), new Vec3(bb.maxX, bb.minY, bb.minZ),
            new Vec3(bb.maxX, bb.minY, bb.maxZ), new Vec3(bb.minX, bb.minY, bb.maxZ),
            new Vec3(bb.minX, bb.maxY, bb.minZ), new Vec3(bb.maxX, bb.maxY, bb.minZ),
            new Vec3(bb.maxX, bb.maxY, bb.maxZ), new Vec3(bb.minX, bb.maxY, bb.maxZ)
        };
        int[][] edges = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
        for (int[] e : edges) line3d(g, vp, cam, W, H, c[e[0]], c[e[1]], color);
    }

    // Draw a world-space segment as a screen line, clipped at the camera's near plane so a segment that
    // crosses behind the camera doesn't project to infinity.
    private static void line3d(GuiGraphicsExtractor g, Matrix4f vp, Vec3 cam, int W, int H, Vec3 A, Vec3 B, int color) {
        Vector4f a = ScreenProjection.clip(vp, cam, A.x, A.y, A.z);
        Vector4f b = ScreenProjection.clip(vp, cam, B.x, B.y, B.z);
        boolean ain = a.w > NEAR_W, bin = b.w > NEAR_W;
        if (!ain && !bin) return;                                     // the whole segment is behind the near plane
        if (!ain)      a = lerp4(a, b, (NEAR_W - a.w) / (b.w - a.w));  // move end A forward to the near plane
        else if (!bin) b = lerp4(b, a, (NEAR_W - b.w) / (a.w - b.w));  // move end B forward to the near plane
        float[] sa = ScreenProjection.toScreen(a, W, H), sb = ScreenProjection.toScreen(b, W, H);
        line2d(g, sa[0], sa[1], sb[0], sb[1], color);
    }

    private static Vector4f lerp4(Vector4f a, Vector4f b, float t) {
        return new Vector4f(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t);
    }

    /** Thin screen-space line between two points, as a rotated fill. */
    private static void line2d(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int color) {
        HudDraw.alongSegment(g, x1, y1, x2, y2, 0.5f, hl -> g.fill(-hl, 0, hl, LINE_PX, color));
    }
}
