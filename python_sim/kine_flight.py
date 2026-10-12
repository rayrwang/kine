"""kine's flight code in Python, for the notebook in this folder.

A line-for-line copy of the mod's pure flight classes (no game types), so the notebook can fly exactly what the mod
flies. Each function names the Java it copies, under src/main/java/ai/rrw/kine/. Copied at commit COPIED_FROM;
validate_against_java.py flies both and compares them tick for tick, and the notebook's first section checks the
golden traces in kine's own unit tests.

Conventions, as in the mod and the game: blocks and ticks (20 a second; m/s = blocks a tick x 20); Minecraft's yaw
(0 = +Z, 90 = -X, clockwise seen from above) and pitch (positive = nose down).

Also here, for comparison only: the porpoise law the mod flew from June until 2026-10-11 (JUNE), and the generic
pieces the fast-pitch study's search needs (any pitch response, the level-off law's parameter vector, the measures).
"""

import math
import re
import struct
from dataclasses import dataclass

COPIED_FROM = "1ebaf05"


def f32(x):
    """A Java float literal or cast, as the double Java widens it to (0.98F is 0.9800000190734863)."""
    return struct.unpack("f", struct.pack("f", x))[0]


def jround(x):
    """Java's Math.round: floor(x + 0.5), not Python's round-half-even."""
    return int(math.floor(x + 0.5))


# ------------------------------------------------------------------------------------------- the physics
# autoflight/FlightModel3D.java

GRAV = 0.08
DELTA_MAX = 20.0         # most look offset (deg) the steering commands in one tick
EMERGENCY_DELTA = 45.0   # the emergency bank's look offset


def vel_yaw(vx, vz):
    """FlightModel3D.velYaw: the yaw (deg) of a horizontal velocity."""
    return math.degrees(math.atan2(-vx, vz))


def wrap180(a):
    """FlightModel3D.wrap180: an angle wrapped to (-180, 180]."""
    while a > 180.0:
        a -= 360.0
    while a <= -180.0:
        a += 360.0
    return a


def physics_step(yaw, pitch, v):
    """FlightModel3D.physicsStep: one tick of LivingEntity.updateFallFlyingMovement (26.1.2) with the look at (yaw,
    pitch). v = [vx, vy, vz] in blocks a tick, changed in place."""
    pr = math.radians(pitch)
    yr = -math.radians(yaw)
    cp = math.cos(pr)
    lx = math.sin(yr) * cp
    lz = math.cos(yr) * cp
    look_hor = math.sqrt(lx * lx + lz * lz)
    move_hor = math.sqrt(v[0] * v[0] + v[2] * v[2])
    lift = cp * cp
    v[1] += GRAV * (-1.0 + lift * 0.75)                      # gravity less lift
    if v[1] < 0.0 and look_hor > 0.0:                         # the descent redirect
        c = v[1] * -0.1 * lift
        v[0] += lx * c / look_hor
        v[1] += c
        v[2] += lz * c / look_hor
    if pr < 0.0 and look_hor > 0.0:                           # nose up: the pump, x3.2 into the climb
        c = move_hor * (-math.sin(pr)) * 0.04
        v[0] += -lx * c / look_hor
        v[1] += c * 3.2
        v[2] += -lz * c / look_hor
    if look_hor > 0.0:                                        # steering toward the look's heading
        v[0] += (lx / look_hor * move_hor - v[0]) * 0.1
        v[2] += (lz / look_hor * move_hor - v[2]) * 0.1
    v[0] *= 0.99
    v[1] *= 0.98
    v[2] *= 0.99


@dataclass(frozen=True)
class Response:
    """The autopilot's pitch response per tick: `smooth` of the gap toward the command, at most `cap` degrees
    (FlightModel3D.ease and easeLanding)."""
    smooth: float
    cap: float

    def ease(self, pitch, cmd):
        pitch += max(-self.cap, min(self.cap, (cmd - pitch) * self.smooth))
        return max(-90.0, min(90.0, pitch))

    @property
    def name(self):
        if self.smooth == 1.0:
            return "instant" if self.cap >= 180 else f"{self.cap:g} deg a tick"
        return f"{self.smooth:g} of the gap, at most {self.cap:g} deg a tick"


PORPOISE_RESPONSE = Response(1.0, 15.0)   # FlightModel3D.SMOOTH, CAP: straight at the command, 15 deg a tick (300 deg/s)
LANDING_RESPONSE = Response(0.15, 7.0)    # FlightModel3D.LANDING_SMOOTH, LANDING_CAP
JUNE_RESPONSE = Response(0.15, 7.0)       # the porpoise's response until 2026-10-11


