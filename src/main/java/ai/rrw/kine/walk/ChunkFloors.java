package ai.rrw.kine.walk;

import java.util.ArrayList;
import java.util.List;

/**
 * The walkability table's entry for one chunk: for each of its 256 columns, the places a player can be in it, lowest
 * first ("floors"), and the heights of the blocks in it that hurt or must not be walked through. Immutable once built,
 * so a search on another thread can read it while the game changes; a changed chunk gets a new one. No Minecraft class
 * and nothing tied to the live world, so a saved copy of a chunk seen earlier can stand in for a loaded one (planning
 * beyond render distance, for later).
 *
 * <p>Three types of floor:
 * <ul>
 *   <li>GROUND: standing on a block's collision top inside the column's middle (the footprint of a player standing at
 *       its centre), with at least 1.8 of free height above (the player's height), feet not under water;</li>
 *   <li>SWIM: the top of water at least two deep (or with nothing to stand on under it), with air above for the head;
 *       its height is the top of the highest water block;</li>
 *   <li>CLIMB: inside a ladder, vine or other climbable block with room for the body, not standing on anything.</li>
 * </ul>
 * Per floor: its height (the feet), the free height above it inside the footprint ("head") and over the whole cell
 * ("cellTop" and "cellClear": the cell is free of collisions from cellTop to cellTop + cellClear), flags, and a window
 * of four block kinds from the floor block up (from fb), so a sweep of the player's box near the floor sees the exact
 * shapes (doors, fences, slabs).
 */
public final class ChunkFloors {
    public static final int GROUND = 0, SWIM = 1, CLIMB = 2;

    /** Feet in water (wading). */
    public static final int WADE = 1;
    /** The feet block is climbable: a ladder starts here. */
    public static final int CLIMB_FEET = 2;
    /** The floor block slows walking (soul sand, honey). */
    public static final int SLOW = 4;
    /** A plain floor: the floor block's top covers the whole cell at the floor's height, and the whole cell is as free
     *  above it as the footprint is. Links between plain floors need no sweep. */
    public static final int SIMPLE = 8;

    public static final int WINDOW = 4;
    /** Free heights are measured up to this far above a floor (a fall into water can be longer than a safe fall). */
    public static final float MAX_CLEAR = 24.0F;

    public final int cx, cz;
    /** A number that grows with every chunk built, to tell a rebuilt chunk from the one a path was planned on. */
    public final long stamp;
    final int[] start;           // floors of column c (x + 16 z, local) are start[c] .. start[c + 1] - 1
    final byte[] type;
    final short[] flags;
    final float[] h, head, cellTop, cellClear;
    final int[] fb;
    final int[] window;          // WINDOW per floor: kind ids from fb up
    final int[] hazStart;        // hazard heights of column c are haz[hazStart[c] .. hazStart[c + 1] - 1]
    final int[] haz;
    /** Per column, the stamp of the build in which it last changed (its floors or hazards), so a route can tell a
     *  change that matters to it from one elsewhere in the chunk (grass turning to dirt under a wall). Set once by the
     *  table before the entry is published. */
    long[] columnStamps;

    private ChunkFloors(int cx, int cz, long stamp, int[] start, List<Floor> fl, int[] hazStart, int[] haz) {
        this.cx = cx; this.cz = cz; this.stamp = stamp;
        this.start = start;
        int n = fl.size();
        type = new byte[n]; flags = new short[n];
        h = new float[n]; head = new float[n]; cellTop = new float[n]; cellClear = new float[n];
        fb = new int[n]; window = new int[n * WINDOW];
        for (int i = 0; i < n; i++) {
            Floor f = fl.get(i);
            type[i] = (byte) f.type; flags[i] = (short) f.flags;
            h[i] = f.h; head[i] = f.head; cellTop[i] = f.cellTop; cellClear[i] = f.cellClear;
            fb[i] = f.fb;
            System.arraycopy(f.window, 0, window, i * WINDOW, WINDOW);
        }
        this.hazStart = hazStart;
        this.haz = haz;
    }

