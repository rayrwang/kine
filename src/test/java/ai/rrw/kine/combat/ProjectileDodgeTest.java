package ai.rrw.kine.combat;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The dodge's direction and the keys it presses for it. */
class ProjectileDodgeTest {

    @Test
    void stepsSidewaysToTheFlightNotAlongIt() {
        // an arrow flying -X straight at us, passing a hair above: step along Z, never X
        Vec3 d = ProjectileDodge.dodgeDir(new Vec3(1e-3, -0.2, 0), new Vec3(-3, -0.1, 0));
        assertEquals(0.0, d.x, 1e-12);
        assertEquals(1.0, Math.abs(d.z), 1e-12);
        // sitting a little to the +Z side of its line: step further +Z
        assertTrue(ProjectileDodge.dodgeDir(new Vec3(0, 0, 0.4), new Vec3(-3, 0, 0)).z > 0.99);
        assertTrue(ProjectileDodge.dodgeDir(new Vec3(0, 0, -0.4), new Vec3(-3, 0, 0)).z < -0.99);
        // straight down: away from where it lands
        assertTrue(ProjectileDodge.dodgeDir(new Vec3(0.3, 0, 0), new Vec3(0, -2, 0)).x > 0.99);
    }

    @Test
    void keysWalkTheChosenWay() {
        // yaw 0 faces +Z: forward walks +Z, left walks +X (Entity.getInputVector)
        assertArrayEquals(new boolean[]{true, false, false, false}, ProjectileDodge.bestCombo(new Vec3(0, 0, 1), 0f));
        assertArrayEquals(new boolean[]{false, true, false, false}, ProjectileDodge.bestCombo(new Vec3(0, 0, -1), 0f));
        assertArrayEquals(new boolean[]{false, false, true, false}, ProjectileDodge.bestCombo(new Vec3(1, 0, 0), 0f));
        assertArrayEquals(new boolean[]{true, false, false, true}, ProjectileDodge.bestCombo(new Vec3(-1, 0, 1).normalize(), 0f));
        // yaw 90 faces -X: forward walks -X
        assertArrayEquals(new boolean[]{true, false, false, false}, ProjectileDodge.bestCombo(new Vec3(-1, 0, 0), 90f));
    }
}
