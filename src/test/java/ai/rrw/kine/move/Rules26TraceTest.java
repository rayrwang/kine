package ai.rrw.kine.move;

import ai.rrw.kine.compat.GameVersion;
import ai.rrw.kine.compat.UnderRelease;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The movement copy against real ticks where the 26.x releases' rules differ (compat/Rules): the recordings of ct2
 * RulesPhysicsTest, made in a lockstep client test by the 26.x proof (2026-10-15, kine jar cee94706, results
 * ct/&lt;release&gt;/results/p1_rules*): falls onto slime from 5, 8 and 1 blocks, one with the shift key held and one
 * with it released after the landing and pressed again in the air, walking and a sprint jump on slime, a jump into a
 * ceiling with slime under the feet (pressed once, and held), a fall onto a bed with and without the shift key, and one
 * onto honey. From 26.2 a collision restitutes the movement (Rules.BOUNCE_RESTITUTION), and nine of the twelve runs go
 * otherwise than up to 26.1.2 (BOUNCE_RUNS); in the other three the shift key or honey ends the fall the same way in
 * both. Each recording replays exactly (Traces.replay: the same doubles and flags every tick) under its own release's
 * rules, and differs in those nine runs under the other family's (UnderRelease).
 *
 * <p>The fixtures in move/: rules_26.1.2.txt.gz is 26.1.2's p1_rules2 (26.1's p1_rules is the same bytes);
 * rules_26.2.txt.gz is 26.2's p1_rules (26.3's p1_rules is the same bytes, so it stands for 26.3 too). The recorder's
 * last run, a jump pressed in lava with an elytra worn, isn't traced (kine doesn't copy lava's own movement): only its
 * glide start is checked, below. traceTest replays a release's own recording, move/rules_traces.txt as the recorder
 * writes it, with that release's rules (theReleasesOwnRecordingReplaysExactly).
 */
public class Rules26TraceTest {
    /** The recordings, by the release each was made on, and the fixture holding it. */
    static final String[][] RECORDINGS = {
        {"26.1", "rules_26.1.2.txt.gz"},
        {"26.1.2", "rules_26.1.2.txt.gz"},
        {"26.2", "rules_26.2.txt.gz"},
        {"26.3", "rules_26.2.txt.gz"},
    };

    /** The runs 26.2's restitution changes: each differs under the other family's rules. Up to 26.1.2 slime under the
     *  feet reverses a downward v.y at every vertical hit (a landing, and each tick of standing or walking on it) and
     *  keeps an upward one (a ceiling hit), and a bed returns 0.66 of it (SlimeBlock, BedBlock.bounceUp); from 26.2 a
     *  bounce comes only from a fall of at least a tick's gravity, adds back the gravity and drag of the part of the
     *  tick after the hit, a bed's restitution is 0.75, and a ceiling hit takes v.y to 0 (the player's bounciness). */
    static final List<String> BOUNCE_RUNS = List.of("slime_drop", "slime_drop_high", "slime_drop_low", "slime_drop_shift_mid",
        "slime_walk", "slime_sprintjump", "slime_ceiling", "slime_ceiling_held", "bed_drop");

