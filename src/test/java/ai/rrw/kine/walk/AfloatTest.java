package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The walk's float (Afloat), closed loop in the movement copy: a player left in water with the keys Afloat presses
 * each tick (as Afloat.during: none when standing with the eyes out), from the bottom of ponds 2, 4 and 8 deep, from
 * under a roof over half a pond, under lily pads with open water only round the edge, at the bottom of a shaft 8 deep
 * and one block wide, and floating at the top. Each: no drowning in 600 ticks (two drownings' worth), the eyes out of
 * the water at the end, and the air back to full. Without the float (no keys) the same starts sink and drown.
 */
public class AfloatTest {
    static final Kind LILY = Palette.add("lily_pad_test", List.of(TestWorld.box(0, 0, 0, 1, 0.09375, 1)), 0.6F, 1.0F, 0);

    record Run(double drowned, int eyesUnderLast100, double minAir, double air, double y, int pressed) {}

    /** Ground at y 10 (feet 11); a pond in x 6..15, z -6..6, its top block at y 10 (level with the ground), depth d. */
    static TestWorld pond(int d) {
        TestWorld w = TestWorld.blocks(-8, -16, 31, 15).floor(10);
        for (int y = 10 - d; y < 10; y++) w.fill(-8, y, -16, 31, y, 15, TestWorld.STONE);
        w.fill(6, 11 - d, -6, 15, 10, 6, TestWorld.WATER);
        return w;
    }

    /** Under water at (x, z) on the bottom of a pond whose bottom block is y0 - 1 (feet at y0), the air at 200. */
    static MoveState bottom(double x, int y0, double z) {
        MoveState s = new MoveState();
        s.x = x; s.y = y0; s.z = z;
        s.inWater = true; s.eyesInWater = true; s.onGround = true;
        s.air = 200;
        return s;
    }

    static Run run(String name, TestWorld w, MoveState s, boolean afloat) {
        BlockGrid g = Layer2Sim.grid(w);
        Body body = new Body();
        GroundPhysics phys = new GroundPhysics(g, body);
        float yaw = 30f;   // the rotation sent: an arbitrary one, the keys are pressed relative to it
        double minAir = s.air;
        int pressed = 0, dry = 0;
        boolean armed = afloat;   // after the walk: armed until out of the water for good (Afloat.after)
        int[] eyes = new int[600];
        for (int t = 0; t < 600; t++) {
            int k = 0;
            dry = s.inWater ? 0 : dry + 1;
            if (armed && Afloat.over(s.onGround, dry)) armed = false;
            if (armed && s.inWater && !(s.onGround && !s.eyesInWater)) {
                double eyeY = s.y + (s.swimPose ? body.swimEyeHeight : s.crouchPose ? body.crouchEyeHeight : body.eyeHeight);
                k = s.eyesInWater ? Afloat.keys(g, s.x, eyeY, s.z, yaw) : MoveState.JUMP;
                pressed++;
            }
            phys.step(s, k, yaw, 0f);
            eyes[t] = s.eyesInWater ? 1 : 0;
            minAir = Math.min(minAir, s.air);
        }
        int last = 0;
        for (int t = 500; t < 600; t++) last += eyes[t];
        if (afloat) assertTrue(armed, name + ": still on at the end (treading water lifts the feet out for a few ticks)");
        Run r = new Run(s.drownDamage, last, minAir, s.air, s.y, pressed);
        System.out.printf(Locale.ROOT, "afloat %-12s %s: drowned %.0f, eyes under %d of the last 100 ticks, air min %.0f end %.0f, feet %.2f, keys %d ticks%n",
            name, afloat ? "float" : "no keys", r.drowned(), r.eyesUnderLast100(), r.minAir(), r.air(), r.y(), r.pressed());
        return r;
    }

    static void breathes(String name, TestWorld w, MoveState s) {
        Run without = run(name, w, s.copy(), false);
        Run with = run(name, w, s, true);
        assertTrue(without.drowned() > 0, name + ": without keys it drowns");
        assertEquals(0.0, with.drowned(), name + ": no drowning");
        assertTrue(with.eyesUnderLast100() <= 20, name + ": the eyes mostly out at the end: " + with.eyesUnderLast100());
        assertTrue(with.air() >= 280, name + ": the air back to full or nearly: " + with.air());
    }

    @Test
    void comesUpFromTheBottomAndTreadsWater() {
        for (int d : new int[]{2, 4, 8}) breathes("bottom" + d, pond(d), bottom(10.5, 11 - d, 0.5));
    }

    @Test
    void outFromUnderARoof() {
        // a stone roof straight on the water over the pond's west half (x 6..10): out east to open water
        TestWorld w = pond(4).fill(6, 11, -6, 10, 11, 6, TestWorld.STONE);
        breathes("roof", w, bottom(8.5, 7, 0.5));
    }

    @Test
    void outFromUnderLilyPads() {
        // lily pads over all but the pond's outer ring
        TestWorld w = pond(4).fill(7, 11, -5, 14, 11, 5, LILY);
        breathes("lilyPads", w, bottom(10.5, 7, 0.5));
    }

    @Test
    void upANarrowShaft() {
        // a shaft one block wide and 8 deep in the ground
        TestWorld w = TestWorld.blocks(-8, -16, 31, 15).floor(10);
        for (int y = 2; y < 10; y++) w.fill(-8, y, -16, 31, y, 15, TestWorld.STONE);
        w.fill(4, 3, 0, 4, 10, 0, TestWorld.WATER);
        breathes("shaft8", w, bottom(4.5, 3, 0.5));
    }

    @Test
    void keepsAfloatFromTheTop() {
        // floating at the top of water 4 deep with the eyes out, full air: without keys it sinks and drowns
        MoveState s = bottom(10.5, 10, 0.5);
        s.y = 9.4; s.onGround = false; s.eyesInWater = false; s.air = 300;
        breathes("top4", pond(4), s);
    }

    @Test
    void roofedEverywhereStillSwimsUp() {
        // no open water anywhere in reach: jump, the best there is (no way out without breaking a block)
        TestWorld w = pond(4).fill(6, 11, -6, 15, 11, 6, TestWorld.STONE);
        BlockGrid g = Layer2Sim.grid(w);
        assertNull(Afloat.nearestOpen(g, 10, 8, 0));
        assertEquals(MoveState.JUMP, Afloat.keys(g, 10.5, 8.62, 0.5, 0f));
    }
}
