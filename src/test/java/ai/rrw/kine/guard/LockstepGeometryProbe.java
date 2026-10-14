package ai.rrw.kine.guard;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

/** Not a check: the lockstep scenarios' terrain and starts (superflat: ground top at y -60), flown hands off offline,
 *  to aim them before spending client runs. Prints what the guardian does in each. */
class LockstepGeometryProbe {
    interface Box { boolean in(int x, int y, int z); }
    static BlockGrid world(Box solid, Box water, Box leaves) {
        return Worlds.of((x, y, z) -> {
            if (y < -60) return Worlds.STONE;
            if (water != null && water.in(x, y, z)) return Worlds.WATER;
            if (leaves != null && leaves.in(x, y, z)) return Worlds.LEAVES;
            if (solid != null && solid.in(x, y, z)) return Worlds.STONE;
            return BlockInfo.AIR;
        });
    }
    static boolean box(int x, int y, int z, int x0, int y0, int z0, int x1, int y1, int z1) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
    }
    static void fly(String name, BlockGrid g, double x, double y, double z, float yaw, float pitch, int ticks) {
        MoveState s = GuardLoop.gliding(x, y, z, 0, -0.1, 0);
        GuardLoop.Run r = GuardLoop.run(g, s, yaw, pitch, Pilot.HOLD, null, new Guard.Config(), ticks, -1, 0, false);
        System.out.printf("%-8s %s end %.0f %.0f %.0f%n", name, r, r.end.x, r.end.y, r.end.z);
        GuardLoop.Run off = GuardLoop.run(g, s, yaw, pitch, Pilot.HOLD, null, offCfg(), ticks, -1, 0, false);
        System.out.printf("%-8s guardian off: damage %.1f at %d (%s) end %.0f %.0f %.0f%n", name, off.damage, off.damageTick,
            Guard.kindNames(off.kinds), off.end.x, off.end.y, off.end.z);
    }
    static Guard.Config offCfg() { Guard.Config c = new Guard.Config(); c.horizon = 0; return c; }

    @Test
    void probe() {
        // S1 wall across z 200..201 from the ground to 60; glide from rest at y 40, pitch 20
        fly("wall", world((x, y, z) -> box(x, y, z, -40, -60, 200, 40, 60, 201), null, null), 0.5, 40, 0.5, 0f, 20f, 600);
        // S2 dive at 60 from y 60
        fly("dive", world(null, null, null), 0.5, 60, -299.5, 0f, 60f, 600);
        // S3 cliff: a plateau to y 0 from z 400, glide from -20 at pitch 10
        fly("cliff", world((x, y, z) -> box(x, y, z, -40, -60, 400, 40, 0, 440), null, null), 0.5, -20, 250.5, 0f, 10f, 600);
        // S7 roof: a ceiling at y 0 over z 800..1100, glide at -20 pitch 5
        fly("roof", world((x, y, z) -> box(x, y, z, -40, 0, 800, 40, 0, 1100), null, null), 0.5, -20, 800.5, 0f, 5f, 600);
        // S8 arch: pillars x -6 and 6, roof -40..-38, z 1300..1310; glide from -45 at z 1250, pitch 3
        fly("arch", world((x, y, z) -> (box(x, y, z, -6, -60, 1300, -6, -38, 1310) || box(x, y, z, 6, -60, 1300, 6, -38, 1310)
            || box(x, y, z, -6, -40, 1300, 6, -38, 1310)), null, null), 0.5, -45, 1250.5, 0f, 3f, 600);
        // S9 canopy: leaves -45..-42 over x -30..30, z 1600..1700, a hole x -10..10, z 1610..1630; glide from -35 at z 1608
        for (float pitch : new float[]{25f, 35f, 45f})
            fly("canopy" + (int) pitch, world(null, null, (x, y, z) -> box(x, y, z, -30, -45, 1600, 30, -42, 1700) && !box(x, y, z, -10, -45, 1610, 10, -42, 1640)),
                0.5, -32, 1611.5, 0f, pitch, 600);
        // S10 water tank: water -60..-41 over x -20..20, z 2000..2060; dive from 0 at z 1990, pitch 60
        fly("water", world((x, y, z) -> box(x, y, z, -21, -60, 1999, 21, -41, 2061) && !box(x, y, z, -20, -60, 2000, 20, -41, 2060),
            (x, y, z) -> box(x, y, z, -20, -60, 2000, 20, -41, 2060), null), 0.5, 0, 1990.5, 0f, 60f, 600);
    }
}
