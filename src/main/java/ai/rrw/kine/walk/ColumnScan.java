package ai.rrw.kine.walk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds a chunk's {@link ChunkFloors} from the kinds of its blocks, one column at a time, bottom to top. A GROUND
 * floor is the top of a block's collision inside the footprint (the middle 0.6 by 0.6 of the column) with 1.8 of free
 * height above it in the footprint, its block safe to stand on and the head out of water; a SWIM floor is the top of
 * water the player can't stand in; a CLIMB floor is a climbable block with room for the body and no floor at it.
 * What ends a free height: a collision box inside the footprint (for "head") or the cell (for "cellClear"), or a block
 * the player must not enter (lava, hazards, cobwebs and the like). Pure computation over kind ids, so it runs on a
 * worker thread from copies of the chunk's sections, and in unit tests from made-up columns.
 */
public final class ColumnScan {
    private ColumnScan() {}

    private static final AtomicLong STAMPS = new AtomicLong();

    /** The kinds of a chunk's blocks: local x and z 0 to 15, y from minY (inclusive) to maxY (exclusive). */
    public interface Source {
        int minY();
        int maxY();
        /** Fill ids[0 .. maxY - minY) with the kind ids of column (lx, lz), bottom up. */
        void column(int lx, int lz, int[] ids);
    }

