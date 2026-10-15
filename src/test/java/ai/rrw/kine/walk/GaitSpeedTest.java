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
 * The top speed of each gait on flat ground, on the movement copy (for the gait setting, RW 2026-10-14 "add teh gaint":
 * layer 1's estimate must charge the fastest the level allows and never more): W alone, W with jump held, W and
 * sprint, W, sprint and jump held, each on stone, ice and blue ice (the slipperiest, so the fastest on foot); 400 ticks
 * from standing, the speed over the last 200.
 */
public class GaitSpeedTest {
    static final BlockInfo STONE = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
    static final BlockInfo ICE = new BlockInfo(Shape.BLOCK, 0.98F, 1.0F, 1.0F, 0);
    static final BlockInfo BLUE_ICE = new BlockInfo(Shape.BLOCK, 0.989F, 1.0F, 1.0F, 0);

    /** Blocks a second, holding these keys along +z from standing on a floor of this block. */
    static double speed(BlockInfo floor, int keys) {
        BlockGrid g = new BlockGrid(-4, -4, -4, 12, 16, 260);
        for (int x = -2; x <= 2; x++) for (int z = -2; z < 252; z++) g.set(x, 0, z, floor);
        GroundPhysics phys = new GroundPhysics(g, new Body());
        MoveState s = new MoveState();
        s.x = 0.5; s.y = 1.0; s.z = 0.5; s.vy = -0.0784000015258789; s.onGround = true;
        double z0 = 0;
        for (int t = 0; t < 400; t++) {
            if (t == 200) z0 = s.z;
            phys.step(s, keys, 0f);
        }
        return (s.z - z0) / 200 * 20;
    }

    @Test
    void theFastestOfEachGait() {
        int W = MoveState.FORWARD, J = MoveState.JUMP, S = MoveState.SPRINT;
        String[] names = {"walk", "walk, jump held", "sprint", "sprint, jump held"};
        int[] keys = {W, W | J, W | S, W | S | J};
        BlockInfo[] floors = {STONE, ICE, BLUE_ICE};
        String[] fn = {"stone", "ice", "blue ice"};
        double[][] v = new double[4][3];
        for (int i = 0; i < 4; i++)
            for (int f = 0; f < 3; f++) {
                v[i][f] = speed(floors[f], keys[i]);
                System.out.printf(Locale.ROOT, "%-18s on %-8s %.3f m/s (%.3f ticks a block)%n", names[i], fn[f], v[i][f], 20 / v[i][f]);
            }
        // walking: with jump held no faster than walking's best (a jump adds its push only while sprinting), and the
        // estimate's rates (Costs) under the best of each
        double walkBest = 0, sprintBest = 0;
        for (int f = 0; f < 3; f++) { walkBest = Math.max(walkBest, Math.max(v[0][f], v[1][f])); sprintBest = Math.max(sprintBest, v[2][f]); }
        for (int f = 0; f < 3; f++) assertTrue(v[1][f] <= Math.max(v[0][0], Math.max(v[0][1], v[0][2])) + 1e-9,
            "walk-jumping is no faster than walking's best on " + fn[f]);
        assertTrue(20 / Costs.WALK_FASTEST > walkBest, "the Walk estimate's speed is above walking's best");
        assertTrue(20 / Costs.SPRINT_FASTEST > sprintBest, "the Sprint estimate's speed is above sprinting's best");
        assertTrue(v[3][0] > v[2][0], "sprint-jumping is the fastest on stone");
    }
}