# ------------------------------------------------------------------------------------------- the porpoise law
# autoflight/PorpoiseLaw.java

DIVE, PULL_UP, SWEEP, LEVEL, GLIDE = 0, 1, 2, 3, 4
PHASE_NAMES = {DIVE: "dive", PULL_UP: "pull-up", SWEEP: "sweep", LEVEL: "level", GLIDE: "glide"}


@dataclass(frozen=True)
class Program:
    """PorpoiseLaw.Profile, the level-off law: after the trigger, `level` for level_ticks; `pull_up` for pull_up_hold;
    then the command eases from pull_up toward sweep_to by sweep_gain of the gap each tick for sweep_ticks; then
    `glide` for glide_ticks; then the dive pitch until the next trigger. trigger_speed (m/s) is the climb's pull-up
    speed; the hold pulls up on altitude and has none (nan)."""
    dive: float
    level: float
    level_ticks: int
    pull_up: float
    pull_up_hold: int
    sweep_gain: float
    sweep_to: float
    sweep_ticks: int
    glide: float
    glide_ticks: int
    trigger_speed: float

    def cmd_at(self, tau):
        """programCmd: the command tau ticks after the trigger, or nan once the program is over."""
        if tau < self.level_ticks:
            return self.level
        tau -= self.level_ticks
        if tau < self.pull_up_hold:
            return self.pull_up
        tau -= self.pull_up_hold
        if tau < self.sweep_ticks:
            return self.sweep_to + (self.pull_up - self.sweep_to) * math.pow(1.0 - self.sweep_gain, tau + 1)
        tau -= self.sweep_ticks
        if tau < self.glide_ticks:
            return self.glide
        return math.nan

    def phase_at(self, tau):
        if tau < self.level_ticks:
            return LEVEL
        tau -= self.level_ticks
        if tau < self.pull_up_hold:
            return PULL_UP
        tau -= self.pull_up_hold
        if tau < self.sweep_ticks:
            return SWEEP
        tau -= self.sweep_ticks
        return GLIDE if tau < self.glide_ticks else DIVE


@dataclass(frozen=True)
class Step:
    """The June law's profile (in the mod until 2026-10-11): at the trigger step to `pull_up`, hold it for `hold`
    ticks, sweep the command back down by `sweep` deg a tick, then dive at `dive`. trigger_speed as in Program."""
    dive: float
    pull_up: float
    trigger_speed: float
    hold: int
    sweep: float


CLIMB = Program(44.3, 0.0, 13, -78.5, 3, 0.0336, -6.9, 86, 32.9, 81, 44.1)        # PorpoiseLaw.CLIMB
ALT_HOLD = Program(44.5, 0.1, 13, -82.7, 1, 0.0219, 12.6, 84, 37.6, 173, math.nan)  # PorpoiseLaw.ALT_HOLD
DESC_MARGIN = 8.7                                                                  # PorpoiseLaw.DESC_MARGIN

JUNE_CLIMB = Step(40.0, -62.0, 44.0, 11, 18.0 / 20.0)
JUNE_HOLD = Step(38.0, -66.0, math.nan, 13, 24.0 / 20.0)
JUNE_MARGIN = 6.0


@dataclass(frozen=True)
class Law:
    """A whole law: the two profiles, the hold's pull-up margin and the pitch response that flies it."""
    climb: object
    hold: object
    margin: float
    response: Response
    name: str = ""


CURRENT = Law(CLIMB, ALT_HOLD, DESC_MARGIN, PORPOISE_RESPONSE, "the level-off law, 15 deg a tick (in the mod)")
JUNE = Law(JUNE_CLIMB, JUNE_HOLD, JUNE_MARGIN, JUNE_RESPONSE, "the June law, 0.15 / 7 (until 2026-10-11)")


