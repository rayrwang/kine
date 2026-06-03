package ai.rrw.kine.autoflight;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

public class FlightDirector {

  public static boolean isActive()       { return active; }
  public static float   commandedPitch() { return commandedPitch; }
  public static boolean isLevelMode()    { return levelMode; }
  /** Model-derived cruise ground speed (m/s) for the active mode; seeds the range/endurance estimate. */
  public static float   expectedGroundSpeed() { return levelMode ? L_SPEED : C_SPEED; }

  // --- TWO PROFILES (lag-aware optimization; both confirmed in-flight) ---
  // CLIMB: gain altitude. ~+0.9 m/s climb at ~22 m/s ground, ~68-block dive per cycle.
  private static final float  C_DIVE  = 40f;
  private static final float  C_UP    = -62f;        // over-commanded: the pitch lag means ~-52 actual
  private static final double C_TRIG  = 44.0;
  private static final int    C_HOLD  = 11;
  private static final float  C_SWEEP = 18f / 20f;   // 0.9 deg/tick
  private static final float  C_SPEED = 21.9f;       // model ground speed (m/s)
  // LEVEL: hold altitude at max speed. ~30.2 m/s ground, ~flat (-1.8 m/min), ~126-block dive per cycle.
  private static final float  L_DIVE  = 38f;
  private static final float  L_UP    = -73f;        // over-commanded: ~-59 actual
  private static final double L_TRIG  = 52.0;
  private static final int    L_HOLD  = 12;
  private static final float  L_SWEEP = 28f / 20f;   // 1.4 deg/tick
  private static final float  L_SPEED = 30.2f;

  // --- altitude-based mode switch (absolute Y, sampled once per cycle at the dive trough) ---
  // Highest terrain is 384, so keeping the porpoise bottom above ~400 clears all terrain everywhere:
  // cruise level for speed. Below that, climb. Sampling the trough once per cycle (not continuously)
  // plus hysteresis stops the mode flipping as Y swings ~100 blocks within each cycle.
  private static final double ENTER_LEVEL = 415.0;   // trough rises to here -> switch to level
  private static final double EXIT_LEVEL  = 398.0;   // trough sinks to here -> back to climb
  private static final double ABS_FLOOR   = 390.0;   // trough above this is terrain-cleared: skip the AGL floor

  // active profile values (selected by the mode); the state machine reads these
  private static float  aDive = C_DIVE, aUp = C_UP, aSweep = C_SWEEP;
  private static double aTrig = C_TRIG;
  private static int    aHold = C_HOLD;
  private static boolean levelMode = false;
  private static double cycMinY = Double.MAX_VALUE;  // running trough of the current cycle
  private static double lastTroughY = -1.0e9;        // last sampled trough (mode decision + terrain bypass)

  // AGL gating for the CLIMB regime (low-altitude terrain safety). The level regime is kept safe by
  // ABS_FLOOR instead, because its deep dive would otherwise trip the AGL floor near tall terrain.
  // Need ENGAGE_AGL clear blocks below to ARM; once armed, stay armed until below FLOOR_AGL.
  private static final int ENGAGE_AGL = 140;
  private static final int FLOOR_AGL  = 48;

  private static final int MAGENTA = 0xFFFF00FF;
  private static final int RED     = 0xFFFF3030;

  // --- state machine ---
  private static final int HOLD = 0, TOP = 1, SWEEP = 2;
  private static int phase = HOLD;
  private static float commandedPitch = C_DIVE;
  private static float commandedPitchOld = C_DIVE;   // value at the previous tick, for per-frame interpolation
  private static int topTicks = 0;
  // The heading director compares desiredYaw against the player heading on the same per-tick time base.
  // (Mixing it with the per-frame-interpolated view yaw used to sawtooth at tick frequency, worst during a
  // fast turn — the source of the bar's shimmer.) A frame-rate-independent low-pass then settles the
  // per-tick steps into fluid motion, the same regardless of FPS.
  private static final float YAW_BAR_TC = 0.08f;   // heading-bar low-pass time constant (s); ~matches the old 0.18/frame at 60fps
  private static Float smoothYawOff = null;         // null = not currently steering
  private static long  yawNanos = 0L;               // wall-clock of the last bar update, for the dt-based filter
  private static boolean active = false;
  private static boolean tooLow = false;   // gliding, but below the altitude to arm

  public static void register() {
    HudElementRegistry.attachElementAfter(
        VanillaHudElements.MISC_OVERLAYS,
        Identifier.fromNamespaceAndPath(Kine.MOD_ID, "flightdir"),
        FlightDirector::render
    );
    ClientTickEvents.END_CLIENT_TICK.register(FlightDirector::tick);
  }

