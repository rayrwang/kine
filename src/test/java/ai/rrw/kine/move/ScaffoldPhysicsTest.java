package ai.rrw.kine.move;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scaffolding in the movement copy (2026-10-15): its collision shape from the player's collision context (where the
 * feet are at each collision query, the shift key then), climbing in it, and the shift key's way down through it. The
 * rules on a made-up scene here (the scene of the lockstep test, scene()), and the recorded ticks of
 * scaffold_traces.txt replayed open loop: the copy must give the game's doubles. The file was recorded 2026-10-15 in a
 * lockstep client test (the course's scaffold helper's clienttest ScaffoldPhysicsTest, run scphys2, fall damage off)
 * with the game's keys pressed tick by tick: 20 runs, 845 ticks, walking into a column's side, climbing it with the
 * jump key held, on its top, sneaking down through it and letting go part way, walking off it and onto it, bottom
 * pieces walked and jumped under, sneaked into and along, the course's scaffolding_jump made and missed, a piece
 * flush with the ground.
 */
public class ScaffoldPhysicsTest {
    static final int F = MoveState.FORWARD, B = MoveState.BACKWARD, L = MoveState.LEFT, R = MoveState.RIGHT,
                     J = MoveState.JUMP, S = MoveState.SHIFT, SP = MoveState.SPRINT;

    static final BlockInfo SOLID = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
    static final BlockInfo SCAFFOLD = new BlockInfo(Shape.SCAFFOLD_STABLE, 0.6F, 1.0F, 1.0F,
        BlockInfo.SPECIAL | BlockInfo.CLIMBABLE | BlockInfo.SCAFFOLD | BlockInfo.LARGE);
    static final BlockInfo SCAFFOLD_BOTTOM = new BlockInfo(Shape.SCAFFOLD_STABLE, 0.6F, 1.0F, 1.0F,
        BlockInfo.SPECIAL | BlockInfo.CLIMBABLE | BlockInfo.SCAFFOLD | BlockInfo.SCAFFOLD_BOTTOM | BlockInfo.LARGE);

    /**
     * The lockstep test's scene (clienttest ScaffoldPhysicsTest.build, a superflat world: the ground's top at -61, feet
     * at -60): S1 a column of three on the ground at (50, -60..-58, 10), a stone pillar beside it at (50, -60..-56, 9);
     * S2 a column of two at (50, -60..-59, 16) with an
     * arm of two bottom pieces at (51..52, -59, 16) over the ground (head height); S3 the course's scaffolding_jump: the
     * take-off stone at (60, -58, 22), a column of three at (64, -58..-56, 22) on stone at (64, -60..-59, 22); S4 a
     * piece flush with the ground at (56, -61, 10).
     */
    static BlockGrid scene() {
        BlockGrid g = new BlockGrid(40, -64, 0, 41, 16, 31);
        for (int x = 40; x <= 80; x++)
            for (int z = 0; z <= 30; z++)
                for (int y = -64; y <= -61; y++) g.set(x, y, z, SOLID);
        for (int y = -60; y <= -58; y++) g.set(50, y, 10, SCAFFOLD);
        for (int y = -60; y <= -56; y++) g.set(50, y, 9, SOLID);
        for (int y = -60; y <= -59; y++) g.set(50, y, 16, SCAFFOLD);
        g.set(51, -59, 16, SCAFFOLD_BOTTOM);
        g.set(52, -59, 16, SCAFFOLD_BOTTOM);
        g.set(60, -58, 22, SOLID);
        for (int y = -60; y <= -59; y++) g.set(64, y, 22, SOLID);
        for (int y = -58; y <= -56; y++) g.set(64, y, 22, SCAFFOLD);
        g.set(56, -61, 10, SCAFFOLD);
        return g;
    }

    static MoveState standing(double x, double y, double z) {
        MoveState s = new MoveState();
        s.x = x; s.y = y; s.z = z;
        s.vy = -0.0784000015258789;
        s.onGround = true;
        s.hasSupport = true; s.supX = (int) Math.floor(x); s.supY = (int) Math.floor(y) - 1; s.supZ = (int) Math.floor(z);
        return s;
    }