class State:
    """FlightModel3D.State: the kinematics plus the PorpoiseLaw's state, flown by `law` (CURRENT unless given)."""

    def __init__(self, x, y, z, vx, vy, vz, target, pitch, climbing, law=CURRENT):
        self.law = law
        self.x, self.y, self.z, self.vx, self.vy, self.vz = x, y, z, vx, vy, vz
        self.target = target
        self.pitch = pitch
        self.cmd = pitch
        self.climbing = climbing
        self.phase = DIVE
        self.program_ticks = 0          # PorpoiseLaw.programTicks; the June law's pullUpTicks
        self.cycle_min = math.inf       # Double.MAX_VALUE
        self.trough = -1.0e9

    def copy(self):
        s = State(self.x, self.y, self.z, self.vx, self.vy, self.vz, self.target, self.pitch, self.climbing, self.law)
        s.cmd, s.phase, s.program_ticks, s.cycle_min, s.trough = self.cmd, self.phase, self.program_ticks, self.cycle_min, self.trough
        return s

    def profile(self):
        return self.law.climb if self.climbing else self.law.hold

    def update(self, y, h_speed, target):
        """PorpoiseLaw.update: one tick of the law from this tick's altitude, horizontal speed (m/s) and the trough
        floor to fly to. Returns True on the tick it pulls up. The June law's branch is the study's copy of the old
        PorpoiseLaw (~/_/kine_fastpitch_2026-10-11/sim, FP.Law)."""
        if y < self.cycle_min:
            self.cycle_min = y
        p = self.profile()
        if self.phase == DIVE:
            self.cmd = p.dive
            pull = (h_speed >= self.law.climb.trigger_speed) if self.climbing else (y <= target + self.law.margin)
            if pull:
                self.trough = self.cycle_min
                self.climbing = self.trough < target
                self.cycle_min = math.inf
                p = self.profile()
                self.program_ticks = 0
                if isinstance(p, Program):
                    self._program(p)
                else:
                    self.phase = PULL_UP
                    self.cmd = p.pull_up
                return True
        elif isinstance(p, Program):
            self.program_ticks += 1
            self._program(p)
        elif self.phase == PULL_UP:
            self.cmd = p.pull_up
            self.program_ticks += 1
            if self.program_ticks >= p.hold:
                self.phase = SWEEP
        else:
            self.cmd += p.sweep
            if self.cmd >= p.dive:
                self.cmd = p.dive
                self.phase = DIVE
        return False

    def _program(self, p):
        c = p.cmd_at(self.program_ticks)
        if math.isnan(c):
            self.cmd = p.dive
            self.phase = DIVE
        else:
            self.cmd = c
            self.phase = p.phase_at(self.program_ticks)

    def restart_climb(self):
        """PorpoiseLaw.restartClimb: start a climb, diving at the climb profile's pitch."""
        self.climbing, self.phase, self.cmd, self.program_ticks, self.cycle_min = True, DIVE, self.law.climb.dive, 0, math.inf

    def restart_hold(self):
        """PorpoiseLaw.restartHold: start in altitude hold, as at the end of a rocket takeoff."""
        self.climbing, self.phase, self.cmd, self.program_ticks, self.cycle_min = False, DIVE, self.law.hold.dive, 0, math.inf

    @property
    def h_speed(self):
        return math.sqrt(self.vx * self.vx + self.vz * self.vz) * 20.0


def step_look(s, look_yaw, frozen=False):
    """FlightModel3D.stepLook: ease the pitch toward last tick's command, physics with the look at look_yaw,
    integrate, then the law. frozen holds the pitch (a control freeze). Returns True on a pull-up tick."""
    if not frozen:
        s.pitch = s.law.response.ease(s.pitch, s.cmd)
    v = [s.vx, s.vy, s.vz]
    physics_step(look_yaw, s.pitch, v)
    s.vx, s.vy, s.vz = v
    s.x += s.vx
    s.y += s.vy
    s.z += s.vz
    return s.update(s.y, math.sqrt(s.vx * s.vx + s.vz * s.vz) * 20.0, s.target)


def step(s, target_heading, max_delta=DELTA_MAX):
    """FlightModel3D.step: steer toward target_heading with the look at most max_delta off the velocity's heading."""
    vy0 = vel_yaw(s.vx, s.vz)
    delta = max(-max_delta, min(max_delta, wrap180(target_heading - vy0)))
    return step_look(s, vy0 + delta)


# ------------------------------------------------------------------------------------------- measures
# The fast-pitch study's measures (~/_/kine_fastpitch_2026-10-11/sim, FP.start, FP.measure, FP.climb, FP.hold).

def start(law, target, y0):
    """FP.start: a fresh aircraft in level flight entering the law's climb restart, along +z."""
    s = State(0.0, y0, 0.0, 0.0, -0.3, 1.5, target, 30.0, True, law)
    s.restart_climb()
    s.climbing = target > 1.0e5 or y0 < target
    s.cmd = law.climb.dive if s.climbing else law.hold.dive
    return s


STUCK = 1500   # a dive longer than this never pulls up


