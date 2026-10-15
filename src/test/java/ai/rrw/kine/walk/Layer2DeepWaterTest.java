package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.search.RecedingSearch;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A walk started on the bottom of deep water (a lake 20 and 30 deep, the far bank 42 on), layer 2 closed loop with
 * the copy's blocks as Layer2 reads them from the game: 16 round, 8 down and up from the feet as far as Layer2.gridUp
 * gives, read again as Layer2 does (moved 4 across or 2 up or down, or 20 ticks old); each tick's move made in the
 * whole world. With the old reach (6 up) the copy's blocks ended inside the water and read as a solid roof there:
 * no way to the air in sight, every rise of more than 5 blocks bumping into it, and the field ends 12 under the top,
 * so layer 2 swam along the bottom to the far wall and drowned there. Reaching past the water's top it comes up.
 */
public class Layer2DeepWaterTest {
    /** The blocks within r across, down below and up above the feet, the rest unknown (WorldSnapshot.around). */
    static BlockGrid around(BlockGrid full, MoveState s, int r, int down, int up) {
        int bx = (int) Math.floor(s.x), by = (int) Math.floor(s.y), bz = (int) Math.floor(s.z);
        BlockGrid g = new BlockGrid(bx - r, by - down, bz - r, 2 * r + 1, down + up + 1, 2 * r + 1);
        for (int x = bx - r; x <= bx + r; x++)
            for (int y = by - down; y <= by + up; y++)
                for (int z = bz - r; z <= bz + r; z++) {
                    BlockInfo b = full.inside(x, y, z) ? full.get(x, y, z) : BlockInfo.AIR;
                    if (b != BlockInfo.AIR) g.set(x, y, z, b);
                }
        return g;
    }

    /** Layer2.gridUp on the copy's blocks: in water two past the first block over the feet that isn't water. */
    static int gridUp(BlockGrid full, MoveState s) {
        if (!s.inWater) return Layer2.GRID_UP;
        int bx = (int) Math.floor(s.x), by = (int) Math.floor(s.y), bz = (int) Math.floor(s.z);
        for (int k = 1; k <= Layer2.GRID_MAX_UP; k++)
            if (full.get(bx, by + k, bz).fluid != BlockInfo.WATER_FLUID) return Math.max(Layer2.GRID_UP, Math.min(Layer2.GRID_MAX_UP, k + 2));
        return Layer2.GRID_MAX_UP;
    }

    record Result(boolean arrived, int ticks, double drowned, double minAir) {}

    static Result walk(int depth, boolean oldReach) {
        // stone to y 30 (feet 31), a lake x 4..44, z -10..10, depth deep, its top block at 30
        TestWorld w = TestWorld.blocks(-8, -16, 63, 15);
        w.fill(-8, 0, -16, 63, 30, 15, TestWorld.STONE);
        w.fill(4, 31 - depth, -10, 44, 30, 10, TestWorld.WATER);
        View v = w.view();
        Costs costs = Costs.layer2();
        int gx = 52, gz = 0;
        MoveState s = new MoveState();
        s.x = 10.5; s.y = 31 - depth; s.z = 0.5; s.inWater = true; s.eyesInWater = true; s.onGround = true;
        long start = View.NONE;   // the walk starts from the water's top above (Walk.startNode)
        for (int dy = 1; dy <= 40 && start == View.NONE; dy++) start = v.nodeAt(10, s.y + dy, 0);
        assertNotEquals(View.NONE, start);
        PathSearch.Result r = new PathSearch(v, costs).gaps(true).run(start, gx, gz, 1.0, Long.MAX_VALUE, 2_000_000, false);
        assertTrue(r.found);
        Route route = new Tighten(v, costs, 0.2, true).route(r, gx, gz);
        Field f = Field.build(v, costs, true, true, false, 1, r.path, route, s.x, s.z, gx, gz, Double.NaN, true);
        BlockGrid full = Layer2Sim.grid(w);
        Body body = new Body();
        GroundPhysics real = new GroundPhysics(full, body);
        RecedingSearch.Effort effort = new RecedingSearch.Effort();
        effort.beam = 4; effort.depth = 5; effort.hold = 4; effort.draws = 1; effort.budgetNanos = 2_000_000_000L;
        float sentYaw = -90f, sentPitch = 0f;
        int[] prev = null;
        int up = oldReach ? Layer2.GRID_UP : gridUp(full, s);
        BlockGrid g = around(full, s, Layer2.GRID_R, Layer2.GRID_DOWN, up);
        double gy = s.y, gxx = s.x, gzz = s.z, minAir = s.air;
        int gt = 0;
        for (int t = 0; t < 900; t++) {
            int want = oldReach ? Layer2.GRID_UP : gridUp(full, s);
            if (Math.hypot(s.x - gxx, s.z - gzz) > Layer2.GRID_R / 4.0 || Math.abs(s.y - gy) > 2 || t - gt >= Layer2.GRID_AGE || want > up) {
                up = want;
                g = around(full, s, Layer2.GRID_R, Layer2.GRID_DOWN, up);
                gy = s.y; gxx = s.x; gzz = s.z; gt = t;
            }
            RunModel model = new RunModel(new GroundPhysics(g, body), f, gx, gz, 0f, costs.maxFall);
            RecedingSearch<RunWorld> rs = new RecedingSearch<>(model);
            RunWorld root = new RunWorld(s.copy(), sentYaw, sentPitch);
            root.takeoffY = s.y;
            RecedingSearch.Effort e = effort.copy();
            if (s.inWater) e.hold = RunModel.WATER_HOLD;
            RecedingSearch.Result res = RunModel.plan(model, rs, root, prev, 1, e, t);
            prev = res.plan();
            RunWorld ww = root.copy();
            model.step(ww, res.plan()[0], 0, null);   // the keys and the turn, from the copy
            real.step(s, ww.lastKeys, ww.sentYaw, ww.sentPitch);   // the move, in the whole world
            sentYaw = ww.sentYaw; sentPitch = ww.sentPitch;
            minAir = Math.min(minAir, s.air);
            double dx = gx + 0.5 - s.x, dz = gz + 0.5 - s.z;
            if (dx * dx + dz * dz <= 0.25 && (s.onGround || s.inWater)) return new Result(true, t + 1, s.drownDamage, minAir);
        }
        return new Result(false, 900, s.drownDamage, minAir);
    }

    @Test
    void comesUpFromTheBottomOfDeepWater() {
        for (int depth : new int[]{20, 30}) {
            Result old = walk(depth, true), now = walk(depth, false);
            System.out.printf(Locale.ROOT, "deep start %d: blocks 6 up: arrived=%b ticks=%d drowned=%.0f minAir=%.0f | to the top: arrived=%b ticks=%d drowned=%.0f minAir=%.0f%n",
                depth, old.arrived(), old.ticks(), old.drowned(), old.minAir(), now.arrived(), now.ticks(), now.drowned(), now.minAir());
            assertTrue(old.drowned() > 0, depth + " deep, the old reach: drowns on the bottom");
            assertTrue(now.arrived(), depth + " deep: arrives");
            assertEquals(0.0, now.drowned(), depth + " deep: no drowning");
        }
    }
}
