package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import java.util.Set;

/**
 * Lateral navigation for the autopilot. SELECTED holds a compass heading; MANAGED flies to an X/Z
 * coordinate and then runs a gentle descending-landing program once it arrives. The autopilot reads
 * desiredYaw()/landing()/landingPitch() to steer; this class only ever sets targets, never touches
 * the player directly.
 */
public class Nav {

    public enum Mode { OFF, SELECTED, MANAGED }

    private static final int    GREEN          = 0xFF44FF44;
    private static final int    COLUMN_BODY    = 0x4044FF44; // translucent green beam
    private static final int    COLUMN_CORE    = 0x9044FF44; // brighter centre line
    private static final double COLUMN_BASE_Y  = -64;        // beam spans the full world column at the target
    private static final double COLUMN_TOP_Y   = 320;
    private static final double ARRIVAL_RADIUS = 24.0;   // begin the landing program within this of the target
    private static final float  DESCENT_PITCH  = 15f;    // gentle nose-down glide while landing (deg, +down)
    private static final int    SCAN_RADIUS    = 16;     // search this far out for a safe landing column
    private static final int    SCAN_DEPTH     = 48;     // how far down to look for ground in a column

    // blocks we refuse to touch down on (lava is also caught as a fluid below)
    private static final Set<Block> DANGER = Set.of(
        Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
        Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
        Blocks.WITHER_ROSE);

    // --- state ---
    private static Mode    mode = Mode.OFF;
    private static float   selectedHeading = 0f;   // compass degrees (0=N, 90=E) for SELECTED
    private static int     targetX, targetZ;       // for MANAGED
    private static boolean landing = false;
    private static boolean haveSpot = false;
    private static int     spotX, spotZ;           // chosen safe landing column
    private static double  speed = 0;              // smoothed ground speed (m/s) for ETA

    private static KeyMapping navKey;

    // --- accessors used by the autopilot / flight director ---
    public static Mode    mode()          { return mode; }
    public static boolean steering()      { return mode != Mode.OFF; }
    public static boolean landing()       { return landing; }
    public static float   landingPitch()  { return DESCENT_PITCH; }
    public static float   selectedHeading() { return selectedHeading; }
    public static int     targetX()       { return targetX; }
    public static int     targetZ()       { return targetZ; }

    // --- set by the nav screen ---
    public static void setSelected(float headingDeg) { mode = Mode.SELECTED; selectedHeading = wrap360(headingDeg); clearLanding(); }
    public static void setManaged(int x, int z)      { mode = Mode.MANAGED; targetX = x; targetZ = z; clearLanding(); }
    public static void off()                         { mode = Mode.OFF; clearLanding(); }
    private static void clearLanding()               { landing = false; haveSpot = false; }