def measure(s, settle, span, keep=False):
    """FP.measure: fly settle + span ticks straight along +z and measure over the whole cycles inside the span.
    Returns a dict (ok False if the law ever went STUCK ticks without a pull-up, or made under 3 pull-ups); with keep,
    the span's per-tick series too."""
    since = 0
    for _ in range(settle):
        if step_look(s, 0.0):
            since = 0
        else:
            since += 1
            if since > STUCK:
                return {"ok": False}
    ys, zs, ps, hs, pulls, clb, troughs = [], [], [], [], [], [], []
    for i in range(span):
        pulled = step_look(s, 0.0)
        ys.append(s.y); zs.append(s.z); ps.append(s.pitch); hs.append(s.h_speed)
        clb.append(s.climbing); troughs.append(s.trough)
        if pulled:
            pulls.append(i)
            since = 0
        else:
            since += 1
            if since > STUCK:
                return {"ok": False}
    if len(pulls) < 3:
        return {"ok": False}
    a, b = pulls[0], pulls[-1]
    n = len(pulls) - 1
    dt = b - a
    m = {"ok": True, "cycles": n, "period": dt / n,
         "climb": (ys[b] - ys[a]) / dt * 20.0, "speed": (zs[b] - zs[a]) / dt * 20.0,
         "pitch_min": min(ps[a + 1:b + 1]), "pitch_max": max(ps[a + 1:b + 1]),
         "max_rate": max(abs(ps[i] - ps[i - 1]) for i in range(a + 1, b + 1))}
    tr = [troughs[i] - s.target for i in pulls[1:]]
    peaks = [max(ys[pulls[k - 1]:pulls[k]]) - s.target for k in range(1, len(pulls))]
    m["trough"] = sum(tr) / n
    m["min_trough"] = min(tr)
    m["peak"] = max(peaks)
    m["climb_frac"] = sum(1 for i in pulls[1:] if clb[i]) / n
    if keep:
        m.update(ys=ys, zs=zs, ps=ps, hs=hs, pulls=pulls)
    return m


def climb_measure(law):
    """FP.climb: the steady climb, every cycle a CLIMB cycle (the target far above)."""
    return measure(start(law, 1.0e9, 0.0), 4000, 12000)


def hold_measure(law):
    """FP.hold: the closed-loop hold at 350, both profiles, the law's own choice at each pull-up."""
    return measure(start(law, 350.0, 350.0), 6000, 24000)


# The level-off law as the study's search parameterised it: x = [dive, trigger (climb) or margin (hold),
# level, level ticks, pull-up, pull-up ticks, sweep gain, sweep toward, sweep ticks, glide, glide ticks]
# (OptFP.shaped; FP.shapedCmd rounds the tick counts with Math.round).

SHAPED_LO_CLIMB = [20, 30, -40, 0, -90, 0, 0.005, -60, 0, 0, 0]
SHAPED_HI_CLIMB = [70, 60, 40, 30, 0, 20, 0.3, 40, 150, 70, 120]
SHAPED_LO_HOLD = [20, 0, -40, 0, -90, 0, 0.005, -60, 0, 0, 0]
SHAPED_HI_HOLD = [70, 30, 40, 30, 0, 20, 0.3, 40, 150, 70, 250]
SHAPED_SEED_CLIMB = [43, 43, 0, 15, -85, 3, 0.045, -12, 70, 30, 65]
SHAPED_SEED_HOLD = [43, 6, 0, 15, -85, 3, 0.045, -12, 70, 30, 65]


def shaped(x, hold=False):
    """A Program from the search's vector."""
    x = [float(v) for v in x]
    return Program(x[0], x[2], jround(x[3]), x[4], jround(x[5]), x[6], x[7], jround(x[8]), x[9], jround(x[10]),
                   math.nan if hold else x[1])


def parse_profile(text, hold=False):
    """A profile from the study's printed form: "(dive / pull-up / trigger or alt / hold / sweep)" for the June law's
    shape, or "shaped(dive D, trig T; level L for n, pull-up U for n, sweep k toward A for n, glide G for n)"."""
    nums = [float(v) for v in re.findall(r"-?\d+(?:\.\d+)?", text)]
    if text.startswith("shaped"):
        if "trig alt" in text:
            d, l, nl, u, h, k, a, ns, g, ng = nums
            t = math.nan
        else:
            d, t, l, nl, u, h, k, a, ns, g, ng = nums
        return Program(d, l, int(nl), u, int(h), k, a, int(ns), g, int(ng), math.nan if hold else t)
    if "/ alt /" in text:
        d, u, h, sw = nums
        return Step(d, u, math.nan, int(h), sw)
    d, u, t, h, sw = nums
    return Step(d, u, t, int(h), sw)