    static String read(String file) {
        try (InputStream in = Rules26TraceTest.class.getResourceAsStream("/move/" + file)) {
            if (in == null) return null;
            InputStream text = file.endsWith(".gz") ? new GZIPInputStream(in) : in;
            return new String(text.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every run of a recording through the copy, with the rules of the release this class was loaded for: the run's
     *  name when it replays exactly, else the name and how it differs ("name: ..."). */
    static String[] replay(String text) {
        Traces.File f;
        try {
            f = Traces.read(new StringReader(text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GroundPhysics phys = new GroundPhysics(f.grid(), f.body());
        List<String> out = new ArrayList<>();
        for (Traces.Run run : f.runs()) {
            Traces.Diff d = Traces.replay(phys, run);
            out.add(d.pos() == 0.0 && d.vel() == 0.0 && d.flagTick() < 0 ? run.name()
                : String.format(Locale.ROOT, "%s: pos %.3e vel %.3e flagTick %d worstTick %d", run.name(), d.pos(), d.vel(),
                    d.flagTick(), d.worstTick()));
        }
        return out.toArray(new String[0]);
    }

    /** A fixture replayed under the release UnderRelease loads the class for. */
    public abstract static class Replay implements Supplier<String[]> {
        private final String file;

        Replay(String file) {
            this.file = file;
        }

        @Override
        public String[] get() {
            return replay(read(file));
        }
    }

    public static final class Of2612 extends Replay {
        public Of2612() { super("rules_26.1.2.txt.gz"); }
    }

    public static final class Of262 extends Replay {
        public Of262() { super("rules_26.2.txt.gz"); }
    }

    static String[] under(String release, String file) {
        return file.equals("rules_26.1.2.txt.gz") ? UnderRelease.get(release, Of2612.class) : UnderRelease.get(release, Of262.class);
    }

    static List<String> differing(String[] runs) {
        List<String> d = new ArrayList<>();
        for (String r : runs) if (r.contains(":")) d.add(r.substring(0, r.indexOf(':')));
        return d;
    }

    @Test
    void eachRecordingReplaysExactlyUnderItsOwnRelease() {
        for (String[] rec : RECORDINGS) {
            String[] runs = under(rec[0], rec[1]);
            System.out.printf(Locale.ROOT, "%s (%s) under %s's rules: %d runs, %d differ%n", rec[1], rec[0], rec[0], runs.length,
                differing(runs).size());
            assertEquals(12, runs.length, rec[1] + ": runs");
            for (String r : runs) assertFalse(r.contains(":"), rec[1] + " under " + rec[0] + "'s rules: " + r);
        }
    }

    @Test
    void eachDiffersInTheBounceRunsUnderTheOtherFamilysRules() {
        String[][] crossed = {
            {"26.2", "rules_26.1.2.txt.gz"}, {"26.3", "rules_26.1.2.txt.gz"},
            {"26.1.2", "rules_26.2.txt.gz"}, {"26.1", "rules_26.2.txt.gz"},
        };
        for (String[] c : crossed) {
            String[] runs = under(c[0], c[1]);
            for (String r : runs) System.out.printf(Locale.ROOT, "  %s under %s's rules: %s%n", c[1], c[0], r);
            assertEquals(BOUNCE_RUNS, differing(runs), c[1] + " under " + c[0] + "'s rules: the runs that differ");
        }
    }

    /** traceTest: the release's own recording, when its traces hold move/rules_traces.txt, else the fixture of the
     *  release this JVM runs as (the development release in the unit tests), with this JVM's rules. */
    @Test
    void theReleasesOwnRecordingReplaysExactly() {
        String release = GameVersion.minecraft(), text = read("rules_traces.txt"), name = "rules_traces.txt";
        if (text == null) {
            for (String[] rec : RECORDINGS) if (rec[0].equals(release)) name = rec[1];
            text = read(name);
        }
        assertNotNull(text, "no recording for " + release);
        String[] runs = replay(text);
        System.out.printf(Locale.ROOT, "%s under %s's rules: %d runs, %d differ%n", name, release, runs.length, differing(runs).size());
        for (String r : runs) assertFalse(r.contains(":"), name + " under " + release + "'s rules: " + r);
    }

    /** The copy's glide start for a jump newly pressed in lava, off the ground, with an elytra worn and gliding copied:
     *  a player in the middle of a pool three deep, one tick in it with no keys (the fluid seen), then the press. */
    public static final class LavaGlideStart implements Supplier<Boolean> {
        @Override
        public Boolean get() {
            BlockGrid g = new BlockGrid(-8, 50, -8, 17, 20, 17);
            BlockInfo stone = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
            BlockInfo lava = new BlockInfo(Shape.EMPTY, 0.6F, 1.0F, 1.0F, BlockInfo.HAZARD | BlockInfo.FLUID, BlockInfo.LAVA_FLUID,
                8.0F / 9.0F, 1.0F);
            for (int x = -8; x <= 8; x++)
                for (int z = -8; z <= 8; z++) {
                    g.set(x, 59, z, stone);
                    for (int y = 60; y <= 62; y++) g.set(x, y, z, lava);
                }
            Body b = new Body();
            b.canGlide = true;
            b.glide = true;
            GroundPhysics phys = new GroundPhysics(g, b);
            MoveState s = new MoveState();
            s.x = 0.5; s.y = 60.6; s.z = 0.5;
            s.vy = -0.01;
            phys.step(s, 0, 0f, 0f);
            assertTrue(s.inLava && !s.onGround, "in the lava, off the ground");
            phys.step(s, MoveState.JUMP, 0f, 0f);
            return s.fallFlying;
        }
    }

    /** Rules.NO_GLIDE_START_IN_LAVA. RulesPhysicsTest's lava_glide_press (results.txt): the glide starts in the press's
     *  tick on 26.1, 26.1.1, 26.1.2 and 26.2 (15 gliding ticks) and never on 26.3. */
    @Test
    void aJumpPressedInLavaStartsNoGlideFrom263() {
        assertTrue(UnderRelease.get("26.1.2", LavaGlideStart.class));
        assertTrue(UnderRelease.get("26.2", LavaGlideStart.class));
        assertFalse(UnderRelease.get("26.3", LavaGlideStart.class));
    }
}
