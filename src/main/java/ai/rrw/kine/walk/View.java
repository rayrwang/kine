package ai.rrw.kine.walk;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * A snapshot of the walkability table for one search: the chunks as they were when it was taken (each entry is
 * immutable, so the snapshot is a map of references). Answers what the search and the sweep ask: a column's floors,
 * and the kind of block at any position, exact inside a floor's window, air inside a floor's free space, a hurting
 * stand-in at a hazard's height, a plain solid block anywhere else, and unknown where no chunk is loaded or built.
 */
public final class View {
    private final Long2ObjectOpenHashMap<ChunkFloors> chunks;
    private int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
    private ChunkFloors last;

    public View(Long2ObjectMap<ChunkFloors> chunks) {
        this.chunks = new Long2ObjectOpenHashMap<>(chunks);
    }

    /** Another snapshot of the same chunks, for another thread (the chunk cache below is one search's). */
    public View copy() {
        return new View(chunks);
    }

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** The chunk holding block column (x, z), or null when not loaded or not built. Not thread safe (one search each). */
    public ChunkFloors chunk(int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (cx == lastCx && cz == lastCz) return last;
        lastCx = cx; lastCz = cz;
        last = chunks.get(key(cx, cz));
        return last;
    }

    public ChunkFloors chunkAt(int cx, int cz) {
        return chunks.get(key(cx, cz));
    }

    public boolean known(int x, int z) {
        return chunk(x, z) != null;
    }

    // ---------------------------------------------------------------- nodes: (x, z, floor ordinal) packed in a long

    /** No node. (Node keys can be negative: x's sign is the top bit.) */
    public static final long NONE = Long.MAX_VALUE;

    public static long node(int x, int z, int i) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (i & 0xFFF);
    }

    public static int nodeX(long n) { return (int) (n >> 38) << 6 >> 6; }
    public static int nodeZ(long n) { return (int) ((n >> 12) & 0x3FFFFFF) << 6 >> 6; }
    public static int nodeI(long n) { return (int) (n & 0xFFF); }

    /** The per-floor index of a node in its chunk (the chunk from {@link #chunk}), or -1. */
    public int index(long n) {
        int x = nodeX(n), z = nodeZ(n);
        ChunkFloors c = chunk(x, z);
        if (c == null) return -1;
        int i = nodeI(n);
        if (i >= c.count(x & 15, z & 15)) return -1;
        return c.index(x & 15, z & 15, i);
    }

    /** The node of the floor in column (x, z) nearest to feet height y: the highest floor at or below y + 0.6 within 2.5
     *  below (a standing, swimming or climbing player), else the nearest; NONE if none. */
    public long nodeAt(int x, double y, int z) {
        ChunkFloors c = chunk(x, z);
        if (c == null) return NONE;
        int lx = x & 15, lz = z & 15, n = c.count(lx, lz);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int idx = c.index(lx, lz, i);
            double d = y - c.h(idx);
            double score = d >= -0.6 && d <= 2.5 ? Math.abs(d) * 0.1 : Math.abs(d) + 10;
            if (score < bestD) { bestD = score; best = i; }
        }
        return best < 0 || bestD > 13 ? NONE : node(x, z, best);
    }

    // ---------------------------------------------------------------- blocks, for the sweep

    /** The kind of block at (x, y, z) as far as the table knows it. */
    public Kind block(int x, int y, int z) {
        ChunkFloors c = chunk(x, z);
        if (c == null) return Palette.UNKNOWN;
        int col = (x & 15) + 16 * (z & 15);
        int s = c.start[col], e = c.start[col + 1];
        for (int i = s; i < e; i++) {
            int k = y - c.fb[i];
            if (k >= 0 && k < ChunkFloors.WINDOW) return Palette.get(c.window[i * ChunkFloors.WINDOW + k]);
        }
        for (int i = c.hazStart[col]; i < c.hazStart[col + 1]; i++) if (c.haz[i] == y) return Palette.HAZARD_SOLID;
        for (int i = s; i < e; i++) {
            if (c.type[i] == ChunkFloors.CLIMB) continue;
            float lo = c.cellTop[i], hi = c.clearTop(i);
            if (y >= lo - 1e-5 && y + 1 <= hi + 1e-5) return Palette.AIR;
        }
        if (y >= c.openFrom[col] - 1e-5) return Palette.AIR;   // over the highest floor and hazard: open sky
        return Palette.SOLID;
    }

    /** The kind of block at (x, y, z) where the table really knows it: inside a floor's window, a hazard's height, a
     *  floor's free space, open sky or a run of tall blocks (Palette.SOLID for one covering its cell's top, else the
     *  block); null where block() would only assume a solid block (between a floor's free space,
     *  capped at ChunkFloors.MAX_CLEAR, and whatever is above: on a tower a level's air more than 24 over the one below). */
    public Kind blockKnown(int x, int y, int z) {
        ChunkFloors c = chunk(x, z);
        if (c == null) return null;
        Kind k = block(x, y, z);
        if (k != Palette.SOLID) return k;
        // block() ends with SOLID for "anything else": known only when a floor's window says so
        int col = (x & 15) + 16 * (z & 15);
        for (int i = c.start[col]; i < c.start[col + 1]; i++) {
            int w = y - c.fb[i];
            if (w >= 0 && w < ChunkFloors.WINDOW) return k;
        }
        // inside a run of tall blocks (ColumnScan.walls): a wall's inside under its top floor, SOLID for a block covering
        // its cell's top (as block() assumed), else the block itself (a fence barring a window)
        for (int i = c.wallStart[col]; i < c.wallStart[col + 1]; i += 3)
            if (y >= c.wall[i] && y <= c.wall[i + 1]) return c.wall[i + 2] == ColumnScan.WALL_RUN ? k : Palette.get(c.wall[i + 2]);
        return null;
    }

    /** Whether the column (x, z) has a hazard height in [y0, y1] (block heights, inclusive). */
    public boolean hazardIn(int x, int z, int y0, int y1) {
        ChunkFloors c = chunk(x, z);
        if (c == null) return false;
        int col = (x & 15) + 16 * (z & 15);
        for (int i = c.hazStart[col]; i < c.hazStart[col + 1]; i++) if (c.haz[i] >= y0 && c.haz[i] <= y1) return true;
        return false;
    }
}