def neg_climb(x, smooth, cap):
    """OptFP.fClimb with no speed floor, for the level-off climb: minus the climb rate (m/s); 1e3 if it fails."""
    law = Law(shaped(x), JUNE_HOLD, JUNE_MARGIN, Response(smooth, cap))
    m = climb_measure(law)
    if not m["ok"] or not math.isfinite(m["climb"]):
        return 1e3
    return -m["climb"]


def neg_cruise(x, smooth, cap, climb):
    """OptFP.fHold for the level-off hold: minus the closed-loop ground speed at 350 (m/s), with penalties if the
    altitude isn't held (mean trough under 1 above the target, any trough over 5 below it, or a drift); the climb
    profile fixed at `climb`."""
    law = Law(climb, shaped(x, hold=True), x[1], Response(smooth, cap))
    m = hold_measure(law)
    if not m["ok"] or not math.isfinite(m["speed"]):
        return 1e3
    pen = max(0.0, 1.0 - m["trough"]) * 0.5 + max(0.0, -5.0 - m["min_trough"]) * 0.5 + max(0.0, abs(m["climb"]) - 0.01) * 50
    return -m["speed"] + pen


# ------------------------------------------------------------------------------------------- the holding circle
# autoflight/HoldModel.java

HOLD_OFFSET, HOLD_CENTRE_GAIN, HOLD_CLIMB_CAP = 7.0, 2.0, 5.0


def hold_look_yaw(x, z, fix_x, fix_z, velocity_yaw, climbing):
    """HoldModel.lookYaw: the look OFFSET right of the velocity, CENTRE_GAIN less toward the fix and more away from
    it (so the circle drifts until the fix is its centre), at most CLIMB_CAP while the law climbs."""
    to_fix = vel_yaw(fix_x - x, fix_z - z)
    off = HOLD_OFFSET - HOLD_CENTRE_GAIN * math.cos(math.radians(wrap180(to_fix - velocity_yaw)))
    if climbing:
        off = min(off, HOLD_CLIMB_CAP)
    return velocity_yaw + off


# ------------------------------------------------------------------------------------------- the landing
# autoflight/LandingModel.java

APPROACH, L_DIVE, BRAKE, MID, FINAL = 0, 1, 2, 3, 4
LANDING_PHASES = {APPROACH: "approach", L_DIVE: "spiral plunge", BRAKE: "brake", MID: "middle spiral", FINAL: "finish"}
ENTRY, RADIUS, RADIAL_GAIN = 8.0, 2.0, 20.0
APPROACH_PITCH, DIVE_PITCH = 5.0, 60.0
BRAKE_HEIGHT, BRAKE_PITCH = 60.0, -30.0
MID_PITCH, FINAL_HEIGHT, FINAL_PITCH = 25.0, 10.0, -5.0
SLOW_SINK, YAW_RATE, MAX_OFFSET = -0.35, 15.0, 1.5


class LandingLaw:
    """LandingModel.Law: the phase and this tick's look command."""

    def __init__(self):
        self.phase, self.yaw, self.pitch = APPROACH, 0.0, 0.0

    def update(self, x, y, z, vy, cx, top, cz):
        dx, dz = cx - x, cz - z
        dist = math.sqrt(dx * dx + dz * dz)
        h = y - top
        bearing = math.degrees(math.atan2(-dx, dz))
        if self.phase == APPROACH and dist <= ENTRY:
            self.phase = L_DIVE
        if self.phase == L_DIVE and h <= BRAKE_HEIGHT:
            self.phase = BRAKE
        if self.phase == BRAKE and vy > SLOW_SINK:
            self.phase = MID if h > FINAL_HEIGHT else FINAL
        if self.phase == MID and h <= FINAL_HEIGHT:
            self.phase = FINAL
        if self.phase == APPROACH:
            self.yaw, self.pitch = bearing, APPROACH_PITCH
        elif self.phase in (L_DIVE, MID):
            corr = max(-90.0, min(90.0, (dist - RADIUS) * RADIAL_GAIN))
            self.yaw = bearing + 90.0 - corr
            self.pitch = DIVE_PITCH if self.phase == L_DIVE else MID_PITCH
        elif self.phase == BRAKE:
            self.yaw, self.pitch = bearing, BRAKE_PITCH
        else:
            self.yaw, self.pitch = bearing, FINAL_PITCH


