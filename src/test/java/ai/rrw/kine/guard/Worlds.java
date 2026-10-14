package ai.rrw.kine.guard;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Shape;

/** Synthetic terrain for the guardian's offline scenarios: a block function read lazily (no cell array). */
final class Worlds {
    private Worlds() {}

    static final BlockInfo STONE = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
    static final BlockInfo LEAVES = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
    static final BlockInfo WATER = new BlockInfo(Shape.EMPTY, 0.6F, 1.0F, 1.0F, BlockInfo.WATER | BlockInfo.FLUID,
        BlockInfo.WATER_FLUID, 8.0F / 9.0F);
    static final BlockInfo HAY = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0, BlockInfo.NO_FLUID, 0.0F, 0.2F);
    static final BlockInfo SLIME = new BlockInfo(Shape.BLOCK, 0.8F, 1.0F, 1.0F, BlockInfo.SPECIAL, BlockInfo.NO_FLUID, 0.0F, 0.0F);
    static final BlockInfo LAVA = new BlockInfo(Shape.EMPTY, 0.6F, 1.0F, 1.0F, BlockInfo.FLUID | BlockInfo.HAZARD,
        BlockInfo.LAVA_FLUID, 8.0F / 9.0F);

    interface Fn { BlockInfo at(int x, int y, int z); }

    static final class FnGrid extends BlockGrid {
        final Fn f;
        FnGrid(Fn f) { super(0, 0, 0, 0, 0, 0); this.f = f; }
        @Override public BlockInfo get(int x, int y, int z) { return f.at(x, y, z); }
    }

    static BlockGrid of(Fn f) { return new FnGrid(f); }

    /** Flat stone up to y 63 (standing height 64). */
    static BlockGrid flat() { return of((x, y, z) -> y < 64 ? STONE : BlockInfo.AIR); }

    /** Flat ground and a wall across +z at z >= wallZ, from the ground to y 320. */
    static BlockGrid wall(int wallZ) { return of((x, y, z) -> y < 64 || z >= wallZ && z < wallZ + 8 ? STONE : BlockInfo.AIR); }

    /** Flat ground at 64, a cliff rising to `top` from z >= cliffZ. */
    static BlockGrid cliff(int cliffZ, int top) { return of((x, y, z) -> y < 64 || z >= cliffZ && y < top ? STONE : BlockInfo.AIR); }

    /** The Nether's shape: a floor up to 31, a bedrock roof from 123 to 127, nothing above. */
    static BlockGrid netherRoof() { return of((x, y, z) -> y < 32 || y >= 123 && y < 128 ? STONE : BlockInfo.AIR); }

    /** Flat ground and an arch over the path: pillars beside x 0, a roof from y 80 to 84 over z 100 to 110. */
    static BlockGrid arch() {
        return of((x, y, z) -> {
            if (y < 64) return STONE;
            boolean span = z >= 100 && z <= 110;
            if (span && (x == -6 || x == 6) && y < 85) return STONE;
            if (span && Math.abs(x) <= 6 && y >= 80 && y < 85) return STONE;
            return BlockInfo.AIR;
        });
    }

    /** A forest: ground at 64, a canopy of leaves from 74 to 79 everywhere except a 12 by 12 clearing around (0, zc). */
    static BlockGrid canopy(int zc) {
        return of((x, y, z) -> {
            if (y < 64) return STONE;
            boolean clearing = Math.abs(x) <= 6 && Math.abs(z - zc) <= 6;
            if (!clearing && y >= 74 && y < 80) return LEAVES;
            return BlockInfo.AIR;
        });
    }

    /** A lake: stone to 33, water from 34 to 63 (30 deep), within 200 of the origin; flat stone ground at 64 beyond. */
    static BlockGrid lake() {
        return of((x, y, z) -> {
            boolean in = Math.abs(x) < 200 && Math.abs(z) < 400;
            if (!in) return y < 64 ? STONE : BlockInfo.AIR;
            if (y < 34) return STONE;
            if (y < 64) return WATER;
            return BlockInfo.AIR;
        });
    }

    /** A box canyon: walls either side (x beyond +-12) up to 160 and across the end at z >= endZ, ground at 64. */
    static BlockGrid canyon(int endZ) {
        return of((x, y, z) -> y < 64 || (Math.abs(x) > 12 || z >= endZ) && y < 160 ? STONE : BlockInfo.AIR);
    }
}
