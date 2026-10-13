package ai.rrw.kine.walk;

import ai.rrw.kine.Settings;
import ai.rrw.kine.search.RecedingSearch;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Layer 2's search on screen, beside layer 1's replay: the runs it simulated as thin lines of the feet's positions tick
 * by tick (so a jump is an arc and a swim follows the water), the ones it kept at each depth brighter, the plan it chose
 * bold, carried on to where it lands. A replan's lines are made on the worker every third replan (the runs of the
 * deepest depth reached, best first, at most {@link #RUNS}, simulated again from the replan's start) and uploaded once;
 * each set fades over {@link #FADE_S} as newer ones come, so the last second's replans trail behind the player.
 */
final class Layer2View {
    private Layer2View() {}

    static final int RUNS = 40, EVERY = 3;
    static final double FADE_S = 1.0;
    // colours: candidates from violet (scored worst) to cyan (best), the kept ones pale cyan, the chosen plan gold
    static final int WORST = 0x5A3CC8, BEST = 0x3CDCFF, KEPT = 0xB4F5FF, CHOSEN = 0xFFD24A;

    /** The colour between a and b, t from 0 (a) to 1 (b). */
    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** One replan's lines, built on the worker, uploaded on first draw. */
    static final class Frame {
        final LineSet.Builder builder;
        final long made = System.nanoTime();
        final int runs;
        LineSet set;
        boolean built = false;

        Frame(LineSet.Builder b, int runs) {
            this.builder = b;
            this.runs = runs;
        }

        double age() {
            return (System.nanoTime() - made) / 1e9;
        }
    }

    /** Collects what the search scored (RecedingSearch.Observer), then makes the frame. */
    static final class Recorder implements RecedingSearch.Observer {
        final List<int[]> ticks = new ArrayList<>();
        final List<Double> score = new ArrayList<>();
        final List<Integer> depth = new ArrayList<>();
        final List<Boolean> kept = new ArrayList<>();
        final int hold;

        Recorder(int hold) {
            this.hold = hold;
        }

        @Override
        public void candidate(int[] t, double s, int d, boolean k) {
            ticks.add(t); score.add(s); depth.add(d); kept.add(k);
        }

        /** The frame: the deepest depth's runs, best first, and the kept ones of earlier depths; the chosen plan. */
        Frame frame(RunModel model, RunWorld root, RecedingSearch.Result r) {
            int deepest = 0;
            for (int d : depth) deepest = Math.max(deepest, d);
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < ticks.size(); i++) if (depth.get(i) == deepest || kept.get(i)) idx.add(i);
            idx.sort((a, b) -> Double.compare(score.get(a), score.get(b)));
            if (idx.size() > RUNS) idx = idx.subList(0, RUNS);
            LineSet.Builder b = new LineSet.Builder(root.s.x, root.s.y, root.s.z);
            int n = 0;
            for (int k = idx.size() - 1; k >= 0; k--) {   // the best last, drawn over the rest
                int i = idx.get(k);
                boolean keep = kept.get(i);
                float rank = idx.size() <= 1 ? 1F : 1F - (float) k / (idx.size() - 1);
                int a = keep ? 0xD0 : (int) (0x60 + 0x70 * rank);
                run(model, root, ticks.get(i), hold, false, b, (a << 24) | (keep ? KEPT : mix(WORST, BEST, rank)), keep ? 3.0F : 2.0F);
                n++;
            }
            if (r.plan() != null) {
                run(model, root, r.plan(), hold, true, b, 0xF8 << 24 | CHOSEN, 6.0F);
                n++;
            }
            return new Frame(b, n);
        }

        /** One run from the root, as the search simulated it (a segment's heading fixed at its first tick), its feet
         *  each tick into the builder; the chosen plan carried on until it lands. */
        private static void run(RunModel model, RunWorld root, int[] t, int hold, boolean land, LineSet.Builder b, int argb, float w) {
            RunWorld x = root.copy();
            double[] pts = new double[3 * (t.length + 42)];
            int m = 0;
            pts[m++] = x.s.x; pts[m++] = x.s.y; pts[m++] = x.s.z;
            for (int i = 0; i < t.length && !x.arrived; i++) {
                int run = i % hold != 0 && t[i] == t[i - 1] ? 1 : 0;
                model.step(x, t[i], run, null);
                pts[m++] = x.s.x; pts[m++] = x.s.y; pts[m++] = x.s.z;
            }
            if (land) {
                int keys = x.lastKeys & ~ai.rrw.kine.move.MoveState.JUMP;
                for (int i = 0; i < 40 && !x.arrived && RunModel.airborne(model.phys, x.s); i++) {
                    model.phys.step(x.s, keys, x.sentYaw, x.sentPitch);
                    pts[m++] = x.s.x; pts[m++] = x.s.y; pts[m++] = x.s.z;
                }
            }
            // a little above the feet, so the lines clear the ground they run on
            for (int i = 1; i < m; i += 3) pts[i] += 0.08;
            b.polyline(pts, m / 3, argb, w);
        }
    }

    private static final Deque<Frame> FRAMES = new ArrayDeque<>();
    private static Frame lastSeen = null;
    private static boolean registered = false;

    /** Draws the recent frames (a WalkRender layer, there for good once added). */
    static final WalkRender.Layer LAYER = new WalkRender.Layer() {
        @Override
        public void draw(Matrix4f pose, Vec3 cam, boolean faint) {
            Frame f = Layer2.frame();
            if (f != null && f != lastSeen) {
                lastSeen = f;
                FRAMES.addFirst(f);
            }
            while (!FRAMES.isEmpty() && (FRAMES.peekLast().age() > FADE_S + 0.2 || FRAMES.size() > 12)) {
                Frame old = FRAMES.removeLast();
                if (old.set != null) old.set.close();
            }
            if (!Settings.walkLayer2Display) return;
            boolean live = Walk.active();
            for (Frame fr : FRAMES) {
                if (!fr.built) { fr.built = true; fr.set = fr.builder.build("layer 2 runs"); }
                if (fr.set == null) continue;
                // the newest at full strength while walking; the rest fading with age
                double age = fr == FRAMES.peekFirst() && live ? 0 : fr.age();
                float a = (float) Math.max(0, 1 - age / FADE_S);
                if (a <= 0) continue;
                fr.set.draw(faint ? WalkRender.LINES_THROUGH : WalkRender.LINES, pose, cam, fr.set.lines,
                    a * (faint ? WalkRender.FAINT : 1F));
            }
        }

        @Override public boolean done() { return false; }

        @Override public void close() { }
    };

    /** Add the layer to the walk's drawing (once, on the client thread). */
    static void ensureRegistered() {
        if (registered) return;
        registered = true;
        WalkRender.addLayer(LAYER);
    }

    /** Whether this replan should make a frame. */
    static boolean frameDue(int tick) {
        return Settings.walkLayer2Display && tick % EVERY == 0;
    }
}