def landing_simulate(law, x, y, z, vx, vy, vz, yaw, pitch, cx, top, cz, max_ticks, freeze=None,
                     response=LANDING_RESPONSE):
    """LandingModel.simulate: roll a landing out to the spot's height (the ground level with it): the look turns at
    most YAW_RATE a tick toward the command and the pitch eases with the landing's response. Returns a dict: touched,
    offset (blocks from the spot's centre), damage (fall damage), ticks, and per tick x, y, z, phase.
    freeze=(start, ticks): the look held for those ticks (the landing study's 1 s freeze test). response: the pitch
    response (the mod's is the landing's own, 0.15 / 7)."""
    xs, ys, zs, phases = [x], [y], [z], [law.phase]
    v = [vx, vy, vz]
    fall = 1.0
    for t in range(1, max_ticks + 1):
        law.update(x, y, z, v[1], cx, top, cz)
        if not (freeze and freeze[0] <= t < freeze[0] + freeze[1]):
            yaw += max(-YAW_RATE, min(YAW_RATE, wrap180(law.yaw - yaw)))
            pitch = response.ease(pitch, law.pitch)
        physics_step(yaw, pitch, v)
        x += v[0]; y += v[1]; z += v[2]
        if v[1] < 0:
            fall -= v[1]
        if v[1] > -0.5 and fall > 1.0:       # gliding: a slow tick resets the fall (Entity.checkFallDistanceAccumulation)
            fall = 1.0
        xs.append(x); ys.append(y); zs.append(z); phases.append(law.phase)
        if y <= top:
            off = math.sqrt((x - cx) ** 2 + (z - cz) ** 2)
            return dict(touched=True, offset=off, damage=max(0.0, fall - 3.0), ticks=t, xs=xs, ys=ys, zs=zs, phases=phases)
    return dict(touched=False, offset=math.nan, damage=math.nan, ticks=max_ticks, xs=xs, ys=ys, zs=zs, phases=phases)


# autoflight/LandingTime.java: the landing's seconds from a height above the world's bottom (a table rolled out
# offline by the mod's LandingTimeGenerator; the reserve the durability readout keeps).
LANDING_TIME_HEIGHT = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64,
                       96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 480, 512, 640, 768, 896, 1024, 1152,
                       1280, 1408, 1536, 1664, 1792, 1920, 2048, 2176]
LANDING_TIME_SECONDS = [0.05, 17.00, 22.20, 23.80, 25.40, 26.80, 28.05, 28.40, 29.55, 29.90, 31.00, 16.10, 16.30,
                        17.50, 18.50, 19.35, 20.10, 20.85, 21.60, 22.30, 23.05, 23.75, 24.50, 25.20, 25.95, 15.65,
                        16.70, 17.80, 18.95, 20.10, 21.25, 22.40, 23.55, 24.70, 25.85, 27.00, 28.15, 29.30, 30.45,
                        31.60, 36.15, 40.75, 45.35, 49.90, 54.50, 59.10, 63.70, 68.25, 72.85, 77.45, 82.00, 86.60,
                        91.20]


def landing_seconds(height):
    """LandingTime.seconds."""
    H, S = LANDING_TIME_HEIGHT, LANDING_TIME_SECONDS
    if not height > H[0]:
        return S[0]
    i = 1
    while i < len(H) - 1 and H[i] < height:
        i += 1
    return S[i - 1] + (S[i] - S[i - 1]) * (height - H[i - 1]) / (H[i] - H[i - 1])


# ------------------------------------------------------------------------------------------- rockets
# fireworks/RocketBurn.java

def rocket_min_ticks(duration):
    """A rocket burns lifetime + 1 ticks, lifetime = 10 (1 + duration) + nextInt(6) + nextInt(7)."""
    return 10 * (1 + duration) + 1


def rocket_max_ticks(duration):
    return rocket_min_ticks(duration) + 11


def rocket_mean_ticks(duration):
    return rocket_min_ticks(duration) + 5.5


def rocket_still_burning(duration, elapsed):
    """RocketBurn.stillBurning: the chance a rocket still pushes after `elapsed` ticks (42 equally likely pairs)."""
    extra_needed = elapsed - rocket_min_ticks(duration)
    if extra_needed < 0:
        return 1.0
    return sum(1 for a in range(6) for b in range(7) if a + b > extra_needed) / 42.0


# autoflight/TakeoffModel.java

