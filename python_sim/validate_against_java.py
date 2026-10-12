"""Check kine_flight.py against the mod's own Java, tick for tick.

Runs JavaTrace.java on the compiled mod (build/classes/java/main; build first with ./gradlew compileJava), flies the
same cases with kine_flight.py, and prints the largest difference per case. Run from anywhere:

    python3 python_sim/validate_against_java.py

A difference near 1e-12 is floating-point rounding (Java's and C's libm can differ in the last bit); a real
mismatch shows as a different phase, rocket count or hit tick, or a difference of a block or more.
"""

import math
import os
import subprocess
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.dont_write_bytecode = True            # no __pycache__ in the repo
sys.path.insert(0, HERE)
import kine_flight as kf  # noqa: E402

ROOT = os.path.dirname(HERE)


def java_lines():
    classes = os.path.join(ROOT, "build", "classes", "java", "main")
    if not os.path.isdir(classes):
        sys.exit("no compiled classes at build/classes/java/main: run ./gradlew compileJava first")
    out = subprocess.run(["java", "-cp", classes, os.path.join(HERE, "JavaTrace.java")],
                         capture_output=True, text=True, check=True).stdout
    rows = defaultdict(list)
    for line in out.splitlines():
        c, *v = line.split(",")
        rows[c].append(v)
    return rows


def num(v):
    return 1.0 if v == "true" else 0.0 if v == "false" else float(v)


def python_lines():
    rows = defaultdict(list)
    s = kf.State(0, 200, 0, 0, -0.5, 1.5, 350, 20, True)
    s.cmd = 40
    for t in range(1, 4001):
        kf.step(s, 0.0)
        rows["climb"].append([t, s.y, s.z, s.vy, s.pitch, s.cmd, s.phase, s.climbing])
    s = kf.State(0, 300, 0, 0, -0.5, 1.5, 300, 20, False)
    s.cmd = 38
    for t in range(1, 601):
        kf.step(s, 90.0)
        rows["turn"].append([t, s.x, s.y, s.z, s.pitch])
    s = kf.State(0, 400, 0, 0, -0.3, 1.5, 350, 30, False)
    s.cmd = kf.ALT_HOLD.dive
    for _ in range(4000):
        kf.step(s, 0.0)
    s.x = s.z = 0.0
    for t in range(1, 3001):
        kf.step_look(s, kf.hold_look_yaw(s.x, s.z, 0, 0, kf.vel_yaw(s.vx, s.vz), s.climbing))
        rows["hold"].append([t, s.x, s.y, s.z, s.pitch])
    r = kf.landing_simulate(kf.LandingLaw(), 0, 350, -200, 0, 0, 1.5, 0, 10, 3.5, 64, 40.5, 4000)
    rows["landing_end"].append([r["touched"], r["offset"], r["damage"], r["ticks"]])
    for i in range(len(r["xs"])):
        rows["landing"].append([i, r["xs"][i], r["ys"][i], r["zs"][i]])
    p = kf.takeoff(0.5, 64, 0.5, 0, 350, 27, lambda x, z: 64.0, 319, 3000)
    rows["takeoff_end"].append([p["rockets"], p["reaches_cruise"], p["ticks"], len(p["xs"])])
    for i in range(len(p["xs"])):
        rows["takeoff"].append([i, p["xs"][i], p["ys"][i], p["zs"][i]])
    for pitch in (-85.0, -70.0):
        for arm in (0, 1):
            for yaw in range(360):
                rows["hit"].append([yaw, pitch, arm, kf.rocket_hit_tick(yaw, pitch, arm == 1, 1.7)])
    for w in range(0, 360, 7):
        rows["safeyaw"].append([w, kf.rocket_safe_yaw(w, [-85, -80, -75, -70, -65], True)])
    h = 0.0
    while h < 2300:
        rows["landtime"].append([h, kf.landing_seconds(h)])
        h += 7.3
    return rows


def main():
    j, py = java_lines(), python_lines()
    worst_all = 0.0
    for case in j:
        a, b = j[case], py[case]
        if len(a) != len(b):
            print(f"{case:12s} LENGTH DIFFERS: java {len(a)} rows, python {len(b)}")
            worst_all = math.inf
            continue
        worst = 0.0
        for ra, rb in zip(a, b):
            for va, vb in zip(ra, rb):
                d = abs(num(va) - float(vb))
                worst = max(worst, d)
        worst_all = max(worst_all, worst)
        print(f"{case:12s} {len(a):5d} rows   largest difference {worst:.3g}")
    print("OK: the Python flies what the Java flies" if worst_all < 1e-6 else "MISMATCH")


if __name__ == "__main__":
    main()
