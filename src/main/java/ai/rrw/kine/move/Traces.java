package ai.rrw.kine.move;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recorded real ticks and the blocks they ran over, as text, so the movement copy can be checked against the game
 * (a client test records them; a unit test replays the saved file). Pure Java.
 *
 * <p>Format, one item a line: "body" and its numbers; "grid x0 y0 z0 nx ny nz"; "kind i friction speed jump flags
 * block|empty|shape" with the shape as three coordinate lists and the full cells ("xs a,b,.. ys .. zs .. full 0101.."),
 * one per kind; "cells" with the kind of every cell in x, y, z order, run-length coded as kind*count; then per run
 * "run name", "start" with the state's fields, and one "tick keys yaw x y z vx vy vz onGround sprinting" line per tick
 * (the keys and rotation the game used that tick, the state after it).
 */
public final class Traces {
    private Traces() {}

    /** One tick as the game played it: keys, rotation sent, the state after it (older files have no pitch and no
     *  water flags: pitch 0, flags not compared). */
    public record Tick(int keys, float yaw, double x, double y, double z, double vx, double vy, double vz,
                       boolean onGround, boolean sprinting, float pitch, int water, int air) {
        /** No air recorded. */
        public static final int NO_AIR = Integer.MIN_VALUE;

        public Tick(int keys, float yaw, double x, double y, double z, double vx, double vy, double vz, boolean onGround,
                    boolean sprinting) {
            this(keys, yaw, x, y, z, vx, vy, vz, onGround, sprinting, 0.0F, -1, NO_AIR);
        }

        public Tick(int keys, float yaw, double x, double y, double z, double vx, double vy, double vz, boolean onGround,
                    boolean sprinting, float pitch, int water) {
            this(keys, yaw, x, y, z, vx, vy, vz, onGround, sprinting, pitch, water, NO_AIR);
        }
        /** The water flags packed (in water 1, swimming 2, swimming pose 4, eyes in water 8), -1 when not recorded. */
        public static int water(boolean inWater, boolean swimming, boolean swimPose, boolean eyes) {
            return (inWater ? 1 : 0) | (swimming ? 2 : 0) | (swimPose ? 4 : 0) | (eyes ? 8 : 0);
        }

        /** The water flags and the glide's (gliding 16, the glide pose 32), with 64 to say the glide's are recorded. */
        public static int flags(MoveState s) {
            return water(s.inWater, s.swimming, s.swimPose, s.eyesInWater) | (s.fallFlying ? 16 : 0) | (s.glidePose ? 32 : 0) | 64;
        }
    }

    /** A run: the state it started from and its ticks. */
    public record Run(String name, MoveState start, List<Tick> ticks) {}

    public record File(Body body, BlockGrid grid, List<Run> runs) {}

    /** The largest differences between the copy and the game over a run: position and velocity (each axis), and the
     *  first tick the on-ground or sprint flag differed (-1 if never). */
    public record Diff(double pos, double vel, int flagTick, int worstTick) {}

    public static Diff replay(GroundPhysics phys, Run run) {
        MoveState s = run.start().copy();
        double pos = 0, vel = 0;
        int flag = -1, worst = -1;
        for (int i = 0; i < run.ticks().size(); i++) {
            Tick t = run.ticks().get(i);
            phys.step(s, t.keys(), t.yaw(), t.pitch());
            double dp = Math.max(Math.abs(s.x - t.x()), Math.max(Math.abs(s.y - t.y()), Math.abs(s.z - t.z())));
            double dv = Math.max(Math.abs(s.vx - t.vx()), Math.max(Math.abs(s.vy - t.vy()), Math.abs(s.vz - t.vz())));
            if (dp > pos || dv > vel) worst = i;
            pos = Math.max(pos, dp);
            vel = Math.max(vel, dv);
            int mask = (t.water() & 64) != 0 ? 0x7F : 0x0F;
            boolean waterDiff = t.water() >= 0 && (Tick.flags(s) & mask) != (t.water() & mask);
            if (flag < 0 && (s.onGround != t.onGround() || s.sprinting != t.sprinting() || waterDiff)) flag = i;
        }
        return new Diff(pos, vel, flag, worst);
    }