CLIMB_PITCH = -70.0       # the powered climb, 70 deg above the horizon
PITCH_RATE = 6.0          # leaning over or looking up, 120 deg/s
CLEARANCE = 6.0           # the leaning climb keeps this over the terrain
HOLD_BAND = 5.0           # after the hand-over, never more than this below the cruise
LATENCY = 2               # ticks from a rocket's use to its first push
MAX_ROCKETS = 40
HOLD_TICKS, SETTLE_TICKS, CLIMB_TICKS = 1500, 60, 600
JUMP_V = 0.42
DRAG_V = f32(0.98)


def boost(yaw, pitch, v):
    """TakeoffModel.boost: a rocket's push on a gliding player, v = v + 0.1 L + 0.5 (1.5 L - v), i.e. 0.5 v + 0.85 L."""
    pr = math.radians(pitch)
    yr = -math.radians(yaw)
    cp = math.cos(pr)
    lx, ly, lz = math.sin(yr) * cp, -math.sin(pr), math.cos(yr) * cp
    v[0] += lx * 0.1 + (lx * 1.5 - v[0]) * 0.5
    v[1] += ly * 0.1 + (ly * 1.5 - v[1]) * 0.5
    v[2] += lz * 0.1 + (lz * 1.5 - v[2]) * 0.5


def toward(pitch, target, rate):
    return pitch + max(-rate, min(rate, target - pitch))


def holds_cruise(y, vx, vy, vz, pitch, yaw, target):
    """TakeoffModel.holdsCruise: would the autopilot, handed over now in altitude hold with no more rockets, never go
    below target - HOLD_BAND after its first dive and still be holding (not climbing) at the end?"""
    s = State(0.0, y, 0.0, vx, vy, vz, target, pitch, False)
    s.restart_hold()
    low = math.inf
    for k in range(HOLD_TICKS):
        step_look(s, yaw)
        if k >= SETTLE_TICKS:
            low = min(low, s.y)
    return low >= target - HOLD_BAND and not s.climbing


def lean_clear(x, y, z, vx, vy, vz, pitch, yaw, height_at, top):
    """TakeoffModel.leanClear: may the climb lean over now (keeping CLEARANCE over the terrain until above top)?"""
    v = [vx, vy, vz]
    for _ in range(CLIMB_TICKS):
        if y > top + CLEARANCE:
            return True
        pitch = toward(pitch, CLIMB_PITCH, PITCH_RATE)
        physics_step(yaw, pitch, v)
        x += v[0]; y += v[1]; z += v[2]
        boost(yaw, pitch, v)
        h = height_at(x, z)
        if math.isnan(h) or y < h + CLEARANCE:
            return False
    return False


def takeoff(x, y, z, yaw, target, burn, height_at, top, max_points):
    """TakeoffModel.fromGround then fly: the jump, the elytra opening on the third tick, the first rocket the tick
    after; straight up until lean_clear, then leaning to CLIMB_PITCH; at each burn-out another rocket unless
    holds_cruise; after the hand-over the autopilot's hold fills the rest of max_points. Rockets burn `burn` ticks.
    Returns a dict: xs, ys, zs, rockets, reaches_cruise, ticks (to the hand-over), uses (tick of each rocket use)."""
    xs, ys, zs = [x], [y], [z]
    ground, vy = y, JUMP_V
    for _ in range(2):                                   # the jump: air movement first, then gravity and drag
        y += vy
        vy = (vy - GRAV) * DRAG_V
        xs.append(x); ys.append(y); zs.append(z)
    v = [0.0, vy, 0.0]
    physics_step(yaw, -90.0, v)                          # the elytra opens and flies this tick
    x += v[0]; y += v[1]; z += v[2]
    xs.append(x); ys.append(y); zs.append(z)
    pitch, lean, burn_left, wait = -90.0, False, 0, -1
    fire, rockets, ticks, flown, cruise, uses = True, 0, 0, 0, False, []
    while rockets <= MAX_ROCKETS and ticks < 4000:
        ticks += 1
        if fire:
            rockets += 1
            uses.append(3 + flown)
            fire = False
            wait = LATENCY - 1
        if not lean and y - ground > 2.0 and lean_clear(x, y, z, v[0], v[1], v[2], pitch, yaw, height_at, top):
            lean = True
        if lean:
            pitch = toward(pitch, CLIMB_PITCH, PITCH_RATE)
        physics_step(yaw, pitch, v)
        x += v[0]; y += v[1]; z += v[2]
        flown += 1
        if wait > 0:
            wait -= 1
        elif wait == 0:
            wait = -1
            burn_left = burn
        burnt_out = False
        if burn_left > 0:
            boost(yaw, pitch, v)
            burn_left -= 1
            burnt_out = burn_left == 0
        if len(xs) < max_points:
            xs.append(x); ys.append(y); zs.append(z)
        if burnt_out and wait < 0:
            if holds_cruise(y, v[0], v[1], v[2], pitch, yaw, target):
                cruise = True
                break
            fire = True
    if cruise:
        s = State(x, y, z, v[0], v[1], v[2], target, pitch, False)
        s.restart_hold()
        while len(xs) < max_points:
            step_look(s, yaw)
            xs.append(s.x); ys.append(s.y); zs.append(s.z)
    return dict(xs=xs, ys=ys, zs=zs, rockets=rockets, reaches_cruise=cruise, ticks=3 + flown, uses=uses)


