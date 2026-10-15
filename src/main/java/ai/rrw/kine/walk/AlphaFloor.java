package ai.rrw.kine.walk;

import ai.rrw.kine.Settings;
import ai.rrw.kine.combat.KillAura;
import ai.rrw.kine.combat.ProjectileDodge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The walk's alpha floor (RW 2026-10-13 19:09, "oh eyah tahts cool": as an Airbus's alpha floor commands full thrust
 * past the thrust lever near a stall, the walk goes faster than its gait setting when something is chasing you or you
 * are about to take damage). It lifts the gait to Sprint-jump (the fastest on foot; never to elytra hops) on a trigger
 * kine can see: a hostile mob within CHASE (and CHASE_DY up or down) that the player can see and that has come a block
 * nearer over the last second; a projectile on course to hit (the projectile dodge's own test); a lit creeper within
 * CREEPER; burning, or in lava; hurt in the last HURT ticks; under water with the air below AIR_LOW. On at the first
 * trigger; off HOLD ticks after the last; on for MAX ticks at the most, then not again until the triggers have been
 * clear for HOLD (a trigger that never clears, a zombie behind glass, doesn't keep it on). Still under the game's own
 * limits: no sprint at food 6 or less (Walk.canSprint).
 */
public final class AlphaFloor {
    private AlphaFloor() {}

    static final double CHASE = 16.0, CHASE_DY = 6.0, CLOSING = 1.0, CREEPER = 7.0;
    static final int HOLD = 60, MAX = 600, LOOKBACK = 20, AIR_LOW = 100;

    private static boolean on = false, lockedOut = false;
    private static int onSince = 0, lastTrigger = -100000;
    private static String why = "";
    /** Each hostile's distance over the last LOOKBACK ticks: {tick, distance} pairs, oldest first. */
    private static final Map<Integer, ArrayDeque<double[]>> seen = new HashMap<>();
    /** Engagements this walk (for tests). */
    public static int engaged = 0;

    /** Whether it is on now. */
    public static boolean on() {
        return on;
    }

    /** What set it off last ("" when it hasn't). */
    public static String why() {
        return why;
    }

    /** The gait the walk may use: the setting, or Sprint-jump while the floor is on (never lower than the setting). */
    static int raise(int gait) {
        return on && gait < Settings.GAIT_SPRINT_JUMP ? Settings.GAIT_SPRINT_JUMP : gait;
    }

    /** A new walk: off, nothing seen. */
    static void reset() {
        on = false;
        lockedOut = false;
        lastTrigger = -100000;
        why = "";
        seen.clear();
        engaged = 0;
    }

    /** Once a tick while the walk runs. */
    static void update(Minecraft mc, LocalPlayer p, int tick) {
        step(trigger(mc, p, tick), tick);
    }

    /** The state machine: this tick's trigger (null: none) at this tick. */
    static void step(String t, int tick) {
        if (t != null) { lastTrigger = tick; why = t; }
        boolean clear = tick - lastTrigger > HOLD;
        if (lockedOut && clear) lockedOut = false;
        if (on && (clear || tick - onSince > MAX)) {
            on = false;
            if (!clear) lockedOut = true;
        } else if (!on && !lockedOut && t != null) {
            on = true;
            onSince = tick;
            engaged++;
        }
    }

    /** What sets it off now, or null. */
    static String trigger(Minecraft mc, LocalPlayer p, int tick) {
        String chase = chaser(mc, p, tick);
        if (chase != null) return chase;
        if (p.isOnFire()) return "burning";
        if (p.isInLava()) return "in lava";
        if (p.hurtTime > 0) return "hurt";   // counts down from 10 after a hit
        if (p.isUnderWater() && p.getAirSupply() < AIR_LOW) return "air low";
        if (mc.level != null && ProjectileDodge.onCourse(p, mc.level)) return "a projectile on course";
        return null;
    }

    /** A hostile closing on the player with a line to it, or a lit creeper near; records distances as it goes. */
    private static String chaser(Minecraft mc, LocalPlayer p, int tick) {
        if (mc.level == null) return null;
        String found = null;
        java.util.Set<Integer> here = new java.util.HashSet<>();
        for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(CHASE, CHASE_DY, CHASE))) {
            if (!(e instanceof Mob mob) || !mob.isAlive() || !KillAura.targets(e, p)) continue;
            double d = e.distanceTo(p);
            if (e instanceof Creeper c && (c.getSwellDir() > 0 || c.isIgnited()) && d <= CREEPER) found = "a creeper lit";
            ArrayDeque<double[]> h = seen.computeIfAbsent(e.getId(), k -> new ArrayDeque<>());
            h.addLast(new double[]{tick, d});
            while (!h.isEmpty() && h.peekFirst()[0] < tick - LOOKBACK) h.removeFirst();
            here.add(e.getId());
            if (found == null && h.peekFirst()[0] <= tick - LOOKBACK + 1 && h.peekFirst()[1] - d >= CLOSING
                && p.hasLineOfSight(e))
                found = "a " + e.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT) + " closing";
        }
        for (Iterator<Integer> it = seen.keySet().iterator(); it.hasNext(); ) if (!here.contains(it.next())) it.remove();
        return found;
    }
}
