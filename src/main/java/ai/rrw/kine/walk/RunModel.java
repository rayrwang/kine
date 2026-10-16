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

    /** The height the walk keeps while it has no route to a goal above (Walk.keepAbove, set each tick; NaN: none): a
     *  tick ending on the ground or in water under it is UNSAFE (a run of layer 2's dropped into the sea under the goal,
     *  the Parkour Spiral). Taken by each model as it is made. */
    static volatile double keepAboveNow = Double.NaN;
    final double keepAbove = keepAboveNow;
    /** Air (RW, 2026-10-12: "ti donst take into acount my air bubles ... the serach shoudl also acocutn for hte air
     *  bubles/damage"): drowning damage costs DROWN_COST ticks a point of health; a run that ends with the eyes under
     *  water must leave the air to reach air again (the ticks to the nearest open water top, straight up or a few blocks
     *  across) with AIR_RESERVE to spare, the shortfall counted as the drowning it would bring; and each tick with the
     *  eyes under water costs AIR_TICK, so of two equal ways the one breathing wins (a sprint swim held at the top with
     *  the eyes out is as fast as one under it). A run that ends with the eyes under water has the way back up to the air
     *  ahead of it: UNDER_END of its ticks counted (settled), so the search keeps the head up where nothing else is to
     *  be had. Without it, at the floor nearest a goal it can't reach in a pond, every run scored about the same and the
     *  one letting the player sink often won: the walk stood on the bottom with the eyes under until it gave up there
     *  (pondIslandFar in a lockstep test; RW 2026-10-14: "the layer 2 search got me stuck at the bottom of a bpond and i
     *  started dorwnign"). AIR_TICK alone is too small for that (under the field's unevenness of about 2 ticks), and
     *  made larger it charges every tick under water, wherever the run ends. */
    static final double DROWN_COST = 60.0, AIR_RESERVE = 30.0, AIR_TICK = 0.02, RISE_TICKS = 8.0, UNDER_END = 0.25;
    /** Count the air (on; off only to measure what it changes). */
    boolean airAware = true;
    /** Parkour (the setting): off, a run that crosses a gap in the air and lands on its far side is UNSAFE (gapCheck). */
    boolean parkour = true;
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

    static int dir(int a) { return a == STOP ? -1 : isFree(a) ? -1 : (a - 1) % DIRS; }
    static int gait(int a) { return a == STOP ? -1 : isFree(a) ? freeGait(a) : (a - 1) / DIRS; }
    static int action(int dir, int gait) { return 1 + gait * DIRS + dir; }

    /**
     * Free actions, near a jump (RW, 2026-10-13: layer 2 "only has sdicrete angles ... maybe nee dmore ocntinous"): any
     * heading to the degree, or toward the jump's landing recomputed every tick, with a gait and a strafe (the view
     * turned 45 degrees off the heading, the 45 strafe); turning every tick, so a jump can start on any tick and turn in
     * the air. Not in the beam's own set: offered as whole plans when a jump is ahead (plan).
     */
    static final int FREE_BASE = 1000, FREE_TOWARD = 360;
    static boolean isFree(int a) { return a >= FREE_BASE; }
    static int free(int gait, int strafe, int headingCode) { return FREE_BASE + ((gait * 3 + strafe + 1) * 361) + headingCode; }
    static int freeGait(int a) { return (a - FREE_BASE) / 361 / 3; }
    static int freeStrafe(int a) { return (a - FREE_BASE) / 361 % 3 - 1; }
    static int freeCode(int a) { return (a - FREE_BASE) % 361; }
    static int headingCode(float yaw) { return Math.floorMod(Math.round(yaw) + 180, 360); }

    /** The jump ahead the free actions aim at (the landing's centre and floor), or NaN when none. */
    double targetX = Double.NaN, targetY, targetZ;
    /** Replans that offered free plans (for tests). */
    int freeSeeds;
    /** The free search's memory toward this landing (kept by the caller between replans; null: none), and the tick
     *  on the walk's clock. */
    FreeMemory freeMemory;
    int now;
    /** The time the free plans took to score, and how many were scored (all replans of this model). */
    long freeNanos;
    int freeRollouts;

    /** A free action's movement heading from where the run is. */
    float freeHeading(int a, MoveState s) {
        int c = freeCode(a);
        if (c == FREE_TOWARD && !Double.isNaN(targetX)) return yawTo(s, targetX, targetZ);
        return c == FREE_TOWARD ? descent(s) : c - 180f;
    }

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

    /** The gait setting (Settings.GAIT_*: the most the walk may use). Walk: the sprinting gaits left out, and in the copy
     *  no sprint key, the double-tap window closed and a running sprint ended each tick, as the walk's keys do in the
     *  game (Walk.underGait). Walk and Sprint: a jump (at Sprint, one made while sprinting) that lands level with
     *  nothing under it to cross (a pace jump, for speed alone, or at Walk for nothing) costs PACE. Sprint-jump and
     *  above: everything. */
    int cap = ai.rrw.kine.Settings.GAIT_SPRINT_JUMP;
    /** A pace jump at the gait setting's Sprint: as five seconds, so walking on is always better where it can be. */
    static final double PACE = 100.0;

    /** No sprint-jump gait in the beam (Layer2: lava under the ground near the player); free actions keep theirs. */
    boolean noSprintJumps = false;

    @Override public boolean allowed(int a) {
        if (noSprintJumps && a != STOP && !isFree(a) && gait(a) == SPRINT_JUMP) return false;
        if (cap >= ai.rrw.kine.Settings.GAIT_SPRINT || a == STOP || isFree(a)) return true;
        int g = gait(a);
        return g != SPRINT && g != SPRINT_JUMP && g != SPRINT_SNEAK;
    }

    /** The keys as the gait allows them, and (at Walk) the sprint the game would not start or keep. */
    private int underGait(MoveState s, int keys) {
        if (cap >= ai.rrw.kine.Settings.GAIT_SPRINT) return keys;
        s.sprinting = false;
        s.sprintTriggerTime = 0;
        return keys & ~MoveState.SPRINT;
    }

    /**
     * The gait setting's Walk and Sprint: a jump the ground doesn't need. p: {the height a jump made (at Sprint, while
     * sprinting) left the
     * ground from (NaN: none), 1 once the ground under it fell away more than a jump's height}. A jump needs no pace
     * check when it lands higher (a step up, a block), crosses a gap (the floor under it more than JUMP_MAX below the
     * take-off) or leaves or reaches water or a climbable; one that comes down level or lower with solid ground under it
     * all the way could have been walked: PACE.
     */
    double paceCheck(MoveState s, boolean air0, double y0, int keys, double[] p) {
        if (cap > ai.rrw.kine.Settings.GAIT_SPRINT) return 0.0;
        // at Walk any jump (one while walking gains nothing, so it is for the ground or for nothing); at Sprint one
        // made while sprinting
        boolean counts = cap == ai.rrw.kine.Settings.GAIT_WALK || s.sprinting;
        // off the ground with the jump key down: a jump, even one a ceiling cut short at once (a head hitter: the bonk
        // leaves no upward speed, and the jump is all the faster for it)
        if (!air0 && !s.onGround && (keys & MoveState.JUMP) != 0 && counts && !s.inWater) {
            p[0] = y0;
            p[1] = 0;
            return 0.0;
        }
        if (Double.isNaN(p[0])) return 0.0;
        if (s.inWater || phys.onClimbable(s)) { p[0] = Double.NaN; return 0.0; }
        if (!s.onGround) {
            if (p[1] == 0 && floorUnder(s.x, s.y, s.z, p[0]) < p[0] - Graph.JUMP_MAX - 1e-3) p[1] = 1;
            return 0.0;
        }
        boolean pace = p[1] == 0 && s.y < p[0] + 0.5;
        p[0] = Double.NaN;
        return pace ? PACE : 0.0;
    }

    @Override public int actionCount() { return 1 + DIRS * gaits; }

    @Override
    public double step(RunWorld w, int a, int tickInSegment, SplittableRandom rnd) {
        if (w.arrived) return 0.0;
        MoveState s = w.s;
        if (isFree(a)) {
            // every tick: the heading, and the view 45 degrees off it for a strafe
            w.segHeading = freeHeading(a, s);
            w.segYaw = w.segHeading - freeStrafe(a) * 45f;
        } else {
            if (tickInSegment == 0 || Float.isNaN(w.segYaw)) w.segYaw = a == STOP ? w.sentYaw : heading(s, dir(a));
            w.segHeading = Float.NaN;
        }
        w.segPitch = pitch(a, s.inWater);
        turn(w);
        int keys = underGait(s, keys(a, isFree(a) ? w.segHeading : w.segYaw, w.sentYaw, s.inWater, s.onGround, s.fallFlying, s.lastKeys));
        double y0 = s.y, fall0 = s.fallDistance, drowned0 = s.drownDamage;
        boolean air0 = !s.onGround;
        phys.step(s, keys, w.sentYaw, w.sentPitch);
        w.lastKeys = keys;
        if (w.ticks == 0) { w.firstYaw = w.segYaw; w.firstPitch = w.segPitch; w.firstKeys = keys; w.firstHeading = w.segHeading; }
        w.ticks++;
        w.record();
        double c = 1.0 + marks(s, air0, y0, fall0) + (s.fallFlying ? GLIDE_WEAR : 0.0);
        if (!allowed(a)) c += UNSAFE;   // a plan from before the gait changed
        if (cap <= ai.rrw.kine.Settings.GAIT_SPRINT) {
            double[] pc = {w.paceFrom, w.paceDeep};
            c += paceCheck(s, air0, y0, keys, pc);
            w.paceFrom = pc[0]; w.paceDeep = pc[1];
        }
        if (!parkour) {
            double[] g = {w.takeoffY, w.gapFloor, w.gapValue};
            c += gapCheck(s, g);
            w.takeoffY = g[0]; w.gapFloor = g[1]; w.gapValue = g[2];
        }
        if (airAware) {
            c += (s.drownDamage - drowned0) * DROWN_COST;
            if (s.eyesInWater && s.waterBreathingTicks < 0) c += AIR_TICK;
        }
        if (arrived(s)) {
            if (!stand || still(s)) w.arrived = true;
            else c -= 1.0 - SETTLE_COST;   // on the hop's floor, not yet still: a cheap tick
        }
        return c;
    }

    /** In water the sprint-jump gait swims up: sprint with the pitch this far up, and no jump (a jump at the top
     *  ends the swim). Measured on the copy: sprint swimming keeps 0.196 blocks a tick at any pitch, against 0.116
     *  swimming along the top with W and jump; held at the top with the pitch 25 to 40 up the body floats with the eyes
     *  just out of the water (no gravity while sprinting in water), breathing at full speed (RW: "its possble to still
     *  rpint , just with yrou palyer a bit hgiher so yorue also briehating"); 30 up leaves the eyes 0.15 clear. */
    static final float SWIM_UP_PITCH = -30f;
    /** The swim-up pitch in use: SWIM_UP_PITCH (Layer2AirTest sets the old -25 for a moment to measure the old way). */
    static float swimUpPitch = SWIM_UP_PITCH;

    /** The pitch sent for an action: in water level, or up for the swim-up gait; a glide's own; else the view's. */
    float pitch(int a, boolean inWater) {
        return pitch(a, inWater, viewPitch);
    }

    static float pitch(int a, boolean inWater, float viewPitch) {
        int g = a == STOP ? -1 : gait(a);
        if (g >= GLIDE && !inWater) return GLIDE_PITCH[g - GLIDE];
        if (!inWater) return viewPitch;
        return g == SPRINT_JUMP ? swimUpPitch : 0f;
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

    /** Whether the walk would count the player as arrived (at any speed). */
    boolean arrived(MoveState s) {
        double dx = goalX - s.x, dz = goalZ - s.z;
        // climbing counts on a ladder or a vine (it holds), not in scaffolding (2026-10-15: inside its top piece the
        // player sinks to the piece under once the keys are up; the top is arrived at standing on it)
        return dx * dx + dz * dz <= 0.25 && (s.onGround || s.inWater || phys.onClimbable(s) && !phys.inScaffolding(s))
            && (Double.isNaN(goalY) || Math.abs(s.y - goalY) < 0.6);
    }

    /** Standing still: on the ground with horizontal speed squared at most STILL2 (as Walk.standing); in water or on a
     *  climbable, any. */
    boolean still(MoveState s) {
        return !s.onGround || s.inWater || phys.onClimbable(s) || s.vx * s.vx + s.vz * s.vz <= STILL2;
    }

    /**
     * A hop's floor (Walk: walking without a route to a floor past where a far goal's route stops): the walk counts it
     * reached only standing still on it (the next jump starts from a standing start), so a run arrives only once still
     * (horizontal speed squared at most STILL2, as Walk.standing), and a tick on the floor before that, moving, costs
     * SETTLE_COST instead of a whole tick. Without it every run counted as arrived the tick it came within half a block
     * of the centre, at any speed, and cost nothing after: all ties, and the plan kept was the shifted last one, walking
     * toward the centre the player already stood on, the keys flipping every tick for the 30 ticks until the walk's
     * settle rule gave up waiting (RW 2026-10-14 17:25:43: "when it landso on a blaock it can vibrate in spot for a
     * second or two before making the next jump"; walk 2 of final3d_pk39: 30 ticks of it at nearly every hop). The moving
     * ticks are cheap, not whole. Set only for a replan from a state already on the floor (Layer2: settling there); a
     * run toward it from elsewhere arrives as before, at any speed: judged by standing, a landing at speed on a small
     * floor (an end rod) slides off it in the copy under the run's held keys, so every flight toward the rod looked
     * unsafe, and the replans in flight released the keys or sneaked and came down short (full3_c1 walk 1 and dev3_c3,
     * end_rod_jump).
     */
    boolean stand = false;
    static final double STILL2 = 1e-4, SETTLE_COST = 0.25;

    /** The destination's Y (feet): only that floor of the goal column is arriving (within 0.6); NaN: any floor. */
    double goalY = Double.NaN;

    /** This tick's marks: a hurting landing, hurting blocks, a glide, movement the copy leaves out. */
    private double marks(MoveState s, boolean air0, double y0, double fall0) {
        double c = 0;
        // a landing on slime (the shift key up) costs nothing, on a bed, a hay bale or honey a share of the fall
        // (Block.fallOn's overrides: the landing's fall factor, Costs.fallLimit)
        double fall = fall0 + Math.max(0.0, y0 - s.y);
        if (air0 && s.onGround && !s.inWater && (s.touched & BlockInfo.SLIME) == 0
            && fall > Costs.fallLimit(maxFall, s.landedFallFactor)) c += UNSAFE;
        if ((s.onGround || s.inWater) && s.y < keepAbove - 1e-3) c += UNSAFE;
        if ((s.touched & BlockInfo.HAZARD) != 0) c += UNSAFE;
        // a landing on the edge of what holds it over lava (within EDGE_MARGIN): live it comes down a hundredth off the
        // copy's and slides off (RW's far walk cleg1, f61: layer 2 hopping the slabs over the Parkour Spiral's lava field)
        if (air0 && s.onGround && !s.inWater && phys.edgeOverHazard(s, EDGE_MARGIN, 6.0)) c += UNSAFE;
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

    /**
     * Parkour off: a jump across a gap. In the air, the column under the player is a gap when its floor is more than
     * a jump (1.25) below the take-off, or it holds something that hurts (lava, fire), or nothing at all within 5
     * below; water under it is safe to fall into and not a gap. Landing on the ground more than a jump above the
     * lowest gap floor crossed is a crossing: UNSAFE (less than that, the lower floor is a step down the slope, not a
     * gap: a drop down a hillside sails over the next ledge below and lands on this one). Landing in the gap is a drop
     * (the fall's own mark applies). Nor is it a gap when the floor crossed is on the way as much as the landing is
     * (its time left in the field no more than the landing's): a steep slope of drops, the arc down over the next ledge
     * to the one above it, lands no worse off than falling short would have. g: {take-off height, lowest gap floor
     * (NaN for none), its time left (NaN: not in the field)}, updated; the take-off is the root's floor (Layer2: the
     * last height the player stood at), never a height in the air.
     */
    double gapCheck(MoveState s, double[] g) {
        if (s.onGround || s.inWater || phys.onClimbable(s)) {
            double c = 0.0;
            if (!Double.isNaN(g[1]) && s.onGround && s.y > g[1] + Graph.JUMP_MAX + 1e-4) {
                double landing = field == null ? Double.NaN : field.at(s.x, s.y, s.z);
                boolean onTheWay = !Double.isNaN(g[2]) && !Double.isNaN(landing) && g[2] <= landing + 1e-6;
                if (!onTheWay) c = UNSAFE;
            }
            g[0] = s.y;
            g[1] = Double.NaN;
            g[2] = Double.NaN;
            return c;
        }
        if (Double.isNaN(g[0])) g[0] = s.y;
        double floor = floorUnder(s.x, s.y, s.z, g[0]);
        if (floor < g[0] - Graph.JUMP_MAX - 1e-3 && (Double.isNaN(g[1]) || floor < g[1])) {
            g[1] = floor;
            g[2] = Double.isInfinite(floor) || field == null ? Double.NaN : field.at(s.x, floor, s.z);
        }
        return 0.0;
    }

    /** The floor under (x, z) below the feet at y: the top of the first block with a collision, scanning down to 5
     *  below the take-off; water or a climbable there counts as a floor at the take-off's height (safe to fall into,
     *  held on to), something that hurts as no floor; NEGATIVE_INFINITY when there is none. */
    private double floorUnder(double x, double y, double z, double takeoff) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int top = (int) Math.floor(y), bottom = (int) Math.floor(takeoff) - 5;
        for (int by = top; by >= bottom; by--) {
            ai.rrw.kine.move.BlockInfo b = phys.grid().get(bx, by, bz);
            if ((b.flags & ai.rrw.kine.move.BlockInfo.HAZARD) != 0) return Double.NEGATIVE_INFINITY;
            if ((b.flags & ai.rrw.kine.move.BlockInfo.WATER) != 0) return takeoff;
            // a ladder or a vine in the column is held on to, not fallen past: no gap (climbing off a vine's top onto
            // the wall it hangs on looked like a crossing over the vine's column, UNSAFE with Parkour off, and the walk
            // hung at the top: the vine5off scene, "Stuck")
            if ((b.flags & ai.rrw.kine.move.BlockInfo.CLIMBABLE) != 0) return takeoff;
            if (!b.shape.empty && by + b.shape.top() <= y + 1e-6) return by + b.shape.top();
        }
        return Double.NEGATIVE_INFINITY;
    }

    /** Ticks on a ladder before a run carried on to its landing counts as caught by it. */
    static final int CAUGHT = 8;

    /** Still to be carried on: in the air, or on a ladder for fewer than CAUGHT ticks and not standing. */
    private boolean unsettled(MoveState s, int heldOnLadder) {
        if (s.onGround || s.inWater) return false;
        return !phys.onClimbable(s) || heldOnLadder < CAUGHT;
    }

    @Override
    public double estimate(RunWorld w) {
        if (w.arrived) return 0.0;
        MoveState s = w.s;
        if (airborne(phys, s) || phys.onClimbable(s) && !s.onGround && (Math.abs(s.vx) > 0.15 || Math.abs(s.vz) > 0.15)) {
            // carried on until it lands: the same keys without jump, the rotation going on toward the heading; or with
            // the keys let go, whichever comes down better (a run's next segment can let go: off a ledge with a steep
            // slope below, walking on carries past the next step to a fall that hurts, letting go lands on it; scored
            // as walking on, the walk off the ledge lost to standing at its top, which stood for good)
            int keys = w.lastKeys & ~MoveState.JUMP;
            double on = carriedOn(w, keys);
            return keys == 0 ? on : Math.min(on, carriedOn(w, 0));
        }
        // on the ground still moving: letting go it slides on about SLIDE times its speed (ground friction 0.546 a
        // tick); a slide off into lava is UNSAFE. A run ending so had layer 2 land on a loose take-off's edge going on
        // at 0.25 a tick over the Parkour Spiral's lava field and slide off (RW's far walk cleg1, f54: dead, twice)
        double v2 = s.vx * s.vx + s.vz * s.vz;
        if (s.onGround && !s.inWater && v2 > SLIDE_CHECK2 && phys.slidesIntoHazard(s, s.vx * SLIDE, s.vz * SLIDE, 6.0))
            return settled(s, UNSAFE);
        return settled(s, 0.0);
    }

    /** A run's end on the ground: the slide's length over the speed, and the least speed (squared) looked at. */
    static final double SLIDE = 2.2, SLIDE_CHECK2 = 0.02 * 0.02;
    /** How far the box must reach over what it lands on, every way, where lava lies under the edge (blocks). */
    static final double EDGE_MARGIN = 0.1;

    /** The time left once a run in the air (or swinging on a ladder) comes down, carried on with these keys. */
    private double carriedOn(RunWorld w, int keys) {
        MoveState r = scratch;
        r.set(w.s);
        double extra = 0;
        float yaw = w.sentYaw, pitch = w.sentPitch;
        int t = 0, held = 0;
        double[] gap = {w.takeoffY, w.gapFloor, w.gapValue}, pace = {w.paceFrom, w.paceDeep};
        // on a ladder it lets go of the keys (it slides down, held in the ladder's cell); caught once it has been on
        // one for CAUGHT ticks (a jump through a ladder's cell at speed can fly on out of it and fall)
        while (unsettled(r, held) && t < (r.fallFlying ? 60 : 40)) {
            double y0 = r.y, fall0 = r.fallDistance;
            boolean ladder = phys.onClimbable(r);
            int k = underGait(r, ladder ? 0 : keys);
            phys.step(r, k, yaw, pitch);
            held = phys.onClimbable(r) ? held + 1 : 0;
            t++;
            extra += 1.0 + marks(r, true, y0, fall0);
            extra += paceCheck(r, true, y0, k, pace);
            if (!parkour) extra += gapCheck(r, gap);
            if (arrived(r)) return extra;
            float dy = GameMath.wrapDegrees(w.segYaw - yaw);
            yaw += Math.max(-TURN, Math.min(TURN, dy));
        }
        if (unsettled(r, held)) {
            if (!r.fallFlying) return extra + UNSAFE;
            // still gliding: count from the floor it is over, a tick a block of height to come down
            int j = field.below(r.x, r.y, r.z);
            return j < 0 ? extra + OFF_FIELD : extra + field.value(j) + (r.y - field.y(j));
        }
        return settled(r, extra);
    }

    /** The time left from a state on the ground (or in water): the field's, the air short, and with the eyes under
     *  water a part of the way back up (UNDER_END). */
    private double settled(MoveState s, double extra) {
        if (airAware) extra += airShort(s) + (s.eyesInWater && s.waterBreathingTicks < 0 ? UNDER_END * ticksToAir(s) : 0.0);
        double v = field.at(s.x, s.y, s.z);
        if (Double.isNaN(v)) return extra + Math.max(OFF_FIELD + Math.hypot(goalX - s.x, goalZ - s.z) * field.rate, offFieldFloor);
        return extra + v;
    }

    /** The least a state off the field costs (Layer2: the field's value where the run starts, and OFF_FIELD more; 0 when
     *  the start is off it too). Off the field the cost was OFF_FIELD and the straight distance to the goal: a route that
     *  first goes back, away from the goal, valued more than that from its start, so stepping off it toward the goal
     *  scored better, and each time the walk turned back along the route it turned round again (RW's playtest of
     *  ed08088: leg 3 on the bottom level by the sweet berry bushes, leg 1 walked from leg 0's start). */
    double offFieldFloor = 0.0;

    /** With the eyes under water: the drowning a shortfall of air would bring, as cost (none while there's the air to
     *  reach air again with the reserve to spare). The air lasts until it reaches -20. */
    double airShort(MoveState s) {
        if (!s.eyesInWater || s.waterBreathingTicks >= 0) return 0.0;
        double need = ticksToAir(s) + AIR_RESERVE;
        double have = s.air + 20.0;
        if (need <= have) return 0.0;
        return DROWN_COST * 2.0 * (need - have) / 20.0;
    }

    /** Ticks to get the eyes out of water: up to the open top of the water in this column, or, under a ceiling, across
     *  to the nearest column within 3 that has one (a sprint swim's 5.1 ticks a block across, RISE_TICKS a block up);
     *  200 if none. */
    double ticksToAir(MoveState s) {
        double eyeY = s.y + (s.swimPose || s.glidePose ? phys.body().swimEyeHeight : s.crouchPose ? phys.body().crouchEyeHeight
            : phys.body().eyeHeight);
        int bx = GameMath.floor(s.x), bz = GameMath.floor(s.z), by = GameMath.floor(eyeY);
        double best = Double.MAX_VALUE;
        for (int r = 0; r <= 3 && best == Double.MAX_VALUE; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    double top = openTop(bx + dx, by, bz + dz);
                    if (Double.isNaN(top)) continue;
                    double t = 3.0 + Math.hypot(dx, dz) * 5.1 + Math.max(0.0, top + 0.05 - eyeY) * RISE_TICKS;
                    best = Math.min(best, t);
                }
        return best == Double.MAX_VALUE ? 200.0 : best;
    }

    /** How far up the water's top is looked for (Layer2's blocks reach two past it, up to 32 above the feet). */
    static final int TOP_SCAN = 40;

    /** The height of the water's top in column (x, z) above block y, where air is over it; NaN under a ceiling or
     *  with no water top within TOP_SCAN (it was 16: from deeper the way up was not seen). */
    private double openTop(int x, int y, int z) {
        BlockInfo b = phys.grid().get(x, y, z);
        if (b.fluid != BlockInfo.WATER_FLUID) return b.shape.empty ? y : Double.NaN;   // eyes' block not water: there
        for (int k = 1; k <= TOP_SCAN; k++) {
            BlockInfo up = phys.grid().get(x, y + k, z);
            if (up.fluid == BlockInfo.WATER_FLUID) continue;
            if (!up.shape.empty || up.is(BlockInfo.SPECIAL)) return Double.NaN;   // a ceiling (or the snapshot's edge)
            BlockInfo top = phys.grid().get(x, y + k - 1, z);
            return y + k - 1 + top.fluidHeight;
        }
        return Double.NaN;
    }

    /**
     * One replan: the beam search, started from the best of the last plan (shifted by the ticks since), each gait held
     * the whole horizon toward the field's way on and the shortcut, and each jump held in every heading. The search
     * keeps that line and can only
     * improve on it, so a plan whose gain shows only after a few ticks (sinking until the eyes are under water, then
     * sprint swimming) isn't lost to the beam's short view at its first depths.
     */
    static RecedingSearch.Result plan(RunModel model, RecedingSearch<RunWorld> rs, RunWorld root, int[] previous, int shift,
                                      RecedingSearch.Effort e, long seed) {
        long t0 = System.nanoTime();
        int h = e.horizon();
        int[] best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        if (previous != null && previous.length > shift) {
            int[] p = new int[h];
            for (int i = 0; i < h; i++) p[i] = previous[Math.min(i + shift, previous.length - 1)];
            double sc = rs.score(root, p, e, seed);
            if (sc < bestScore) { bestScore = sc; best = p; }
        }
        // each gait held the whole horizon toward the way on and the shortcut; and every jump in every direction: the
        // beam scores a first move by where it is 4 ticks on, and drops a jump that pays only when it lands (one up off
        // to the side under a tree's leaves, half way and still under them at 4 ticks: the benchmark's seed 369819014
        // stood for good), so each goes in whole, scored to the end
        int gaits = model.gaits;
        for (int d = 0; d < DIRS; d++)
            for (int g = 0; g < gaits; g++) {
                if (d >= 2 && g != SPRINT_JUMP && g != JUMP) continue;
                if (!model.allowed(action(d, g))) continue;
                int[] p = new int[h];
                java.util.Arrays.fill(p, action(d, g));
                double sc = rs.score(root, p, e, seed);
                if (sc < bestScore - 1e-9) { bestScore = sc; best = p; }
            }
        // a slow start that pays later, toward the way on: no keys, walking or sneaking for one or two segments, then
        // a sprint or a sprint-jump held (sinking before a sprint swim, which the beam's first depths can't see pay)
        int[] slow = gaits > SNEAK ? new int[]{STOP, action(DESCENT, WALK), action(DESCENT, SNEAK), action(DESCENT, SPRINT_SNEAK)}
            : new int[]{STOP, action(DESCENT, WALK)};
        int[] fast = model.allowed(action(DESCENT, SPRINT)) ? new int[]{SPRINT, SPRINT_JUMP} : new int[]{WALK, JUMP};
        for (int a1 : slow)
            for (int g2 : fast)
                for (int k = e.hold; k < h && k <= 2 * e.hold; k += e.hold) {
                    if (!model.allowed(a1)) continue;
                    int[] p = new int[h];
                    java.util.Arrays.fill(p, 0, k, a1);
                    java.util.Arrays.fill(p, k, h, action(DESCENT, g2));
                    double sc = rs.score(root, p, e, seed);
                    if (sc < bestScore - 1e-9) { bestScore = sc; best = p; }
                }
        if (!Double.isNaN(model.targetX)) {
            long f0 = System.nanoTime();
            Free f = new Free(model, rs, root, e, seed, h, t0 + e.budgetNanos / 2);
            f.search(model.freeMemory, model.now);
            if (f.score < Free.MISSES && f.score < bestScore - 1e-9) { bestScore = f.score; best = f.plan; }
            model.freeSeeds++;
            model.freeRollouts += f.rollouts;
            model.freeNanos += System.nanoTime() - f0;
        }
        // one budget for the whole replan: the beam gets what the plans above left (a quarter of it at least)
        RecedingSearch.Effort eb = e.copy();
        eb.budgetNanos = Math.max(e.budgetNanos / 4, e.budgetNanos - (System.nanoTime() - t0));
        return rs.plan(root, best, 0, eb, seed);
    }

    /**
     * What the free search keeps between replans toward one landing: the best plans it found (the heading, the jump's
     * tick on the walk's clock, the gait, the strafe) and how far through its grid it got.
     */
    static final class FreeMemory {
        static final int KEEP = 3;
        final long target;
        final float[] yaw = new float[KEEP];
        final int[] jumpAt = new int[KEEP], gait = new int[KEEP], strafe = new int[KEEP];
        int n, cursor;

        FreeMemory(long target) { this.target = target; }

        static long key(double x, double y, double z) {
            return ((long) Math.floor(x) << 40) ^ ((long) Math.floor(z) << 16) ^ (long) Math.floor(y);
        }
    }

    /**
     * A jump ahead: free plans (any heading near the landing's bearing, the jump on any tick, then toward the landing,
     * every tick), scored whole. In the air: toward the landing with each strafe, or a heading held a few ticks first.
     * On the ground: the best plans the last replans found (at their ticks now), a slice of the grid (walking or
     * sprinting, a heading every 6 degrees over 30 either way, the jump on each tick to 7: 176 plans, GRID_SLICE a
     * replan, spread over it, so the whole grid is tried every few ticks), then round the best (each strafe, a degree
     * or two either side, the jump a tick either side). Stops at its deadline with the best so far.
     */
    static final class Free {
        static final int GRID_SLICE = 60, GRID = 2 * 11 * 8;
        final RunModel model;
        final RecedingSearch<RunWorld> rs;
        final RunWorld root;
        final RecedingSearch.Effort e;
        final long seed, deadline;
        final int h, jMax;
        final float bearing;
        int[] plan;
        double score = Double.POSITIVE_INFINITY;
        float yaw;
        int j, gait = SPRINT, strafe, rollouts;
        boolean late;
        /** This replan's best few, for the memory. */
        final double[] topScore = new double[FreeMemory.KEEP];
        final float[] topYaw = new float[FreeMemory.KEEP];
        final int[] topJ = new int[FreeMemory.KEEP], topGait = new int[FreeMemory.KEEP], topStrafe = new int[FreeMemory.KEEP];
        int topN;

        Free(RunModel model, RecedingSearch<RunWorld> rs, RunWorld root, RecedingSearch.Effort e, long seed, int h, long deadline) {
            this.model = model; this.rs = rs; this.root = root; this.e = e; this.seed = seed; this.h = h; this.deadline = deadline;
            this.jMax = Math.min(h - 1, 7);
            this.bearing = yawTo(root.s, model.targetX, model.targetZ);
            this.yaw = bearing;
        }

        /** What a plan that never stands on the landing pays in this search's ranking: such a plan is not offered
         *  (toward a landing behind a wall it would only press into the wall), only searched round. */
        static final double MISSES = 1e4;
        private final boolean[] hit = new boolean[1];
        private final java.util.function.Consumer<RunWorld> onLanding = this::seen;

        private void seen(RunWorld w) {
            if (hit[0]) return;
            MoveState q = w.s;
            hit[0] = w.arrived || q.onGround && Math.abs(q.y - model.targetY) < 0.1
                && Math.abs(q.x - model.targetX) < 0.8 && Math.abs(q.z - model.targetZ) < 0.8;
        }

        /** A plan's score in this search: its score, plus MISSES if it never stands on the landing. */
        double scoreOf(int[] p) {
            hit[0] = false;
            double sc = rs.score(root, p, e, seed, onLanding);
            return hit[0] ? sc : sc + MISSES;
        }

        /** Score one free plan before its jump; false once past the deadline. */
        boolean tryJump(float y, int jt, int g, int st) {
            if (late || System.nanoTime() > deadline) { late = true; return false; }
            rollouts++;
            int[] p = freeJump(h, g, st, y, jt);
            double sc = scoreOf(p);
            if (sc < score - 1e-9) { score = sc; plan = p; yaw = y; j = jt; gait = g; strafe = st; }
            keep(sc, y, jt, g, st);
            return true;
        }

        /** Among this replan's best few (one entry for each heading, jump tick, gait and strafe). */
        void keep(double sc, float y, int jt, int g, int st) {
            for (int k = 0; k < topN; k++)
                if (topYaw[k] == y && topJ[k] == jt && topGait[k] == g && topStrafe[k] == st) return;
            int at = topN < FreeMemory.KEEP ? topN++ : FreeMemory.KEEP - 1;
            if (at == FreeMemory.KEEP - 1 && topN == FreeMemory.KEEP && sc >= topScore[at]) return;
            topScore[at] = sc; topYaw[at] = y; topJ[at] = jt; topGait[at] = g; topStrafe[at] = st;
            for (int k = at; k > 0 && topScore[k] < topScore[k - 1]; k--) {
                double ts = topScore[k]; topScore[k] = topScore[k - 1]; topScore[k - 1] = ts;
                float ty = topYaw[k]; topYaw[k] = topYaw[k - 1]; topYaw[k - 1] = ty;
                int t = topJ[k]; topJ[k] = topJ[k - 1]; topJ[k - 1] = t;
                t = topGait[k]; topGait[k] = topGait[k - 1]; topGait[k - 1] = t;
                t = topStrafe[k]; topStrafe[k] = topStrafe[k - 1]; topStrafe[k - 1] = t;
            }
        }

        void search(FreeMemory mem, int now) {
            if (!root.s.onGround) {
                for (int st = -1; st <= 1; st++) if (!tryAir(st, Float.NaN, 0)) return;
                for (int k = 2; k <= 6; k += 2)
                    for (float off = -30f; off <= 30f; off += 10f)
                        if (off != 0f && !tryAir(0, bearing + off, k)) return;
                return;
            }
            search:
            {
                // what the last replans found, at their ticks now
                if (mem != null)
                    for (int k = 0; k < mem.n; k++) {
                        int jt = mem.jumpAt[k] - now;
                        if (jt >= 0 && jt <= jMax && !tryJump(mem.yaw[k], jt, mem.gait[k], mem.strafe[k])) break search;
                    }
                // a slice of the grid, spread over it, from where the last replan stopped
                int from = mem != null ? mem.cursor : 0;
                for (int i = 0; i < GRID_SLICE; i++) {
                    int c = (int) ((from + i) * 61L % GRID);
                    int g = c / 88 == 0 ? SPRINT : WALK, off = (c % 88) / 8, jt = c % 8;
                    if (jt > jMax) continue;
                    if (!tryJump(bearing - 30f + 6f * off, jt, g, 0)) break search;
                    if (mem != null) mem.cursor = (from + i + 1) % GRID;
                }
                // round the best
                float y0 = yaw;
                int j0 = j, g0 = gait;
                for (int st = -1; st <= 1; st++)
                    for (float off = -2f; off <= 2f; off += 1f)
                        for (int jt = Math.max(0, j0 - 1); jt <= Math.min(jMax, j0 + 1); jt++)
                            if (!tryJump(y0 + off, jt, g0, st)) break search;
            }
            if (mem != null) {
                mem.n = topN;
                for (int k = 0; k < topN; k++) {
                    mem.yaw[k] = topYaw[k]; mem.jumpAt[k] = topJ[k] + now; mem.gait[k] = topGait[k]; mem.strafe[k] = topStrafe[k];
                }
            }
        }

        boolean tryAir(int st, float y, int k) {
            if (late || System.nanoTime() > deadline) { late = true; return false; }
            rollouts++;
            int[] p = freeAir(h, st, y, k);
            double sc = scoreOf(p);
            if (sc < score - 1e-9) { score = sc; plan = p; }
            return true;
        }
    }

    /** A free plan in the air: the heading yaw held k ticks (none if NaN), then toward the landing, at a sprint. */
    static int[] freeAir(int h, int strafe, float yaw, int k) {
        int[] p = new int[h];
        int code = Float.isNaN(yaw) ? FREE_TOWARD : headingCode(yaw);
        for (int i = 0; i < h; i++) p[i] = free(SPRINT, strafe, i < k ? code : FREE_TOWARD);
        return p;
    }

    /** A free plan: the gait toward yaw for j ticks, the jump on tick j, then toward the landing at a sprint. */
    static int[] freeJump(int h, int g, int strafe, float yaw, int j) {
        int[] p = new int[h];
        int code = headingCode(yaw);
        for (int i = 0; i < h; i++) {
            if (i < j) p[i] = free(g, strafe, code);
            else if (i == j) p[i] = free(g == WALK ? JUMP : SPRINT_JUMP, strafe, code);
            else p[i] = free(SPRINT, strafe, FREE_TOWARD);
        }
        return p;
    }

    static String name(int a) {
        if (a == STOP) return "stop";
        if (isFree(a)) {
            int c = freeCode(a);
            return (c == FREE_TOWARD ? "toward" : "yaw" + (c - 180)) + (freeStrafe(a) != 0 ? (freeStrafe(a) < 0 ? "-left" : "-right") : "")
                + "-" + switch (freeGait(a)) { case WALK -> "walk"; case SPRINT -> "sprint"; case SPRINT_JUMP -> "sprintjump";
                                                case JUMP -> "jump"; default -> "g" + freeGait(a); };
        }
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
