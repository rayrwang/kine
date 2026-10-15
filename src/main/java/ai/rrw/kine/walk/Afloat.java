package ai.rrw.kine.walk;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.Input;
import ai.rrw.kine.fight.Moves;
import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.GameMath;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.WorldSnapshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;

/**
 * Keeps the head above water whenever the walk leaves the player in water with no one at the keys (RW 2026-10-14:
 * "the layer 2 search got me stuck at the bottom of a bpond and i started dorwnign"). In water a player with no keys
 * pressed sinks, 1 block in about 40 ticks, and at the bottom of water two deep or more the eyes are under: the air
 * lasts 15 seconds, then drowning hurts 2 every second. The walk handed no keys when it ended by itself (arrived in
 * water, or stopped as near as it gets, "Stuck", no way on), when it was paused from the tab, and on its own ticks with
 * no steering (planning; walking without a route while layer 2 has no plan).
 *
 * <p>On those ticks, in water and not standing with the eyes out: jump held (swimming up, then treading water at the
 * top), and where the water over the eyes is closed (a roof, lily pads) toward the nearest water open to the air,
 * through the water, within REACH blocks. After the walk it keeps on until a movement key of yours (the walk's own
 * rule: any key gives you control back) or until out of the water; a new walk ends it.
 */
public final class Afloat {
    private Afloat() {}

    /** How far the way to open water is looked for: blocks each way, and up from the feet. */
    static final int REACH = 8, UP = 12;
    /** Within this of a narrow opening's centre (a column whose neighbours are roofed) the body fits up through it. */
    static final double CENTRE = 0.15;

    /** Ticks out of the water that end it: longer than a bob above the surface lasts (treading water lifts the feet out
     *  of it for a few ticks at a time). */
    static final int DRY_TICKS = 20;

    private static boolean armed = false;
    private static int dry = 0;
    private static BlockGrid grid = null;
    private static int gridAge = 0;
    private static double gridX, gridY, gridZ;

    /** Ticks it pressed keys (tests). */
    public static int pressedTicks = 0;

    /** The walk ended or paused without your keys: keep the head above water from the next tick. */
    static void arm() {
        armed = true;
        dry = 0;
    }

    /** A new walk, or your keys: no more. */
    static void disarm() {
        armed = false;
    }

    /** Whether it keeps the head above water after a walk now (tests and the display). */
    public static boolean armed() {
        return armed;
    }

    /** The keys after the walk (Walk.adjustInput, the walk not running): yours as they are, but while armed, in water
     *  and with none of yours pressed, its own. */
    static Input after(LocalPlayer p, Input keys) {
        if (!armed) return keys;
        boolean yours = keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump() || keys.shift();
        dry = p.isInWater() ? 0 : dry + 1;
        if (yours || !Settings.walkPathfinder || !p.isAlive() || over(p.onGround(), dry)) {
            armed = false;
            return keys;
        }
        Input k = during(p);
        return k == Input.EMPTY ? keys : k;
    }

    /** Out of the water for good: standing on dry ground, or out of it longer than DRY_TICKS. Ended on the first tick
     *  out of the water, it let go at the top of the first bob above the surface, and the player sank and drowned (in a
     *  lockstep test). */
    static boolean over(boolean onGround, int dryTicks) {
        return dryTicks > 0 && onGround || dryTicks > DRY_TICKS;
    }

    /** The keys on a tick of the walk's with no steering (Walk.adjustInput): none on land or standing with the eyes out
     *  of the water, else the way up to the air. */
    static Input during(LocalPlayer p) {
        boolean eyes = p.isEyeInFluid(FluidTags.WATER);
        if (!p.isInWater() || p.onGround() && !eyes) return Input.EMPTY;
        int k = eyes ? keys(blocks(p), p.getX(), p.getEyeY(), p.getZ(), p.getYRot()) : MoveState.JUMP;
        pressedTicks++;
        return WorldSnapshot.input(k);
    }

    /** The blocks round the player for the way to open water: read again after moving a block or every 10 ticks. */
    private static BlockGrid blocks(LocalPlayer p) {
        double dx = p.getX() - gridX, dy = p.getY() - gridY, dz = p.getZ() - gridZ;
        if (grid == null || ++gridAge >= 10 || dx * dx + dy * dy + dz * dz > 1.0) {
            grid = WorldSnapshot.around(Compat.level(p), p, REACH + 1, 2, UP);
            gridX = p.getX(); gridY = p.getY(); gridZ = p.getZ();
            gridAge = 0;
        }
        return grid;
    }

    /**
     * The keys that bring the eyes (at eyeY) up out of the water, at the rotation yaw: jump (swimming up) where the water
     * over them is open to the air or nothing is known above; else toward the nearest water open to the air, through the
     * water, jump held so the body keeps to the roof and comes up as soon as it is past it.
     */
    static int keys(BlockGrid g, double x, double eyeY, double z, float yaw) {
        int bx = GameMath.floor(x), by = GameMath.floor(eyeY), bz = GameMath.floor(z);
        if (above(g, bx, by, bz) != CLOSED) return MoveState.JUMP;
        int[] t = nearestOpen(g, bx, by, bz);
        if (t == null) return MoveState.JUMP;
        double tx = t[0] + 0.5 - x, tz = t[1] + 0.5 - z;
        if (Math.hypot(tx, tz) < CENTRE) return MoveState.JUMP;
        float heading = (float) Math.toDegrees(Math.atan2(-tx, tz));
        return Moves.keys(heading, yaw, Moves.WALK) | MoveState.JUMP;
    }

    static final int OPEN = 0, CLOSED = 1, UNKNOWN = 2;

    /** What is over the water from block y up in column (x, z): OPEN (the first block that isn't water is free to put the
     *  head in), CLOSED (it has a collision box: a roof, a lily pad), UNKNOWN (past the blocks read). */
    static int above(BlockGrid g, int x, int y, int z) {
        for (int k = 0; k <= UP + 2; k++) {
            if (!g.inside(x, y + k, z)) return UNKNOWN;
            BlockInfo b = g.get(x, y + k, z);
            if (b.fluid == BlockInfo.WATER_FLUID && b.shape.empty) continue;
            return b.fluid == BlockInfo.NO_FLUID && b.shape.empty && !b.is(BlockInfo.SPECIAL) ? OPEN : CLOSED;
        }
        return UNKNOWN;
    }

    /** The nearest column (by swimming, through water blocks with no collision box) whose water is open to the air or
     *  unknown above, from block (x, y, z), within REACH; {x, z} or null if none. */
    static int[] nearestOpen(BlockGrid g, int x, int y, int z) {
        int w = 2 * REACH + 1, h = UP + 2;
        int y0 = y - 1;
        boolean[] seen = new boolean[w * w * h];
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        q.add(new int[]{x, y, z});
        seen[((y - y0) * w + REACH) * w + REACH] = true;
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (above(g, c[0], c[1], c[2]) != CLOSED) return new int[]{c[0], c[2]};
            for (int[] s : steps) {
                int nx = c[0] + s[0], ny = c[1] + s[1], nz = c[2] + s[2];
                int ix = nx - x + REACH, iy = ny - y0, iz = nz - z + REACH;
                if (ix < 0 || iz < 0 || ix >= w || iz >= w || iy < 0 || iy >= h) continue;
                int i = (iy * w + ix) * w + iz;
                if (seen[i]) continue;
                seen[i] = true;
                BlockInfo b = g.get(nx, ny, nz);
                if (b.fluid != BlockInfo.WATER_FLUID || !b.shape.empty) continue;
                q.add(new int[]{nx, ny, nz});
            }
        }
        return null;
    }
}
