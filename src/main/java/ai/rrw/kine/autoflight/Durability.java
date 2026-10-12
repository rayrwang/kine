package ai.rrw.kine.autoflight;

import java.util.ArrayList;
import java.util.List;

/**
 * Elytra durability, free of game types so it can be tested: when a wing counts as spent, the order the wings are
 * flown in, and the endurance (END) of every wing carried, with the landing reserve held on the last one.
 *
 * <p>From the 26.1.2 code: while gliding, LivingEntity.updateFallFlying rolls one point of damage on the glider every
 * 20 ticks. Unbreaking on an elytra follows the tool rule, not the armour one (the elytra is in the
 * durability_enchantable tag but not in armor_enchantable; Enchantments.UNBREAKING), so each roll takes the point with
 * chance 1 / (level + 1): a point lasts (level + 1) seconds on average and 1 second at the least. The game stops the
 * glide with 1 point left (LivingEntity.canGlideUsing, ItemStack.nextDamageWillBreak); kine stops sooner, at FLOOR.
 */
public final class Durability {
    private Durability() {}

    /** A wing with this many points left or fewer is spent, enchanted or not (RW: "ok 3"). Points left is the
     *  tooltip's number, max damage minus damage. */
    public static final int FLOOR = 3;

    /** One elytra: the points it has left, its Unbreaking level, and whether it takes no damage at all
     *  (Unbreakable). */
    public record Wing(int left, int unbreaking, boolean unbreakable) {
        /** The points it can still glide on before the floor, never below 0. */
        public int usable() { return Durability.usable(left); }
        /** The average seconds one point lasts. */
        public double rate() { return unbreaking + 1; }
        /** The seconds its usable points last on average; infinite for one that takes no damage. */
        public double seconds() { return unbreakable ? Double.POSITIVE_INFINITY : usable() * rate(); }
        /** Worn down to the floor: the failsafe swaps it out. Never for one that takes no damage. */
        public boolean spent() { return !unbreakable && Durability.spent(left); }
    }

    /** Spent: down to the floor. Only this test, no list of spent wings, so a wing mended or repaired since is usable
     *  again by itself. */
    public static boolean spent(int left) { return left <= FLOOR; }

    /** Points a wing can still glide on before the floor, never below 0. */
    public static int usable(int left) { return Math.max(0, left - FLOOR); }

    /** The spare to swap in: the one with the most usable points (the first of equals), or -1 if none has any. */
    public static int freshest(List<Wing> spares) {
        int best = -1;
        for (int i = 0; i < spares.size(); i++) {
            if (spares.get(i).usable() > 0 && (best < 0 || spares.get(i).usable() > spares.get(best).usable())) best = i;
        }
        return best;
    }

    /** The order the wings are flown in (RW): the worn one to its floor, then the usable spares, freshest first (the
     *  first of equals first, as {@link #freshest} picks). The last in this order holds the landing reserve. */
    public static List<Wing> order(Wing worn, List<Wing> spares) {
        List<Wing> left = new ArrayList<>(spares), out = new ArrayList<>();
        out.add(worn);
        for (int i = freshest(left); i >= 0; i = freshest(left)) out.add(left.remove(i));
        return out;
    }

    /** The landing reserve in points: one point a second of landing, the worst the game can take (RW: "ok worst"). */
    public static int reservePoints(double landingSeconds) {
        return Math.max(0, (int) Math.ceil(landingSeconds - 1e-9));
    }

    /**
     * END, in seconds: the expected gliding time on the usable points of every wing in `order` ({@link #order}), less
     * the landing reserve, the last `reservePoints` points of the order counted at their wing's average rate. Those are
     * the last wing's; if it has fewer, the rest are the wing's before it, and so on. So END reaching 0 means the
     * landing must start now. Infinite while the worn wing takes no damage; never below 0, and 0 once the reserve is
     * all that is left.
     */
    public static double endurance(List<Wing> order, int reservePoints) {
        if (order.isEmpty()) return 0.0;
        if (order.get(0).unbreakable()) return Double.POSITIVE_INFINITY;
        double total = 0.0;
        for (Wing w : order) total += w.seconds();
        double held = 0.0;
        int toHold = reservePoints;
        for (int i = order.size() - 1; i >= 0 && toHold > 0; i--) {
            int take = Math.min(toHold, order.get(i).usable());
            held += take * order.get(i).rate();
            toHold -= take;
        }
        if (toHold > 0) return 0.0;   // fewer usable points than the reserve: land now
        return Math.max(0.0, total - held);
    }
}
