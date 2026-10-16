package ai.rrw.kine.walk;

import ai.rrw.kine.Kine;
import ai.rrw.kine.move.GameMath;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Neo templates (RW 2026-10-14 22:42:38: "right maybe just do the layer 2 tmeparlet fo the neo first"): for the neos
 * the jump search's form has no way over (a straight run, one first heading, the jump, a turn toward the landing), input
 * sequences found offline in the movement copy by a wide search over every tick's input, one layout at a time, stored
 * with the standing spot and the view they start from (resource assets/kine/walk/neo_templates.json, written by
 * NeoTemplateGenTest, in the catalogue's frame from the key block: the layout's take-off, the runway's last block, where
 * it has one, else its start block, its corner at the origin; the landing and the feet on it from there too).
 *
 * <p>In play, standing on a block with a landing to reach that the jump search found no way to: each template whose
 * landing, turned by a quarter turn or more and mirrored or not (8 ways), is that landing from this block is tried in
 * the copy from the state now: a walk to its spot (Recipe.approach), the view turned to its start (keys up), then its
 * inputs, turned and mirrored the same way, with a small search round them (the whole sequence turned a few degrees
 * either way, the jump a tick sooner or later) to fit where the walk really stands. The copy decides: a template
 * whose blocks are not here simply doesn't land, so no layout check is needed beyond the landing's place. The first
 * template that lands, its best variation (the widest margin on the landing), is played as one recipe with its walk
 * there and its turn.
 */
final class NeoTemplates {
    private NeoTemplates() {}

    record Template(String id, int ldx, int ldy, int ldz, double feet, double landingFeet, double sx, double sz, float startYaw,
                    float[] yaw, int[] keys, int robust, boolean climb) {
        Template(String id, int ldx, int ldy, int ldz, double feet, double landingFeet, double sx, double sz, float startYaw,
                 float[] yaw, int[] keys, int robust) {
            this(id, ldx, ldy, ldz, feet, landingFeet, sx, sz, startYaw, yaw, keys, robust, false);
        }
    }

    static final String RESOURCE = "/assets/kine/walk/neo_templates.json";
    private static volatile List<Template> all = null;

    static List<Template> all() {
        List<Template> t = all;
        if (t == null) all = t = load();
        return t;
    }

    /** For tests: templates other than the resource's. */
    static void use(List<Template> t) {
        all = t;
    }

