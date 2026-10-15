package ai.rrw.kine.hud;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.Gui2D;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.FlightDirector;
import ai.rrw.kine.autoflight.Guardian;
import ai.rrw.kine.autoflight.Landing;
import ai.rrw.kine.autoflight.Nav;
import ai.rrw.kine.autoflight.Takeoff;
import ai.rrw.kine.autoflight.TurnGuard;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * The flight director display: magenta bars for the commanded pitch (horizontal bar) and heading
 * (vertical bar), and TOO LOW TO ACTIVATE AUTOPILOT when it can't arm; and beside them the altitude, a
 * display of its own.
 * Guidance only; FlightDirector holds the law.
 */
public final class FlightDirectorHud {
    private FlightDirectorHud() {}

    // The heading bar compares the commanded yaw with the player's per-tick yaw, not the per-frame
    // interpolated view yaw (mixing the two makes the bar sawtooth at tick rate). A frame-rate-independent
    // low-pass smooths the per-tick steps.
    private static final float YAW_BAR_TC = 0.08f;   // heading-bar low-pass time constant (s)
    private static Float smoothYawOff = null;         // null = not currently steering
    private static long  yawNanos = 0L;               // wall-clock of the last bar update, for the dt-based filter

    public static void register() {
        Compat.hud("flightdir", FlightDirectorHud::render);
    }

    private static void render(Gui2D g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int cx = W / 2, cy = H / 2;
        int maxOff = HudLayout.directorTravel(H);

        // current altitude, level with the crosshair and past the heading bar's full travel (cx + maxOff), so
        // the moving bar never covers it
        if (Settings.shows(Settings.displayAltitude)) {
            String altS = Integer.toString((int) Math.round(p.getY()));
            g.text(mc.font, altS, cx + maxOff + 8, cy - mc.font.lineHeight / 2, HudColors.GREEN, true);
        }

        if (!Settings.shows(Settings.displayFlightDirectors)) return;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        if (Takeoff.active()) {
            // the takeoff flies the view: the bars show its commanded attitude, as they show the autopilot's (RW)
            bars(g, p, partial, cx, cy, maxOff, H, Takeoff.commandedPitch(), Mth.wrapDegrees(Takeoff.commandedYaw() - p.getYRot()));
            return;
        }
        if (Takeoff.usesWarningRow()) return;   // the takeoff's prompt has the row, and the law flies nothing until it hands over
        if (!p.isFallFlying()) return;   // the bars are for elytra flight only, never in creative free flight (RW); the takeoff's above
        if (Guardian.rescuing()) {
            // the guardian flies the view: the bars show its rescue's look; TERRAIN has the warning row
            bars(g, p, partial, cx, cy, maxOff, H, Guardian.commandedPitch(), Mth.wrapDegrees(Guardian.commandedYaw() - p.getYRot()));
            return;
        }

        boolean landing = Nav.landing();
        if (FlightDirector.isTooLow() && !landing && !Landing.valid()) {   // a landing spot on offer has the row instead
            // the too-low warning and the nav readout share a row; too-low wins
            String s = "TOO LOW TO ACTIVATE AUTOPILOT";
            int y = HudLayout.warningRow(H, mc.font.lineHeight);
            g.text(mc.font, s, cx - mc.font.width(s) / 2, y, HudColors.RED, true);
            return;
        }
        if (!FlightDirector.isActive() && !landing) return;

        // Interpolate both inputs to the bar between ticks so it tracks at the framerate, not the 20 Hz
        // tick rate. The commanded pitch is the landing descent while landing, otherwise the porpoise lerped
        // from its previous-tick value.
        float cmdNow  = landing ? Nav.landingPitch() : Mth.lerp(partial, FlightDirector.commandedPitchOld(), FlightDirector.commandedPitch());

        // The vertical bar is the heading director: it deflects toward the heading the autopilot steers to,
        // the terrain-avoidance heading while that is steering, else the nav heading (the autopilot's order).
        Float yawErr = null;
        if (Settings.terrainAvoidance && TurnGuard.steering()) {
            yawErr = Mth.wrapDegrees((float) TurnGuard.desiredHeading() - p.getYRot());
        } else if (Nav.steering()) {
            yawErr = Mth.wrapDegrees(Nav.desiredYaw(p) - p.getYRot());
        }
        bars(g, p, partial, cx, cy, maxOff, H, cmdNow, yawErr);
    }

    // The two bars for a commanded pitch and a heading error (null: no heading bar deflection). getViewXRot is
    // the same smoothed pitch the camera uses.
    private static void bars(Gui2D g, LocalPlayer p, float partial, int cx, int cy, int maxOff, int H,
                             float cmdNow, Float yawErr) {
        float pitchNow = p.getViewXRot(partial);
        float k = H * 0.35f / 30f;                     // 35% of the screen height per 30 degrees
        int off = (int) Math.max(-maxOff, Math.min(maxOff, (cmdNow - pitchNow) * k));
        int barY = cy + off;

        int barX = cx;
        if (yawErr != null) {
            // compare against the per-tick heading, not the interpolated view yaw, so the offset doesn't sawtooth
            float rawOff = Math.max(-maxOff, Math.min(maxOff, yawErr * k));
            long now = System.nanoTime();
            double dt = (yawNanos == 0L) ? 0.0 : (now - yawNanos) / 1.0e9;
            yawNanos = now;
            if (smoothYawOff == null || dt <= 0 || dt > 0.5) {
                smoothYawOff = rawOff;   // seed on the first steering frame or after a hitch, so it doesn't sweep in from centre
            } else {
                smoothYawOff = (float) (smoothYawOff + (rawOff - smoothYawOff) * (1.0 - Math.exp(-dt / YAW_BAR_TC)));
            }
            barX = cx + (int) (float) smoothYawOff;
        } else {
            smoothYawOff = null;
            yawNanos = 0L;
        }

        int thick = 1, half = 60, gap = 0;
        // horizontal (pitch) director bar, moves vertically
        g.fill(cx - half, barY - thick, cx - gap,  barY + thick, HudColors.MAGENTA);
        g.fill(cx + gap,  barY - thick, cx + half, barY + thick, HudColors.MAGENTA);
        // vertical (heading) director bar, moves horizontally
        g.fill(barX - thick, cy - half, barX + thick, cy + half, HudColors.MAGENTA);
    }
}
