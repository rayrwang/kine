package ai.rrw.kine.walk;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Optional;

/**
 * The walking pathfinder in the world. Layer 1's search takes milliseconds, too fast to watch, so a search is replayed
 * after it ends, on a walk's first plan and on replans that change the way (a routine refresh toward a far goal only
 * cross-fades the route) (RW, 2026-10-13, agreed: "one color ... for the search, then magnetia for the tighneing stage
 * until it setltes into the final magneta"):
 * <ol>
 *   <li>0 to 1 s, the search in orange: the feeler, the best way so far (Replay), growing from the player toward the
 *       goal, probing and pulling back in a maze; under it the area searched so far, outlined faintly on the ground,
 *       spreading from the player and bending round obstacles;</li>
 *   <li>then the tightening in magenta: the feeler's last state, the grid path, turns into a magenta staircase that
 *       pulls taut a leg at a time from the player outward, settling into the route;</li>
 *   <li>then the route stays as rails on the ground near the player (blue), with a thin magenta line along the rest of
 *       the route and on past the loaded area to the goal; the last route's rails stay until the new ones come in.</li>
 * </ol>
 * The detailed setting shows the old display instead of the first two stages: every floor the search saw, one short
 * line from the floor it was reached from, coloured by cost so far, then the grid path in white and the route over it.
 * Everything but the rails and the outline is built once per search into GPU buffers (LineSet) and drawn with one call
 * each per frame (a replay's steps are ranges of one buffer); the rails near the player and the outline are streamed
 * per frame, a few hundred to a few thousand quads. Depth-tested, with a faint copy of the lines drawn through terrain
 * first (as the elytra destination pillar is), so they read behind hills.
 */
public final class WalkRender {
    private WalkRender() {}

    // the detailed display's timeline (s from a plan's arrival)
    static final double REPLAY_S = 2.0, GRID_AT = 2.1, ROUTE_AT = 2.6, FADE_AT = 3.4, FADE_S = 1.2;
    // the display's: the search to SEARCH_S, the feeler turning magenta over MORPH_S, the tightening over TAUT_S, then
    // the route and the rails in over SETTLE_S; the outline fades over OUTLINE_FADE_S after the search
    static final double SEARCH_S = 1.0, MORPH_S = 0.15, TAUT_S = 0.7, SETTLE_S = 0.25, OUTLINE_FADE_S = 0.6;
    static final double TAUT_AT = SEARCH_S + MORPH_S, ROUTE_IN = TAUT_AT + TAUT_S;
    static final double OUTLINE_W = 0.07;
    private static final int OUTLINE = 0x8C << 24 | (Replay.ORANGE & 0xFFFFFF);
    static final double RAIL_W = 0.1;
    static final double RAIL_NEAR = 32, RAIL_FAR = 48;     // rails full to RAIL_NEAR along the route, gone by RAIL_FAR
    static final double RAIL_BEHIND = 2.0;                 // and from this far behind the player, fading in
    static final double RAIL_SWAP = 0.3;                   // a replan's rails cross-fade over this many seconds

