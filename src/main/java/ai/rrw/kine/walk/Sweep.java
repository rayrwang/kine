package ai.rrw.kine.walk;

import ai.rrw.kine.move.ShapeProbe;

/**
 * The walk-straight check: sweeps the player's box along a straight line over the table, as the tightening (and the
 * search, for links between floors that aren't plain) asks "can the player walk from here to there in a straight
 * line?". At every step along the line (every STEP blocks):
 * <ul>
 *   <li>support: the highest collision top under the box, shrunk by the safety margin on each side (so a player up to
 *       the margin off the line still has it), within a step up (0.6) above the current height and a safe fall (3)
 *       below; a rise of more than 0.6 is not a walk (a jump), and nothing within 3 below is a fall that hurts;</li>
 *   <li>after a drop of more than a step the player is in the air for a while, carried on by its speed: until it would
 *       land (the fall's time at sprint speed in the air) the ground must not rise into it and the whole fall must stay
 *       within 3;</li>
 *   <li>room: no collision box (the game's own shapes, through the movement copy's overlap test) in the box grown by
 *       the margin, from a step above the ground to 1.8 above it (from the higher of before and after, in a fall);</li>
 *   <li>no hazard (lava, fire, cactus, berry bushes...) or block to avoid in any block the grown box touches, nothing
 *       hurting underfoot, no water or ladder at the feet (those are other moves), nothing unknown.</li>
 * </ul>
 * It also times the walk from the blocks underfoot (their friction and speed factor: the steady speed the game's
 * ground movement settles at) and records the ground's height along the line, for drawing.
 */
final class Sweep {
    static final double STEP = 0.2;
    static final double HALF = 0.3;            // half the player's width
    static final double STEP_UP = 0.6;
    static final double HEIGHT = 1.8;
    /** Horizontal speed in the air while sprinting, blocks a tick (0.026 a tick of push against 0.91 drag, so a
     *  sprinting player keeps about 0.28 off an edge); the longest a fall can carry it. */
    static final double AIR_SPEED = 0.29;

    final View v;
    final Costs costs;
    double margin;
    boolean canSprint = true;
    /** Let the walk end in a ladder's column (the search's links onto a ladder's foot); the tightening keeps it off. */
    boolean allowClimb = false;

    // results of the last walk
    double time;          // ticks
    double endH;          // ground height at the end
    int samples;          // ground heights recorded, if asked
    double[] sx = new double[256], sh = new double[256], sz = new double[256];
    boolean record;
    String why = "";

    private final double[] box = new double[6];
    private Kind groundKind;

    Sweep(View v, Costs costs, double margin) {
        this.v = v;
        this.costs = costs;
        this.margin = margin;
    }

