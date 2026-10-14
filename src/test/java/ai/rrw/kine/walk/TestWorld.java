package ai.rrw.kine.walk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A made-up world for the pathfinder's unit tests: block kinds in a box of chunks, heights 0 to 48, turned into the
 * walkability table the same way the game's chunks are (ColumnScan).
 */
final class TestWorld {
    static final int MIN_Y = 0, MAX_Y = 48;

    static final Kind STONE = Palette.add("stone", List.of(box(0, 0, 0, 1, 1, 1)), 0.6F, 1.0F, 0);
    static final Kind SLAB = Palette.add("slab", List.of(box(0, 0, 0, 1, 0.5, 1)), 0.6F, 1.0F, 0);
    /** Stairs rising toward +x: the bottom half, and the top half's east half. */
    static final Kind STAIR_EAST = Palette.add("stair_east", List.of(box(0, 0, 0, 1, 0.5, 1), box(0.5, 0.5, 0, 1, 1, 1)), 0.6F, 1.0F, 0);
    static final Kind FENCE = Palette.add("fence", List.of(box(0.375, 0, 0.375, 0.625, 1.5, 0.625)), 0.6F, 1.0F, 0);
    /** A closed door on the cell's west side (one block of it). */
    static final Kind DOOR_WEST = Palette.add("door_west", List.of(box(0, 0, 0, 0.1875, 1, 1)), 0.6F, 1.0F, Kind.DOOR);
    static final Kind WATER = Palette.add("water", List.of(), 0.6F, 1.0F, Kind.WATER);
    static final Kind LAVA = Palette.add("lava", List.of(), 0.6F, 1.0F, Kind.LAVA);
    static final Kind CACTUS = Palette.add("cactus", List.of(box(1 / 16.0, 0, 1 / 16.0, 15 / 16.0, 15 / 16.0, 15 / 16.0)), 0.6F, 1.0F, Kind.HAZARD);
    /** A ladder on the cell's west side. */
    static final Kind LADDER_WEST = Palette.add("ladder_west", List.of(box(0, 0, 0, 0.1875, 1, 1)), 0.6F, 1.0F, Kind.CLIMB);
    /** A ladder on the cell's east side (on a wall to the east). */
    static final Kind LADDER_EAST = Palette.add("ladder_east", List.of(box(0.8125, 0, 0, 1, 1, 1)), 0.6F, 1.0F, Kind.CLIMB);
    static final Kind SOUL_SAND = Palette.add("soul_sand", List.of(box(0, 0, 0, 1, 0.875, 1)), 0.6F, 0.4F, Kind.SLOW);
    static final Kind MAGMA = Palette.add("magma", List.of(box(0, 0, 0, 1, 1, 1)), 0.6F, 1.0F, Kind.FLOOR_HAZARD);
    static final Kind ICE = Palette.add("ice", List.of(box(0, 0, 0, 1, 1, 1)), 0.98F, 1.0F, 0);
    /** A vine: climbable, no collision. */
    static final Kind VINE = Palette.add("vine", List.of(), 0.6F, 1.0F, Kind.CLIMB);

    static double[] box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new double[]{x0, y0, z0, x1, y1, z1};
    }

    final int cx0, cz0, cx1, cz1;   // chunks, inclusive
    private final Map<Long, int[]> blocks = new HashMap<>();   // per column (x, z): ids bottom up

    TestWorld(int cx0, int cz0, int cx1, int cz1) {
        this.cx0 = cx0; this.cz0 = cz0; this.cx1 = cx1; this.cz1 = cz1;
    }

    /** A world of the chunks covering blocks x0..x1, z0..z1. */
    static TestWorld blocks(int x0, int z0, int x1, int z1) {
        return new TestWorld(x0 >> 4, z0 >> 4, x1 >> 4, z1 >> 4);
    }

    private int[] col(int x, int z) {
        return blocks.computeIfAbsent(View.key(x, z), k -> new int[MAX_Y - MIN_Y]);
    }

    TestWorld set(int x, int y, int z, Kind k) {
        col(x, z)[y - MIN_Y] = k.id;
        return this;
    }

    TestWorld fill(int x0, int y0, int z0, int x1, int y1, int z1, Kind k) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) set(x, y, z, k);
        return this;
    }

    /** Stone floor at y over the whole world (feet at y + 1). */
    TestWorld floor(int y) {
        return fill(cx0 * 16, y, cz0 * 16, cx1 * 16 + 15, y, cz1 * 16 + 15, STONE);
    }

    Kind get(int x, int y, int z) {
        int[] c = blocks.get(View.key(x, z));
        return c == null || y < MIN_Y || y >= MAX_Y ? Palette.AIR : Palette.get(c[y - MIN_Y]);
    }

    ChunkFloors chunk(int cx, int cz) {
        return ColumnScan.chunk(cx, cz, new ColumnScan.Source() {
            public int minY() { return MIN_Y; }
            public int maxY() { return MAX_Y; }
            public void column(int lx, int lz, int[] ids) {
                int[] c = blocks.get(View.key(cx * 16 + lx, cz * 16 + lz));
                if (c == null) java.util.Arrays.fill(ids, Palette.AIR.id);
                else System.arraycopy(c, 0, ids, 0, ids.length);
            }
        });
    }

    View view() {
        Long2ObjectOpenHashMap<ChunkFloors> m = new Long2ObjectOpenHashMap<>();
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++) m.put(View.key(cx, cz), chunk(cx, cz));
        return new View(m);
    }
}