# fireworks/RocketHit.java: whether a rocket used while already fast hits its own player on the server

HIT_TICKS, HIT_HALF, HIT_HEIGHT, HIT_HAND = 40, 0.3, 0.6, 0.5
HIT_SPEEDS = (1.2, 1.5, 1.7)
HIT_MARGIN_DEG = 3.0


def _face(da, fa, fb, fc, db, dc, a0, a1, b0, b1, c0, c1):
    if abs(da) <= 1.0e-7:
        return False
    s = ((a0 if da > 0 else a1) - fa) / da
    if not (0.0 < s < 1.0):
        return False
    pb, pc = fb + s * db, fc + s * dc
    return b0 - 1.0e-7 < pb < b1 + 1.0e-7 and c0 - 1.0e-7 < pc < c1 + 1.0e-7


def _enters(x0, y0, z0, x1, y1, z1, fx, fy, fz, dx, dy, dz):
    """AABB.clip's test: the segment from f along d crosses a face it moves toward, inside that face."""
    return (_face(dx, fx, fy, fz, dy, dz, x0, x1, y0, y1, z0, z1)
            or _face(dy, fy, fz, fx, dz, dx, y0, y1, z0, z1, x0, x1)
            or _face(dz, fz, fx, fy, dx, dy, z0, z1, x0, x1, y0, y1))


def rocket_hit_tick(yaw, pitch, right_arm, speed):
    """RocketHit.hitTick: the rocket's life at the hit, or -1 if it never hits its player within HIT_TICKS."""
    ry, rp = math.radians(yaw), math.radians(pitch)
    lx, ly, lz = -math.sin(ry) * math.cos(rp), -math.sin(rp), math.cos(ry) * math.cos(rp)
    ha = math.radians(yaw + (80.0 if right_arm else -80.0))
    fx, fy, fz = -math.sin(ha) * HIT_HAND, 0.0, math.cos(ha) * HIT_HAND
    vx, vy, vz = lx * speed, ly * speed, lz * speed
    for n in range(1, HIT_TICKS + 1):
        vx, vy, vz = 0.5 * vx + 0.85 * lx, 0.5 * vy + 0.85 * ly, 0.5 * vz + 0.85 * lz
        m = max(0.0, min(0.3, (n - 2) / 20.0))           # ProjectileUtil.computeMargin at the rocket's age
        if _enters(-HIT_HALF - m, -m, -HIT_HALF - m, HIT_HALF + m, HIT_HEIGHT + m, HIT_HALF + m, fx, fy, fz, vx, vy, vz):
            return n - 1
    return -1


def rocket_hits(yaw, pitch, right_arm):
    """RocketHit.hits: at any of a few fast speeds at the use."""
    return any(rocket_hit_tick(yaw, pitch, right_arm, sp) >= 0 for sp in HIT_SPEEDS)


def rocket_safe(yaw, pitches, right_arm):
    """RocketHit.safe: no look within HIT_MARGIN_DEG of this yaw hits, at any of the pitches."""
    for pitch in pitches:
        off = -HIT_MARGIN_DEG
        while off <= HIT_MARGIN_DEG:
            if rocket_hits(yaw + off, pitch, right_arm):
                return False
            off += 0.5
    return True


def rocket_safe_yaw(wanted, pitches, right_arm):
    """RocketHit.safeYaw: the nearest safe yaw within 45 deg (Java float arithmetic), else a cardinal yaw."""
    wanted = f32(wanted)
    off = 0.0
    while off <= 45.0:
        if rocket_safe(wanted + off, pitches, right_arm):
            return f32(wanted + off)
        if off > 0.0 and rocket_safe(wanted - off, pitches, right_arm):
            return f32(wanted - off)
        off += 0.5
    return f32(jround(f32(wanted / 90.0)) * 90.0)
