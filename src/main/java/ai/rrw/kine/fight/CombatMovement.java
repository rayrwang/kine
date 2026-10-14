package ai.rrw.kine.fight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.hud.HudColors;
import ai.rrw.kine.hud.HudLayout;
import ai.rrw.kine.mixin.KeyMappingAccessor;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.WorldSnapshot;
import ai.rrw.kine.search.EffortTuner;
import ai.rrw.kine.search.RecedingSearch;
import ai.rrw.kine.util.GameModes;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Input;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Combat movement: during a fight with the mobs it models (zombies and their variants, skeletons and their kin,
 * creepers, spiders; FightSnapshot.kindOf), moves you so you take less damage and the fight goes better, by pressing
 * movement keys (so the server sees an ordinary player walking), on top of the kill aura.
 *
 * <p>At the end of every tick with such mobs (the kill aura's targets) near, it takes a snapshot of the fight
 * (FightSnapshot) and hands it to a worker thread, which runs the receding-horizon search (RecedingSearch over
 * FightModel: the exact movement copy, the mob models, the projectiles in flight and, when it is on, the kill aura's own
 * swing timing) within a
 * time budget, with the effort set from measured replan times (EffortTuner). The next tick, where the movement keys
 * are read (VanillaActions.adjustInput, after the kill aura's swap of your keys), it takes the plan's first action and
 * presses the keys that go nearest its heading at the rotation sent this tick.
 *
 * <p>Control (RW's rule): it moves you only while you press no movement key (W, A, S, D, jump, sneak, sprint, read as
 * physical presses, so toggle sprint and toggle sneak don't hold it off). Press any and only your movement counts;
 * release and it moves you again at once; the search keeps running meanwhile. With no modelled mob within 8 blocks (a
 * skeleton within 16) it lets go completely and doesn't search. On foot only. It stands down when a hostile it doesn't
 * model is within 8 blocks, while you use an item, and when every plan it finds is unsafe. Fall prevention still acts
 * after it. Projectile dodge stands down while it moves you, so the two never press keys in the same tick; its plans
 * fly every projectile the dodge would consider and take their hits into account, so it dodges them itself.
 */
public final class CombatMovement {
    private CombatMovement() {}

    /** Modelled mobs within this many blocks start the search and give it control (skeletons within
     *  FightSnapshot.ENGAGE_BOW). */
    public static final double ENGAGE = FightSnapshot.ENGAGE;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kine-combat-search");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final RecedingSearch.Effort START = effort();
    private static final EffortTuner TUNER = new EffortTuner(START, 2, 10, 1, 4);

    /** Ticks each plan step holds its action when a creeper is in the fight: 10, a 40-tick horizon that sees a whole
     *  fuse (30 ticks) and the walk out of it; 5 otherwise. */
    static final int CREEPER_HOLD = 10;

    static boolean hasCreeper(FightWorld w) {
        for (MobSim m : w.mobs) if (m.alive && m.kind == MobSim.Kind.CREEPER) return true;
        return false;
    }

    static RecedingSearch.Effort effort() {
        RecedingSearch.Effort e = new RecedingSearch.Effort();
        e.beam = 4; e.depth = 4; e.hold = 5; e.draws = 2;
        e.budgetNanos = 25_000_000L;   // a worker over a snapshot: most of the 50 ms between ticks
        return e;
    }

    /** A replan in flight or done: its snapshot, when it was submitted, and its result. */
    private record Job(FightSnapshot.Shot shot, int tick, long submitted, Future<RecedingSearch.Result> result) {}

    private static Job job = null;
    private static RecedingSearch.Result lastResult = null;
    private static FightSnapshot.Shot lastShot = null;
    private static int lastResultTick = 0;
    private static int tick = 0;
    private static boolean controlling = false;
    private static boolean engaged = false;

    // measured, for tests and the report
    public static long searches = 0, controlledTicks = 0, waitedNanos = 0, maxWaitNanos = 0, snapshotNanos = 0, maxSnapshotNanos = 0;
    public static long searchNanos = 0, maxSearchNanos = 0;
    public static String lastAction = "";
    public static String standDownReason = "";

    /** Whether it pressed the keys this tick. */
    public static boolean controlling() {
        return controlling;
    }

    public static RecedingSearch.Effort currentEffort() {
        return TUNER.effort();
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(CombatMovement::endTick);
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "combat_movement"), CombatMovement::render);
    }

    /**
     * Whether combat movement is in a fight it moves you in, or would if you let go of your keys: on, in survival or
     * adventure, on foot (an item in use aside: it lets go while one is, but the fight is still its), the pathfinder not
     * walking you, and a mob it models within its engage distance (FightSnapshot.engages). The bow aura stands down then
     * (RW 2026-10-14: "since cm only works when close enogh to use melle, so use melle there").
     */
    public static boolean engagedFight(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!Settings.combatMovement || p == null || mc.level == null || !GameModes.survivalLike(mc)
            || ai.rrw.kine.walk.Walk.active()) return false;
        if (!p.isAlive() || p.isSpectator() || p.isFallFlying() || p.isInWater() || p.isInLava() || p.isPassenger()
            || p.onClimbable() || p.getAbilities().flying) return false;
        return FightSnapshot.engages(mc, p);
    }

    /** On foot, alive, not using an item: the only state the copy covers. */
    static boolean onFoot(LocalPlayer p) {
        return p.isAlive() && !p.isSpectator() && !p.isFallFlying() && !p.isInWater() && !p.isInLava() && !p.isPassenger()
            && !p.onClimbable() && !p.getAbilities().flying && !p.isUsingItem();
    }

    /** End of a tick: this tick's move packet has gone. Snapshot the fight and start a replan. */
    private static void endTick(Minecraft mc) {
        tick++;
        FightSnapshot.follow(mc);
        LocalPlayer p = mc.player;
        if (!Settings.combatMovement || p == null || mc.level == null || !onFoot(p) || !GameModes.survivalLike(mc)
            || ai.rrw.kine.walk.Walk.active()) {
            engaged = false;
            standDownReason = "off, not on foot, or the pathfinder walking";
            return;
        }
        if (job != null && !job.result().isDone()) return;   // still working on the last one: keep it
        harvest();
        long t0 = System.nanoTime();
        FightSnapshot.Shot shot = FightSnapshot.take(mc);
        long took = System.nanoTime() - t0;
        if (shot == null || shot.mobs() == 0 || !shot.engage()) {
            engaged = false;
            lastResult = null;
            standDownReason = "no modelled mob near";
            return;
        }
        engaged = true;
        snapshotNanos += took;
        maxSnapshotNanos = Math.max(maxSnapshotNanos, took);
        int[] previous = lastResult != null ? lastResult.plan() : null;
        int shift = lastResult != null ? tick - lastResultTick : 0;
        RecedingSearch.Effort e = TUNER.effort().copy();
        if (hasCreeper(shot.world())) e.hold = CREEPER_HOLD;   // a 40-tick horizon: a fuse is 30 (model-world bench: no blasts)
        long seed = tick * 0x9E3779B97F4A7C15L;
        double slider = Settings.killAura ? Settings.combatBias / 100.0 : 0.0;   // without the aura nothing attacks: survive
        int at = tick;
        Future<RecedingSearch.Result> f = WORKER.submit(() -> {
            FightModel model = new FightModel(new GroundPhysics(shot.grid(), shot.body()), slider);
            RecedingSearch<FightWorld> rs = new RecedingSearch<>(model);
            RecedingSearch.Result r = rs.plan(shot.world(), previous, shift, e, seed);
            if (DEBUG) debug(at, shot, rs, model, r, e, seed);
            return r;
        });
        job = new Job(shot, tick, System.nanoTime(), f);
    }

    private static final boolean DEBUG = System.getProperty("kine.combatDebug") != null;

    /** A line per replan for diagnosis (-Dkine.combatDebug): the snapshot, the plan, and what standing still scores. */
    private static void debug(int at, FightSnapshot.Shot shot, RecedingSearch<FightWorld> rs, FightModel fm,
                              RecedingSearch.Result r, RecedingSearch.Effort e, long seed) {
        FightWorld w = shot.world();
        int[] hold = new int[e.horizon()];
        java.util.Arrays.fill(hold, Moves.HOLD);
        double holdScore = rs.score(w, hold, e, seed);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT, "Kine combat debug t=%d p=%.2f,%.2f,%.2f v=%.3f,%.3f hp=%.1f inv=%d k=%d sent=%.1f view=%.1f score=%.3f hold=%.3f plan=",
            at, w.player.x, w.player.y, w.player.z, w.player.vx, w.player.vz, w.health, w.invulnerable, w.aura.k, w.aura.sentYaw,
            w.aura.viewYaw, r.score(), holdScore));
        if (r.plan() != null) for (int i = 0; i < Math.min(10, r.plan().length); i += 5) sb.append(Moves.name(r.plan()[i])).append(' ');
        for (MobSim m : w.mobs) {
            double[] seen = m.seen();
            sb.append(String.format(java.util.Locale.ROOT, "| m%d %s %.2f,%.2f seen %.2f,%.2f v %.3f,%.3f hp %.1f cd %d fresh %d delay %d draw %d atk %d xbow %d swell %d/%d",
                m.id, m.kind, m.x, m.z, seen[0], seen[2], m.vx, m.vz, m.health, m.attackCooldown, m.freshIn, m.delay, m.drawTicks, m.attackTime, m.xbow, m.swell, m.swellDir));
        }
        for (ArrowSim a : w.arrows)
            sb.append(String.format(java.util.Locale.ROOT, "| a%d %.2f,%.2f,%.2f v %.3f,%.3f,%.3f age %d", a.id, a.x, a.y, a.z, a.vx, a.vy, a.vz, a.age));
        // the chosen plan's terms against standing still and walking straight away from the nearest
        if (r.plan() != null) {
            int[] away = new int[e.horizon()];
            java.util.Arrays.fill(away, Moves.action(Moves.AWAY_NEAREST, Moves.WALK));
            sb.append(" || plan ").append(fm.explain(w, r.plan(), e.hold, seed));
            sb.append(" || hold ").append(fm.explain(w, hold, e.hold, seed));
            sb.append(" || away ").append(fm.explain(w, away, e.hold, seed));
        }
        Kine.LOGGER.info(sb.toString());
    }

    /** Take a finished replan's result. */
    private static void harvest() {
        if (job == null || !job.result().isDone()) return;
        try {
            RecedingSearch.Result r = job.result().get();
            searches++;
            searchNanos += r.stats().nanos();
            maxSearchNanos = Math.max(maxSearchNanos, r.stats().nanos());
            TUNER.record(r.stats());
            lastResult = r;
            lastShot = job.shot();
            lastResultTick = job.tick();
        } catch (Exception ex) {
            Kine.LOGGER.warn("Kine combat movement: search failed", ex);
            lastResult = null;
        }
        job = null;
    }

    /** A way out of lit creepers this tick: the heading, and the creepers fled (entity ids). */
    public record Escape(float yaw, java.util.Set<Integer> ids) {
        public boolean flees(net.minecraft.world.entity.Entity e) { return ids.contains(e.getId()); }
    }

    /**
     * RW's sprint out (2026-10-13, his "ok"): while combat movement walks you away from lit creepers (it moved you last
     * tick, and its plan's action now heads away from every lit creeper within 8 blocks: AuraSim.fleeing), the way out
     * and the creepers fled, else null. The kill aura then neither swings at nor faces them, and with nothing else in
     * reach turns toward the way out (within its turn a tick), so the walk out can be a sprint. Read by KillAura before
     * the keybinds, so it goes by the plan in hand.
     */
    public static Escape escape(LocalPlayer p) {
        RecedingSearch.Result r = lastResult;
        FightSnapshot.Shot shot = lastShot;
        if (!Settings.combatMovement || !controlling || r == null || r.plan() == null || shot == null) return null;
        int at = Math.max(0, tick - lastResultTick);
        if (at >= r.plan().length || r.plan()[at] == Moves.HOLD) return null;
        float heading = FightModel.headingFor(shot.world(), Moves.direction(r.plan()[at]));
        MobSim[] mobs = shot.world().mobs;
        boolean[] f = AuraSim.fleeing(mobs, p.getX(), p.getY(), p.getZ(), heading);
        if (f == null) return null;
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (int i = 0; i < mobs.length; i++) if (f[i]) ids.add(mobs[i].id);
        return new Escape(heading, ids);
    }

    /**
     * Where the movement keys are read (VanillaActions.adjustInput, right after the kill aura's swap of your keys):
     * your keys if you press any or it has nothing to do, else the keys of the plan's first action at this tick's
     * rotation.
     */
    public static Input adjustInput(LocalPlayer p, Input keys) {
        controlling = false;
        if (!Settings.combatMovement || !engaged || !onFoot(p) || !GameModes.survivalLike(Minecraft.getInstance())) return keys;
        if (yourMovement(keys)) { standDownReason = "your keys"; return keys; }
        // the replan from the end of the last tick: wait for it a little if it isn't done (in real play it has had
        // the whole time between ticks; in a lockstep test this keeps every run the same)
        if (job != null && !job.result().isDone()) {
            long left = job.submitted() + START.budgetNanos + 10_000_000L - System.nanoTime();
            long w0 = System.nanoTime();
            try { if (left > 0) job.result().get(left, TimeUnit.NANOSECONDS); } catch (Exception ignored) { }
            long waited = System.nanoTime() - w0;
            waitedNanos += waited;
            maxWaitNanos = Math.max(maxWaitNanos, waited);
        }
        harvest();
        RecedingSearch.Result r = lastResult;
        if (r == null || r.plan() == null) { standDownReason = "no plan"; return keys; }
        if (r.score() >= FightModel.UNSAFE) { standDownReason = "every plan unsafe"; return keys; }
        if (lastShot.unsupported() > 0) { standDownReason = "a hostile it doesn't model is near"; return keys; }
        int at = Math.max(0, tick - lastResultTick);
        if (at >= r.plan().length) { standDownReason = "plan too old"; return keys; }
        int action = r.plan()[at];
        int k = 0;
        if (action != Moves.HOLD) {
            float heading = FightModel.headingFor(lastShot.world(), Moves.direction(action));
            if (!Float.isNaN(heading)) k = Moves.keys(heading, p.getYRot(), Moves.gait(action));
        }
        controlling = true;
        controlledTicks++;
        lastAction = Moves.name(action);
        standDownReason = "";
        return WorldSnapshot.input(k);
    }

    /** Whether you press a movement key yourself. W, A, S, D and jump as the game read them; sneak and sprint as
     *  physical presses (with toggle sneak or toggle sprint the game's state stays on after you let go). */
    static boolean yourMovement(Input keys) {
        if (keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump()) return true;
        if (ai.rrw.kine.view.Freecam.active()) return false;   // in freecam your sneak and sprint move the camera
        Minecraft mc = Minecraft.getInstance();
        return physical(mc, mc.options.keyShift, mc.options.toggleCrouch().get())
            || physical(mc, mc.options.keySprint, mc.options.toggleSprint().get());
    }

    private static boolean physical(Minecraft mc, KeyMapping km, boolean toggle) {
        if (!toggle) return km.isDown();
        InputConstants.Key key = ((KeyMappingAccessor) km).kine$getKey();
        if (key.getType() == InputConstants.Type.KEYSYM) return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
        return km.isDown();
    }

    /** The indicator: MOVE in green on the right, under AP, in the row WALK uses (the two never show together), while
     *  it moves you. */
    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!controlling || mc.player == null || mc.options.hideGui) return;
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        int x = HudLayout.apLeftEdge(w / 2, 0);
        g.text(mc.font, "MOVE", x, HudLayout.annunciatorLower(h, mc.font.lineHeight), HudColors.GREEN, true);
    }
}