    // ---------------------------------------------------------------- writing

    public static void writeHeader(PrintWriter w, Body b, BlockGrid g) {
        w.printf(Locale.ROOT, "body %s %s %s %s %s %s %s %s %s %s %s %s %d %s %s%n", f(b.walkSpeed), f(b.sprintSpeed), f(b.sneakSpeed),
            f(b.jumpStrength), f(b.jumpBoost), d(b.gravity), f(b.stepHeight), f(b.movementEfficiency), f(b.width), f(b.height),
            f(b.crouchHeight), b.canSprint, b.sprintWindow, b.canGlide, d(b.knockbackResistance));
        w.printf(Locale.ROOT, "body2 %s %s %s %s %s %b%n", f(b.swimHeight), f(b.eyeHeight), f(b.crouchEyeHeight), f(b.swimEyeHeight),
            f(b.waterMovementEfficiency), b.dolphinsGrace);
        w.printf(Locale.ROOT, "body3 %b %d%n", b.glide, b.glideLandTicks);
        w.printf(Locale.ROOT, "body4 %d %s %b%n", b.maxAir, d(b.oxygenBonus), b.turtleHelmet);
        w.printf(Locale.ROOT, "grid %d %d %d %d %d %d%n", g.x0, g.y0, g.z0, g.nx, g.ny, g.nz);
        List<BlockInfo> kinds = g.kinds();
        for (int i = 0; i < kinds.size(); i++) {
            BlockInfo k = kinds.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append("kind ").append(i).append(' ').append(f(k.friction)).append(' ').append(f(k.speedFactor)).append(' ')
              .append(f(k.jumpFactor)).append(' ').append(k.flags).append(' ');
            Shape s = k.shape;
            if (s.block) sb.append("block");
            else if (s.empty) sb.append("empty");
            else {
                sb.append("shape");
                String[] names = {"xs", "ys", "zs"};
                for (int a = 0; a < 3; a++) {
                    sb.append(' ').append(names[a]).append(' ');
                    for (int j = 0; j < s.coords[a].length; j++) sb.append(j > 0 ? "," : "").append(d(s.coords[a][j]));
                }
                sb.append(" full ");
                for (boolean x : s.full) sb.append(x ? '1' : '0');
            }
            w.println(sb);
            if (k.fluid != BlockInfo.NO_FLUID) w.printf(Locale.ROOT, "fluid %d %d %s%n", i, k.fluid, f(k.fluidHeight));
        }
        StringBuilder cells = new StringBuilder("cells");
        int last = -2, count = 0;
        for (int x = g.x0; x < g.x0 + g.nx; x++)
            for (int y = g.y0; y < g.y0 + g.ny; y++)
                for (int z = g.z0; z < g.z0 + g.nz; z++) {
                    int k = g.kindAt(x, y, z);
                    if (k == last) { count++; continue; }
                    if (count > 0) cells.append(' ').append(last).append('*').append(count);
                    last = k; count = 1;
                }
        if (count > 0) cells.append(' ').append(last).append('*').append(count);
        w.println(cells);
    }

    public static void writeRunStart(PrintWriter w, String name, MoveState s) {
        w.println("run " + name);
        w.printf(Locale.ROOT, "start %s %s %s %s %s %s %b %b %b %b %b %b %d %d %s %b %b %d %d %d %d %s%n",
            d(s.x), d(s.y), d(s.z), d(s.vx), d(s.vy), d(s.vz), s.onGround, s.horizontalCollision, s.minorHorizontalCollision,
            s.sprinting, s.crouching, s.crouchPose, s.sprintTriggerTime, s.noJumpDelay, d(s.fallDistance), s.hasSupport,
            s.onGroundNoBlocks, s.supX, s.supY, s.supZ, s.lastKeys, f(s.lastMoveForward));
        w.printf(Locale.ROOT, "start2 %b %b %b %b %s%n", s.swimPose, s.swimming, s.inWater, s.eyesInWater, d(s.waterHeight));
        w.printf(Locale.ROOT, "start3 %b %b %d%n", s.fallFlying, s.glidePose, s.glideGroundTicks);
        w.printf(Locale.ROOT, "start4 %s %d%n", d(s.air), s.waterBreathingTicks);
    }

