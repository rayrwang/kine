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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

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
    private static final int    COOLDOWN_TICKS = 10;     // ticks aim stays off after you look away
    private static final double ACQUIRE_DEG    = 6.0;    // a target is picked up when the view is this close to aiming at it (aimGap)
    private static final double HOLD_DEG       = 10.0;   // and kept while the view stays this close
    private static final int    ARC_STEPS      = 16;     // points along aimGap's arc
    private static final double REACH_MISS     = 0.5;    // blocks: a solved shot that passes further from the led point can't reach it
    private static final double AIM_RANGE      = 128.0;  // farthest target picked up or kept (blocks)
    private static final double SEARCH_COS     = Math.cos(Math.toRadians(60.0));   // cut before the solve: a long shot aims up to about 45 degrees above its target
    // How much older the client's view of a target is than where the target will be when the shot spawns, besides
    // the round trip: a living entity is drawn 2 ticks behind the last position the server sent (InterpolationHandler's
    // 3 steps, at the 2 or 3 tick update interval), and a bow or trident fires with the rotation sent the tick before
    // its release (the release packet carries none, and handleKeybinds runs before the tick's move packet).
    private static final double INTERP_TICKS   = 2.0;

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

    // aim state
    private static boolean aiming = false;
    private static float lastAimYaw, lastAimPitch;
    private static int cooldown = 0;
    private static Entity lock = null;                // the target auto aim holds, kept from tick to tick
    private static float lastSolYaw, lastSolPitch;    // its solved aim last tick, for the turn's feed-forward
    private static boolean haveLastSol = false;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ProjectileTargeting::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "projectile_reticle"),
            ProjectileTargeting::render);
    }

    // which projectile, its flight parameters, and the item it comes from
    private record Held(Spec spec, ItemStack item) {}

    private static Held heldShot(LocalPlayer p) {
        if (p.isUsingItem()) {
            ItemStack use = p.getUseItem();
            if (use.is(Items.BOW)) {
                float t = p.getTicksUsingItem() / 20f;
                float pow = (t * t + 2f * t) / 3f;
                if (pow > 1f) pow = 1f;
                if (pow < 0.1f) return null;
                return new Held(new Spec(0.05, 0.99F, pow * 3.0, 0.0, false, true), use);   // arrow
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

    // What the projectile can hit (Projectile.canHitEntity): live, pickable mobs and players, and dragon parts.
    // Arrows and tridents pass through players the shooter's team can't harm (AbstractArrow.canHitEntity).
    private static Predicate<Entity> hittable(LocalPlayer self, boolean arrow) {
        return e -> {
            if (e instanceof EnderDragonPart) return true;   // dragon hitboxes aren't LivingEntity
            if (!(e instanceof LivingEntity) || !e.canBeHitByProjectile()) return false;
            return !(arrow && e instanceof Player other && !self.canHarmPlayer(other));
        };
    }

    // the damage an arrow or trident from this item does, for the shield rules
    private static Holder<DamageType> damageType(ClientLevel level, ItemStack item) {
        return level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                    .getOrThrow(item.is(Items.TRIDENT) ? DamageTypes.TRIDENT : DamageTypes.ARROW);
    }

    // Stops a simulated flight at the first block or entity (the reticle).
    private static Ballistics.Collider<Entity> collider(ClientLevel level, Entity self, Predicate<Entity> hittable) {
        return (pos, next, tick) -> {
            BlockHitResult bhr = level.clip(new ClipContext(pos, next,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
            Vec3 segEnd = bhr.getType() != HitResult.Type.MISS ? bhr.getLocation() : next;

            Entity ne = null; Vec3 np = null; double best = Double.MAX_VALUE;
            for (Entity e : level.getEntities(self, new AABB(pos, segEnd).inflate(2.0), hittable)) {
                Optional<Vec3> h = entityHit(level, self, e.getBoundingBox(), pos, segEnd, HitChance.margin(tick));
                if (h.isPresent()) {
                    double d = pos.distanceToSqr(h.get());
                    if (d < best) { best = d; ne = e; np = h.get(); }
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
    // (aimGap), within ACQUIRE_DEG, in line of sight and in reach of the draw as it is now.
    private static Entity acquire(LocalPlayer p, ClientLevel level, Vec3 eye, Vec3 spawn, Spec spec, double lead,
                                  Entity covered, Predicate<Entity> hurtable) {
        if (covered != null) return covered;
        Vec3 look = p.getLookAngle();
        Entity best = null; double bestGap = ACQUIRE_DEG;
        for (Entity e : level.getEntities(p, new AABB(eye, eye).inflate(AIM_RANGE), hurtable)) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist < 1e-3 || dist > AIM_RANGE || to.scale(1.0 / dist).dot(look) < SEARCH_COS) continue;
            if (!losClear(level, p, eye, e.getBoundingBox().getCenter())) continue;
            Ballistics.Solution s = solve(p, spawn, e, spec, lead);
            if (Math.abs(s.miss()) > REACH_MISS) continue;   // a bow not drawn far enough yet
            double gap = aimGap(look, to.scale(1.0 / dist), Ballistics.dirOf(s.yaw(), s.pitch()));
            if (gap < bestGap) { bestGap = gap; best = e; }
        }
        return best;
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
        Vec3 c = e.getBoundingBox().getCenter();
        return c.distanceTo(eye) <= AIM_RANGE && losClear(level, p, eye, c);
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

    // Ticks between the target position the client shows and the target's position when the shot spawns, before
    // the flight itself: the drawing lag, the tick a release waits for its rotation, and the round trip (the
    // server's measure of it, from the player list).
    private static double leadTicks(Minecraft mc, LocalPlayer p, ItemStack item) {
        double t = INTERP_TICKS + (item.is(Items.CROSSBOW) ? 0 : 1);   // a crossbow fires with a use packet, which carries its rotation
        ClientPacketListener conn = mc.getConnection();
        PlayerInfo info = conn != null ? conn.getPlayerInfo(p.getUUID()) : null;
        if (info != null) t += info.getLatency() / 50.0;
        return t;
    }

    // Ballistic solve with target lead, for a target entity, from where it will be when the shot spawns, at the box
    // centre moved to the best point AimAdvisor last found for it.
    private static Ballistics.Solution solve(LocalPlayer p, Vec3 spawn, Entity target, Spec spec, double lead) {
        Vec3 tv = motion(target);
        Vec3 center = target.getBoundingBox().getCenter().add(tv.scale(lead))
                            .add(AimAdvisor.aimOffset(target, spawn, p.level().getGameTime()));
        return Ballistics.solve(spawn, center, tv, spec, shooterMotion(p, spec), p.onGround());
    }

    private static void dropLock() { aiming = false; lock = null; haveLastSol = false; }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player; ClientLevel level = mc.level;
        if (p == null || level == null) { dropLock(); valid = false; return; }
        if (!Settings.projectileReticle && !Settings.autoAim) { dropLock(); valid = false; return; }

        valid = false; hitBox = null; hitId = -1; noDamage = null;
        Held held = heldShot(p);
        if (held == null) { dropLock(); return; }
        Spec spec = held.spec();

        Vec3 eye = p.getEyePosition();
        Vec3 spawn = new Vec3(eye.x, p.getEyeY() - 0.1F, eye.z);   // where the server spawns the projectile

        // Weapons fire arrows or tridents. Some targets they hit take no damage (NoDamage); the reticle still
        // shows those, and auto aim leaves them alone.
        Predicate<Entity> hittable = hittable(p, spec.weapon());
        Holder<DamageType> type = damageType(level, held.item());
        boolean piercing = Enchant.level(held.item(), Enchantments.PIERCING) > 0;
        Predicate<Entity> hurtable = hittable.and(e -> NoDamage.reason(e, spawn, type, piercing) == null);
        Ballistics.Collider<Entity> collider = collider(level, p, hittable);
        double lead = spec.weapon() ? leadTicks(mc, p, held.item()) : 0;

        // aim assist, weapons only (bow, crossbow, trident), with target lead
        Entity locked = null;
        boolean overridden = aiming && (Math.abs(Mth.wrapDegrees(p.getYRot() - lastAimYaw)) > MOUSE_EPS
                                     || Math.abs(p.getXRot() - lastAimPitch) > MOUSE_EPS);
        if (overridden) { cooldown = COOLDOWN_TICKS; dropLock(); }
        if (cooldown > 0) { cooldown--; dropLock(); }
        else if (!spec.weapon() || !Settings.autoAim) dropLock();
        else {
            // The held target stays held from tick to tick; leading it moves the shot off where it stands now, so
            // what the reticle covers can't be what keeps it. Otherwise pick one up.
            Entity tgt = holds(lock, p, level, eye, hurtable) ? lock : null;
            boolean kept = tgt != null;
            if (!kept) {
                Ballistics.Flight<Entity> s0 = Ballistics.simulate(spawn, launchVel(p, p.getYRot(), p.getXRot(), spec), spec, collider);
                Entity covered = null;
                if (s0.impact() != null) {
                    double r0 = Math.max(MIN_R, eye.distanceTo(s0.impact()) * ANG_PER_UNC * SPREAD_MULT);
                    covered = bestTarget(p, level, s0.impact(), r0, hurtable);
                }
                tgt = acquire(p, level, eye, spawn, spec, lead, covered, hurtable);
            }
            Ballistics.Solution sol = tgt == null ? null : solve(p, spawn, tgt, spec, lead);
            // a held target whose aim jumps away (a teleport) or that goes behind you is let go, not chased
            if (kept && aimGap(p.getLookAngle(), tgt.getBoundingBox().getCenter().subtract(eye).normalize(),
                               Ballistics.dirOf(sol.yaw(), sol.pitch())) > HOLD_DEG) sol = null;
            if (sol == null) dropLock();
            else if (Math.abs(sol.miss()) > REACH_MISS) {
                // held, but out of reach of the draw as it is now: hold the view still rather than lob toward it
                lastAimYaw = p.getYRot(); lastAimPitch = p.getXRot(); aiming = true;
                lock = tgt; locked = tgt; haveLastSol = false;
            } else {
                float yaw = (float) sol.yaw(), pitch = (float) sol.pitch();
                boolean same = tgt == lock && haveLastSol;
                double count = MouseTurn.sens(mc, p) * 0.15;   // degrees per mouse count
                float dYaw = AimControl.step(Mth.wrapDegrees(yaw - p.getYRot()), same ? Mth.wrapDegrees(yaw - lastSolYaw) : 0f, count);
                float dPitch = AimControl.step(pitch - p.getXRot(), same ? pitch - lastSolPitch : 0f, count);
                // turned as a mouse would, by whole counts, so the rotation sent is one a vanilla client could send
                MouseTurn.toward(p, p.getYRot() + dYaw, Mth.clamp(p.getXRot() + dPitch, -90f, 90f));
                lastAimYaw = p.getYRot(); lastAimPitch = p.getXRot(); aiming = true;
                lock = tgt; locked = tgt;
                lastSolYaw = yaw; lastSolPitch = pitch; haveLastSol = true;
            }
        }

        // final reticle, after any aim correction this tick
        Ballistics.Flight<Entity> sf = Ballistics.simulate(spawn, launchVel(p, p.getYRot(), p.getXRot(), spec), spec, collider);
        Entity tgtF = locked;
        double r = 0;
        if (sf.impact() != null) {
            r = Math.max(MIN_R, eye.distanceTo(sf.impact()) * ANG_PER_UNC * SPREAD_MULT);
            if (tgtF == null) tgtF = bestTarget(p, level, sf.impact(), r, hittable);
        }
        // the hit chance of the shot as aimed now, and the best point to aim at while auto aim holds a target;
        // for a target the shot can't hurt, the reason instead
        String why = spec.weapon() && tgtF != null ? NoDamage.reason(tgtF, spawn, type, piercing) : null;
        if (spec.weapon() && tgtF != null && why == null)
            AimAdvisor.update(level, spawn, Ballistics.launchDir(p.getYRot(), p.getXRot(), spec), spec,
                              shooterMotion(p, spec), p.onGround(), tgtF, locked != null, lead);
        if (sf.impact() == null) return;
        impact = sf.impact(); normal = sf.normal();
        radius = r; hitBox = tgtF != null ? tgtF.getBoundingBox() : null;
        hitId = tgtF != null && spec.weapon() ? tgtF.getId() : -1;
        noDamage = why;
        valid = true;
    }

    // Drawn as a HUD overlay rather than in the world: the ring and target box are computed in world space
    // in tick() and projected here, so they show on top of terrain (there is no line render type without
    // depth testing to draw them in the world).
    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!valid || !Settings.projectileReticle) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        Matrix4f vp = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Vec3 cam = camera.position();
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        // spread ring on the impact surface, green if it covers a target, else white
        Vec3 n = normal.normalize();
        Vec3 u = (Math.abs(n.y) < 0.99 ? n.cross(new Vec3(0, 1, 0)) : n.cross(new Vec3(1, 0, 0))).normalize();
        Vec3 w = n.cross(u).normalize();
        Vec3 ic = impact.add(n.scale(0.01));
        int targetColor = noDamage != null ? COLOR_NO_DAMAGE : COLOR_ENTITY;
        int ringColor = hitBox != null ? targetColor : COLOR_RING;
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
        if (hitBox != null) boxProjected(g, vp, cam, W, H, hitBox, targetColor);

        // beside the crosshair: why the shot can't hurt the target, or else its hit chance as aimed now
        if (noDamage != null) g.text(mc.font, noDamage, W / 2 + 6, H / 2 + 4, COLOR_NO_DAMAGE, true);
        else if (hitId >= 0 && mc.level != null) {
            double c = AimAdvisor.chance(hitId, mc.level.getGameTime());
            if (!Double.isNaN(c)) g.text(mc.font, Math.round(c * 100) + "%", W / 2 + 6, H / 2 + 4, COLOR_ENTITY, true);
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
