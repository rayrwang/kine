package ai.rrw.kine.autoflight;

import ai.rrw.kine.guard.Pilot;
import ai.rrw.kine.move.MoveState;

/**
 * The rocket takeoff's own future commands in flight, as a {@link Pilot} for the elytra guardian's rollouts: the look
 * turned onto the climb's heading and pitch as Takeoff.onFrame turns it (straight up at its look-up rate, then over to
 * the lean at the lean rate), the rocket burning now pushing for the rest of its mean burn, and the next one fired as
 * {@link TakeoffModel#fly} fires them (when one burns out, unless the autopilot taken over now would hold the cruise),
 * after the latency of a use; then the autopilot's altitude hold along the climb's heading. Rockets are fired only
 * while the game takes key presses: with a screen open (chat, the inventory) none is, the burning one runs out and the
 * climb stalls with its nose held up, which is what killed RW on 2026-10-13 at 23:31 EDT. The lean is held as it is
 * (the live climb leans once its leaning path clears the terrain; climbing straight up instead is as safe).
 */
public final class TakeoffPilot implements Pilot {
    static final double LOOK_RATE = 12.0, LEAN_RATE = TakeoffModel.PITCH_RATE;   // degrees a tick, as Takeoff.onFrame

    final double yaw, cruise;
    final boolean lean, canFire;
    final int burn;
    int burnLeft, wait, rocketsLeft;
    boolean fire = false, burntOut = false;
    double lastPitch = -90.0;
    ApModel handed = null;   // after the hand-over: the autopilot's hold

    /** `burnLeft`: ticks the burning rocket still pushes (0 none); `untilPush`: ticks until one fired starts to push
     *  (-1 none); `burn`: the mean burn of the next rockets; `rocketsLeft`: rockets carried; `canFire`: the game takes
     *  key presses (no screen open). */
    TakeoffPilot(double yaw, boolean lean, int burnLeft, int untilPush, int burn, double cruise, int rocketsLeft,
                 boolean canFire) {
        this.yaw = yaw; this.lean = lean; this.burnLeft = burnLeft; this.wait = untilPush; this.burn = burn;
        this.cruise = cruise; this.rocketsLeft = rocketsLeft; this.canFire = canFire;
        // nothing burning or coming: the takeoff fires at once when it can (Takeoff.beforeKeybinds)
        if (burnLeft <= 0 && untilPush < 0 && canFire && rocketsLeft > 0) fire = true;
    }

    @Override
    public TakeoffPilot copy() {
        TakeoffPilot t = new TakeoffPilot(yaw, lean, burnLeft, wait, burn, cruise, rocketsLeft, canFire);
        t.fire = fire; t.burntOut = burntOut; t.lastPitch = lastPitch;
        t.handed = handed == null ? null : handed.copy();
        return t;
    }

    @Override
    public void look(MoveState s, float y, float p, float[] out) {
        if (handed != null) { handed.look(s, y, p, out); return; }
        if (fire) { fire = false; rocketsLeft--; wait = TakeoffModel.LATENCY - 1; }
        double target = lean ? TakeoffModel.CLIMB_PITCH : -90.0, rate = lean ? LEAN_RATE : LOOK_RATE;
        out[1] = (float) (p + Math.max(-rate, Math.min(rate, target - p)));
        out[0] = (float) (y + Math.max(-LOOK_RATE, Math.min(LOOK_RATE, FlightModel3D.wrap180(yaw - y))));
        lastPitch = out[1];
    }

    @Override
    public boolean boost() {
        if (handed != null) return false;
        if (wait > 0) wait--;
        else if (wait == 0) { wait = -1; burnLeft = burn; }
        burntOut = false;
        if (burnLeft <= 0) return false;
        burntOut = --burnLeft == 0;
        return true;
    }

    @Override
    public void after(MoveState s) {
        if (handed != null) { handed.after(s); return; }
        if (!burntOut || wait >= 0) return;
        if (TakeoffModel.holdsCruise(s.y, s.vx, s.vy, s.vz, lastPitch, yaw, cruise)) {
            ApModel m = new ApModel();
            m.mode = ApModel.NONE;
            m.law.restartHold();
            m.floor = cruise;
            handed = m;
        } else if (canFire && rocketsLeft > 0) fire = true;
    }

    @Override
    public String toString() {
        return "takeoff" + (canFire ? "" : " (no rockets: a screen is open)");
    }
}
