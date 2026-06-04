"""
terrain_poc.py -- model-predictive terrain-avoidance planner, 1-D (straight-ahead) PoC.

Phase A of the autopilot terrain-avoidance build: prove the logic in the *same* physics
as porpoise_sim before any Java port or in-game flying.

Design (matches what we agreed):
  * ONE Controller == the shipped altitude-triggered FlightDirector law, parameterized by a
    target trough altitude. The SAME controller drives both the rollout (prediction) and the
    flown path, so predicted == actual by construction (the Java port must preserve this).
  * Planner, every replan: scan a LADDER of target floors low->high, roll each forward over
    the look-ahead horizon, pick the LOWEST whose trajectory clears terrain+BUFFER everywhere.
    If none clears -- not even continuous max-climb (Cmax) -- or terrain is unknown within the
    look-ahead, return DISENGAGE.
  * Receding horizon: re-plan, commit, advance. Resume-to-low is automatic (once the obstacle
    is behind, the low floor clears again and wins).
  * DISENGAGE is a SUCCESS, not a crash: the autopilot is fenced to straight+vertical only; the
    real player inherits turns and circle-to-land, a strictly larger envelope. We hand off.

Run:  python3 terrain_poc.py
"""

import math

# ---- physics constants (mirror porpoise_sim / verified 26.1.2 model) -------------------
GRAV, SMOOTH, CAP = 0.08, 0.15, 7.0

# ---- shipped FlightDirector profiles (FlightDirector.java C_* / L_*) --------------------
CLIMB = dict(DIVE=40.0, UP=-62.0, TRIG=44.0, TOP=11, SWEEP=0.9)   # speed-triggered, max climb
HOLD  = dict(DIVE=38.0, UP=-66.0, TRIG=None, TOP=13, SWEEP=1.2)   # altitude-triggered hold
DESC_MARGIN = 6.0          # holding: pull up when y <= target + this
CLIMB_SPEED, HOLD_SPEED = 21.9, 30.1   # m/s anchors (for reference)

PH_HOLD, PH_TOP, PH_SWEEP = 0, 1, 2


def physics_step(pitch, vx, vy):
    """One tick of updateFallFlyingMovement in the vertical plane (pitch already eased)."""
    f = math.radians(pitch)
    cf, sf = math.cos(f), math.sin(f)
    lift = cf * cf
    h0 = vx
    vy += GRAV * (-1.0 + lift * 0.75)
    if vy < 0.0 and cf > 0.0:                 # descent redirection
        conv = vy * -0.1 * lift
        vx += conv; vy += conv
    if f < 0.0 and cf > 0.0:                  # nose-up zoom: x3.2 into vertical
        conv3 = h0 * (-sf) * 0.04
        vx -= conv3; vy += conv3 * 3.2
    if cf > 0.0:                              # steering toward h0
        vx += (h0 - vx) * 0.1
    vx *= 0.99; vy *= 0.98                    # drag
    return vx, vy


class Kin:
    """Kinematic state: position + velocity, blocks and blocks/tick."""
    __slots__ = ("x", "y", "vx", "vy")

    def __init__(self, x, y, vx, vy):
        self.x, self.y, self.vx, self.vy = x, y, vx, vy

    def copy(self):
        return Kin(self.x, self.y, self.vx, self.vy)


class Controller:
    """Shipped altitude-triggered FlightDirector law, parameterized by target trough altitude.

    Order per tick mirrors measure()/FlightDirector exactly:
        ease()    -- autopilot lag advances pitch toward cmd (uses cmd from last tick)
        <physics>
        update()  -- state machine recomputes cmd/phase from post-physics y, vx
    """
    __slots__ = ("target", "phase", "pitch", "cmd", "topT", "climbing", "prof")

    def __init__(self, target, pitch=HOLD["DIVE"], climbing=False):
        self.target = target
        self.phase = PH_HOLD
        self.pitch = pitch
        self.cmd = pitch
        self.topT = 0
        self.climbing = climbing
        self.prof = CLIMB if climbing else HOLD

    def copy(self):
        c = Controller.__new__(Controller)
        c.target = self.target; c.phase = self.phase; c.pitch = self.pitch
        c.cmd = self.cmd; c.topT = self.topT; c.climbing = self.climbing; c.prof = self.prof
        return c

    def ease(self):
        self.pitch += max(-CAP, min(CAP, (self.cmd - self.pitch) * SMOOTH))
        self.pitch = max(-90.0, min(90.0, self.pitch))
        return self.pitch

    def update(self, y, vx):
        hSpeed = vx * 20.0
        p = self.prof
        if self.phase == PH_HOLD:
            self.cmd = p["DIVE"]
            pull = (hSpeed >= CLIMB["TRIG"]) if self.climbing else (y <= self.target + DESC_MARGIN)
            if pull:                                   # trough: decide next cycle
                self.climbing = y < self.target
                self.prof = CLIMB if self.climbing else HOLD
                self.phase = PH_TOP; self.topT = 0; self.cmd = self.prof["UP"]
        elif self.phase == PH_TOP:
            self.cmd = p["UP"]; self.topT += 1
            if self.topT >= p["TOP"]:
                self.phase = PH_SWEEP
        else:  # SWEEP
            self.cmd += p["SWEEP"]
            if self.cmd >= p["DIVE"]:
                self.cmd = p["DIVE"]; self.phase = PH_HOLD


