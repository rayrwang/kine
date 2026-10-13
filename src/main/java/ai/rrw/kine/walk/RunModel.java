package ai.rrw.kine.walk;

import ai.rrw.kine.fight.Moves;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.GameMath;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.search.Model;
import ai.rrw.kine.search.RecedingSearch;

import java.util.SplittableRandom;

/**
 * Layer 2 as a {@link Model} for the receding-horizon search: runs of the game's own movement keys, simulated tick by
 * tick on the exact movement copy (walking, sprinting, jumping, swimming, climbing), scored by the time they take and
 * where they end on layer 1's map of the time left ({@link Field}). Nothing is scripted: no recipe for a sprint-jump, a
 * jump out of water or a gap; a run that does one scores better only because the copy says it gets further.
 *
 * <p>An action is a heading and a gait, held for a segment of ticks. The heading is fixed at the segment's first tick
 * from where the run is then: toward the point the field's cheapest moves lead to (2.5 blocks on), that turned by
 * 15, 35, 70, 110 or 180 degrees either way, or toward the best floor within 6 blocks in a straight line (a jump over a
 * gap, a corner cut). The gaits: walk (W), sprint (W and sprint), sprint-jump (W, sprint, jump), jump (W and jump:
 * swimming up, a careful hop), sneak (W and sneak: on land slow, with the game's edge stop; in water it goes down), and
 * sprint with sneak (in water a dive); or no keys: 67 actions. Segments are 4 ticks on land and 8 in water (a horizon
 * of 20 and 40 ticks: from floating at the top a sprint swim pays only after sinking until the eyes are under). Each
 * tick the rotation sent turns toward the heading by at most 29.5
 * degrees (as the kill aura turns it, so no faster than a human), and the keys are the W/A/S/D combination nearest the
 * heading at that rotation (Moves.keys), as the executor will press them. In water the sprint-jump gait is a swim
 * upward (sprint, the pitch 25 degrees up, no jump) and the pitch is otherwise level; on land the view's pitch is
 * kept.
 *
 * <p>The cost: one per tick until the run arrives (within half a block of the destination's centre, on the ground, in
 * water or on a ladder, as the walk counts arriving), plus marks: a fall that would hurt, lava, fire or another block
 * that hurts, an attempt to glide, all {@link #UNSAFE}; movement the copy leaves out (a current, slime, the edge of the
 * snapshot) {@link #UNCOPIED}. The end-of-plan estimate: a run that ends in the air is carried on (the same keys,
 * without jump) until it lands; then the field's time at the landing.
 *
 * <p>The beam search starts from the best of a few whole plans scored first ({@link #plan}): the last plan shifted, each
 * gait held, and a slow start (no keys, walking or sneaking) followed by a sprint or sprint-jump; its own first depths
 * see too little of the horizon to find a start that pays only later.
 */
final class RunModel implements Model<RunWorld> {
    static final float TURN = 29.5F;
    static final double UNSAFE = 1000.0, UNCOPIED = 40.0, OFF_FIELD = 300.0;
    /** The segment length in water: a sprint swim pays only after sinking until the eyes are under, about 35 ticks
     *  from floating at the top (measured on the copy), so the horizon there is 5 segments of 8 ticks. */
    static final int WATER_HOLD = 8;

    static final float[] OFFSETS = {15, -15, 35, -35, 70, -70, 110, -110, 180};
    static final int DESCENT = 0, SHORTCUT = 1, DIRS = 2 + OFFSETS.length;
    /** The gaits: W; W and sprint; W, sprint and jump; W and jump; W and sneak; W, sprint and sneak (in water sneak
     *  goes down: RW, 2026-10-12, "yeha dadd shift", so the search can sink below the top to start a sprint swim). The
     *  first four without the sneaking two (BASE_GAITS) for measuring what sneak adds. */
    static final int WALK = 0, SPRINT = 1, SPRINT_JUMP = 2, JUMP = 3, SNEAK = 4, SPRINT_SNEAK = 5, GAITS = 6, BASE_GAITS = 4;
    /** Elytra hops (only with the setting on and a glider worn, Body.glide): W, and jump pressed afresh in the air to
     *  open the glider (released a tick first if held); the pitch level-ish, down or up. */
    static final int GLIDE = 6, GLIDE_DOWN = 7, GLIDE_UP = 8, GLIDE_GAITS = 9;
    static final float[] GLIDE_PITCH = {5f, 30f, -20f};
    /** What a tick of gliding costs on top of its time: the elytra's wear (a point of durability every second). */
    static final double GLIDE_WEAR = 0.5;
    /** Actions: 0 is no keys; then gait-major, a = 1 + gait * DIRS + dir, so a model with fewer gaits is a prefix. */
    static final int STOP = 0, COUNT = 1 + DIRS * GAITS;

