package ai.rrw.kine.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ProjectileTargeting {

    // --- sim / reticle ---
    private static final int    MAX_TICKS   = 120;
    private static final double SPREAD_MULT = 2.0;
    private static final double ANG_PER_UNC = 0.0172275;
    private static final double MIN_R       = 0.15;
    private static final int    RING_SEGS   = 48;
    private static final float  LINE_WIDTH  = 2.0f;
    private static final double ENTITY_PAD  = 0.3;   // arrow pick inflation

    // --- aim assist ---
    private static final float AIM_SMOOTH     = 0.35f; // ease fraction/tick toward solution
    private static final float AIM_MAX_STEP   = 10f;   // deg/tick cap
    private static final float MOUSE_EPS      = 0.4f;  // mouse drift (deg) that releases the lock
    private static final int   COOLDOWN_TICKS = 10;    // ticks aim stays off after you look away
    private static final int   AIM_ITERS      = 8;

    // --- colors (ARGB) ---
    private static final int COLOR_RING   = 0xFFFFFFFF;
    private static final int COLOR_ENTITY = 0xFF55FF55;

    // flight params for the held/used projectile
    private record Spec(double gravity, double drag, double speed, double uncertainty,
                        double yOffsetDeg, boolean gravityFirst, boolean weapon) {}
    private record SimResult(Vec3 impact, Vec3 normal, Entity entity, List<Vec3> path, int ticks) {}

    // render state (written each tick, read each frame)
    private static volatile boolean valid = false;
    private static volatile Vec3 impact = Vec3.ZERO;
    private static volatile Vec3 normal = new Vec3(0, 1, 0);
    private static volatile double radius = 0;
    private static volatile AABB hitBox = null;

    // aim state
    private static boolean aiming = false;
    private static float lastAimYaw, lastAimPitch;
    private static int cooldown = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ProjectileTargeting::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(ProjectileTargeting::render);
    }

    // ---- which projectile, and its params ----
    private static Spec specFor(LocalPlayer p) {
        if (p.isUsingItem()) {
            var it = p.getUseItem().getItem();
            if (it == Items.BOW) {
                float t = p.getTicksUsingItem() / 20f;
                float pow = (t * t + 2f * t) / 3f;
                if (pow > 1f) pow = 1f;
                if (pow < 0.1f) return null;
                return new Spec(0.05, 0.99, pow * 3.0, 1.0, 0.0, false, true);   // arrow
            }
            if (it == Items.TRIDENT) return new Spec(0.05, 0.99, 2.5, 1.0, 0.0, false, true);
        }
        Spec s = specForHeld(p.getMainHandItem());
        return s != null ? s : specForHeld(p.getOffhandItem());
    }

    private static Spec specForHeld(ItemStack stack) {
        var it = stack.getItem();
        if (it == Items.SNOWBALL || it == Items.EGG || it == Items.ENDER_PEARL)
            return new Spec(0.03, 0.99, 1.5, 1.0, 0.0, true, false);
        if (it == Items.SPLASH_POTION || it == Items.LINGERING_POTION)
            return new Spec(0.03, 0.99, 0.5, 1.0, -20.0, true, false);
        if (it == Items.EXPERIENCE_BOTTLE)
            return new Spec(0.03, 0.99, 0.7, 1.0, -20.0, true, false);
        if (it == Items.CROSSBOW && CrossbowItem.isCharged(stack)) {
            ChargedProjectiles cp = stack.get(DataComponents.CHARGED_PROJECTILES);
            if (cp != null && cp.contains(Items.FIREWORK_ROCKET)) return null; // firework physics differ
            return new Spec(0.05, 0.99, 3.15, 1.0, 0.0, false, true);          // arrow
        }
        return null;
    }

    // initial projectile velocity for a given aim (matches Projectile.shootFromRotation, incl. inherited motion).
    // Inherited speed uses the TRUE per-tick position delta — getKnownMovement()/getDeltaMovement() underreport
    // for the local player, which is why this drifted while moving/flying.
    private static Vec3 launchVel(LocalPlayer p, double yaw, double pitch, Spec spec) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch), pro = Math.toRadians(pitch + spec.yOffsetDeg());
        double xd = -Math.sin(yr) * Math.cos(pr);
        double yd = -Math.sin(pro);
        double zd = Math.cos(yr) * Math.cos(pr);
        Vec3 dir = new Vec3(xd, yd, zd).normalize().scale(spec.speed());
        Vec3 km = new Vec3(p.getX() - p.xOld, p.getY() - p.yOld, p.getZ() - p.zOld);
        return dir.add(km.x, p.onGround() ? 0.0 : km.y, km.z);
    }

    private static boolean canHit(Entity e) {
        return e instanceof LivingEntity && e.isAlive() && !e.isSpectator();
    }

    // step the projectile tick-by-tick. collide=true stops at blocks/entities (for the reticle);
    // collide=false is a free-flight arc (for the aim solver, so drop is measured at the target's range).
    private static SimResult simulate(ClientLevel level, Vec3 eye, Vec3 vel, Spec spec, Entity self, boolean collide) {
        Vec3 pos = eye, v = vel, nrm = new Vec3(0, 1, 0), imp = null;
        Entity ent = null;
        int hitTick = MAX_TICKS;
        List<Vec3> pts = new ArrayList<>();
        pts.add(pos);
        for (int i = 0; i < MAX_TICKS; i++) {
            if (spec.gravityFirst()) { v = v.add(0, -spec.gravity(), 0); v = v.scale(spec.drag()); }
            Vec3 next = pos.add(v);

            if (collide) {
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
                if (ne != null) { imp = np; ent = ne; pts.add(np); hitTick = i + 1; break; }
                if (bhr.getType() != HitResult.Type.MISS) { imp = bhr.getLocation(); nrm = bhr.getDirection().getUnitVec3(); pts.add(imp); hitTick = i + 1; break; }
            }

            pos = next; pts.add(pos);
            if (!spec.gravityFirst()) { v = v.scale(spec.drag()); v = v.add(0, -spec.gravity(), 0); }
        }
        return new SimResult(imp, nrm, ent, pts, hitTick);
    }

    // entity nearest the reticle center P, among those the spread disc (radius r) overlaps
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

    private static int nearestIndex(List<Vec3> path, Vec3 t) {
        int best = 0; double bd = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            double d = path.get(i).distanceToSqr(t);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    // iterative ballistic solve WITH target lead. Uses a FREE-FLIGHT arc and measures the miss at the
    // projectile's closest approach to the target's range, so it lifts the aim for drop at long range.
    private static Vec3 solveAim(ClientLevel level, LocalPlayer p, Vec3 eye, Entity target, Spec spec) {
        Vec3 center = target.getBoundingBox().getCenter();
        Vec3 tv = new Vec3(target.getX() - target.xOld, target.getY() - target.yOld, target.getZ() - target.zOld);
        Vec3 aim = center;
        for (int i = 0; i < AIM_ITERS; i++) {
            Vec3 d = aim.subtract(eye);
            if (d.lengthSqr() < 1e-6) break;
            d = d.normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-d.y, -1.0, 1.0)));
            List<Vec3> path = simulate(level, eye, launchVel(p, yaw, pitch, spec), spec, p, false).path();
            int k = nearestIndex(path, center);                  // flight time to the target's range
            Vec3 leadTarget = center.add(tv.scale(k));           // where the target will be then
            Vec3 cp = path.get(nearestIndex(path, leadTarget));  // projectile's closest approach to it
            aim = aim.add(leadTarget.subtract(cp));              // raise/adjust aim by the miss at that range
        }
        return aim;
    }

    private static float ease(float cur, float target, boolean wrap) {
        float delta = wrap ? Mth.wrapDegrees(target - cur) : target - cur;
        return cur + Mth.clamp(delta * AIM_SMOOTH, -AIM_MAX_STEP, AIM_MAX_STEP);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player; ClientLevel level = mc.level;
        if (p == null || level == null) { aiming = false; valid = false; return; }

        valid = false; hitBox = null;
        Spec spec = specFor(p);
        if (spec == null) { aiming = false; return; }

        Vec3 eye = p.getEyePosition();

        // reticle from current aim -> candidate target
        SimResult s0 = simulate(level, eye, launchVel(p, p.getYRot(), p.getXRot(), spec), spec, p, true);
        Entity tgt = null;
        if (s0.impact() != null) {
            double r0 = Math.max(MIN_R, eye.distanceTo(s0.impact()) * spec.uncertainty() * ANG_PER_UNC * SPREAD_MULT);
            tgt = bestTarget(p, level, s0.impact(), r0);
        }

        // aim assist — weapons only (bow / crossbow / trident), with target lead
        Entity locked = null;
        boolean overridden = aiming && (Math.abs(Mth.wrapDegrees(p.getYRot() - lastAimYaw)) > MOUSE_EPS
                                     || Math.abs(p.getXRot() - lastAimPitch) > MOUSE_EPS);
        if (overridden) { cooldown = COOLDOWN_TICKS; aiming = false; }
        if (cooldown > 0) { cooldown--; aiming = false; }
        else if (tgt != null && spec.weapon()) {
            Vec3 aimAt = solveAim(level, p, eye, tgt, spec);
            Vec3 d = aimAt.subtract(eye).normalize();
            float fyaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float fpitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-d.y, -1.0, 1.0)));
            p.setYRot(ease(p.getYRot(), fyaw, true));
            p.setXRot(Mth.clamp(ease(p.getXRot(), fpitch, false), -90f, 90f));
            lastAimYaw = p.getYRot(); lastAimPitch = p.getXRot(); aiming = true;
            locked = tgt;
        } else aiming = false;

        // final reticle (reflects any aim correction this tick)
        SimResult sf = simulate(level, eye, launchVel(p, p.getYRot(), p.getXRot(), spec), spec, p, true);
        if (sf.impact() == null) return;
        double r = Math.max(MIN_R, eye.distanceTo(sf.impact()) * spec.uncertainty() * ANG_PER_UNC * SPREAD_MULT);
        Entity tgtF = locked != null ? locked : bestTarget(p, level, sf.impact(), r);
        impact = sf.impact(); normal = sf.normal();
        radius = r; hitBox = tgtF != null ? tgtF.getBoundingBox() : null;
        valid = true;
    }

    private static void render(LevelRenderContext ctx) {
        if (!valid) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        PoseStack ps = ctx.poseStack();
        MultiBufferSource.BufferSource buf = ctx.bufferSource();
        VertexConsumer vc = buf.getBuffer(RenderTypes.lines());
        Matrix4f m = ps.last().pose();
        PoseStack.Pose pose = ps.last();

        // spread ring on the impact surface (green if it covers a target, else white)
        Vec3 n = normal.normalize();
        Vec3 u = (Math.abs(n.y) < 0.99 ? n.cross(new Vec3(0, 1, 0)) : n.cross(new Vec3(1, 0, 0))).normalize();
        Vec3 w = n.cross(u).normalize();
        Vec3 ic = impact.add(n.scale(0.01));
        int ringColor = hitBox != null ? COLOR_ENTITY : COLOR_RING;
        Vec3 prev = null;
        for (int i = 0; i <= RING_SEGS; i++) {
            double a = (Math.PI * 2 * i) / RING_SEGS;
            Vec3 ptp = ic.add(u.scale(Math.cos(a) * radius)).add(w.scale(Math.sin(a) * radius));
            if (prev != null) seg(vc, m, pose, cam, prev, ptp, ringColor);
            prev = ptp;
        }
        if (hitBox != null) box(vc, m, pose, cam, hitBox, COLOR_ENTITY);

        buf.endBatch(RenderTypes.lines());
    }

    private static void seg(VertexConsumer vc, Matrix4f m, PoseStack.Pose pose, Vec3 cam, Vec3 a, Vec3 b, int color) {
        Vec3 d = b.subtract(a).normalize();
        vc.addVertex(m, (float)(a.x-cam.x), (float)(a.y-cam.y), (float)(a.z-cam.z))
            .setColor(color).setNormal(pose, (float)d.x, (float)d.y, (float)d.z).setLineWidth(LINE_WIDTH);
        vc.addVertex(m, (float)(b.x-cam.x), (float)(b.y-cam.y), (float)(b.z-cam.z))
            .setColor(color).setNormal(pose, (float)d.x, (float)d.y, (float)d.z).setLineWidth(LINE_WIDTH);
    }

    private static void box(VertexConsumer vc, Matrix4f m, PoseStack.Pose pose, Vec3 cam, AABB bb, int color) {
        Vec3 a = new Vec3(bb.minX, bb.minY, bb.minZ), b = new Vec3(bb.maxX, bb.maxY, bb.maxZ);
        Vec3[] c = {
            new Vec3(a.x,a.y,a.z), new Vec3(b.x,a.y,a.z), new Vec3(b.x,a.y,b.z), new Vec3(a.x,a.y,b.z),
            new Vec3(a.x,b.y,a.z), new Vec3(b.x,b.y,a.z), new Vec3(b.x,b.y,b.z), new Vec3(a.x,b.y,b.z)
        };
        int[][] edges = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
        for (int[] e : edges) seg(vc, m, pose, cam, c[e[0]], c[e[1]], color);
    }
}