    // the rails' quads: depth-tested against the terrain with a bias toward the camera (as the game's own lines with
    // depth bias), so the ground they lie on doesn't break them up far away; no depth write, both faces
    static final RenderPipeline RAILS_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_rails"))
        .withCull(false)
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false, -1.0F, -1.0F))
        .build());
    static final RenderType RAILS = RenderType.create("kine_walk_rails", RenderSetup.builder(RAILS_PIPELINE).createRenderSetup());
    private static final int RAIL_TOP = (0xE0 << 24) | 0x2864FF;   // the elytra rails' blue
    static final float FAINT = 0.16F;

    // depth-tested lines; the bright frontier passes only where no dim line is already at the same depth
    static final RenderPipeline LINES = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines")).build());
    static final RenderPipeline LINES_OVER = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines_strict"))
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN, true)).build());
    static final RenderPipeline LINES_THROUGH = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Kine.MOD_ID, "pipeline/walk_lines_through"))
        .withDepthStencilState(Optional.empty()).build());

    /** One plan's drawing: its replay (uploaded on first draw) and when it arrived. */
    private static final class Shown {
        Planner.Plan plan;
        final long start = System.nanoTime();
        /** Replayed (a walk's first plan, a replan that changes the way, a preview), else the route cross-fades in. */
        final boolean replay;
        LineSet bright, dim, grid, route, beyond, feel, taut;
        boolean built = false;
        long fadeFrom = -1;     // when it fades (a newer plan replaced it; in the future while the newer one replays)
        int railHint = -1;      // the rails' last nearest segment
        Shown(Planner.Plan plan, boolean replay) { this.plan = plan; this.replay = replay; }

        void build() {
            if (built) return;
            built = true;
            Replay r = plan.replay;
            bright = r.bright.build("tree");
            dim = r.dim.build("tree dim");
            grid = r.grid.build("grid");
            route = r.route.build("route");
            beyond = r.beyond.build("beyond");
            if (replay) {
                feel = r.feel.build("feeler");
                taut = r.taut.build("tightening");
            }
        }

        /** A finer pass of the same search arrived while the search part plays: show it instead, on the same clock. */
        void swap(Planner.Plan p) {
            close();
            bright = dim = grid = route = beyond = feel = taut = null;
            built = false;
            plan = p;
        }

        void close() {
            for (LineSet s : new LineSet[]{bright, dim, grid, route, beyond, feel, taut}) if (s != null) s.close();
        }

        double age() { return (System.nanoTime() - start) / 1e9; }

        /** When the route and its rails come in, s from the arrival. */
        double routeAt() {
            if (!replay || !Settings.walkSearchDisplay) return 0;
            return Settings.walkSearchDetailed ? ROUTE_AT : ROUTE_IN;
        }
    }

    private static Shown current = null, old = null;

    /** Something more to draw each frame, after the route: the hook for layer 2's view (its candidate runs, jump arcs,
     *  chosen plan and fading replans, each built into LineSets once and drawn with the same pipelines). */
    interface Layer {
        /** Draw, the faint copy through terrain first (faint true), then depth-tested. */
        void draw(Matrix4f pose, Vec3 cam, boolean faint);
        /** Finished: dropped and closed. */
        boolean done();
        void close();
    }

    private static final java.util.List<Layer> LAYERS = new java.util.ArrayList<>();

    static void addLayer(Layer l) {
        LAYERS.add(l);
    }

    // measured
    public static long frameNanos = 0, frames = 0, maxFrameNanos = 0, uploadNanos = 0;
    /** Per part, summed over frames: the search tree and paths, the route lines, the rails, the pillar. */
    public static long treeNanos = 0, routeNanos = 0, railNanos = 0, beamNanos = 0;
    public static int lastTreeSegments = 0;

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(WalkRender::render);
    }

    /** A new plan arrived (client thread): replayed. */
    static void newPlan(Planner.Plan p) {
        newPlan(p, true);
    }

    /** A new plan arrived (client thread): replayed, or (a routine refresh) its route cross-faded in. */
    static void newPlan(Planner.Plan p, boolean replay) {
        if (current != null && current.plan.id == p.id && current.replay && replay) {
            if (current.age() < SEARCH_S) {
                current.swap(p);
                lastTreeSegments = p.replay.seenAt.length + p.replay.feel.n;
                return;
            }
            replay = false;   // a finer pass after the search part: its route cross-fades in
        }
        if (old != null) old.close();
        old = current;
        current = new Shown(p, replay && Settings.walkSearchDisplay);
        // the last route stays till the new one comes in
        if (old != null) old.fadeFrom = System.nanoTime() + (long) (current.routeAt() * 1e9);
        lastTreeSegments = p.replay.seenAt.length + p.replay.feel.n;
        replays += current.replay ? 1 : 0;
        refreshes += current.replay ? 0 : 1;
    }

    /** Seconds since the plan on show arrived (for tests), -1 if none. */
    public static double shownAge() {
        return current == null ? -1 : current.age();
    }

    /** Plans shown with a replay, and routes cross-faded in without one (for tests). */
    public static int replays = 0, refreshes = 0;

    /** The replay in progress, 0 to 1, or -1 when none (for tests). */
    public static double replayProgress() {
        if (current == null || !current.replay) return -1;
        return Math.min(1.0, current.age() / (Settings.walkSearchDetailed ? REPLAY_S : ROUTE_IN));
    }

    private static void render(LevelRenderContext ctx) {
        long t0 = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.options.hideGui) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        Matrix4f pose = ctx.poseStack().last().pose();
        boolean searchOn = Settings.walkSearchDisplay, pathOn = Settings.walkPathDisplay;
        if (current != null && !Walk.active() && !Walk.showing() && !Walk.previewing()) {
            // cancelled, or failed with no route: let it fade (a paused or arrived walk stays drawn till Cancel)
            if (current.fadeFrom < 0) current.fadeFrom = System.nanoTime();
        }
        if (current != null && (searchOn || pathOn)) {
            long u0 = System.nanoTime();
            current.build();
            if (old != null) old.build();
            uploadNanos += System.nanoTime() - u0;
        }

        // faint copies through terrain first, then depth-tested
        long a = System.nanoTime();
        if (searchOn && current != null && current.replay) drawSearch(current, pose, cam, true);
        long b = System.nanoTime();
        if (pathOn) {
            if (old != null) drawRoute(old, pose, cam, true);
            if (current != null) drawRoute(current, pose, cam, true);
        }
        long c = System.nanoTime();
        if (searchOn && current != null && current.replay) {
            drawSearch(current, pose, cam, false);
            if (!Settings.walkSearchDetailed) outline(ctx, current, pose, cam);
        }
        long d0 = System.nanoTime();
        if (pathOn) {
            if (old != null) drawRoute(old, pose, cam, false);
            if (current != null) drawRoute(current, pose, cam, false);
        }
        long e = System.nanoTime();
        if (pathOn) {
            // the new rails fade in when the route comes in (at once for a refresh, after the replay for a replay) while
            // the old fade out
            float in = current == null ? 0 : (float) Math.min(1, Math.max(0, (current.age() - current.routeAt()) / RAIL_SWAP));
            if (current != null && current.routeAt() == 0 && (old == null || old.fadeFrom < 0)) in = 1.0F;
            if (old != null && old.fadeFrom > 0) rails(ctx, old, p, pose, cam, Math.min(1 - in, fade(old)));
            if (current != null) rails(ctx, current, p, pose, cam, current.fadeFrom > 0 ? fade(current) : in);
        }
        long f = System.nanoTime();
        treeNanos += (b - a) + (d0 - c);
        routeNanos += (c - b) + (e - d0);
        railNanos += f - e;
        for (Layer l : LAYERS) l.draw(pose, cam, true);
        for (Layer l : LAYERS) l.draw(pose, cam, false);
        LAYERS.removeIf(l -> { if (l.done()) { l.close(); return true; } return false; });
        if (old != null && old.fadeFrom > 0 && (System.nanoTime() - old.fadeFrom) / 1e9 > FADE_S) { old.close(); old = null; }
        long g0 = System.nanoTime();
        WalkBeam.render(ctx);
        beamNanos += System.nanoTime() - g0;
        long d = System.nanoTime() - t0;
        frameNanos += d;
        frames++;
        maxFrameNanos = Math.max(maxFrameNanos, d);
    }

    /** The search display: the feeler, then the tightening (or the detailed display's tree and grid path). */
    private static void drawSearch(Shown s, Matrix4f pose, Vec3 cam, boolean faint) {
        if (Settings.walkSearchDetailed) { drawTree(s, pose, cam, faint); return; }
        double t = s.age();
        Replay r = s.plan.replay;
        float k = (faint ? FAINT * 1.6F : 1.0F) * (s.fadeFrom > 0 ? fade(s) : 1.0F);
        RenderPipeline pipe = faint ? LINES_THROUGH : LINES;
        if (t < TAUT_AT && s.feel != null) {
            int step = r.feelStep(t / SEARCH_S);
            float a = t < SEARCH_S ? 1.0F : (float) Math.max(0, 1 - (t - SEARCH_S) / MORPH_S);
            s.feel.drawRange(pipe, pose, cam, r.feelFrom[step], r.feelCount[step], a * k);
        }
        if (t >= SEARCH_S && s.taut != null) {
            float a = (float) Math.min(1, (t - SEARCH_S) / MORPH_S);
            if (t > ROUTE_IN) a *= (float) Math.max(0, 1 - (t - ROUTE_IN) / SETTLE_S);
            int f = r.tautStep((t - TAUT_AT) / TAUT_S);
            s.taut.drawRange(pipe, pose, cam, r.tautFrom[f], r.tautCount[f], a * k);
        }
    }

    /**
     * The area searched so far, outlined on the ground: each side of an expanded column whose neighbour isn't expanded
     * yet, a thin strip just inside it, so the outline spreads from the player and bends round obstacles. Streamed per
     * frame while it shows (the search part and its fade); a few thousand quads at most.
     */
    private static void outline(LevelRenderContext ctx, Shown s, Matrix4f pose, Vec3 cam) {
        Replay r = s.plan.replay;
        if (r.cellOrder == null) return;
        double t = s.age();
        float a = t < SEARCH_S ? 1.0F : (float) Math.max(0, 1 - (t - SEARCH_S) / OUTLINE_FADE_S);
        if (s.fadeFrom > 0) a = Math.min(a, fade(s));
        if (a <= 0.01F) return;
        int e = (int) Math.round(r.expansions * Math.min(1.0, t / SEARCH_S));
        int n = r.cellsBy(e);
        if (n == 0) return;
        int color = ((int) ((OUTLINE >>> 24) * a) << 24) | (OUTLINE & 0xFFFFFF);
        VertexConsumer vc = ctx.bufferSource().getBuffer(RAILS);
        int w = r.cellW, h = r.cellH;
        for (int j = 0; j < n; j++) {
            int idx = r.cellOrder[j], cx = idx % w, cz = idx / w;
            double x = r.cellX0 + cx, z = r.cellZ0 + cz, y = r.cellY[idx] + 0.03;
            if (cx == 0 || r.cellAt[idx - 1] >= e) quad(vc, pose, cam, x, y, z, x + OUTLINE_W, z + 1, color);
            if (cx == w - 1 || r.cellAt[idx + 1] >= e) quad(vc, pose, cam, x + 1 - OUTLINE_W, y, z, x + 1, z + 1, color);
            if (cz == 0 || r.cellAt[idx - w] >= e) quad(vc, pose, cam, x, y, z, x + 1, z + OUTLINE_W, color);
            if (cz == h - 1 || r.cellAt[idx + w] >= e) quad(vc, pose, cam, x, y, z + 1 - OUTLINE_W, x + 1, z + 1, color);
        }
        ctx.bufferSource().endBatch(RAILS);
    }

    /** A flat quad at height y over x0..x1, z0..z1. */
    private static void quad(VertexConsumer vc, Matrix4f pose, Vec3 cam, double x0, double y, double z0, double x1, double z1, int argb) {
        vertex(vc, pose, cam, x0, y, z0, argb);
        vertex(vc, pose, cam, x0, y, z1, argb);
        vertex(vc, pose, cam, x1, y, z1, argb);
        vertex(vc, pose, cam, x1, y, z0, argb);
    }

    /** The detailed display: the tree replay and the grid and tightened paths. */
    private static void drawTree(Shown s, Matrix4f pose, Vec3 cam, boolean faint) {
        double t = s.age();
        Replay r = s.plan.replay;
        float k = faint ? FAINT : 1.0F;
        float treeA = (float) (t < FADE_AT ? 1.0 : Math.max(0, 1 - (t - FADE_AT) / FADE_S));
        if (s.fadeFrom > 0) treeA = Math.min(treeA, fade(s));
        if (treeA > 0) {
            int e = (int) Math.round(r.expansions * Math.min(1.0, t / REPLAY_S));
            if (faint) {
                if (s.dim != null) s.dim.draw(LINES_THROUGH, pose, cam, r.closedBy(e), k * treeA * 0.7F);
            } else {
                if (s.dim != null) s.dim.draw(LINES, pose, cam, r.closedBy(e), treeA);
                if (s.bright != null) s.bright.draw(LINES_OVER, pose, cam, r.seenBy(e), treeA);
            }
        }
        if (t > GRID_AT && s.grid != null) {
            float a = (float) Math.min(1, (t - GRID_AT) / 0.3) * (t < FADE_AT + 0.6 ? 1 : (float) Math.max(0, 1 - (t - FADE_AT - 0.6) / FADE_S));
            if (s.fadeFrom > 0) a = Math.min(a, fade(s));
            s.grid.draw(faint ? LINES_THROUGH : LINES, pose, cam, s.grid.lines, a * k);
        }
    }

    /** The route: magenta during the replay, then a thin line on past the rails, and on past the loaded area. */
    private static void drawRoute(Shown s, Matrix4f pose, Vec3 cam, boolean faint) {
        double t = s.age(), at = s.routeAt();
        float a = at == 0 ? 1.0F : (float) Math.min(1, Math.max(0, (t - at) / (Settings.walkSearchDetailed ? 0.3 : SETTLE_S)));
        if (s.fadeFrom > 0) a = Math.min(a, fade(s));
        float k = faint ? FAINT * 1.6F : 1.0F;
        RenderPipeline pipe = faint ? LINES_THROUGH : LINES;
        boolean settled = at == 0 || t > at + (Settings.walkSearchDetailed ? 1.8 : 1.0);
        if (s.route != null) s.route.draw(pipe, pose, cam, s.route.lines, a * k * (settled ? 0.75F : 1.0F));
        if (s.beyond != null) s.beyond.draw(pipe, pose, cam, s.beyond.lines, a * k);
    }

    private static float fade(Shown s) {
        return (float) Math.max(0, Math.min(1, 1 - (System.nanoTime() - s.fadeFrom) / 1e9 / FADE_S));
    }

    /**
     * The rails near the player, from the plan's RailPath (one smooth centreline, two continuous strips over the ground):
     * from a little behind the player, where they fade in, to RAIL_NEAR along the route ahead at full strength, fading
     * out by RAIL_FAR; the alpha changes point by point, so there are no steps. The player's place on the route is
     * taken from its position between ticks, so the near end moves smoothly. A new plan's rails fade in over
     * RAIL_SWAP while the last plan's fade out, so a replan never makes them jump. Depth-tested with a bias toward the
     * camera, so the ground doesn't break them up at a distance. Streamed per frame: a few hundred quads.
     */
    private static void rails(LevelRenderContext ctx, Shown s, LocalPlayer p, Matrix4f pose, Vec3 cam, float strength) {
        RailPath rp = s.plan.rails;
        if (rp == null || rp.n < 2 || strength <= 0.01F) return;
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double px = p.xOld + (p.getX() - p.xOld) * pt, pz = p.zOld + (p.getZ() - p.zOld) * pt;
        double[] at = new double[1];
        s.railHint = rp.nearest(px, pz, s.railHint, at);
        double here = at[0];
        double from = here - RAIL_BEHIND, to = here + RAIL_FAR;
        VertexConsumer vc = ctx.bufferSource().getBuffer(RAILS);
        for (int i = 1; i < rp.n; i++) {
            if (rp.along[i] < from) continue;
            if (rp.along[i - 1] > to) break;
            for (int side = -1; side <= 1; side += 2) {
                double[] ry = side > 0 ? rp.leftY : rp.rightY, off = side > 0 ? rp.leftOff : rp.rightOff;
                // no breaks: down a drop or up a ladder the rail runs on as a steep piece
                double a1 = side * (off[i - 1] - RAIL_W / 2), a2 = side * (off[i - 1] + RAIL_W / 2);
                double b1 = side * (off[i] - RAIL_W / 2), b2 = side * (off[i] + RAIL_W / 2);
                int c0 = railColor(rp.along[i - 1] - here, ry[i - 1], cam, strength);
                int c1 = railColor(rp.along[i] - here, ry[i], cam, strength);
                if ((c0 >>> 24) < 2 && (c1 >>> 24) < 2) continue;
                vertex(vc, pose, cam, rp.x[i - 1] + rp.ax[i - 1] * a1, ry[i - 1], rp.z[i - 1] + rp.az[i - 1] * a1, c0);
                vertex(vc, pose, cam, rp.x[i] + rp.ax[i] * b1, ry[i], rp.z[i] + rp.az[i] * b1, c1);
                vertex(vc, pose, cam, rp.x[i] + rp.ax[i] * b2, ry[i], rp.z[i] + rp.az[i] * b2, c1);
                vertex(vc, pose, cam, rp.x[i - 1] + rp.ax[i - 1] * a2, ry[i - 1], rp.z[i - 1] + rp.az[i - 1] * a2, c0);
            }
        }
        ctx.bufferSource().endBatch(RAILS);
    }

    /** A rail point's colour: the rails' top colour, blue (they lie on the ground, so from below one would see the
     *  block under it, not the rail: the elytra rails' orange underside never shows, and picking it by the camera's
     *  height painted rails above eye level, on stairs, orange in patches), its alpha by its distance d along the route
     *  from the player (fading in behind, out far ahead) times the rails' strength. */
    private static int railColor(double d, double y, Vec3 cam, float strength) {
        double a = Math.min(1, Math.max(0, (d + RAIL_BEHIND) / (RAIL_BEHIND - 0.5)));
        if (d > RAIL_NEAR) a *= Math.max(0, 1 - (d - RAIL_NEAR) / (RAIL_FAR - RAIL_NEAR));
        int alpha = (int) (0xE0 * a * strength);
        return RAIL_TOP & 0xFFFFFF | (alpha << 24);
    }

    private static void vertex(VertexConsumer vc, Matrix4f mat, Vec3 cam, double x, double y, double z, int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF, a = (argb >>> 24) & 0xFF;
        vc.addVertex(mat, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).setColor(r, g, b, a);
    }
}
