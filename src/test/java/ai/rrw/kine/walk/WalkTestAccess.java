package ai.rrw.kine.walk;

import ai.rrw.kine.compat.Input;

/** Package access for tests elsewhere. */
public final class WalkTestAccess {
    private WalkTestAccess() {}

    public static Input handBack(Input keys) {
        return Walk.handBackKeys(keys);
    }
}