    /** Minecraft yaw (deg) the autopilot should fly to satisfy the current nav mode. */
    public static float desiredYaw(LocalPlayer p) {
        if (mode == Mode.SELECTED) return wrap180(selectedHeading + 180f);   // compass -> MC yaw (N = yaw 180)
        double tx = (landing && haveSpot ? spotX + 0.5 : targetX + 0.5);
        double tz = (landing && haveSpot ? spotZ + 0.5 : targetZ + 0.5);
        double dx = tx - p.getX(), dz = tz - p.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));                  // bearing as MC yaw
    }

    public static void register() {
        navKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.kine.nav", GLFW.GLFW_KEY_N, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(Nav::tick);
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "nav"),
            Nav::render);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        while (navKey.consumeClick()) mc.setScreen(new ai.rrw.kine.ui.KineNavScreen());
        if (mode == Mode.OFF) { clearLanding(); return; }

        // smoothed ground speed for ETA
        double dx = p.getX() - p.xOld, dz = p.getZ() - p.zOld;
        speed = speed * 0.9 + Math.sqrt(dx * dx + dz * dz) * 20.0 * 0.1;

        if (mode == Mode.MANAGED) {
            double hdx = (targetX + 0.5) - p.getX(), hdz = (targetZ + 0.5) - p.getZ();
            double dist = Math.sqrt(hdx * hdx + hdz * hdz);
            if (!landing && dist < ARRIVAL_RADIUS && p.isFallFlying() && Autopilot.isEngaged()) {
                landing = true;
                findSafeSpot(mc, p);   // pick a safe touchdown column near the target, once
            }
        }
        if (landing && p.onGround() && !p.isFallFlying()) landing = false;   // touched down — stop the descent
    }

    /** Nearest non-dangerous solid column to the target, searched ring by ring; falls back to the target. */
    private static void findSafeSpot(Minecraft mc, LocalPlayer p) {
        Level level = mc.level;
        int fromY = (int) Math.floor(p.getY());
        for (int r = 0; r <= SCAN_RADIUS; r += 2) {
            int best = Integer.MAX_VALUE, bx = targetX, bz = targetZ; boolean found = false;
            for (int dx = -r; dx <= r; dx += 2) for (int dz = -r; dz <= r; dz += 2) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // current ring only
                int x = targetX + dx, z = targetZ + dz;
                if (safeColumn(level, x, z, fromY)) {
                    int d = dx * dx + dz * dz;
                    if (d < best) { best = d; bx = x; bz = z; found = true; }
                }
            }
            if (found) { spotX = bx; spotZ = bz; haveSpot = true; return; }
        }
        spotX = targetX; spotZ = targetZ; haveSpot = false;   // nothing safe found; aim at the target anyway
    }

    private static boolean safeColumn(Level level, int x, int z, int fromY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bottom = Math.max(level.getMinY(), fromY - SCAN_DEPTH);
        for (int y = fromY; y >= bottom; y--) {
            pos.set(x, y, z);
            BlockState s = level.getBlockState(pos);
            if (s.isAir()) continue;
            if (!s.getFluidState().isEmpty()) return false;        // water/lava surface — not a landing spot
            return !DANGER.contains(s.getBlock());                 // first solid block: safe iff not dangerous
        }
        return false;   // void / nothing within range
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (mode == Mode.OFF) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int cx = W / 2, cy = H / 2;

        if (mode == Mode.MANAGED) drawTargetColumn(g, mc, W, H);
        int maxOff = (int) (H * 0.25f);
        int lh = mc.font.lineHeight;
        int etaY  = cy - maxOff - lh - 6;   // the slot the "too low" warning otherwise uses
        int lineY = etaY - lh - 2;          // mode/target line, one line above

        String top = (mode == Mode.SELECTED)
            ? "HDG " + pad3(Math.round(selectedHeading))
            : "COORD " + targetX + " " + targetZ;
        g.text(mc.font, top, cx - mc.font.width(top) / 2, lineY, GREEN, true);

        if (mode == Mode.MANAGED) {
            String eta;
            if (landing) {
                eta = "LANDING";
            } else if (speed > 0.5) {
                double hdx = (targetX + 0.5) - p.getX(), hdz = (targetZ + 0.5) - p.getZ();
                eta = "ETA " + fmtTime(Math.sqrt(hdx * hdx + hdz * hdz) / speed);
            } else {
                eta = "ETA --";
            }
            g.text(mc.font, eta, cx - mc.font.width(eta) / 2, etaY, GREEN, true);
        }
    }

    // --- helpers ---

    /** Baritone-style beam: project the world column at the target X/Z to screen and draw it. There is
     *  no per-frame world-render hook in 26.1, so this is screen-space (and therefore draws through
     *  terrain, like Baritone's goal beam). */
    private static void drawTargetColumn(GuiGraphicsExtractor g, Minecraft mc, int W, int H) {
        Camera cam = mc.gameRenderer.getMainCamera();
        Matrix4f vp = cam.getViewRotationProjectionMatrix(new Matrix4f());
        Vec3 c = cam.position();
        double wx = targetX + 0.5, wz = targetZ + 0.5;

        float[] base = project(vp, c, wx, COLUMN_BASE_Y, wz, W, H);
        float[] top  = project(vp, c, wx, COLUMN_TOP_Y,  wz, W, H);
        if (base == null || top == null) return;   // (partly) behind the camera — skip this frame

        float sdx = top[0] - base[0], sdy = top[1] - base[1];
        float len = (float) Math.sqrt(sdx * sdx + sdy * sdy);
        if (len < 1f) return;

        double ddx = wx - c.x, ddz = wz - c.z;
        double dist = Math.sqrt(ddx * ddx + ddz * ddz);
        int width = (int) Math.max(2, Math.min(16, 600.0 / Math.max(1.0, dist)));

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate((base[0] + top[0]) / 2f, (base[1] + top[1]) / 2f);
        pose.rotate((float) Math.atan2(sdy, sdx));   // align the quad with the projected column
        int hl = Math.round(len / 2f);
        g.fill(-hl, -width / 2, hl, width / 2, COLUMN_BODY);
        g.fill(-hl, -1, hl, 1, COLUMN_CORE);
        pose.popMatrix();
    }

    private static float[] project(Matrix4f vp, Vec3 c, double wx, double wy, double wz, int W, int H) {
        Vector4f v = new Vector4f((float) (wx - c.x), (float) (wy - c.y), (float) (wz - c.z), 1.0f);
        vp.transform(v);
        if (v.w <= 0.0f) return null;
        return new float[]{ (v.x / v.w * 0.5f + 0.5f) * W, (1.0f - (v.y / v.w * 0.5f + 0.5f)) * H };
    }

    private static float wrap360(float d) { d %= 360f; return d < 0 ? d + 360f : d; }
    private static float wrap180(float d) { return Mth.wrapDegrees(d); }
    private static String pad3(long n) { n = ((n % 360) + 360) % 360; return String.format("%03d", n); }
    private static String fmtTime(double sec) {
        if (sec < 0 || Double.isNaN(sec) || Double.isInfinite(sec)) return "--";
        long s = Math.round(sec);
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }
}