    /** Walk from (x0, h0, z0) straight to (x1, z1). True if walkable; then endH is the height there and time the ticks. */
    boolean walk(double x0, double h0, double z0, double x1, double z1) {
        time = 0;
        samples = 0;
        why = "";
        double dx = x1 - x0, dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dz * dz);
        int n = Math.max(1, (int) Math.ceil(len / STEP));
        double step = len / n;
        double in = Math.max(0.05, HALF - margin), out = HALF + margin;
        double h = h0;                       // the feet: on the ground, or falling
        double fallFrom = Double.NaN, sAir = 0;
        if (record) sample(x0, h0, z0);
        for (int k = 1; k <= n; k++) {
            double x = x0 + dx * k / n, z = z0 + dz * k / n;
            boolean air = !Double.isNaN(fallFrom);
            double base = air ? fallFrom : h;
            // safety: the box shrunk by the margin has ground within a safe fall (so does any box within the margin)
            if (Double.isNaN(ground(x, z, in, h + STEP_UP, base - costs.maxFall - 1e-6))) {
                why = "no ground within a safe fall"; return false;
            }
            // physics: the player's own box stands on the highest top under it
            double g = ground(x, z, HALF, h + STEP_UP, base - costs.maxFall - 1e-6);
            if (groundKind.is(Kind.FLOOR_HAZARD) || groundKind.blocksBody()) { why = "hurts to stand on"; return false; }
            double upper;
            if (air) {
                // carried on through the air at sprint speed: the feet follow the fall's curve until they meet the ground
                sAir += step;
                double y = fallFrom - Costs.fallDistance(sAir / AIR_SPEED);
                upper = fallFrom + HEIGHT;
                if (g >= y - 1e-6) {
                    if (g > y + STEP_UP + 1e-6) { why = "ground rises into a fall"; return false; }
                    fallFrom = Double.NaN;
                    h = g;
                } else {
                    h = y;
                }
                time += step / AIR_SPEED;
            } else if (g > h + STEP_UP + 1e-6) {
                why = "a rise of more than a step"; return false;
            } else if (g < h - STEP_UP - 1e-6) {
                fallFrom = h;           // off an edge: falling from here (h stays until the curve says otherwise)
                sAir = 0;
                upper = h + HEIGHT;
                time += step / AIR_SPEED;
            } else {
                upper = Math.max(h, g) + HEIGHT;
                h = g;
                time += step / speed(groundKind);
            }
            // room for the body, the box grown by the margin
            if (!clear(x, z, out, g + STEP_UP, upper)) { why = "blocked"; return false; }
            // hazards in any block the grown box touches, from the floor block up
            if (hazardNear(x, z, out, (int) Math.floor(g - 1e-4), (int) Math.floor(upper - 1e-4))) { why = "hazard"; return false; }
            // no water or climbable at the feet: those are other moves
            if (feetSpecial(x, z, in, g)) { why = "water or ladder"; return false; }
            if (record) sample(x, g, z);
        }
        if (!Double.isNaN(fallFrom)) { why = "ends in the air"; return false; }
        endH = h;
        return true;
    }

    private void sample(double x, double h, double z) {
        if (samples == sx.length) {
            sx = java.util.Arrays.copyOf(sx, samples * 2);
            sh = java.util.Arrays.copyOf(sh, samples * 2);
            sz = java.util.Arrays.copyOf(sz, samples * 2);
        }
        sx[samples] = x; sh[samples] = h; sz[samples] = z;
        samples++;
    }

    /** The highest collision top under the square of half width `half` at (x, z), between `lowest` and `top`; NaN if
     *  none (or something unknown under it). Sets groundKind. */
    double ground(double x, double z, double half, double top, double lowest) {
        int xa = (int) Math.floor(x - half), xb = (int) Math.floor(x + half - 1e-9);
        int za = (int) Math.floor(z - half), zb = (int) Math.floor(z + half - 1e-9);
        int ya = (int) Math.floor(lowest), yb = (int) Math.floor(top);
        double best = Double.NaN;
        groundKind = null;
        for (int bx = xa; bx <= xb; bx++)
            for (int bz = za; bz <= zb; bz++)
                for (int y = yb; y >= ya; y--) {
                    Kind k = v.block(bx, y, bz);
                    if (k == Palette.UNKNOWN) return Double.NaN;
                    if (k.empty()) continue;
                    double t = k.topWithin(x - half - bx, x + half - bx, z - half - bz, z + half - bz, top - y);
                    if (Double.isNaN(t)) continue;
                    double wt = y + t;
                    if (wt < lowest) continue;
                    if (Double.isNaN(best) || wt > best) { best = wt; groundKind = k; }
                    break;   // lower blocks in this column are under this one
                }
        return best;
    }

    /** No collision box in the square of half width `half` at (x, z) between heights lo and hi. */
    boolean clear(double x, double z, double half, double lo, double hi) {
        if (hi <= lo) return true;
        box[0] = x - half; box[1] = lo + 1e-7; box[2] = z - half;
        box[3] = x + half; box[4] = hi - 1e-7; box[5] = z + half;
        int xa = (int) Math.floor(box[0]), xb = (int) Math.floor(box[3]);
        int za = (int) Math.floor(box[2]), zb = (int) Math.floor(box[5]);
        int ya = (int) Math.floor(box[1]), yb = (int) Math.floor(box[4]);
        for (int bx = xa; bx <= xb; bx++)
            for (int bz = za; bz <= zb; bz++)
                for (int y = ya; y <= yb; y++) {
                    Kind k = v.block(bx, y, bz);
                    if (k == Palette.UNKNOWN) return false;
                    if (ShapeProbe.overlaps(k.info.shape, box, bx, y, bz)) return false;
                }
        return true;
    }

    /** A block that hurts or is to be avoided in any block the square touches, block heights y0 to y1. */
    boolean hazardNear(double x, double z, double half, int y0, int y1) {
        int xa = (int) Math.floor(x - half), xb = (int) Math.floor(x + half - 1e-9);
        int za = (int) Math.floor(z - half), zb = (int) Math.floor(z + half - 1e-9);
        for (int bx = xa; bx <= xb; bx++)
            for (int bz = za; bz <= zb; bz++)
                for (int y = y0; y <= y1; y++) {
                    Kind k = v.block(bx, y, bz);
                    if (k.blocksBody()) return true;
                }
        return false;
    }

    /** Water or a climbable block at the feet under the (shrunk) square. */
    boolean feetSpecial(double x, double z, double half, double g) {
        int y = (int) Math.floor(g + 1e-4);
        int xa = (int) Math.floor(x - half), xb = (int) Math.floor(x + half - 1e-9);
        int za = (int) Math.floor(z - half), zb = (int) Math.floor(z + half - 1e-9);
        for (int bx = xa; bx <= xb; bx++)
            for (int bz = za; bz <= zb; bz++) {
                Kind k = v.block(bx, y, bz);
                if (k.is(Kind.WATER)) return true;
            }
        if (allowClimb) return false;
        Kind c = v.block((int) Math.floor(x), y, (int) Math.floor(z));
        return c.is(Kind.CLIMB);
    }

    private final double[] speedSprint = new double[64], speedWalk = new double[64];

    /** Blocks a tick on this floor at the steady speed: LivingEntity.travel pushes 0.98 of the speed (sprint 0.13, walk
     *  0.1, times 0.216 / friction^3), Entity.move scales the velocity by the block's speed factor, and friction keeps
     *  friction * 0.91 of it, so each tick covers push / (1 - speedFactor * friction * 0.91). */
    double speed(Kind k) {
        double[] cache = canSprint ? speedSprint : speedWalk;
        int id = k.id;
        if (id < cache.length && cache[id] > 0) return cache[id];
        float fr = k.info.friction, sf = k.info.speedFactor;
        double push = (canSprint ? 0.13 : 0.1) * 0.98 * (0.21600002 / (fr * fr * fr));
        double s = push / (1 - sf * fr * 0.91);
        if (id < cache.length) cache[id] = s;
        return s;
    }
}
