package ai.rrw.kine.walk;

/**
 * The walk's arrival time (ETA), as close as it can be made (RW, 2026-10-12: "as intelgient as posible ... think all
 * the thigns to take intoa coucnt and how best ot cauclat it most accuratel"), from what decides it:
 * <ol>
 *   <li>The planned time left along the route ({@link TimeProfile}): layer 1's moves ahead costed as time (open
 *       ground, water, ladders, steps, drops, gaps, glides, the route's corners), with the costs fitted from layer 2's
 *       own simulated runs when layer 2 walks. This knows what lies ahead, which closing speed alone can't.</li>
 *   <li>Past a route that stops at the loaded area's edge, the straight line on to the goal at the open-ground rate
 *       times a detour factor: how much longer than the straight line the known part of the route is, learned from
 *       the plans of this walk (a prior of 1.15 until it has seen enough of the ground).</li>
 *   <li>A ratio learned on the way: the real ticks spent walking against the planned ticks they used up, over the
 *       whole walk and over the last ten seconds, blended, starting from 1 (the plan's own word). It takes in what the
 *       plan can't know: a Speed or Slowness effect, the layer that walks, a slow server, fights and stops.</li>
 *   <li>Hunger ahead: sprinting stops at 6 food, and each point of food or saturation lasts about 40 blocks of
 *       sprinting on foot (0.1 exhaustion a block, 4 a point), so the blocks on foot past that are charged at the
 *       walking rate (none in peaceful, where food doesn't drop).</li>
 * </ol>
 * The shown figure is smoothed as the elytra's ETA is (RW: "An instrumentt that fluctuates is worse than one that may
 * or may not be a little biased"): it counts down a second a second and drifts toward the estimate with a time
 * constant, snapping on a big change (a new route much longer or shorter); it stands still while the walk is paused.
 * No Minecraft class here: the walk feeds it, the tests too.
 */
public final class Eta {
    /** The whole-walk ratio starts as if this many ticks had gone exactly to plan; the recent one, RECENT_PRIOR. */
    public static double PRIOR = 100.0, RECENT_PRIOR = 40.0;
    /** The recent ratio's memory, ticks. */
    public static double RECENT_TC = 200.0;
    /** The recent ratio's share of the blend. */
    public static double RECENT_SHARE = 0.5;
    /** The detour factor past the loaded edge (time against the straight line at the line's own rate) before the
     *  routes have shown theirs; worth this many blocks of route. Measured 2026-10-13: 1.15 with weight 64 put the
     *  part past the edge on open ground 10% high early on (the routes' own factor there 1.0 to 1.05). */
    public static double DETOUR_PRIOR = 1.05, DETOUR_WEIGHT = 24.0;
    /** The shown ETA's time constant: a share of the time left, between DISPLAY_TC_MIN and DISPLAY_TC_MAX seconds;
     *  DISPLAY_TC_UP when the estimate is above the figure (it would count up: what reads as an instrument that
     *  fluctuates), DISPLAY_TC_DOWN when below (a quicker countdown). Chosen 2026-10-13 on nine lockstep walks: error
     *  at 25/50/75% of a walk 7.9% (the estimate itself 7.4%), the figure going up 0.1 times a minute (the estimate
     *  3.2); the symmetric 0.15 between 2 and 10 s had 10.7%, also 0.1 a minute. */
    public static double DISPLAY_TC_UP = 0.25, DISPLAY_TC_DOWN = 0.05, DISPLAY_TC_MIN = 0.5, DISPLAY_TC_MAX = 10.0;
    /** The first ticks of walking (from rest: the run-up) don't count toward the ratio. */
    public static int WARMUP = 20;
    /** Blocks of sprinting on foot a point of food or saturation lasts: 40 sprinting on the ground (4 exhaustion a
     *  point, 0.1 a block); about 60 sprint-jumping, as layer 2 runs (0.2 a jump, the air not counted; measured
     *  2026-10-13: two points over 120 blocks). The walk sets it for the layer that walks. */
    public double blocksPerPoint = 40.0;