  private static void tick(Minecraft mc) {
    commandedPitchOld = commandedPitch;   // remember last tick's value so render can interpolate to it
    LocalPlayer p = mc.player;
    if (p == null || mc.level == null || !p.isFallFlying()) {
      reset();
      return;
    }

    int clear = clearBelow(mc, p);
    boolean absSafe = lastTroughY >= ABS_FLOOR;
    // arm needs ENGAGE_AGL clear below; once armed, stay armed while either the AGL floor holds OR the
    // trough is high enough that terrain is moot. (absSafe lets the deep level dive keep flying over tall
    // terrain, where AGL at the bottom is small even though absolute altitude is safe.)
    boolean room = active ? (absSafe || clear >= FLOOR_AGL) : (clear >= ENGAGE_AGL);
    if (!room) {
      active = false;
      tooLow = true;
      phase = HOLD; commandedPitch = aDive; topTicks = 0; cycMinY = Double.MAX_VALUE;
      return;
    }
    active = true;
    tooLow = false;

    double dx = p.getX() - p.xOld, dz = p.getZ() - p.zOld;
    double hSpeed = Math.sqrt(dx * dx + dz * dz) * 20.0;   // m/s, matches the velocity HUD

    double y = p.getY();
    if (y < cycMinY) cycMinY = y;                          // track the bottom of this cycle

    switch (phase) {
      case HOLD -> {
        commandedPitch = aDive;
        if (hSpeed >= aTrig) {
          // trigger == bottom of the cycle: sample the trough, choose the mode for the next cycle
          lastTroughY = cycMinY;
          updateMode(lastTroughY);
          cycMinY = Double.MAX_VALUE;
          phase = TOP; topTicks = 0; commandedPitch = aUp;
        }
      }
      case TOP -> {
        commandedPitch = aUp;
        if (++topTicks >= aHold) phase = SWEEP;
      }
      case SWEEP -> {
        commandedPitch += aSweep;
        if (commandedPitch >= aDive) { commandedPitch = aDive; phase = HOLD; }
      }
    }
  }

  /** Pick climb/level from the sampled trough (hysteresis), then load that profile's constants. */
  private static void updateMode(double troughY) {
    if (!levelMode && troughY >= ENTER_LEVEL)      levelMode = true;
    else if (levelMode && troughY <= EXIT_LEVEL)   levelMode = false;
    if (levelMode) { aDive = L_DIVE; aUp = L_UP; aTrig = L_TRIG; aHold = L_HOLD; aSweep = L_SWEEP; }
    else           { aDive = C_DIVE; aUp = C_UP; aTrig = C_TRIG; aHold = C_HOLD; aSweep = C_SWEEP; }
  }

  private static void reset() {
    active = false; tooLow = false;
    levelMode = false; lastTroughY = -1.0e9; cycMinY = Double.MAX_VALUE;
    aDive = C_DIVE; aUp = C_UP; aTrig = C_TRIG; aHold = C_HOLD; aSweep = C_SWEEP;
    phase = HOLD; commandedPitch = C_DIVE; topTicks = 0;
  }

  public static boolean isTooLow() { return tooLow; }

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

  private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
    if (!Settings.displayFlightDirectors) return;
    Minecraft mc = Minecraft.getInstance();
    LocalPlayer p = mc.player;
    if (p == null) return;

    int W = mc.getWindow().getGuiScaledWidth();
    int H = mc.getWindow().getGuiScaledHeight();
    int cx = W / 2, cy = H / 2;
    int maxOff = (int) (H * 0.25f);

    boolean landing = Nav.landing();
    if (tooLow && !landing) {
      // too-low advisory and the nav readout are mutually exclusive — too-low always wins here
      String s = "TOO LOW TO ACTIVATE AUTOPILOT";
      int y = cy - maxOff - mc.font.lineHeight - 6;
      g.text(mc.font, s, cx - mc.font.width(s) / 2, y, RED, true);
      return;
    }
    if (!active && !landing) return;

    // Interpolate both inputs to the bar between ticks so it tracks at the framerate, not the 20 Hz
    // tick rate. getViewXRot is the same smoothed pitch the camera uses; the commanded pitch is the
    // landing descent while landing, otherwise the porpoise lerped from its previous-tick value.
    float partial = delta.getGameTimeDeltaPartialTick(false);
    float cmdNow  = landing ? Nav.landingPitch() : Mth.lerp(partial, commandedPitchOld, commandedPitch);
    float pitchNow = p.getViewXRot(partial);
    float k = H * 0.35f / 30f;                     // ~35% of screen per 30°
    int off = (int) Math.max(-maxOff, Math.min(maxOff, (cmdNow - pitchNow) * k));
    int barY = cy + off;

    // vertical bar is the heading/lateral director: deflects when a nav mode commands a turn
    int barX = cx;
    if (Nav.steering()) {
      // compare against the per-tick heading, not the interpolated view yaw, so the offset doesn't sawtooth
      float yawErr = Mth.wrapDegrees(Nav.desiredYaw(p) - p.getYRot());
      float rawOff = Math.max(-maxOff, Math.min(maxOff, yawErr * k));
      long now = System.nanoTime();
      double dt = (yawNanos == 0L) ? 0.0 : (now - yawNanos) / 1.0e9;
      yawNanos = now;
      if (smoothYawOff == null || dt <= 0 || dt > 0.5) {
        smoothYawOff = rawOff;   // seed on the first steering frame (or after a hitch) so it doesn't sweep in from center
      } else {
        smoothYawOff = (float) (smoothYawOff + (rawOff - smoothYawOff) * (1.0 - Math.exp(-dt / YAW_BAR_TC)));
      }
      barX = cx + (int) (float) smoothYawOff;
    } else {
      smoothYawOff = null;
      yawNanos = 0L;
    }

    int thick = 1, half = 60, gap = 0;
    // horizontal (pitch) director bar — moves vertically
    g.fill(cx - half, barY - thick, cx - gap,  barY + thick, MAGENTA);
    g.fill(cx + gap,  barY - thick, cx + half, barY + thick, MAGENTA);
    // vertical (heading) director bar — moves horizontally with the nav turn command
    g.fill(barX - thick, cy - half, barX + thick, cy + half, MAGENTA);
  }
}
