package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.hud.NavHud;
import ai.rrw.kine.hud.RadioAltimeter;
import ai.rrw.kine.ui.KineNavScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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

    private static final double ARRIVAL_RADIUS = 24.0;   // begin the landing program within this of the target
    private static final double EXIT_RADIUS    = 36.0;   // cancel landing if the pilot takes over and flies past this
    private static final float  DIVE_PITCH    = 80f;    // near-vertical dive when high, to shed altitude fast (deg, +down)
    private static final float  FLARE_PITCH   = 10f;    // gentle glide for the final approach near the ground
    private static final double FLARE_AGL     = 12.0;   // at or below this height above ground, hold the gentle flare
    private static final double DIVE_AGL      = 500.0;  // at or above this height, hold the full dive
    private static final double ORBIT_RADIUS   = 10.0;   // circle this tight around the landing spot
    private static final double RADIAL_GAIN     = 6.0;   // how hard to correct back toward that radius (deg per block)
    private static final int    SCAN_RADIUS    = 16;     // search this far out for a safe landing column
    private static final int    SCAN_DEPTH     = 48;     // how far below the column's top to keep looking, if the heightmap is stale

    // blocks we refuse to touch down on (lava is also caught as a fluid below)
    private static final Set<Block> DANGER = Set.of(
        Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
        Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
        Blocks.WITHER_ROSE);

    // state
    private static Mode    mode = Mode.OFF;
    private static float   selectedHeading = 0f;   // compass degrees (0=N, 90=E) for SELECTED
    private static boolean hasHeading = false;     // no heading chosen until the dial is set
    private static int     targetX, targetZ;       // for MANAGED
    private static boolean hasTarget = false;      // no destination chosen until coords are entered
    private static boolean landing = false;
    private static boolean haveSpot = false;
    private static int     spotX, spotZ;           // chosen safe landing column

    private static KeyMapping navKey;

    // read by the autopilot and the flight director
    public static Mode    mode()          { return mode; }
    public static boolean steering()      { return (mode == Mode.SELECTED && hasHeading) || (mode == Mode.MANAGED && hasTarget); }
    public static boolean landing()       { return landing; }
    /** Descent angle for the landing program: steep up high to lose altitude fast, easing to a gentle flare near the ground. */
    public static float landingPitch() {
        double agl = Math.max(0, RadioAltimeter.agl());
        double t = Mth.clamp((agl - FLARE_AGL) / (DIVE_AGL - FLARE_AGL), 0.0, 1.0);
        return (float) Mth.lerp(t, FLARE_PITCH, DIVE_PITCH);
    }
    public static float   selectedHeading() { return selectedHeading; }
    public static boolean hasHeading()    { return hasHeading; }
    public static boolean hasTarget()     { return hasTarget; }
    public static int     targetX()       { return targetX; }
    public static int     targetZ()       { return targetZ; }

    // set by the nav screen
    public static void enterSelected() { mode = Mode.SELECTED; clearLanding(); }   // mode only; the heading stays unset
    public static void enterManaged()  { mode = Mode.MANAGED;  clearLanding(); }   // mode only; the target stays unset
    public static void setHeading(float headingDeg) { mode = Mode.SELECTED; selectedHeading = wrap360(headingDeg); hasHeading = true; clearLanding(); }
    public static void setTarget(int x, int z)       { mode = Mode.MANAGED; targetX = x; targetZ = z; hasTarget = true; NavHud.resetEta(); clearLanding(); }
    public static void off()                         { mode = Mode.OFF; clearLanding(); }
    private static void clearLanding()               { landing = false; haveSpot = false; }

    /** Minecraft yaw (deg) the autopilot should fly to satisfy the current nav mode. */
    public static float desiredYaw(LocalPlayer p) {
        return desiredYaw(p.getX(), p.getZ());
    }

    /** The same from a position (x, z), for predictions. */
    public static float desiredYaw(double x, double z) {
        if (mode == Mode.SELECTED) return wrap180(selectedHeading + 180f);   // compass -> MC yaw (N = yaw 180)
        double tx = (landing && haveSpot ? spotX + 0.5 : targetX + 0.5);
        double tz = (landing && haveSpot ? spotZ + 0.5 : targetZ + 0.5);
        double dx = tx - x, dz = tz - z;
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
            "key.kine.nav", GLFW.GLFW_KEY_N, Kine.KEY_CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(Nav::tick);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        while (navKey.consumeClick()) mc.setScreen(new KineNavScreen());
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
                landing = false; haveSpot = false;   // pilot took over and flew off: resume flying to the target
            }
        }
        if (landing && p.onGround() && !p.isFallFlying()) landing = false;   // touched down: end the landing
    }

    /** Nearest non-dangerous solid column to the target, searched ring by ring; falls back to the target. */
    private static void findSafeSpot(Minecraft mc, LocalPlayer p) {
        Level level = mc.level;
        for (int r = 0; r <= SCAN_RADIUS; r += 2) {
            int best = Integer.MAX_VALUE, bx = targetX, bz = targetZ; boolean found = false;
            for (int dx = -r; dx <= r; dx += 2) for (int dz = -r; dz <= r; dz += 2) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // current ring only
                int x = targetX + dx, z = targetZ + dz;
                if (safeColumn(level, x, z)) {
                    int d = dx * dx + dz * dz;
                    if (d < best) { best = d; bx = x; bz = z; found = true; }
                }
            }
            if (found) { spotX = bx; spotZ = bz; haveSpot = true; return; }
        }
        spotX = targetX; spotZ = targetZ; haveSpot = false;   // nothing safe found; aim at the target anyway
    }

    /** Whether a column's top block is safe to land on: not a fluid and not a dangerous block. The scan
     *  starts at the surface (the WORLD_SURFACE heightmap, the highest non-air block), so it finds the
     *  ground from any height. */
    private static boolean safeColumn(Level level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int bottom = Math.max(level.getMinY(), top - SCAN_DEPTH);
        for (int y = top; y >= bottom; y--) {
            pos.set(x, y, z);
            BlockState s = level.getBlockState(pos);
            if (s.isAir()) continue;
            if (!s.getFluidState().isEmpty()) return false;        // water or lava surface: not a landing spot
            return !DANGER.contains(s.getBlock());                 // first solid block: safe unless dangerous
        }
        return false;   // void, or nothing within range
    }

    private static float wrap360(float d) { d %= 360f; return d < 0 ? d + 360f : d; }
    private static float wrap180(float d) { return Mth.wrapDegrees(d); }
}
