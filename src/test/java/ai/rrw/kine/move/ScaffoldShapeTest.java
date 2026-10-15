package ai.rrw.kine.move;

import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The copy's scaffolding shapes (Shape.SCAFFOLD_STABLE, SCAFFOLD_UNSTABLE_BOTTOM) against the game's, built here as
 * ScaffoldingBlock builds them (its own fields need the game's bootstrap): the same cell boundaries on every axis (the
 * step-up's candidate heights come from them) and the same full cells, read through WorldSnapshot.shape as the
 * snapshot reads every shape.
 */
public class ScaffoldShapeTest {
    static VoxelShape box(double a, double b, double c, double d, double e, double f) {   // Block.box
        return Shapes.box(a / 16.0, b / 16.0, c / 16.0, d / 16.0, e / 16.0, f / 16.0);
    }

    static VoxelShape column(double size, double minY, double maxY) {   // Block.column
        double h = size / 2.0;
        return box(8.0 - h, minY, 8.0 - h, 8.0 + h, maxY, 8.0 + h);
    }

    static void same(Shape want, Shape got) {
        for (int a = 0; a < 3; a++) assertArrayEquals(want.coords[a], got.coords[a], 0.0, "axis " + a);
        assertArrayEquals(want.full, got.full);
        assertEquals(want.block, got.block);
        assertEquals(want.top(), got.top(), 0.0);
    }

    @Test
    void theCopysShapesAreTheGames() {
        VoxelShape stable = Shapes.or(column(16.0, 14.0, 16.0),
            Shapes.rotateHorizontal(box(0.0, 0.0, 0.0, 2.0, 16.0, 2.0)).values().stream().reduce(Shapes.empty(), Shapes::or));
        same(Shape.SCAFFOLD_STABLE, WorldSnapshot.shape(stable));
        same(Shape.SCAFFOLD_UNSTABLE_BOTTOM, WorldSnapshot.shape(column(16.0, 0.0, 2.0)));
        // SHAPE_BELOW_BLOCK's top, which isAbove reads for a bottom piece: 0
        assertEquals(0.0, Shapes.block().move(0.0, -1.0, 0.0).optimize().max(net.minecraft.core.Direction.Axis.Y), 0.0);
    }
}
