package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockInfo;

import java.util.Arrays;
import java.util.List;

/**
 * Every block kind the walkability table has met, by id. Ids are handed out once and never reused, so a table built
 * on one thread and read on another agrees on them; adding takes a lock, reading doesn't (the array is replaced whole,
 * and a reader sees either the old one or the new one, both with every id it can hold). The first four are stand-ins:
 * air, a plain solid block (what the table assumes for blocks it didn't keep, outside every floor's window and free
 * space), unknown (not loaded or not built yet: solid, and the search treats its columns as the loaded area's edge),
 * and a solid block that hurts (hazards the table kept only as a height).
 */
public final class Palette {
    private Palette() {}

    private static final Object LOCK = new Object();
    private static volatile Kind[] kinds = new Kind[0];

    private static final List<double[]> FULL = List.of(new double[]{0, 0, 0, 1, 1, 1});

    public static final Kind AIR = add("air", List.of(), 0.6F, 1.0F, 0);
    public static final Kind SOLID = add("solid", FULL, 0.6F, 1.0F, 0);
    public static final Kind UNKNOWN = add("unknown", FULL, 0.6F, 1.0F, Kind.AVOID);
    public static final Kind HAZARD_SOLID = add("hazard", FULL, 0.6F, 1.0F, Kind.HAZARD);

    public static Kind get(int id) {
        return kinds[id];
    }

    public static int size() {
        return kinds.length;
    }

    /** A new kind from boxes (tests, stand-ins). */
    public static Kind add(String name, List<double[]> boxes, float friction, float speedFactor, int flags) {
        synchronized (LOCK) {
            Kind k = Kind.of(kinds.length, name, boxes, friction, speedFactor, flags);
            append(k);
            return k;
        }
    }

    /** A new kind from the movement copy's block info and the game shape's boxes (McKinds). */
    static Kind add(String name, BlockInfo info, List<double[]> boxes, int flags) {
        synchronized (LOCK) {
            Kind k = new Kind(kinds.length, name, info, boxes, flags);
            append(k);
            return k;
        }
    }

    private static void append(Kind k) {
        Kind[] next = Arrays.copyOf(kinds, kinds.length + 1);
        next[k.id] = k;
        kinds = next;
    }
}
