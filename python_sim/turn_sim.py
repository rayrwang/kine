#!/usr/bin/env python3
"""3-D elytra flight model for turn characterization (MC 26.1.2).

Verbatim port of LivingEntity.updateFallFlyingMovement (full 3-D), driven by the
shipped FlightModel climb/hold porpoise law for pitch and a commanded look-yaw for turns.

Goals:
  Exp1  validate the 3-D port reproduces the verified vertical-plane FlightModel at zero turn
  Exp2  characterize turn rate / radius / speed-bleed vs held look-offset delta
  Exp3  GATING: does a *climbing* porpoise still net-climb while turning? at what radius?

Units: blocks, blocks/tick. m/s = blocks/tick * 20.
"""
import math

# ---- shipped FlightModel constants ----
GRAV, SMOOTH, CAP = 0.08, 0.15, 7.0
C_DIVE, C_UP, C_TRIG, C_SWEEP, C_TOP = 40.0, -62.0, 44.0, 18.0/20.0, 11
L_DIVE, L_UP, L_SWEEP, L_TOP = 38.0, -66.0, 24.0/20.0, 13
DESC_MARGIN = 6.0
PH_HOLD, PH_TOP, PH_SWEEP = 0, 1, 2

# ---------------- physics ----------------

def look_angle(yaw_deg, pitch_deg):
    """MC calculateViewVector -> unit look vector (x,y,z)."""
    pr = math.radians(pitch_deg)
    yr = -math.radians(yaw_deg)
    cy, sy = math.cos(yr), math.sin(yr)
    cp, sp = math.cos(pr), math.sin(pr)
    return (sy*cp, -sp, cy*cp)

def physics3d(vx, vy, vz, yaw_deg, pitch_deg):
    """One tick of updateFallFlyingMovement in full 3-D (pitch already eased)."""
    lx, ly, lz = look_angle(yaw_deg, pitch_deg)
    lean = math.radians(pitch_deg)
    lookHor = math.sqrt(lx*lx + lz*lz)
    moveHor = math.sqrt(vx*vx + vz*vz)
    lift = math.cos(lean)**2
    vy += GRAV*(-1.0 + lift*0.75)
    if vy < 0.0 and lookHor > 0.0:
        conv = vy * -0.1 * lift
        vx += lx*conv/lookHor; vy += conv; vz += lz*conv/lookHor
    if lean < 0.0 and lookHor > 0.0:
        conv = moveHor * (-math.sin(lean)) * 0.04
        vx += -lx*conv/lookHor; vy += conv*3.2; vz += -lz*conv/lookHor
    if lookHor > 0.0:
        vx += (lx/lookHor*moveHor - vx)*0.1
        vz += (lz/lookHor*moveHor - vz)*0.1
    return vx*0.99, vy*0.98, vz*0.99

def vel_yaw(vx, vz):
    """MC yaw (deg) whose horizontal look direction (-sin y, cos y) points along (vx,vz)."""
    return math.degrees(math.atan2(-vx, vz))

# ---------------- controller (port of FlightModel) ----------------

def ease(pitch, cmd):
    pitch += max(-CAP, min(CAP, (cmd - pitch)*SMOOTH))
    return max(-90.0, min(90.0, pitch))

class Ctrl:
    """Porpoise law: keys off altitude y and horizontal speed (m/s)."""
    def __init__(self, target, pitch, climbing):
        self.target = target; self.pitch = pitch; self.cmd = pitch
        self.phase = PH_HOLD; self.topT = 0; self.climbing = climbing
        self._load()
    def _load(self):
        if self.climbing: self.pDive,self.pUp,self.pSweep,self.pTop = C_DIVE,C_UP,C_SWEEP,C_TOP
        else:             self.pDive,self.pUp,self.pSweep,self.pTop = L_DIVE,L_UP,L_SWEEP,L_TOP
    def update(self, y, hSpeed_ms):
        if self.phase == PH_HOLD:
            self.cmd = self.pDive
            pull = (hSpeed_ms >= C_TRIG) if self.climbing else (y <= self.target + DESC_MARGIN)
            if pull:
                self.climbing = y < self.target
                self._load()
                self.phase = PH_TOP; self.topT = 0; self.cmd = self.pUp
        elif self.phase == PH_TOP:
            self.cmd = self.pUp; self.topT += 1
            if self.topT >= self.pTop: self.phase = PH_SWEEP
        else:
            self.cmd += self.pSweep
            if self.cmd >= self.pDive: self.cmd = self.pDive; self.phase = PH_HOLD

# ---------------- vertical-plane reference (shipped FlightModel) ----------------

