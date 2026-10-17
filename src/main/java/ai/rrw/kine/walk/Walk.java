package ai.rrw.kine.walk;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.Gui2D;
import ai.rrw.kine.compat.Input;
import ai.rrw.kine.fight.Moves;
import ai.rrw.kine.hud.HudLayout;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.WorldSnapshot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * The walking pathfinder, layer 1: walks you to the destination set in the nav screen's Walk tab. It plans with the
 * table and the search (Planner, on a worker), then follows the route by pressing movement keys, the follower below,
 * which layer 2 will replace.
 *
 * <p>RW's rulings (2026-10-12): walk only, nothing broken or placed; any movement key gives you control back for good
 * (W, A, S, D, jump, sneak, sprint pressed by you), restarted from the tab; its own destination, one X and Z per
 * dimension; free look: the camera is yours and moves only with your mouse, and the mouse never stops the walk.
 * Pausing (RW, later the same day): your keys, the tab's Pause, arriving, or anything else that ends the walk short of
 * a change of dimension pauses it: no more control, but the route, rails and pillar stay drawn (so you can walk back
 * along it), until the tab's Cancel dismisses them. The tab's one button reads Go, then Pause while walking, then
 * Cancel while paused, then Go again. A new destination cancels a walk running or paused toward the old one.
 *
 * <p>The facing. The follower faces where it walks, but only for the server: it hands its facing to the kill aura's
 * rotation (KillAura.beforeKeybinds asks {@link #restRotation} where the rotation sent should rest when not fighting),
 * which turns the player by whole mouse counts at the start of each tick, at most 30 degrees a tick from the last
 * rotation sent, and turns back to your view after the move packet, so the camera never moves. When the kill aura is
 * fighting it holds the rotation, and the follower presses the keys nearest its heading at the aura's rotation. Each
 * tick the keys are the ones (of the eight W/A/S/D directions) nearest the wanted heading at the rotation sent that
 * tick (Moves.keys), sprinting (W among them) while food is above 6.
 *
 * <p>Hand-back. The tick you press a movement key the walk stops; the rotation sent may be up to 180 degrees from your
 * camera and the game moves you by your keys relative to it, so from that tick the kill aura's key swap re-presses
 * your keys as the ones nearest your camera-relative direction at the rotation sent, while that rotation turns back to
 * your camera at most 30 degrees a tick; sprint only where the re-pressed keys include W. Once they match your keys
 * pass through unchanged.
 */
public final class Walk {
    private Walk() {}

    public enum State { IDLE, PLANNING, WALKING, ARRIVED, STOPPED, FAILED }

    private static State state = State.IDLE;
    private static String message = "";
    private static final Map<ResourceKey<Level>, int[]> DEST = new HashMap<>();
    /** Each dimension's destination Y (the feet's height there, the Walk tab's optional Y); none or NaN: any floor of the
     *  column. With one, only that floor counts (within 0.6) for the search, the field, layer 2 and arriving. */
    private static final Map<ResourceKey<Level>, Double> DEST_Y = new HashMap<>();
    /** The walk's destination Y (taken when it starts: a new destination cancels the walk). */
    private static double walkY = Double.NaN;
    /** The highest the player has stood (on the ground, out of water) in this walk, or where it is on a route to the
     *  goal (a way all the way there is trusted to go down): see keepAbove. */
    private static double peakY = Double.NaN;
    /** The last floor a plan started from that was the ground under the feet (replan), and how many times the walk has
     *  gone back to it (retrace). */
    private static long lastStand = View.NONE;
    private static int retraces = 0;
    static final int RETRACE_MAX = 5;
    static final double RETRACE_REACH = 12.0;
    /** How far under peakY a walk with no route to a goal above may still go (a step down, a slab). */
    static final double KEEP_DROP = 1.0;
    private static ResourceKey<Level> dimension = null;

    private static Planner.Job job = null;          // the plan in flight, or the last one
    /** A replan since job was started returned without a search (the terrain not read yet, no floor to start from):
     *  job is the last search, from another place. In PLANNING it is not taken: its plan was made before a fall or a
     *  hop across, and its verdict (no way onto the goal) is not this place's. */
    private static boolean jobStale = false;
    private static Planner.Plan plan = null;        // the route being walked
    private static long planId = -1;
    private static int planPass = -1;
    /** The last plan from a job looked at (adopted or not: a plan to the floor nearest an unreachable goal may be
     *  passed over for walking without a route). */
    private static long seenId = -1;
    private static int seenPass = -1;
    /** The plan to the floor nearest an unreachable goal whose end has been searched from once more. */
    private static long nearestCheckedId = -1;
    /** The loosened layer 1's memory for this walk (Loose, LooseCheck; cleared by Go): moves found not to work (a loose
     *  move whose check in the movement copy failed, or any move the walk stood before while the jump search found no
     *  way over it), left out of every search after; and what the checks found. */
    private static final java.util.Set<Long> blocked = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The blocked moves now, as a set another thread can read (layer 2's field leaves them out as the plan does). */
    static it.unimi.dsi.fastutil.longs.LongOpenHashSet blockedNow() {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet s = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(blocked.size());
        for (Long e : blocked) s.add(e.longValue());
        return s;
    }
    private static final LooseCheck.Memo looseMemo = new LooseCheck.Memo();
    /** The last loose plan looked at. */
    private static long seenLooseId = -1;
    private static int seenLoosePass = -1;
    /** Moves blocked this walk because the walk stood before them and the jump search found no way (for the report). */
    public static int movesBlocked = 0;
    /** The last search found no way onto the goal (a plan only to the floor nearest it, or nothing nearer than here):
     *  a walk that then makes no progress (walking without a route stood at a wall or an edge) ends as near as it gets,
     *  not "Stuck" (RW, 2026-10-13). Cleared by a plan with a way onto the goal or toward the loaded edge. */
    private static boolean noWaySeen = false;
    /** Walking without a route to a floor past where a far goal's route stops (Hop): that floor's column and height,
     *  its node, the floors tried this walk, the nearest the walk has stood to the goal, the hops made, and the height
     *  the current one started from (a fall well below it ends the hops). */
    private static int[] hop = null;
    private static double hopY = Double.NaN, hopFromY = Double.NaN;
    private static long hopNode = View.NONE;
    private static final java.util.Set<Long> hopsTried = new java.util.HashSet<>();
    /** Each floor gets a second try before it joins hopsTried (a copy-found jump dropped part way, as over
     *  slime_bounce's slime, is often made on the next). */
    private static final java.util.Map<Long, Integer> hopTries = new java.util.HashMap<>();
    /** Arrivals on each hop floor since the walk last got a real step nearer the goal (HOP_PROGRESS blocks; with a Y, its
     *  time at the fastest speed, as Hop measures nearness). */
    private static final java.util.Map<Long, Integer> hopArrivals = new java.util.HashMap<>();
    static final double HOP_PROGRESS = 1.0;
    static final int HOP_TRIES = 2;
    /** Ticks standing still after a hop's jump searches found nothing before the hop is given up (see endTick). */
    static final int HOP_GIVE_UP = 60;
    /** The nearest the walk has stood to the goal, as Hop measures it (with a Y, the climb counted). */
    private static final Hop.Best hopBest = new Hop.Best();
    private static int hops = 0;
    private static boolean hopsOff = false;
    /** At most this many hops in a walk; hops tried in a row without getting across before the walk ends; how far a
     *  hop's floor may be (the longest jump of the catalogue is 5.4 blocks past its take-off's edge: layer 2 without a
     *  route crosses one thing, and a floor farther on needs layer 1 between); the no-progress replans a hop gets. */
    static final int HOP_MAX = 200, HOP_FAILS = 3, HOP_STUCK = 2;
    static final double HOP_REACH = 8.0;
    private static int hopFails = 0;
    /** The tick the walk first stood on the hop's floor (-1: not yet); how long it may take to come to rest there. */
    private static int hopOnSince = -1;
    /** A hop arrived on a climbable: its plan without a route holds on until layer 1's next plan, which replaces it. */
    private static boolean hopTaken = false;
    static final int SETTLE_TICKS = 30;

    /** On a climbable that holds the player where it is (a ladder, a vine), for the arrivals; not scaffolding
     *  (2026-10-15): inside its top piece, arriving there let go of the keys and the player sank onto the piece under
     *  it (the course's scaffolding_jump: the top is arrived at standing on it). */
    private static boolean holdingClimb(LocalPlayer p) {
        return p.onClimbable() && !p.getInBlockState().is(net.minecraft.world.level.block.Blocks.SCAFFOLDING);
    }

    /** Still: no more horizontal speed than layer 2's jump search allows for a standing start. */
    private static boolean standing(LocalPlayer p) {
        var v = p.getDeltaMovement();
        return p.onGround() && v.x * v.x + v.z * v.z <= 1e-4;
    }

    /** How near the goal the walk stands, as Hop.pick measures floors: from the block it stands in (its column's corner
     *  to the goal column's). Measured from the player's position, a step behind its block's centre made the floor
     *  beside it (the pad it had come from) count as nearer, and the walk hopped back onto it. */
    private static double standDistance(LocalPlayer p, int[] d) {
        return Math.hypot(d[0] - p.getBlockX(), d[1] - p.getBlockZ());
    }

    /** The walk stood at the feet's height y, horizontal distance g from the goal column: into hopBest; whether that was
     *  a real step (HOP_PROGRESS) nearer than the best before. */
    private static boolean hopStood(double g, double y) {
        Costs c = costs();
        double n = Hop.nearness(c, g, y, walkY);
        boolean step = n < hopBest.n - Hop.progress(c, walkY, HOP_PROGRESS);
        hopBest.stood(n, g, Hop.tie(c, walkY));
        if (step) hopsBlind = 0;
        return step;
    }

    /** Hops since the walk last stood a real step nearer the goal (hopStood): HOP_MAX bounds these, not the walk's hops
     *  (200 in a walk stopped the Kine Parkour course's unbroken walk short of its end: about two hops a jump, 121 jumps;
     *  cH1 stood at 196 by #93, and a fresh Go reset it). */
    private static int hopsBlind = 0;

    /** The tick the walk came onto the hop's floor (now, the first time asked). */
    private static int hopOnTick(int now) {
        if (hopOnSince < 0) hopOnSince = now;
        return hopOnSince;
    }
    private static int tick = 0, lastPlanTick = 0, lastCheckTick = 0;
    private static int seg = 1;                     // the waypoint walked toward
    private static double bestLeft = Double.MAX_VALUE;
    private static int bestLeftTick = 0, stuckReplans = 0, replans = 0;
    private static double progressed = 0;           // route length gained since the last no-progress replan
    /** Route length gained that clears the count of no-progress replans: "stuck" is several of them in a row. */
    static final double REAL_PROGRESS = 8.0;
    private static boolean handBack = false;
    // whether the search display replays the plan in flight: a walk's first plan and replans that change the way do;
    // routine refreshes toward a far goal only cross-fade the route (RW, 2026-10-13)
    private static boolean jobReplay = true, replayNext = true;
    private static int settleWaits = 0;
    /** Why the last replans happened (for tests and the report). */
    public static final java.util.List<String> replanWhy = new java.util.ArrayList<>();

    // this tick's steering, set at the tick's start
    private static boolean steering = false;
    /** Layer 2 steers this tick (else the layer 1 follower). */
    private static boolean layer2 = false;
    private static float wantYaw = 0f;
    private static boolean wantJump = false, wantSprint = false, wantKeys = false;

    /** The move costs layer 1 plans with: Baritone's for its own follower, the ones fitted from layer 2's runs when
     *  layer 2 walks (Costs.layer2); layer 2's field uses the same. */
    static Costs costs() {
        Costs base = Costs.forGait(Settings.walkLayer2 ? Costs.LAYER2 : Costs.BARITONE, gait());
        int hp = fallAllowance();
        return hp <= 0 ? base : Costs.withFallDamage(base, hp);
    }

    /**
     * The safe fall of the jump search and the checks (Recipe.safeFall): the costs' (above), and toward a far goal too
     * the whole Fall damage allowed (fallAllowance keeps it from layer 1's routes toward a far goal: their nearest
     * floor lay on the ground under the Kine Parkour course). A jump the search picks lands where it aims, so the
     * damage is its own choice of jump: the course's down_6m3 and down_7p0625m5_record land from falls past the safe 3,
     * and on the way to the course's far target their templates were judged at the safe fall alone and never fitted
     * (c108e: safe fall 3.90 with 6 allowed).
     */
    static double jumpSafeFall() {
        Costs base = Costs.forGait(Settings.walkLayer2 ? Costs.LAYER2 : Costs.BARITONE, gait());
        int hp = fallAllowanceAnyGoal();
        return hp <= 0 ? base.safeFall : Costs.withFallDamage(base, hp).safeFall;
    }

    /** As fallAllowance, toward any goal (the health guard kept). */
    static int fallAllowanceAnyGoal() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || Settings.walkFallDamage <= 0) return 0;
        return Math.max(0, Math.min(Settings.walkFallDamage, (int) Math.floor(p.getHealth()) - 8));
    }

    /** Health the walk may lose to harmful blocks (cactus) now: Settings.walkHarmfulBlocks (half hearts),
     *  with Parkour, never below 8 left (four hearts), as ticks of touching them (Recipe.HARM_TICKS_PER_HP a half
     *  heart); 0: none. */
    static int harmAllowance() {
        if (Settings.walkHarmfulBlocks <= 0 || !Settings.walkParkour) return 0;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return 0;
        int hp = Math.max(0, Math.min(Settings.walkHarmfulBlocks, (int) Math.floor(p.getHealth()) - 8));
        return hp * Recipe.HARM_TICKS_PER_HP;
    }

    /** Health the walk may lose to a drop now (Settings.walkFallDamage, half hearts), never below 8 left (four hearts):
     *  a drop of the safe 3 plus this hurts no more (Minecraft's fall damage is the fall past 3, rounded up, before
     *  armour; armour and Feather Falling only lower it). */
    static int fallAllowance() {
        if (Settings.walkFallDamage <= 0) return 0;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return 0;
        // only toward a near goal (NEAR_GOAL): toward a far one the floor nearest it is the way's end, chosen by distance
        // whatever the falls to it, and with damage allowed it lay on the ground under the Kine Parkour course: the walk
        // dropped off the course after #5 and stopped at #6 (RW's settings from 2026-10-16 04:03, Fall damage allowed 2,
        // for the Parkour Spiral's leg 13; the gate's q39_g2a, CB400_40, CB_41)
        if (!nearGoal(destination())) return 0;
        return Math.max(0, Math.min(Settings.walkFallDamage, (int) Math.floor(p.getHealth()) - 8));
    }

    /** The gait the walk may use now: Settings.walkGait (the most it may use; it still jumps where the ground needs), or
     *  more while the alpha floor is on (AlphaFloor: something chasing you or about to hurt you). */
    public static int gait() {
        return AlphaFloor.raise(Settings.walkGait);
    }

    /** The gait the last tick walked at (a change replans: the alpha floor on or off). */
    private static int lastGait = -1;

    /** Whether the walk may sprint now: the gait allows it, and the game does (food above 6). */
    static boolean canSprint(LocalPlayer p) {
        return gait() >= Settings.GAIT_SPRINT && p.getFoodData().getFoodLevel() > 6;
    }

    /** The walk's keys under the gait: at Walk no sprint key, the double-tap sprint's window closed (a fresh W press
     *  within it starts a sprint; this runs in KeyboardInput.tick, before LocalPlayer.aiStep looks at it), and a sprint
     *  already running ended (it carries on by itself while W is held). The movement copy does the same (RunModel). */
    private static Input underGait(LocalPlayer p, Input k) {
        if (gait() >= Settings.GAIT_SPRINT) return k;
        ((ai.rrw.kine.mixin.MoveLocalPlayerAccessor) p).kine$setSprintTriggerTime(0);
        if (p.isSprinting()) p.setSprinting(false);
        return k.sprint() ? new Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), k.shift(), false) : k;
    }
    /** The strict search's time for a walk's first plan and for a replan: 1000 and 500 ms (were 400 and 200). On RW's
     *  loaded machine the first plan ran out of 400 ms in every walk logged on 2026-10-16 (plan 1 "out of time": a partial
     *  route walked toward the sea's edge while the loose job searched from the start, the corner at 02:15, the swaps at
     *  03:14 and 03:56) where the same search ended in time in the lockstep tests (no way: the loose job at once). RW
     *  03:59: a cap is not a strict constraint, raised within reason. KINE_PLAN_BUDGET_MS (tests) sets both. */
    public static final long FIRST_BUDGET = budget(1_000_000_000L), REPLAN_BUDGET = budget(500_000_000L);

    private static long budget(long nanos) {
        String ms = System.getenv("KINE_PLAN_BUDGET_MS");
        try { return ms == null ? nanos : Long.parseLong(ms.trim()) * 1_000_000L; } catch (NumberFormatException e) { return nanos; }
    }
    /** Ticks a walk started in water with no floor found waits for the float to bring the player up to open water. */
    static final int UNDER_WATER_WAIT = 400;

    // measured, for tests and the report
    public static long clientNanos = 0, clientMaxNanos = 0, clientTicks = 0;
    public static int plans = 0;
    public static String lastWhy = "";

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Walk::endTick);
        Compat.hud("walk", Walk::render);
    }

    // ---------------------------------------------------------------- the destination and the controls

    public static State state() { return state; }
    public static String message() { return message; }
    public static Planner.Plan plan() { return plan; }
    /** The walk's first plan (its last pass), for the report: the whole route from the start. */
    public static Planner.Plan firstPlan() { return firstPlan; }
    private static Planner.Plan firstPlan = null;
    public static int replans() { return replans; }

    // the arrival time
    private static final Eta eta = new Eta();
    private static double etaShown = Double.NaN, etaRaw = Double.NaN, etaDist = Double.NaN;
    private static int[] etaFor = null;   // the destination the ETA learned toward

    /** Whether the walk's distance and ETA are on show: while it walks you or plans, and paused with its route. */
    public static boolean etaShowing() {
        return plan != null && (active() || state == State.STOPPED) && !Double.isNaN(etaShown);
    }

    /** The ETA to show, seconds (smoothed: counts down a second a second, stands while paused); NaN if none. */
    public static double etaSeconds() { return etaShown; }

    /** The ETA's estimate before smoothing, seconds (tests). */
    public static double etaRawSeconds() { return etaRaw; }

    /** The blocks left along the route, and straight on past where it stops short; NaN if none. */
    public static double distanceLeft() { return etaDist; }

    /** The ETA's model (tests read its parts). */
    public static Eta eta() { return eta; }

    /** Whether the walk is in control (planning or walking). */
    public static boolean active() {
        return state == State.PLANNING || state == State.WALKING;
    }

    /** Whether a walk that no longer controls you is still drawn: paused, arrived, or failed with a route. */
    public static boolean showing() {
        return !active() && plan != null && (state == State.STOPPED || state == State.ARRIVED || state == State.FAILED);
    }

    /** What the tab's one button does now. */
    public enum Button { GO, PAUSE, CANCEL }

    public static Button button() {
        return active() ? Button.PAUSE : showing() ? Button.CANCEL : Button.GO;
    }

    private static boolean dismissed = false;   // Cancel pressed: the pillar hidden until a new destination or Go

    /** Whether the destination's pillar is hidden by a Cancel. */
    public static boolean dismissed() {
        return dismissed;
    }

    /** Whether this tick's step is the walk's own, checked safe, so fall prevention lets it through. With layer 2: its
     *  plan's landing was checked in the movement copy (no fall that hurts). With layer 1's follower: its own step into
     *  water, into deep water or a drop onto a floor in water, with water still at the landing (RW, 2026-10-12); on
     *  the rest of the walk fall prevention guards as usual. */
    public static boolean ownStepSafe(LocalPlayer p) {
        if (state != State.WALKING || !steering || plan == null) return false;
        // layer 2 checked its plan's landing in the movement copy (no fall that hurts): let it through
        if (layer2) return Layer2.safe();
        Route r = plan.route;
        if (seg < 1 || seg >= r.n) return false;
        int m = r.move[seg];
        if (m != Route.WATER_IN && m != Route.DROP) return false;
        double tx = r.x[seg], ty = r.y[seg], tz = r.z[seg];
        if (Math.hypot(tx - p.getX(), tz - p.getZ()) > 2.0) return false;
        // the block the feet land in (a pool to stand in), or the one under the floor height (the top of deep water,
        // or a waterlogged floor)
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(tx), Mth.floor(ty + 1e-4), Mth.floor(tz));
        if (Compat.level(p).getFluidState(pos).is(FluidTags.WATER)) return true;
        pos.setY(Mth.floor(ty - 1e-4));
        return Compat.level(p).getFluidState(pos).is(FluidTags.WATER);
    }

    /** This dimension's walk destination {x, z}, or null. */
    public static int[] destination() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : DEST.get(mc.level.dimension());
    }

    /** This dimension's destination Y (feet), NaN when none was given: any floor of the column. */
    public static double destinationY() {
        Minecraft mc = Minecraft.getInstance();
        Double y = mc.level == null ? null : DEST_Y.get(mc.level.dimension());
        return y == null ? Double.NaN : y;
    }

    /** The destination, any floor of its column. */
    public static void setDestination(int x, int z) {
        setDestination(x, z, Double.NaN);
    }

    /** The destination with its optional Y (feet; NaN: any floor of the column). Another column, or another floor of the
     *  same one, is a new destination. */
    public static void setDestination(int x, int z, double y) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        int[] old = DEST.put(mc.level.dimension(), new int[]{x, z});
        Double oldY = DEST_Y.put(mc.level.dimension(), y);
        boolean yChanged = oldY == null ? !Double.isNaN(y) : !(Double.isNaN(oldY) && Double.isNaN(y) || oldY == y);
        if (old == null || old[0] != x || old[1] != z || yChanged) {
            // a new destination: a walk running or paused toward the old one is cancelled, back to Go (RW), and the
            // new one's pillar shows
            if (active() || showing()) cancel();
            dismissed = false;
        }
    }

    /** Walk to this dimension's destination (the Walk tab's Go). */
    public static void go() {
        Minecraft mc = Minecraft.getInstance();
        int[] d = destination();
        if (!Settings.walkPathfinder || mc.player == null || d == null) return;
        dimension = mc.level.dimension();
        walkY = destinationY();
        peakY = mc.player.getY();
        lastStand = View.NONE;
        retraces = 0;
        gapSeg = -1;
        wasOnGround = true;
        looseFalls.clear();
        fellGaps.clear();
        dismissed = false;
        endPreview();
        Afloat.disarm();
        if (job != null) job.cancelled = true;   // an earlier walk's search: its plan is not for this walk
        job = null;
        jobStale = false;
        jobKeep = Double.NaN;
        planKeep = Double.NaN;
        state = State.PLANNING;
        message = "Planning";
        plan = null;
        planId = -1;
        seenId = -1;
        AlphaFloor.reset();
        lastGait = -1;
        seg = 1;
        handBack = false;
        stuckReplans = 0;
        noWaySeen = false;
        hop = null; hopY = Double.NaN; hopFromY = Double.NaN; hopNode = View.NONE;
        hopsTried.clear(); hopTries.clear(); hopArrivals.clear(); hopBest.reset(); hops = 0; hopsBlind = 0; hopsOff = false; hopFails = 0; hopOnSince = -1; hopTaken = false;
        hopLeft = View.NONE;
        hopsChained = 0;
        blocked.clear();
        looseMemo.clear();
        DoorOpener.reset();
        seenLooseId = -1;
        seenLoosePass = -1;
        movesBlocked = 0;
        progressed = 0;
        replans = 0;
        replanWhy.clear();
        settleWaits = 0;
        firstPlan = null;
        bestLeft = Double.MAX_VALUE;
        bestLeftTick = tick;
        // what the ETA learned on the way (its ratio, the detour past the loaded edge) carries over a pause to the same
        // destination; a new destination starts afresh
        if (etaFor == null || etaFor[0] != d[0] || etaFor[1] != d[1]) eta.reset(); else eta.resume();
        etaFor = d.clone();
        etaShown = etaRaw = etaDist = Double.NaN;
        Layer2.reset();
        replayNext = true;
        replan(mc, FIRST_BUDGET);
    }

    // a plan shown without walking (tests, screenshots)
    private static Planner.Job previewJob = null;
    private static long previewShownId = -1;
    private static int previewShownPass = -1;
    private static boolean previewing = false;

    /** Plan from the floor at (x, y, z) to column (gx, gz) and show it, without walking (tests and screenshots). */
    public static void preview(int x, double y, int z, int gx, int gz) {
        View v = WalkTable.snapshot();
        long start = v.nodeAt(x, y, z);
        if (start == View.NONE) { lastWhy = "preview: no floor at the start"; return; }
        previewJob = Planner.plan(v, start, gx, gz, costs(), margin(), true, parkour(), FIRST_BUDGET);
        previewing = true;
    }

    /** The preview's latest plan, or null. */
    public static Planner.Plan previewPlan() {
        return previewJob == null ? null : previewJob.latest.get();
    }

    /** Whether a preview is on show (WalkRender keeps it instead of fading it). */
    static boolean previewing() {
        return previewing;
    }

    public static void endPreview() {
        previewing = false;
        previewJob = null;
    }

    /** Pause: control back to you, the route still drawn (the tab's Pause, your keys, or another reason). */
    public static void stop(String why) {
        if (job != null) job.cancelled = true;
        if (active()) {
            state = State.STOPPED;
            message = why.isEmpty() ? "Paused" : "Paused: " + why;
            Afloat.arm();   // in water the head kept above it until your keys (taking over by them ends it at once)
        }
        steering = false;
        RunModel.keepAboveNow = Double.NaN;
    }

    /** Cancel: control back if it was still walking, and the route, rails and pillar dismissed (the tab's Cancel, a
     *  change of dimension, the pathfinder switched off). */
    public static void cancel() {
        if (job != null) job.cancelled = true;
        job = null;
        Layer2.reset();
        state = State.IDLE;
        message = "";
        steering = false;
        plan = null;
        planId = -1;
        dismissed = true;
    }

    private static void fail(String why) {
        if (job != null) job.cancelled = true;
        state = State.FAILED;
        message = why;
        steering = false;
        Afloat.arm();
        lastWhy = why;
        Minecraft mc = Minecraft.getInstance();
        Compat.actionBar(mc, Component.literal("Walk: " + why));
    }

    private static void replan(Minecraft mc, long budget) {
        LocalPlayer p = mc.player;
        int[] d = destination();
        if (p == null || d == null) return;
        if (jobBusy()) return;   // one at a time
        if (forceDirect && plan == null) {
            Planner.Plan dp = directPlan(p, d, "forced (test)");
            if (dp != null) { adoptDirect(dp); return; }
        }
        if (forceDirect && plan != null && plan.direct) return;   // no layer 1 refreshes either
        // plan on an up-to-date table around the player: a block just changed there may not be in it yet
        if (!WalkTable.settled(p.getBlockX(), p.getBlockZ(), 1) && settleWaits++ < 40) {
            message = "Reading the terrain";
            jobStale = true;
            return;
        }
        View view = WalkTable.snapshot();
        long start = startNode(view, p);
        if (start != View.NONE && p.onGround() && !p.isInWater() && !p.onClimbable()) {
            if (groundAt(view, start, p.getY())) lastStand = start;
            else if (floorOver(view, start, p.getY()) && retrace(p, view, start)) return;
        }
        if (start == View.NONE) {
            jobStale = true;
            if (settleWaits++ < 40) { message = "Reading the terrain"; return; }
            // in water with no floor over it (a roof, lily pads, deeper than 24): the float (Afloat, on the ticks it plans)
            // takes the player to open water, whose top is a floor to start from
            if (p.isInWater() && settleWaits < UNDER_WATER_WAIT) { message = "Coming up for air"; return; }
            if (plan == null) fail("can't tell where you stand");
            return;
        }
        settleWaits = 0;
        boolean canSprint = canSprint(p);
        boolean glides = Settings.walkLayer2 && Settings.walkElytraHops && WorldSnapshot.body(p).canGlide;
        // the loosened layer 1 as the fallback (with Parkour on: its moves are parkour), and the moves found not to work
        // left out of the strict search too
        // a walk on a found loose route searches the loose way again at once (Planner: loose.first), and the strict job's
        // pass that falls short isn't walked meanwhile (the loose search runs: the walk keeps the route it has)
        boolean onLoose = plan != null && !plan.direct && plan.result.found && plan.looseMoves > 0;
        Planner.LooseContext loose = parkour() && Settings.walkLoose
            ? new Planner.LooseContext(blocked, looseMemo, GridRequests::around, WorldSnapshot.body(p), WalkTable.blockChanges, canSprint,
                onLoose)
            : null;
        if (job != null) job.cancelled = true;   // a loose job still checking the old plan's moves lets go
        Graph.doorsOn = Settings.walkOpenDoors;
        Recipe.safeFall = jumpSafeFall();   // the jump search and the checks take the walk's safe fall
        Recipe.harmTicks = harmAllowance();   // and its harmful-blocks allowance
        jobKeep = keepAbove(p.getY());
        job = Planner.plan(view, start, d[0], d[1], walkY, costs(), margin(), canSprint, parkour(), glides, budget, loose,
            jobKeep);
        jobStale = false;
        lastPlanStart = start;
        lastPlanTerrain = Layer2.terrain();
        jobReplay = replayNext;
        replayNext = true;
        lastPlanTick = tick;
        plans++;
    }

    /** A plan is being made: the strict search runs, or the loose one runs and has nothing yet (a new plan would cancel
     *  it before it has one). */
    private static boolean jobBusy() {
        if (job == null) return false;
        if (!job.done) return true;
        Planner.Job lj = job.loose;
        return lj != null && !lj.done && lj.latest.get() == null;
    }

    /** Whether the walk stands before a loose move of its plan whose check in the movement copy is still running (the
     *  loose job checks the route's loose moves in order ahead of the walk): it waits for it, no stuck time. */
    private static boolean waitingForCheck() {
        return plan != null && plan.checkedTo != Integer.MAX_VALUE && job != null && job.loose != null && !job.loose.done
            && Layer2.waitingForCheck();
    }

    /** The move from the floor the walk stands on to the jump's landing the jump search found no way onto: blocked with
     *  its family (Loose.family), so every search after leaves it out. True if blocked. */
    private static boolean blockStalledJump(LocalPlayer p) {
        double[] l = Layer2.lastRoutedLanding();
        if (l == null) return false;
        View v = WalkTable.snapshot();
        long from = startNode(v, p);
        long to = v.nodeAt((int) Math.floor(l[0]), l[1], (int) Math.floor(l[2]));
        if (from == View.NONE || to == View.NONE || from == to) return false;
        for (long e : Loose.family(v, from, to)) blocked.add(e);
        movesBlocked++;
        return true;
    }

    /** A jump the route's gap move (GAP) was making: its take-off and landing floors and the lower of their heights;
     *  gapSeg -1 for none. */
    private static int gapSeg = -1;
    private static long gapFrom = View.NONE, gapTo = View.NONE;
    private static double gapLow = Double.NaN;
    private static boolean wasOnGround = true;

    /**
     * A gap jump that fell short: the player left the ground near a GAP move's take-off (within GAP_NEAR, at its height)
     * and came down more than a block under both its take-off and its landing. A loose move that fell so twice is
     * blocked with its family (LOOSE_FALLS). The proven move is blocked (its key only,
     * Loose.strictEdge) and the walk plans again: the loose search offers the same jump, checked in the copy and made
     * from rest at the take-off by the jump search, where layer 2's own run had made it on the go. The Parkour Spiral's
     * leg 18 (f48 g3a): layer 2's run took the 4-block gap at 123 from 1.3 short of its edge, ran its side into the wall
     * beside it, caught the corner of a brick, jumped on from there and fell 3; five times round, then the time-out.
     * True when it planned again.
     */
    private static boolean watchGap(LocalPlayer p, Route r, Minecraft mc) {
        boolean ground = p.onGround() || p.isInWater() || p.onClimbable();
        boolean left = wasOnGround && !ground;
        wasOnGround = ground;
        if (r == null || plan.direct) { gapSeg = -1; return false; }
        // the jump's move: the segment walked toward or one of the next two (layer 2's runs jump on the go, a
        // waypoint or two before the follower's place has come to the gap: RW's far walk cleg1, f58, fell at the
        // gap at (-28,63,-39) four times round with the place one segment short at the take-off)
        int k = -1;
        if (left && gapSeg < 0 && seg > 0)
            for (int j = seg; j < Math.min(r.n, seg + 3) && k < 0; j++)
                if ((r.move[j] == Route.GAP || r.move[j] == Route.LOOSE)
                    && Math.hypot(p.getX() - r.x[j - 1], p.getZ() - r.z[j - 1]) <= GAP_NEAR && Math.abs(p.getY() - r.y[j - 1]) < 0.6)
                    k = j;
        if (k > 0) {
            gapRecipe = Layer2.playingRecipe();
            View v = WalkTable.snapshot();
            // the move's own floors from the plan's path (the route's points can lie where the table has no floor: a
            // route planned from a chorus stem's top starts there)
            long[] mn = moveNodes(plan, r.move[k], r.x[k], r.y[k], r.z[k]);
            gapFrom = mn != null ? mn[0] : v.nodeAt((int) Math.floor(r.x[k - 1]), r.y[k - 1], (int) Math.floor(r.z[k - 1]));
            gapTo = mn != null ? mn[1] : v.nodeAt((int) Math.floor(r.x[k]), r.y[k], (int) Math.floor(r.z[k]));
            gapLow = Math.min(r.y[k - 1], r.y[k]);
            gapLoose = r.move[k] == Route.LOOSE;
            gapSeg = gapFrom != View.NONE && gapTo != View.NONE && gapFrom != gapTo ? k : -1;
            return false;
        }
        // a copy-found jump still playing (a drop onto slime and the bounce off it is one) has not come down yet
        if (gapSeg < 0 || !ground || Layer2.playingRecipe()) return false;
        boolean fell = p.getY() < gapLow - 1.0;
        gapSeg = -1;
        if (!fell) {
            // a copy-found jump for a loose move that came down short of its landing at the take-off's height: counted
            // with the falls (the 03:14 case, f67: the step from a chorus stem onto the flower beside it, 93 jumps found
            // and played, each back on the stem)
            View v = WalkTable.snapshot();
            ChunkFloors lc = v.chunk(View.nodeX(gapTo), View.nodeZ(gapTo));
            int li = lc == null ? -1 : v.index(gapTo);
            boolean onLanding = li >= 0 && Math.abs(p.getY() - lc.h(li)) < 0.6
                && Math.hypot(p.getX() - View.nodeX(gapTo) - 0.5, p.getZ() - View.nodeZ(gapTo) - 0.5) < 1.2;
            if (!gapLoose || !gapRecipe || onLanding) return false;
        }
        String at = String.format(java.util.Locale.ROOT, "%d %d to %d %d", View.nodeX(gapFrom), View.nodeZ(gapFrom), View.nodeX(gapTo),
            View.nodeZ(gapTo));
        if (!gapLoose) {
            fellGaps.add(new long[]{gapFrom, gapTo});
            blocked.add(Loose.strictEdge(gapFrom, gapTo));
            movesBlocked++;
            replanFor("a gap jump fell short: as a checked jump (" + at + ")", mc);
            return true;
        }
        // a loose jump, checked in the copy, that fell short live twice: blocked with its family (once may be the
        // game's own jitter; the same jump from the same floor again and again found the same fall: leg 18's loop)
        if (looseFalls.merge(Loose.edge(gapFrom, gapTo), 1, Integer::sum) < LOOSE_FALLS) return false;
        View v = WalkTable.snapshot();
        for (long e : Loose.family(v, gapFrom, gapTo)) blocked.add(e);
        movesBlocked++;
        replanFor("a loose jump missed twice: planning round it (" + at + ")", mc);
        return true;
    }

    private static boolean gapLoose = false, gapRecipe = false;
    /** The gaps that fell short live this walk (take-off, landing), for layer 2's approach to their loose versions. */
    private static final java.util.List<long[]> fellGaps = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Whether a loose move from `from` to `to` takes the place of a gap that fell short this walk: its take-off and its
     *  landing each within a column of the gap's (the loose search may offer the jump from the floor beside). */
    static boolean gapFell(long from, long to) {
        for (long[] g : fellGaps)
            if (Math.abs(View.nodeX(g[0]) - View.nodeX(from)) <= 1 && Math.abs(View.nodeZ(g[0]) - View.nodeZ(from)) <= 1
                && Math.abs(View.nodeX(g[1]) - View.nodeX(to)) <= 1 && Math.abs(View.nodeZ(g[1]) - View.nodeZ(to)) <= 1) return true;
        return false;
    }
    /** Live falls of each loose move this walk (Loose.edge). */
    private static final java.util.Map<Long, Integer> looseFalls = new java.util.HashMap<>();
    static final int LOOSE_FALLS = 2;

    /** The take-off and landing floors of the plan's move of kind mv landing at (x, y, z) (its column, within 0.6 of
     *  its height), or null. */
    private static long[] moveNodes(Planner.Plan pl, int mv, double x, double y, double z) {
        if (pl == null || pl.direct) return null;
        PathSearch.Result res = pl.result;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int i = 1; i < res.path.length; i++)
            if (res.moves[i] == mv && View.nodeX(res.path[i]) == bx && View.nodeZ(res.path[i]) == bz && Math.abs(res.pathY[i] - y) < 0.6)
                return new long[]{res.path[i - 1], res.path[i]};
        return null;
    }

    /** How far from a gap move's take-off a jump counts as that move's (blocks): the run-up's last steps. */
    static final double GAP_NEAR = 2.5;

    /** Take a new plan as the route (the first one starts the walk). */
    private static void adoptPlan(LocalPlayer p, Planner.Plan np, String why) {
        hop = null;   // layer 1 has a plan again: the hop, if any, is over
        hopTaken = false;
        if (firstPlan == null || firstPlan.id == np.id) firstPlan = np;
        plan = np;
        planId = np.id;
        planPass = np.pass;
        seg = startSegment(p, plan.route);
        bestLeft = Double.MAX_VALUE;
        bestLeftTick = tick;
        eta.onPlan(np.time, np.route);
        state = State.WALKING;
        message = why;
        WalkRender.newPlan(np, jobReplay);
    }

    /** The start floor and the terrain's version (Layer2.terrain) of the last plan asked for. */
    private static long lastPlanStart = View.NONE, lastPlanTerrain = -1;

    /** Whether a replan now would search what the last one did: the same start floor, nothing loaded or changed since,
     *  and the last search not cut short by its budget (a partial result). */
    private static boolean sameAsLastPlan(LocalPlayer p) {
        if (plan == null || plan.result.partial || Layer2.terrain() != lastPlanTerrain) return false;
        return startNode(WalkTable.snapshot(), p) == lastPlanStart;
    }

    /** Whether the walk may jump gaps: the Parkour setting (off by default: a jump that falls short lands in the gap;
     *  RW, 2026-10-13: "parkour needs to e togalble since its rksky"), and only when layer 2 walks (the layer 1
     *  follower has no way to make a jump). */
    static boolean parkour() {
        return Settings.walkLayer2 && Settings.walkParkour;
    }

    /** How near the goal must be (straight line, blocks) for walking without a route when layer 1 finds none. Set by
     *  the planning cost (Layer2DirectTest, 2026-10-13): Field.direct's box builds in 5.8 ms at 48 blocks on this
     *  machine (8.7 at 64: it grows with the box's area), and layer 2 alone arrived in 12 of 12 walks at every distance
     *  out to 48 over scattered pillars and pits; past that the box's cost grows faster than its use (a goal that far
     *  with no route is most likely cut off for good). */
    static final double DIRECT_NEAR = 48.0;

    /** Walking without a route (Settings.walkDirect, layer 2 walking): when layer 1 found none and the goal's column is
     *  loaded and within DIRECT_NEAR; null otherwise. */
    /** Tests: walk without a route from the start (layer 2 alone), whatever layer 1 would find. */
    public static boolean forceDirect = false;

    private static void adoptDirect(Planner.Plan dp) {
        firstPlan = dp;
        plan = dp;
        planId = dp.id;
        planPass = dp.pass;
        seg = 1;
        bestLeft = Double.MAX_VALUE;
        bestLeftTick = tick;
        eta.onPlan(null, dp.route);
        state = State.WALKING;
        message = "No route: walking with the movement search";
        WalkRender.newPlan(dp, false);
    }

    private static Planner.Plan directPlan(LocalPlayer p, int[] d, String why) {
        if (!Settings.walkLayer2 || !Settings.walkDirect) return null;
        if (Math.hypot(d[0] + 0.5 - p.getX(), d[1] + 0.5 - p.getZ()) > DIRECT_NEAR) return null;
        View view = WalkTable.snapshot();
        if (!view.known(d[0], d[1])) return null;
        long start = startNode(view, p);
        if (start == View.NONE) return null;
        return Planner.direct(view, start, d[0], d[1], walkY, costs(), margin(), canSprint(p));
    }

    /**
     * Standing where the walk table has no floor (between a fence's posts: the table has the fence's top, 1.5 up, as that
     * column's floor), a plan from there starts where the player isn't: back to the last floor a plan started from (the
     * way it came, as walking without a route with layer 2: its search in the movement copy has the real blocks), and a
     * plan from there. On the Parkour Spiral's first level a jump landed in a pocket between fences at (-5,56,-44): the
     * walk planned from the fence's top and stood until stuck (leg 0's start to checkpoint 2, 3 of 4 tries; RW: "it can
     * just retrace its steps, but it's not finding that"). True when taken.
     */
    private static boolean retrace(LocalPlayer p, View view, long start) {
        if (lastStand == View.NONE || lastStand == start || retraces >= RETRACE_MAX || !Settings.walkLayer2 || !Settings.walkDirect)
            return false;
        int x = View.nodeX(lastStand), z = View.nodeZ(lastStand);
        ChunkFloors c = view.chunk(x, z);
        int idx = view.index(lastStand);
        if (c == null || idx < 0) return false;
        double h = c.h(idx);
        if (Math.hypot(x + 0.5 - p.getX(), z + 0.5 - p.getZ()) > RETRACE_REACH) return false;
        Planner.Plan dp = Planner.direct(view, start, x, z, h, costs(), margin(), canSprint(p));
        if (dp == null) return false;
        // the route's loose move the walk was on: made live, it left the walk off the table's floors, so it is blocked
        // with its family and the plan from the floor gone back to takes another way. Unblocked, that plan was the same
        // route and the same jump: RW's far walk from leg 0's start (cleg1, f48) hopped from the pool's sand into the
        // pocket between the fence at x -26 and the water five times, going back each time, then Stuck
        Route r = plan == null || plan.direct ? null : plan.route;
        if (r != null && seg > 0 && seg < r.n && r.move[seg] == Route.LOOSE) {
            long from = view.nodeAt((int) Math.floor(r.x[seg - 1]), r.y[seg - 1], (int) Math.floor(r.z[seg - 1]));
            long to = view.nodeAt((int) Math.floor(r.x[seg]), r.y[seg], (int) Math.floor(r.z[seg]));
            if (from != View.NONE && to != View.NONE && from != to) {
                for (long e : Loose.family(view, from, to)) blocked.add(e);
                movesBlocked++;
            }
        }
        // a search from here (its start, the fence's top, isn't where the player is): its plan would come in and
        // replace the way back (B0w_22: a loose plan from the pocket's fence top took over half way back and jumped in
        // again)
        if (job != null) job.cancelled = true;
        job = null;
        jobStale = false;
        hop = new int[]{x, z};
        hopY = h;
        hopNode = lastStand;
        hopFromY = p.getY();
        hopOnSince = -1;
        hopTaken = false;
        hops++;
        hopsBlind++;
        retraces++;
        Layer2.newTry();
        adoptDirect(dp);
        // a plan made now: without it the replan every 5 s while walking without a route came the next tick (the last
        // search's time was long past), found the same start and retraced again, all five in five ticks (B0w_26 0.1)
        lastPlanTick = tick;
        stuckReplans = 0;
        progressed = 0;
        message = String.format(java.util.Locale.ROOT, "Off the table's floors here: back to %d %d %d", x, (int) Math.floor(h), z);
        if (replanWhy.size() < 200) replanWhy.add(tick + ": off the table's floors: back to " + x + " " + z + " (retrace " + retraces + ")");
        return true;
    }

    /** No way on to a far goal (layer 1's every reachable place searched, the walk at the floor nearest it, the goal past
     *  the range of walking without a route): walk without a route to a floor past here instead (Hop.pick), Parkour on.
     *  True when one is taken. */
    private static boolean startHop(LocalPlayer p, int[] d) {
        if (!Settings.walkLayer2 || !Settings.walkDirect || !Settings.walkParkour || hopsOff || hopsBlind >= HOP_MAX
            || hopFails >= HOP_FAILS) return false;
        View view = WalkTable.snapshot();
        long start = startNode(view, p);
        if (start == View.NONE) return false;
        hopStood(standDistance(p, d), p.getY());
        // not back onto the floor the last hop left (it was stood on, so it is no nearer): on the course a pad and the
        // next jump's start beside it, a step apart, each counted nearer from the other (the start by the step up, the
        // pad by its height nearer the goal's Y), and the walk went pad, start, pad, ..., its jump searched from the
        // pad (diag_4x1, #95: three rounds, then given up; trapdoor_jump the same with the plan to the nearest floor)
        java.util.Set<Long> tried = hopsTried;
        if (hopLeft != View.NONE && hopLeft != start) { tried = new java.util.HashSet<>(hopsTried); tried.add(hopLeft); }
        long pick = hopPick(view, p.getX(), p.getY(), p.getZ(), d, hopBest, tried);
        if (pick == View.NONE) return false;
        if (hopTries.merge(pick, 1, Integer::sum) >= HOP_TRIES) hopsTried.add(pick);
        int x = View.nodeX(pick), z = View.nodeZ(pick);
        ChunkFloors c = view.chunk(x, z);
        double h = c.h(view.index(pick));
        Planner.Plan dp = Planner.direct(view, start, x, z, h, costs(), margin(), canSprint(p));
        if (dp == null) return false;
        hop = new int[]{x, z};
        hopY = h;
        hopNode = pick;
        hopFromY = p.getY();
        hopOnSince = -1;
        hopTaken = false;
        hopLeft = start;
        hops++;
        hopsBlind++;
        Layer2.newTry();   // a new try from here: layer 2's jump search may search again from the floor it stands on
        adoptDirect(dp);
        stuckReplans = 0;
        progressed = 0;
        message = String.format(java.util.Locale.ROOT, "No route on from here: walking without one to %d %d %d", x, (int) Math.floor(h), z);
        if (replanWhy.size() < 200) replanWhy.add(tick + ": without a route to " + x + " " + z + " (hop " + hops + ")");
        return true;
    }

    /**
     * The next hop's floor (Hop.pick): within the safe fall first; only if none, as far down as the jump search may land
     * (jumpSafeFall: Fall damage allowed toward any goal). The course's down_7p0625m5_record (#109) lands 5 down, past
     * the safe 3.9, so it was never offered (c109a: stopped in 31 ticks on its take-off); taken at once, the deeper
     * allowance put a floor 9 down beside slime_bounce's take-off (#22) before the bounce's own landing, and the walk
     * stood there through three hops (f113 G2a).
     */
    private static long hopPick(View view, double x, double y, double z, int[] d, Hop.Best best, java.util.Set<Long> tried) {
        Costs c = costs();
        long pick = Hop.pick(view, c, x, y, z, d[0], d[1], walkY, best, tried, c.safeFall, HOP_REACH, keepAbove(y));
        double deep = jumpSafeFall();
        if (pick == View.NONE && deep > c.safeFall + 1e-9)
            pick = Hop.pick(view, c, x, y, z, d[0], d[1], walkY, best, tried, deep, HOP_REACH, keepAbove(y));
        return pick;
    }

    /** The safety margin in blocks (the setting, in hundredths). */
    public static double margin() {
        return Settings.walkMargin / 100.0;
    }

    /** The floor the player is on: its column, else a neighbour its box overlaps, else, deep in water, the water's top
     *  above. Standing on the ground on the block of a neighbouring column (the box overhangs it), with no ground floor
     *  at the feet in its own column, that block's floor. */
    static long startNode(View v, LocalPlayer p) {
        int bx = Mth.floor(p.getX()), bz = Mth.floor(p.getZ());
        long n = v.nodeAt(bx, p.getY(), bz);
        if (p.onGround() && !p.onClimbable() && (n == View.NONE || !groundAt(v, n, p.getY()))) {
            // at the top of a vine wall, climbed from the vine's column, the player stands on the wall's top block with
            // the box over the vine's column: its own column's nearest floor is the vine's top (a climb floor a block
            // under the feet), so layer 1 planned a climb off onto where it stood, the hop there arrived at once, and it
            // planned the same again, 247 ticks a round (the course's vine_jump and twisting_vines_free, full3_c1)
            long sup = p.mainSupportingBlockPos.map(b -> v.nodeAt(b.getX(), p.getY(), b.getZ())).orElse(View.NONE);
            if (sup != View.NONE && groundAt(v, sup, p.getY())) return sup;
            // on the ground where neither the column nor the supporting block's has a floor at the feet, the column's
            // floor more than a step under them: a floor beside it within a step down, if any, is where the walk can
            // go. On the Parkour Spiral's first level a loose jump came down short on the grass at a fence's foot,
            // beside its end post (-26,59,-32), the box over the pool: the table has the fence's top as that column's
            // floor and the pool, 2 under the feet, as the player's; plans started in the pool, and layer 2 stood at
            // the edge until stuck (L1w_18's leg 1.2, RW 2026-10-16 00:23 "stuck by the fence next to the pond").
            // The sand beside it, a step down, is the way back to the jump
            if (n == View.NONE || floorUnder(v, n, p.getY() - STEP_START)) {
                long step = stepFloor(v, p, bx, bz);
                if (step != View.NONE) return step;
            }
            // no floor near the feet in the player's column, none a step down beside it: the supporting block's column
            // (its floor over the feet: a fence's top, its post beside the sand the player stands on) is the start, so
            // the walk goes back to where it last stood (retrace) and doesn't wait for a start that never comes ("Reading
            // the terrain" 200 ticks, then Stuck: RW's walk log 2026-10-16 04:04 at -1.7 56 -42.3, on the sand at a
            // fence's foot by the first gap, the column there empty from 52 to 58)
            if (n == View.NONE && sup != View.NONE) return sup;
        }
        if (n != View.NONE) return n;
        double best = Double.MAX_VALUE;
        long bestN = View.NONE;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                long m = v.nodeAt(bx + dx, p.getY(), bz + dz);
                if (m == View.NONE) continue;
                double d = Math.hypot(bx + dx + 0.5 - p.getX(), bz + dz + 0.5 - p.getZ());
                if (d < best) { best = d; bestN = m; }
            }
        // deep under water: the water's top above (a swim floor) is where the walk starts from
        if (bestN == View.NONE && p.isInWater())
            for (int dy = 1; dy <= 24 && bestN == View.NONE; dy++) {
                long m = v.nodeAt(bx, p.getY() + dy, bz);
                if (m != View.NONE) {
                    ChunkFloors c = v.chunk(bx, bz);
                    if (c != null && c.type(v.index(m)) == ChunkFloors.SWIM) bestN = m;
                }
            }
        return bestN;
    }

    // ---------------------------------------------------------------- chained hops (the jump search from a moving start)

    /**
     * The floor the walk would hop to next from a landing on block column (lx, lz) with the feet at (x, y, z), as
     * startHop would pick it standing there once across: {x, z, feet height, node, the hop floor's own node}, or null. Only while a hop is under
     * way without a route (Layer2 chains the next jump onto the one being played: RW 2026-10-14 00:57:04, the jump
     * search from a moving start, "yea hfiel as todo").
     */
    static double[] nextHopFrom(int lx, int lz, double x, double y, double z) {
        if (hop == null || plan == null || !plan.direct || hopTaken || hopsOff || hopsBlind + 1 >= HOP_MAX
            || !Settings.walkLayer2 || !Settings.walkDirect || !Settings.walkParkour) return null;
        // the jump being played lands on the hop's floor, block (lx, lz) (else it isn't across yet)
        if (lx != hop[0] || lz != hop[1] || Math.abs(y - hopY) >= 0.6) return null;
        int[] d = destination();
        if (d == null) return null;
        View view = WalkTable.snapshot();
        Hop.Best best = hopBest.copy();
        Costs costs = costs();
        double g = Math.hypot(d[0] - Math.floor(x), d[1] - Math.floor(z));
        best.stood(Hop.nearness(costs, g, y, walkY), g, Hop.tie(costs, walkY));
        long pick = hopPick(view, x, y, z, d, best, new java.util.HashSet<>());
        if (pick == View.NONE || pick == hopNode) return null;
        int px = View.nodeX(pick), pz = View.nodeZ(pick);
        ChunkFloors c = view.chunk(px, pz);
        if (c == null) return null;
        return new double[]{px, pz, c.h(view.index(pick)), pick, hopNode};
    }

    /**
     * A chained jump (Layer2: the next jump found from where the one being played lands, played on without stopping)
     * takes the walk over the hop's floor: what arriving on it does (the progress, the counts), then the next hop under
     * way without a route to `next` (nextHopFrom), its plan adopted at once, with no stop to plan (layer 1 is asked
     * again on the way, every 5 s, as while walking without a route). False if no plan to it can be made.
     */
    static boolean chainHop(double[] next) {
        if (hop == null || plan == null || !plan.direct) return false;
        int[] d = destination();
        if (d == null) return false;
        View view = WalkTable.snapshot();
        int nx = (int) next[0], nz = (int) next[1];
        double h = next[2];
        long node = (long) next[3];
        Planner.Plan dp = Planner.direct(view, hopNode, nx, nz, h, costs(), margin(),
            Minecraft.getInstance().player == null || Minecraft.getInstance().player.getFoodData().getFoodLevel() > 6);
        if (dp == null) return false;
        // across the hop's floor (as the arrival in endTick, the floor its own column)
        if (hopStood(Math.hypot(d[0] - hop[0], d[1] - hop[1]), hopY)) hopArrivals.clear();
        hopArrivals.merge(hopNode, 1, Integer::sum);
        hopFails = 0;
        hopsTried.clear();
        hopTries.clear();
        double fromY = hopY;
        hop = new int[]{nx, nz};
        hopY = h;
        hopNode = node;
        hopFromY = fromY;
        hopOnSince = -1;
        hopTaken = false;
        hops++;
        hopsBlind++;
        hopsChained++;
        adoptDirect(dp);
        stuckReplans = 0;
        progressed = 0;
        message = String.format(java.util.Locale.ROOT, "No route on from here: walking without one to %d %d %d (chained)", nx, (int) Math.floor(h), nz);
        if (replanWhy.size() < 200) replanWhy.add(tick + ": chained on to " + nx + " " + nz + " (hop " + hops + ")");
        return true;
    }

    /**
     * The next loose move of the route walked whose take-off is the floor at block column (lx, lz) with the feet at y
     * (within 0.6), once its check has passed: {x, z, feet height, its landing's node, the take-off's node}, or null. For
     * Layer2's chained jumps along a loose route (a bounce off slime goes straight on into the next jump; on any landing
     * the walk would otherwise stop, stand and search again).
     */
    static double[] nextLooseFrom(int lx, int lz, double y) {
        Planner.Plan pl = plan;
        if (pl == null || pl.direct || !parkour()) return null;
        PathSearch.Result r = pl.result;
        for (int i = 1; i < r.path.length; i++) {
            if (r.moves[i] != Graph.LOOSE) continue;
            long a = r.path[i - 1];
            if (View.nodeX(a) != lx || View.nodeZ(a) != lz || Math.abs(r.pathY[i - 1] - y) > 0.6) continue;
            if (pl.checkedTo < i) return null;
            return new double[]{View.nodeX(r.path[i]), View.nodeZ(r.path[i]), r.pathY[i], r.path[i], a};
        }
        return null;
    }

    /** Hops taken by a chained jump this walk (tests). */
    public static int hopsChained = 0;
    /** The floor the last hop started from (startHop): not hopped back onto. */
    private static long hopLeft = View.NONE;

    /** How far under the feet a start's floor may be before a floor within a step down beside it is taken instead. */
    static final double STEP_START = 1.1;

    /** Whether floor n's top is under height y. */
    private static boolean floorUnder(View v, long n, double y) {
        ChunkFloors c = v.chunk(View.nodeX(n), View.nodeZ(n));
        if (c == null) return false;
        int i = v.index(n);
        return i >= 0 && c.h(i) < y;
    }

    /** The ground floor nearest the player in the 3 by 3 columns round (bx, bz) whose top is at the feet or up to a
     *  step under them, or NONE. */
    private static long stepFloor(View v, LocalPlayer p, int bx, int bz) {
        double y = p.getY(), best = Double.MAX_VALUE;
        long bestN = View.NONE;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                long m = v.nodeAt(bx + dx, y, bz + dz);
                if (m == View.NONE) continue;
                ChunkFloors c = v.chunk(bx + dx, bz + dz);
                int i = v.index(m);
                if (c == null || i < 0 || c.type(i) != ChunkFloors.GROUND || c.h(i) > y + 0.1 || c.h(i) < y - 1.05) continue;
                double d = Math.hypot(bx + dx + 0.5 - p.getX(), bz + dz + 0.5 - p.getZ());
                if (d < best) { best = d; bestN = m; }
            }
        return bestN;
    }

    /** Whether floor n is over feet at y by more than a step (the table's floor in a fence's column, its top 1.5 up). */
    private static boolean floorOver(View v, long n, double y) {
        ChunkFloors c = v.chunk(View.nodeX(n), View.nodeZ(n));
        if (c == null) return false;
        int i = v.index(n);
        return i >= 0 && c.h(i) > y + 0.6;
    }

    /** Whether node n is a ground floor with its top at the feet (y, within 0.1). */
    private static boolean groundAt(View v, long n, double y) {
        ChunkFloors c = v.chunk(View.nodeX(n), View.nodeZ(n));
        if (c == null) return false;
        int i = v.index(n);
        return c.type(i) == ChunkFloors.GROUND && Math.abs(c.h(i) - y) < 0.1;
    }

    // ---------------------------------------------------------------- the tick

    /** The start of every client tick (MinecraftTickMixin, before the kill aura turns): your keys end the walk; then this
     *  tick's steering from where the player is. */
    public static void onTickStart(Minecraft mc) {
        long t0 = System.nanoTime();
        steering = false;
        layer2 = false;
        LocalPlayer p = mc.player;
        Layer2.traceTickStart(p);   // the copy trace's state (KINE_COPY_TRACE=1 only)
        if (p != null && active()) {
            if (Compat.screen(mc) == null && yourMovement(mc)) {
                stop("you took over");
                handBack = true;
            } else if (!Settings.walkPathfinder) {
                cancel();
            } else if (mc.level == null || mc.level.dimension() != dimension) {
                cancel();   // the route belongs to the dimension left
            } else if (!p.isAlive()) {
                stop("died");
            } else if (p.isFallFlying() && !(Settings.walkLayer2 && Settings.walkElytraHops) || p.isPassenger()
                       || p.getAbilities().flying) {
                // a glide is the walk's own with elytra hops on (layer 2 opened the glider)
                stop("off the ground (gliding, riding or flying)");
            } else if (state == State.WALKING && plan != null) {
                steer(p);
            }
        }
        measure(t0);
    }

    /** Whether you press a movement key yourself: W, A, S, D, jump as held; sneak and sprint as physical presses
     *  (with toggle sneak or toggle sprint the game's own state stays on after you let go). */
    static boolean yourMovement(Minecraft mc) {
        if (ai.rrw.kine.view.Freecam.active()) return false;   // in freecam your keys move the camera
        var o = mc.options;
        if (o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown() || o.keyJump.isDown()) return true;
        return physical(mc, o.keyShift, o.toggleCrouch().get()) || physical(mc, o.keySprint, o.toggleSprint().get());
    }

    private static boolean physical(Minecraft mc, KeyMapping km, boolean toggle) {
        if (!toggle) return km.isDown();
        return Compat.isKeyboard(km) ? Compat.held(mc, km) : km.isDown();
    }

    /** Where the rotation sent should rest when the kill aura isn't fighting (KillAura.beforeKeybinds): while walking,
     *  facing the way the follower walks (the view's pitch kept); else your view. */
    public static float[] restRotation(LocalPlayer p, float viewYaw, float viewPitch) {
        if (steering && state == State.WALKING && DoorOpener.active()) return DoorOpener.aim(p);   // opening a door: at it
        if (steering && state == State.WALKING) return new float[]{wantYaw, layer2 ? Layer2.pitch(p, viewPitch) : viewPitch};
        return new float[]{viewYaw, viewPitch};
    }

    /** The movement keys this tick (VanillaActions.adjustInput, after the kill aura's swap and combat movement). */
    public static Input adjustInput(LocalPlayer p, Input keys) {
        long t0 = System.nanoTime();
        try {
            if (handBack) { Afloat.disarm(); return handBackKeys(keys); }
            if (!active()) return Afloat.after(p, keys);   // after a walk that left you in water: the head kept above it
            // your keys as the game read them this tick (a press that came after the tick's start)
            if (keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump()) {
                stop("you took over");
                handBack = true;
                Afloat.disarm();
                return handBackKeys(keys);
            }
            // stand while the first plan is made (in water: up to the air, Afloat)
            if (state == State.PLANNING || !steering) return underGait(p, Afloat.during(p));
            if (layer2) return underGait(p, WorldSnapshot.input(Layer2.keys(p)));
            int k = wantKeys ? Moves.keys(wantYaw, p.getYRot(), wantSprint ? Moves.SPRINT : Moves.WALK) : 0;
            if (wantJump) k |= MoveState.JUMP;
            return underGait(p, WorldSnapshot.input(k));
        } finally {
            measure(t0);
        }
    }

    /** During the hand-back: your keys, already swapped by the kill aura for the rotation sent; sprint only with W
     *  among them (the game would stop a sprint without a forward impulse anyway; the input packet shows it so). */
    static Input handBackKeys(Input keys) {
        if (keys.sprint() && !keys.forward())
            return new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), false);
        return keys;
    }

    /** This tick's steering, for tests: the waypoint walked toward and its move, who steers, the heading and the
     *  follower's wants. */
    public static String steeringState() {
        Route r = plan == null ? null : plan.route;
        String mv = r != null && seg < r.n ? r.moveName(seg) : "-";
        return String.format(java.util.Locale.ROOT, "seg=%d/%s move=%s steer=%b l2=%b want=%.1f jump=%b sprint=%b keys=%b%s",
            seg, r == null ? "-" : Integer.toString(r.n), mv, steering, layer2, wantYaw, wantJump, wantSprint, wantKeys,
            layer2 ? "" : Layer2.lastWhy.isEmpty() ? "" : " l2why=" + Layer2.lastWhy);
    }

    /** Whether the hand-back is on: from your first key press until the rotation sent is back on your view. */
    public static boolean handingBack() {
        return handBack;
    }

    /** End of a client tick: the table, plans in and out, arrival, replans, the hand-back's end. */
    private static void endTick(Minecraft mc) {
        long t0 = System.nanoTime();
        tick++;
        WalkTable.tick(mc);
        GridRequests.serve(mc);
        LocalPlayer p = mc.player;
        if (handBack && (p == null || !ai.rrw.kine.combat.KillAura.turned())) handBack = false;
        if (previewJob != null) {
            Planner.Plan pp = previewJob.latest.get();
            if (pp != null && (pp.id != previewShownId || pp.pass != previewShownPass)) {
                previewShownId = pp.id;
                previewShownPass = pp.pass;
                WalkRender.newPlan(pp);
            }
        }
        if (p == null || !active()) { measure(t0); return; }
        int[] d = destination();
        if (d == null) { cancel(); measure(t0); return; }
        AlphaFloor.update(mc, p, tick);
        int g = gait();
        if (lastGait >= 0 && g != lastGait && state == State.WALKING) {
            replanFor(AlphaFloor.on() ? "alpha floor on: " + AlphaFloor.why() : "alpha floor off", mc);
        }
        lastGait = g;

        // a plan in: the first one starts the walk; later ones replace the route. The loosened layer 1's plans (its job
        // started by a strict job that found no way on) come first: while it searches, the strict job's floor nearest
        // the goal isn't walked to (on the Parkour Spiral it lay in the sea under the goal, which kills there)
        // a replan that made no search (the terrain not read yet) after a fall or a hop across left the last search in
        // job: its plan was seen already and the walk stood in PLANNING for good (the course's cobweb, 1200 ticks, cH23
        // walk 3), or its verdict, no way onto the goal from where the hop started, ended the walk in the air a tick
        // later (hh_3bc_1bm, walks 1 and 2); a fresh Go cleared it ("stuck, unstuck by toggling"). Planned again instead
        boolean staleJob = jobStale && state == State.PLANNING;
        Planner.Job lj = job == null || staleJob ? null : job.loose;
        Planner.Plan lp = lj == null ? null : lj.latest.get();
        if (lp != null && (lp.id != seenLooseId || lp.pass != seenLoosePass)) {
            seenLooseId = lp.id;
            seenLoosePass = lp.pass;
            noWaySeen = false;
            if (offNewRoute(p, lp.route) && tick - farRouteTick > FAR_ROUTE_WAIT && state == State.WALKING) {
                // the loose job searched from where the walk was when it began; the walk went on along the strict job's
                // partial route meanwhile, and its route doesn't come by here: placed on it by the nearest leg, a leg
                // three blocks up over a wall ahead, layer 2 made for it from below and stood pressed into a corner
                // under the overhang at (-31.7,59,-27.3) until stuck (the Parkour Spiral's leg 0 to checkpoint 2, RW's
                // walk log 2026-10-16 02:15; L1w_18 stood there 200 ticks). A search from here instead (the checks'
                // memo keeps the moves already checked)
                farRouteTick = tick;
                replanFor(FAR_ROUTE_WHY, mc);
            } else {
                adoptPlan(p, lp, lp.looseMoves == 0 ? "Walking" : "Walking, with " + lp.looseMoves + " nearly possible jumps checked in the movement copy");
                planKeep = jobKeep;
            }
        } else if (lj != null && (!lj.done || lp != null && (lj.failure == null || lp.checkedTo == Integer.MAX_VALUE))) {
            // the loose search runs with nothing yet, or its plan is being walked: the strict job's own isn't taken
            if (plan == null) message = "No way by the proven moves: searching nearly possible jumps";
        } else if (job != null && !staleJob) {
            Planner.Plan np = job.latest.get();
            // a routine refresh of a route still being walked takes only what its job ends with: the quick first
            // pass (the weighted search) can leave the loaded area by another way out, which flashed up as a stray
            // route at an angle for a moment every few seconds while the route dimmed (RW, 2026-10-13)
            boolean waitForBest = !jobReplay && plan != null && !job.done;
            boolean fresh = np != null && !waitForBest && (np.id != seenId || np.pass != seenPass);
            if (fresh) { seenId = np.id; seenPass = np.pass; }
            if (fresh) noWaySeen = np.result.nearest;
            if (fresh && np.result.nearest) {
                // no way to the goal (every reachable place searched; found at the start or on the way, the goal
                // shut in: a tree's trunk, a pocket the graph has no way into): with Parkour on, walk without a route
                // when it's near (layer 2 may jump what the graph has no move for); else to the floor nearest it and
                // end there (Parkour off, layer 2 crosses no gap the graph doesn't: without a route it would only stand
                // at the edge until it gave up, "Stuck")
                if (plan != null && plan.direct && !hopTaken) fresh = false;
                else if (Settings.walkParkour) {
                    Planner.Plan dp = directPlan(p, d, np.result.reason);
                    if (dp != null) { adoptDirect(dp); fresh = false; }
                }
                // on the way by hops, a way only to a floor no nearer the goal than the block stood on (by the hops'
                // own measure, standDistance): straight on to the next hop from here. Walked, it took the walk back
                // off the hop just made: on the course's trapdoor_jump (#94) the take-off a step up was hopped onto,
                // the nearest floor was the pad below it (one nearer the goal's height), the walk went back down, and
                // the next hop's jump search ran from the pad, where the jump isn't made (f94 G4, both walks)
                if (fresh && hops > 0 && np.result.path.length > 0 && p.onGround()) {
                    long end = np.result.path[np.result.path.length - 1];
                    double there = Math.hypot(d[0] - View.nodeX(end), d[1] - View.nodeZ(end));
                    if (there >= standDistance(p, d) - 1e-9 && startHop(p, d)) fresh = false;
                }
            }
            if (fresh) {
                hop = null;   // layer 1 has a plan again: the hop, if any, is over
                hopTaken = false;
                if (firstPlan == null || firstPlan.id == np.id) firstPlan = np;
                plan = np;
                planKeep = jobKeep;
                planId = np.id;
                planPass = np.pass;
                seg = startSegment(p, plan.route);
                // a new route, or a finer pass of the same one: its length left is not comparable with the old mark
                bestLeft = Double.MAX_VALUE;
                bestLeftTick = tick;
                eta.onPlan(np.time, np.route);
                state = State.WALKING;
                message = np.route.viaExit ? "Walking toward the edge of what's loaded"
                    : np.result.nearest ? "No way onto the destination: walking as near as it gets" : "Walking";
                WalkRender.newPlan(np, jobReplay);
            } else if (job.done && job.failure != null && (plan == null || job.latest.get() == null)) {
                if (job.exhausted) noWaySeen = true;
                if (plan == null) {
                    // shut in, and nowhere nearer it than here: with Parkour off, without a route would only stand
                    Planner.Plan dp = job.exhausted && !Settings.walkParkour ? null : directPlan(p, d, job.failure);
                    if (dp == null && job.exhausted && startHop(p, d)) { measure(t0); return; }
                    if (dp == null && job.exhausted) {
                        double gd = Math.hypot(d[0] + 0.5 - p.getX(), d[1] + 0.5 - p.getZ());
                        fail(String.format(java.util.Locale.ROOT, "No way onto the destination: stopped as near as it gets, %.1f blocks off", gd));
                        measure(t0);
                        return;
                    }
                    if (dp == null) { fail("No path: " + job.failure); measure(t0); return; }
                    adoptDirect(dp);
                } else if (job.exhausted && !plan.direct) {
                    // a replan found the goal shut in and nothing nearer it than here: the route walked so far (often
                    // one toward the loaded edge, from before the goal's ground loaded) leads nowhere
                    if (startHop(p, d)) { measure(t0); return; }
                    double gd = Math.hypot(d[0] + 0.5 - p.getX(), d[1] + 0.5 - p.getZ());
                    fail(String.format(java.util.Locale.ROOT, "No way onto the destination: stopped as near as it gets, %.1f blocks off", gd));
                    measure(t0);
                    return;
                }
            }
            if (job.done && state == State.PLANNING && job.latest.get() == null && job.failure == null) {
                // nothing from it (the start wasn't readable yet): try again
                replan(mc, FIRST_BUDGET);
            }
        } else if (state == State.PLANNING) {
            replan(mc, FIRST_BUDGET);
        }

        if (state != State.WALKING || plan == null) { measure(t0); return; }
        Route r = plan.route;

        // a hop: arrived on its floor, back to layer 1 for the real goal; fallen well below where it started, the hops end
        if (hop != null && plan.direct && !hopTaken) {
            // a drop layer 2 plays as a copy-found jump that still lands (checked each tick) is the way, not a fall: the
            // course's slime_bounce drops 6 onto slime and bounces up to a landing 3 under the start (RW 2026-10-14)
            // and a drop the copy says ends without hurting (layer 2's own way onto slime: the bounce) is not one either;
            // judged only going down (off the slime, going up, keys up in the copy would carry it past the landing)
            if (p.getY() < hopFromY - 4.0 && p.getDeltaMovement().y < 0.0 && !Layer2.playingRecipe()
                && !Layer2.fallEndsSafely(p, costs().safeFall)) {
                // fell: no more hops; plan from where it is (the walk ends at the floor nearest the goal there)
                hopsOff = true;
                hop = null;
                plan = null;
                state = State.PLANNING;
                message = "Fell: planning from here";
                replanFor("fell on the way, without a route", mc);
                measure(t0);
                return;
            } else if ((p.onGround() || holdingClimb(p)) && Math.abs(p.getY() - hopY) < 0.6
                       && Math.hypot(hop[0] + 0.5 - p.getX(), hop[1] + 0.5 - p.getZ()) <= 0.75
                       && (standing(p) && Layer2.staysPut(p) || holdingClimb(p) || tick - hopOnTick(tick) > SETTLE_TICKS)
                       // or a copy-found jump played to its end left the player holding a climbable (a climb template
                       // onto a ladder chain's far ladder, not the hop's floor): across, and layer 1 plans the climb on.
                       // Kept on the hop, layer 2 took the player back down off the fourth ladder to the hop's floor
                       // (the course's ladder_90_spiral, #101, c101b: its template played to 0.002 of the copy's)
                       || holdingClimb(p) && Layer2.recipeJustEnded()) {
                // on a ladder or a vine the walk never stands: it is across once on it in the hop's column (it waited
                // 30 ticks there and slid off: the course's ladder_diagonal, RW 2026-10-14; the plan up the climb then
                // takes over, layer 2 keeping hold till it comes, hopTaken below)
                // (standing: still, and the copy says it stays there keys up: on a thin post's far edge it was still
                // enough and slid off the next tick, RunModel.staysPut)
                // on the hop's floor, and stood still there (or long enough on it): the next jump starts from a
                // standing start, as a walk started there would (arriving with the run's speed, on a small floor the
                // walk slid off it before the next jump, and layer 2's copy-found jump searches only from standing)
                if (hopStood(standDistance(p, d), p.getY())) hopArrivals.clear();
                // back on a floor it hopped to before, with no real progress since, is a round, not a way on: it counts
                // as a failed hop (the course's mm_backwalled_1bm_4b went round for good: the pad beside the start is
                // nearer the far goal by 0.002, so layer 1's nearest floor, and the start is a step up from it)
                boolean again = hopArrivals.merge(hopNode, 1, Integer::sum) > 1;
                if (again) hopFails++;
                else hopFails = 0;
                hopOnSince = -1;
                // across: the floors tried from back there may be tried again from here (a landing tried from the pad
                // beside a jump's start, before the start was stood on); being nearer the goal than the walk has stood
                // keeps the hops going forward
                hopsTried.clear();
                hopTries.clear();
                // and not hopped to again until the walk gets nearer the goal (the next hop takes another floor)
                if (again) for (long n : hopArrivals.keySet()) if (hopArrivals.get(n) > 1) hopsTried.add(n);
                message = "Across: planning on";
                replanFor("across, without a route: planning on", mc);
                if (p.onClimbable()) {
                    // on a ladder or a vine layer 2 keeps hold (the plan without a route stays, toward where it hangs)
                    // until the next plan comes: let go for the tick between, it slid off a ladder's foot and fell (the
                    // course's ladder_4b, RW 2026-10-14). That plan replaces this one when it comes (hopTaken)
                    hopTaken = true;
                } else {
                    hop = null;
                    plan = null;
                    state = State.PLANNING;
                    measure(t0);
                    return;
                }
            }
        }

        // arrived: within half a block of the destination's centre
        double gx = d[0] + 0.5 - p.getX(), gz = d[1] + 0.5 - p.getZ();
        if (gx * gx + gz * gz <= 0.25 && (p.onGround() || p.isInWater() || holdingClimb(p))
            && (Double.isNaN(walkY) || Math.abs(p.getY() - walkY) < 0.6)) {
            state = State.ARRIVED;
            message = "Arrived";
            steering = false;
            Afloat.arm();
            measure(t0);
            return;
        }

        if (watchGap(p, r, mc)) { measure(t0); return; }

        // progress, stuck and off-route checks; replans
        double left = remaining(p, r);
        // a jump search running, or its jump playing: no stuck time while it does (Layer2.busy)
        boolean l2busy = Layer2.busy() || waitingForCheck() || DoorOpener.active();
        if (l2busy) bestLeftTick = tick;
        if (ARRIVE_LOG && plan.result.nearest && left < 0.75 && tick % 10 == 0)
            ai.rrw.kine.Kine.LOGGER.info(String.format(java.util.Locale.ROOT, "Kine arrive: tick %d left %.3f l2busy=%b (l2 %b check %b door %b) ground=%b climb=%b job=%s checked=%b standing=%b staysPut=%b at %.3f %.3f %.3f",
                tick, left, l2busy, Layer2.busy(), waitingForCheck(), DoorOpener.active(), p.onGround(), p.onClimbable(),
                job == null ? "none" : job.done ? "done" : "running", nearestCheckedId == plan.id, standing(p), Layer2.staysPut(p),
                p.getX(), p.getY(), p.getZ()));
        if (!l2busy && plan.result.nearest && left < 0.75 && (p.onGround() || p.isInWater() || p.onClimbable()) && (job == null || job.done)
            && nearestCheckedId != plan.id && (standing(p) && Layer2.staysPut(p) || p.isInWater() || p.onClimbable())) {
            // at the floor nearest a goal with no way onto it: one more search on the table as it is now (ground loaded
            // since may open a way); if it finds none nearer, the walk ends here (the exhausted replan, below)
            nearestCheckedId = plan.id;
            if (Layer2.terrain() != lastPlanTerrain) replanFor(AT_NEAREST, mc);
            else {
                // nothing loaded or changed since the plan that found no way: its search again finds the same (RW
                // 2026-10-15); straight on to the hops, or the end, as the exhausted replan would
                if (startHop(p, d)) { measure(t0); return; }
                double gd = Math.hypot(d[0] + 0.5 - p.getX(), d[1] + 0.5 - p.getZ());
                fail(String.format(java.util.Locale.ROOT, "No way onto the destination: stopped as near as it gets, %.1f blocks off", gd));
                measure(t0);
                return;
            }
        }
        if (left < bestLeft - 0.25 || Compat.screen(mc) != null) {   // a screen pauses it
            if (bestLeft != Double.MAX_VALUE && left < bestLeft) progressed += bestLeft - left;
            if (progressed >= REAL_PROGRESS) stuckReplans = 0;
            bestLeft = left;
            bestLeftTick = tick;
        }
        boolean idle = job == null || job.done;
        // layer 2 stood before a jump of its route (a gap, a loose move, a jump up) and the jump search found no way over
        // it: that move is blocked for the walk and the route planned again without it (the strict search, then the
        // loosened one); with Parkour on. Else, on a route to the floor nearest the goal: on to the hops now, not after 4
        // no-progress replans (245 ticks standing, 12 times in walk 2 of final3d_pk39)
        if (Layer2.routeJumpNone() && !plan.direct && hop == null) {
            if (parkour() && blockStalledJump(p)) {
                replanFor("no way over the jump ahead: planning round it", mc);
                measure(t0);
                return;
            }
            if (noWaySeen) {
                stuckReplans = Math.max(stuckReplans, 3);
                bestLeftTick = Math.min(bestLeftTick, tick - 61);
            }
        }
        // a hop whose jump searches (from here, the spots, the templates) found no way, and the player has stood still
        // HOP_GIVE_UP ticks: layer 2's own free search toward the landing has had its time (it spreads its grid of jumps
        // over its replans and keeps the best: a search cut short by its budget gets more each tick, so it is let run
        // a while; with 10 ticks the course's neo_winged, neo_headhitter and neo_nix, crossed by layer 2's own moves
        // before, were given up at once), so the hop is given up now, not after HOP_STUCK no-progress replans of 61 ticks
        // (RW 2026-10-15: a deterministic search retried from the same state finds the same nothing)
        if (hop != null && plan.direct && !hopTaken && Layer2.hopJumpNone() && standing(p) && Layer2.stillTicks() >= HOP_GIVE_UP) {
            stuckReplans = Math.max(stuckReplans, HOP_STUCK);
            bestLeftTick = Math.min(bestLeftTick, tick - 61);
        }
        // and not while the loose search runs: from where the walk stands, its plan is the replan's answer (a replan from
        // here with the strict search finding no way handed on to it, then "a replan would search the same", Stuck 30
        // ticks later, the loose plan still coming: RW's walk log 2026-10-16 02:15, the corner at (-31.7,59,-27.3))
        boolean looseRuns = job != null && job.loose != null && !job.loose.done;
        if (tick - bestLeftTick > 60 && idle && !looseRuns) {
            // standing still where the last plan was made, on the same terrain, its search not cut short: a replan would
            // search the same again (deterministic) and layer 2 has had its time; on to what comes after the replans
            // hanging still on a ladder counts as standing (the Kine Parkour course's #37 ladder_to_ladder, f48 G2c: the
            // walk hung at the first ladder's top, the floor nearest a goal it had no way on to, through three replans of
            // 3 s before the hop to the second ladder: 13 s)
            if (hop == null && (standing(p) || p.onClimbable()) && Layer2.stillTicks() >= 60 && sameAsLastPlan(p))
                stuckReplans = Math.max(stuckReplans, 3);
            if (++stuckReplans > (hop != null ? HOP_STUCK : 3)) {
                if (hop != null) {
                    hop = null;
                    hopFails++;
                    if (startHop(p, d)) { measure(t0); return; }
                } else if (noWaySeen && startHop(p, d)) {
                    // layer 1's way toward the floor nearest the goal stalled short of its end (a gap move layer 2
                    // didn't make from here): the next floor without a route, as at the way's end. And walking without
                    // a route to a goal in reach whose last jumps the graph doesn't join (the jump search aims only at
                    // floors joined to the goal, within 8): the course's neo_chained (#120), its goal 16 off and its
                    // landing 10.5 from the start, stood until this ended it, no hop to its take-off 3 ahead (c120a)
                    measure(t0);
                    return;
                }
                if (noWaySeen) {
                    double gd = Math.hypot(d[0] + 0.5 - p.getX(), d[1] + 0.5 - p.getZ());
                    fail(String.format(java.util.Locale.ROOT, "No way onto the destination: stopped as near as it gets, %.1f blocks off", gd));
                } else fail("Stuck");
                measure(t0);
                return;
            }
            String why = String.format(java.util.Locale.ROOT, "no progress in 3 s (%d in a row, left %.1f, seg %d/%d)",
                stuckReplans, left, seg, r.n);
            bestLeft = Double.MAX_VALUE;
            bestLeftTick = tick;
            progressed = 0;
            replanFor(why, mc);
        } else if (idle && !plan.direct && !plan.result.found && plan.id != underKeptPlan && underKept(p, r)) {
            // the route (to the floor nearest the goal, or a search cut short) goes on under the height kept, down a drop
            // off the level into the sea: planned while the goal was past the loaded table (keepAbove NaN then), and
            // layer 2 keeps the height now: it stood at the drop for good, the follower taking no drops (the Parkour
            // Spiral, checkpoint 2 to 4 with the strict search out of time, R2s_33: the drop from 68 to the sea at 51; RW's
            // walk log 2026-10-16 03:14 and 03:00, "it runs almost to the edge of the water"). Planned again, with it
            underKeptPlan = plan.id;
            replanFor(UNDER_KEPT, mc);
        } else if (idle && !plan.direct && offRoute(p, r) > (Settings.walkLayer2 && Layer2.field() != null ? Field.RADIUS - 1.0 : 2.5)) {
            replanFor("off the route", mc);
        } else if (idle && (r.viaExit || plan.result.partial) && left < 24 && tick - lastPlanTick > 20) {
            replanFor(r.viaExit ? NEAR_EDGE : NEAR_END, mc);
        } else if (idle && (r.viaExit || plan.result.partial) && tick - lastPlanTick > 100) {
            replanFor(EVERY_5S, mc);
        } else if (idle && plan.throughUnknown && tick - lastPlanTick > 100) {
            // the goal reached round ground not loaded then (chunks kept from an earlier visit with a gap between):
            // as the gap loads, a straighter way may open (measured 2026-10-13: a route bent 4.9 blocks off the line
            // and walked to the end)
            replanFor(PAST_UNKNOWN, mc);
        } else if (idle && plan.direct && tick - lastPlanTick > 100) {
            // no route: try layer 1 again every 5 s (the walk may have got past what it had no way over)
            replanFor(WITHOUT_ROUTE, mc);
        } else if (idle && tick - lastCheckTick > 20) {
            lastCheckTick = tick;
            if (changedAlong(r)) replanFor("blocks changed along the route", mc);
        }
        if (state == State.WALKING) {
            Recipe.safeFall = jumpSafeFall();
            Recipe.harmTicks = harmAllowance();
            keepHeight(p);
            DoorOpener.update(mc, p, plan);
            long noDoor = DoorOpener.takeGivenUp();
            if (noDoor != 0) {
                blocked.add(noDoor);
                replanFor("a door that didn't open: planning round it", mc);
            }
        }
        if (state == State.WALKING) {
            if (hop != null && plan.direct) Layer2.endTick(mc, plan, hop, hopY, true);
            else if (plan.result.nearest && !plan.direct && plan.result.path.length > 0) {
                // a route only to the floor nearest a goal it has no way onto: layer 2 makes for that floor and comes
                // to rest on it, as on a hop's floor (the walk goes on from there once standing on it, above). Made
                // for the far goal, the runs had nothing to gain by standing on the floor: on the course's
                // up_1p25_max_height (#54), a 1x1 block a step up, they stepped on and off it for 100 to 225 ticks
                // (dev77 and dev88 alike, c52a/c52b) and once until the walk gave up (f88 G4 walk 2)
                long end = plan.result.path[plan.result.path.length - 1];
                Layer2.endTick(mc, plan, new int[]{View.nodeX(end), View.nodeZ(end)}, plan.result.pathY[plan.result.path.length - 1], true);
            }
            else Layer2.endTick(mc, plan, d, walkY);
            tickEta(mc, p);
        }
        measure(t0);
    }

    /** The ETA's tick: where the player is along the route, the estimate, and the figure shown. */
    private static void tickEta(Minecraft mc, LocalPlayer p) {
        boolean walking = steering && !mc.isPaused();   // it walks on under a screen, unless the game pauses
        eta.blocksPerPoint = Settings.walkLayer2 ? 60.0 : 40.0;   // layer 2 sprint-jumps (the air costs no food)
        net.minecraft.world.food.FoodData food = p.getFoodData();
        boolean canSprint = canSprint(p);   // as the plans are made (replan): the gait and food
        eta.tick(p.getX(), p.getZ(), walking, canSprint);
        boolean peaceful = mc.level != null && mc.level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL;
        etaRaw = eta.rawSeconds(food.getFoodLevel(), food.getSaturationLevel(), canSprint, peaceful);
        etaShown = eta.shown(etaRaw, walking, System.nanoTime());
        etaDist = eta.distanceLeft(p.getX(), p.getZ());
    }

    /** The plan already planned again for going under the height kept (at most once a plan). */
    private static long underKeptPlan = -1;
    /** The height kept (keepAbove) the current job was planned with, and the plan walked: NaN, none (the goal past the
     *  loaded table then, or no goal above). */
    private static double jobKeep = Double.NaN, planKeep = Double.NaN;
    static final String UNDER_KEPT = "the route goes under the height kept: planning again with it";

    /** Whether the route, planned with no height kept, goes on from the waypoint walked toward under the height the walk
     *  keeps now (keepAbove). Only such a route: one planned with a height kept goes under the height the walk keeps
     *  now only by what the walk has climbed since, and planning again for that, 2 s into a walk up a small rise, left
     *  a small strict search exhausted under it and the walk went to its nearest floor, into a pocket by the lava
     *  course, and gave up there (RW's playtest of a64c041, 2026-10-16 03:51); layer 2 keeps the height as it walks. */
    private static boolean underKept(LocalPlayer p, Route r) {
        if (!Double.isNaN(planKeep)) return false;
        double ka = keepAbove(p.getY());
        if (Double.isNaN(ka)) return false;
        for (int k = Math.max(1, seg); k < r.n; k++) if (r.y[k] < ka - 1e-4) return true;
        return false;
    }

    /** Replans that only refresh the route toward a far goal (no replay): the reasons below. */
    static final String EVERY_5S = "every 5 s toward a far goal", NEAR_EDGE = "near the loaded edge", NEAR_END = "near a partial route's end",
        PAST_UNKNOWN = "every 5 s while the line to the goal crosses ground not loaded",
        WITHOUT_ROUTE = "every 5 s while walking without a route",
        AT_NEAREST = "at the floor nearest a goal with no way onto it: once more";

    private static void replanFor(String why, Minecraft mc) {
        // a search still running with nothing yet (replan would refuse it): not counted, and the routine replans' clock
        // starts again, so they ask in their time and not every tick (R2s_33: 1705 refused "every 5 s toward a far
        // goal" replans in a walk of 3000 ticks while the loose search ran)
        if (jobBusy()) { lastPlanTick = tick; return; }
        replayNext = !(why.equals(EVERY_5S) || why.equals(NEAR_EDGE) || why.equals(NEAR_END) || why.equals(PAST_UNKNOWN)
            || why.equals(WITHOUT_ROUTE) || why.equals(AT_NEAREST));
        replans++;
        if (replanWhy.size() < 200) replanWhy.add(tick + ": " + why);
        replan(mc, REPLAN_BUDGET);
    }

    private static void measure(long t0) {
        long d = System.nanoTime() - t0;
        clientNanos += d;
        clientMaxNanos = Math.max(clientMaxNanos, d);
    }

    /** Per tick, from the client test: one tick's worth of measured time is the sum of the three calls. */
    public static void countTick() {
        clientTicks++;
    }

    // ---------------------------------------------------------------- the follower

    static final double PASS = 0.35;      // a waypoint counts as reached within this of it, horizontally
    static final double LOOK = 1.6;       // pure pursuit: aim this far along the route ahead

    /** This tick's steering: which waypoint, which way, which keys. */
    private static void steer(LocalPlayer p) {
        Route r = plan.route;
        if (DoorOpener.active()) {
            // a closed door on the route within reach: stand, the rotation sent turns to it, the click opens it
            // (DoorOpener.beforeKeybinds); the route on goes through the open door
            wantKeys = false;
            wantJump = false;
            wantSprint = false;
            layer2 = false;
            steering = true;
            return;
        }
        if (plan.direct) {
            // no route: layer 2 alone steers (the follower would walk the straight line, maybe into a gap); a tick it
            // hands back, the player stands
            if (Settings.walkLayer2 && Layer2.steer(p)) { wantYaw = Layer2.yaw(); layer2 = true; steering = true; }
            else steering = false;
            return;
        }
        boolean l2 = Settings.walkLayer2 && Layer2.steer(p);
        if (seg <= 1 && toStart(p, r)) {
            // the route starts on a floor beside the feet and a step or more under them (startNode's step down: the
            // player on ground the table has no floor for) and goes on by a jump: first a plain walk down onto the
            // start, then the jump from a standing start there. Layer 2 counted itself on the jump already, pressed
            // into the edge sneaking (moving, so never still for its jump search) until stuck: the Parkour Spiral's
            // leg 1, at a fence's foot by the pool (RW 2026-10-16 00:23)
            double ax = r.x[0] - p.getX(), az = r.z[0] - p.getZ();
            wantYaw = (float) Math.toDegrees(Math.atan2(-ax, az));
            wantKeys = Math.hypot(ax, az) > 0.15;
            wantJump = false;
            wantSprint = false;
            layer2 = false;
            steering = true;
            seg = 1;
            return;
        }
        if (l2 && !(Layer2.standingLong() && seg < r.n && r.move[seg] == Route.WALK)) {
            // layer 2 moves along the route its own way: keep the place on the route for the progress check and for
            // the follower should layer 2 hand it a tick. Layer 2's runs standing still a while on a plain walk of the
            // route (no search, recipe or check waited for): the follower walks it (the Parkour Spiral's leg 1, a slab
            // beside a top slab half a block up: layer 2 stood there until stuck)
            int near = nearestSegment(p.getX(), p.getY(), p.getZ(), r, seg, (seg < r.n ? legLength(r, seg) : 0) + 6.0);
            if (near > seg) seg = near;
            wantYaw = Layer2.yaw();
            layer2 = true;
            steering = true;
            return;
        }
        if (seg >= r.n) { steering = false; return; }
        advance(p, r);
        if (seg >= r.n) { steering = false; return; }
        int m = r.move[seg];
        double px = p.getX(), py = p.getY(), pz = p.getZ();
        double tx = r.x[seg], ty = r.y[seg], tz = r.z[seg];
        double dist = Math.hypot(tx - px, tz - pz);
        boolean canSprint = canSprint(p);
        wantJump = false;
        wantKeys = true;
        wantSprint = canSprint;
        double aimX = tx, aimZ = tz;
        switch (m) {
            case Route.WALK -> {
                double[] a = pursuit(p, r);
                aimX = a[0]; aimZ = a[1];
                // slow down for a drop or a jump coming up: walk the last two blocks
                if (seg + 1 < r.n && r.move[seg + 1] != Route.WALK && dist < 2.0) wantSprint = false;
            }
            case Route.WADE -> wantSprint = false;
            case Route.JUMP -> {
                if (p.onGround() && py < ty - 0.3 && (dist < 1.3 || p.horizontalCollision)) wantJump = true;
            }
            case Route.DROP -> {
                wantSprint = false;
                double v = Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z);
                // brake on the ground near the edge, then step off slowly; in the air keep making for the landing
                if (p.onGround() && py > ty + 0.3 && dist < 1.2 && v > 0.11) wantKeys = false;
                if (dist < 0.2) wantKeys = false;
            }
            case Route.WATER_IN -> {
                wantSprint = false;
                if (p.isInWater()) wantJump = true;
            }
            case Route.SWIM, Route.WATER_OUT -> {
                wantSprint = false;
                wantJump = true;
            }
            case Route.CLIMB_ON -> wantSprint = false;
            case Route.CLIMB_UP -> {
                wantSprint = false;
                wantJump = true;
                wantKeys = dist > 0.25;
            }
            case Route.CLIMB_DOWN -> {
                wantSprint = false;
                wantKeys = dist > 0.3;
            }
            case Route.CLIMB_OFF -> {
                wantSprint = false;
                if (ty > py + 0.4) wantJump = true;
            }
            case Route.GAP, Route.LOOSE -> {
                // the follower has no way to make a jump (parkour is layer 2's): on the ground it stands. It walked on
                // toward the landing instead, off the take-off: RW's far walk cleg1 (f52), layer 2's runs all unsafe at
                // a loose take-off over the lava field, the follower handed the tick walked into the lava (dead)
                if (p.onGround()) { wantKeys = false; wantSprint = false; }
            }
            default -> {}
        }
        double ax = aimX - px, az = aimZ - pz;
        if (ax * ax + az * az > 1e-6) wantYaw = (float) Math.toDegrees(Math.atan2(-ax, az));
        else wantKeys = false;
        steering = true;
    }

    /** How far (horizontally) the walk may stand from a loose job's new route and take it; further, or under the leg it
     *  would be placed on by more than a step, it plans from where it is (at most once in FAR_ROUTE_WAIT ticks). */
    static final double FAR_ROUTE = 3.0;
    static final int FAR_ROUTE_WAIT = 100;
    static final String FAR_ROUTE_WHY = "the loose route came from where the walk was: planning from here";
    private static int farRouteTick = -1000;

    /** Whether the player is off route r for taking it now: the leg nearestSegment places it on more than FAR_ROUTE
     *  away, or that leg's lower end more than a step over the feet (the corner under the overhang: the leg placed on
     *  ran three blocks up, over the wall, and nearestSegment's measure counted it 2.5 off). */
    static boolean offNewRoute(LocalPlayer p, Route r) {
        return offNewRoute(p.getX(), p.getY(), p.getZ(), r);
    }

    static boolean offNewRoute(double px, double py, double pz, Route r) {
        if (r.n < 2) return Math.hypot(r.x[0] - px, r.z[0] - pz) > FAR_ROUTE;
        int k = startSegment(px, py, pz, r);
        return pointToLeg(px, pz, r, k) > FAR_ROUTE || py < Math.min(r.y[k - 1], r.y[k]) - 0.6;
    }

    /** Whether the walk is to step down onto the route's start first (steer): standing beside it, more than a step's
     *  part over it, and the route's first move a jump of some kind (a walk or a drop the follower and layer 2 make
     *  from where it stands). */
    private static boolean toStart(LocalPlayer p, Route r) {
        if (r.n < 2 || !p.onGround() || p.isInWater() || p.onClimbable()) return false;
        int m = r.move[1];
        if (m == Route.WALK || m == Route.DROP || m == Route.WADE) return false;
        // (not once on the jump's own landing: a route of two points, a floor and the jump a step up onto the block
        // beside it, keeps its place at 1 until arrival, and on the landing the player was a step over the start and
        // within reach, so it was walked back down onto the start and jumped up again, over and over: the course's
        // up_1p25_max_height, #54, 70 to 225 ticks of it, c52a-d)
        if (Math.abs(p.getY() - r.y[1]) < 0.3 && Math.hypot(r.x[1] - p.getX(), r.z[1] - p.getZ()) < 0.8) return false;
        if (!(p.getY() > r.y[0] + 0.6 && p.getY() < r.y[0] + 2.6 && Math.hypot(r.x[0] - p.getX(), r.z[0] - p.getZ()) < 1.5)) return false;
        // and only off the table's floors (its case: the player on ground the table has no floor for). Standing on a
        // floor of the table, the route's own landing at its very edge 0.80 from the middle (the test above wants 0.8),
        // the walk was taken back toward the route's start over the gap between: the course's tower_staircase, #64
        // (r107_g2a walk 1): pressed at the sneak edge 35 ticks (fall prevention's sneak) instead of layer 2's jump,
        // then slid off and fell 12
        return !onTableFloor(p);
    }

    /** A line every 10 ticks near the floor nearest an unreachable goal: the arrival check's parts (KINE_ARRIVE_LOG=1,
     *  a measuring tool, off by default). */
    static final boolean ARRIVE_LOG = "1".equals(System.getenv("KINE_ARRIVE_LOG"));

    /** Whether the block the player stands on (the game's supporting block) has a floor of the table's at the feet
     *  (within 0.1): at an edge the feet's own column may be the gap beside it. */
    private static boolean onTableFloor(LocalPlayer p) {
        if (p.mainSupportingBlockPos.isEmpty()) return false;
        net.minecraft.core.BlockPos b = p.mainSupportingBlockPos.get();
        View v = WalkTable.snapshot();
        long n = v.nodeAt(b.getX(), p.getY(), b.getZ());
        return n != View.NONE && groundAt(v, n, p.getY());
    }

    /** Move on past waypoints already reached. */
    private static void advance(LocalPlayer p, Route r) {
        while (seg < r.n) {
            int m = r.move[seg];
            double tx = r.x[seg], ty = r.y[seg], tz = r.z[seg];
            double dist = Math.hypot(tx - p.getX(), tz - p.getZ());
            double py = p.getY();
            boolean reached;
            switch (m) {
                case Route.JUMP, Route.CLIMB_OFF, Route.WATER_OUT -> reached = dist < 0.6 && py > ty - 0.25 && p.onGround();
                case Route.DROP -> reached = dist < 0.6 && py < ty + 0.3 && p.onGround();
                case Route.CLIMB_UP -> reached = py > ty - 0.2;
                case Route.CLIMB_DOWN -> reached = py < ty + 0.2;
                case Route.WATER_IN, Route.SWIM -> reached = dist < 0.6 && p.isInWater();
                case Route.CLIMB_ON -> reached = dist < 0.4;
                default -> {
                    // a straight leg: reached when close, or when past its end along the leg
                    double sx = r.x[seg - 1], sz = r.z[seg - 1];
                    double lx = tx - sx, lz = tz - sz, len2 = lx * lx + lz * lz;
                    double t = len2 < 1e-9 ? 1 : ((p.getX() - sx) * lx + (p.getZ() - sz) * lz) / len2;
                    boolean nextSpecial = seg + 1 < r.n && r.move[seg + 1] != Route.WALK;
                    boolean bend = seg + 1 < r.n && turn(r, seg) > 20;
                    reached = dist < (nextSpecial || bend ? PASS : 0.8) || t >= 1 && !nextSpecial && seg + 1 < r.n;
                    if (seg == r.n - 1) reached = false;   // the last point: arrival decides
                }
            }
            if (!reached) return;
            seg++;
        }
    }

    /** The route's turn at waypoint k, degrees (0 straight on). */
    private static double turn(Route r, int k) {
        double ax = r.x[k] - r.x[k - 1], az = r.z[k] - r.z[k - 1], bx = r.x[k + 1] - r.x[k], bz = r.z[k + 1] - r.z[k];
        double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
        if (la < 1e-9 || lb < 1e-9) return 0;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ax * bx + az * bz) / (la * lb)))));
    }

    /** The point LOOK ahead along the route from the player's projection on the current leg, not past a special move. */
    private static double[] pursuit(LocalPlayer p, Route r) {
        double px = p.getX(), pz = p.getZ();
        int k = seg;
        double sx = r.x[k - 1], sz = r.z[k - 1], ex = r.x[k], ez = r.z[k];
        double lx = ex - sx, lz = ez - sz, len = Math.hypot(lx, lz);
        double t = len < 1e-9 ? 1 : Math.max(0, Math.min(1, ((px - sx) * lx + (pz - sz) * lz) / (len * len)));
        double along = LOOK;
        double cx = sx + lx * t, cz = sz + lz * t;
        double rest = len * (1 - t);
        while (along > rest) {
            along -= rest;
            if (k + 1 >= r.n || r.move[k + 1] != Route.WALK) return new double[]{ex, ez};
            // round a real bend at the bend itself: aiming past it would cut the corner inside the margin
            double ax = ex - cx, az = ez - cz, bx = r.x[k + 1] - ex, bz = r.z[k + 1] - ez;
            double cos = (ax * bx + az * bz) / Math.max(1e-9, Math.hypot(ax, az) * Math.hypot(bx, bz));
            if (cos < Math.cos(Math.toRadians(20))) return new double[]{ex, ez};
            k++;
            cx = ex; cz = ez;
            ex = r.x[k]; ez = r.z[k];
            rest = Math.hypot(ex - cx, ez - cz);
        }
        double f = rest < 1e-9 ? 0 : along / rest;
        return new double[]{cx + (ex - cx) * f, cz + (ez - cz) * f};
    }

    /** The leg the player is nearest (for a new route): the waypoint after it. */
    /**
     * The height a walk with no route to its goal keeps to, or NaN: while the destination's Y is above it, KEEP_DROP under
     * the highest the player has stood. The floor nearest the goal (layer 1's fallback), the hops and layer 2's runs
     * without a route don't go under it: those are commitments nothing corrects, chosen by the A* estimate, which prices
     * a climb as cheap as a ladder's (it must not overcharge). On the Parkour Spiral the sea under a goal ten blocks up
     * scored three times nearer than the start, and the walk dropped into the sea instead of doing the course (RW's
     * playtest, 2026-10-15: "it jumped into the water rather than doing the course"). Pricing climbs dearer instead would
     * still leave it a close call there (the start 58.7 blocks and 10 up from the goal, the sea 18 under it).
     */
    /** How far (blocks, straight) a goal may be to be near: the kept height and the loose search are only toward a near
     *  goal. It was the loaded table's reach, which moves with the render distance: at RW's 32 the Kine Parkour course's
     *  target, 489 blocks on, came inside it at #68 mid-course, the kept height switched on there (the pad a block under
     *  #68's landing left out of the hops), and the walk stopped at #69 (RW 2026-10-16 00:18, 03:28); at 5 it never did. */
    static final double NEAR_GOAL = 160.0;

    /** Whether the destination is near (NEAR_GOAL) and in the loaded table. */
    static boolean nearGoal(int[] d) {
        if (d == null || !WalkTable.known(d[0], d[1])) return false;
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null || Math.hypot(d[0] + 0.5 - p.getX(), d[1] + 0.5 - p.getZ()) <= NEAR_GOAL;
    }

    static double keepAbove(double y) {
        if (Double.isNaN(walkY) || Double.isNaN(peakY)) return Double.NaN;
        // only toward a goal in the loaded area: toward one past it (the Kine Parkour course's target) the hops' and the
        // nearest floor's way on goes down drops as often as up, and the course's own machinery is as it was
        int[] d = destination();
        if (d == null || !nearGoal(d)) return Double.NaN;
        double lim = peakY - KEEP_DROP;
        // fallen under it (off a jump, into a pool): a block under where it is, so there is a way back up to look for
        if (y < lim) lim = y - KEEP_DROP;
        return walkY > lim + 1e-3 ? lim : Double.NaN;
    }

    /** Each walking tick: the highest stood (a route all the way to the goal moves it to where the player is: such a
     *  route may go down, and is trusted to), and layer 2's runs told the height to keep when there is no such route. */
    private static void keepHeight(LocalPlayer p) {
        boolean routed = plan != null && !plan.direct && plan.result.found;
        if (p.onGround() && !p.isInWater()) {
            if (routed || Double.isNaN(peakY)) peakY = p.getY();
            else peakY = Math.max(peakY, p.getY());
        }
        RunModel.keepAboveNow = routed ? Double.NaN : keepAbove(p.getY());
    }

    private static int nearestSegment(LocalPlayer p, Route r) {
        return nearestSegment(p.getX(), p.getY(), p.getZ(), r);
    }

    /** The leg of a new route the walk is on: nearestSegment over the legs no further along the route than the walk is
     *  from its start, and 4 more (a route made from a start the walk has since left, or one that comes back by, put
     *  the walk on a later leg passing near: three blocks up over a wall into a corner, or past its start toward the
     *  sea's edge: R0_32 leg 0.2 walked off it and the map took the player back). */
    static int startSegment(double px, double py, double pz, Route r) {
        return nearestSegment(px, py, pz, r, 1, Math.hypot(px - r.x[0], pz - r.z[0]) + Math.abs(py - r.y[0]) + 4.0);
    }

    private static int startSegment(LocalPlayer p, Route r) {
        return startSegment(p.getX(), p.getY(), p.getZ(), r);
    }

    /** The legs from leg `from` on whose start is within `reach` along the route of leg from's start (nearestSegment's
     *  measure): the walk's place as it goes, layer 2 steering (a leg further on than that is a later pass by here). */
    static int nearestSegment(double px, double py, double pz, Route r, int from, double reach) {
        int best = Math.max(1, Math.min(from, r.n - 1));
        double bestD = Double.MAX_VALUE, along = 0;
        for (int k = best; k < r.n; k++) {
            if (k > best) along += Math.hypot(r.x[k - 1] - r.x[k - 2], r.z[k - 1] - r.z[k - 2]) + Math.abs(r.y[k - 1] - r.y[k - 2]);
            if (along > reach) break;
            double h = pointToLeg(px, pz, r, k);
            double lo = Math.min(r.y[k - 1], r.y[k]) - 1.0, hi = Math.max(r.y[k - 1], r.y[k]) + 1.5;
            double dv = py < lo ? lo - py : py > hi ? py - hi : 0.0;
            double d = Math.hypot(h, 2.0 * dv);
            if (d < bestD - 1e-6) { bestD = d; best = k; }
        }
        return best;
    }

    /** The route's leg nearest a point: by the horizontal distance to it and twice how far the point is over or under
     *  the leg's heights (a block under its lower end, a block and a half over its higher). Horizontally alone, a route
     *  that comes back over its own start (the Parkour Spiral's leg 8: up nine blocks four blocks off) put the walk at
     *  its 36th leg of 39 while it stood at the first, and it stood still there. */
    static int nearestSegment(double px, double py, double pz, Route r) {
        int best = 1;
        double bestD = Double.MAX_VALUE;
        for (int k = 1; k < r.n; k++) {
            double h = pointToLeg(px, pz, r, k);
            double lo = Math.min(r.y[k - 1], r.y[k]) - 1.0, hi = Math.max(r.y[k - 1], r.y[k]) + 1.5;
            double dv = py < lo ? lo - py : py > hi ? py - hi : 0.0;
            double d = Math.hypot(h, 2.0 * dv);
            if (d < bestD - 1e-6) { bestD = d; best = k; }
        }
        return best;
    }

    private static double legLength(Route r, int k) {
        return Math.hypot(r.x[k] - r.x[k - 1], r.z[k] - r.z[k - 1]) + Math.abs(r.y[k] - r.y[k - 1]);
    }

    private static double pointToLeg(double px, double pz, Route r, int k) {
        double sx = r.x[k - 1], sz = r.z[k - 1], lx = r.x[k] - sx, lz = r.z[k] - sz;
        double len2 = lx * lx + lz * lz;
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - sx) * lx + (pz - sz) * lz) / len2));
        return Math.hypot(px - (sx + lx * t), pz - (sz + lz * t));
    }

    /** How far the player is from the current leg (horizontally). */
    private static double offRoute(LocalPlayer p, Route r) {
        if (seg >= r.n) return 0;
        int m = r.move[seg];
        if (m != Route.WALK && m != Route.WADE) return 0;   // the special moves check themselves
        return pointToLeg(p.getX(), p.getZ(), r, seg);
    }

    /** The route's length left from the player: to the current waypoint, then the rest. */
    private static double remaining(LocalPlayer p, Route r) {
        if (seg >= r.n) return Math.hypot(r.x[r.n - 1] - p.getX(), r.z[r.n - 1] - p.getZ());
        double d = Math.hypot(r.x[seg] - p.getX(), r.z[seg] - p.getZ()) + Math.abs(r.y[seg] - p.getY());
        for (int k = seg + 1; k < r.n; k++) d += Math.hypot(r.x[k] - r.x[k - 1], r.z[k] - r.z[k - 1]) + Math.abs(r.y[k] - r.y[k - 1]);
        return d;
    }

    /** Whether a column the route crosses (or one beside it) changed since it was planned. A column whose chunk has
     *  since unloaded (behind the player, at a small render distance) or was unknown at planning is not a change. */
    private static boolean changedAlong(Route r) {
        long[] stamps = plan.stamps;
        if (stamps == null) return false;
        for (int i = 0; i + 2 < stamps.length; i += 3) {
            long now = WalkTable.columnStamp((int) stamps[i], (int) stamps[i + 1]);
            if (now != -1 && stamps[i + 2] != -1 && now != stamps[i + 2]) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- the indicator

    /** WALK in cyan on the right, under AP, in the row combat movement's MOVE uses (the two never show together),
     *  while it walks you; "WALK ..." while it plans. */
    private static void render(Gui2D g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!active() || mc.player == null || Compat.hudHidden(mc)) return;
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        String s = state == State.PLANNING ? "WALK ..." : "WALK";
        g.text(mc.font, s, HudLayout.apLeftEdge(w / 2, ai.rrw.kine.hud.NavHud.lastHalfCenter()),
            HudLayout.annunciatorLower(h, mc.font.lineHeight), ai.rrw.kine.hud.HudColors.CYAN, true);
    }
}
