#!/usr/bin/env python3
"""2-D terrain-avoidance planner (MC 26.1.2) - BASIC: climb-first / dodge / retreat.

Uses the verified 3-D elytra physics + shipped porpoise law (see turn_sim.py).
Receding-horizon: each plan cycle picks ONE target heading; the closed loop produces
smooth curves. Prioritized search:
  Stage 1  steer toward destination, climb to cruise -> if it clears, done (keep course)
  Stage 2  no straight climb clears -> smallest heading offset that clears (descending dodge)
  Stage 3  nothing clears -> RETREAT (reverse course, fly back out known-clear air)
CLB LK climbing-holding-pattern is deferred (advanced layer).
"""
import math, sys

GRAV, SMOOTH, CAP = 0.08, 0.15, 7.0
C_DIVE, C_UP, C_TRIG, C_SWEEP, C_TOP = 40.0, -62.0, 44.0, 18.0/20.0, 11
L_DIVE, L_UP, L_SWEEP, L_TOP = 38.0, -66.0, 24.0/20.0, 13
DESC_MARGIN = 6.0
PH_HOLD, PH_TOP, PH_SWEEP = 0, 1, 2

DELTA_MAX = 20.0    # max look-offset (deg) the steering will command
BUFFER    = 10.0    # clearance required above ground (blocks)

# ---- physics (verbatim 3-D) ----
def look_angle(yaw, pitch):
    pr = math.radians(pitch); yr = -math.radians(yaw)
    cy, sy = math.cos(yr), math.sin(yr); cp, sp = math.cos(pr), math.sin(pr)
    return (sy*cp, -sp, cy*cp)

def physics3d(vx, vy, vz, yaw, pitch):
    lx, _, lz = look_angle(yaw, pitch)
    lean = math.radians(pitch)
    lookHor = math.sqrt(lx*lx+lz*lz); moveHor = math.sqrt(vx*vx+vz*vz)
    lift = math.cos(lean)**2
    vy += GRAV*(-1.0+lift*0.75)
    if vy < 0 and lookHor > 0:
        c = vy*-0.1*lift; vx += lx*c/lookHor; vy += c; vz += lz*c/lookHor
    if lean < 0 and lookHor > 0:
        c = moveHor*(-math.sin(lean))*0.04; vx += -lx*c/lookHor; vy += c*3.2; vz += -lz*c/lookHor
    if lookHor > 0:
        vx += (lx/lookHor*moveHor - vx)*0.1; vz += (lz/lookHor*moveHor - vz)*0.1
    return vx*0.99, vy*0.98, vz*0.99

def vel_yaw(vx, vz): return math.degrees(math.atan2(-vx, vz))
def wrap180(a):
    while a > 180: a -= 360
    while a < -180: a += 360
    return a
def ease(p, cmd): return max(-90.0, min(90.0, p+max(-CAP, min(CAP,(cmd-p)*SMOOTH))))

# ---- aircraft state (kinematics + porpoise controller) ----
class S:
    def __init__(s, x,y,z,vx,vy,vz, target, pitch, climbing):
        s.x,s.y,s.z,s.vx,s.vy,s.vz = x,y,z,vx,vy,vz
        s.target=target; s.pitch=pitch; s.cmd=pitch; s.phase=PH_HOLD; s.topT=0; s.climbing=climbing
        s._load()
    def _load(s):
        if s.climbing: s.pD,s.pU,s.pS,s.pT = C_DIVE,C_UP,C_SWEEP,C_TOP
        else:          s.pD,s.pU,s.pS,s.pT = L_DIVE,L_UP,L_SWEEP,L_TOP
    def copy(s):
        n=S(s.x,s.y,s.z,s.vx,s.vy,s.vz,s.target,s.pitch,s.climbing)
        n.cmd=s.cmd; n.phase=s.phase; n.topT=s.topT; return n

def step(s, target_heading):
    """One tick: steer toward target_heading (rate-limited) + porpoise + physics + integrate + law."""
    vyaw = vel_yaw(s.vx, s.vz)
    delta = max(-DELTA_MAX, min(DELTA_MAX, wrap180(target_heading - vyaw)))
    look_yaw = vyaw + delta
    s.pitch = ease(s.pitch, s.cmd)
    s.vx, s.vy, s.vz = physics3d(s.vx, s.vy, s.vz, look_yaw, s.pitch)
    s.x += s.vx; s.y += s.vy; s.z += s.vz
    # porpoise law on post-physics state
    h = math.sqrt(s.vx*s.vx+s.vz*s.vz)*20.0
    if s.phase == PH_HOLD:
        s.cmd = s.pD
        pull = (h >= C_TRIG) if s.climbing else (s.y <= s.target+DESC_MARGIN)
        if pull:
            s.climbing = s.y < s.target; s._load(); s.phase=PH_TOP; s.topT=0; s.cmd=s.pU
    elif s.phase == PH_TOP:
        s.cmd=s.pU; s.topT+=1
        if s.topT>=s.pT: s.phase=PH_SWEEP
    else:
        s.cmd+=s.pS
        if s.cmd>=s.pD: s.cmd=s.pD; s.phase=PH_HOLD

CLEARS, HITS, INSUFFICIENT = 1, 0, -1
def rollout(s0, target_heading, terrain, horizon, min_lookahead, dest):
    """Returns (status, progress): progress = displacement projected onto the
       start->dest direction (so sliding sideways past an impassable wall scores ~0)."""
    s = s0.copy(); start_z = s.z; start_x = s.x
    bx, bz = dest
    dx, dz = bx-start_x, bz-start_z; dlen = math.hypot(dx,dz) or 1.0
    ux, uz = dx/dlen, dz/dlen
    status = CLEARS
    for _ in range(horizon):
        step(s, target_heading)
        g = terrain(s.x, s.z)
        if g is None:
            travelled = math.hypot(s.x-start_x, s.z-start_z)
            status = CLEARS if travelled >= min_lookahead else INSUFFICIENT
            break
        if s.y - g < BUFFER:
            status = HITS; break
    progress = (s.x-start_x)*ux + (s.z-start_z)*uz
    return status, progress

