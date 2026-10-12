package ai.rrw.kine.move;

import java.util.Arrays;
import java.util.List;

/**
 * Public access to the movement copy's collision shapes for the pathfinder (walk/), which keeps its own table of the
 * blocks rather than a BlockGrid: the box test BlockCollisions makes (a full cube directly, any other shape through
 * Shapes.joinIsNotEmpty, ported in {@link Shape#overlaps}), and a shape built from plain boxes, as WorldSnapshot
 * builds one from the game's VoxelShape, for unit tests and for blocks described without the game.
 */
public final class ShapeProbe {
    private ShapeProbe() {}

    /** Whether the box (minX, minY, minZ, maxX, maxY, maxZ) meets the shape placed at block (x, y, z). */
    public static boolean overlaps(Shape s, double[] box, int x, int y, int z) {
        if (s.empty) return false;
        if (s.block) return box[0] < x + 1.0 && box[3] > x && box[1] < y + 1.0 && box[4] > y && box[2] < z + 1.0 && box[5] > z;
        return s.overlaps(box, x, y, z);
    }

    /** A shape from block-local boxes {minX, minY, minZ, maxX, maxY, maxZ}: the boundaries along each axis are the
     *  boxes' own, and a cell is full when its centre is inside a box (as WorldSnapshot.shape reads the game's). */
    public static Shape fromBoxes(List<double[]> boxes) {
        if (boxes.isEmpty()) return Shape.EMPTY;
        if (boxes.size() == 1) {
            double[] b = boxes.get(0);
            if (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 1 && b[4] == 1 && b[5] == 1) return Shape.BLOCK;
        }
        double[][] c = new double[3][];
        for (int a = 0; a < 3; a++) {
            double[] v = new double[boxes.size() * 2];
            int n = 0;
            for (double[] b : boxes) { v[n++] = b[a]; v[n++] = b[a + 3]; }
            c[a] = Arrays.stream(v).sorted().distinct().toArray();
        }
        int sx = c[0].length - 1, sy = c[1].length - 1, sz = c[2].length - 1;
        boolean[] full = new boolean[sx * sy * sz];
        for (int i = 0; i < sx; i++)
            for (int j = 0; j < sy; j++)
                for (int k = 0; k < sz; k++) {
                    double px = (c[0][i] + c[0][i + 1]) / 2, py = (c[1][j] + c[1][j + 1]) / 2, pz = (c[2][k] + c[2][k + 1]) / 2;
                    for (double[] b : boxes)
                        if (px > b[0] && px < b[3] && py > b[1] && py < b[4] && pz > b[2] && pz < b[5]) {
                            full[(i * sy + j) * sz + k] = true;
                            break;
                        }
                }
        return new Shape(c, full, false);
    }
}
