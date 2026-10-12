package ai.rrw.kine.walk;

import net.minecraft.world.entity.player.Input;

/** Package access for tests elsewhere. */
public final class WalkTestAccess {
    private WalkTestAccess() {}

    public static Input handBack(Input keys) {
        return Walk.handBackKeys(keys);
    }
}
