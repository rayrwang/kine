package ai.rrw.kine.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A short-horizon lookahead in a receding-horizon loop, for any {@link Model}: a beam search over sequences of
 * actions, each held for a segment of ticks; every candidate is simulated forward in several random draws (the same
 * draws for every candidate), its cost summed tick by tick plus the model's end-of-plan estimate, and scored by the
 * mean or the worst over the draws. At each depth only the best few (the beam) are extended. The previous best plan,
 * shifted by the ticks since it was made, is always a candidate and is never dropped from the beam, so a replan can
 * only keep or improve on it (within the model's own numbers).
 *
 * <p>The effort (beam width, segment length, depth, draws) is a knob, and the search stops at a time budget: what it
 * has then is returned, the best plan of the last full depth reached, its last action held to the horizon. The
 * returned plan is one action per tick for the whole horizon; the caller does its first tick and replans.
 */
public final class RecedingSearch<W> {
    /** How hard to search. */
    public static final class Effort {
        public int beam = 6, depth = 5, hold = 4, draws = 3;
        /** Score by the worst draw rather than the mean. */
        public boolean worst = false;
        /** Wall-clock budget per replan, in nanoseconds. */
        public long budgetNanos = 15_000_000L;

        public Effort copy() {
            Effort e = new Effort();
            e.beam = beam; e.depth = depth; e.hold = hold; e.draws = draws; e.worst = worst; e.budgetNanos = budgetNanos;
            return e;
        }

        public int horizon() { return depth * hold; }

        @Override public String toString() {
            return "beam " + beam + " depth " + depth + "x" + hold + " draws " + draws + (worst ? " worst" : " mean");
        }
    }

    /** What a replan did. */
    public record Stats(long nanos, int candidates, long ticks, int depthReached, boolean timedOut, boolean keptPrevious) {}

    /** The plan (one action a tick, the whole horizon), its score, and the stats; plan null if every candidate was
     *  forbidden. */
    public record Result(int[] plan, double score, Stats stats) {}

    private final Model<W> model;

    /** Sees every candidate the search scores, for a display (the walk's layer 2 draws them): its actions (one a tick),
     *  its score, the depth it reached, and whether it stayed in the beam. Called on the search's thread. */
    public interface Observer {
        void candidate(int[] ticks, double score, int depth, boolean kept);
    }

    private Observer observer;

    public RecedingSearch(Model<W> model) {
        this.model = model;
    }

    public RecedingSearch<W> observe(Observer o) {
        this.observer = o;
        return this;
    }

    private final class Node {
        final int[] ticks;          // the actions so far, one a tick
        final Object[] worlds;      // one world per draw
        final double[] cost;        // cost so far per draw
        double score;               // mean or worst of cost + estimate
        final boolean previous;     // the shifted previous plan's own line

        Node(int[] ticks, Object[] worlds, double[] cost, boolean previous) {
            this.ticks = ticks; this.worlds = worlds; this.cost = cost; this.previous = previous;
        }
    }

    /**
     * One replan from the root world.
     * @param previous the last plan returned (one action a tick), or null
     * @param shift    ticks done since that plan was made (its first `shift` ticks are gone)
     * @param seed     the draws' seed for this replan
     */
    @SuppressWarnings("unchecked")
    public Result plan(W root, int[] previous, int shift, Effort e, long seed) {
        long t0 = System.nanoTime(), deadline = t0 + e.budgetNanos;
        int horizon = e.horizon(), draws = Math.max(1, e.draws), actions = model.actionCount();
        long[] ticksRun = {0};
        int candidates = 0;

        Object[] w0 = new Object[draws];
        for (int d = 0; d < draws; d++) w0[d] = model.copy(root);
        Node rootNode = new Node(new int[0], w0, new double[draws], false);

        int[] prevTicks = null;
        if (previous != null && previous.length > shift) {
            prevTicks = new int[horizon];
            for (int i = 0; i < horizon; i++) {
                int j = i + shift;
                prevTicks[i] = previous[Math.min(j, previous.length - 1)];
            }
        }
        Node prevNode = prevTicks != null ? rootNode : null;

        List<Node> beam = List.of(rootNode);
        int depthReached = 0;
        boolean timedOut = false;
        for (int depth = 0; depth < e.depth && !timedOut; depth++) {
            List<Node> children = new ArrayList<>();
            int from = depth * e.hold, to = from + e.hold;
            if (prevNode != null) {   // the previous plan's line first, so it is in even if time runs out
                int[] seg = Arrays.copyOfRange(prevTicks, from, to);
                Node c = extend(prevNode, seg, seed, ticksRun, true, e.worst);
                candidates++;
                prevNode = c;
                if (c != null) children.add(c);
            }
            outer:
            for (Node n : beam) {
                for (int a = 0; a < actions; a++) {
                    if (System.nanoTime() > deadline) { timedOut = true; break outer; }
                    int[] seg = new int[e.hold];
                    Arrays.fill(seg, a);
                    Node c = extend(n, seg, seed, ticksRun, false, e.worst);
                    candidates++;
                    if (c != null) children.add(c);
                }
            }
            if (timedOut && depth > 0) break;   // keep the last full depth
            if (children.isEmpty()) { beam = List.of(); break; }
            children.sort(Comparator.comparingDouble(n -> n.score));
            List<Node> next = new ArrayList<>(children.subList(0, Math.min(e.beam, children.size())));
            if (prevNode != null && !next.contains(prevNode)) next.add(prevNode);
            if (observer != null) for (Node c : children) observer.candidate(c.ticks, c.score, depth + 1, next.contains(c));
            beam = next;
            depthReached = depth + 1;
        }

        Node best = null;
        for (Node n : beam) if (n.ticks.length > 0 && (best == null || n.score < best.score)) best = n;
        Stats st = new Stats(System.nanoTime() - t0, candidates, ticksRun[0], depthReached, timedOut, best != null && best.previous);
        if (best == null) return new Result(null, Double.POSITIVE_INFINITY, st);
        int[] plan = Arrays.copyOf(best.ticks, horizon);
        for (int i = best.ticks.length; i < horizon; i++) plan[i] = best.ticks[best.ticks.length - 1];
        return new Result(plan, best.score, st);
    }

    /** The node after holding seg's actions (one a tick) from n, in every draw; null if forbidden. A segment starts
     *  on its first tick (tickInSegment 0) and wherever its action changes. */
    @SuppressWarnings("unchecked")
    private Node extend(Node n, int[] seg, long seed, long[] ticksRun, boolean previous, boolean worstScore) {
        int draws = n.worlds.length;
        Object[] worlds = new Object[draws];
        double[] cost = n.cost.clone();
        int base = n.ticks.length;
        double sum = 0, worst = Double.NEGATIVE_INFINITY;
        for (int d = 0; d < draws; d++) {
            W w = model.copy((W) n.worlds[d]);
            int run = 0;
            for (int i = 0; i < seg.length; i++) {
                run = i > 0 && seg[i] == seg[i - 1] ? run + 1 : 0;
                SplittableRandom rnd = new SplittableRandom(mix(seed, d, base + i));
                cost[d] += model.step(w, seg[i], run, rnd);
                ticksRun[0]++;
                if (model.forbidden(w)) return null;
            }
            worlds[d] = w;
            double total = cost[d] + model.estimate(w);
            sum += total;
            worst = Math.max(worst, total);
        }
        int[] ticks = Arrays.copyOf(n.ticks, base + seg.length);
        System.arraycopy(seg, 0, ticks, base, seg.length);
        Node c = new Node(ticks, worlds, cost, previous);
        c.score = worstScore ? worst : sum / draws;
        return c;
    }

    /** The score a fixed plan (one action a tick) gets from the root with this effort's draws and scoring, as the
     *  search would score it: for tests and diagnostics. */
    public double score(W root, int[] plan, Effort e, long seed) {
        return score(root, plan, e, seed, null);
    }

    /** As above, each tick's world (the first draw's) shown to `each`, for a caller that checks where the plan goes. */
    @SuppressWarnings("unchecked")
    public double score(W root, int[] plan, Effort e, long seed, java.util.function.Consumer<W> each) {
        int draws = Math.max(1, e.draws);
        double sum = 0, worst = Double.NEGATIVE_INFINITY;
        for (int d = 0; d < draws; d++) {
            W w = model.copy(root);
            double cost = 0;
            int run = 0;
            for (int i = 0; i < plan.length; i++) {
                run = i % e.hold != 0 && plan[i] == plan[i - 1] ? run + 1 : 0;
                cost += model.step(w, plan[i], run, new SplittableRandom(mix(seed, d, i)));
                if (each != null && d == 0) each.accept(w);
            }
            double total = cost + model.estimate(w);
            sum += total;
            worst = Math.max(worst, total);
        }
        return e.worst ? worst : sum / draws;
    }

    private static long mix(long seed, int draw, int tick) {
        long z = seed + 0x9E3779B97F4A7C15L * (draw + 1) + 0xBF58476D1CE4E5B9L * (tick + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