# ---- prioritized planner ----
OFFSETS = [10,20,30,45,60,75,90,110,130,150]   # tried smallest-first, both signs
PROG_MIN = 150.0                                 # a turn must close at least this toward dest
def plan(s, dest_bearing, dest, terrain, horizon=900, min_lookahead=400):
    # Stage 1: straight toward destination, climbing
    st, _ = rollout(s, dest_bearing, terrain, horizon, min_lookahead, dest)
    if st == CLEARS:
        return dest_bearing, "CLB"
    # Stage 2: smallest heading offset that clears AND makes real progress toward dest
    best=None
    for off in OFFSETS:
        for sgn in (+1,-1):
            h = dest_bearing + sgn*off
            st, prog = rollout(s, h, terrain, horizon, min_lookahead, dest)
            if st == CLEARS and prog >= PROG_MIN:
                if best is None or off < best[1]: best=(h, off)
        if best: break
    if best: return best[0], "TURN"
    # Stage 3: nothing makes progress -> retreat (reverse course, back over known-clear air)
    return vel_yaw(s.vx, s.vz)+180.0, "RETREAT"

def fly(s, dest, terrain, max_ticks=4000, replan_every=30, arrive=120.0):
    bx,bz = dest
    traj=[]; action="CLB"; tgt=vel_yaw(s.vx,s.vz)
    committed_until=-1; BACKOFF_COMMIT=600; TURN_COMMIT=150
    for t in range(max_ticks):
        if t % replan_every == 0 and t >= committed_until:
            dest_bearing = vel_yaw(bx - s.x, bz - s.z)
            tgt, action = plan(s, dest_bearing, dest, terrain)
            if action == "TURN":
                committed_until = t + TURN_COMMIT          # fly this heading as a straight climbing leg
            elif action == "RETREAT":
                tgt = dest_bearing + 180.0; action = "BACKOFF"
                committed_until = t + BACKOFF_COMMIT
        step(s, tgt)
        traj.append((t, s.x, s.y, s.z, action, s.climbing))
        if math.hypot(bx-s.x, bz-s.z) < arrive: 
            return traj, "ARRIVED"
        if s.y < (terrain(s.x,s.z) or -1e9) + 1.0:
            return traj, "CRASH"
    return traj, "TIMEOUT"

# ---- terrain scenarios (return ground height, or None if unknown) ----
BASE=60.0
def flat(x,z): return BASE
def climbable_slope(x,z):       # rises at ~3% (out-climbable), peak 250
    if z < 200: return BASE
    return min(250.0, BASE + (z-200)*0.03)
def wall_offgap(x,z):           # wide 290 wall at z=700; gap only at 50<x<110 (off the straight path)
    if 690 <= z <= 710 and not (50 <= x <= 110): return 290.0
    return BASE
def wide_wall(x,z):             # impassable wall, no gap, spans far wider than look-ahead
    if 690 <= z <= 710: return 290.0
    return BASE
def box_canyon(x,z):            # enclosed U, opening only toward -z; barrier wider than reach
    if 690<=z<=710 and -600<=x<=600: return 290.0          # front
    if 150<=z<=710 and (-600<=x<=-180): return 290.0       # left wall (wide)
    if 150<=z<=710 and (180<=x<=600):   return 290.0       # right wall (wide)
    return BASE

def summarize(name, traj, outcome, dest):
    actions=[a for *_,a,_ in traj]
    seg=[]; 
    for a in actions:
        if not seg or seg[-1][0]!=a: seg.append([a,1])
        else: seg[-1][1]+=1
    phases=" -> ".join(f"{a}({n})" for a,n in seg)
    ymin=min(p[2] for p in traj); ymax=max(p[2] for p in traj)
    xmin=min(p[1] for p in traj); xmax=max(p[1] for p in traj); zmax=max(p[3] for p in traj)
    print(f"\n[{name}] {outcome}  ticks={len(traj)}")
    print(f"   y range {ymin:.0f}..{ymax:.0f}   x range {xmin:.0f}..{xmax:.0f}   max z {zmax:.0f}  (dest z={dest[1]})")
    print(f"   phases: {phases}")

if __name__ == "__main__":
    dest=(0.0, 3000.0)
    def fresh():
        w = S(0, 2000, 0, 0,0,1.5, 9000.0, 0.0, True)   # very high, climbing, heading +Z
        for _ in range(500): step(w, 0.0)               # settle into the climb limit cycle
        s = w.copy(); s.x=0.0; s.y=180.0; s.z=0.0       # transplant settled state to climb-out altitude
        return s
    for name, terr, d in [("flat", flat, dest),
                           ("climbable slope", climbable_slope, dest),
                           ("wall, off-center gap", wall_offgap, (0.0, 1500.0)),
                           ("wide impassable wall", wide_wall, (0.0, 1500.0)),
                           ("enclosed box canyon", box_canyon, (0.0, 1500.0))]:
        traj, outcome = fly(fresh(), d, terr, max_ticks=6000)
        summarize(name, traj, outcome, d)
        # x position as it passes the wall plane z~700
        at = [p for p in traj if 695 <= p[3] <= 705]
        if at: print(f"   at z~700: x={at[0][1]:.0f}, y={at[0][2]:.0f}")
