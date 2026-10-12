package ai.rrw.kine.search;

import java.util.SplittableRandom;

/**
 * What the receding-horizon search plans over: a world it can copy and step one tick at a time under an action, a cost
 * for each tick, an estimate of the cost left at the end of a plan, and the actions it may take. The combat movement
 * plugs in one; the pathfinder's second layer will plug in another. W is the simulated world, mutable.
 */
public interface Model<W> {
    /** A deep copy. */
    W copy(W w);

    /** How many actions there are; actions are 0 .. count - 1, held for whole segments. */
    int actionCount();

    /**
     * Advance the world one tick under the action. tickInSegment is 0 on the first tick the action is held (a model
     * whose actions depend on the world, such as "away from the nearest mob", fixes their meaning then). The random
     * stream is this draw's own, the same for every candidate (common random numbers). Returns this tick's cost.
     */
    double step(W w, int action, int tickInSegment, SplittableRandom rnd);

    /** The cost left after the plan's last tick (the end-of-plan estimate). */
    double estimate(W w);

    /** A world the plan must never reach (it would hurt the player regardless of cost): the candidate is dropped. */
    default boolean forbidden(W w) { return false; }
}
