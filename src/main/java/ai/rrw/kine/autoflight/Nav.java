package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.hud.NavHud;
import ai.rrw.kine.ui.KineNavScreen;
import ai.rrw.kine.util.Motion;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
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
 * coordinate and, on arriving, lands there with the {@link LandingModel} law (a spiral plunge, a brake well
 * above the ground and a slow nose-up finish onto the spot), or first circles over it ({@link HoldModel}), as the
 * nav screen's At destination choice says: hold, hold till day (while monsters can spawn there), or land. An aimed
 * landing ({@link Landing}) starts the landing law at once on the spot picked with the reticle. When END reaches 0 on
 * the autopilot (the durability failsafe, {@link ElytraGuard#landNow}) it lands near where you are, an emergency
 * landing, on water only when nothing dry is in reach. The autopilot reads desiredYaw()/landing()/landingPitch() to steer;
 * this class only ever sets targets, never touches the player directly.
 */
public class Nav {

    public enum Mode { OFF, SELECTED, MANAGED }

    static final double ARRIVAL_RADIUS = 24.0;           // begin the landing program within this of the target
    private static final double EXIT_RADIUS    = 36.0;   // cancel landing if the pilot takes over and flies past this
    private static final double HOLD_EXIT_RADIUS = 400.0; // end a hold if the pilot takes over and flies past this (the
                                                          // circle reaches about 240 from the fix before it centres)
    private static final int    SCAN_RADIUS    = 16;     // search this far out for a safe landing column
    private static final int    SCAN_DEPTH     = 48;     // how far below the column's top to keep looking, if the heightmap is stale
    private static final int    EMERGENCY_REACH = 32;    // an emergency landing looks this far around you for dry ground, then water

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
    private static boolean aimed = false;          // this landing was picked with the reticle, or is an emergency landing
    private static boolean emergency = false;      // this landing is the durability failsafe's (END 0): water allowed
    // the trip's mode and destination while an aimed landing borrows them, given back when it ends
    private static Mode    tripMode = Mode.OFF;
    private static int     tripX, tripZ;
    private static boolean tripHasTarget = false;
    private static LandingModel.Law law = new LandingModel.Law();

    /** What the autopilot does on reaching a managed destination: circle over it until told otherwise, circle while
     *  monsters can spawn there and then land, or land at once. */
    public enum Arrival {
        HOLD("Hold", "hold"), HOLD_TILL_DAY("Hold till day", "holdTillDay"), LAND("Land", "land");
        public final String label, key;
        Arrival(String label, String key) { this.label = label; this.key = key; }
    }
    private static boolean holding = false;                  // circling over the destination

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
    public static boolean hasSpot()       { return landing() && haveSpot; }   // landing on a known spot (spotX, spotZ, spotTop)
    public static int     targetZ()       { return targetZ; }
    public static boolean holding()       { return Settings.navigation && holding; }
    /** Landing because END reached 0 (the durability failsafe). */
    public static boolean emergency()     { return landing() && emergency; }
    /** The At destination choice, saved in kine.json (Settings.navArrival); hold till day by default (RW), and for an
     *  unknown value. */
    public static Arrival arrival() {
        for (Arrival a : Arrival.values()) if (a.key.equals(Settings.navArrival)) return a;
        return Arrival.HOLD_TILL_DAY;
    }
    /** Set from the nav screen; a hold in progress acts on it the next tick. */
    public static void setArrival(Arrival a) { Settings.navArrival = a.key; Settings.save(); }

    // set by the nav screen (the landing is cleared first, so an aimed landing's trip comes back before the change)
    public static void enterSelected() { clearLanding(); mode = Mode.SELECTED; }   // mode only; the heading stays unset
    public static void enterManaged()  { clearLanding(); mode = Mode.MANAGED; }    // mode only; the target stays unset
    public static void setHeading(float headingDeg) { clearLanding(); mode = Mode.SELECTED; selectedHeading = wrap360(headingDeg); hasHeading = true; }
    public static void setTarget(int x, int z)       { clearLanding(); mode = Mode.MANAGED; targetX = x; targetZ = z; hasTarget = true; NavHud.resetEta(); }
    public static void off()                         { clearLanding(); mode = Mode.OFF; }
    private static void clearLanding()               { landing = false; haveSpot = false; holding = false; emergency = false; endAimed(); }

    /** Land now on the column (x, z) whose top surface is at `top`: picked with the reticle and already checked
     *  safe and reachable by {@link Landing}. */
    public static void landAt(int x, double top, int z, LocalPlayer p) {
        landAt(x, top, z, p.getX(), p.getY(), p.getZ(), p.getDeltaMovement().y);
    }

    /** The same from the player's position and sink (blocks a tick). The spot borrows the destination, so the
     *  HUD and the steering follow it; the trip's mode and destination come back when the landing ends. */
    static void landAt(int x, double top, int z, double px, double py, double pz, double vy) {
        if (!aimed) { tripMode = mode; tripX = targetX; tripZ = targetZ; tripHasTarget = hasTarget; }   // a second aim keeps the trip
        mode = Mode.MANAGED; targetX = x; targetZ = z; hasTarget = true; NavHud.resetEta();
        spotX = x; spotZ = z; spotTop = top; haveSpot = true;
        landing = true; aimed = true; holding = false; emergency = false; law = new LandingModel.Law();
        law.update(px, py, pz, vy, spotX + 0.5, spotTop, spotZ + 0.5);   // steer from now
    }

    /** END has reached 0 on the autopilot: land near here now with the landing law (RW: "emergency landing prefer dry,
     *  water only when nothing dry"). The spot borrows the destination as an aimed one does; the trip comes back at
     *  touchdown. */
    private static void emergencyLanding(Minecraft mc, LocalPlayer p) {
        Level level = mc.level;
        int px = p.getBlockX(), pz = p.getBlockZ();
        int[] spot = nearestColumn(px, pz, EMERGENCY_REACH, (x, z) -> safeColumn(level, x, z));
        boolean water = spot == null;
        if (water) spot = nearestColumn(px, pz, EMERGENCY_REACH, (x, z) -> waterColumn(level, x, z));
        if (spot == null) {
            // nothing dry or wet in reach: straight down, as a destination landing falls back. But only onto terrain the
            // client has: a column not yet sent (just after a teleport or a join) reads as empty, its surface the world's
            // bottom, and the law would brake far below the real ground. Then wait, flying on, until it arrives.
            if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz) <= level.getMinY()) return;
            spot = new int[]{ px, pz };
        }
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, spot[0], spot[1]);   // fluids count: the water's surface
        landAt(spot[0], top, spot[1], p);
        emergency = true;
        Kine.LOGGER.info("Kine landing: END 0, emergency landing at {} {} {}{} from y {}", spot[0], top, spot[1],
            water ? " (water, nothing dry in reach)" : "", String.format("%.1f", p.getY()));
    }

    // An aimed landing is over (touched down, abandoned, or ended from the nav screen or the settings): the trip's mode and
    // destination come back, since an aimed spot is not a destination to come back to.
    private static void endAimed() {
        if (!aimed) return;
        aimed = false;
        mode = tripMode; targetX = tripX; targetZ = tripZ; hasTarget = tripHasTarget;
        NavHud.resetEta();
    }

    /** Minecraft yaw (deg) the autopilot should fly to satisfy the current nav mode. */
    public static float desiredYaw(LocalPlayer p) {
        return desiredYaw(p.getX(), p.getZ(), Motion.trackYaw(p, p.getYRot()), FlightDirector.law().climbing);
    }

    /** The same from a position (x, z), outside a hold (where the velocity doesn't matter). */
    public static float desiredYaw(double x, double z) {
        return desiredYaw(x, z, 0.0, false);
    }

    /** The same from a position (x, z), the velocity's yaw and whether the porpoise law is climbing, for predictions.
     *  While holding it is the look (the circle's), not a course. */
    public static float desiredYaw(double x, double z, double velYaw, boolean climbing) {
        if (mode == Mode.SELECTED) return wrap180(selectedHeading + 180f);   // compass -> MC yaw (N = yaw 180)
        if (landing) return wrap180((float) law.yaw);                         // the landing law steers
        if (holding) return wrap180((float) HoldModel.lookYaw(x, z, targetX + 0.5, targetZ + 0.5, velYaw, climbing));
        double dx = targetX + 0.5 - x, dz = targetZ + 0.5 - z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));                  // en route: fly straight at it
    }

    public static void register() {
        navKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.kine.nav", GLFW.GLFW_KEY_N, Kine.KEY_CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(Nav::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.isPaused()) return;   // singleplayer paused: the game's ticks still come, but the world stands still
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        while (navKey.consumeClick()) if (Settings.navigation) mc.setScreen(new KineNavScreen());
        // END 0 on the autopilot: land near here, whatever nav was doing; a landing already under way goes on. Ahead of
        // the hold's own check below, so a hold ends in this landing too.
        if (!landing && Settings.navigation && Autopilot.isEngaged() && p.isFallFlying() && !p.isInWater()
                && ElytraGuard.landNow()) emergencyLanding(mc, p);
        if (mode() == Mode.OFF) { clearLanding(); return; }

        if (mode == Mode.MANAGED && hasTarget) {
            double hdx = (targetX + 0.5) - p.getX(), hdz = (targetZ + 0.5) - p.getZ();
            double dist = Math.sqrt(hdx * hdx + hdz * hdz);
            if (!landing && holding) {
                if (!Autopilot.isEngaged() && dist > HOLD_EXIT_RADIUS) holding = false;   // pilot took over and flew off
                else if (Autopilot.isEngaged() && p.isFallFlying() && !holdNow(mc)) { holding = false; beginLanding(mc, p); }
            } else if (!landing) {
                if (dist < ARRIVAL_RADIUS && p.isFallFlying() && Autopilot.isEngaged()) {
                    if (holdNow(mc)) holding = true;
                    else beginLanding(mc, p);
                }
            } else if (!Autopilot.isEngaged() && dist > EXIT_RADIUS) {
                clearLanding();   // pilot took over and flew off: resume flying to the target (the trip's, if aimed)
            }
        }
        if (landing && p.getAbilities().flying) clearLanding();   // creative or spectator flight: the landing lets go (RW)
        if (landing) {
            if (p.onGround() && !p.isFallFlying()) touchedDown();
            else if (emergency && p.isInWater()) {
                // on water: the glide goes on there (LivingEntity.canGlide doesn't check for water), so this is the
                // touchdown, and the autopilot lets go as it does on the ground, without AUTOPILOT OFF
                touchedDown();
                Autopilot.releaseAfterLanding();
            }
            else law.update(p.getX(), p.getY(), p.getZ(), p.getDeltaMovement().y, spotX + 0.5, spotTop, spotZ + 0.5);
        }
    }

    // Land on the destination: a safe touchdown column near it, picked once, and the landing law from here (from
    // anywhere in the hold, too: its approach glides in from where it starts).
    private static void beginLanding(Minecraft mc, LocalPlayer p) {
        landing = true;
        findSafeSpot(mc, p);
        spotTop = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, spotX, spotZ);
        law = new LandingModel.Law();
    }

    // Whether to circle over the destination now rather than land: never once END has reached 0 (the durability
    // failsafe's land now), always for Hold, and for Hold till day while monsters can spawn there.
    static boolean holdNow(Minecraft mc) {
        if (ElytraGuard.landNow()) return false;
        return switch (arrival()) {
            case HOLD -> true;
            case LAND -> false;
            case HOLD_TILL_DAY -> monstersCanSpawn(mc.level);
        };
    }

    /** Whether a monster could spawn on open ground now: at night, or in a thunderstorm (the light is level-wide,
     *  so this holds at the destination too). Never in a dimension without days (the Nether, the End), where a
     *  hold till day would never end. */
    public static boolean monstersCanSpawn(Level level) {
        if (level.dimensionType().hasFixedTime()) return false;
        return HoldModel.darkEnoughToSpawn(level.getSkyDarken(), level.isThundering(),
            level.dimensionType().monsterSpawnLightTest().maxInclusive());
    }

    /** Touched down: the landing ends, and an aimed or emergency one gives the trip back. */
    static void touchedDown() { landing = false; emergency = false; endAimed(); }

    /** Nearest non-dangerous solid column to the target, searched ring by ring; falls back to the target. */
    private static void findSafeSpot(Minecraft mc, LocalPlayer p) {
        Level level = mc.level;
        int[] spot = nearestColumn(targetX, targetZ, SCAN_RADIUS, (x, z) -> safeColumn(level, x, z));
        if (spot != null) { spotX = spot[0]; spotZ = spot[1]; haveSpot = true; return; }
        spotX = targetX; spotZ = targetZ; haveSpot = false;   // nothing safe found; aim at the target anyway
    }

    // The spot the destination would get on arrival, for the path prediction: the same search as findSafeSpot, redone
    // at most once a second per destination (it reads up to 81 columns).
    private static boolean previewValid = false;
    private static Level previewLevel;
    private static int previewX, previewZ, previewForX, previewForZ;
    private static double previewTop;
    private static long previewTime;

    /** Flying to a managed destination and neither landing nor circling over it yet: coming within ARRIVAL_RADIUS
     *  of it will start one or the other. */
    static boolean arriving() { return mode() == Mode.MANAGED && hasTarget && !landing && !holding; }

    /** The landing spot an arrival now would pick, as {x of its centre, its top, z of its centre}. */
    static double[] arrivalSpot(Level level) {
        long now = level.getGameTime();
        if (!previewValid || previewLevel != level || previewForX != targetX || previewForZ != targetZ
                || now - previewTime >= 20 || now < previewTime) {
            int[] spot = nearestColumn(targetX, targetZ, SCAN_RADIUS, (x, z) -> safeColumn(level, x, z));
            previewX = spot != null ? spot[0] : targetX;
            previewZ = spot != null ? spot[1] : targetZ;
            previewTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING, previewX, previewZ);
            previewValid = true; previewLevel = level; previewForX = targetX; previewForZ = targetZ; previewTime = now;
        }
        return new double[]{ previewX + 0.5, previewTop, previewZ + 0.5 };
    }

    private interface ColumnTest { boolean test(int x, int z); }

    /** The nearest column around (cx, cz) that passes the test, searched in square rings 2 blocks apart out to
     *  `radius`, nearest first within a ring; {x, z}, or null if none does. */
    private static int[] nearestColumn(int cx, int cz, int radius, ColumnTest ok) {
        for (int r = 0; r <= radius; r += 2) {
            int best = Integer.MAX_VALUE, bx = cx, bz = cz; boolean found = false;
            for (int dx = -r; dx <= r; dx += 2) for (int dz = -r; dz <= r; dz += 2) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // current ring only
                int x = cx + dx, z = cz + dz;
                if (ok.test(x, z)) {
                    int d = dx * dx + dz * dz;
                    if (d < best) { best = d; bx = x; bz = z; found = true; }
                }
            }
            if (found) return new int[]{ bx, bz };
        }
        return null;
    }

    /** Whether a column's surface is water, the emergency landing's fallback: the first block below the open sky
     *  holds water (lava and every other surface no). */
    static boolean waterColumn(Level level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int bottom = Math.max(level.getMinY(), top - SCAN_DEPTH);
        for (int y = top; y >= bottom; y--) {
            pos.set(x, y, z);
            BlockState s = level.getBlockState(pos);
            if (s.isAir()) continue;
            return s.getFluidState().is(FluidTags.WATER);
        }
        return false;
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