def physics_vertical(pitch, vx, vy):
    f = math.radians(pitch); cf = math.cos(f); sf = math.sin(f); lift = cf*cf; h0 = vx
    vy += GRAV*(-1.0 + lift*0.75)
    if vy < 0.0 and cf > 0.0:
        conv = vy*-0.1*lift; vx += conv; vy += conv
    if f < 0.0 and cf > 0.0:
        conv3 = h0*(-sf)*0.04; vx -= conv3; vy += conv3*3.2
    if cf > 0.0:
        vx += (h0 - vx)*0.1
    return vx*0.99, vy*0.98

# ---------------- simulation loop ----------------

def run(delta_deg, ticks, target=2000.0, y0=120.0, speed0=1.5, climbing=True,
        warmup=600, sample_from=None):
    """Fly the porpoise; after `warmup` ticks hold look-offset delta_deg.
       Returns dict of metrics over the post-warmup window."""
    # seed heading +Z (yaw 0)
    vx, vy, vz = 0.0, 0.0, speed0
    x = y = z = 0.0; y = y0
    c = Ctrl(target, 0.0, climbing)
    ys=[]; turns=[]; hs=[]; prev_yaw=None; yaw_unwrapped=0.0
    if sample_from is None: sample_from = warmup
    for t in range(ticks):
        moveHor = math.sqrt(vx*vx+vz*vz)
        delta = delta_deg if t >= warmup else 0.0
        yaw = vel_yaw(vx, vz) + delta
        c.pitch = ease(c.pitch, c.cmd)
        vx, vy, vz = physics3d(vx, vy, vz, yaw, c.pitch)
        x += vx; y += vy; z += vz
        moveHor_post = math.sqrt(vx*vx+vz*vz)
        c.update(y, moveHor_post*20.0)
        if t >= sample_from:
            ys.append(y); hs.append(moveHor_post)
            vyaw = vel_yaw(vx, vz)
            if prev_yaw is not None:
                d = vyaw - prev_yaw
                while d > 180: d -= 360
                while d < -180: d += 360
                yaw_unwrapped += d
                turns.append(d)
            prev_yaw = vyaw
    n = len(ys)
    climb_rate = (ys[-1]-ys[0])/(n-1)*20.0 if n > 1 else 0.0   # m/s
    avg_turn = (sum(turns)/len(turns)) if turns else 0.0        # deg/tick
    turn_rate = avg_turn*20.0                                   # deg/s
    avg_speed = (sum(hs)/len(hs))*20.0 if hs else 0.0           # m/s horiz
    # turn radius: r = speed / omega ; omega in rad/s
    omega = math.radians(abs(turn_rate))
    radius = (avg_speed/omega) if omega > 1e-9 else float('inf')
    return dict(delta=delta_deg, climb_ms=climb_rate, turn_deg_s=turn_rate,
                radius=radius, speed_ms=avg_speed, total_turn=yaw_unwrapped, n=n)

# ---------------- experiments ----------------

def exp1_validate():
    print("=== Exp1: 3-D port vs vertical-plane FlightModel (delta=0) ===")
    # vertical reference
    vx, vy = 1.5, 0.0; yv = 120.0; cv = Ctrl(2000.0, 0.0, True)
    # 3-D, heading +Z, no turn
    vx3, vy3, vz3 = 0.0, 0.0, 1.5; y3 = 120.0; c3 = Ctrl(2000.0, 0.0, True)
    maxdy = 0.0; maxdh = 0.0
    for t in range(1500):
        # vertical
        cv.pitch = ease(cv.pitch, cv.cmd)
        vx, vy = physics_vertical(cv.pitch, vx, vy); yv += vy; cv.update(yv, vx*20)
        # 3-D
        yaw = vel_yaw(vx3, vz3)
        c3.pitch = ease(c3.pitch, c3.cmd)
        vx3, vy3, vz3 = physics3d(vx3, vy3, vz3, yaw, c3.pitch); y3 += vy3
        h3 = math.sqrt(vx3*vx3+vz3*vz3); c3.update(y3, h3*20)
        maxdy = max(maxdy, abs(yv - y3)); maxdh = max(maxdh, abs(vx - h3))
    print(f"  over 1500 ticks: max |dy| = {maxdy:.3e} blocks, max |d(hSpeed)| = {maxdh:.3e} blk/tick")
    print(f"  ({'MATCH' if maxdy < 1e-9 and maxdh < 1e-9 else 'MISMATCH'})")

def exp2_turns():
    print("\n=== Exp2: turn characterization (climbing porpoise, 2000-tick hold) ===")
    print(f"  {'look off':>8} {'turn rate':>10} {'radius':>9} {'speed':>8} {'climb':>8} {'360 in':>8}")
    print(f"  {'(deg)':>8} {'(deg/s)':>10} {'(blocks)':>9} {'(m/s)':>8} {'(m/s)':>8} {'(ticks)':>8}")
    for d in [0, 5, 10, 15, 20, 25, 30, 40, 50, 60, 75, 90]:
        r = run(d, ticks=2600, warmup=600)
        ttime = abs(360.0/r['turn_deg_s']) if abs(r['turn_deg_s'])>1e-6 else float('inf')
        print(f"  {d:>8} {r['turn_deg_s']:>10.2f} {r['radius']:>9.1f} "
              f"{r['speed_ms']:>8.1f} {r['climb_ms']:>+8.3f} {ttime*20:>8.0f}")

