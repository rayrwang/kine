package ai.rrw.kine.autoflight;

import ai.rrw.kine.Settings;
import ai.rrw.kine.util.Compass;
import ai.rrw.kine.util.Motion;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

public class FlightDirector {

    static final AltitudeLaw LAW = new AltitudeLaw();

    public static boolean isActive()       { return LAW.active; }
    public static float   commandedPitch() { return (float) LAW.law.cmd; }
    /** Last tick's commanded pitch, so the display can interpolate between ticks. */
    public static float   commandedPitchOld() { return (float) LAW.commandedPitchOld; }
    public static int     targetAltitude() { return LAW.targetAlt; }
    public static int     floorAltitude()  { return LAW.floorAlt; }   // the floor the law is flying to now
    public static void    setTargetAltitude(int y) { LAW.setTargetAltitude(y); }
    /** Model ground speed (m/s) of the active profile; seeds the range and ETA estimate. */
    public static float   expectedGroundSpeed() { return LAW.expectedGroundSpeed(); }
    public static int     periodTicks()    { return LAW.measuredPeriod; }   // live porpoise period (ticks), for the speed mean
    /** Vertical mode for the annunciator (AltitudeLaw.VM_*). */
    public static int     verticalMode() { return LAW.vmode; }
    public static final int VM_CLB = AltitudeLaw.VM_CLB, VM_ALT_STAR = AltitudeLaw.VM_ALT_STAR,
                            VM_ALT = AltitudeLaw.VM_ALT, VM_DESC = AltitudeLaw.VM_DESC;
    /** The live porpoise law, so the planner's and the rails' rollouts continue the current cycle. */
    public static PorpoiseLaw law()       { return LAW.law; }
    public static boolean isTooLow()      { return LAW.tooLow; }

    private static boolean      cruiseInit   = false;    // one-shot: pick the start cruise from initial facing
    private static final double ABS_FLOOR    = 390.0;   // trough above this is terrain-cleared: skip the AGL floor

    // Arming gate without terrain avoidance: arm with ENGAGE_AGL clear blocks below; once armed, stay armed
    // while FLOOR_AGL blocks stay clear or the last trough is above ABS_FLOOR (the hold profile's deep dive
    // would otherwise trip the floor over tall terrain).
    private static final int ENGAGE_AGL = 140;
    private static final int FLOOR_AGL  = 48;



    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FlightDirector::tick);
    }

    private static void tick(Minecraft mc) {
        LAW.startTick();
        LocalPlayer p = mc.player;
        // Once, when there first is a player: set the starting cruise from the initial facing.
        if (p != null && !cruiseInit) {
            LAW.initCruise(Compass.heading(p.getYRot()));
            cruiseInit = true;
        }
        if (p == null || mc.level == null || !p.isFallFlying()) {
            LAW.reset();
            TurnGuard.reset();
            return;
        }

        // Is the way ahead flyable? With terrain avoidance on, flyable means the planner finds a path from
        // here. Because the climb law dives, that refuses when too low to clear the dive, when low and slow
        // with no energy to climb, and when boxed in. Without avoidance, an AGL window arms the autopilot.
        // The law's floor keeps last tick's planner floor (raised to climb over terrain), so the law runs one
        // tick behind the planner. It falls back to the target when avoidance is off, the autopilot is
        // disengaged, or no path exists.
        boolean room;
        if (Settings.terrainAvoidance) {
            // Checked every tick, not latched, so sinking into a low and slow state with no way out drops the
            // bars. Exception: a committed turn or emergency bank, whose low speed would otherwise read as
            // infeasible mid-turn. A stuck manoeuvre ends through the controller's own hand-off.
            boolean maneuvering = Autopilot.isEngaged()
                    && (TurnGuard.action() == TurnPlanner.TURN || TurnGuard.action() == TurnPlanner.EMERGENCY);
            room = TurnGuard.feasibleToArm(mc, p, LAW.targetAlt) || maneuvering;
        } else {
            int clear = clearBelow(mc, p);
            boolean absSafe = LAW.lastTroughY >= ABS_FLOOR;
            // arm with ENGAGE_AGL clear below; once armed, stay armed while the AGL floor holds or the last
            // trough is high enough that terrain does not matter
            room = LAW.active ? (absSafe || clear >= FLOOR_AGL) : (clear >= ENGAGE_AGL);
        }
        if (!LAW.tick(room, Motion.groundSpeed(p), p.getY())) {
            TurnGuard.reset();
            return;
        }
        // Lateral terrain avoidance: this tick's steering heading. An imminent wall becomes an emergency bank;
        // if banking can't escape, the controller hands control back rather than fly into the ground.
        if (Settings.terrainAvoidance && Autopilot.isEngaged()) {
            TurnGuard.evaluate(mc, p, LAW.targetAlt);
            if (TurnGuard.handOff()) Autopilot.disengage();
            // Adopt the planner's trough floor for next tick's law: the cruise floor, or higher to climb over
            // rising terrain. The planner only raises it, so clamp into [targetAlt, ALT_MAX].
            LAW.floorAlt = Math.max(LAW.targetAlt, Math.min(AltitudeLaw.ALT_MAX, (int) Math.round(TurnGuard.desiredFloor())));
        } else { TurnGuard.reset(); LAW.floorAlt = LAW.targetAlt; }
    }

    /** Clear blocks straight below the player, scanning down to ENGAGE_AGL (returns ENGAGE_AGL if more). */
    private static int clearBelow(Minecraft mc, LocalPlayer p) {
        int x = p.getBlockX(), z = p.getBlockZ();
        int top = (int) Math.floor(p.getY()) - 1;
        int limit = Math.max(mc.level.getMinY(), top - ENGAGE_AGL);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = top; y > limit; y--) {
            pos.set(x, y, z);
            if (!mc.level.getBlockState(pos).isAir()) return top - y;
        }
        return ENGAGE_AGL;
    }
}
