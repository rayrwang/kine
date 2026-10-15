package ai.rrw.kine.compat;

/**
 * The keys a player holds in one tick, as kine's features read and press them. It has the same parts and names as the
 * game's net.minecraft.world.entity.player.Input (from 1.21.2), which 1.21 and 1.21.1 lack, so feature code uses this
 * one on every release and each family turns it into the game's own and back where the keyboard is read
 * (KeyboardInputMixin, Epoch.lastKeys).
 */
public record Input(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint) {
    public static final Input EMPTY = new Input(false, false, false, false, false, false, false);
}