    static int dir(int a) { return a == STOP ? -1 : (a - 1) % DIRS; }
    static int gait(int a) { return a == STOP ? -1 : (a - 1) / DIRS; }
    static int action(int dir, int gait) { return 1 + gait * DIRS + dir; }

    final GroundPhysics phys;
    final Field field;
    final double goalX, goalZ;
    final float viewPitch;
    final double maxFall;
    /** Record each run's feet for the display. */
    boolean trails;

    private final MoveState scratch = new MoveState();

    RunModel(GroundPhysics phys, Field field, int goalX, int goalZ, float viewPitch, double maxFall) {
        this.phys = phys;
        this.field = field;
        this.goalX = goalX + 0.5;
        this.goalZ = goalZ + 0.5;
        this.viewPitch = viewPitch;
        this.maxFall = maxFall;
    }

    @Override public RunWorld copy(RunWorld w) { return w.copy(); }

    /** How many gaits the search may use (GAITS, or BASE_GAITS without sneaking, for measurement). */
    int gaits = GAITS;

    @Override public int actionCount() { return 1 + DIRS * gaits; }

    @Override
    public double step(RunWorld w, int a, int tickInSegment, SplittableRandom rnd) {
        if (w.arrived) return 0.0;
        MoveState s = w.s;
        if (tickInSegment == 0 || Float.isNaN(w.segYaw)) w.segYaw = a == STOP ? w.sentYaw : heading(s, dir(a));
        w.segPitch = pitch(a, s.inWater);
        turn(w);
        int keys = keys(a, w.segYaw, w.sentYaw, s.inWater, s.onGround, s.fallFlying, s.lastKeys);
        double y0 = s.y, fall0 = s.fallDistance;
        boolean air0 = !s.onGround;
        phys.step(s, keys, w.sentYaw, w.sentPitch);
        w.lastKeys = keys;
        if (w.ticks == 0) { w.firstYaw = w.segYaw; w.firstPitch = w.segPitch; w.firstKeys = keys; }
        w.ticks++;
        w.record();
        double c = 1.0 + marks(s, air0, y0, fall0) + (s.fallFlying ? GLIDE_WEAR : 0.0);
        if (arrived(s)) w.arrived = true;
        return c;
    }

    /** In water the sprint-jump gait swims up: sprint with the pitch this far up, and no jump (a jump at the top
     *  ends the swim). Measured on the copy: sprint swimming keeps 0.196 blocks a tick at any pitch (level, or along
     *  the top with the pitch up), against 0.116 swimming along the top with W and jump. */
    static final float SWIM_UP_PITCH = -25f;

    /** The pitch sent for an action: in water level, or up for the swim-up gait; a glide's own; else the view's. */
    float pitch(int a, boolean inWater) {
        return pitch(a, inWater, viewPitch);
    }

    static float pitch(int a, boolean inWater, float viewPitch) {
        int g = a == STOP ? -1 : gait(a);
        if (g >= GLIDE && !inWater) return GLIDE_PITCH[g - GLIDE];
        if (!inWater) return viewPitch;
        return g == SPRINT_JUMP ? SWIM_UP_PITCH : 0f;
    }

