package ai.rrw.kine.move;

/**
 * A block's collision shape as the game holds it (a VoxelShape): the cell boundaries along each axis and which cells
 * are full. The two operations the player's movement needs are ported line by line from the 26.1.2 code, so they
 * give the same doubles: {@link #collide} is VoxelShape.collideX (how far a box may move along an axis before it
 * meets the shape), and {@link #overlaps} is Shapes.joinIsNotEmpty(shape, box, AND) (which shapes BlockCollisions
 * hands to the collision at all). Both take the shape moved to its block, as VoxelShape.move does: each coordinate
 * plus the block's position, added in double.
 */
public final class Shape {
    /** The cell boundaries along x, y and z (VoxelShape.getCoords), in block-local coordinates. */
    final double[][] coords;
    /** Cells along each axis (DiscreteVoxelShape.getSize). */
    final int[] size;
    /** Full cells, index (x * size[1] + y) * size[2] + z. */
    final boolean[] full;
    /** No full cell (Shapes.empty()). */
    public final boolean empty;
    /** The game's own full-cube singleton (Shapes.block()): BlockCollisions tests it against the box directly. */
    public final boolean block;
    /** The lowest and highest coordinate of a full cell on each axis (VoxelShape.min and max), local. */
    final double[] min = new double[3], max = new double[3];

    public static final Shape EMPTY = new Shape(new double[][]{{0, 1}, {0, 1}, {0, 1}}, new boolean[1], false);
    public static final Shape BLOCK = new Shape(new double[][]{{0, 1}, {0, 1}, {0, 1}}, new boolean[]{true}, true);

    public Shape(double[][] coords, boolean[] full, boolean block) {
        this.coords = coords;
        this.size = new int[]{coords[0].length - 1, coords[1].length - 1, coords[2].length - 1};
        this.full = full;
        this.block = block;
        boolean any = false;
        for (boolean f : full) any |= f;
        this.empty = !any;
        for (int a = 0; a < 3; a++) {
            int lo = Integer.MAX_VALUE, hi = -1;
            for (int x = 0; x < size[0]; x++)
                for (int y = 0; y < size[1]; y++)
                    for (int z = 0; z < size[2]; z++)
                        if (full[(x * size[1] + y) * size[2] + z]) {
                            int i = a == 0 ? x : a == 1 ? y : z;
                            lo = Math.min(lo, i);
                            hi = Math.max(hi, i + 1);
                        }
            min[a] = lo == Integer.MAX_VALUE ? Double.POSITIVE_INFINITY : coords[a][lo];
            max[a] = hi <= 0 ? Double.NEGATIVE_INFINITY : coords[a][hi];
        }
    }

    /** DiscreteVoxelShape.isFullWide: false outside the grid. */
    boolean fullWide(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < size[0] && y < size[1] && z < size[2] && full[(x * size[1] + y) * size[2] + z];
    }

    /** VoxelShape.findIndex on the shape moved by off: the number of boundaries at or below v, less one. */
    int findIndex(int axis, double v, double off) {
        double[] c = coords[axis];
        int from = 0, len = c.length;   // Mth.binarySearch(0, size + 1, i -> v < get(axis, i)) - 1
        while (len > 0) {
            int half = len / 2, mid = from + half;
            if (v < c[mid] + off) len = half;
            else { from = mid + 1; len -= half + 1; }
        }
        return from - 1;
    }

    /** The y boundaries of the shape moved by off, as collectCandidateStepUpHeights reads them (getCoords(Y)). */
    double coordY(int i, double off) {
        return coords[1][i] + off;
    }

    int coordCountY() {
        return coords[1].length;
    }