    public static void writeTick(PrintWriter w, Tick t) {
        if (t.air() != Tick.NO_AIR) {
            w.printf(Locale.ROOT, "tick %d %s %s %s %s %s %s %s %b %b %s %d %d%n", t.keys(), f(t.yaw()), d(t.x()), d(t.y()), d(t.z()),
                d(t.vx()), d(t.vy()), d(t.vz()), t.onGround(), t.sprinting(), f(t.pitch()), t.water(), t.air());
        } else if (t.water() < 0) {
            w.printf(Locale.ROOT, "tick %d %s %s %s %s %s %s %s %b %b%n", t.keys(), f(t.yaw()), d(t.x()), d(t.y()), d(t.z()),
                d(t.vx()), d(t.vy()), d(t.vz()), t.onGround(), t.sprinting());
        } else {
            w.printf(Locale.ROOT, "tick %d %s %s %s %s %s %s %s %b %b %s %d%n", t.keys(), f(t.yaw()), d(t.x()), d(t.y()), d(t.z()),
                d(t.vx()), d(t.vy()), d(t.vz()), t.onGround(), t.sprinting(), f(t.pitch()), t.water());
        }
    }

    // exact text: the bits of the double or float in hex, so nothing is lost
    private static String d(double v) { return Double.toHexString(v); }
    private static String f(float v) { return Float.toHexString(v); }

    // ---------------------------------------------------------------- reading