def step(ctrl, st):
    """Advance one tick (mutates ctrl + st). Returns the eased pitch flown this tick."""
    pitch = ctrl.ease()
    st.vx, st.vy = physics_step(pitch, st.vx, st.vy)
    st.x += st.vx; st.y += st.vy
    ctrl.update(st.y, st.vx)
    return pitch


# ---- terrain: T(x) -> height, or None if unknown (unloaded) ----------------------------
def flat(h):
    return lambda x: h

def slope(x0, h0, x1, h1):
    def f(x):
        if x <= x0: return h0
        if x >= x1: return h1
        return h0 + (h1 - h0) * (x - x0) / (x1 - x0)
    return f

def ridge(base, x0, peak, x1):
    """Triangular ridge: base -> peak at center of [x0,x1] -> base."""
    xm = 0.5 * (x0 + x1)
    def f(x):
        if x <= x0 or x >= x1: return base
        if x <= xm: return base + (peak - base) * (x - x0) / (xm - x0)
        return base + (peak - base) * (x1 - x) / (x1 - xm)
    return f

def wall(base, x0, top):
    """Step up to a tall plateau at x0 (stays up)."""
    return lambda x: top if x >= x0 else base

def known_until(t, x_known):
    """Wrap terrain so it returns None (unknown) beyond x_known."""
    return lambda x: (t(x) if x <= x_known else None)


# ---- planner ---------------------------------------------------------------------------
def rollout(ctrl, st, target, terrain, horizon, buffer, min_lookahead):
    """Roll the SAME controller forward `horizon` ticks at the given target.
    Returns (code, traj) where traj = [(x, y, pitch), ...] and code is:
       1 CLEARS       -- cleared the whole horizon, OR ran into unknown terrain only after
                         min_lookahead blocks of verified-clear path (good enough to proceed;
                         the receding plan re-checks as more chunks load)
       0 HITS         -- would dip below terrain+buffer
      -1 INSUFFICIENT -- ran into unknown terrain before min_lookahead (can't see far enough)"""
    c = ctrl.copy(); c.target = target
    s = st.copy()
    traj = []
    for _ in range(horizon):
        pitch = step(c, s)
        traj.append((s.x, s.y, pitch))
        g = terrain(s.x)
        if g is None:
            return (1 if s.x >= min_lookahead else -1), traj   # unknown: ok iff seen far enough
        if s.y < g + buffer:
            return 0, traj
    return 1, traj


def candidate_floors(cruise, raise_max, raise_step):
    """Ladder of target trough floors, low->high. Top entries become continuous max-climb
    once the target outruns what the climb can reach in-horizon (Cmax)."""
    n = int(round(raise_max / raise_step))
    return [cruise + k * raise_step for k in range(n + 1)]


def plan(ctrl, st, terrain, horizon, buffer, floors, min_lookahead):
    """Pick the LOWEST floor whose rollout clears. Returns (target or None, traj_of_choice).
    None target == DISENGAGE (no floor up to Cmax clears, or terrain unknown too near)."""
    chosen_traj = None
    for tgt in floors:
        code, traj = rollout(ctrl, st, tgt, terrain, horizon, buffer, min_lookahead)
        if chosen_traj is None:
            chosen_traj = traj
        if code == 1:
            return tgt, traj
    return None, chosen_traj                  # disengage (keep last/Cmax traj for inspection)


# ---- receding-horizon harness ----------------------------------------------------------
def warmup(cruise, ticks=1500):
    """Settle into the steady cruise-hold limit cycle.

    The hold is a high-energy limit cycle (trough ~target+3, peak ~target+109); it only sustains
    when entered with energy, which is how the autopilot actually arrives (off a climb or a
    descent at altitude). So we seed above the peak at cruise speed and let it settle in."""
    ctrl = Controller(cruise, pitch=HOLD["DIVE"], climbing=False)
    st = Kin(0.0, cruise + 120.0, 1.6, 0.0)
    for _ in range(ticks):
        step(ctrl, st)
    st.x = 0.0
    return ctrl, st


