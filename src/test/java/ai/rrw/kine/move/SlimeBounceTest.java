package ai.rrw.kine.move;

import ai.rrw.kine.walk.Recipe;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slime in the movement copy (RW 2026-10-14, round 2: the course's slime_bounce stopped the walk at jump 22 in three
 * walks of eight). The game's SlimeBlock: a fall onto it reverses the vertical speed unless the shift key is down
 * (updateEntityMovementAfterFallOn), costs no fall damage then (fallOn), and standing or walking on it with the vertical
 * speed under 0.1 scales the horizontal speed by 0.4 + |vy| * 0.2 each tick (stepOn, after travel). A bed reverses 0.66
 * of it and halves the fall. The station's layout: the take-off block, a slime block 3 on and 6 down, the landing (gold)
 * 6 on and 3 down, nothing else.
 */
public class SlimeBounceTest {
    static final int F = 20;   // the take-off's top
    static final BlockInfo STONE = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);
    static final BlockInfo SLIME = new BlockInfo(Shape.BLOCK, 0.8F, 1.0F, 1.0F, BlockInfo.SPECIAL | BlockInfo.SLIME);
    static final BlockInfo BED = new BlockInfo(Shape.EMPTY, 0.6F, 1.0F, 1.0F, BlockInfo.SPECIAL | BlockInfo.BED);

    static BlockGrid station() {
        BlockGrid g = new BlockGrid(-16, -16, -16, 40, 64, 40);
        g.set(0, F - 1, 0, STONE);       // the take-off
        g.set(0, F - 2, 1, STONE);       // the course's pad beside it, one lower
        g.set(3, F - 7, 0, SLIME);
        g.set(6, F - 4, 0, STONE);       // the landing
        return g;
    }

    static MoveState standing(double x, double y, double z) {
        MoveState s = new MoveState();
        s.x = x; s.y = y; s.z = z;
        s.vy = -0.0784000015258789;
        s.onGround = true;
        s.hasSupport = true; s.supX = (int) Math.floor(x); s.supY = (int) Math.floor(y) - 1; s.supZ = (int) Math.floor(z);
        return s;
    }

    /** Dropped from h above a slime block: the bounce's top over the block, and no hurting landing. */
    @Test
    void aFallOntoSlimeBouncesBackUp() {
        BlockGrid g = new BlockGrid(-16, -16, -16, 32, 64, 32);
        g.set(0, 0, 0, SLIME);
        GroundPhysics phys = new GroundPhysics(g, new Body());
        MoveState s = new MoveState();
        s.x = 0.5; s.y = 7.0; s.z = 0.5;   // 6 above the top
        boolean landed = false;
        double top = 0;
        int bounces = 0;
        for (int t = 0; t < 80; t++) {
            boolean air = !s.onGround;
            phys.step(s, 0, 0f);
            if (air && s.onGround) { bounces++; landed = true; }
            if (landed && bounces == 1) top = Math.max(top, s.y - 1.0);
        }
        System.out.printf(Locale.ROOT, "slime: 6 down, the first bounce's top %.3f over the block, %d bounces in 80 ticks%n", top, bounces);
        assertTrue(top > 4.0 && top < 6.0, "a 6 block fall bounces most of the way back: " + top);

        // shift held: no bounce, a landing
        MoveState q = new MoveState();
        q.x = 0.5; q.y = 7.0; q.z = 0.5;
        for (int t = 0; t < 40; t++) phys.step(q, MoveState.SHIFT, 0f);
        assertTrue(q.onGround && Math.abs(q.y - 1.0) < 1e-9, "sneaking lands on slime: " + q.y);
    }

    /** Walking on slime: the speed is cut each tick it stands on it, so a walk across is slow. */
    @Test
    void walkingOnSlimeIsSlow() {
        BlockGrid g = new BlockGrid(-16, -16, -16, 48, 64, 48);
        BlockGrid h = new BlockGrid(-16, -16, -16, 48, 64, 48);
        for (int x = -2; x <= 20; x++) { g.set(x, 0, 0, SLIME); h.set(x, 0, 0, STONE); }
        double[] went = new double[2];
        for (int i = 0; i < 2; i++) {
            GroundPhysics phys = new GroundPhysics(i == 0 ? g : h, new Body());
            MoveState s = standing(0.5, 1.0, 0.5);
            for (int t = 0; t < 40; t++) phys.step(s, MoveState.FORWARD, -90f);
            went[i] = s.x - 0.5;
        }
        System.out.printf(Locale.ROOT, "40 ticks walking: on slime %.3f blocks, on stone %.3f%n", went[0], went[1]);
        assertTrue(went[0] < 0.6 * went[1], "slime slows a walk: " + went[0] + " against " + went[1]);
    }

    /** The station: the copy's jump search from standing on the take-off finds a way to the landing, and from starts a
     *  little off it (as a walk arrives) the same inputs still land: what the walk needs to keep playing them. */
    @Test
    void theStationsJumpLandsFromStartsNearIt() {
        BlockGrid g = station();
        GroundPhysics phys = new GroundPhysics(g, new Body());
        MoveState s = standing(0.49, F, 0.5);
        float bearing = (float) Math.toDegrees(Math.atan2(-6.0, 0.0));
        Recipe r = Recipe.search(phys, s, -90f, 6, F - 4, 0, bearing - 90f, bearing + 90f, 6f, 8, 8, 60);
        assertTrue(r.found(), "a way from the take-off");
        boolean viaSlime = false;
        for (int t = 0; t < r.ticks; t++) if (r.path[3 * t + 1] < F - 5.5) viaSlime = true;
        double margin = Math.min(Math.min(r.endX - 6, 7 - r.endX), Math.min(r.endZ, 1 - r.endZ));
        int held = 0, tried = 0;
        for (double dx = -0.1; dx <= 0.1001; dx += 0.025)
            for (double dz = -0.1; dz <= 0.1001; dz += 0.025) {
                tried++;
                if (r.stillLands(phys, standing(0.49 + dx, F, 0.5 + dz), 0, 6, F - 4, 0)) held++;
            }
        System.out.printf(Locale.ROOT, "station: %s, %d ways of %d, %d ticks, %s, margin %.3f; from starts within 0.1: %d of %d land%n",
            r.how, r.ways, r.tried, r.ticks, viaSlime ? "by the slime" : "over the slime", margin, held, tried);
        assertTrue(held * 10 >= tried * 9, "the inputs land from 9 in 10 starts near the take-off: " + held + " of " + tried);
    }

    /** The walk's fall check (Recipe.fallEndsSafely, for Layer2.fallEndsSafely): a fall of 6 onto slime ends safely (the bounce), onto stone it
     *  hurts, and a fall of 2 onto stone doesn't (RW 2026-10-14: layer 2's own way down onto the course's slime_bounce
     *  was taken for a fall and ended the walk). */
    @Test
    void aFallOntoSlimeIsNoFall() {
        for (int k = 0; k < 3; k++) {
            BlockGrid g = new BlockGrid(-16, -16, -16, 32, 64, 32);
            g.set(0, 0, 0, k == 0 ? SLIME : STONE);
            GroundPhysics phys = new GroundPhysics(g, new Body());
            MoveState s = new MoveState();
            s.x = 0.5; s.z = 0.5; s.y = k == 2 ? 3.0 : 7.0;
            boolean safe = Recipe.fallEndsSafely(phys, s, 3.0);
            assertEquals(k != 1, safe, k == 0 ? "6 onto slime" : k == 1 ? "6 onto stone" : "2 onto stone");
        }
    }
}
