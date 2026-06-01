package ai.rrw.kine.combat;

import ai.rrw.kine.Kine;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Optional;

public class ProjectileTargeting {
    private static final float LINE_WIDTH = 2.0f;

    // --- tuning ---
    private static final int    MAX_TICKS    = 120;        // max flight steps to simulate
    private static final double SPREAD_MULT  = 2.0;        // circle = this many spread-radii wide
    private static final double ANG_PER_UNC  = 0.0172275;  // from Projectile.shoot()
    private static final int    RING_SEGS    = 48;
    private static final int    COLOR_RING   = 0xFFFFFFFF;  // white
    private static final int    COLOR_ENTITY = 0xFF55FF55;  // green

    // flight params for the held projectile; gravityFirst distinguishes throwable vs arrow tick order
    private record Spec(double gravity, double drag, double speed, double uncertainty, boolean gravityFirst) {}

    // computed each tick, drawn each frame
    private static boolean valid = false;
    private static Vec3 impact = Vec3.ZERO;
    private static Vec3 normal = new Vec3(0, 1, 0);
    private static double radius = 0;
    private static AABB hitBox = null;   // non-null => highlight this entity

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ProjectileTargeting::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(ctx -> render(ctx));
    }

    private static Spec specFor(LocalPlayer p) {
        if (p.isUsingItem() && p.getUseItem().getItem() == Items.BOW) {       // drawing a bow
            float t = p.getTicksUsingItem() / 20f;
            float pow = (t * t + 2f * t) / 3f;
            if (pow > 1f) pow = 1f;
            if (pow < 0.1f) return null;                                       // barely drawn
            return new Spec(0.05, 0.99, pow * 3.0, 1.0, false);               // arrow: move->drag->gravity
        }
        if (isThrowable(p.getMainHandItem()) || isThrowable(p.getOffhandItem()))
            return new Spec(0.03, 0.99, 1.5, 1.0, true);                       // throwable: gravity->drag->move
        return null;
    }

    private static boolean isThrowable(ItemStack s) {
        return s.getItem() == Items.SNOWBALL || s.getItem() == Items.EGG || s.getItem() == Items.ENDER_PEARL;
    }

    private static boolean canHit(Entity e) {
        return e instanceof LivingEntity && e.isAlive() && !e.isSpectator();
    }

    private static void tick(Minecraft mc) {
        valid = false; hitBox = null;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        Spec spec = specFor(p);
        if (spec == null) return;

        Vec3 start = p.getEyePosition();
        Vec3 pos = start;
        Vec3 v = p.getLookAngle().scale(spec.speed());
        Vec3 imp = null;
        Vec3 nrm = new Vec3(0, 1, 0);
        Entity entHit = null;

        for (int i = 0; i < MAX_TICKS; i++) {
            if (spec.gravityFirst()) { v = v.add(0, -spec.gravity(), 0); v = v.scale(spec.drag()); }
            Vec3 next = pos.add(v);

            BlockHitResult bhr = mc.level.clip(new ClipContext(
                pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            Vec3 segEnd = bhr.getType() != HitResult.Type.MISS ? bhr.getLocation() : next;

            Entity nearestE = null; Vec3 nearestPt = null; double best = Double.MAX_VALUE;
            for (Entity e : mc.level.getEntities(p, new AABB(pos, segEnd).inflate(2.0), ProjectileTargeting::canHit)) {
                Optional<Vec3> hit = e.getBoundingBox().inflate(0.3).clip(pos, segEnd);
                if (hit.isPresent()) {
                    double d = pos.distanceToSqr(hit.get());
                    if (d < best) { best = d; nearestE = e; nearestPt = hit.get(); }
                }
            }

            if (nearestE != null) { entHit = nearestE; imp = nearestPt; break; }
            if (bhr.getType() != HitResult.Type.MISS) { imp = bhr.getLocation(); nrm = bhr.getDirection().getUnitVec3(); break; }

            pos = next;
            if (!spec.gravityFirst()) { v = v.scale(spec.drag()); v = v.add(0, -spec.gravity(), 0); }
        }
        if (imp == null) return;

        double r = start.distanceTo(imp) * spec.uncertainty() * ANG_PER_UNC * SPREAD_MULT;

        // "reasonably likely to hit an entity": central ray hit one, or one sits within the spread of the path
        if (entHit == null) {
            double best = Double.MAX_VALUE;
            for (Entity e : mc.level.getEntities(p, new AABB(start, imp).inflate(r + 2.0), ProjectileTargeting::canHit)) {
                if (e.getBoundingBox().inflate(r).clip(start, imp).isPresent()) {
                    double d = start.distanceToSqr(e.position());
                    if (d < best) { best = d; entHit = e; }
                }
            }
        }

        impact = imp; normal = nrm; radius = Math.max(0.15, r);
        if (entHit != null) hitBox = entHit.getBoundingBox();
        valid = true;
    }

    private static void render(LevelRenderContext ctx) {
        if (!valid) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        PoseStack ps = ctx.poseStack();
        MultiBufferSource.BufferSource buf = ctx.bufferSource();
        VertexConsumer vc = buf.getBuffer(RenderTypes.lines());
        Matrix4f mat = ps.last().pose();
        PoseStack.Pose pose = ps.last();

        // ring in the plane perpendicular to the surface normal, nudged off the face to avoid z-fighting
        Vec3 n = normal.normalize();
        Vec3 u = (Math.abs(n.y) < 0.99 ? n.cross(new Vec3(0, 1, 0)) : n.cross(new Vec3(1, 0, 0))).normalize();
        Vec3 w = n.cross(u).normalize();
        Vec3 c = impact.add(n.scale(0.01));
        int ringColor = hitBox != null ? COLOR_ENTITY : COLOR_RING;
        Vec3 prev = null;
        for (int i = 0; i <= RING_SEGS; i++) {
            double a = (Math.PI * 2 * i) / RING_SEGS;
            Vec3 pt = c.add(u.scale(Math.cos(a) * radius)).add(w.scale(Math.sin(a) * radius));
            if (prev != null) seg(vc, mat, pose, cam, prev, pt, ringColor);
            prev = pt;
        }

        if (hitBox != null) box(vc, mat, pose, cam, hitBox, COLOR_ENTITY);
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