    static List<Template> load() {
        List<Template> out = new ArrayList<>();
        try (InputStream in = NeoTemplates.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return out;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("templates")) out.add(parse(e.getAsJsonObject()));
        } catch (Exception ex) {
            Kine.LOGGER.warn("Kine walk: neo templates not read", ex);
        }
        return out;
    }

    /** A template's share of its variations that landed in the generator (robust / variations; 0 if not given). */
    static double share(Template t) {
        Double v = SHARE.get(t);
        return v == null ? 0 : v;
    }

    private static final java.util.Map<Template, Double> SHARE = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /**
     * A recipe by a template from the state s onto one of the landings, the walk's take-off any of the key blocks: the
     * templates in the order of their share of variations that landed (the most robust first), each from each key block
     * onto each landing. RW's triple neo (2026-10-15): fitted landing by landing, a thin neo_chained template (1% of its
     * variations) landed in the copy on the platform 8 ahead and was taken before the triple's (2.6%), then dropped in
     * play; the triple's lands.
     */
    static Recipe fitAny(GroundPhysics phys, MoveState s, float sent, java.util.Collection<int[]> keys, java.util.Collection<int[]> lands) {
        List<Template> order = new ArrayList<>(all());
        order.sort((a, b) -> Double.compare(share(b), share(a)));
        for (Template t : order)
            for (int[] k : keys)
                for (int[] l : lands) {
                    Recipe r = fitTemplate(phys, s, sent, k[0], k[1], k[2], l, t, false);
                    if (r != null) return r;
                }
        return null;
    }

    static Template parse(JsonObject o) {
        JsonArray l = o.getAsJsonArray("landing"), sp = o.getAsJsonArray("spot"), ya = o.getAsJsonArray("yaw"), ka = o.getAsJsonArray("keys");
        float[] yaw = new float[ya.size()];
        int[] keys = new int[ka.size()];
        for (int i = 0; i < yaw.length; i++) yaw[i] = ya.get(i).getAsFloat();
        for (int i = 0; i < keys.length; i++) keys[i] = ka.get(i).getAsInt();
        Template made = new Template(o.get("id").getAsString(), l.get(0).getAsInt(), l.get(1).getAsInt(), l.get(2).getAsInt(),
            o.has("feet") ? o.get("feet").getAsDouble() : 1.0, o.has("landingFeet") ? o.get("landingFeet").getAsDouble() : 1.0,
            sp.get(0).getAsDouble(), sp.get(1).getAsDouble(), o.get("startYaw").getAsFloat(), yaw, keys,
            o.has("robust") ? o.get("robust").getAsInt() : 0, o.has("climb") && o.get("climb").getAsBoolean());
        if (o.has("robust") && o.has("variations") && o.get("variations").getAsInt() > 0)
            SHARE.put(made, o.get("robust").getAsDouble() / o.get("variations").getAsInt());
        return made;
    }

    // ------------------------------------------------------------------ the 8 ways a layout can lie

    /** A horizontal offset (x, z) of the template's frame in the world: mirrored (z to -z) if m, then turned r quarter
     *  turns, each (x, z) to (-z, x). */
    static double[] turn(double x, double z, int r, boolean m) {
        if (m) z = -z;
        for (int i = 0; i < (r & 3); i++) { double t = x; x = -z; z = t; }
        return new double[]{x, z};
    }

    /** A yaw of the template's frame in the world, the same way: the mirror takes yaw y to 180 - y (the direction
     *  (-sin y, cos y) to (-sin y, -cos y)), each quarter turn adds 90. */
    static float yaw(float y, int r, boolean m) {
        return (m ? 180f - y : y) + 90f * (r & 3);
    }

    /** The keys the same way: the mirror swaps left and right. */
    static int keys(int k, boolean m) {
        if (!m) return k;
        int lr = k & (MoveState.LEFT | MoveState.RIGHT);
        int sw = ((lr & MoveState.LEFT) != 0 ? MoveState.RIGHT : 0) | ((lr & MoveState.RIGHT) != 0 ? MoveState.LEFT : 0);
        return (k & ~(MoveState.LEFT | MoveState.RIGHT)) | sw;
    }

    // ------------------------------------------------------------------ fitting a template where the walk stands

    /** The small search round a template's inputs: the whole sequence turned by these degrees, and the jump moved by a
     *  tick either way. */
    static final float[] YAW_OFFSETS = {0f, 0.3f, -0.3f, 0.6f, -0.6f, 1f, -1f, 1.5f, -1.5f, 2.5f, -2.5f, 4f, -4f};
    /** And the yaws from the jump on turned by these more (the run-up and the flight fitted apart: the walk comes to
     *  rest a few hundredths off the spot, the sneak's smallest step being some 0.06). */
    static final float[] AIR_OFFSETS = {0f, 1f, -1f, 2.5f, -2.5f, 5f, -5f};
    static final int[] JUMP_SHIFTS = {0, -1, 1};
    /** The spots a little forward (and one back) of a template's own, along its start, tried when none of the
     *  variations land from the walk to its own (blocks). */
    static final double[] SPOT_SHIFTS = {0.01, 0.02, -0.01};
    /** Ticks a sequence may run past its own end to come down on the landing. */
    static final int RUN_ON = 6;

    public static int fitsTried = 0, fitsFound = 0;
    /** Templates, each with the way it lay ("id r1m"), that dropped in play this walk: not fitted again (Layer2.reset
     *  clears it). */
    static final java.util.Set<String> dropped = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * A recipe from the state s (standing on block (bx, by, bz), the view at sent) onto the landing block l by a template,
     * or null. Its how names the template, the way it lies, the variation and the walk to its spot.
     */
    static Recipe fit(GroundPhysics phys, MoveState s, float sent, int bx, int by, int bz, int[] l) {
        return fit(phys, s, sent, bx, by, bz, l, false);
    }

    /** As above; climbOnly: only the templates that end held on a climbable. */
    static Recipe fit(GroundPhysics phys, MoveState s, float sent, int bx, int by, int bz, int[] l, boolean climbOnly) {
        for (Template t : all()) {
            Recipe r = fitTemplate(phys, s, sent, bx, by, bz, l, t, climbOnly, false);
            if (r != null) return r;
        }
        // then, every template again, from spots a little forward and back of its own (fitOne): only when none fits
        // from its own spot, so a fit found before costs no more
        for (Template t : all()) {
            Recipe r = fitTemplate(phys, s, sent, bx, by, bz, l, t, climbOnly, true);
            if (r != null) return r;
        }
        return null;
    }

    /** Never let go: the fitting outside a walk's jump search. */
    static final java.util.function.BooleanSupplier NEVER = () -> false;
    /** Whether the jump search fitting now has been let go (Layer2.searchGen), per thread: set and read on the search's
     *  own thread, so the loose moves' checks (LooseCheck, on their own threads) and the walk's own search don't let go
     *  of each other's fits: every fit from then on is null at once. */
    static final ThreadLocal<java.util.function.BooleanSupplier> STOP = ThreadLocal.withInitial(() -> NEVER);

    /** One template from key block (bx, by, bz) onto l, every way it can lie; null if it doesn't fit or land. */
    static Recipe fitTemplate(GroundPhysics phys, MoveState s, float sent, int bx, int by, int bz, int[] l, Template t, boolean climbOnly) {
        return fitTemplate(phys, s, sent, bx, by, bz, l, t, climbOnly, false);
    }

    /** As above; shifted: from the spots a little off the template's own (SPOT_SHIFTS) instead of from its own. */
    static Recipe fitTemplate(GroundPhysics phys, MoveState s, float sent, int bx, int by, int bz, int[] l, Template t, boolean climbOnly,
                              boolean shifted) {
        int dx = l[0] - bx, dz = l[2] - bz;
        if (STOP.get().getAsBoolean()) return null;
        {
            if (climbOnly && !t.climb()) return null;
            // the landing's height by its feet, not its block: in play a landing's block is the one under its feet
            // rounded (Layer2: round(feet) - 1), so a trapdoor's or a carpet's (feet 0.1875 or 0.0625 up its block)
            // is the block below it, a slab's its own
            // (a held climbable landing: its block in play may be named by a node at its bottom or its top, a block
            // either way; the copy's hold check decides)
            if (Math.abs(by + t.landingFeet() - (l[1] + 1)) > (t.climb() ? 1.05 : 0.55) || Math.abs(s.y - (by + t.feet())) > 0.02) return null;
            for (int m = 0; m <= 1; m++)
                for (int r = 0; r < 4; r++) {
                    double[] o = turn(t.ldx(), t.ldz(), r, m == 1);
                    if (Math.round(o[0]) != dx || Math.round(o[1]) != dz) continue;
                    if (!dropped.isEmpty() && dropped.contains(t.id() + " r" + r + (m == 1 ? "m" : ""))) continue;
                    fitsTried++;
                    Recipe found = fitOne(phys, s, sent, bx, by, bz, l, t, r, m == 1, shifted);
                    if (found != null) { fitsFound++; return found; }
                }
        }
        return null;
    }

    /** One template lying one way: the walk to its spot, the turn, the variations; the best that lands, or null. */
    static Recipe fitOne(GroundPhysics phys, MoveState s, float sent, int bx, int by, int bz, int[] l, Template t, int r, boolean m) {
        return fitOne(phys, s, sent, bx, by, bz, l, t, r, m, false);
    }

    /** As above; shifted: only from the spots a little forward and back of its own (SPOT_SHIFTS). */
    static Recipe fitOne(GroundPhysics phys, MoveState s, float sent, int bx, int by, int bz, int[] l, Template t, int r, boolean m,
                         boolean shifted) {
        double[] sp = turn(t.sx() - 0.5, t.sz() - 0.5, r, m);
        double spx = bx + 0.5 + sp[0], spz = bz + 0.5 + sp[1];
        Recipe walk = null;
        MoveState at = s;
        float view = sent;
        if (Math.hypot(spx - s.x, spz - s.z) > 0.03 || s.vx * s.vx + s.vz * s.vz > RunModel.STILL2) {
            // a spot off the block stood on (a template's run-up starts back along a runway): the longer walk there
            walk = Math.hypot(spx - s.x, spz - s.z) > 1.0 ? Recipe.approachFar(phys, s, sent, spx, spz)
                : Recipe.approach(phys, s, sent, spx, spz);
            if (walk == null) return null;
            at = Recipe.after(phys, s, walk);
            view = walk.yaw[walk.ticks - 1];
        }
        float start = yaw(t.startYaw(), r, m);
        int n = t.keys().length;
        float[] y = new float[n];
        int[] k = new int[n];
        for (int i = 0; i < n; i++) { y[i] = yaw(t.yaw()[i], r, m); k[i] = keys(t.keys()[i], m); }
        // the feet on the landing above l's block (the template gives them from the key block it starts on; l's block
        // may be the one under a thin landing block, see fit)
        double lf = by + t.landingFeet() - l[1];
        Recipe best = null;
        double bestMargin = -1;
        if (!shifted) {
            for (float da : AIR_OFFSETS)
                for (int js : JUMP_SHIFTS)
                    for (float d : YAW_OFFSETS) {
                        Recipe v = variation(phys, at, view, start + d, y, k, d, da, js, l, lf);
                        if (v != null && v.margin > bestMargin + 1e-9) {
                            bestMargin = v.margin;
                            best = v;
                            best.how = String.format(java.util.Locale.ROOT, "template %s r%d%s yaw %+.1f air %+.1f jump %+d", t.id(), r,
                                m ? "m" : "", d, da, js);
                        }
                    }
            if (best == null) best = track(phys, at, view, start, y, k, spx, spz, l, lf);
        } else if (walk != null) {
            // the walk comes to rest a few thousandths either side of the spot, and some templates land only from one
            // side of it (the course's up_4p0p4375_c45, #93, and trapdoor_jump, #94: from rest at the spot every start
            // 5 mm or more back of it, toward the landing, missed, every one up to 20 mm forward landed; 3 of 7 places on
            // the key block fit):
            // walks to spots a little forward of it toward the landing, and one back, the variations from each
            double fx = l[0] + 0.5 - spx, fz = l[2] + 0.5 - spz, fl = Math.hypot(fx, fz);
            if (fl < 1e-6) { fx = 1; fz = 0; } else { fx /= fl; fz /= fl; }
            for (double off : SPOT_SHIFTS) {
                double tx = spx + fx * off, tz = spz + fz * off;
                Recipe w2 = Math.hypot(tx - s.x, tz - s.z) > 1.0 ? Recipe.approachFar(phys, s, sent, tx, tz) : Recipe.approach(phys, s, sent, tx, tz);
                if (w2 == null) continue;
                MoveState at2 = Recipe.after(phys, s, w2);
                float view2 = w2.yaw[w2.ticks - 1];
                for (float da : AIR_OFFSETS)
                    for (int js : JUMP_SHIFTS)
                        for (float d : YAW_OFFSETS) {
                            Recipe v = variation(phys, at2, view2, start + d, y, k, d, da, js, l, lf);
                            if (v != null && v.margin > bestMargin + 1e-9) {
                                bestMargin = v.margin;
                                best = v;
                                best.how = String.format(java.util.Locale.ROOT, "template %s r%d%s yaw %+.1f air %+.1f jump %+d (spot %+.3f)", t.id(), r,
                                    m ? "m" : "", d, da, js, off);
                            }
                        }
                if (best != null) { walk = w2; break; }
            }
        }
        if (best == null) return null;
        if (best.how.isEmpty() || best.how.startsWith("tracking"))
            best.how = String.format(java.util.Locale.ROOT, "template %s r%d%s %s", t.id(), r, m ? "m" : "", best.how);
        return walk == null ? best : Recipe.concat(walk, best);
    }

    /** Ticks a landing may take to come to rest on the landing block. */
    static final int REST = 20;

    /**
     * From a landing on l's column (state s, the view at v): 0 if it comes to rest there with keys up, else the ticks of
     * the shift key held that bring it to rest there (the generator's "stop": a fast landing, mm_45_strafe's or
     * neo_winged's, slides off the block with keys up), -1 if neither does within REST ticks.
     */
    static int rest(GroundPhysics phys, MoveState s, float v, int[] l) {
        for (int keys : new int[]{0, MoveState.SHIFT}) {
            MoveState q = s.copy();
            for (int i = 0; i <= REST; i++) {
                if (q.vx * q.vx + q.vz * q.vz <= RunModel.STILL2) return keys == 0 ? 0 : i;
                if (i == REST) break;
                phys.step(q, keys, v, 0f);
                boolean onCol = q.x + 0.3 > l[0] && q.x - 0.3 < l[0] + 1 && q.z + 0.3 > l[2] && q.z - 0.3 < l[2] + 1;
                if (!q.onGround || !onCol) break;
            }
        }
        return -1;
    }

    /** The tracking beam's width, and the turns it may add to the template's yaw each tick (on top of what it added so
     *  far: a run a little off keeps its own heading). */
    static final int TRACK_WIDTH = 80;
    static final float[] TRACK_TURNS = {0f, 0.5f, -0.5f, 1.5f, -1.5f, 4f, -4f, 10f, -10f};

    /**
     * From where the walk came to rest (a few hundredths off the spot: the sneak's smallest step is some 0.06), a beam
     * that follows the template's own path from exactly its spot (its place each tick, in the world as it is): each tick
     * the template's keys, its yaw turned by what the beam added so far plus one of TRACK_TURNS, the runs kept nearest
     * the path; the first to land (its sum of misses the least). A run-up round a wall's corner that a constant offset
     * can't fit is steered back onto the template's line. Null when the template doesn't land from its own spot here,
     * or no tracked run lands.
     */
    static Recipe track(GroundPhysics phys, MoveState at, float view, float start, float[] y, int[] k, double spx, double spz,
                        int[] l, double landingFeet) {
        MoveState ideal = at.copy();
        ideal.x = spx; ideal.z = spz; ideal.vx = 0; ideal.vz = 0;
        Recipe ref = variation(phys, ideal, start, start, y, k, 0f, 0f, 0, l, landingFeet);
        if (ref == null) return null;
        int n = ref.ticks;
        // the turn to the start, standing
        List<Float> pre = new ArrayList<>();
        MoveState s0 = at.copy();
        float v = view;
        for (int i = 0; i < 12 && Math.abs(GameMath.wrapDegrees(start - v)) > 1e-3; i++) {
            v += Math.max(-29.5f, Math.min(29.5f, GameMath.wrapDegrees(start - v)));
            phys.step(s0, 0, v, 0f);
            pre.add(v);
        }
        if (Math.abs(GameMath.wrapDegrees(start - v)) > 1e-3) return null;
        record Node(MoveState s, float view, float bias, double cost, Node parent, float yaw, int keys, boolean jumped, int after) {}
        List<Node> beam = new ArrayList<>();
        beam.add(new Node(s0, v, 0f, 0, null, v, 0, false, -1));
        Node won = null;
        double top = l[1] + landingFeet;
        for (int i = 0; i < n + RUN_ON && !beam.isEmpty() && won == null; i++) {
            List<Node> next = new ArrayList<>();
            int ri = Math.min(i, n - 1);
            for (Node nd : beam)
                for (float dt : TRACK_TURNS) {
                    float bias = nd.bias() + dt;
                    float want = ref.yaw[ri] + bias;
                    float vv = nd.view() + Math.max(-29.5f, Math.min(29.5f, GameMath.wrapDegrees(want - nd.view())));
                    int key = i < n ? ref.keys[i] : ref.keys[n - 1] & ~MoveState.JUMP;
                    MoveState q = nd.s().copy();
                    boolean jumped = nd.jumped();
                    int after = nd.after();
                    if ((key & MoveState.JUMP) != 0 && q.onGround) { jumped = true; after = 0; }
                    q.landedFall = 0.0;
                    q.touched = 0;
                    phys.step(q, key, vv, 0f);
                    if (Recipe.hurts(q) || Recipe.spoils(q)) continue;
                    if (!jumped && Recipe.offFloor(at, q)) continue;
                    if (after >= 0) after++;
                    if (q.y < l[1] - 4) continue;
                    double ex = q.x - ref.path[3 * ri], ey = q.y - ref.path[3 * ri + 1], ez = q.z - ref.path[3 * ri + 2];
                    double cost = nd.cost() + ex * ex + ez * ez + ey * ey;
                    Node c = new Node(q, vv, bias, cost, nd, vv, key, jumped, after);
                    boolean onCol = q.x + 0.3 > l[0] && q.x - 0.3 < l[0] + 1 && q.z + 0.3 > l[2] && q.z - 0.3 < l[2] + 1;
                    if (jumped && after > 2 && q.onGround && !(q.vy > 0.0)) {
                        if (onCol && Math.abs(q.y - top) <= 0.1) {
                            if ((won == null || cost < won.cost()) && rest(phys, q, vv, l) >= 0) won = c;
                            continue;
                        }
                        // down elsewhere within the inputs (a bunny hop on the runway, a momentum run's hops): on
                        // with them; past them, a miss
                        if (i >= n - 1) continue;
                    }
                    if (jumped && i == n - 1 && !q.onGround && Recipe.holdsOn(phys, q, l[0], l[1], l[2])) {
                        if (won == null || cost < won.cost()) won = c;
                        continue;
                    }
                    next.add(c);
                }
            next.sort(java.util.Comparator.comparingDouble(Node::cost));
            List<Node> kept = new ArrayList<>();
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (Node c : next) {
                MoveState q = c.s();
                long key = Math.round(q.x * 200) * 0x9E3779B97F4A7C15L ^ Math.round(q.z * 200) * 0xC2B2AE3D27D4EB4FL
                    ^ Math.round(q.y * 200) ^ Math.round(q.vx * 400) * 31 ^ Math.round(q.vz * 400) * 131;
                if (!seen.add(key)) continue;
                kept.add(c);
                if (kept.size() >= TRACK_WIDTH) break;
            }
            beam = kept;
        }
        if (won == null) return null;
        List<Node> chain = new ArrayList<>();
        for (Node c = won; c.parent() != null; c = c.parent()) chain.add(0, c);
        float[] fy = new float[pre.size() + chain.size()];
        int[] fk = new int[fy.length];
        for (int i = 0; i < pre.size(); i++) fy[i] = pre.get(i);
        for (int i = 0; i < chain.size(); i++) { fy[pre.size() + i] = chain.get(i).yaw(); fk[pre.size() + i] = chain.get(i).keys(); }
        MoveState e = won.s();
        if (e.onGround) {   // (a held climbable landing has its hold in the inputs)
            int sneak = rest(phys, e, won.view(), l);
            if (sneak > 0) {
                int m0 = fy.length;
                fy = java.util.Arrays.copyOf(fy, m0 + sneak);
                fk = java.util.Arrays.copyOf(fk, m0 + sneak);
                for (int j = 0; j < sneak; j++) { fy[m0 + j] = won.view(); fk[m0 + j] = MoveState.SHIFT; }
            }
        }
        Recipe out = Recipe.of(phys, at, fy, fk, l);
        out.margin = Math.min(Math.min(e.x - l[0], l[0] + 1 - e.x), Math.min(e.z - l[2], l[2] + 1 - e.z));
        out.how = String.format(java.util.Locale.ROOT, "tracking (miss %.3f)", Math.sqrt(won.cost() / Math.max(1, chain.size())));
        return out;
    }

    /**
     * One variation from the state at (standing, the view at `view`): keys up while the view turns to `start` (at most
     * 29.5 degrees a tick), then the inputs with every yaw turned by d and each jump moved by js ticks, run on with the
     * last keys (no jump) up to RUN_ON ticks; it lands when, after a jump, it comes down on the landing block's column
     * at its top (or onto it, held, for a climbable). Coming down elsewhere within the inputs is a step of the sequence
     * (a bunny hop on the runway); after them, a miss. Null when it doesn't land.
     */
    static Recipe variation(GroundPhysics phys, MoveState at, float view, float start, float[] y, int[] k, float d, float da,
                            int js, int[] l, double landingFeet) {
        int n = k.length;
        int[] kk = new int[n];
        for (int i = 0; i < n; i++) kk[i] = k[i] & ~MoveState.JUMP;
        for (int i = 0; i < n; i++)
            if ((k[i] & MoveState.JUMP) != 0) {
                int j = Math.max(0, Math.min(n - 1, i + js));
                kk[j] |= MoveState.JUMP;
            }
        List<Float> ys = new ArrayList<>();
        List<Integer> ks = new ArrayList<>();
        MoveState s = at.copy();
        float v = view;
        // the turn to the start, standing
        for (int i = 0; i < 12 && Math.abs(GameMath.wrapDegrees(start - v)) > 1e-3; i++) {
            v += Math.max(-29.5f, Math.min(29.5f, GameMath.wrapDegrees(start - v)));
            phys.step(s, 0, v, 0f);
            ys.add(v);
            ks.add(0);
        }
        if (Math.abs(GameMath.wrapDegrees(start - v)) > 1e-3) return null;
        boolean jumped = false;
        int after = -1;
        double top = l[1] + landingFeet;
        for (int i = 0; i < n + RUN_ON; i++) {
            float want = (i < n ? y[i] : y[n - 1]) + d + (jumped ? da : 0f);
            float dd = GameMath.wrapDegrees(want - v);
            v += Math.max(-29.5f, Math.min(29.5f, dd));
            int key = i < n ? kk[i] : kk[n - 1] & ~MoveState.JUMP;
            if ((key & MoveState.JUMP) != 0 && s.onGround) { jumped = true; after = 0; }
            s.landedFall = 0.0;
            s.touched = 0;
            phys.step(s, key, v, 0f);
            if (Recipe.hurts(s) || Recipe.spoils(s)) return null;
            if (!jumped && Recipe.offFloor(at, s)) return null;
            ys.add(v);
            ks.add(key);
            if (after >= 0) after++;
            if (s.y < l[1] - 4) return null;
            boolean onCol = s.x + 0.3 > l[0] && s.x - 0.3 < l[0] + 1 && s.z + 0.3 > l[2] && s.z - 0.3 < l[2] + 1;
            boolean landed = s.onGround && !(s.vy > 0.0);
            if (jumped && after > 2 && landed) {
                if (onCol && Math.abs(s.y - top) <= 0.1) {
                    // come to rest on it: keys up, or the shift key held (its ticks then part of the recipe)
                    int sneak = rest(phys, s, v, l);
                    if (sneak < 0) return null;
                    for (int j = 0; j < sneak; j++) { phys.step(s, MoveState.SHIFT, v, 0f); ys.add(v); ks.add(MoveState.SHIFT); }
                    break;
                }
                // down elsewhere within the inputs (the triple neo's bunny hop on its runway, a momentum run's hops): on
                // with them; past them, a miss
                if (i >= n - 1) return null;
            }
            // a climbable landing: held on it in its column when the template's inputs (their hold) end
            if (jumped && i == n - 1 && !s.onGround && Recipe.holdsOn(phys, s, l[0], l[1], l[2])) break;
            if (i == n + RUN_ON - 1) return null;
        }
        float[] fy = new float[ys.size()];
        int[] fk = new int[ks.size()];
        for (int i = 0; i < fy.length; i++) { fy[i] = ys.get(i); fk[i] = ks.get(i); }
        Recipe out = Recipe.of(phys, at, fy, fk, l);
        out.margin = Math.min(Math.min(s.x - l[0], l[0] + 1 - s.x), Math.min(s.z - l[2], l[2] + 1 - s.z));
        return out;
    }
}
