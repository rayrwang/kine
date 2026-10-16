package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Shape;
import ai.rrw.kine.move.ShapeProbe;

import java.util.List;

/**
 * One kind of block as the walking pathfinder sees it: the movement copy's {@link BlockInfo} (its collision shape for
 * the player, friction, speed and jump factors) and what the walkability table needs on top: the shape's highest top
 * and lowest bottom inside the player's footprint at a column's centre (the middle 0.6 by 0.6, where a player standing
 * at the centre has its box) and over the whole cell, whether its top face covers the whole cell, and flags for what
 * the shape doesn't say (water, lava, things that hurt, things to climb, doors). Kinds live in the {@link Palette} and
 * the table stores their ids. No Minecraft class here; McKinds makes them from block states.
 */
public final class Kind {
    /** Water, a waterlogged block or a bubble column: the player wades or swims. */
    public static final int WATER = 1;
    /** Lava: never entered. */
    public static final int LAVA = 2;
    /** Hurts on touch: fire, cactus, berry bushes, wither roses, powder snow, pointed dripstone, campfires. */
    public static final int HAZARD = 4;
    /** Hurts to stand on: magma (without sneaking), campfires. */
    public static final int FLOOR_HAZARD = 8;
    /** A ladder, vine or other climbable block. */
    public static final int CLIMB = 16;
    /** Passable for collision but not to walk through: cobwebs, portals, honey (stick), bubble columns, end gateways
     *  (scaffolding and slime no longer: McKinds). The table treats them as walls. */
    public static final int AVOID = 32;
    /** A door, trapdoor or fence gate (closed ones are walls through their collision shape). */
    public static final int DOOR = 64;
    /** Slows walking: speed factor below 1 (soul sand, honey), or slime (its stepOn). */
    public static final int SLOW = 128;
    /** A closed door the walk can open by hand (a wooden door: DoorOpener); iron ones need redstone. */
    public static final int CLOSED_DOOR = 256;

    /** The middle of a column the player's box covers when standing at its centre: 0.2 to 0.8 on x and z. */
    public static final double FOOT_LO = 0.2, FOOT_HI = 0.8;

    public final int id;
    public final String name;
    public final BlockInfo info;
    /** Highest collision top and lowest collision bottom inside the footprint, block-local; NaN when the shape misses it. */
    public final float footTop, footBottom;
    /** The same over the whole cell; NaN for no collision. */
    public final float cellTop, cellBottom;
    /** The shape's top face covers the whole cell at cellTop (a full block, a slab, a carpet; not stairs or a fence). */
    public final boolean topFull;
    public final int flags;
    /** Where the block is drawn higher than its collision reaches (snow layers, soul sand, mud), the drawn top,
     *  block-local; NaN otherwise. The walking rails lie on what you see. */
    public final float drawnTop;
    /** The shape's boxes, block-local {minX, minY, minZ, maxX, maxY, maxZ} each. */
    final double[][] boxes;

    Kind(int id, String name, BlockInfo info, List<double[]> boxes, int flags) {
        this(id, name, info, boxes, flags, Float.NaN);
    }

    Kind(int id, String name, BlockInfo info, List<double[]> boxes, int flags, float drawnTop) {
        this.drawnTop = drawnTop;
        this.id = id;
        this.name = name;
        this.info = info;
        this.flags = flags;
        this.boxes = boxes.toArray(new double[0][]);
        float ft = Float.NaN, fb = Float.NaN, ct = Float.NaN, cb = Float.NaN;
        for (double[] b : this.boxes) {
            ct = Float.isNaN(ct) ? (float) b[4] : Math.max(ct, (float) b[4]);
            cb = Float.isNaN(cb) ? (float) b[1] : Math.min(cb, (float) b[1]);
            if (b[0] < FOOT_HI && b[3] > FOOT_LO && b[2] < FOOT_HI && b[5] > FOOT_LO) {
                ft = Float.isNaN(ft) ? (float) b[4] : Math.max(ft, (float) b[4]);
                fb = Float.isNaN(fb) ? (float) b[1] : Math.min(fb, (float) b[1]);
            }
        }
        this.footTop = ft; this.footBottom = fb; this.cellTop = ct; this.cellBottom = cb;
        this.topFull = !Float.isNaN(ct) && coversTop(this.boxes, ct);
    }

