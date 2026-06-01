package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
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

  // --- commanded technique (from the optimizer) ---
  private static final float DIVE       = 34f;   // nose-down hold angle (xRot +)
  private static final float UP         = -48f;  // nose-up snap angle (xRot -)
  private static final double TRIGGER   = 44.0;  // snap up once horiz speed (m/s) reaches this
  private static final int   TOP_HOLD   = 12;    // ticks to hold the snap (~0.6 s)
  private static final float SWEEP_RATE = 13f / 20f;  // deg per tick easing back down
  private static final int   AGL_MIN    = 64;    // need this many clear blocks below (dive budget ~50)

  private static final int MAGENTA = 0xFFFF00FF;

  // --- state machine ---
  private static final int HOLD = 0, TOP = 1, SWEEP = 2;
  private static int phase = HOLD;
  private static float commandedPitch = DIVE;
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
      phase = HOLD; commandedPitch = DIVE; topTicks = 0;   // reset for next engagement
      return;
    }

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
    active = true;
  }

  /** True if there are at least AGL_MIN air blocks straight below — room to dive. */
  private static boolean hasAltitude(Minecraft mc, LocalPlayer p) {
    int x = p.getBlockX(), z = p.getBlockZ();
    int top = (int) Math.floor(p.getY()) - 1;
    int limit = Math.max(mc.level.getMinY(), top - AGL_MIN);
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int y = top; y > limit; y--) {
      pos.set(x, y, z);
      if (!mc.level.getBlockState(pos).isAir()) return false;   // ground too close
    }
    return true;
  }

  private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
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
    int maxOff = (int) (H * 0.40f);
    int off = (int) Math.max(-maxOff, Math.min(maxOff, error * k));
    int barY = cy + off;

    int thick = 1, half = 60, gap = 0;
    // horizontal director (commands pitch): two wings with a gap for the boresight
    g.fill(cx - half, barY - thick, cx - gap,  barY + thick, MAGENTA);
    g.fill(cx + gap,  barY - thick, cx + half, barY + thick, MAGENTA);
    // vertical director (roll/heading): centered + unused for now
    g.fill(cx - thick, cy - 40, cx + thick, cy + 40, MAGENTA);
  }
}
