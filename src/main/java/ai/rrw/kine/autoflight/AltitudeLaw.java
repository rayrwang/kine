package ai.rrw.kine.autoflight;

/**
 * The flight director's altitude law, free of game types so it can be tested: the hysteresis on the
 * arming verdict, the {@link PorpoiseLaw} as flown, the vertical mode for the annunciator, and the
 * measured porpoise period. FlightDirector feeds it the game state once per tick.
 */
public final class AltitudeLaw {
    /** Vertical modes for the annunciator: CLB and DESC amber, ALT* (capture) yellow, ALT (hold) green. */
    public static final int VM_CLB = 0, VM_ALT_STAR = 1, VM_ALT = 2, VM_DESC = 3;

    static final float  CLIMB_SPEED = 22.5f;        // climb profile's model ground speed (m/s)
    static final float  HOLD_SPEED = 32.4f;        // hold profile's closed-loop average (m/s)
    static final int    ALT_MIN      = -60;
    static final int    ALT_MAX      = 2000;
    static final int    DESC_LIVE    = 150;     // while diving above target + this, show DESC (above the steady peak of about +137)
    static final double ALT_HOLD_BAND = 10.0;   // |trough - target| within this: ALT (settled hold)
    static final double ALT_CAP_BAND  = 40.0;   // within this: ALT* (capturing); beyond: CLB or DESC
    static final int PERIOD_MIN = 160, PERIOD_MAX = 420;
    // Hysteresis for the arming verdict. In rough terrain `room` chatters from tick to tick (the porpoise
    // crosses the feasibility boundary, terrain samples shift), so it is integrated: +1 per infeasible tick,
    // -2 per feasible tick, clamped. The warning trips at LOW_TRIP and clears below LOW_CLEAR; a 50/50
    // chatter drifts toward zero and never trips.
    static final int LOW_TRIP  = 12;   // 0.6 s of sustained infeasibility before it trips
    static final int LOW_CLEAR = 4;    // must drain to here before it clears
    static final int LOW_CAP   = 20;   // ceiling, so a long bad stretch still clears promptly once good

    /** The porpoise law's live state; the planner and the rails copy it into their rollouts. */
    final PorpoiseLaw law = new PorpoiseLaw();

    int targetAlt = 350;       // set in the nav screen; the trough altitude to hold (build limit 320)
    // The altitude the law flies to. Without terrain avoidance it equals targetAlt; with it, the planner
    // raises it to the lowest floor that clears the terrain ahead, and targetAlt stays the pilot's choice.
    int floorAlt = targetAlt;

    int    vmode = VM_CLB;              // vertical mode (annunciator only; control uses law.climbing)
    double lastTroughY = -1.0e9;        // last sampled trough (the AGL gate's terrain bypass)
    double commandedPitchOld;           // last tick's command, for per-frame interpolation

    // Porpoise period measured live (ticks between pull-ups). The range readout averages speed over whole
    // cycles of this length (about 254 ticks climbing, 360 holding). Periods outside [PERIOD_MIN, PERIOD_MAX]
    // are ignored, so a long descent's single dive leaves the last value in place.
    int cycleTicks = 0;                 // ticks since the last pull-up
    int measuredPeriod = 360;           // the hold cruise period until measured

    boolean active = false;
    boolean tooLow = false;             // gliding, but no flyable path to arm on
    boolean freshGlide = true;          // the first verdict of a new glide is taken raw (see tick)
    int lowAccum = 0;

    AltitudeLaw() {
        law.restartClimb();
        commandedPitchOld = law.cmd;
    }

    void setTargetAltitude(int y) { targetAlt = Math.max(ALT_MIN, Math.min(ALT_MAX, y)); }
    /** Model ground speed (m/s) of the active profile; seeds the range and ETA estimate. */
    float expectedGroundSpeed() { return law.climbing ? CLIMB_SPEED : HOLD_SPEED; }

