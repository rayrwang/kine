package ai.rrw.kine.move;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A snapshot of the blocks around the player, as the movement copy reads them: one BlockInfo per block, looked up by
 * world position. Blocks outside the snapshot read as {@link BlockInfo#UNKNOWN} (solid and flagged). No Minecraft
 * class is used here; WorldSnapshot fills it from the live world. Not final: the elytra guardian's copy of the blocks
 * far ahead reads copied chunk sections instead of a box of cells, by overriding {@link #get}.
 */
public class BlockGrid {
    public final int x0, y0, z0, nx, ny, nz;
    private final short[] cells;
    private final List<BlockInfo> table = new ArrayList<>();
    private final Map<BlockInfo, Short> index = new IdentityHashMap<>();

    public BlockGrid(int x0, int y0, int z0, int nx, int ny, int nz) {
        this.x0 = x0; this.y0 = y0; this.z0 = z0;
        this.nx = nx; this.ny = ny; this.nz = nz;
        this.cells = new short[nx * ny * nz];
        table.add(BlockInfo.AIR);
        index.put(BlockInfo.AIR, (short) 0);
    }

    public boolean inside(int x, int y, int z) {
        return x >= x0 && y >= y0 && z >= z0 && x < x0 + nx && y < y0 + ny && z < z0 + nz;
    }

    public BlockInfo get(int x, int y, int z) {
        if (!inside(x, y, z)) return BlockInfo.UNKNOWN;
        return table.get(cells[((x - x0) * ny + (y - y0)) * nz + (z - z0)]);
    }

    public void set(int x, int y, int z, BlockInfo b) {
        if (!inside(x, y, z)) return;
        Short i = index.get(b);
        if (i == null) {
            i = (short) table.size();
            table.add(b);
            index.put(b, i);
        }
        cells[((x - x0) * ny + (y - y0)) * nz + (z - z0)] = i;
    }

    /** The distinct block kinds held, for writing the snapshot out. */
    public List<BlockInfo> kinds() {
        return table;
    }

    public int kindAt(int x, int y, int z) {
        return inside(x, y, z) ? cells[((x - x0) * ny + (y - y0)) * nz + (z - z0)] : -1;
    }

    /** Shapes that touch a box, as BlockCollisions finds them, in its order (Cursor3D: x fastest, then y, then z). */
    public static final class Colliders {
        public Shape[] shape = new Shape[64];
        public int[] x = new int[64], y = new int[64], z = new int[64];
        public int n;

        void add(Shape s, int bx, int by, int bz) {
            if (n == shape.length) {
                shape = java.util.Arrays.copyOf(shape, n * 2);
                x = java.util.Arrays.copyOf(x, n * 2);
                y = java.util.Arrays.copyOf(y, n * 2);
                z = java.util.Arrays.copyOf(z, n * 2);
            }
            shape[n] = s; x[n] = bx; y[n] = by; z[n] = bz; n++;
        }
    }

    /**
     * BlockCollisions(level, entity, box): every block whose collision shape overlaps the box (minX, minY, minZ, maxX,
     * maxY, maxZ). The ring of blocks just outside the box is read only for tall shapes (fences, walls), as the game
     * does; corners never.
     */
    public void collect(double[] box, Colliders out) {
        out.n = 0;
        int xa = GameMath.floor(box[0] - 1.0E-7) - 1, xb = GameMath.floor(box[3] + 1.0E-7) + 1;
        int ya = GameMath.floor(box[1] - 1.0E-7) - 1, yb = GameMath.floor(box[4] + 1.0E-7) + 1;
        int za = GameMath.floor(box[2] - 1.0E-7) - 1, zb = GameMath.floor(box[5] + 1.0E-7) + 1;
        for (int z = za; z <= zb; z++)
            for (int y = ya; y <= yb; y++)
                for (int x = xa; x <= xb; x++) {
                    int type = (x == xa || x == xb ? 1 : 0) + (y == ya || y == yb ? 1 : 0) + (z == za || z == zb ? 1 : 0);
                    if (type == 3 || type == 2) continue;   // corners never; edges only for a moving piston
                    BlockInfo b = get(x, y, z);
                    if (type == 1 && !b.is(BlockInfo.LARGE)) continue;
                    Shape s = b.shape;
                    if (s.block) {
                        if (box[0] < x + 1.0 && box[3] > x && box[1] < y + 1.0 && box[4] > y && box[2] < z + 1.0 && box[5] > z)
                            out.add(s, x, y, z);
                    } else if (!s.empty && s.overlaps(box, x, y, z)) {
                        out.add(s, x, y, z);
                    }
                }
    }
}