    /** Pairs: ticks, keys. */
    static int[] script(int... segs) {
        List<Integer> k = new ArrayList<>();
        for (int i = 0; i < segs.length; i += 2) for (int t = 0; t < segs[i]; t++) k.add(segs[i + 1]);
        return k.stream().mapToInt(Integer::intValue).toArray();
    }

    static MoveState run(GroundPhysics phys, MoveState s, int[] keys, float yaw, boolean print) {
        for (int t = 0; t < keys.length; t++) {
            phys.step(s, keys[t], yaw);
            if (print) System.out.printf(Locale.ROOT, "  t=%d keys=%d %s climb=%b%n", t, keys[t], s, phys.onClimbable(s));
        }
        return s;
    }

    static Traces.File traces() throws Exception {
        try (var in = ScaffoldPhysicsTest.class.getResourceAsStream("/move/scaffold_traces.txt")) {
            assertNotNull(in, "golden traces missing");
            return Traces.read(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    static Traces.Run run(Traces.File f, String name) {
        return f.runs().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void everyRecordedTickIsReproducedExactly() throws Exception {
        Traces.File f = traces();
        assertEquals(RUNS.length, f.runs().size());
        GroundPhysics phys = new GroundPhysics(f.grid(), f.body());
        int ticks = 0;
        for (Traces.Run run : f.runs()) {
            Traces.Diff d = Traces.replay(phys, run);
            assertEquals(0.0, d.pos(), run.name() + ": position");
            assertEquals(0.0, d.vel(), run.name() + ": velocity");
            assertEquals(-1, d.flagTick(), run.name() + ": a flag");
            ticks += run.ticks().size();
        }
        assertTrue(ticks > 800, "ticks: " + ticks);
    }

    @Test
    void theRecordedRunsDoWhatTheirNamesSay() throws Exception {
        Traces.File f = traces();
        // the snapshot's pieces: the column's flags, the arm's bottom pieces
        assertTrue(f.grid().get(50, -58, 10).is(BlockInfo.SCAFFOLD | BlockInfo.CLIMBABLE));
        assertFalse(f.grid().get(50, -58, 10).is(BlockInfo.SCAFFOLD_BOTTOM));
        assertTrue(f.grid().get(51, -59, 16).is(BlockInfo.SCAFFOLD_BOTTOM));
        Traces.Tick end;
        // climbed with the jump key held: on the top
        end = last(run(f, "s1_climb_jump"));
        assertEquals(-57.0, end.y(), 0.0);
        assertTrue(end.onGround());
        // sneaked down through it to the ground; let go part way, it stood on the piece it was above
        assertEquals(-60.0, last(run(f, "s1_top_sneak_down")).y(), 0.0);
        assertEquals(-59.0, last(run(f, "s1_sneak_release")).y(), 0.0);
        // walked through its foot
        assertTrue(last(run(f, "s1_walk_into_side")).x() > 51.3);
        // a bottom piece: sneaked into it from its top, it stands on its lowest eighth
        assertEquals(-58.875, last(run(f, "s2_arm_top_sneak")).y(), 0.0);
        // the course's way: on the top; the jump that comes down onto a piece's top slab's side is stopped by it
        assertEquals(-55.0, last(run(f, "s3_way_a")).y(), 0.0);
        assertEquals(-55.0, last(run(f, "s3_way_b")).y(), 0.0);
        assertEquals(-60.0, last(run(f, "s3_blocked")).y(), 0.0);
    }

    static Traces.Tick last(Traces.Run r) {
        return r.ticks().get(r.ticks().size() - 1);
    }

    @Test
    void aCopyWithoutTheContextFails() throws Exception {
        // scaffolding as one fixed shape (its shape for no one: SHAPE_STABLE), as the copy had it from the snapshot
        Traces.File f = traces();
        java.util.List<BlockInfo> kinds = f.grid().kinds();
        for (int i = 0; i < kinds.size(); i++) {
            BlockInfo k = kinds.get(i);
            if (k.is(BlockInfo.SCAFFOLD))
                kinds.set(i, new BlockInfo(k.shape, k.friction, k.speedFactor, k.jumpFactor,
                    k.flags & ~(BlockInfo.SCAFFOLD | BlockInfo.SCAFFOLD_BOTTOM), k.fluid, k.fluidHeight, k.fallFactor));
        }
        GroundPhysics phys = new GroundPhysics(f.grid(), f.body());
        int differ = 0;
        for (Traces.Run run : f.runs()) if (Traces.replay(phys, run).pos() > 1e-3) differ++;
        assertTrue(differ >= 10, "runs that differ: " + differ);
    }

    @Test
    void theShapeFollowsTheContext() {
        BlockInfo b = SCAFFOLD, bot = SCAFFOLD_BOTTOM;
        // above the block (its top at y + 1, within 1e-5): the stable shape, unless sneaking
        assertSame(b.shape, BlockInfo.scaffoldShape(b, 10, 11.0, false));
        assertSame(b.shape, BlockInfo.scaffoldShape(b, 10, 11.0 - 9.9e-6, false));
        assertSame(Shape.EMPTY, BlockInfo.scaffoldShape(b, 10, 11.0 - 1.0e-5, false));
        assertSame(Shape.EMPTY, BlockInfo.scaffoldShape(b, 10, 11.0, true));
        assertSame(Shape.EMPTY, BlockInfo.scaffoldShape(b, 10, 10.5, false));
        // a bottom piece: its lowest eighth for a player above its bottom (sneaking or inside it)
        assertSame(Shape.SCAFFOLD_UNSTABLE_BOTTOM, BlockInfo.scaffoldShape(bot, 10, 11.0, true));
        assertSame(Shape.SCAFFOLD_UNSTABLE_BOTTOM, BlockInfo.scaffoldShape(bot, 10, 10.125, false));
        assertSame(Shape.EMPTY, BlockInfo.scaffoldShape(bot, 10, 9.9999, false));
        assertSame(bot.shape, BlockInfo.scaffoldShape(bot, 10, 11.0, false));
    }

    @Test
    void standOnTopSneakDownClimbUp() {
        GroundPhysics phys = new GroundPhysics(scene(), new Body());
        // standing on the column's top: it holds
        MoveState s = run(phys, standing(50.5, -57, 10.5), script(20, 0), -90f, false);
        assertEquals(-57.0, s.y, 0.0);
        assertTrue(s.onGround);
        // the shift key: down through it (no hold as on a ladder), at most 0.15 a tick, onto the ground
        s = run(phys, standing(50.5, -57, 10.5), script(30, S), -90f, false);
        assertEquals(-60.0, s.y, 0.0);
        // part way down, the key let go: it stands on the block it is above
        s = run(phys, standing(50.5, -57, 10.5), script(4, S, 20, 0), -90f, false);
        assertEquals(-58.0, s.y, 0.0);
        assertTrue(s.onGround);
        // inside it at the bottom, the jump key held: up and onto its top
        s = run(phys, standing(50.5, -60, 10.5), script(45, J), -90f, false);
        assertTrue(s.y >= -57.0 - 1e-9, "up: " + s);
        s = run(phys, s, script(20, 0), -90f, false);
        assertEquals(-57.0, s.y, 0.0);
    }

    @Test
    void walksThroughItBelowItsTop() {
        GroundPhysics phys = new GroundPhysics(scene(), new Body());
        // at the ground, into the column's side: no wall (inside, climbing: speed held to 0.15)
        MoveState s = run(phys, standing(47.5, -60, 10.5), script(40, F), -90f, false);
        assertTrue(s.x > 51.3, "through: " + s);
        assertEquals(-60.0, s.y, 0.0);
    }

    @Test
    void aBottomPieceHasAFloorInItsBottomEighth() {
        GroundPhysics phys = new GroundPhysics(scene(), new Body());
        // on the arm's top, the shift key: down through its top onto its own bottom eighth, and held there
        MoveState s = run(phys, standing(51.5, -58, 16.5), script(20, S), -90f, false);
        assertEquals(-59.0 + 0.125, s.y, 1e-12);
        assertTrue(s.onGround);
        s = run(phys, s, script(10, 0), -90f, false);
        assertEquals(-59.0 + 0.125, s.y, 1e-12);
    }

    /** KINE_SCAFFOLD_SCRIPTS: the lockstep test's runs through the copy, printed tick by tick (for choosing them). */
    @Test
    void printScripts() {
        String which = System.getenv("KINE_SCAFFOLD_SCRIPTS");
        if (which == null) return;
        GroundPhysics phys = new GroundPhysics(scene(), new Body());
        if (which.equals("sweep")) {
            // the station's way scripted: a run-up of r ticks, the sprint-jump held j ticks, then b ticks of the jump with
            // the back key, then the jump alone
            for (int r = 0; r <= 6; r++)
                for (int j = 6; j <= 14; j++)
                    for (int b = 0; b <= 6; b++) {
                        int[] k = script(r, F | SP, j, F | SP | J, b, B | J, 25, J, 15, 0);
                        MoveState s = run(phys, standing(60.5, -57, 22.5), k, -90f, false);
                        if (s.y > -55.01 && s.onGround)
                            System.out.printf(Locale.ROOT, "sweep r=%d j=%d b=%d end %s%n", r, j, b, s);
                    }
            return;
        }
        for (Object[] r : RUNS) {
            if (!which.equals("all") && !which.contains((String) r[0])) continue;
            System.out.println("run " + r[0]);
            double[] p = (double[]) r[1];
            run(phys, standing(p[0], p[1], p[2]), (int[]) r[3], (float) r[2], true);
        }
    }

    /** The lockstep test's runs (name, start, yaw, keys), kept the same as clienttest ScaffoldPhysicsTest.runs. */
    static final Object[][] RUNS = {
        {"s1_walk_into_side", new double[]{47.5, -60, 10.5}, -90f, script(40, F, 10, 0)},
        {"s1_climb_jump", new double[]{50.5, -60, 10.5}, -90f, script(45, J, 15, 0)},
        {"s1_walk_in_jump", new double[]{47.5, -60, 10.5}, -90f, script(12, F, 40, F | J, 10, 0)},
        {"s1_top_sneak_down", new double[]{50.5, -57, 10.5}, -90f, script(30, S, 10, 0)},
        {"s1_sneak_release", new double[]{50.5, -57, 10.5}, -90f, script(5, S, 15, 0, 4, S, 10, 0)},
        {"s1_top_jump", new double[]{50.5, -57, 10.5}, -90f, script(25, J, 10, 0)},
        {"s1_top_sneak_walk", new double[]{50.5, -57, 10.5}, -90f, script(20, S | F, 10, 0)},
        {"s1_top_walk_off", new double[]{50.5, -57, 10.5}, -90f, script(15, F, 20, 0)},
        {"s1_walk_off_onto_top", new double[]{50.5, -55, 9.5}, 0f, script(6, F, 20, 0)},
        {"s1_inside_mid_sneak", new double[]{50.5, -59, 10.5}, -90f, script(15, S, 10, 0, 20, J, 10, 0)},
        {"s2_under_arm_walk", new double[]{54.5, -60, 16.5}, 90f, script(30, F, 10, 0)},
        {"s2_under_arm_jump", new double[]{51.5, -60, 16.5}, 90f, script(20, J, 10, 0)},
        {"s2_under_arm_sneak", new double[]{51.5, -60, 16.5}, 90f, script(10, S, 10, F | S, 10, 0)},
        {"s2_arm_top_sneak", new double[]{51.5, -58, 16.5}, 90f, script(20, S, 20, 0)},
        {"s2_arm_walk_on", new double[]{50.5, -58, 16.5}, -90f, script(25, F, 10, 0)},
        {"s2_arm_slab_jump", new double[]{51.5, -58.875, 16.5}, -90f, script(15, J, 15, 0)},
        {"s3_way_a", new double[]{60.5, -57, 22.5}, -90f, script(2, F | SP, 7, F | SP | J, 4, B | J, 25, J, 15, 0)},
        {"s3_way_b", new double[]{60.5, -57, 22.5}, -90f, script(1, F | SP, 9, F | SP | J, 4, B | J, 25, J, 15, 0)},
        {"s3_blocked", new double[]{60.5, -57, 22.5}, -90f, script(3, F | SP, 9, F | SP | J, 4, B | J, 25, J, 15, 0)},
        {"s4_flush_walk_sneak", new double[]{54.5, -60, 10.5}, -90f, script(10, F, 10, S | F, 10, 0, 10, J, 10, 0)},
    };
}