    /** Scan every column of the chunk. */
    public static ChunkFloors chunk(int cx, int cz, Source src) {
        int minY = src.minY(), height = src.maxY() - src.minY();
        int[] ids = new int[height];
        ChunkFloors.Builder b = new ChunkFloors.Builder(cx, cz);
        List<ChunkFloors.Floor> fl = new ArrayList<>();
        int[] haz = new int[height];
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                // column order must be x + 16 z: the builder takes columns 0, 1, 2 ... in that order
                src.column(lx, lz, ids);
                fl.clear();
                int nHaz = column(ids, minY, fl, haz);
                b.column(fl, haz, nHaz);
            }
        return b.build(STAMPS.incrementAndGet());
    }

    /** One column's floors, in height order, into `out`; its hazard heights into `haz`; returns how many hazards. */
    static int column(int[] ids, int minY, List<ChunkFloors.Floor> out, int[] haz) {
        int n = ids.length, nHaz = 0;
        for (int yi = 0; yi < n; yi++) {
            Kind k = Palette.get(ids[yi]);
            int y = minY + yi;
            if (k.blocksBody()) haz[nHaz++] = y;

            // GROUND: the top of this block's collision inside the footprint
            if (!Float.isNaN(k.footTop) && !k.blocksBody() && !k.is(Kind.FLOOR_HAZARD)) {
                float h = y + k.footTop;
                float ceil = ceiling(ids, minY, yi + 1, h, true);
                float head = Math.min(ceil - h, ChunkFloors.MAX_CLEAR);
                if (head >= 1.8F - 1e-5F) {
                    int fyi = (int) Math.floor(h + 1e-4) - minY;   // the block the feet are in
                    Kind feet = fyi < n ? Palette.get(ids[fyi]) : Palette.AIR;
                    Kind headK = fyi + 1 < n ? Palette.get(ids[fyi + 1]) : Palette.AIR;
                    if (!headK.is(Kind.WATER)) {   // head under water: the SWIM floor at the top serves
                        ChunkFloors.Floor f = new ChunkFloors.Floor();
                        f.type = ChunkFloors.GROUND;
                        f.h = h;
                        f.head = head;
                        f.fb = y;
                        f.cellTop = y + k.cellTop;
                        f.cellClear = Math.min(ceiling(ids, minY, yi + 1, f.cellTop, false) - f.cellTop, ChunkFloors.MAX_CLEAR);
                        int fl = 0;
                        if (feet.is(Kind.WATER) || k.is(Kind.WATER)) fl |= ChunkFloors.WADE;
                        if (feet.is(Kind.CLIMB)) fl |= ChunkFloors.CLIMB_FEET;
                        if (k.is(Kind.SLOW) || feet.is(Kind.SLOW)) fl |= ChunkFloors.SLOW;
                        if (k.topFull && k.footTop == k.cellTop && f.cellClear >= f.head - 1e-5F)
                            fl |= feet.is(Kind.CLIMB) ? (feet.empty() ? ChunkFloors.CLIMB_PLAIN : 0) : ChunkFloors.SIMPLE;
                        f.flags = fl;
                        window(ids, yi, f.window);
                        out.add(f);
                    }
                }
            }

            // SWIM: the top water block, where no GROUND floor has its feet
            if (k.is(Kind.WATER) && k.empty() && !k.is(Kind.AVOID) && (yi + 1 >= n || !Palette.get(ids[yi + 1]).is(Kind.WATER))
                && !feetAt(out, y)) {
                float ceil = ceiling(ids, minY, yi + 1, y + 1, true);
                if (ceil - (y + 1) >= 1.4F - 1e-5F) {
                    ChunkFloors.Floor f = new ChunkFloors.Floor();
                    f.type = ChunkFloors.SWIM;
                    f.h = y + 1;
                    f.head = Math.min(ceil - (y + 1), ChunkFloors.MAX_CLEAR);
                    f.fb = y;
                    f.cellTop = y + 1;
                    f.cellClear = Math.min(ceiling(ids, minY, yi + 1, y + 1, false) - (y + 1), ChunkFloors.MAX_CLEAR);
                    window(ids, yi, f.window);
                    out.add(f);
                }
            }

            // CLIMB: a climbable block with room for the body, where no GROUND floor has its feet
            if (k.is(Kind.CLIMB) && Float.isNaN(k.footTop) && !k.blocksBody() && !k.is(Kind.WATER) && !feetAt(out, y)) {
                float ceil = ceiling(ids, minY, yi + 1, y, true);
                if (ceil - y >= 1.8F - 1e-5F) {
                    ChunkFloors.Floor f = new ChunkFloors.Floor();
                    f.type = ChunkFloors.CLIMB;
                    f.h = y;
                    f.head = Math.min(ceil - y, ChunkFloors.MAX_CLEAR);
                    f.fb = y - 1;
                    f.cellTop = y;
                    f.cellClear = 0;
                    window(ids, yi - 1, f.window);
                    out.add(f);
                }
            }
        }
        out.sort((a, b) -> Float.compare(a.h, b.h));
        return nHaz;
    }

    /** Whether a GROUND floor already found has its feet in block y. */
    private static boolean feetAt(List<ChunkFloors.Floor> out, int y) {
        for (int i = out.size() - 1; i >= 0; i--) {
            ChunkFloors.Floor f = out.get(i);
            if (f.type == ChunkFloors.GROUND && Math.floor(f.h + 1e-4) == y) return true;
            if (f.h < y - 2) break;
        }
        return false;
    }

    /** The lowest point above `from` (a height) where the free space ends: a collision bottom inside the footprint (or
     *  anywhere in the cell when !footprint), or the bottom of a block not to be entered; scanning blocks from index j0
     *  up, at most MAX_CLEAR above `from`. */
    private static float ceiling(int[] ids, int minY, int j0, float from, boolean footprint) {
        int n = ids.length;
        float cap = from + ChunkFloors.MAX_CLEAR;
        for (int j = Math.max(0, j0); j < n; j++) {
            int y = minY + j;
            if (y >= cap) return cap;
            Kind k = Palette.get(ids[j]);
            float bottom = footprint ? k.footBottom : k.cellBottom;
            if (!Float.isNaN(bottom)) {
                // a box starting below `from` in this block (a block whose top is the floor itself is never scanned)
                float at = y + bottom;
                if (at >= from - 1e-5F) return Math.min(at, cap);
                // the box spans `from`: there is no free space here at all
                return from;
            }
            if (k.blocksBody()) return Math.max(from, Math.min(y, cap));
        }
        return Math.max(from, Math.min(minY + n, cap));
    }

    private static void window(int[] ids, int yi, int[] w) {
        for (int k = 0; k < ChunkFloors.WINDOW; k++) {
            int j = yi + k;
            w[k] = j >= 0 && j < ids.length ? ids[j] : Palette.AIR.id;
        }
    }
}
