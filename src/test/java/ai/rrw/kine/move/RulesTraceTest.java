package ai.rrw.kine.move;

import ai.rrw.kine.compat.GameVersion;
import ai.rrw.kine.guard.GlideSim;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The movement copy against real ticks where the game's rules differ between releases (compat/Rules): proof_traces.txt
 * is recorded in a lockstep client test (ct2 ProofPhysicsTest, 2026-10-15; its javadoc lists the runs and the flag each
 * is for). Replayed open loop from each run's start with the run's own step height and glider, the copy must give the
 * game's doubles and flags every tick, the game's air every tick (when the client and server ran in lockstep), and over
 * each run the health the game took from its landings (GlideSim.fallDamage of each landing the copy makes, with the
 * landing block's factor; not where the copy marks a hazard: a glide into a wall hurts by its speed, which the copy
 * doesn't count). A hurt sends the client the server's velocity for its player, which no copy follows (as
 * GlidePhysicsTest notes for a glide into a wall): it comes with the health, so a run is compared up to the tick
 * before the client's health first drops. traceTest replays a release's own recording with that release's rules; the
 * file in the test resources is 26.1.2's.
 *
 * <p>The file has, beside kine's trace lines, lines Traces.read skips: "xproof physics release lockstep", per run
 * "xbody stepHeight canGlide" and "xs health", per tick "x health clientFallDistance" (after the tick).
 */
public class RulesTraceTest {
    /** What the proof file adds to kine's trace format. */
    public record Extra(String release, boolean lockstep, List<RunExtra> runs) {}

    /** A run's own step height and glider, its health at the start, and after each tick the client's health and fall
     *  distance. */
    public record RunExtra(float stepHeight, boolean canGlide, float health0, List<Float> health, List<Double> clientFall) {}

    /** A run replayed: the largest position and velocity differences, the first tick a flag differed (-1 none), the
     *  first tick the air differed (-1 none, or not compared), the damage the copy's landings make and the health the
     *  game lost, and the first difference in words. */
    public record Result(String run, double pos, double vel, int flagTick, int airTick, double damageCopy, double damageGame,
                         int compared, String first) {
        public boolean exact() {
            return pos == 0.0 && vel == 0.0 && flagTick < 0 && airTick < 0 && damageCopy == damageGame;
        }
    }

    /**
     * Runs the copy is known to get wrong, so their difference is shown and not counted as a failure (the copy isn't
     * changed here); the test fails instead if one of them stops differing, so the entry goes when the copy is fixed:
     * run name, the first and last release it is wrong on, and why.
     * <ul>
     *   <li>glide_vine from 1.21.11: LivingEntity.onClimbable is false while gliding in a block of #can_glide_through
     *       (vines), so the game glides on through the vines; GroundPhysics.onClimbable has no such test and ends the
     *       glide there (physics/FINDINGS.md, "Kine against 26.1.2 itself").</li>
     * </ul>
     * Fixed since and so off the list (2026-10-15): the air going under water up to 1.21.4 (Rules.CLIENT_TICKS_AIR) and
     * 1.21.4's client not looking at the water after its move (GroundPhysics.move, Rules.CLIENT_SKIPS_FALL_CHECK):
     * water_drop_4, _25 and _40 replay on 1.21.2 and 1.21.4.
     */
    static final String[][] KNOWN = {
        {"glide_vine", "1.21.11", "99", "GroundPhysics.onClimbable lacks 1.21.11's can_glide_through test while gliding"},
    };

    /** Why a run is a known fault on this release, or null. */
    static String known(String run) {
        for (String[] k : KNOWN)
            if (k[0].equals(run) && GameVersion.atLeast(k[1]) && GameVersion.compare(GameVersion.minecraft(), k[2]) <= 0) return k[3];
        return null;
    }

    public static Extra extras(String text) {
        String release = "?";
        boolean lockstep = true;
        List<RunExtra> runs = new ArrayList<>();
        float step = 0, h0 = 0;
        boolean glider = false;
        List<Float> health = null;
        List<Double> fall = null;
        for (String line : text.split("\n")) {
            String[] p = line.trim().split(" ");
            switch (p[0]) {
                case "xproof" -> { release = p[2]; lockstep = Boolean.parseBoolean(p[3]); }
                case "run" -> {
                    if (health != null) runs.add(new RunExtra(step, glider, h0, health, fall));
                    health = new ArrayList<>();
                    fall = new ArrayList<>();
                }
                case "xbody" -> { step = Float.parseFloat(p[1]); glider = Boolean.parseBoolean(p[2]); }
                case "xs" -> h0 = Float.parseFloat(p[1]);
                case "x" -> { health.add(Float.parseFloat(p[1])); fall.add(Double.parseDouble(p[2])); }
                default -> {}
            }
        }
        if (health != null) runs.add(new RunExtra(step, glider, h0, health, fall));
        return new Extra(release, lockstep, runs);
    }

    /** The header's body with a run's own step height and glider. */
    static Body body(Body b, RunExtra x) {
        Body c = new Body();
        c.walkSpeed = b.walkSpeed; c.sprintSpeed = b.sprintSpeed; c.sneakSpeed = b.sneakSpeed; c.jumpStrength = b.jumpStrength;
        c.jumpBoost = b.jumpBoost; c.gravity = b.gravity; c.stepHeight = x.stepHeight(); c.movementEfficiency = b.movementEfficiency;
        c.width = b.width; c.height = b.height; c.crouchHeight = b.crouchHeight; c.swimHeight = b.swimHeight;
        c.eyeHeight = b.eyeHeight; c.crouchEyeHeight = b.crouchEyeHeight; c.swimEyeHeight = b.swimEyeHeight;
        c.waterMovementEfficiency = b.waterMovementEfficiency; c.dolphinsGrace = b.dolphinsGrace; c.canSprint = b.canSprint;
        c.enoughFood = b.enoughFood; c.blind = b.blind; c.sprintWindow = b.sprintWindow; c.canGlide = x.canGlide();
        c.glide = b.glide; c.glideLandTicks = b.glideLandTicks; c.maxAir = b.maxAir; c.oxygenBonus = b.oxygenBonus;
        c.turtleHelmet = b.turtleHelmet; c.airEyeLag = b.airEyeLag; c.knockbackResistance = b.knockbackResistance;
        return c;
    }

    /** The trace format keeps no fall factor (BlockInfo.fallFactor, WorldSnapshot.fallFactor): a landing on slime
     *  costs nothing (SlimeBlock.fallOn), put back here from the slime flag; the scenes land on nothing else that
     *  changes it. */
    static void fallFactors(BlockGrid g) {
        java.util.Map<BlockInfo, BlockInfo> slime = new java.util.IdentityHashMap<>();
        for (int x = g.x0; x < g.x0 + g.nx; x++)
            for (int y = g.y0; y < g.y0 + g.ny; y++)
                for (int z = g.z0; z < g.z0 + g.nz; z++) {
                    BlockInfo b = g.get(x, y, z);
                    if (!b.is(BlockInfo.SLIME)) continue;
                    g.set(x, y, z, slime.computeIfAbsent(b, k -> new BlockInfo(k.shape, k.friction, k.speedFactor, k.jumpFactor,
                        k.flags, k.fluid, k.fluidHeight, 0.0F, k.bounce)));
                }
    }

    /** Every run of a proof file through the copy, with the rules of the release this JVM runs as. */
    public static List<Result> replay(String text) throws IOException {
        Traces.File f = Traces.read(new StringReader(text));
        fallFactors(f.grid());
        Extra x = extras(text);
        assertEquals(f.runs().size(), x.runs().size(), "an xbody line per run");
        List<Result> out = new ArrayList<>();
        for (int r = 0; r < f.runs().size(); r++) {
            Traces.Run run = f.runs().get(r);
            RunExtra e = x.runs().get(r);
            GroundPhysics phys = new GroundPhysics(f.grid(), body(f.body(), e));
            MoveState s = run.start().copy();
            double pos = 0, vel = 0, dmg = 0;
            int flag = -1, air = -1;
            boolean hazard = false;
            String first = "";
            // up to the tick before the client's health first drops (the server's velocity comes with it)
            int n = run.ticks().size();
            for (int i = 0; i < e.health().size(); i++)
                if (e.health().get(i) < (i == 0 ? e.health0() : e.health().get(i - 1))) { n = i; break; }
            for (int i = 0; i < n; i++) {
                Traces.Tick t = run.ticks().get(i);
                s.landedFall = 0.0;
                s.touched = 0;
                phys.step(s, t.keys(), t.yaw(), t.pitch());
                if (s.landedFall > 0.0) dmg += Math.max(0.0, GlideSim.fallDamage(s.landedFall, 3.0, s.landedFallFactor));
                hazard |= (s.touched & BlockInfo.HAZARD) != 0;
                double dp = Math.max(Math.abs(s.x - t.x()), Math.max(Math.abs(s.y - t.y()), Math.abs(s.z - t.z())));
                double dv = Math.max(Math.abs(s.vx - t.vx()), Math.max(Math.abs(s.vy - t.vy()), Math.abs(s.vz - t.vz())));
                if ((dp > 0 || dv > 0) && pos == 0 && vel == 0)
                    first += String.format(Locale.ROOT, "tick %d: game pos %s vel %s, copy pos %s vel %s; ", i,
                        vec(t.x(), t.y(), t.z()), vec(t.vx(), t.vy(), t.vz()), vec(s.x, s.y, s.z), vec(s.vx, s.vy, s.vz));
                pos = Math.max(pos, dp);
                vel = Math.max(vel, dv);
                int mask = (t.water() & 64) != 0 ? 0x7F : 0x0F;
                boolean flagDiff = s.onGround != t.onGround() || s.sprinting != t.sprinting()
                    || t.water() >= 0 && (Traces.Tick.flags(s) & mask) != (t.water() & mask);
                if (flag < 0 && flagDiff) {
                    flag = i;
                    first += String.format(Locale.ROOT, "flags at tick %d: game ground %b sprint %b water %d, copy %b %b %d; ", i,
                        t.onGround(), t.sprinting(), t.water() & mask, s.onGround, s.sprinting, Traces.Tick.flags(s) & mask);
                }
                if (air < 0 && x.lockstep() && t.air() != Traces.Tick.NO_AIR && Math.abs(s.air - t.air()) > 1e-9) {
                    air = i;
                    first += String.format(Locale.ROOT, "air at tick %d: game %d, copy %.1f; ", i, t.air(), s.air);
                }
            }
            // the hurt's own tick: when the server runs on its own (1.21.1's boot harness) the health can come in the tick
            // of the hurting move itself, so the copy goes one more tick for its landings and hazards (not compared)
            if (n < run.ticks().size()) {
                Traces.Tick t = run.ticks().get(n);
                s.landedFall = 0.0;
                s.touched = 0;
                phys.step(s, t.keys(), t.yaw(), t.pitch());
                if (s.landedFall > 0.0) dmg += Math.max(0.0, GlideSim.fallDamage(s.landedFall, 3.0, s.landedFallFactor));
                hazard |= (s.touched & BlockInfo.HAZARD) != 0;
            }
            double lost = e.health().isEmpty() ? 0.0 : e.health0() - e.health().get(e.health().size() - 1);
            // a flight into a wall hurts by its speed, which the copy marks as a hazard and doesn't count: no damage compared
            if (hazard) dmg = lost;
            if (dmg != lost) first += String.format(Locale.ROOT, "damage: game %.1f, copy %.1f", lost, dmg);
            out.add(new Result(run.name(), pos, vel, flag, air, dmg, lost, n, first));
        }
        return out;
    }

    static String vec(double a, double b, double c) {
        return String.format(Locale.ROOT, "(%.9f, %.9f, %.9f)", a, b, c);
    }

    static String text() throws IOException {
        try (InputStream in = RulesTraceTest.class.getResourceAsStream("/move/proof_traces.txt")) {
            assertNotNull(in, "proof traces missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyRecordedTickIsReproducedUnderTheRecordingsRelease() throws Exception {
        String text = text();
        Extra x = extras(text);
        System.out.printf(Locale.ROOT, "proof_traces.txt recorded on %s (lockstep %b), replayed under %s's rules%n", x.release(),
            x.lockstep(), GameVersion.minecraft());
        List<String> wrong = new ArrayList<>();
        int ticks = 0;
        for (Result r : replay(text)) {
            String k = known(r.run());
            System.out.printf(Locale.ROOT, "  %-20s %s pos %.3e vel %.3e flagTick %d airTick %d damage game %.1f copy %.1f ticks %d%s %s%n",
                r.run(), r.exact() ? "exact" : k != null ? "KNOWN" : "DIFF ", r.pos(), r.vel(), r.flagTick(), r.airTick(),
                r.damageGame(), r.damageCopy(), r.compared(), k != null ? " (" + k + ")" : "", r.first());
            if (!r.exact() && k == null) wrong.add(r.run() + ": " + r.first());
            if (r.exact() && k != null) wrong.add(r.run() + ": exact now, though listed as a known fault (" + k + "): take it off KNOWN");
        }
        for (Traces.Run run : Traces.read(new StringReader(text)).runs()) ticks += run.ticks().size();
        assertTrue(ticks > 1000, "ticks: " + ticks);
        assertTrue(wrong.isEmpty(), "runs the copy doesn't reproduce: " + String.join(" | ", wrong));
    }

    @Test
    void theRunsDoWhatTheirNamesSay() throws Exception {
        String text = text();
        Traces.File f = Traces.read(new StringReader(text));
        Extra x = extras(text);
        for (int i = 0; i < f.runs().size(); i++) {
            Traces.Run run = f.runs().get(i);
            RunExtra e = x.runs().get(i);
            Traces.Tick last = run.ticks().get(run.ticks().size() - 1);
            switch (run.name()) {
                // the jump on the slime's bounce goes higher than a jump from the ground (0.42 a tick: 1.25 blocks)
                case "slime_bounce_jump" -> assertTrue(run.ticks().stream().mapToDouble(Traces.Tick::y).max().orElse(0) > -60 + 1.3,
                    "a jump off the bounce");
                // the long drops reach the water's floor area and the eyes go under
                case "water_drop_25", "water_drop_40" -> assertTrue(run.ticks().stream().anyMatch(t -> (t.water() & 8) != 0), "eyes under");
                // the water breathing run is under water with the air under the most at the start
                case "breath_refill" -> assertTrue(run.start().air < 250 && run.start().waterBreathingTicks > 0, "air "
                    + run.start().air + " breathing " + run.start().waterBreathingTicks);
                // a glider worn in the glides, and the glide starts
                case "glide_ladder", "glide_vine", "glide_scaffold" -> {
                    assertTrue(e.canGlide(), "elytra");
                    assertTrue(run.ticks().stream().anyMatch(t -> (t.water() & 16) != 0), "glided");
                }
                case "edge_625" -> assertEquals(0.624995F, e.stepHeight(), 1e-7F, "step height");
                case "drop_4", "drop_4_0625", "drop_5_9375" -> assertTrue(e.health0() - e.health().get(e.health().size() - 1) >= 1.0F, "hurt");
                default -> { }
            }
            assertTrue(last != null);
        }
    }
}
