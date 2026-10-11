package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudDraw;
import ai.rrw.kine.hud.HudLayout;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Aimed landing. In manual flight (gliding, autopilot not engaged) a reticle marks where you could land: the flat
 * surface you look at, or for a wall or the side of a pole, the nearer of its top and the ground at its foot. It
 * shows only for a spot in a loaded chunk within MAX_DIST blocks horizontally, safe to stand on (no fluid, no
 * harmful block on it or beside it, no drop of more than 3 blocks right beside it), and reachable: the landing
 * law rolled out from your present speed and heading must touch down within 1.5 blocks of it without damage.
 * With a reticle, the warning row says LEFT CLICK TO LAND; a left click engages the autopilot to land there
 * ({@link Nav#landAt}, {@link LandingModel}).
 */
public final class Landing {
    private Landing() {}

    public static final double MAX_DIST = 1024.0;       // horizontal distance limit for a spot (RW: farther out; loaded chunks bound it first, 512 at render distance 32)
    private static final double RAY = 1536.0;           // how far the look is traced (past MAX_DIST, for a steep look from high up)
    private static final double MIN_BELOW = 2.0;        // the spot must be at least this far below you
    private static final int    MAX_DROP = 3;           // a neighbour lower than this beside the spot is a fall
    private static final int    REACH_TICKS = 4000;     // the reach rollout's limit
    private static final float  NEAR_W = 0.05f;
    private static final int    MARK = 5;               // half the screen marker's size (px)

    private static boolean valid = false;
    private static int spotX, spotZ;
    private static double spotTop;

    /** Whether a landing spot is offered now (its reticle and prompt are showing). */
    public static boolean valid() { return valid; }
    /** The offered spot as {x, top surface y, z}, or null. */
    public static int[] spot() { return valid ? new int[]{ spotX, (int) spotTop, spotZ } : null; }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Landing::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "landing"),
            Landing::render);
    }

    private static void tick(Minecraft mc) {
        valid = false;
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || !Settings.landing || !Settings.autopilot) return;
        if (!p.isFallFlying() || Autopilot.isEngaged() || Takeoff.active()) return;   // manual flight only

        Vec3 eye = p.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(RAY)),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        if (hit.getType() != HitResult.Type.BLOCK || !level.hasChunkAt(hit.getBlockPos())) return;
        int[] spot = surface(level, hit);
        if (spot == null) return;
        int x = spot[0], top = spot[1], z = spot[2];
        double cx = x + 0.5, cz = z + 0.5;
        double dx = cx - p.getX(), dz = cz - p.getZ();
        if (dx * dx + dz * dz > MAX_DIST * MAX_DIST || top > p.getY() - MIN_BELOW) return;
        if (!safe(level, x, top, z)) return;

        Vec3 v = p.getDeltaMovement();
        LandingModel.Result r = LandingModel.simulate(new LandingModel.Law(), p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z,
            p.getYRot(), p.getXRot(), cx, top, cz, REACH_TICKS, 0);
        if (!r.touched() || r.offset() > LandingModel.MAX_OFFSET || r.damage() > 0) return;
        spotX = x; spotZ = z; spotTop = top;
        valid = true;
    }

    /**
     * The flat surface for a hit: a top face open to the sky is it; any other face gives the nearer (in height from
     * the hit point) of the hit column's top and the ground at the foot of the face. Returns {x, top surface y, z},
     * or null.
     */
    private static int[] surface(ClientLevel level, BlockHitResult hit) {
        BlockPos b = hit.getBlockPos();
        Direction face = hit.getDirection();
        int colTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING, b.getX(), b.getZ());
        if (face == Direction.UP) return colTop == b.getY() + 1 ? new int[]{ b.getX(), colTop, b.getZ() } : null;
        double hy = hit.getLocation().y;
        int[] best = new int[]{ b.getX(), colTop, b.getZ() };
        double bestD = colTop - hy;
        if (face.getAxis().isHorizontal()) {
            BlockPos c = b.relative(face);
            if (level.hasChunkAt(c)) {
                int foot = level.getHeight(Heightmap.Types.MOTION_BLOCKING, c.getX(), c.getZ());
                if (foot <= hy && hy - foot < bestD) best = new int[]{ c.getX(), foot, c.getZ() };
            }
        }
        return best;
    }

    /** Safe to land on: the top block is not a fluid or a harmful block, no harmful block on the eight columns beside
     *  it, and none of them is more than MAX_DROP lower (an edge to fall from). Unloaded neighbours are not safe. */
    private static boolean safe(ClientLevel level, int x, int top, int z) {
        if (!Nav.safeColumn(level, x, z)) return false;
        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
            if (ox == 0 && oz == 0) continue;
            int nx = x + ox, nz = z + oz;
            if (!level.hasChunkAt(new BlockPos(nx, top, nz))) return false;
            int nTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING, nx, nz);
            if (nTop < top - MAX_DROP) return false;
            BlockState s = level.getBlockState(new BlockPos(nx, nTop - 1, nz));
            if (Nav.DANGER.contains(s.getBlock()) || s.getFluidState().is(FluidTags.LAVA)) return false;
            // fire, berry bushes, wither roses and powder snow don't block motion: one sits at the heightmap's height
            if (Nav.DANGER.contains(level.getBlockState(new BlockPos(nx, nTop, nz)).getBlock())) return false;
        }
        return true;
    }

    /** The click that lands: a left click while a spot is offered and the crosshair is not on an entity in reach
     *  (that click attacks). Returns true when it took the click. */
    public static boolean beforeKeybinds(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!valid || p == null) return false;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) return false;
        if (!mc.options.keyAttack.consumeClick()) return false;
        while (mc.options.keyAttack.consumeClick()) { }
        Nav.landAt(spotX, spotTop, spotZ, p);
        Autopilot.engageForLanding(p);
        Kine.LOGGER.info("Kine landing: aimed at {} {} {} from y {}", spotX, String.format("%.0f", spotTop), spotZ,
            String.format("%.1f", p.getY()));
        valid = false;
        return true;
    }

    // ---- the reticle and the prompt ----

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!valid) return;
        Minecraft mc = Minecraft.getInstance();
        int W = mc.getWindow().getGuiScaledWidth(), H = mc.getWindow().getGuiScaledHeight();
        String s = "LEFT CLICK TO LAND";
        g.text(mc.font, s, W / 2 - mc.font.width(s) / 2, HudLayout.warningRow(H, mc.font.lineHeight), HudColors.GREEN, true);

        Camera camera = mc.gameRenderer.getMainCamera();
        Matrix4f vp = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Vec3 cam = camera.position();
        // the top face of the landing block, and a fixed-size square on its centre so it shows from far away
        double y = spotTop + 0.02;
        double[][] c = { { spotX, spotZ }, { spotX + 1, spotZ }, { spotX + 1, spotZ + 1 }, { spotX, spotZ + 1 } };
        float[][] s4 = new float[4][];
        for (int i = 0; i < 4; i++) s4[i] = ScreenProjection.project(vp, cam, c[i][0], y, c[i][1], W, H, NEAR_W);
        for (int i = 0; i < 4; i++) {
            float[] a = s4[i], b = s4[(i + 1) % 4];
            if (a != null && b != null) HudDraw.line(g, Math.round(a[0]), Math.round(a[1]), Math.round(b[0]), Math.round(b[1]), HudColors.GREEN);
        }
        float[] m = ScreenProjection.project(vp, cam, spotX + 0.5, y, spotZ + 0.5, W, H, NEAR_W);
        if (m == null) return;
        int mx = Math.round(m[0]), my = Math.round(m[1]);
        HudDraw.line(g, mx - MARK, my - MARK, mx + MARK, my - MARK, HudColors.GREEN);
        HudDraw.line(g, mx + MARK, my - MARK, mx + MARK, my + MARK, HudColors.GREEN);
        HudDraw.line(g, mx + MARK, my + MARK, mx - MARK, my + MARK, HudColors.GREEN);
        HudDraw.line(g, mx - MARK, my + MARK, mx - MARK, my - MARK, HudColors.GREEN);
        g.fill(mx, my, mx + 1, my + 1, HudColors.GREEN);
    }
}
