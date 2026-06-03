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
  /** True while climbing toward the target (below it); false while holding altitude (at/above it). */
  public static boolean isClimbing()     { return climbing; }
  public static int     targetAltitude() { return targetAlt; }
  public static void    setTargetAltitude(int y) { targetAlt = Math.max(ALT_MIN, Math.min(ALT_MAX, y)); }
  /** Model-derived cruise ground speed (m/s) for the active mode; seeds the range/endurance estimate. */
  public static float   expectedGroundSpeed() { return climbing ? C_SPEED : L_SPEED; }
  public static int     periodTicks()    { return measuredPeriod; }   // live porpoise period (ticks), for the speed mean
  /** Vertical FMA mode for the annunciator. CLB/DESC = orange, ALT* (capture) = yellow, ALT (hold) = green. */
  public static int     verticalMode() { return vmode; }
  public static final int VM_CLB = 0, VM_ALT_STAR = 1, VM_ALT = 2, VM_DESC = 3;

  // --- TWO PROFILES (lag-aware optimization; both confirmed in-flight) ---
  // CLIMB: gain altitude. ~+0.9 m/s climb at ~22 m/s ground, ~68-block dive per cycle.
  private static final float  C_DIVE  = 40f;
  private static final float  C_UP    = -62f;        // over-commanded: the pitch lag means ~-52 actual
  private static final double C_TRIG  = 44.0;
  private static final int    C_HOLD  = 11;
  private static final float  C_SWEEP = 18f / 20f;   // 0.9 deg/tick
  private static final float  C_SPEED = 21.9f;       // model ground speed (m/s)
  // HOLD: hold altitude at max speed. ~30.0 m/s raw, drifts UP slightly (+1.8 m/min) so the correction is
  // done by the dive-extension (fast) instead of climb-catches (slow) -> ~30.1 m/s closed-loop AVERAGE,
  // higher than a faster-on-paper descending profile that has to buy height back at climb speed (~10% of
  // the time). Keeps the porpoise bottom at/above target. ~126-block dive per cycle.
  private static final float  L_DIVE  = 38f;
  private static final float  L_UP    = -66f;        // over-commanded: ~-56 actual
  private static final double L_TRIG  = 52.0;
  private static final int    L_HOLD  = 13;
  private static final float  L_SWEEP = 24f / 20f;   // 1.2 deg/tick
  private static final float  L_SPEED = 30.1f;       // closed-loop hold average (held by dive-extension, ~0 climb-catches)

  // --- altitude hold (closed-loop on the dive trough = "bottom of the porpoise") ---
  // The trough is sampled once per cycle at the pull-up. Below the target we climb (climb profile); at or
  // above it we hold/descend by simply DIVING UNTIL WE REACH THE TARGET ALTITUDE and only then pulling up,
  // so the bottom of every porpoise lands on the target — a descent from any height bottoms out at the
  // target in a single dive. DESC_MARGIN is how far above target the pull-up begins (the nose-down coast
  // through the pull-up carries the true trough ~3 blocks lower, parking it just above target).
  private static int          targetAlt    = 400;     // selectable in the nav menu; the bottom we hold
  private static final int    ALT_MIN      = -60;
  private static final int    ALT_MAX      = 2000;
  private static final int    DESC_MARGIN  = 6;       // hold/descend: begin the pull-up this many blocks above target
  private static final int    DESC_LIVE    = 150;     // while diving above target+this, annunciate DESC (clears the ~109 steady ceiling)
  private static final double ABS_FLOOR    = 390.0;   // trough above this is terrain-cleared: skip the AGL floor

  // active profile values (selected by the mode); the state machine reads these
  private static float  aDive = C_DIVE, aUp = C_UP, aSweep = C_SWEEP;
  private static double aTrig = C_TRIG;
  private static int    aHold = C_HOLD;
  private static boolean climbing = true;            // true = climbing toward target, false = holding
  private static int    vmode = VM_CLB;              // vertical FMA mode (annunciator only; control uses climbing)
  private static final double ALT_HOLD_BAND = 10.0;  // |trough - target| within this -> ALT (settled hold)
  private static final double ALT_CAP_BAND  = 40.0;  // ...within this -> ALT* (capturing); beyond -> CLB / DESC
  private static double cycMinY = Double.MAX_VALUE;  // running trough of the current cycle
  private static double lastTroughY = -1.0e9;        // last sampled trough (for the terrain bypass)

  // Porpoise period measured live (ticks between pull-ups), exposed to the range/endurance speed mean so
  // its phase-balanced average truncates to whole *current* cycles (≈245 climbing, ≈325 holding) instead of
  // a fixed guess. Only normal cycles update it; a big descent's single long dive-to-target falls outside
  // the band and is ignored, leaving the last cruise/climb period in place.
  private static int    cycleTicks     = 0;          // ticks since the last pull-up
  private static int    measuredPeriod = 320;        // seeded near the hold cruise period until measured
  private static final int PERIOD_MIN = 160, PERIOD_MAX = 420;

  // AGL gating for the CLIMB regime (low-altitude terrain safety). When holding altitude the trough is
  // kept safe by ABS_FLOOR instead, because the deep hold dive would otherwise trip the AGL floor near
  // tall terrain. Need ENGAGE_AGL clear blocks below to ARM; once armed, stay armed until below FLOOR_AGL.
  private static final int ENGAGE_AGL = 140;
  private static final int FLOOR_AGL  = 48;

  private static final int MAGENTA = 0xFFFF00FF;
  private static final int RED     = 0xFFFF3030;
  private static final int GREEN   = 0xFF44FF44;   // current-altitude readout (matches the nav HUD green)

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
    // trough is high enough that terrain is moot. (absSafe lets the deep hold dive keep flying over tall
    // terrain, where AGL at the bottom is small even though absolute altitude is safe.)
    boolean room = active ? (absSafe || clear >= FLOOR_AGL) : (clear >= ENGAGE_AGL);
    if (!room) {
      active = false;
      tooLow = true;
      // below arming altitude: revert to the climb profile so re-arming starts climbing back up
      climbing = true; vmode = VM_CLB; loadProfile();
      phase = HOLD; commandedPitch = aDive; topTicks = 0; cycMinY = Double.MAX_VALUE; cycleTicks = 0;
      return;
    }
    active = true;
    tooLow = false;
    cycleTicks++;                                          // measuring the current porpoise period

    double dx = p.getX() - p.xOld, dz = p.getZ() - p.zOld;
    double hSpeed = Math.sqrt(dx * dx + dz * dz) * 20.0;   // m/s, matches the velocity HUD

    double y = p.getY();
    if (y < cycMinY) cycMinY = y;                          // track the bottom of this cycle

    switch (phase) {
      case HOLD -> {
        commandedPitch = aDive;
        // Live DESC: while diving down from well above the steady porpoise ceiling, show the descent the
        // whole way down (the per-cycle trough samples at the bottom, so it would otherwise read ALT the
        // instant we arrive). Latches until the pull-up, where decideMode reclassifies from the trough.
        if (!climbing && y > targetAlt + DESC_LIVE) vmode = VM_DESC;
        // Pull-up trigger. Climbing toward the target: pull up at the climb profile's speed (max climb rate).
        // Holding or descending to it: keep diving until we reach the target altitude, then pull up — so a
        // descent from any height bottoms out right at the target in a single dive, no nibbling down.
        boolean pull = climbing ? (hSpeed >= aTrig) : (y <= targetAlt + DESC_MARGIN);
        if (pull) {
          if (cycleTicks >= PERIOD_MIN && cycleTicks <= PERIOD_MAX) measuredPeriod = cycleTicks;
          cycleTicks = 0;                      // start timing the next cycle
          lastTroughY = cycMinY;               // bottom of the cycle: sample trough, choose climb vs hold
          decideMode(lastTroughY);
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

  /** From the sampled trough: climb if below target (climb profile), otherwise hold/descend (the dive runs
   *  down to the target before pulling up). Also sets the vertical FMA mode for the annunciator. */
  private static void decideMode(double troughY) {
    double err = troughY - targetAlt;          // >0 means above target
    climbing = err < 0;
    loadProfile();
    double ae = Math.abs(err);                 // vertical FMA mode for the annunciator
    if      (ae <= ALT_HOLD_BAND) vmode = VM_ALT;        // settled at target
    else if (ae <= ALT_CAP_BAND)  vmode = VM_ALT_STAR;   // nearing -> capture
    else                          vmode = (err < 0) ? VM_CLB : VM_DESC;
  }

  private static void loadProfile() {
    if (climbing) { aDive = C_DIVE; aUp = C_UP; aTrig = C_TRIG; aHold = C_HOLD; aSweep = C_SWEEP; }
    else          { aDive = L_DIVE; aUp = L_UP; aTrig = L_TRIG; aHold = L_HOLD; aSweep = L_SWEEP; }
  }

  private static void reset() {
    active = false; tooLow = false;
    climbing = true; vmode = VM_CLB; lastTroughY = -1.0e9; cycMinY = Double.MAX_VALUE;
    loadProfile();
    phase = HOLD; commandedPitch = C_DIVE; topTicks = 0; cycleTicks = 0;
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

    // current altitude (player Y), green, just the number — lined up with the crosshair and placed past the
    // heading bar's full travel (cx ± maxOff) so it never sits under the moving vertical bar.
    String altS = Integer.toString((int) Math.round(p.getY()));
    g.text(mc.font, altS, cx + maxOff + 8, cy - mc.font.lineHeight / 2, GREEN, true);

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