    /** The keys for an action with this heading at the rotation sent, from the state now (in water, on the ground,
     *  gliding, and last tick's keys, for a glide's fresh jump press). */
    static int keys(int a, float heading, float sentYaw, boolean inWater, boolean onGround, boolean fallFlying, int lastKeys) {
        if (a == STOP) return 0;
        int g = gait(a);
        int k = Moves.keys(heading, sentYaw, Moves.WALK);
        if (g >= GLIDE) {
            // jump pressed afresh: off the ground, then in the air to open the glider (a held jump is let go first)
            if (!fallFlying && !inWater && (lastKeys & MoveState.JUMP) == 0) k |= MoveState.JUMP;
            return k;
        }
        if ((g == SPRINT || g == SPRINT_JUMP || g == SPRINT_SNEAK) && (k & MoveState.FORWARD) != 0) k |= MoveState.SPRINT;
        if (g == JUMP || g == SPRINT_JUMP && !inWater) k |= MoveState.JUMP;
        if (g == SNEAK || g == SPRINT_SNEAK) k |= MoveState.SHIFT;
        return k;
    }

    /** The rotation sent turns toward the segment's heading and pitch, at most TURN degrees of mouse travel. */
    static void turn(RunWorld w) {
        float dy = GameMath.wrapDegrees(w.segYaw - w.sentYaw), dp = w.segPitch - w.sentPitch;
        double len = Math.sqrt((double) dy * dy + (double) dp * dp);
        if (len <= TURN) { w.sentYaw += dy; w.sentPitch = w.segPitch; return; }
        double f = TURN / len;
        w.sentYaw += (float) (dy * f);
        w.sentPitch += (float) (dp * f);
    }

    /** Whether the walk would count the player as arrived. */
    boolean arrived(MoveState s) {
        double dx = goalX - s.x, dz = goalZ - s.z;
        return dx * dx + dz * dz <= 0.25 && (s.onGround || s.inWater || phys.onClimbable(s));
    }

    /** This tick's marks: a hurting landing, hurting blocks, a glide, movement the copy leaves out. */
    private double marks(MoveState s, boolean air0, double y0, double fall0) {
        double c = 0;
        if (air0 && s.onGround && !s.inWater && fall0 + Math.max(0.0, y0 - s.y) > maxFall) c += UNSAFE;
        if ((s.touched & BlockInfo.HAZARD) != 0) c += UNSAFE;
        if ((s.touched & BlockInfo.UNCOPIED) != 0) c += UNCOPIED;
        if (s.wouldGlide) c += UNSAFE;
        s.touched = 0;
        s.wouldGlide = false;
        return c;
    }

    /** The world yaw of a direction from where the run is: toward the field's way on, turned by an offset, or toward
     *  the best floor in a straight line. */
    float heading(MoveState s, int d) {
        float base = descent(s);
        if (d == DESCENT) return base;
        if (d == SHORTCUT) {
            int j = field.shortcut(s.x, s.y, s.z, 6.0);
            return j < 0 ? base : yawTo(s, field.x(j), field.z(j));
        }
        return GameMath.wrapDegrees(base + OFFSETS[d - 2]);
    }

    /** Toward the point the cheapest moves from here lead to; the goal when near it or off the field. */
    float descent(MoveState s) {
        double gd = Math.hypot(goalX - s.x, goalZ - s.z);
        if (field.endsAtGoal && gd < 2.5) return yawTo(s, goalX, goalZ);
        int i = field.nodeAt(s.x, s.y, s.z);
        double[] aim = i >= 0 ? field.aim(i, s.x, s.z, 2.5) : null;
        if (aim == null) return yawTo(s, goalX, goalZ);
        return yawTo(s, aim[0], aim[2]);
    }

