package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.hud.NavHud;
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
 * coordinate and lands there once it arrives, with the {@link LandingModel} law: a spiral plunge, a brake well
 * above the ground and a slow nose-up finish onto the spot. An aimed landing ({@link Landing}) starts that law at
 * once on the spot picked with the reticle. The autopilot reads desiredYaw()/landing()/landingPitch() to steer;
 * this class only ever sets targets, never touches the player directly.
 */
public class Nav {

    public enum Mode { OFF, SELECTED, MANAGED }

    private static final double ARRIVAL_RADIUS = 24.0;   // begin the landing program within this of the target
    private static final double EXIT_RADIUS    = 36.0;   // cancel landing if the pilot takes over and flies past this
    private static final int    SCAN_RADIUS    = 16;     // search this far out for a safe landing column
    private static final int    SCAN_DEPTH     = 48;     // how far below the column's top to keep looking, if the heightmap is stale

    // blocks we refuse to touch down on (lava is also caught as a fluid below)
    static final Set<Block> DANGER = Set.of(
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
    private static double  spotTop;                // its top surface (the y you stand at on it)
    private static boolean aimed = false;          // this landing was picked with the reticle
    private static LandingModel.Law law = new LandingModel.Law();

    private static KeyMapping navKey;

    // read by the autopilot and the flight director
    // With navigation switched off in the settings, nav reads as off: nothing steers, nothing lands.
    public static Mode    mode()          { return Settings.navigation ? mode : Mode.OFF; }
    public static boolean steering()      { return (mode() == Mode.SELECTED && hasHeading) || (mode() == Mode.MANAGED && hasTarget); }
    public static boolean landing()       { return Settings.navigation && landing; }
    /** The landing law's pitch this tick (LandingModel). */
    public static float landingPitch() { return (float) law.pitch; }
    /** A copy of the landing law's state and the spot, for the path prediction. */
    public static LandingModel.Law landingLaw() { return law.copy(); }
    public static double spotX()   { return spotX + 0.5; }
    public static double spotZ()   { return spotZ + 0.5; }
    public static double spotTop() { return spotTop; }
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
    private static void clearLanding()               { landing = false; haveSpot = false; aimed = false; }

    /** Land now on the column (x, z) whose top surface is at `top`: picked with the reticle and already checked
     *  safe and reachable by {@link Landing}. */
    public static void landAt(int x, double top, int z, LocalPlayer p) {
        mode = Mode.MANAGED; targetX = x; targetZ = z; hasTarget = true; NavHud.resetEta();
        spotX = x; spotZ = z; spotTop = top; haveSpot = true;
        landing = true; aimed = true; law = new LandingModel.Law();
        law.update(p.getX(), p.getY(), p.getZ(), p.getDeltaMovement().y, spotX + 0.5, spotTop, spotZ + 0.5);   // steer from now
    }

    /** Minecraft yaw (deg) the autopilot should fly to satisfy the current nav mode. */
    public static float desiredYaw(LocalPlayer p) {
        return desiredYaw(p.getX(), p.getZ());
    }

    /** The same from a position (x, z), for predictions. */
    public static float desiredYaw(double x, double z) {
        if (mode == Mode.SELECTED) return wrap180(selectedHeading + 180f);   // compass -> MC yaw (N = yaw 180)
        if (landing) return wrap180((float) law.yaw);                         // the landing law steers
        double dx = targetX + 0.5 - x, dz = targetZ + 0.5 - z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));                  // en route: fly straight at it
    }

    public static void register() {
        navKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.kine.nav", GLFW.GLFW_KEY_N, Kine.KEY_CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(Nav::tick);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        while (navKey.consumeClick()) if (Settings.navigation) mc.setScreen(new KineNavScreen());
        if (mode() == Mode.OFF) { clearLanding(); return; }

        if (mode == Mode.MANAGED && hasTarget) {
            double hdx = (targetX + 0.5) - p.getX(), hdz = (targetZ + 0.5) - p.getZ();
            double dist = Math.sqrt(hdx * hdx + hdz * hdz);
            if (!landing) {
                if (dist < ARRIVAL_RADIUS && p.isFallFlying() && Autopilot.isEngaged()) {
                    landing = true;
                    findSafeSpot(mc, p);   // pick a safe touchdown column near the target, once
                    spotTop = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, spotX, spotZ);
                    law = new LandingModel.Law();
                }
            } else if (!Autopilot.isEngaged() && dist > EXIT_RADIUS) {
                landing = false; haveSpot = false;   // pilot took over and flew off: resume flying to the target
            }
        }
        if (landing) {
            if (p.onGround() && !p.isFallFlying()) {   // touched down: end the landing
                landing = false;
                if (aimed) { hasTarget = false; aimed = false; }   // an aimed spot is not a destination to come back to
            } else {
                law.update(p.getX(), p.getY(), p.getZ(), p.getDeltaMovement().y, spotX + 0.5, spotTop, spotZ + 0.5);
            }
        }
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
    static boolean safeColumn(Level level, int x, int z) {
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