def fly(name, terrain, cruise, *, horizon=420, buffer=8.0, raise_max=60.0, raise_step=4.0,
        replan_every=8, sim_ticks=2600, min_lookahead=160.0, verbose=True):
    """Fly the scenario with per-replan terrain avoidance. Reports the outcome."""
    ctrl, st = warmup(cruise)
    floors = candidate_floors(cruise, raise_max, raise_step)

    target = cruise
    min_clear = math.inf
    disengaged_at = None
    raised = False
    max_target = cruise
    seg = []                 # (x, target) timeline samples
    last_plan_tick = -10**9

    for t in range(sim_ticks):
        if t - last_plan_tick >= replan_every:
            tgt, _ = plan(ctrl, st, terrain, horizon, buffer, floors, min_lookahead)
            last_plan_tick = t
            if tgt is None:
                disengaged_at = (st.x, st.y)
                break
            target = tgt
            ctrl.target = target
            max_target = max(max_target, target)
            if target > cruise + 1e-6:
                raised = True
            seg.append((round(st.x, 1), round(target, 1)))

        g = terrain(st.x)
        if g is not None:
            min_clear = min(min_clear, st.y - g)
        step(ctrl, st)
        if st.x > 4000:       # flew off the end of the scenario
            break

    if verbose:
        print(f"  {name}")
        if disengaged_at is not None:
            dx, dy = disengaged_at
            print(f"    -> DISENGAGE at x={dx:.0f}  y={dy:.0f}   (graceful hand-off)")
        else:
            print(f"    -> flew through. min clearance {min_clear:+.1f} blk (buffer {buffer:.0f})"
                  f"   floor raised: {'yes -> max %.0f' % max_target if raised else 'no'}")
        # compact target timeline: only print where the chosen floor changes
        changes = []
        prev = None
        for x, tg in seg:
            if tg != prev:
                changes.append(f"x{int(x)}:{tg:.0f}")
                prev = tg
        if changes:
            print("       floor timeline:", "  ".join(changes[:14]) + (" ..." if len(changes) > 14 else ""))
    return dict(name=name, disengaged=disengaged_at, min_clear=min_clear,
                raised=raised, max_target=max_target)


if __name__ == "__main__":
    print("=" * 78)
    print("TERRAIN-AVOIDANCE PLANNER -- 1-D PoC (validation in porpoise physics)")
    print("=" * 78)

    # sanity: the steady cruise band the planner has to work within
    c, s = warmup(80.0)
    lo = hi = s.y
    for _ in range(700):
        step(c, s)
        lo = min(lo, s.y); hi = max(hi, s.y)
    print(f"\nsteady cruise @ target 80: trough~{lo:.0f}  peak~{hi:.0f}  swing~{hi-lo:.0f} blk")
    print("(clearance binds at the TROUGH; sustained climb-out is shallow ~4%, so terrain that")
    print(" rises slower than that is trackable, steeper/taller terrain forces a disengage)\n")

    CRUISE = 80.0
    print("scenarios (cruise target 80 -> trough ~83):\n")

    # 1) terrain far below the trough -> never act
    fly("1. flat ground at 40 (well below trough)", flat(40.0), CRUISE, sim_ticks=1500)

    # 2) gentle hill into the flight band, rising slower than the climb-out -> floor tracks
    #    up to clear, then drops back (resume) on the far side
    hill = ridge(40.0, 400, 105.0, 6400)
    fly("2. gentle hill 40 -> 105 -> 40 (rise ~2%, trackable)", hill, CRUISE, sim_ticks=6000)

    # 3) a tall barrier above even the porpoise peak -> cannot clear -> disengage (success)
    fly("3. wall up to 230 at x=900 (above the peak)", wall(40.0, 900, 230.0), CRUISE,
        sim_ticks=2000)

    # 4) a steep ramp rising faster than the climb-out -> outruns us -> disengage
    fly("4. steep ramp 40 -> 200 over 900 blk (rise ~18%)", slope(400, 40, 1300, 200.0), CRUISE,
        sim_ticks=2000)

    # 5) terrain known only a short way ahead -> can't see far enough to be safe -> disengage
    fly("5. terrain unknown beyond x=120 (< min look-ahead 160)", known_until(flat(40.0), 120.0),
        CRUISE, sim_ticks=1500)

    # 6) terrain known well past the look-ahead, low -> proceed (unloaded chunks farther out are
    #    fine; the receding plan re-checks them as they load)
    fly("6. flat 40 known to x=400 (> min look-ahead) then unknown", known_until(flat(40.0), 400.0),
        CRUISE, sim_ticks=1500)

    print("\n" + "=" * 78)
    print("DISENGAGE in 3-5 is the intended outcome: the autopilot is straight+vertical only,")
    print("so it hands a recoverable aircraft to the player (turn / circle-to-land), not a crash.")
    print("=" * 78)
