package ai.rrw.kine.autoflight;

import ai.rrw.kine.compat.Rules;

/**
 * The holding pattern flown over a managed destination, and the light rule that ends a hold till day. Free of game
 * types so it can be tested.
 *
 * <p>The pattern is a circle: the look is held OFFSET degrees right of the velocity while the porpoise law flies the
 * height as usual. It turns CENTRE_GAIN degrees less while the velocity points at the fix and that much more while it
 * points away (CENTRE_GAIN times the cosine of the angle between them), so the legs toward the fix last longer and
 * the circle drifts until the fix is at its centre. While the law climbs back up to its target the offset is held to
 * CLIMB_CAP, since turning costs climb. Chosen from a search of shapes on kine's own flight model
 * (~/_/kine_holding_2026-10-11/): at 350 it holds height on a circle about 234 blocks across, never more than about
 * 121 from the fix once centred, a lap about 24 s; a 1 s freeze of the controls costs at most about 12 blocks of
 * height. Tighter shapes exist (about 176 across) but lose 29 blocks to the same freeze.
 *
 * <p>The light rule is Monster.isDarkEnoughToSpawn for an open-sky block with no block light (26.1.2): a monster can
 * spawn there when the sky light less the sky darkening (Level.getSkyDarken, from the time of day and the weather;
 * a fixed 10 in a thunderstorm) is at most the dimension's monster spawn light roll (0 to 7 in the overworld).
 */
public final class HoldModel {
    private HoldModel() {}

    public static final double OFFSET      = 7.0;   // look right of the velocity (deg)
    public static final double CENTRE_GAIN = 2.0;   // up to this much less toward the fix, more away from it (deg)
    public static final double CLIMB_CAP   = 5.0;   // the offset while the law climbs (deg)

    // The first tick of the day with no surface spawning, in clear weather and in full rain: when the sky darkening
    // first falls to 7, from the game's sky light timeline replayed tick by tick (~/_/kine_holding_2026-10-11/sim/out/
    // dawn.txt). Up to 1.21.10 the darkening comes from the sun's angle (Rules.SKY_DARKEN_FROM_SUN_ANGLE:
    // Level.updateSkyBrightness, with DimensionType.timeOfDay and the float Mth.cos, replayed tick by tick the same
    // way): it falls to 7 at 22813 clear, 23032 in rain. Up to 1.21.11 the client's darkening is a tick behind the time
    // of day kine reads (Rules.SKY_DARKEN_A_TICK_LATE: ClientLevel.tick works it out before tickTime), so there it is 7
    // from a tick later: 22814 and 23033 up to 1.21.10, 22823 and 23047 on 1.21.11 (measured in lockstep client tests,
    // as Compat.overworldTime and Level.getSkyDarken read at a tick's end). Only for the readout's countdown; the hold
    // itself ends on the live light (darkEnoughToSpawn).
    static final int DAWN_CLEAR = (Rules.SKY_DARKEN_FROM_SUN_ANGLE ? 22813 : 22822) + (Rules.SKY_DARKEN_A_TICK_LATE ? 1 : 0);
    static final int DAWN_RAIN = (Rules.SKY_DARKEN_FROM_SUN_ANGLE ? 23032 : 23046) + (Rules.SKY_DARKEN_A_TICK_LATE ? 1 : 0);

    /** The look yaw (deg) for this tick, from the position (x, z), the fix, the velocity's yaw and whether the law is
     *  climbing. */
    public static double lookYaw(double x, double z, double fixX, double fixZ, double velYaw, boolean climbing) {
        double toFix = FlightModel3D.velYaw(fixX - x, fixZ - z);   // the yaw from here to the fix
        double off = OFFSET - CENTRE_GAIN * Math.cos(Math.toRadians(FlightModel3D.wrap180(toFix - velYaw)));
        if (climbing) off = Math.min(off, CLIMB_CAP);
        return velYaw + off;
    }

    /** Whether a monster can spawn on an open-sky block with no block light now. */
    public static boolean darkEnoughToSpawn(int skyDarken, boolean thundering, int maxSpawnLight) {
        return 15 - (thundering ? 10 : skyDarken) <= maxSpawnLight;
    }

    /** Ticks until surface spawning stops at dawn, from the time of day and the rain level (0 to 1); an estimate for
     *  the readout. */
    public static int ticksToDawn(long timeOfDay, float rain) {
        float r = Math.max(0f, Math.min(1f, rain));
        int dawn = Math.round(DAWN_CLEAR + (DAWN_RAIN - DAWN_CLEAR) * r);
        return (int) Math.floorMod(dawn - timeOfDay, 24000L);
    }
}