    /** How many floors the column (local lx, lz) has. */
    public int count(int lx, int lz) {
        int c = lx + 16 * lz;
        return start[c + 1] - start[c];
    }

    /** The index of the column's i-th floor in the per-floor arrays. */
    public int index(int lx, int lz, int i) {
        return start[lx + 16 * lz] + i;
    }

    public int floorCount() {
        return type.length;
    }

    public int type(int idx) { return type[idx]; }
    public int flags(int idx) { return flags[idx]; }
    public float h(int idx) { return h[idx]; }
    public float head(int idx) { return head[idx]; }
    public float cellTop(int idx) { return cellTop[idx]; }
    public float cellClear(int idx) { return cellClear[idx]; }
    public int fb(int idx) { return fb[idx]; }
    public int window(int idx, int k) { return window[idx * WINDOW + k]; }

    /** The stamp of the build in which column (lx, lz) last changed. */
    public long columnStamp(int lx, int lz) {
        return columnStamps == null ? stamp : columnStamps[lx + 16 * lz];
    }

    /** Set the column stamps from the entry this one replaces: a column whose floors and hazards are the same keeps its
     *  stamp, a changed one takes this build's. */
    void stampColumnsFrom(ChunkFloors old) {
        long[] cs = new long[256];
        for (int c = 0; c < 256; c++) cs[c] = old != null && sameColumn(old, this, c) ? old.columnStamp(c & 15, c >> 4) : stamp;
        columnStamps = cs;
    }

    private static boolean sameColumn(ChunkFloors a, ChunkFloors b, int c) {
        int as = a.start[c], ae = a.start[c + 1], bs = b.start[c], be = b.start[c + 1];
        if (ae - as != be - bs) return false;
        for (int i = 0; i < ae - as; i++) {
            int x = as + i, y = bs + i;
            if (a.type[x] != b.type[y] || a.flags[x] != b.flags[y] || a.h[x] != b.h[y] || a.head[x] != b.head[y]
                || a.cellTop[x] != b.cellTop[y] || a.cellClear[x] != b.cellClear[y]) return false;
        }
        int ha = a.hazStart[c + 1] - a.hazStart[c], hb = b.hazStart[c + 1] - b.hazStart[c];
        if (ha != hb) return false;
        for (int i = 0; i < ha; i++) if (a.haz[a.hazStart[c] + i] != b.haz[b.hazStart[c] + i]) return false;
        return true;
    }

    /** Approximate memory use, bytes. */
    public long bytes() {
        return 64L + start.length * 4L + type.length * (1L + 2 + 16 + 4 + 4L * WINDOW) + hazStart.length * 4L + haz.length * 4L;
    }

    /** One floor while building. */
    static final class Floor {
        int type, flags, fb;
        float h, head, cellTop, cellClear;
        final int[] window = new int[WINDOW];
    }

    /** Collects the columns of one chunk in order (column 0 first) and makes the immutable entry. */
    static final class Builder {
        private final int cx, cz;
        private final int[] start = new int[257];
        private final int[] hazStart = new int[257];
        private final List<Floor> floors = new ArrayList<>();
        private int[] haz = new int[16];
        private int nHaz = 0, column = 0;

        Builder(int cx, int cz) { this.cx = cx; this.cz = cz; }

        /** The next column's floors (in height order) and hazard heights. */
        void column(List<Floor> fl, int[] hazY, int nHazY) {
            start[column] = floors.size();
            hazStart[column] = nHaz;
            floors.addAll(fl);
            if (nHaz + nHazY > haz.length) haz = java.util.Arrays.copyOf(haz, Math.max(haz.length * 2, nHaz + nHazY));
            System.arraycopy(hazY, 0, haz, nHaz, nHazY);
            nHaz += nHazY;
            column++;
        }

        ChunkFloors build(long stamp) {
            if (column != 256) throw new IllegalStateException("columns: " + column);
            start[256] = floors.size();
            hazStart[256] = nHaz;
            return new ChunkFloors(cx, cz, stamp, start, floors, hazStart, java.util.Arrays.copyOf(haz, nHaz));
        }
    }
}