    /** The starting cruise level, once, from the initial compass heading: eastbound (0 to 179) flies odd
     *  levels, westbound even, as aircraft do. */
    void initCruise(double heading) {
        targetAlt = (heading < 180.0) ? 350 : 360;
        floorAlt = targetAlt;
    }

    /** Remember last tick's command so the HUD can interpolate between ticks. Call first every tick. */
    void startTick() { commandedPitchOld = law.cmd; }

    /**
     * One gliding tick. `room` is the raw arming verdict (a flyable path exists), hSpeed the horizontal
     * speed (m/s), y the altitude. Returns whether the director is armed; when it isn't, the law restarts
     * at the beginning of a climb.
     */
    boolean tick(boolean room, double hSpeed, double y) {
        // Integrate the raw verdict so a momentary infeasible reading (common near tall terrain) neither
        // flashes the warning nor kicks the law out of cruise; only a sustained loss of a flyable path does.
        lowAccum = Math.max(0, Math.min(LOW_CAP, lowAccum + (room ? -2 : 1)));
        // The integrator rides out chatter in an established cruise. The first verdict of a new glide has no
        // cruise to protect, and integrating it would flash the bars for LOW_TRIP ticks before the warning
        // trips, so that verdict is taken raw and the integrator seeded to match.
        boolean lowConfirmed;
        if (freshGlide) {
            freshGlide = false;
            lowAccum = room ? 0 : LOW_CAP;
            lowConfirmed = !room;
        } else {
            lowConfirmed = tooLow ? (lowAccum > LOW_CLEAR) : (lowAccum >= LOW_TRIP);
        }
        if (lowConfirmed) {
            active = false;
            tooLow = true;
            // no flyable path: restart with a climb, so re-arming climbs back up
            law.restartClimb(); vmode = VM_CLB; cycleTicks = 0;
            floorAlt = targetAlt;       // drop any raised trough
            return false;
        }
        active = true;
        tooLow = false;
        cycleTicks++;                                          // measuring the current porpoise period

        // Show DESC the whole way down a long descent; the trough is only sampled at the bottom, which
        // would read ALT on arrival. Holds until the pull-up, where the trough reclassifies.
        if (law.phase == PorpoiseLaw.DIVE && !law.climbing && y > floorAlt + DESC_LIVE) vmode = VM_DESC;
        if (law.update(y, hSpeed, floorAlt)) {
            if (cycleTicks >= PERIOD_MIN && cycleTicks <= PERIOD_MAX) measuredPeriod = cycleTicks;
            cycleTicks = 0;                      // start timing the next cycle
            lastTroughY = law.trough;
            vmode = verticalMode(lastTroughY - floorAlt);
        }
        return true;
    }

    /** Vertical mode from the trough's error (positive above the target). In steady hold the trough sits
     *  about 3 above the target, so the high side is only reached on the first cycle after arming above
     *  the target (still speed-triggered); normally ALT* means capturing from below and DESC comes from
     *  the live check in tick(). */
    static int verticalMode(double err) {
        double ae = Math.abs(err);
        if      (ae <= ALT_HOLD_BAND) return VM_ALT;        // settled at target
        else if (ae <= ALT_CAP_BAND)  return VM_ALT_STAR;   // capturing (climb side, or first cycle from above)
        else                          return (err < 0) ? VM_CLB : VM_DESC;
    }

    /** Start the cruise in altitude hold, as at the end of a rocket takeoff above the target. */
    void restartHold() {
        law.restartHold(); cycleTicks = 0; vmode = VM_ALT_STAR;
    }

    /** Not gliding: disarm and restart at the beginning of a climb. */
    void reset() {
        active = false; tooLow = false; lowAccum = 0; freshGlide = true;
        vmode = VM_CLB; lastTroughY = -1.0e9;
        floorAlt = targetAlt;
        law.restartClimb(); cycleTicks = 0;
    }
}