    private TimeProfile profile;
    private Route route;
    private double at = -1, routeAt = -1, prevLeft = Double.NaN;
    private double elapsed, consumed, recentE, recentC;
    private int walkingRun = 0;   // ticks walked since the last stop
    private double detourBlocks, detourTicks;   // the known routes' straight lines and their planned times (as a rate)
    private double leftTicks, footLeft;

    // the shown figure
    private double shown = Double.NaN;
    private long shownNanos = 0;

    /** A new walk: forget everything learned. */
    public void reset() {
        profile = null; route = null;
        at = routeAt = -1; prevLeft = Double.NaN;
        elapsed = consumed = recentE = recentC = 0;
        walkingRun = 0;
        detourBlocks = detourTicks = 0;
        shown = Double.NaN; shownNanos = 0;
    }

    /** The walk goes on after a pause, toward the same destination: what was learned stays; it starts from rest. */
    public void resume() {
        walkingRun = 0;
        prevLeft = Double.NaN;
        shown = Double.NaN; shownNanos = 0;
    }

    /** A new plan's route and its time profile. */
    public void onPlan(TimeProfile p, Route r) {
        profile = p;
        route = r;
        at = -1; routeAt = -1;
        prevLeft = Double.NaN;
        if (p != null && p.straight > 16 && p.lineRate > 0) {
            // how much longer this route takes than its straight line at the line's own rate, for the part past the
            // loaded edge
            detourBlocks += p.straight;
            detourTicks += p.total / p.lineRate;
        }
    }

    public boolean hasPlan() {
        return profile != null;
    }

    /** One tick of the walk: where the player is, and whether it walked this tick (not paused, no screen open). */
    public void tick(double px, double pz, boolean walking) {
        if (profile == null) return;
        at = profile.locate(px, pz, at);
        double left = profile.ticksLeft(at);
        walkingRun = walking ? walkingRun + 1 : 0;
        if (walking && !Double.isNaN(prevLeft) && walkingRun > WARMUP) {
            double used = Math.max(-5.0, Math.min(10.0, prevLeft - left));   // a jump along the path isn't progress
            double decay = Math.exp(-1.0 / RECENT_TC);
            elapsed += 1; consumed += used;
            recentE = recentE * decay + 1; recentC = recentC * decay + used;
        }
        prevLeft = left;
        leftTicks = left;
        footLeft = profile.footLeft(at);
    }

    /** The learned ratio of real to planned time. */
    public double ratio() {
        double total = (elapsed + PRIOR) / Math.max(1e-6, consumed + PRIOR);
        double recent = (recentE + RECENT_PRIOR) / Math.max(1e-6, recentC + RECENT_PRIOR);
        double r = (1 - RECENT_SHARE) * total + RECENT_SHARE * recent;
        return Math.max(0.5, Math.min(4.0, r));
    }

    /** The detour factor past the loaded edge. */
    public double detour() {
        double d = (DETOUR_PRIOR * DETOUR_WEIGHT + detourTicks) / (DETOUR_WEIGHT + detourBlocks);
        return Math.max(1.0, Math.min(3.0, d));
    }

    /** The planned ticks left on the route (no ratio, nothing past the edge). */
    public double plannedTicks() {
        return profile == null ? Double.NaN : leftTicks;
    }

    /** The planned rate on open ground, ticks a block (NaN without a plan). */
    public double flatRate() {
        return profile == null ? Double.NaN : profile.flatRate;
    }

    /** The rate on the straight line toward the goal, ticks a block (NaN without a plan). */
    public double lineRate() {
        return profile == null ? Double.NaN : profile.lineRate;
    }

    /** The straight line on from where the route stops short, blocks (0 if it reaches the goal). */
    public double beyondBlocks() {
        return profile == null ? 0 : profile.beyondBlocks;
    }

    /** The ticks past the loaded edge, before the ratio. */
    public double beyondTicks() {
        return profile == null ? 0 : profile.beyondBlocks * profile.lineRate * detour();
    }

