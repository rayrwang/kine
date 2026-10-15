package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.Shape;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The course's scaffolding_jump (station 96) in the movement copy (2026-10-15): from the take-off stone, a sprint-jump
 * into the side of a column of three scaffolding pieces four blocks on, the climb with the jump key held, standing on
 * its top two blocks up. The jump search (Recipe.search, as the station's recipe and layer 2's search call it) must
 * find it, its inputs must end standing on the top, and a landing elsewhere (inside the column, on a piece under the
 * top) must not count.
 */
public class ScaffoldJumpTest {
    static final int FLOOR = 20;
    static final BlockInfo STONE = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
    static final BlockInfo SCAFFOLD = new BlockInfo(Shape.SCAFFOLD_STABLE, 0.6F, 1.0F, 1.0F,
        BlockInfo.SPECIAL | BlockInfo.CLIMBABLE | BlockInfo.SCAFFOLD | BlockInfo.LARGE);

    /** The catalogue's layout, its take-off at (0, FLOOR - 1, 0) (feet at FLOOR), over the void. */
    static BlockGrid station() {
        BlockGrid g = new BlockGrid(-20, 0, -12, 40, 40, 25);
        g.set(0, FLOOR - 1, 0, STONE);
        g.set(4, FLOOR - 2, 0, STONE);
        for (int dy = 0; dy <= 2; dy++) g.set(4, FLOOR - 1 + dy, 0, SCAFFOLD);
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

    @Test
    void theJumpSearchFindsTheClimbOntoTheTop() {
        GroundPhysics phys = new GroundPhysics(station(), new Body());
        int lx = 4, ly = FLOOR + 1, lz = 0;
        MoveState s = standing(0.5, FLOOR, 0.5);
        float yaw = Recipe.yawTo(s.x, s.z, lx + 0.5, lz + 0.5);
        long t0 = System.nanoTime();
        Recipe r = Recipe.search(phys, s, yaw, lx, ly, lz, yaw - 90f, yaw + 90f, 6f, 8, 8, 60);
        System.out.printf(Locale.ROOT, "scaffolding_jump: found %b ways %d of %d how \"%s\" ticks %d end %.4f %.4f %.4f in %.0f ms%n",
            r.found(), r.ways, r.tried, r.how, r.ticks, r.endX, r.endY, r.endZ, (System.nanoTime() - t0) / 1e6);
        assertTrue(r.found(), "a way");
        // its inputs from the start: standing on the top, in the column, keys up after it holding there
        MoveState m = Recipe.after(phys, s, r);
        assertTrue(m.onGround, "on the ground: " + m);
        assertEquals(ly + 1.0, m.y, 1e-9, "on the top");
        assertTrue(m.x + 0.3 > lx && m.x - 0.3 < lx + 1 && m.z + 0.3 > lz && m.z - 0.3 < lz + 1, "over the column: " + m);
        assertTrue(r.stillLands(phys, s, 0, lx, ly, lz));
        for (int t = 0; t < 20; t++) phys.step(m, 0, r.yaw[r.ticks - 1]);
        assertEquals(ly + 1.0, m.y, 1e-9, "still on the top: " + m);
        // the jump key held into the column (the climb) is part of it
        int held = 0;
        for (int k : r.keys) if ((k & MoveState.JUMP) != 0) held++;
        assertTrue(held > 5, "climbing ticks: " + held);
    }

    /** Scaffolding as the walk's table has it (McKinds: no longer avoided; its shape for no one, SHAPE_STABLE's
     *  boxes), with the copy's flags. */
    static final Kind SCAFFOLD_KIND = Palette.add("test_scaffolding", SCAFFOLD, java.util.List.of(
        new double[]{0, 0, 0, 0.125, 1, 0.125}, new double[]{0, 0, 0.875, 0.125, 1, 1}, new double[]{0.875, 0, 0, 1, 1, 0.125},
        new double[]{0.875, 0, 0.875, 1, 1, 1}, new double[]{0, 0.875, 0.125, 1, 1, 0.875}, new double[]{0.125, 0.875, 0, 0.875, 1, 0.125},
        new double[]{0.125, 0.875, 0.875, 0.875, 1, 1}), 0, Float.NaN);

    static TestWorld stationWorld() {
        TestWorld w = TestWorld.blocks(-48, -16, 31, 31);
        w.set(0, FLOOR - 1, 0, TestWorld.STONE);
        w.set(4, FLOOR - 2, 0, TestWorld.STONE);
        for (int dy = 0; dy <= 2; dy++) w.set(4, FLOOR - 1 + dy, 0, SCAFFOLD_KIND);
        return w;
    }

    /** The walk in the copy's walk simulation (Layer2Sim: layer 1 and layer 2, and layer 2 alone), Parkour on. */
    @Test
    void theWalkSimulationMakesIt() {
        for (boolean alone : new boolean[]{false, true}) {
            Layer2Sim sim = new Layer2Sim(stationWorld());
            sim.parkour = true;
            sim.direct = true;
            sim.forceDirect = alone;
            sim.goalY = FLOOR + 2;
            sim.arrivalY = true;
            float yaw = Recipe.yawTo(0.5, 0.5, 4.5, 0.5);
            Layer2Sim.Outcome o = sim.walk(Layer2Sim.standing(0.5, FLOOR, 0.5), yaw, 4, 0, 300);
            double[] q = o.path().isEmpty() ? new double[]{0, 0, 0, 0} : o.path().get(o.path().size() - 1);
            System.out.printf(Locale.ROOT, "walk sim %s: arrived %b ticks %d recipes %d why \"%s\" end %.3f %.3f %.3f ground %b%n",
                alone ? "layer 2 alone" : "layers 1 and 2", o.arrived(), o.ticks(), sim.recipesFound, o.why(), q[0], q[1], q[2],
                (((int) q[3]) & 4) != 0);
            assertTrue(o.arrived(), "arrived");
            assertEquals(FLOOR + 2.0, q[1], 1e-9, "on the top");
            assertTrue((((int) q[3]) & 4) != 0, "standing");
        }
    }

    @Test
    void standingInsideTheColumnIsNoLanding() {
        // a landing on the piece under the top (inside the top piece) is not the top: the same search toward the
        // top with no climb would end there; a ladder-style hold there sinks in scaffolding
        GroundPhysics phys = new GroundPhysics(station(), new Body());
        MoveState in = standing(4.5, FLOOR + 1, 0.5);   // on the middle piece, the feet in the top piece
        assertTrue(phys.onClimbable(in));
        for (int t = 0; t < 10; t++) phys.step(in, 0, -90f);
        assertEquals(FLOOR + 1.0, in.y, 1e-9, "stands on the middle piece's top");
        assertFalse(Recipe.holdsOn(phys, in, 4, FLOOR + 1, 0) && !in.onGround);
    }
}
