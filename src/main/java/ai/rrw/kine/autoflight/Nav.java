package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.hud.RangeEndurance;
import ai.rrw.kine.util.KineTime;
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
    private static final int    RED            = 0xFFFF3030; // matches the flight-director warning red
    private static final int    COLUMN_BODY    = 0x4044FF44; // translucent green beam
    private static final int    COLUMN_CORE    = 0x9044FF44; // brighter centre line
    private static final double COLUMN_BASE_Y  = -64;        // beam spans the full world column at the target
    private static final double COLUMN_TOP_Y   = 320;
    private static final double ARRIVAL_RADIUS = 24.0;   // begin the landing program within this of the target
    private static final double EXIT_RADIUS    = 36.0;   // cancel landing if the pilot takes over and flies past this
    private static final float  DESCENT_PITCH  = 12f;    // gentle nose-down glide while landing (deg, +down)
    private static final double ORBIT_RADIUS   = 10.0;   // circle this tight around the landing spot
    private static final double RADIAL_GAIN     = 6.0;   // how hard to correct back toward that radius (deg per block)
    private static final int    SCAN_RADIUS    = 16;     // search this far out for a safe landing column
    private static final int    SCAN_DEPTH     = 48;     // how far down to look for ground in a column
    private static final double ETA_ANCHOR     = 21.0;   // nominal cruise groundspeed (m/s) the ETA is anchored to (sim: ~20.6 for the deployed porpoise)
    private static final double ETA_ADJUST     = 0.0001; // per-tick drift of the anchor toward the measured mean (~100s time constant)
    private static final double ETA_TC         = 12.0;   // time constant (s) for smoothing the *displayed* ETA, on top of the speed smoothing

    // blocks we refuse to touch down on (lava is also caught as a fluid below)
    private static final Set<Block> DANGER = Set.of(
        Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
        Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
        Blocks.WITHER_ROSE);

    // --- state ---
    private static Mode    mode = Mode.OFF;
    private static float   selectedHeading = 0f;   // compass degrees (0=N, 90=E) for SELECTED
    private static boolean hasHeading = false;     // no heading chosen until the dial is set
    private static int     targetX, targetZ;       // for MANAGED
    private static boolean hasTarget = false;      // no destination chosen until coords are entered
    private static boolean landing = false;
    private static boolean haveSpot = false;
    private static int     spotX, spotZ;           // chosen safe landing column
    private static double  etaSpeed = ETA_ANCHOR;  // anchored, slowly-adapted groundspeed used for the ETA readout
    private static double  etaShown = Double.NaN;  // low-passed ETA value actually displayed (s)
    private static long    etaNanos = 0L;          // wall-clock of the last ETA display update

    private static KeyMapping navKey;

    // --- accessors used by the autopilot / flight director ---
    public static Mode    mode()          { return mode; }
    public static boolean steering()      { return (mode == Mode.SELECTED && hasHeading) || (mode == Mode.MANAGED && hasTarget); }
    public static boolean landing()       { return landing; }
    public static float   landingPitch()  { return DESCENT_PITCH; }
    public static float   selectedHeading() { return selectedHeading; }
    public static boolean hasHeading()    { return hasHeading; }
    public static boolean hasTarget()     { return hasTarget; }
    public static int     targetX()       { return targetX; }
    public static int     targetZ()       { return targetZ; }

    // --- set by the nav screen ---
    public static void enterSelected() { mode = Mode.SELECTED; clearLanding(); }   // mode only — heading stays unset
    public static void enterManaged()  { mode = Mode.MANAGED;  clearLanding(); }   // mode only — target stays unset
    public static void setHeading(float headingDeg) { mode = Mode.SELECTED; selectedHeading = wrap360(headingDeg); hasHeading = true; clearLanding(); }
    public static void setTarget(int x, int z)       { mode = Mode.MANAGED; targetX = x; targetZ = z; hasTarget = true; clearLanding(); }
    public static void off()                         { mode = Mode.OFF; clearLanding(); }
    private static void clearLanding()               { landing = false; haveSpot = false; }

    /** Minecraft yaw (deg) the autopilot should fly to satisfy the current nav mode. */
    public static float desiredYaw(LocalPlayer p) {
        if (mode == Mode.SELECTED) return wrap180(selectedHeading + 180f);   // compass -> MC yaw (N = yaw 180)
        double tx = (landing && haveSpot ? spotX + 0.5 : targetX + 0.5);
        double tz = (landing && haveSpot ? spotZ + 0.5 : targetZ + 0.5);
        double dx = tx - p.getX(), dz = tz - p.getZ();
        float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));          // bearing to target as MC yaw
        if (!landing) return bearing;                                        // en route: fly straight at it
        // Landing: orbit the spot at a tight radius instead of flying through it (which overshoots and
        // swings wide on the U-turn). Tangent = bearing+90; the radial term steers inward when we're
        // outside ORBIT_RADIUS and outward when inside, so the path settles into a circle around the spot.
        double dist = Math.sqrt(dx * dx + dz * dz);
        float correction = (float) Mth.clamp((dist - ORBIT_RADIUS) * RADIAL_GAIN, -90.0, 90.0);
        return wrap180(bearing + 90f - correction);
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
        // ETA anchor: hold a stable cruise speed and drift it slowly toward the measured multi-cycle mean,
        // rather than dividing distance by the porpoise-noisy speed directly. Runs whenever we're flying.
        if (p.isFallFlying()) {
            double measured = RangeEndurance.meanGroundSpeed();
            if (measured > 0.5) etaSpeed += (measured - etaSpeed) * ETA_ADJUST;
        }
        if (mode == Mode.OFF) { clearLanding(); return; }

        if (mode == Mode.MANAGED && hasTarget) {
            double hdx = (targetX + 0.5) - p.getX(), hdz = (targetZ + 0.5) - p.getZ();
            double dist = Math.sqrt(hdx * hdx + hdz * hdz);
            if (!landing) {
                if (dist < ARRIVAL_RADIUS && p.isFallFlying() && Autopilot.isEngaged()) {
                    landing = true;
                    findSafeSpot(mc, p);   // pick a safe touchdown column near the target, once
                }
            } else if (!Autopilot.isEngaged() && dist > EXIT_RADIUS) {
                landing = false; haveSpot = false;   // pilot took over and flew off — resume coord navigation
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

        if (mode == Mode.MANAGED && hasTarget) drawTargetColumn(g, mc, W, H);
        if (FlightDirector.isTooLow() && !landing) return;   // too-low warning owns this slot instead
        int maxOff = (int) (H * 0.25f);
        int lh = mc.font.lineHeight;
        int etaY  = cy - maxOff - lh - 6;   // the slot the "too low" warning otherwise uses
        int lineY = etaY - lh - 2;          // mode/target line, one line above

        String top = (mode == Mode.SELECTED)
            ? (hasHeading ? "HDG " + pad3(Math.round(selectedHeading)) : "HDG ---")
            : (hasTarget  ? "COORD " + targetX + " " + targetZ          : "COORD --");
        g.text(mc.font, top, cx - mc.font.width(top) / 2, lineY, GREEN, true);

        if (mode != Mode.MANAGED || !hasTarget) return;   // no destination yet — no distance/ETA/range

        double hdx = (targetX + 0.5) - p.getX(), hdz = (targetZ + 0.5) - p.getZ();
        double dist = Math.sqrt(hdx * hdx + hdz * hdz);

        String line2 = landing
            ? "LANDING"
            : "DIST " + fmtDist(dist) + "    ETA " + KineTime.format(smoothEta(dist / etaSpeed));
        g.text(mc.font, line2, cx - mc.font.width(line2) / 2, etaY, GREEN, true);

        // If the elytra can't take us that far, flag it to the right of the two readout lines.
        double range = RangeEndurance.rangeMeters();
        if (!landing && range >= 0 && range < dist) {
            int wx = cx + Math.max(mc.font.width(top), mc.font.width(line2)) / 2 + 12;
            g.text(mc.font, "INSUFFICIENT", wx, lineY, RED, true);
            g.text(mc.font, "DURABILITY",   wx, etaY,  RED, true);
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
    private static String fmtDist(double m) { return m >= 1000 ? String.format("%.1f km", m / 1000.0) : Math.round(m) + " m"; }

    /**
     * Smooths the displayed ETA. The raw figure (distance / cruise speed) is right on average but ticks
     * down unevenly because ground speed surges in the dive and stalls at the top of each porpoise.
     * Complementary filter: count the real clock down at exactly one second per second (perfectly even),
     * then drift gently toward the true value so it stays accurate and absorbs genuine speed/heading
     * changes. The slow drift averages the porpoise surge/stall away. Snaps on a big jump (new target,
     * teleport) or after a render gap (screen open, F1) so it never crawls in from a stale value.
     */
    private static double smoothEta(double raw) {
        long now = System.nanoTime();
        double dt = (etaNanos == 0L) ? 0.0 : (now - etaNanos) / 1.0e9;
        etaNanos = now;
        if (Double.isNaN(etaShown) || dt <= 0 || dt > 1.0 || Math.abs(raw - etaShown) > Math.max(30.0, raw * 0.5)) {
            etaShown = raw;
        } else {
            etaShown = Math.max(0.0, etaShown - dt);                  // real-clock countdown: smooth by construction
            etaShown += (raw - etaShown) * (1.0 - Math.exp(-dt / ETA_TC));
        }
        return etaShown;
    }
}
