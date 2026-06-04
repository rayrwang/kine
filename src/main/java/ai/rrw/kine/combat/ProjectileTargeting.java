package ai.rrw.kine.combat;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
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
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;

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
    private static final double ENTITY_PAD  = 0.3;   // arrow pick inflation
    private static final float  NEAR_W      = 0.05f; // clip-space w to clip segments at (avoids divide blow-up)
    private static final int    LINE_PX     = 1;     // reticle line thickness in GUI pixels
    private static final float  MIN_SEG_PX2 = 1.0f;  // min on-screen segment length^2 before we draw (bridges thin-ellipse tips)

    // --- aim assist ---
    private static final float AIM_SMOOTH     = 0.35f; // ease fraction/tick toward solution
    private static final float AIM_MAX_STEP   = 10f;   // deg/tick cap
    private static final float MOUSE_EPS      = 0.4f;  // mouse drift (deg) that releases the lock
    private static final int   COOLDOWN_TICKS = 10;    // ticks aim stays off after you look away
    private static final int   LEAD_ITERS     = 3;   // outer passes: target lead + azimuth (flight time depends on the arc)
    private static final int   PITCH_ITERS    = 6;   // secant steps for the vertical solve (converges in ~2-4)
    // open-sky acquisition fallback: used only when the arrow finds no block impact
    private static final double CONE_DEG      = 6.0;                              // acquisition half-angle
    private static final double CONE_COS      = Math.cos(Math.toRadians(CONE_DEG));
    private static final double CONE_HOLD_COS = Math.cos(Math.toRadians(10.0));   // wider angle to keep an existing lock
    private static final double CONE_RANGE    = 80.0;                             // max fallback acquisition distance (blocks)

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
    private static Entity coneLock = null;   // last cone-acquired target, for open-sky stickiness

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ProjectileTargeting::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "projectile_reticle"),
            ProjectileTargeting::render);
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
        if (e instanceof EnderDragonPart) return true;   // dragon hitboxes aren't LivingEntity
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

    // open-sky fallback: pick the entity nearest your look ray (within a cone), requiring line of sight
    // and a range cap. Keeps the previous lock if it's still inside a wider hold-cone, to avoid flicker.
    private static Entity coneTarget(LocalPlayer p, ClientLevel level, Vec3 eye) {
        Vec3 look = p.getLookAngle();
        AABB search = new AABB(eye, eye).inflate(CONE_RANGE);

        Entity best = null; double bestCos = CONE_COS;   // most-centered new acquisition (tight cone)
        boolean keepLock = false;                        // is the existing lock still valid (hold cone)?

        for (Entity e : level.getEntities(p, search, ProjectileTargeting::canHit)) {
            Vec3 c = e.getBoundingBox().getCenter();
            Vec3 to = c.subtract(eye);
            double dist = to.length();
            if (dist < 1e-3 || dist > CONE_RANGE) continue;
            double cos = to.scale(1.0 / dist).dot(look);
            if (cos < CONE_HOLD_COS) continue;            // outside even the hold cone
            if (!losClear(level, p, eye, c)) continue;    // terrain in the way
            if (e == coneLock) keepLock = true;           // current target still visible & within hold cone
            if (cos > bestCos) { bestCos = cos; best = e; }
        }

        if (keepLock) return coneLock;                    // stick with the current target
        coneLock = best;                                  // else acquire the most-centered (or clear the lock)
        return best;
    }

    private static boolean losClear(ClientLevel level, Entity self, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self)).getType() == HitResult.Type.MISS;
    }

    // unit launch direction for a yaw/pitch (deg), MC convention (up = negative pitch).
    private static Vec3 dirOf(double yaw, double pitch) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        return new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
    }

    // first tick at which the free-flight arc reaches the target's horizontal range (flight time for lead).
    private static int ticksToHoriz(List<Vec3> path, Vec3 eye, double horiz) {
        for (int i = 1; i < path.size(); i++)
            if (Math.hypot(path.get(i).x - eye.x, path.get(i).z - eye.z) >= horiz) return i;
        return path.size() - 1;
    }

    // vertical miss (arrow minus target) where the arc crosses the target's horizontal range. The true,
    // monotonic objective for the pitch solve; uses the real launchVel/simulate so drag, inherited motion,
    // gravity order and any throw y-offset are all baked in. Negative = the arc is below the target (short).
    private static double vMissAtRange(ClientLevel level, LocalPlayer p, Vec3 eye,
                                       double yaw, double pitch, Spec spec, double horiz, double dy) {
        List<Vec3> path = simulate(level, eye, launchVel(p, (float) yaw, (float) pitch, spec), spec, p, false).path();
        for (int i = 1; i < path.size(); i++) {
            Vec3 a = path.get(i - 1), b = path.get(i);
            double h0 = Math.hypot(a.x - eye.x, a.z - eye.z), h1 = Math.hypot(b.x - eye.x, b.z - eye.z);
            if (h1 >= horiz && h1 != h0) {
                double t = (horiz - h0) / (h1 - h0);
                return (a.y + t * (b.y - a.y) - eye.y) - dy;
            }
        }
        return (path.get(path.size() - 1).y - eye.y) - dy;   // never reached the range (truncated/short)
    }

    // drag-free closed-form launch pitch (low arc), MC deg, up = negative. NaN beyond no-drag range.
    private static double seedPitch(Spec spec, double horiz, double dy) {
        double v2 = spec.speed() * spec.speed();
        double disc = v2 * v2 - spec.gravity() * (spec.gravity() * horiz * horiz + 2 * dy * v2);
        if (disc < 0) return Double.NaN;
        double tan = (v2 - Math.sqrt(disc)) / (spec.gravity() * horiz);
        return -Math.toDegrees(Math.atan(tan));
    }

    // launch pitch so the arc crosses the target's range at the target's height: analytic seed + 1-D secant
    // on the (monotonic, low-arc) vertical miss. Converges in ~2-4 steps at any range -- including the long /
    // high-arc shots where the old closest-approach fixed point stalled short.
    private static double solvePitch(ClientLevel level, LocalPlayer p, Vec3 eye,
                                     double yaw, Spec spec, double horiz, double dy) {
        double seed = seedPitch(spec, horiz, dy);
        double p0 = Double.isNaN(seed) ? -45.0 : seed;       // beyond no-drag range: loft and let the secant push
        double p1 = p0 - 1.0;                                 // nudge up 1 deg to seed the secant slope
        double f0 = vMissAtRange(level, p, eye, yaw, p0, spec, horiz, dy);
        double f1 = vMissAtRange(level, p, eye, yaw, p1, spec, horiz, dy);
        for (int i = 0; i < PITCH_ITERS; i++) {
            if (Math.abs(f1) < 0.02) break;
            double den = f1 - f0;
            if (Math.abs(den) < 1e-9) break;
            double p2 = Mth.clamp(p1 - f1 * (p1 - p0) / den, -89.0, 89.0);
            p0 = p1; f0 = f1; p1 = p2; f1 = vMissAtRange(level, p, eye, yaw, p1, spec, horiz, dy);
        }
        return p1;
    }

    // azimuth that points the arrow's (constant-direction) horizontal velocity at the target, correcting
    // exactly for inherited shooter motion: speed*cos(pitch)*[-sinYaw,cosYaw] + km_h must be parallel to the
    // bearing. Drag scales x/z equally so the horizontal path is a straight ray -- this is one-shot exact.
    private static double solveYaw(LocalPlayer p, Vec3 eye, Vec3 lead, double pitch, Spec spec) {
        double bearing = Math.toDegrees(Math.atan2(-(lead.x - eye.x), lead.z - eye.z));
        double ux = lead.x - eye.x, uz = lead.z - eye.z, un = Math.sqrt(ux * ux + uz * uz);
        if (un < 1e-9) return bearing;
        ux /= un; uz /= un;
        double pr = Math.toRadians(pitch), pro = Math.toRadians(pitch + spec.yOffsetDeg());
        double hu = Math.cos(pr);
        double a = spec.speed() * hu / Math.hypot(hu, Math.sin(pro));   // true horizontal launch speed (excl. inherited)
        double kmx = p.getX() - p.xOld, kmz = p.getZ() - p.zOld;
        double ukm = ux * kmx + uz * kmz, c = kmx * kmx + kmz * kmz - a * a, disc = ukm * ukm - c;
        if (disc < 0 || a < 1e-9) return bearing;
        double s = ukm + Math.sqrt(disc);
        return Math.toDegrees(Math.atan2(-(s * ux - kmx), s * uz - kmz));
    }

    // ballistic solve WITH target lead. Outer loop refines the lead (flight time depends on the arc); each
    // pass solves pitch (seed+secant on the range-crossing height) and azimuth (inherited-motion exact).
    private static Vec3 solveAim(ClientLevel level, LocalPlayer p, Vec3 eye, Entity target, Spec spec) {
        Vec3 center = target.getBoundingBox().getCenter();
        Entity velSrc = target instanceof EnderDragonPart part ? part.parentMob : target;
        Vec3 tv = new Vec3(velSrc.getX() - velSrc.xOld, velSrc.getY() - velSrc.yOld, velSrc.getZ() - velSrc.zOld);

        Vec3 lead = center;
        double yaw = Math.toDegrees(Math.atan2(-(center.x - eye.x), center.z - eye.z));
        double pitch = 0.0;
        for (int outer = 0; outer < LEAD_ITERS; outer++) {
            Vec3 d = lead.subtract(eye);
            double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
            if (horiz < 1e-3) break;
            pitch = solvePitch(level, p, eye, yaw, spec, horiz, d.y);
            yaw   = solveYaw(p, eye, lead, pitch, spec);
            int k = ticksToHoriz(simulate(level, eye, launchVel(p, (float) yaw, (float) pitch, spec), spec, p, false).path(), eye, horiz);
            lead = center.add(tv.scale(k));
        }
        return eye.add(dirOf(yaw, pitch));   // caller recovers (yaw,pitch) via line-of-sight to this point
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

        // reticle from current aim -> candidate target
        SimResult s0 = simulate(level, eye, launchVel(p, p.getYRot(), p.getXRot(), spec), spec, p, true);
        Entity tgt = null;
        if (s0.impact() != null) {
            double r0 = Math.max(MIN_R, eye.distanceTo(s0.impact()) * spec.uncertainty() * ANG_PER_UNC * SPREAD_MULT);
            tgt = bestTarget(p, level, s0.impact(), r0);
        } else if (Settings.autoAim && spec.weapon()) {
            tgt = coneTarget(p, level, eye);   // open-sky fallback: pick by look-ray angle
        }

        // aim assist — weapons only (bow / crossbow / trident), with target lead
        Entity locked = null;
        boolean overridden = aiming && (Math.abs(Mth.wrapDegrees(p.getYRot() - lastAimYaw)) > MOUSE_EPS
                                     || Math.abs(p.getXRot() - lastAimPitch) > MOUSE_EPS);
        if (overridden) { cooldown = COOLDOWN_TICKS; aiming = false; }
        if (cooldown > 0) { cooldown--; aiming = false; }
        else if (tgt != null && spec.weapon() && Settings.autoAim) {
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

    // Rendered as a screen-space overlay rather than in the world: the impact ring and target box are
    // computed in world space (in tick) then projected to the screen here, so they draw on top of
    // terrain instead of being occluded by it — there's no no-depth line render type to use in-world.
    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!valid || !Settings.projectileReticle) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        Matrix4f vp = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Vec3 cam = camera.position();
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        // spread ring on the impact surface (green if it covers a target, else white)
        Vec3 n = normal.normalize();
        Vec3 u = (Math.abs(n.y) < 0.99 ? n.cross(new Vec3(0, 1, 0)) : n.cross(new Vec3(1, 0, 0))).normalize();
        Vec3 w = n.cross(u).normalize();
        Vec3 ic = impact.add(n.scale(0.01));
        int ringColor = hitBox != null ? COLOR_ENTITY : COLOR_RING;
        // Walk the ring projecting each point, drawing only once we've moved at least ~1px on screen.
        // At shallow angles the ellipse gets very thin and its left/right tips bunch into sub-pixel
        // steps; without this they'd round to zero length and drop out, splitting the ring into two arcs.
        float[] anchor = null;
        for (int i = 0; i <= RING_SEGS; i++) {
            double a = (Math.PI * 2 * i) / RING_SEGS;
            Vec3 p = ic.add(u.scale(Math.cos(a) * radius)).add(w.scale(Math.sin(a) * radius));
            float[] s = screenOf(vp, cam, p, W, H);
            if (s == null) { anchor = null; continue; }        // point behind the camera: break the ring here
            if (anchor == null) { anchor = s; continue; }
            float ddx = s[0] - anchor[0], ddy = s[1] - anchor[1];
            if (ddx * ddx + ddy * ddy >= MIN_SEG_PX2) {        // far enough to draw a clean segment
                line2d(g, anchor[0], anchor[1], s[0], s[1], ringColor);
                anchor = s;
            }                                                  // else accumulate: keep anchor, bridge to a later point
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

    // Draw a world-space segment as a screen line, clipped to the camera near plane so a segment that
    // crosses behind the camera doesn't blow up to infinity (which used to leave gaps on the ring sides).
    private static void line3d(GuiGraphicsExtractor g, Matrix4f vp, Vec3 cam, int W, int H, Vec3 A, Vec3 B, int color) {
        Vector4f a = clip(vp, cam, A);
        Vector4f b = clip(vp, cam, B);
        boolean ain = a.w > NEAR_W, bin = b.w > NEAR_W;
        if (!ain && !bin) return;                                     // whole segment behind the near plane
        if (!ain)      a = lerp4(a, b, (NEAR_W - a.w) / (b.w - a.w));  // clip endpoint A forward to the near plane
        else if (!bin) b = lerp4(b, a, (NEAR_W - b.w) / (a.w - b.w));  // clip endpoint B forward to the near plane
        float[] sa = toScreen(a, W, H), sb = toScreen(b, W, H);
        line2d(g, sa[0], sa[1], sb[0], sb[1], color);
    }

    /** World point -> clip space (before the perspective divide). */
    private static Vector4f clip(Matrix4f vp, Vec3 cam, Vec3 p) {
        Vector4f v = new Vector4f((float)(p.x - cam.x), (float)(p.y - cam.y), (float)(p.z - cam.z), 1.0f);
        vp.transform(v);
        return v;
    }

    private static Vector4f lerp4(Vector4f a, Vector4f b, float t) {
        return new Vector4f(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t);
    }

    /** Clip-space point -> screen pixel. */
    private static float[] toScreen(Vector4f v, int W, int H) {
        return new float[]{ (v.x / v.w * 0.5f + 0.5f) * W, (1.0f - (v.y / v.w * 0.5f + 0.5f)) * H };
    }

    /** World point -> screen pixel, or null if at/behind the camera near plane. */
    private static float[] screenOf(Matrix4f vp, Vec3 cam, Vec3 p, int W, int H) {
        Vector4f v = clip(vp, cam, p);
        if (v.w <= NEAR_W) return null;
        return toScreen(v, W, H);
    }

    /** Thin screen-space line between two points, via a rotated fill. */
    private static void line2d(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int color) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) return;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate((x1 + x2) / 2f, (y1 + y2) / 2f);
        pose.rotate((float) Math.atan2(dy, dx));
        int hl = Math.round(len / 2f);
        g.fill(-hl, 0, hl, LINE_PX, color);
        pose.popMatrix();
    }
}