    /** Whether the boxes reaching up to `top` cover the whole cell seen from above (checked on an 8 by 8 grid of points). */
    private static boolean coversTop(double[][] boxes, double top) {
        for (int i = 0; i < 8; i++)
            for (int k = 0; k < 8; k++) {
                double x = (i + 0.5) / 8, z = (k + 0.5) / 8;
                boolean in = false;
                for (double[] b : boxes)
                    if (b[4] >= top - 1e-6 && x > b[0] && x < b[3] && z > b[2] && z < b[5]) { in = true; break; }
                if (!in) return false;
            }
        return true;
    }

    public boolean is(int flag) {
        return (flags & flag) != 0;
    }

    /** No collision at all. */
    public boolean empty() {
        return boxes.length == 0;
    }

    /** Something the player must not walk into: lava, a hazard, or a block to avoid. */
    public boolean blocksBody() {
        return (flags & (LAVA | HAZARD | AVOID)) != 0;
    }

    /** Whether this kind's boxes together span the cell from 0 to 1 along axis (0 x, 2 z), gaps under 0.01 closed. */
    boolean spans(int axis) {
        if (boxes == null || boxes.length == 0) return false;
        double[][] iv = new double[boxes.length][];
        for (int i = 0; i < boxes.length; i++) iv[i] = new double[]{boxes[i][axis], boxes[i][axis + 3]};
        java.util.Arrays.sort(iv, (a, b) -> Double.compare(a[0], b[0]));
        double reach = 0.0;
        for (double[] r : iv) {
            if (r[0] > reach + 0.01) return false;
            reach = Math.max(reach, r[1]);
        }
        return reach >= 1.0 - 0.01;
    }

    /** The highest top of this kind's boxes that overlap the horizontal rectangle (block-local x0..x1, z0..z1) and lie at
     *  or below `maxTop` (block-local), or NaN. */
    double topWithin(double x0, double x1, double z0, double z1, double maxTop) {
        double best = Double.NaN;
        for (double[] b : boxes)
            if (b[0] < x1 && b[3] > x0 && b[2] < z1 && b[5] > z0 && b[4] <= maxTop + 1e-9)
                best = Double.isNaN(best) ? b[4] : Math.max(best, b[4]);
        return best;
    }

    /** A kind described by boxes, for tests and for the table's own stand-ins. */
    static Kind of(int id, String name, List<double[]> boxes, float friction, float speedFactor, int flags) {
        return of(id, name, boxes, friction, speedFactor, flags, Float.NaN);
    }

    /** The same, drawn up to `drawnTop` (block-local) above its collision. */
    static Kind of(int id, String name, List<double[]> boxes, float friction, float speedFactor, int flags, float drawnTop) {
        Shape shape = ShapeProbe.fromBoxes(boxes);
        int moveFlags = 0;
        if ((flags & WATER) != 0) moveFlags |= BlockInfo.WATER | BlockInfo.FLUID;
        if ((flags & LAVA) != 0) moveFlags |= BlockInfo.FLUID | BlockInfo.HAZARD;
        if ((flags & (HAZARD | FLOOR_HAZARD)) != 0) moveFlags |= BlockInfo.HAZARD;
        if ((flags & FLOOR_HAZARD) != 0) moveFlags |= BlockInfo.STEP_HURTS;   // magma: hurts to stand on (stepOn)
        if ((flags & (CLIMB | AVOID)) != 0) moveFlags |= BlockInfo.SPECIAL;
        return new Kind(id, name, new BlockInfo(shape, friction, speedFactor, 1.0F, moveFlags), boxes, flags, drawnTop);
    }

    @Override
    public String toString() {
        return name;
    }
}