    /**
     * VoxelShape.collide(axis, moving, distance) for this shape moved to (ox, oy, oz): the distance a box (minX, minY,
     * minZ, maxX, maxY, maxZ) may move along the axis (0 x, 1 y, 2 z) before it meets a full cell.
     */
    double collide(int axis, double[] box, double distance, double ox, double oy, double oz) {
        if (empty) return distance;
        if (Math.abs(distance) < 1.0E-7) return 0.0;
        int a = axis, b = (axis + 1) % 3, c = (axis + 2) % 3;   // AxisCycle: the axis, then the next two in turn
        double[] off = {ox, oy, oz};
        double maxA = box[3 + a], minA = box[a];
        int aMin = findIndex(a, minA + 1.0E-7, off[a]);
        int aMax = findIndex(a, maxA - 1.0E-7, off[a]);
        int bMin = Math.max(0, findIndex(b, box[b] + 1.0E-7, off[b]));
        int bMax = Math.min(size[b], findIndex(b, box[3 + b] - 1.0E-7, off[b]) + 1);
        int cMin = Math.max(0, findIndex(c, box[c] + 1.0E-7, off[c]));
        int cMax = Math.min(size[c], findIndex(c, box[3 + c] - 1.0E-7, off[c]) + 1);
        int aSize = size[a];
        int[] cell = new int[3];
        if (distance > 0.0) {
            for (int ai = aMax + 1; ai < aSize; ai++)
                for (int bi = bMin; bi < bMax; bi++)
                    for (int ci = cMin; ci < cMax; ci++) {
                        cell[a] = ai; cell[b] = bi; cell[c] = ci;
                        if (fullWide(cell[0], cell[1], cell[2])) {
                            double nd = (coords[a][ai] + off[a]) - maxA;
                            if (nd >= -1.0E-7) distance = Math.min(distance, nd);
                            return distance;
                        }
                    }
        } else if (distance < 0.0) {
            for (int ai = aMin - 1; ai >= 0; ai--)
                for (int bi = bMin; bi < bMax; bi++)
                    for (int ci = cMin; ci < cMax; ci++) {
                        cell[a] = ai; cell[b] = bi; cell[c] = ci;
                        if (fullWide(cell[0], cell[1], cell[2])) {
                            double nd = (coords[a][ai + 1] + off[a]) - minA;
                            if (nd <= 1.0E-7) distance = Math.max(distance, nd);
                            return distance;
                        }
                    }
        }
        return distance;
    }

    /**
     * Shapes.joinIsNotEmpty(this shape moved to (ox, oy, oz), Shapes.create(box), BooleanOp.AND): whether they overlap,
     * as the game decides it, coordinates within 1.0E-7 of each other counting as one (IndirectMerger).
     */
    boolean overlaps(double[] box, double ox, double oy, double oz) {
        if (empty) return false;
        if (box[3] - box[0] < 1.0E-7 || box[4] - box[1] < 1.0E-7 || box[5] - box[2] < 1.0E-7) return false;   // Shapes.create: empty
        double[] off = {ox, oy, oz};
        for (int a = 0; a < 3; a++) {
            if (max[a] + off[a] < box[a] - 1.0E-7) return false;
            if (box[3 + a] < min[a] + off[a] - 1.0E-7) return false;
        }
        int[][] mx = merge(0, box[0], box[3], ox), my = merge(1, box[1], box[4], oy), mz = merge(2, box[2], box[5], oz);
        for (int i = 0; i < mx[0].length; i++)
            for (int j = 0; j < my[0].length; j++)
                for (int k = 0; k < mz[0].length; k++)
                    if (fullWide(mx[0][i], my[0][j], mz[0][k]) && mx[1][i] == 0 && my[1][j] == 0 && mz[1][k] == 0) return true;
        return false;
    }

    /** IndirectMerger(first = this shape's coords on the axis plus off, second = {lo, hi}, AND), its merged cells as
     *  {first cell indices, second cell indices}, one per cell in forMergedIndexes order. */
    private int[][] merge(int axis, double lo, double hi, double off) {
        double[] first = coords[axis];
        double[] second = {lo, hi};
        int firstSize = first.length, secondSize = 2, cap = firstSize + secondSize;
        int[] fi = new int[cap], si = new int[cap];
        int n = 0, f = 0, s = 0;
        double last = Double.NaN;
        while (true) {
            boolean outF = f >= firstSize, outS = s >= secondSize;
            if (outF && outS) break;
            boolean choseFirst = !outF && (outS || first[f] + off < second[s] + 1.0E-7);
            if (choseFirst) {
                f++;
                if (s == 0 || outS) continue;   // canSkipFirst (AND)
            } else {
                s++;
                if (f == 0 || outF) continue;   // canSkipSecond (AND)
            }
            int cf = f - 1, cs = s - 1;
            double next = choseFirst ? first[cf] + off : second[cs];
            if (!(last >= next - 1.0E-7)) {
                fi[n] = cf; si[n] = cs; n++;
                last = next;
            } else {
                fi[n - 1] = cf; si[n - 1] = cs;
            }
        }
        int len = Math.max(1, n) - 1;   // forMergedIndexes: resultLength - 1 cells
        int[][] out = new int[2][len];
        for (int i = 0; i < len; i++) { out[0][i] = fi[i]; out[1][i] = si[i]; }
        return out;
    }

    /** The highest point of the shape (VoxelShape.max(Y)), local; -infinity when empty. */
    public double top() {
        return max[1];
    }
}
