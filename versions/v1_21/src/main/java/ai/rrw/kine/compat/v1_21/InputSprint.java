package ai.rrw.kine.compat.v1_21;

/**
 * The sprint key as the client's input last read it, kine's presses included. The input of 1.21 and 1.21.1 has no
 * sprint (LocalPlayer.aiStep reads the sprint key mapping itself), so KeyboardInputMixin keeps it beside the input's
 * other keys, SprintKeyMixin hands it to aiStep, and EpochImpl.lastKeys reads it.
 */
public interface InputSprint {
    boolean kine$sprint();
}
