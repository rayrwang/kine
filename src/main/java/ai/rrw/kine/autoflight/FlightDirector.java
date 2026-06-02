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

  public static boolean isActive()      { return active; }
  public static float   commandedPitch() { return commandedPitch; }

  // --- commanded technique (hand-tuned and confirmed in-flight to hold/gain altitude) ---
  // An offline sweep suggested "faster" profiles, but they lose altitude in practice once the real
  // pitch-tracking lag is included, so we stick with the profile that actually works.
  private static final float  DIVE       = 34f;        // nose-down hold angle (xRot +)
  private static final float  UP         = -48f;       // nose-up snap angle (xRot -)
  private static final double TRIGGER    = 44.0;       // snap up once horiz speed (m/s) reaches this
  private static final int    TOP_HOLD   = 12;         // ticks to hold the snap (~0.6 s)
  private static final float  SWEEP_RATE = 13f / 20f;  // deg per tick easing back down

  // Altitude gating with hysteresis: need ENGAGE_AGL clear blocks below to ARM (room for a full dive
  // plus margin), but once armed we stay armed until dropping below FLOOR_AGL. Without the gap, the
  // dive — which is meant to eat altitude — would drop us under the arm threshold and disengage us
  // mid-maneuver. The dive budget (ENGAGE - FLOOR) comfortably exceeds the profile's real dive depth.
  private static final int ENGAGE_AGL = 120;
  private static final int FLOOR_AGL  = 48;

  private static final int MAGENTA = 0xFFFF00FF;
  private static final int RED     = 0xFFFF3030;

  // --- state machine ---
  private static final int HOLD = 0, TOP = 1, SWEEP = 2;
  private static int phase = HOLD;
  private static float commandedPitch = DIVE;
  private static float commandedPitchOld = DIVE;   // value at the previous tick, for per-frame interpolation
  private static int topTicks = 0;
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
    // arm needs ENGAGE_AGL; once armed, stay armed until below FLOOR_AGL
    if (active ? clear < FLOOR_AGL : clear < ENGAGE_AGL) {
      active = false;
      tooLow = true;                 // gliding but can't (or no longer can) operate safely
      phase = HOLD; commandedPitch = DIVE; topTicks = 0;
      return;
    }
    active = true;
    tooLow = false;

    double dx = p.getX() - p.xOld, dz = p.getZ() - p.zOld;
    double hSpeed = Math.sqrt(dx * dx + dz * dz) * 20.0;   // m/s, matches the velocity HUD

    switch (phase) {
      case HOLD -> {
        commandedPitch = DIVE;
        if (hSpeed >= TRIGGER) { phase = TOP; topTicks = 0; commandedPitch = UP; }
      }
      case TOP -> {
        commandedPitch = UP;
        if (++topTicks >= TOP_HOLD) phase = SWEEP;
      }
      case SWEEP -> {
        commandedPitch += SWEEP_RATE;
        if (commandedPitch >= DIVE) { commandedPitch = DIVE; phase = HOLD; }
      }
    }
  }

  private static void reset() {
    active = false; tooLow = false;
    phase = HOLD; commandedPitch = DIVE; topTicks = 0;
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
      // red advisory — but yield this slot to the nav readout when a nav mode is set
      if (Nav.mode() == Nav.Mode.OFF) {
        String s = "TOO LOW TO ACTIVATE AUTOPILOT";
        int y = cy - maxOff - mc.font.lineHeight - 6;
        g.text(mc.font, s, cx - mc.font.width(s) / 2, y, RED, true);
      }
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
      float yawErr = Mth.wrapDegrees(Nav.desiredYaw(p) - p.getViewYRot(partial));
      barX = cx + (int) Math.max(-maxOff, Math.min(maxOff, yawErr * k));
    }

    int thick = 1, half = 60, gap = 0;
    // horizontal (pitch) director bar — moves vertically
    g.fill(cx - half, barY - thick, cx - gap,  barY + thick, MAGENTA);
    g.fill(cx + gap,  barY - thick, cx + half, barY + thick, MAGENTA);
    // vertical (heading) director bar — moves horizontally with the nav turn command
    g.fill(barX - thick, cy - half, barX + thick, cy + half, MAGENTA);
  }
}