    public static File read(Reader in) throws IOException {
        BufferedReader r = new BufferedReader(in);
        Body body = new Body();
        BlockGrid grid = null;
        List<BlockInfo> kinds = new ArrayList<>();
        List<Run> runs = new ArrayList<>();
        Run run = null;
        String line;
        while ((line = r.readLine()) != null) {
            String[] p = line.trim().split(" ");
            switch (p[0]) {
                case "body" -> {
                    body.walkSpeed = pf(p[1]); body.sprintSpeed = pf(p[2]); body.sneakSpeed = pf(p[3]);
                    body.jumpStrength = pf(p[4]); body.jumpBoost = pf(p[5]); body.gravity = pd(p[6]);
                    body.stepHeight = pf(p[7]); body.movementEfficiency = pf(p[8]); body.width = pf(p[9]);
                    body.height = pf(p[10]); body.crouchHeight = pf(p[11]); body.canSprint = Boolean.parseBoolean(p[12]);
                    body.sprintWindow = Integer.parseInt(p[13]); body.canGlide = Boolean.parseBoolean(p[14]);
                    body.knockbackResistance = pd(p[15]);
                }
                case "grid" -> grid = new BlockGrid(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
                case "kind" -> {
                    Shape s;
                    if (p[6].equals("block")) s = Shape.BLOCK;
                    else if (p[6].equals("empty")) s = Shape.EMPTY;
                    else {
                        double[][] c = new double[3][];
                        for (int a = 0; a < 3; a++) {
                            String[] v = p[8 + 2 * a].split(",");
                            c[a] = new double[v.length];
                            for (int j = 0; j < v.length; j++) c[a][j] = pd(v[j]);
                        }
                        String bits = p[14];
                        boolean[] full = new boolean[bits.length()];
                        for (int j = 0; j < full.length; j++) full[j] = bits.charAt(j) == '1';
                        s = new Shape(c, full, false);
                    }
                    BlockInfo b = Integer.parseInt(p[1]) == 0 && s.empty && Integer.parseInt(p[5]) == 0
                        ? BlockInfo.AIR : new BlockInfo(s, pf(p[2]), pf(p[3]), pf(p[4]), Integer.parseInt(p[5]));
                    kinds.add(b);
                }
                case "fluid" -> {
                    int i = Integer.parseInt(p[1]);
                    BlockInfo k = kinds.get(i);
                    kinds.set(i, new BlockInfo(k.shape, k.friction, k.speedFactor, k.jumpFactor, k.flags,
                        Integer.parseInt(p[2]), pf(p[3])));
                }
                case "body2" -> {
                    body.swimHeight = pf(p[1]); body.eyeHeight = pf(p[2]); body.crouchEyeHeight = pf(p[3]);
                    body.swimEyeHeight = pf(p[4]); body.waterMovementEfficiency = pf(p[5]);
                    body.dolphinsGrace = Boolean.parseBoolean(p[6]);
                }
                case "start4" -> {
                    MoveState s = run.start();
                    s.air = pd(p[1]); s.waterBreathingTicks = Integer.parseInt(p[2]);
                }
                case "body4" -> {
                    body.maxAir = Integer.parseInt(p[1]); body.oxygenBonus = pd(p[2]); body.turtleHelmet = Boolean.parseBoolean(p[3]);
                }
                case "body3" -> {
                    body.glide = Boolean.parseBoolean(p[1]); body.glideLandTicks = Integer.parseInt(p[2]);
                }
                case "start3" -> {
                    MoveState s = run.start();
                    s.fallFlying = Boolean.parseBoolean(p[1]); s.glidePose = Boolean.parseBoolean(p[2]);
                    s.glideGroundTicks = Integer.parseInt(p[3]);
                }
                case "start2" -> {
                    MoveState s = run.start();
                    s.swimPose = Boolean.parseBoolean(p[1]); s.swimming = Boolean.parseBoolean(p[2]);
                    s.inWater = Boolean.parseBoolean(p[3]); s.eyesInWater = Boolean.parseBoolean(p[4]);
                    s.waterHeight = pd(p[5]);
                }
                case "cells" -> {
                    int idx = 0;
                    for (int j = 1; j < p.length; j++) {
                        String[] kc = p[j].split("\\*");
                        int k = Integer.parseInt(kc[0]), n = Integer.parseInt(kc[1]);
                        for (int m = 0; m < n; m++, idx++) {
                            int x = grid.x0 + idx / (grid.ny * grid.nz), y = grid.y0 + idx / grid.nz % grid.ny, z = grid.z0 + idx % grid.nz;
                            grid.set(x, y, z, k < 0 ? BlockInfo.UNKNOWN : kinds.get(k));
                        }
                    }
                }
                case "run" -> { run = new Run(line.substring(4), new MoveState(), new ArrayList<>()); runs.add(run); }
                case "start" -> {
                    MoveState s = run.start();
                    s.x = pd(p[1]); s.y = pd(p[2]); s.z = pd(p[3]); s.vx = pd(p[4]); s.vy = pd(p[5]); s.vz = pd(p[6]);
                    s.onGround = Boolean.parseBoolean(p[7]); s.horizontalCollision = Boolean.parseBoolean(p[8]);
                    s.minorHorizontalCollision = Boolean.parseBoolean(p[9]); s.sprinting = Boolean.parseBoolean(p[10]);
                    s.crouching = Boolean.parseBoolean(p[11]); s.crouchPose = Boolean.parseBoolean(p[12]);
                    s.sprintTriggerTime = Integer.parseInt(p[13]); s.noJumpDelay = Integer.parseInt(p[14]);
                    s.fallDistance = pd(p[15]); s.hasSupport = Boolean.parseBoolean(p[16]); s.onGroundNoBlocks = Boolean.parseBoolean(p[17]);
                    s.supX = Integer.parseInt(p[18]); s.supY = Integer.parseInt(p[19]); s.supZ = Integer.parseInt(p[20]);
                    s.lastKeys = Integer.parseInt(p[21]); s.lastMoveForward = pf(p[22]);
                }
                case "tick" -> run.ticks().add(new Tick(Integer.parseInt(p[1]), pf(p[2]), pd(p[3]), pd(p[4]), pd(p[5]), pd(p[6]),
                    pd(p[7]), pd(p[8]), Boolean.parseBoolean(p[9]), Boolean.parseBoolean(p[10]),
                    p.length > 11 ? pf(p[11]) : 0.0F, p.length > 12 ? Integer.parseInt(p[12]) : -1,
                    p.length > 13 ? Integer.parseInt(p[13]) : Tick.NO_AIR));
                default -> {}
            }
        }
        return new File(body, grid, runs);
    }

    private static double pd(String s) { return Double.parseDouble(s); }
    private static float pf(String s) { return Float.parseFloat(s); }
}