def exp3_gating():
    print("\n=== Exp3: GATING - climbing-turn feasibility (find tightest net-climb) ===")
    print("  sweeping look-offset; climb_ms>0 means orbit gains altitude")
    last_pos = None; first_neg = None
    for d in [0, 5, 10, 15, 18, 20, 22, 25, 28, 30, 35, 40]:
        r = run(d, ticks=4600, warmup=600)   # long window: many porpoise cycles
        tag = "CLIMB" if r['climb_ms'] > 0.001 else ("sink " if r['climb_ms'] < -0.001 else "flat ")
        print(f"  off={d:>3}deg  climb={r['climb_ms']:>+7.3f} m/s  radius={r['radius']:>7.1f} blk  speed={r['speed_ms']:>5.1f}  [{tag}]")
        if r['climb_ms'] > 0.001: last_pos = (d, r['radius'])
        if r['climb_ms'] <= 0.001 and first_neg is None: first_neg = (d, r['radius'])
    print(f"\n  tightest net-climb tested: offset={last_pos[0]}deg at radius ~{last_pos[1]:.0f} blocks" if last_pos else "  no net-climb found")
    if first_neg: print(f"  first non-climb: offset={first_neg[0]}deg (radius ~{first_neg[1]:.0f} blocks)")

if __name__ == "__main__":
    exp1_validate()
    exp2_turns()
    exp3_gating()


def run_racetrack(delta_turn, straight_ticks, ticks, target=4000.0, y0=150.0,
                  speed0=1.5, warmup=600):
    """Racetrack/holding pattern: straight (climbing) legs joined by gentle 180s.
       Returns net climb (m/s) and horizontal footprint (blocks)."""
    vx, vy, vz = 0.0, 0.0, speed0
    x = z = 0.0; y = y0
    c = Ctrl(target, 0.0, True)
    mode = 'straight'; scount = 0; turn_accum = 0.0; prev_yaw = vel_yaw(vx, vz)
    ys=[]; xs=[]; zs=[]
    for t in range(ticks):
        if t < warmup:
            delta = 0.0
        else:
            if mode == 'straight':
                delta = 0.0; scount += 1
                if scount >= straight_ticks:
                    mode = 'turn'; turn_accum = 0.0; prev_yaw = vel_yaw(vx, vz)
            else:
                delta = delta_turn
        yaw = vel_yaw(vx, vz) + delta
        c.pitch = ease(c.pitch, c.cmd)
        vx, vy, vz = physics3d(vx, vy, vz, yaw, c.pitch)
        x += vx; y += vy; z += vz
        c.update(y, math.sqrt(vx*vx+vz*vz)*20.0)
        if t >= warmup and mode == 'turn':
            cyaw = vel_yaw(vx, vz); d = cyaw - prev_yaw
            while d > 180: d -= 360
            while d < -180: d += 360
            turn_accum += d; prev_yaw = cyaw
            if abs(turn_accum) >= 180.0:
                mode = 'straight'; scount = 0
        if t >= warmup:
            ys.append(y); xs.append(x); zs.append(z)
    n = len(ys)
    climb = (ys[-1]-ys[0])/(n-1)*20.0 if n > 1 else 0.0
    foot_w = max(xs)-min(xs); foot_l = max(zs)-min(zs)
    return dict(climb=climb, foot=(foot_w, foot_l), n=n)

if __name__ == "__main__" and len(__import__('sys').argv) > 1 and __import__('sys').argv[1] == 'rt':
    print("=== Racetrack / holding pattern: net climb vs footprint ===")
    print("  continuous circle is shown for comparison (straight=0)")
    print(f"  {'turn off':>8} {'straight':>9} {'climb':>9} {'footprint WxL':>16}")
    print(f"  {'(deg)':>8} {'(ticks)':>9} {'(m/s)':>9} {'(blocks)':>16}")
    for dt, st in [(10,0),(10,100),(10,200),(10,300),
                   (15,100),(15,200),(15,300),
                   (20,150),(20,300),
                   (12,250)]:
        r = run_racetrack(dt, st, ticks=4000+st*6, warmup=600)
        fw, fl = r['foot']
        tag = "circle" if st==0 else "racetrack"
        print(f"  {dt:>8} {st:>9} {r['climb']:>+9.3f}   {fw:>6.0f} x {fl:<6.0f}  [{tag}]")