    static float yawTo(MoveState s, double x, double z) {
        double dx = x - s.x, dz = z - s.z;
        if (dx * dx + dz * dz < 1e-9) return 0f;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    static boolean airborne(GroundPhysics phys, MoveState s) {
        return !s.onGround && !s.inWater && !phys.onClimbable(s);
    }

    @Override
    public double estimate(RunWorld w) {
        if (w.arrived) return 0.0;
        MoveState s = w.s;
        double extra = 0;
        if (airborne(phys, s)) {
            // carried on until it lands: the same keys without jump, the rotation going on toward the heading
            MoveState r = scratch;
            r.set(s);
            int keys = w.lastKeys & ~MoveState.JUMP;
            float yaw = w.sentYaw, pitch = w.sentPitch;
            int t = 0;
            while (airborne(phys, r) && t < (r.fallFlying ? 60 : 40)) {
                double y0 = r.y, fall0 = r.fallDistance;
                phys.step(r, keys, yaw, pitch);
                t++;
                extra += 1.0 + marks(r, true, y0, fall0);
                if (arrived(r)) return extra;
                float dy = GameMath.wrapDegrees(w.segYaw - yaw);
                yaw += Math.max(-TURN, Math.min(TURN, dy));
            }
            if (airborne(phys, r)) {
                if (!r.fallFlying) return extra + UNSAFE;
                // still gliding: count from the floor it is over, a tick a block of height to come down
                int j = field.below(r.x, r.y, r.z);
                return j < 0 ? extra + OFF_FIELD : extra + field.value(j) + (r.y - field.y(j));
            }
            s = r;
        }
        double v = field.at(s.x, s.y, s.z);
        if (Double.isNaN(v)) return extra + OFF_FIELD + Math.hypot(goalX - s.x, goalZ - s.z) * field.rate;
        return extra + v;
    }

    /**
     * One replan: the beam search, started from the best of the last plan (shifted by the ticks since) and each gait
     * held the whole horizon toward the field's way on and toward the shortcut. The search keeps that line and can only
     * improve on it, so a plan whose gain shows only after a few ticks (sinking until the eyes are under water, then
     * sprint swimming) isn't lost to the beam's short view at its first depths.
     */
    static RecedingSearch.Result plan(RunModel model, RecedingSearch<RunWorld> rs, RunWorld root, int[] previous, int shift,
                                      RecedingSearch.Effort e, long seed) {
        int h = e.horizon();
        int[] best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        if (previous != null && previous.length > shift) {
            int[] p = new int[h];
            for (int i = 0; i < h; i++) p[i] = previous[Math.min(i + shift, previous.length - 1)];
            double sc = rs.score(root, p, e, seed);
            if (sc < bestScore) { bestScore = sc; best = p; }
        }
        int gaits = model.gaits;
        for (int d = 0; d < 2; d++)
            for (int g = 0; g < gaits; g++) {
                int[] p = new int[h];
                java.util.Arrays.fill(p, action(d, g));
                double sc = rs.score(root, p, e, seed);
                if (sc < bestScore - 1e-9) { bestScore = sc; best = p; }
            }
        // a slow start that pays later, toward the way on: no keys, walking or sneaking for one or two segments, then
        // a sprint or a sprint-jump held (sinking before a sprint swim, which the beam's first depths can't see pay)
        int[] slow = gaits > SNEAK ? new int[]{STOP, action(DESCENT, WALK), action(DESCENT, SNEAK), action(DESCENT, SPRINT_SNEAK)}
            : new int[]{STOP, action(DESCENT, WALK)};
        for (int a1 : slow)
            for (int g2 : new int[]{SPRINT, SPRINT_JUMP})
                for (int k = e.hold; k < h && k <= 2 * e.hold; k += e.hold) {
                    int[] p = new int[h];
                    java.util.Arrays.fill(p, 0, k, a1);
                    java.util.Arrays.fill(p, k, h, action(DESCENT, g2));
                    double sc = rs.score(root, p, e, seed);
                    if (sc < bestScore - 1e-9) { bestScore = sc; best = p; }
                }
        return rs.plan(root, best, 0, e, seed);
    }

    static String name(int a) {
        if (a == STOP) return "stop";
        int d = dir(a), g = gait(a);
        String dn = d == DESCENT ? "on" : d == SHORTCUT ? "short" : (OFFSETS[d - 2] > 0 ? "+" : "") + (int) OFFSETS[d - 2];
        return dn + switch (g) {
            case GLIDE -> "-glide";
            case GLIDE_DOWN -> "-glidedown";
            case GLIDE_UP -> "-glideup";
            case WALK -> "-walk";
            case SPRINT -> "-sprint";
            case SPRINT_JUMP -> "-sprintjump";
            case JUMP -> "-jump";
            case SNEAK -> "-sneak";
            default -> "-sprintsneak";
        };
    }
}
