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

public class FlightDirector {

  public static boolean isActive()      { return active; }
  public static float   commandedPitch() { return commandedPitch; }

  // --- commanded technique: two tuned profiles from the steady-state optimizer sweep ---
  private record Profile(float dive, float up, double trigger, int topHold, float sweep,
                         double avgSpeed, int aglMin) {}
  // MAX CLIMB: ~25 m/s, climbs ~+1 block/s; dive sinks ~68 blocks, so engage with margin at 80
  private static final Profile MAX_CLIMB = new Profile(34f, -42f, 50.0, 16, 0.55f, 25.0, 80);
  // MAX SPEED: ~33 m/s, holds altitude; steeper/faster dive sinks ~114 blocks, so engage at 135
  private static final Profile MAX_SPEED = new Profile(41f, -42f, 60.0, 28, 1.00f, 33.0, 135);
  private static Profile profile() { return Settings.flightMaxSpeed ? MAX_SPEED : MAX_CLIMB; }

  /** Mean horizontal cruise speed (m/s) of the active profile, for range/endurance planning. */
  public static double averageGroundSpeed() { return profile().avgSpeed(); }
  /** Clear blocks needed straight down before the directors engage (mode-dependent dive budget). */
  public static int requiredAltitude() { return profile().aglMin(); }

  private static final int MAGENTA = 0xFFFF00FF;

  // --- state machine ---
  private static final int HOLD = 0, TOP = 1, SWEEP = 2;
  private static int phase = HOLD;
  private static float commandedPitch = MAX_CLIMB.dive();
  private static int topTicks = 0;
  private static boolean active = false;

  public static void register() {
    HudElementRegistry.attachElementAfter(
        VanillaHudElements.MISC_OVERLAYS,
        Identifier.fromNamespaceAndPath(Kine.MOD_ID, "flightdir"),
        FlightDirector::render
    );
    ClientTickEvents.END_CLIENT_TICK.register(FlightDirector::tick);
  }

  private static void tick(Minecraft mc) {
    LocalPlayer p = mc.player;
    if (p == null || mc.level == null || !p.isFallFlying() || !hasAltitude(mc, p)) {
      active = false;
      phase = HOLD; commandedPitch = profile().dive(); topTicks = 0;   // reset for next engagement
      return;
    }

    double dx = p.getX() - p.xOld, dz = p.getZ() - p.zOld;
    double hSpeed = Math.sqrt(dx * dx + dz * dz) * 20.0;   // m/s, matches the velocity HUD

    Profile pr = profile();
    switch (phase) {
      case HOLD -> {
        commandedPitch = pr.dive();
        if (hSpeed >= pr.trigger()) { phase = TOP; topTicks = 0; commandedPitch = pr.up(); }
      }
      case TOP -> {
        commandedPitch = pr.up();
        if (++topTicks >= pr.topHold()) phase = SWEEP;
      }
      case SWEEP -> {
        commandedPitch += pr.sweep();
        if (commandedPitch >= pr.dive()) { commandedPitch = pr.dive(); phase = HOLD; }
      }
    }
    active = true;
  }

  /** True if there are at least the active profile's required clear blocks straight below. */
  private static boolean hasAltitude(Minecraft mc, LocalPlayer p) {
    int x = p.getBlockX(), z = p.getBlockZ();
    int top = (int) Math.floor(p.getY()) - 1;
    int limit = Math.max(mc.level.getMinY(), top - profile().aglMin());
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int y = top; y > limit; y--) {
      pos.set(x, y, z);
      if (!mc.level.getBlockState(pos).isAir()) return false;   // ground too close
    }
    return true;
  }

  private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
    if (!Settings.displayFlightDirectors) return;
    if (!active) return;
    Minecraft mc = Minecraft.getInstance();
    LocalPlayer p = mc.player;
    if (p == null) return;

    int W = mc.getWindow().getGuiScaledWidth();
    int H = mc.getWindow().getGuiScaledHeight();
    int cx = W / 2, cy = H / 2;

    // pitch command error -> vertical offset of the horizontal bar
    float error = commandedPitch - p.getXRot();   // +ve = pitch down to follow
    float k = H * 0.35f / 30f;                     // ~35% of screen per 30°
    int maxOff = (int) (H * 0.25f);
    int off = (int) Math.max(-maxOff, Math.min(maxOff, error * k));
    int barY = cy + off;

    int thick = 1, half = 60, gap = 0;
    // horizontal director bar
    g.fill(cx - half, barY - thick, cx - gap,  barY + thick, MAGENTA);
    g.fill(cx + gap,  barY - thick, cx + half, barY + thick, MAGENTA);
    // vertical director bar
    g.fill(cx - thick, cy - half, cx + thick, cy + half, MAGENTA);

    // mode annunciator, just above the horizontal bar's maximum upward deflection (cy - maxOff)
    String mode = Settings.flightMaxSpeed ? "MAX SPEED" : "MAX CLIMB";
    int annY = cy - maxOff - mc.font.lineHeight - 6;
    g.text(mc.font, mode, cx - mc.font.width(mode) / 2, annY, 0xFF44FF44, true);
  }
}