    /**
     * The extra ticks hunger will cost: past the blocks on foot the food and saturation keep sprinting for, the rest
     * at the walking rate. canSprint: sprinting now (food above 6); peaceful: food doesn't drop.
     */
    public double hungerTicks(int food, float saturation, boolean canSprint, boolean peaceful) {
        if (profile == null || !canSprint || peaceful) return 0;
        double points = Math.max(0, food - 6) + Math.max(0, saturation);
        double sprintBlocks = points * blocksPerPoint;
        double foot = footLeft + profile.beyondBlocks;
        if (foot <= sprintBlocks) return 0;
        return (foot - sprintBlocks) * profile.flatRate * (profile.noSprintFactor - 1);
    }

    /** The estimate, seconds: the planned time left, past the edge and hunger's, by the learned ratio. */
    public double rawSeconds(int food, float saturation, boolean canSprint, boolean peaceful) {
        if (profile == null) return Double.NaN;
        double t = (leftTicks + beyondTicks() + hungerTicks(food, saturation, canSprint, peaceful)) * ratio();
        return t / 20.0;
    }

    /** The blocks left along the route from the player (horizontal), and straight on past the loaded edge. */
    public double distanceLeft(double px, double pz) {
        if (route == null || route.n == 0) return Double.NaN;
        routeAt = locateRoute(px, pz, routeAt);
        int k = (int) Math.floor(routeAt);
        double t = routeAt - k, d = 0;
        if (k < route.n - 1) {
            d += Math.hypot(route.x[k + 1] - route.x[k], route.z[k + 1] - route.z[k]) * (1 - t);
            for (int i = k + 1; i + 1 < route.n; i++) d += Math.hypot(route.x[i + 1] - route.x[i], route.z[i + 1] - route.z[i]);
        }
        return d + (profile == null ? 0 : profile.beyondBlocks);
    }

    private double locateRoute(double px, double pz, double hint) {
        int n = route.n;
        if (n < 2) return 0;
        int h = hint < 0 ? 0 : (int) hint;
        int from = hint < 0 ? 0 : Math.max(0, h - 2), to = hint < 0 ? n - 2 : Math.min(n - 2, h + 24);
        double bestD = Double.MAX_VALUE, best = 0;
        for (int k = from; k <= to; k++) {
            double sx = route.x[k], sz = route.z[k], lx = route.x[k + 1] - sx, lz = route.z[k + 1] - sz;
            double len2 = lx * lx + lz * lz;
            double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - sx) * lx + (pz - sz) * lz) / len2));
            double d = Math.hypot(px - (sx + lx * t), pz - (sz + lz * t));
            if (d < bestD - 1e-9) { bestD = d; best = k + Math.min(t, 0.999999); }
        }
        return best;
    }

    /**
     * The figure to show, seconds: counts down a second a second while walking and drifts toward the estimate with
     * the DISPLAY_TC constants; snaps to it on a change of more than half (and 30 s), or after a gap of over a second.
     * While paused it stands (no countdown) and keeps drifting.
     */
    public double shown(double raw, boolean walking, long nowNanos) {
        if (Double.isNaN(raw)) return Double.NaN;
        double dt = shownNanos == 0 ? 0 : (nowNanos - shownNanos) / 1e9;
        shownNanos = nowNanos;
        if (Double.isNaN(shown) || dt <= 0 || dt > 1.0 || Math.abs(raw - shown) > Math.max(30.0, raw * 0.5)) {
            shown = raw;
        } else {
            if (walking) shown = Math.max(0, shown - dt);
            double share = raw > shown ? DISPLAY_TC_UP : DISPLAY_TC_DOWN;
            double tc = Math.max(DISPLAY_TC_MIN, Math.min(DISPLAY_TC_MAX, share * raw));
            shown = shown + (raw - shown) * (1 - Math.exp(-dt / tc));
        }
        return shown;
    }
}
