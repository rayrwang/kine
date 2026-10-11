package ai.rrw.kine.combat;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.combat.Ballistics.Spec;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudDraw;
import ai.rrw.kine.hud.ScreenProjection;
import ai.rrw.kine.util.Enchant;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
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
import net.minecraft.world.item.component.ChargedProjectiles;
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

    // aim assist
    private static final float AIM_SMOOTH     = 0.35f; // ease fraction/tick toward solution
    private static final float AIM_MAX_STEP   = 10f;   // deg/tick cap
    private static final float MOUSE_EPS      = 0.4f;  // mouse drift (deg) that releases the lock
    private static final int   COOLDOWN_TICKS = 10;    // ticks aim stays off after you look away
    // open-sky fallback, used only when the projectile's path hits no block
    private static final double CONE_DEG      = 6.0;                              // acquisition half-angle
    private static final double CONE_COS      = Math.cos(Math.toRadians(CONE_DEG));
    private static final double CONE_HOLD_COS = Math.cos(Math.toRadians(10.0));   // wider angle to keep an existing lock
    private static final double CONE_RANGE    = 80.0;                             // max fallback acquisition distance (blocks)

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
    private static Entity coneLock = null;   // last target picked by the open-sky cone, kept while it stays in view

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
                return new Held(new Spec(0.05, 0.99, pow * 3.0, 0.0, false, true), use);   // arrow
            }
            if (use.is(Items.TRIDENT)) return new Held(new Spec(0.05, 0.99, 2.5, 0.0, false, true), use);
        }
        Spec s = specForHeld(p.getMainHandItem());
        if (s != null) return new Held(s, p.getMainHandItem());
        s = specForHeld(p.getOffhandItem());
        return s != null ? new Held(s, p.getOffhandItem()) : null;
    }

    private static Spec specForHeld(ItemStack stack) {
        var it = stack.getItem();
        if (it == Items.SNOWBALL || it == Items.EGG || it == Items.ENDER_PEARL)
            return new Spec(0.03, 0.99, 1.5, 0.0, true, false);
        if (it == Items.SPLASH_POTION || it == Items.LINGERING_POTION)
            return new Spec(0.03, 0.99, 0.5, -20.0, true, false);
        if (it == Items.EXPERIENCE_BOTTLE)
            return new Spec(0.03, 0.99, 0.7, -20.0, true, false);
        if (it == Items.CROSSBOW && CrossbowItem.isCharged(stack)) {
            ChargedProjectiles cp = stack.get(DataComponents.CHARGED_PROJECTILES);
            if (cp != null && cp.contains(Items.FIREWORK_ROCKET)) return null; // firework physics differ
            return new Spec(0.05, 0.99, 3.15, 0.0, false, true, false);   // arrow, fired along the view without the shooter's motion (CrossbowItem.shootProjectile)
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

    // Open-sky fallback: the entity nearest the look ray within a cone, in line of sight and range. The
    // previous target is kept while it stays inside a wider cone, to avoid flicker.
    private static Entity coneTarget(LocalPlayer p, ClientLevel level, Vec3 eye, Predicate<Entity> which) {
        Vec3 look = p.getLookAngle();
        AABB search = new AABB(eye, eye).inflate(CONE_RANGE);

        Entity best = null; double bestCos = CONE_COS;   // most centred new target (narrow cone)
        boolean keepLock = false;                        // is the previous target still in the wide cone?

        for (Entity e : level.getEntities(p, search, which)) {
            Vec3 c = e.getBoundingBox().getCenter();
            Vec3 to = c.subtract(eye);
            double dist = to.length();
            if (dist < 1e-3 || dist > CONE_RANGE) continue;
            double cos = to.scale(1.0 / dist).dot(look);
            if (cos < CONE_HOLD_COS) continue;            // outside even the wide cone
            if (!losClear(level, p, eye, c)) continue;    // terrain in the way
            if (e == coneLock) keepLock = true;           // previous target still visible and in the wide cone
            if (cos > bestCos) { bestCos = cos; best = e; }
        }

        if (keepLock) return coneLock;                    // stick with the current target
        coneLock = best;                                  // else take the most centred (or none)
        return best;
    }

    private static boolean losClear(ClientLevel level, Entity self, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self)).getType() == HitResult.Type.MISS;
    }

    // Ballistic solve with target lead, for a target entity (a dragon part leads with its parent's motion), at the
    // box centre moved to the best point AimAdvisor last found for it.
    private static Vec3 solveAim(LocalPlayer p, Vec3 eye, Entity target, Spec spec) {
        Vec3 center = target.getBoundingBox().getCenter().add(AimAdvisor.aimOffset(target, eye, p.level().getGameTime()));
        Entity velSrc = target instanceof EnderDragonPart part ? part.parentMob : target;
        Vec3 tv = new Vec3(velSrc.getX() - velSrc.xOld, velSrc.getY() - velSrc.yOld, velSrc.getZ() - velSrc.zOld);
        return Ballistics.solveAim(eye, center, tv, spec, shooterMotion(p, spec), p.onGround());
    }

    private static float ease(float cur, float target, boolean wrap) {
        float delta = wrap ? Mth.wrapDegrees(target - cur) : target - cur;
        return cur + Mth.clamp(delta * AIM_SMOOTH, -AIM_MAX_STEP, AIM_MAX_STEP);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player; ClientLevel level = mc.level;
        if (p == null || level == null) { aiming = false; valid = false; return; }
        if (!Settings.projectileReticle && !Settings.autoAim) { aiming = false; valid = false; return; }

        valid = false; hitBox = null; hitId = -1; noDamage = null;
        Held held = heldShot(p);
        if (held == null) { aiming = false; return; }
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

        // reticle from the current aim gives the candidate target
        Ballistics.Flight<Entity> s0 = Ballistics.simulate(spawn, launchVel(p, p.getYRot(), p.getXRot(), spec), spec, collider);
        Entity tgt = null;
        if (s0.impact() != null) {
            double r0 = Math.max(MIN_R, eye.distanceTo(s0.impact()) * ANG_PER_UNC * SPREAD_MULT);
            tgt = bestTarget(p, level, s0.impact(), r0, hurtable);
        } else if (Settings.autoAim && spec.weapon()) {
            tgt = coneTarget(p, level, eye, hurtable);   // open-sky fallback: pick by angle from the look ray
        }

        // aim assist, weapons only (bow, crossbow, trident), with target lead
        Entity locked = null;
        boolean overridden = aiming && (Math.abs(Mth.wrapDegrees(p.getYRot() - lastAimYaw)) > MOUSE_EPS
                                     || Math.abs(p.getXRot() - lastAimPitch) > MOUSE_EPS);
        if (overridden) { cooldown = COOLDOWN_TICKS; aiming = false; }
        if (cooldown > 0) { cooldown--; aiming = false; }
        else if (tgt != null && spec.weapon() && Settings.autoAim) {
            Vec3 aimAt = solveAim(p, spawn, tgt, spec);
            Vec3 d = aimAt.subtract(spawn).normalize();
            float fyaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float fpitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-d.y, -1.0, 1.0)));
            p.setYRot(ease(p.getYRot(), fyaw, true));
            p.setXRot(Mth.clamp(ease(p.getXRot(), fpitch, false), -90f, 90f));
            lastAimYaw = p.getYRot(); lastAimPitch = p.getXRot(); aiming = true;
            locked = tgt;
        } else aiming = false;

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
                              shooterMotion(p, spec), p.onGround(), tgtF, locked != null);
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
