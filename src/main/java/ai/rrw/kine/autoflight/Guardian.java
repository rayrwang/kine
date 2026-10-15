package ai.rrw.kine.autoflight;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.Gui2D;
import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.guard.GlideSim;
import ai.rrw.kine.guard.Guard;
import ai.rrw.kine.guard.Maneuver;
import ai.rrw.kine.guard.Pilot;
import ai.rrw.kine.guard.Track;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudLayout;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.WorldSnapshot;
import ai.rrw.kine.util.GameModes;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The elytra guardian (collision avoidance, RW 2026-10-14, DECISIONS.md): always on while gliding in survival or
 * adventure, by hand or on the autopilot, a takeoff or a landing. Each tick it hands the {@link Guard} the state, the
 * look, whoever flies if it does nothing (the held look; the autopilot's laws, {@link ApModel}; the takeoff's,
 * {@link TakeoffPilot}), the pilot's recent track and a copy of the blocks around ({@link GuardWorld}), on a worker
 * thread at the end of the tick; at the start of the next it takes the judgment (waiting for it at most its time
 * budget: in real play the worker has had the time between ticks, in a lockstep test this keeps runs the same).
 *
 * <p>When the do-nothing path takes damage and it is the last moment a rescue still works with the margin, it fires:
 * the takeoff stops, a landing is let go, and the autopilot engages, past its arming gate, to fly the rescue (each
 * tick's look from the rescue's program on the real state, turned as the autopilot turns, in whole mouse counts, at
 * most 28 degrees a tick); a red TERRAIN shows in the warning row. Once the rescue has done its move and a hands-off
 * glide stays clear well past the warning, the autopilot trips off with its AUTOPILOT OFF warning and the 1.5 s
 * dead-man disconnect, unless the pilot takes over. A small mouse movement is taken back, as the landing takes back
 * a bump (Autopilot.control); a deliberate turn, a movement key or J hands control straight back, and the guardian
 * then stays off for that danger until the do-nothing path is clear again (RW's ruling 1). In flight by hand it fires
 * only once the pilot has given no input (a mouse turn, a movement key, use) for the cooldown setting, default 1 s,
 * so terrain can be flown close on purpose (RW 2026-10-14: "otherwise its basicaly impossible to fly near tererian",
 * "i meant cooldown after yoru last input"); on the autopilot, a takeoff or a landing it always acts ("in autopilot
 * it's alwyas active").
 */
public final class Guardian {
    private Guardian() {}

    /** The judgment's settings: the do-nothing horizon, the freeze margin, the hand-back glide, the budgets. */
    public static final Guard.Config CFG = new Guard.Config();
    /** How long the start of a tick waits for the worker beyond its budget. */
    private static final long WAIT_SLACK_NANOS = 10_000_000L;
    /** A rescue that hasn't handed back after this long lets go anyway (30 s). */
    private static final int MAX_RESCUE_TICKS = 600;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kine-guardian");
        t.setDaemon(true);
        return t;
    });

    private record Job(Future<Guard.Verdict> verdict, int tick, Maneuver steered, Guard guard) {}

    private static Job job = null;
    private static int tick = 0;
    private static Maneuver active = null;          // the rescue under way
    private static boolean overridden = false;      // the pilot took over a rescue: off until the path is clear
    private static Track frozenTrack = Track.NONE;  // the pilot's track when it fired
    private static final Track.Ring RING = new Track.Ring();
    private static String waitSeed = null;          // the move that last passed the can-it-wait check
    private static float sentYaw, sentPitch;        // the rotation the server got for the last tick
    private static float cmdYaw, cmdPitch;          // this tick's rescue look
    private static int startTick;
    private static double startY, startTrack, lowY;
    private static boolean noHelpLogged = false;
    private static int lastInputTick = Integer.MIN_VALUE / 2;  // the pilot's last mouse turn or key, flying by hand
    private static float prevYaw = Float.NaN, prevPitch = Float.NaN;
    private static boolean quietLogged = false;

    // measured, for the report and the tests (read by reflection)
    public static int fires = 0, handbacks = 0, overrides = 0, switches = 0, rearms = 0, noHelps = 0, quietSkips = 0;
    public static long ticks = 0, tickNanos = 0, maxTickNanos = 0, jobs = 0, jobNanos = 0, maxJobNanos = 0;
    public static long waits = 0, waitNanos = 0, maxWaitNanos = 0;
    /** The game thread's time a tick (end of tick and start of the next together), as a histogram: under 0.1, 0.25,
     *  0.5, 1, 2 and 5 ms, and over; and the worst of each half. */
    public static final long[] HIST = new long[7];
    public static long maxEndNanos = 0, maxStartNanos = 0;
    private static long tickSpent = 0;
    private static final double[] EDGES_MS = {0.1, 0.25, 0.5, 1.0, 2.0, 5.0};

    /** The histogram as text: "under 0.1 ms n, ..." */
    public static String histogram() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < HIST.length; i++)
            b.append(i == 0 ? "" : ", ").append(i < EDGES_MS.length ? "under " + EDGES_MS[i] : "over 5.0").append(" ms ").append(HIST[i]);
        return b.toString();
    }
    public static volatile String lastEvent = "";

    public static boolean rescuing() { return active != null; }
    /** This tick's rescue look (for the flight director bars). */
    public static float commandedPitch() { return cmdPitch; }
    public static float commandedYaw() { return cmdYaw; }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Guardian::endTick);
        ClientChunkEvents.CHUNK_UNLOAD.register((lvl, chunk) -> GuardWorld.chunkUnloaded(lvl, chunk.getPos().getMinBlockX() >> 4, chunk.getPos().getMinBlockZ() >> 4));
        Compat.hud("guardian", Guardian::render);
    }

    static boolean enabled(Minecraft mc) {
        return Settings.collisionAvoidance && Settings.autopilot && GameModes.survivalLike(mc);
    }

    private static boolean glider(LocalPlayer p) {
        return p.isFallFlying() || Compat.isGlider(p.getItemBySlot(EquipmentSlot.CHEST)) || Takeoff.active();
    }

    // ---- the end of a tick: the block copies, the track, and the next judgment to the worker ----

    private static void endTick(Minecraft mc) {
        if (mc.isPaused()) return;
        long t0 = System.nanoTime();
        tick++;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || p.isDeadOrDying() || !enabled(mc) || !glider(p)) {
            stop("off");
            GuardWorld.clear();
            RING.clear();
            return;
        }
        GuardWorld.tick(mc, p);
        sentYaw = p.getYRot(); sentPitch = p.getXRot();
        // flying by hand, a turn of the view (only the mouse turns it then) or a key held is the pilot's input
        boolean turned = !Float.isNaN(prevYaw) && (Math.abs(sentYaw - prevYaw) > 0.01f || Math.abs(sentPitch - prevPitch) > 0.01f);
        if (byHand() && (turned || pilotKeys(mc) || mc.options.keyUse.isDown())) lastInputTick = tick;
        prevYaw = sentYaw; prevPitch = sentPitch;
        if (!p.isFallFlying()) { stop("the glide ended"); RING.clear(); overridden = false; note(t0); return; }
        if (active != null && !Autopilot.isEngaged()) stop("the autopilot let go");
        if (active == null) RING.add(p.getX(), p.getY(), p.getZ());
        else lowY = Math.min(lowY, p.getY());
        if (job == null) submit(mc, p);
        note(t0);
    }

    // the end of a tick: counted, and the tick's two halves added up into the histogram
    private static void note(long t0) {
        long took = System.nanoTime() - t0;
        ticks++; tickNanos += took; maxTickNanos = Math.max(maxTickNanos, took);
        maxEndNanos = Math.max(maxEndNanos, took);
        long all = tickSpent + took;
        tickSpent = 0;
        int i = 0;
        while (i < EDGES_MS.length && all / 1e6 >= EDGES_MS[i]) i++;
        HIST[i]++;
    }

    private static void noteStart(long t0) {
        long took = System.nanoTime() - t0;
        tickSpent += took;
        maxStartNanos = Math.max(maxStartNanos, took);
    }

    private static void submit(Minecraft mc, LocalPlayer p) {
        MoveState s = WorldSnapshot.state(p);
        Body body = WorldSnapshot.body(p);
        float yaw = p.getYRot(), pitch = p.getXRot();
        int minY = Compat.minY(mc.level);
        GuardWorld.Grid grid = GuardWorld.snapshot(p, minY);
        double safeFall = p.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
        Track track = active != null ? frozenTrack : RING.snapshot();
        Guard g = new Guard(new GlideSim(grid, body, minY, safeFall), CFG, track);
        long seed = tick;
        if (active != null) {
            Maneuver cur = active.copy();
            job = new Job(WORKER.submit(() -> g.steer(s, yaw, pitch, cur, seed)), tick, cur, g);
        } else {
            Pilot dn = doNothing(mc, p);
            g.lastWaitSeed = waitSeed;
            job = new Job(WORKER.submit(() -> g.watch(s, yaw, pitch, dn, seed)), tick, null, g);
        }
    }

    /** Whoever flies the coming ticks if the guardian does nothing. */
    static Pilot doNothing(Minecraft mc, LocalPlayer p) {
        if (Takeoff.active()) {
            TakeoffPilot t = Takeoff.pilot(mc, p);
            if (t != null) return t;
        }
        if (Autopilot.isEngaged()) {
            ApModel m = ApModel.of(mc, p);
            if (m != null) return m;
        }
        return Pilot.HOLD;
    }

    // ---- the start of a tick: the judgment, and this tick's look while rescuing ----

    /** At the start of each client tick, before the autopilot turns the view (MinecraftTickMixin). */
    public static void onTickStart(Minecraft mc) {
        if (mc.isPaused()) return;
        LocalPlayer p = mc.player;
        if (p == null) return;
        long t0 = System.nanoTime();
        if (job != null) {
            if (!job.verdict().isDone()) {
                long w0 = System.nanoTime();
                try { job.verdict().get(CFG.fireNanos + WAIT_SLACK_NANOS, TimeUnit.NANOSECONDS); } catch (Exception ignored) { }
                long waited = System.nanoTime() - w0;
                waits++; waitNanos += waited; maxWaitNanos = Math.max(maxWaitNanos, waited);
                t0 += waited;   // the wait is counted on its own, not as the guardian's work
            }
            if (job.verdict().isDone()) {
                Job j = job;
                job = null;
                try { act(mc, p, j, j.verdict().get()); }
                catch (Exception e) { Kine.LOGGER.warn("Kine guardian: judgment failed", e); }
            }
        }
        if (active != null && pilotKeys(mc)) override("a movement key");
        if (active != null) {
            if (tick - startTick > MAX_RESCUE_TICKS) handBack(p, "time");
            else {
                float[] out = new float[2];
                active.look(WorldSnapshot.state(p), sentYaw, sentPitch, out);
                cmdYaw = out[0]; cmdPitch = out[1];
                Autopilot.setRescueCommand(cmdYaw, cmdPitch);
            }
        }
        noteStart(t0);
    }

    /** The rescue's look for a frame before the tick starts (a copy, so the program doesn't advance). */
    static float[] preview(LocalPlayer p) {
        float[] out = new float[]{ sentYaw, sentPitch };
        if (active != null) active.copy().look(WorldSnapshot.state(p), sentYaw, sentPitch, out);
        return out;
    }

    private static void act(Minecraft mc, LocalPlayer p, Job j, Guard.Verdict v) {
        jobs++; jobNanos += v.nanos(); maxJobNanos = Math.max(maxJobNanos, v.nanos());
        if (j.steered() == null) waitSeed = j.guard().lastWaitSeed;
        switch (v.kind()) {
            case SAFE -> {
                noHelpLogged = false;
                quietLogged = false;
                if (overridden) { overridden = false; rearms++; event("re-armed: the path ahead is clear"); }
            }
            case NO_HELP -> {
                if (!noHelpLogged) { noHelps++; event("no rescue helps: " + v.danger() + "; best " + v.rescue()); }
                noHelpLogged = true;
            }
            case FIRE -> {
                if (active == null && !overridden && j.steered() == null) {
                    if (byHand() && tick - lastInputTick < Settings.collisionCooldown * 2) {
                        if (!quietLogged) { quietSkips++; event("held: your input " + (tick - lastInputTick) + " ticks ago; " + v.danger()); }
                        quietLogged = true;
                    } else { quietLogged = false; fire(mc, p, j, v); }
                }
            }
            case STEER -> {
                if (active != null && j.steered() != null && v.rescue().plan != j.steered()) {
                    active = v.rescue().plan.copy();
                    switches++;
                    Kine.LOGGER.info("Kine guardian: rescue changed to {}", v.rescue());
                }
            }
            case HANDBACK -> { if (active != null && j.steered() != null) handBack(p, "clear"); }
            default -> { }
        }
    }

    private static void fire(Minecraft mc, LocalPlayer p, Job j, Guard.Verdict v) {
        String flying = Takeoff.active() ? "takeoff" : Nav.landing() ? "landing" : Autopilot.isEngaged() ? "autopilot" : "hand";
        if (Takeoff.active()) Takeoff.yieldToGuardian();
        if (Nav.landing()) Nav.abandonLanding();
        frozenTrack = RING.snapshot();
        active = v.rescue().plan.copy();
        startTick = tick; startY = p.getY(); lowY = p.getY();
        startTrack = Pilot.trackYaw(p.getX() - p.xOld, p.getZ() - p.zOld);
        Autopilot.engageForRescue(p);
        fires++;
        event(String.format(Locale.ROOT, "fired at %.1f %.1f %.1f (%s flight, judgment %d tick%s late): %s if nothing is done; rescue %s",
            p.getX(), p.getY(), p.getZ(), flying, tick - j.tick(), tick - j.tick() == 1 ? "" : "s", v.danger(), v.rescue()));
    }

    private static void handBack(LocalPlayer p, String why) {
        double turned = Pilot.wrap(Pilot.trackYaw(p.getX() - p.xOld, p.getZ() - p.zOld) - startTrack);
        active = null;
        handbacks++;
        Autopilot.disengage();   // AUTOPILOT OFF and the dead-man, unless the pilot takes over
        event(String.format(Locale.ROOT, "handed back (%s) after %d ticks: height lost %.1f (lowest %.1f), turned %.0f",
            why, tick - startTick, startY - p.getY(), startY - lowY, turned));
    }

    /** The pilot took over a rescue (a deliberate mouse turn, a movement key, J): let go at once, without the warning,
     *  and stay off for this danger until the path ahead is clear. */
    public static void override(String why) {
        if (active == null) return;
        active = null;
        overridden = true;
        overrides++;
        Autopilot.releaseQuietly();
        event("overridden by " + why + " after " + (tick - startTick) + " ticks");
    }

    /** Flying by hand: no autopilot (a landing included) and no takeoff. Only then does the cooldown hold it back. */
    private static boolean byHand() {
        return !Autopilot.isEngaged() && !Takeoff.active();
    }

    private static void stop(String why) {
        if (active != null) {
            active = null;
            if (Autopilot.isEngaged()) Autopilot.releaseQuietly();
            event("rescue ended: " + why);
        }
        if (job != null && !job.verdict().isDone()) job.verdict().cancel(false);
        job = null;
    }

    private static void event(String s) {
        lastEvent = s;
        Kine.LOGGER.info("Kine guardian: {}", s);
    }

    private static boolean pilotKeys(Minecraft mc) {
        var o = mc.options;
        return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
            || o.keyJump.isDown() || o.keyShift.isDown();
    }

    /** For tests and diagnostics: the damage the movement copy predicts if the look is held from now, as the guardian's
     *  rollout finds it ("none", or the amount, its kinds and the tick), run here and now on the game's thread over
     *  the current block copies. */
    public static String probe(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "none";
        GuardWorld.tick(mc, p, Long.MAX_VALUE);
        int minY = Compat.minY(mc.level);
        Guard g = new Guard(new GlideSim(GuardWorld.snapshot(p, minY), WorldSnapshot.body(p), minY,
            p.getAttributeValue(Attributes.SAFE_FALL_DISTANCE)), CFG, Track.NONE);
        Guard.Outcome o = g.fly(WorldSnapshot.state(p), p.getYRot(), p.getXRot(), Pilot.HOLD, CFG.horizon);
        return o.hurt() ? String.format(Locale.ROOT, "%.2f %s %d", o.damage, o.toString().replaceAll(".*\\((.*)\\).*", "$1"), o.damageTick)
                        : "none";
    }

    // ---- the warning row ----

    private static void render(Gui2D g, DeltaTracker delta) {
        if (active == null) return;
        Minecraft mc = Minecraft.getInstance();
        int W = mc.getWindow().getGuiScaledWidth(), H = mc.getWindow().getGuiScaledHeight();
        String s = "TERRAIN";
        g.text(mc.font, s, W / 2 - mc.font.width(s) / 2, HudLayout.warningRow(H, mc.font.lineHeight), HudColors.RED, true);
    }
}
