package ai.rrw.kine.autoflight;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Minecraft-side bridge for the terrain-avoidance planner. Each tick (when enabled) it:
 *   1. scans the surface ahead along the ground track into a {@link Terrain},
 *   2. seeds a {@link FlightModel.State} from the live player + the live FlightDirector law state
 *      (so the rollout continues the actual porpoise cycle -- predicted == flown), and
 *   3. asks {@link TerrainPlanner} for the lowest target floor that clears, or DISENGAGE.
 *
 * All the decision logic lives in the verified pure classes; this only marshals game state in and
 * a chosen floor out. The tuning constants mirror the validated harness (terrain_poc.py).
 */
public final class TerrainGuard {
    private TerrainGuard() {}

    // mirror the validated harness
    private static final int    HORIZON       = 420;     // rollout ticks
    private static final double BUFFER        = 8.0;     // blocks of clearance kept above terrain
    private static final double RAISE_MAX     = 60.0;    // ladder reaches cruise + this (top == max climb-out)
    private static final double RAISE_STEP    = 4.0;     // ladder granularity
    private static final double MIN_LOOKAHEAD = 160.0;   // must see at least this far to stay engaged
    private static final double SCAN_DIST     = 700.0;   // how far ahead to sample (covers the horizon reach)
    private static final double SCAN_STEP     = 2.0;     // sample spacing (blocks)

    /** Lowest clearing target floor for the situation ahead, or Double.NaN to disengage. */
    public static double evaluate(Minecraft mc, LocalPlayer p, int cruiseBase) {
        Terrain terrain = scan(mc, p);
        if (terrain == null) return Double.NaN;          // no ground track to scan along -> hand off
        FlightModel.State seed = seedFromPlayer(p, cruiseBase);
        return TerrainPlanner.plan(seed, terrain, cruiseBase, RAISE_MAX, RAISE_STEP,
                                   HORIZON, BUFFER, MIN_LOOKAHEAD);
    }

    /** Seed a rollout from the live player kinematics + the live FlightDirector law state, so the
     *  predicted path continues the actual porpoise cycle. Shared by the planner and the flight-path
     *  ribbon so both roll the SAME trajectory (the ribbon is then a predicted-vs-flown drift check). */
    public static FlightModel.State seedFromPlayer(LocalPlayer p, double target) {
        double dx = p.getX() - p.xOld, dz = p.getZ() - p.zOld;
        double vx = Math.sqrt(dx * dx + dz * dz);        // ground-track speed (blocks/tick)
        double vy = p.getY() - p.yOld;                   // vertical speed (blocks/tick)
        double pitch = p.getXRot();
        FlightModel.State seed = new FlightModel.State(0.0, p.getY(), vx, vy,
                                                       target, pitch, FlightDirector.climbingState());
        seed.phase = FlightDirector.phaseState();
        seed.cmd   = FlightDirector.commandedPitch();
        seed.topT  = FlightDirector.topTicksState();
        return seed;
    }

    /** Sample the MOTION_BLOCKING surface ahead along the ground track. Returns a Terrain whose
     *  heightAt(s) is the surface at along-track distance s, or NaN past the first unloaded column
     *  (so "can't see it" stays distinct from "it's low"). Null if the player has no ground track. */
    private static Terrain scan(Minecraft mc, LocalPlayer p) {
        double dx = p.getX() - p.xOld, dz = p.getZ() - p.zOld;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-4) return null;                   // essentially not moving horizontally
        final double hx = dx / len, hz = dz / len;
        final double px = p.getX(), pz = p.getZ();

        final int n = (int) (SCAN_DIST / SCAN_STEP) + 1;
        final double[] h = new double[n];
        int unknown = n;                                 // index of the first unloaded sample
        var level = mc.level;
        for (int i = 0; i < n; i++) {
            double s = i * SCAN_STEP;
            int bx = (int) Math.floor(px + hx * s);
            int bz = (int) Math.floor(pz + hz * s);
            if (!level.hasChunkAt(bx, bz)) { unknown = i; break; }
            h[i] = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        }
        final int firstUnknown = unknown;
        return x -> {
            double q = x < 0.0 ? 0.0 : x;
            int idx = (int) (q / SCAN_STEP);
            if (idx >= firstUnknown) return Double.NaN;
            int j = Math.min(idx + 1, firstUnknown - 1); // conservative: max of the bracketing samples
            return Math.max(h[idx], h[j]);
        };
    }
}
