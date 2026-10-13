package ai.rrw.kine.walk;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.fight.Moves;
import ai.rrw.kine.hud.HudLayout;
import ai.rrw.kine.mixin.KeyMappingAccessor;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.WorldSnapshot;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
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
    private static ResourceKey<Level> dimension = null;

    private static Planner.Job job = null;          // the plan in flight, or the last one
    private static Planner.Plan plan = null;        // the route being walked
    private static long planId = -1;
    private static int planPass = -1;
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
        return Settings.walkLayer2 ? Costs.LAYER2 : Costs.BARITONE;
    }
    public static final long FIRST_BUDGET = 400_000_000L, REPLAN_BUDGET = 200_000_000L;

    // measured, for tests and the report
    public static long clientNanos = 0, clientMaxNanos = 0, clientTicks = 0;
    public static int plans = 0;
    public static String lastWhy = "";

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Walk::endTick);
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "walk"), Walk::render);
    }

    // ---------------------------------------------------------------- the destination and the controls

    public static State state() { return state; }
    public static String message() { return message; }
    public static Planner.Plan plan() { return plan; }
    /** The walk's first plan (its last pass), for the report: the whole route from the start. */
    public static Planner.Plan firstPlan() { return firstPlan; }
    private static Planner.Plan firstPlan = null;
    public static int replans() { return replans; }

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

    /** Whether this tick the walk is on its own step into water: into deep water, or a drop onto a floor in water,
     *  with water still at the landing. The planner chose that step knowing it lands in water, so fall prevention lets
     *  it through (RW, 2026-10-12); on the rest of the walk fall prevention guards as usual. */
    public static boolean steppingIntoWater(LocalPlayer p) {
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
        if (p.level().getFluidState(pos).is(FluidTags.WATER)) return true;
        pos.setY(Mth.floor(ty - 1e-4));
        return p.level().getFluidState(pos).is(FluidTags.WATER);
    }

    /** This dimension's walk destination {x, z}, or null. */
    public static int[] destination() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : DEST.get(mc.level.dimension());
    }

    public static void setDestination(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        int[] old = DEST.put(mc.level.dimension(), new int[]{x, z});
        if (old == null || old[0] != x || old[1] != z) {
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
        dismissed = false;
        endPreview();
        if (job != null) job.cancelled = true;   // an earlier walk's search: its plan is not for this walk
        job = null;
        state = State.PLANNING;
        message = "Planning";
        plan = null;
        planId = -1;
        seg = 1;
        handBack = false;
        stuckReplans = 0;
        progressed = 0;
        replans = 0;
        replanWhy.clear();
        settleWaits = 0;
        firstPlan = null;
        bestLeft = Double.MAX_VALUE;
        bestLeftTick = tick;
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
        previewJob = Planner.plan(v, start, gx, gz, costs(), margin(), true, Settings.walkLayer2, FIRST_BUDGET);
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
        }
        steering = false;
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
        lastWhy = why;
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setOverlayMessage(Component.literal("Walk: " + why), false);
    }

    private static void replan(Minecraft mc, long budget) {
        LocalPlayer p = mc.player;
        int[] d = destination();
        if (p == null || d == null) return;
        if (job != null && !job.done) return;   // one at a time
        // plan on an up-to-date table around the player: a block just changed there may not be in it yet
        if (!WalkTable.settled(p.getBlockX(), p.getBlockZ(), 1) && settleWaits++ < 40) { message = "Reading the terrain"; return; }
        View view = WalkTable.snapshot();
        long start = startNode(view, p);
        if (start == View.NONE) {
            if (settleWaits++ < 40) { message = "Reading the terrain"; return; }
            if (plan == null) fail("can't tell where you stand");
            return;
        }
        settleWaits = 0;
        boolean canSprint = p.getFoodData().getFoodLevel() > 6;
        boolean glides = Settings.walkLayer2 && Settings.walkElytraHops && WorldSnapshot.body(p).canGlide;
        job = Planner.plan(view, start, d[0], d[1], costs(), margin(), canSprint, Settings.walkLayer2, glides, budget);
        jobReplay = replayNext;
        replayNext = true;
        lastPlanTick = tick;
        plans++;
    }

    /** The safety margin in blocks (the setting, in hundredths). */
    public static double margin() {
        return Settings.walkMargin / 100.0;
    }

    /** The floor the player is on: its column, else a neighbour its box overlaps, else, deep in water, the water's top
     *  above. */
    static long startNode(View v, LocalPlayer p) {
        int bx = Mth.floor(p.getX()), bz = Mth.floor(p.getZ());
        long n = v.nodeAt(bx, p.getY(), bz);
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

    // ---------------------------------------------------------------- the tick

    /** The start of every client tick (MinecraftTickMixin, before the kill aura turns): your keys end the walk; then this
     *  tick's steering from where the player is. */
    public static void onTickStart(Minecraft mc) {
        long t0 = System.nanoTime();
        steering = false;
        layer2 = false;
        LocalPlayer p = mc.player;
        if (p != null && active()) {
            if (mc.screen == null && yourMovement(mc)) {
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
        InputConstants.Key key = ((KeyMappingAccessor) km).kine$getKey();
        if (key.getType() == InputConstants.Type.KEYSYM) return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
        return km.isDown();
    }

    /** Where the rotation sent should rest when the kill aura isn't fighting (KillAura.beforeKeybinds): while walking,
     *  facing the way the follower walks (the view's pitch kept); else your view. */
    public static float[] restRotation(LocalPlayer p, float viewYaw, float viewPitch) {
        if (steering && state == State.WALKING) return new float[]{wantYaw, layer2 ? Layer2.pitch(p, viewPitch) : viewPitch};
        return new float[]{viewYaw, viewPitch};
    }

    /** The movement keys this tick (VanillaActions.adjustInput, after the kill aura's swap and combat movement). */
    public static Input adjustInput(LocalPlayer p, Input keys) {
        long t0 = System.nanoTime();
        try {
            if (handBack) return handBackKeys(keys);
            if (!active()) return keys;
            // your keys as the game read them this tick (a press that came after the tick's start)
            if (keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump()) {
                stop("you took over");
                handBack = true;
                return handBackKeys(keys);
            }
            if (state == State.PLANNING || !steering) return Input.EMPTY;   // stand while the first plan is made
            if (layer2) return WorldSnapshot.input(Layer2.keys(p));
            int k = wantKeys ? Moves.keys(wantYaw, p.getYRot(), wantSprint ? Moves.SPRINT : Moves.WALK) : 0;
            if (wantJump) k |= MoveState.JUMP;
            return WorldSnapshot.input(k);
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

        // a plan in: the first one starts the walk; later ones replace the route
        if (job != null) {
            Planner.Plan np = job.latest.get();
            if (np != null && (np.id != planId || np.pass != planPass)) {
                if (firstPlan == null || firstPlan.id == np.id) firstPlan = np;
                plan = np;
                planId = np.id;
                planPass = np.pass;
                seg = nearestSegment(p, plan.route);
                // a new route, or a finer pass of the same one: its length left is not comparable with the old mark
                bestLeft = Double.MAX_VALUE;
                bestLeftTick = tick;
                state = State.WALKING;
                message = np.route.viaExit ? "Walking toward the edge of what's loaded" : "Walking";
                WalkRender.newPlan(np, jobReplay);
            } else if (job.done && job.failure != null && (plan == null || job.latest.get() == null)) {
                if (plan == null) { fail("No path: " + job.failure); measure(t0); return; }
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

        // arrived: within half a block of the destination's centre
        double gx = d[0] + 0.5 - p.getX(), gz = d[1] + 0.5 - p.getZ();
        if (gx * gx + gz * gz <= 0.25 && (p.onGround() || p.isInWater() || p.onClimbable())) {
            state = State.ARRIVED;
            message = "Arrived";
            steering = false;
            measure(t0);
            return;
        }

        // progress, stuck and off-route checks; replans
        double left = remaining(p, r);
        if (left < bestLeft - 0.25 || mc.screen != null) {   // a screen pauses it
            if (bestLeft != Double.MAX_VALUE && left < bestLeft) progressed += bestLeft - left;
            if (progressed >= REAL_PROGRESS) stuckReplans = 0;
            bestLeft = left;
            bestLeftTick = tick;
        }
        boolean idle = job == null || job.done;
        if (tick - bestLeftTick > 60 && idle) {
            if (++stuckReplans > 3) { fail("Stuck"); measure(t0); return; }
            String why = String.format(java.util.Locale.ROOT, "no progress in 3 s (%d in a row, left %.1f, seg %d/%d)",
                stuckReplans, left, seg, r.n);
            bestLeft = Double.MAX_VALUE;
            bestLeftTick = tick;
            progressed = 0;
            replanFor(why, mc);
        } else if (idle && offRoute(p, r) > (Settings.walkLayer2 && Layer2.field() != null ? Field.RADIUS - 1.0 : 2.5)) {
            replanFor("off the route", mc);
        } else if (idle && (r.viaExit || plan.result.partial) && left < 24 && tick - lastPlanTick > 20) {
            replanFor(r.viaExit ? NEAR_EDGE : NEAR_END, mc);
        } else if (idle && (r.viaExit || plan.result.partial) && tick - lastPlanTick > 100) {
            replanFor(EVERY_5S, mc);
        } else if (idle && tick - lastCheckTick > 20) {
            lastCheckTick = tick;
            if (changedAlong(r)) replanFor("blocks changed along the route", mc);
        }
        if (state == State.WALKING) Layer2.endTick(mc, plan, d);
        measure(t0);
    }

    /** Replans that only refresh the route toward a far goal (no replay): the reasons below. */
    static final String EVERY_5S = "every 5 s toward a far goal", NEAR_EDGE = "near the loaded edge", NEAR_END = "near a partial route's end";

    private static void replanFor(String why, Minecraft mc) {
        replayNext = !(why.equals(EVERY_5S) || why.equals(NEAR_EDGE) || why.equals(NEAR_END));
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
        if (Settings.walkLayer2 && Layer2.steer(p)) {
            // layer 2 moves along the route its own way: keep the place on the route for the progress check and for
            // the follower should layer 2 hand it a tick
            int near = nearestSegment(p, r);
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
        boolean canSprint = p.getFoodData().getFoodLevel() > 6;
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
            default -> {}
        }
        double ax = aimX - px, az = aimZ - pz;
        if (ax * ax + az * az > 1e-6) wantYaw = (float) Math.toDegrees(Math.atan2(-ax, az));
        else wantKeys = false;
        steering = true;
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
    private static int nearestSegment(LocalPlayer p, Route r) {
        int best = 1;
        double bestD = Double.MAX_VALUE;
        for (int k = 1; k < r.n; k++) {
            double d = pointToLeg(p.getX(), p.getZ(), r, k);
            if (d < bestD - 1e-6) { bestD = d; best = k; }
        }
        return best;
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
    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!active() || mc.player == null || mc.options.hideGui) return;
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        String s = state == State.PLANNING ? "WALK ..." : "WALK";
        g.text(mc.font, s, HudLayout.apLeftEdge(w / 2, 0), HudLayout.annunciatorLower(h, mc.font.lineHeight), 0xFF3CE6FF, true);
    }
}
