package ai.rrw.kine.combat;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.combat.Ballistics.Spec;
import ai.rrw.kine.hud.HudDraw;
import ai.rrw.kine.hud.ScreenProjection;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Optional;

public class ProjectileTargeting {

    // simulation and reticle
    private static final double SPREAD_MULT = 2.0;
    private static final double ANG_PER_UNC = 0.0172275;
    private static final double MIN_R       = 0.15;
    private static final int    RING_SEGS   = 48;
    private static final double ENTITY_PAD  = 0.3;   // hitbox inflation when testing the path against entities
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


    // reticle state, written each tick and read each frame
    private static boolean valid = false;
    private static Vec3 impact = Vec3.ZERO;
    private static Vec3 normal = new Vec3(0, 1, 0);
    private static double radius = 0;
    private static AABB hitBox = null;

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

    // which projectile, and its flight parameters
    private static Spec specFor(LocalPlayer p) {
        if (p.isUsingItem()) {
            var it = p.getUseItem().getItem();
            if (it == Items.BOW) {
                float t = p.getTicksUsingItem() / 20f;
                float pow = (t * t + 2f * t) / 3f;
                if (pow > 1f) pow = 1f;
                if (pow < 0.1f) return null;
                return new Spec(0.05, 0.99, pow * 3.0, 0.0, false, true);   // arrow
            }
            if (it == Items.TRIDENT) return new Spec(0.05, 0.99, 2.5, 0.0, false, true);
        }
        Spec s = specForHeld(p.getMainHandItem());
        return s != null ? s : specForHeld(p.getOffhandItem());
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
            return new Spec(0.05, 0.99, 3.15, 0.0, false, true);          // arrow
        }
        return null;
    }

    // The shooter's motion is the per-tick position change: getKnownMovement() and getDeltaMovement()
    // read low for the local player.
    private static Vec3 shooterMotion(LocalPlayer p) {
        return new Vec3(p.getX() - p.xOld, p.getY() - p.yOld, p.getZ() - p.zOld);
    }

    private static Vec3 launchVel(LocalPlayer p, double yaw, double pitch, Spec spec) {
        return Ballistics.launchVel(yaw, pitch, spec, shooterMotion(p), p.onGround());
    }

    private static boolean canHit(Entity e) {
        if (e instanceof EnderDragonPart) return true;   // dragon hitboxes aren't LivingEntity
        return e instanceof LivingEntity && e.isAlive() && !e.isSpectator();
    }

    // Stops a simulated flight at the first block or entity (the reticle).
    private static Ballistics.Collider<Entity> collider(ClientLevel level, Entity self) {
        return (pos, next, tick) -> {
            BlockHitResult bhr = level.clip(new ClipContext(pos, next,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
            Vec3 segEnd = bhr.getType() != HitResult.Type.MISS ? bhr.getLocation() : next;

            Entity ne = null; Vec3 np = null; double best = Double.MAX_VALUE;
            for (Entity e : level.getEntities(self, new AABB(pos, segEnd).inflate(2.0), ProjectileTargeting::canHit)) {
                Optional<Vec3> h = e.getBoundingBox().inflate(ENTITY_PAD).clip(pos, segEnd);
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

    // the entity nearest the reticle centre p, among those the spread disc (radius r) overlaps
    private static Entity bestTarget(Entity self, ClientLevel level, Vec3 p, double r) {
        Entity best = null; double bd = Double.MAX_VALUE;
        for (Entity e : level.getEntities(self, new AABB(p, p).inflate(r + 2.0), ProjectileTargeting::canHit)) {
            if (e.getBoundingBox().inflate(r).contains(p.x, p.y, p.z)) {
                double d = e.getBoundingBox().getCenter().distanceToSqr(p);
                if (d < bd) { bd = d; best = e; }
            }
        }
        return best;
    }

    // Open-sky fallback: the entity nearest the look ray within a cone, in line of sight and range. The
    // previous target is kept while it stays inside a wider cone, to avoid flicker.
    private static Entity coneTarget(LocalPlayer p, ClientLevel level, Vec3 eye) {
        Vec3 look = p.getLookAngle();
        AABB search = new AABB(eye, eye).inflate(CONE_RANGE);

        Entity best = null; double bestCos = CONE_COS;   // most centred new target (narrow cone)
        boolean keepLock = false;                        // is the previous target still in the wide cone?

        for (Entity e : level.getEntities(p, search, ProjectileTargeting::canHit)) {
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

    // Ballistic solve with target lead, for a target entity (a dragon part leads with its parent's motion).
    private static Vec3 solveAim(LocalPlayer p, Vec3 eye, Entity target, Spec spec) {
        Vec3 center = target.getBoundingBox().getCenter();
        Entity velSrc = target instanceof EnderDragonPart part ? part.parentMob : target;
        Vec3 tv = new Vec3(velSrc.getX() - velSrc.xOld, velSrc.getY() - velSrc.yOld, velSrc.getZ() - velSrc.zOld);
        return Ballistics.solveAim(eye, center, tv, spec, shooterMotion(p), p.onGround());
    }

    private static float ease(float cur, float target, boolean wrap) {
        float delta = wrap ? Mth.wrapDegrees(target - cur) : target - cur;
        return cur + Mth.clamp(delta * AIM_SMOOTH, -AIM_MAX_STEP, AIM_MAX_STEP);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player; ClientLevel level = mc.level;
        if (p == null || level == null) { aiming = false; valid = false; return; }
        if (!Settings.projectileReticle && !Settings.autoAim) { aiming = false; valid = false; return; }

        valid = false; hitBox = null;
        Spec spec = specFor(p);
        if (spec == null) { aiming = false; return; }

        Vec3 eye = p.getEyePosition();

        // reticle from the current aim gives the candidate target
        Ballistics.Flight<Entity> s0 = Ballistics.simulate(eye, launchVel(p, p.getYRot(), p.getXRot(), spec), spec, collider(level, p));
        Entity tgt = null;
        if (s0.impact() != null) {
            double r0 = Math.max(MIN_R, eye.distanceTo(s0.impact()) * ANG_PER_UNC * SPREAD_MULT);
            tgt = bestTarget(p, level, s0.impact(), r0);
        } else if (Settings.autoAim && spec.weapon()) {
            tgt = coneTarget(p, level, eye);   // open-sky fallback: pick by angle from the look ray
        }

        // aim assist, weapons only (bow, crossbow, trident), with target lead
        Entity locked = null;
        boolean overridden = aiming && (Math.abs(Mth.wrapDegrees(p.getYRot() - lastAimYaw)) > MOUSE_EPS
                                     || Math.abs(p.getXRot() - lastAimPitch) > MOUSE_EPS);
        if (overridden) { cooldown = COOLDOWN_TICKS; aiming = false; }
        if (cooldown > 0) { cooldown--; aiming = false; }
        else if (tgt != null && spec.weapon() && Settings.autoAim) {
            Vec3 aimAt = solveAim(p, eye, tgt, spec);
            Vec3 d = aimAt.subtract(eye).normalize();
            float fyaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float fpitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-d.y, -1.0, 1.0)));
            p.setYRot(ease(p.getYRot(), fyaw, true));
            p.setXRot(Mth.clamp(ease(p.getXRot(), fpitch, false), -90f, 90f));
            lastAimYaw = p.getYRot(); lastAimPitch = p.getXRot(); aiming = true;
            locked = tgt;
        } else aiming = false;

        // final reticle, after any aim correction this tick
        Ballistics.Flight<Entity> sf = Ballistics.simulate(eye, launchVel(p, p.getYRot(), p.getXRot(), spec), spec, collider(level, p));
        if (sf.impact() == null) return;
        double r = Math.max(MIN_R, eye.distanceTo(sf.impact()) * ANG_PER_UNC * SPREAD_MULT);
        Entity tgtF = locked != null ? locked : bestTarget(p, level, sf.impact(), r);
        impact = sf.impact(); normal = sf.normal();
        radius = r; hitBox = tgtF != null ? tgtF.getBoundingBox() : null;
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
        int ringColor = hitBox != null ? COLOR_ENTITY : COLOR_RING;
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
        if (hitBox != null) boxProjected(g, vp, cam, W, H, hitBox, COLOR_ENTITY);
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
