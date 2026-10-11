package ai.rrw.kine.combat;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.util.Disconnect;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;

/**
 * AFK damage protection: if you take damage after IDLE_TICKS with no input, you are logged out before
 * something kills you, and the disconnect screen names what hit you.
 *
 * Input is a movement or action key held, the mouse turning the view (not counted while the autopilot
 * steers), or an open screen while the game window has focus. Damage is a health drop or a fresh hurt this tick; the damage event
 * packet sets the source on the client on the same tick.
 */
public class AfkGuard {

    private static final int   IDLE_TICKS = 15 * 20;   // 15 s with no input is AFK
    private static final float LOOK_EPS   = 0.1f;      // view-angle change (deg) that counts as a mouse input

    private static int     idleTicks = 0;
    private static float   lastXRot, lastYRot;
    private static float   lastHealth = Float.NaN;
    private static int     lastHurtTime = 0;
    private static boolean primed = false;             // do we have a baseline to diff against yet

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AfkGuard::tick);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { reset(); return; }      // not in a world; never carry state across
        if (!Settings.afkDamageProtection) { baseline(p); idleTicks = 0; return; }

        // idle tracking: any sign of a person at the controls resets the clock
        // An open screen counts only while the window has focus: when it loses focus the game opens the pause
        // screen (Minecraft.pauseIfInactive), which would otherwise always count as someone being there.
        boolean input = (mc.screen != null && mc.isWindowActive()) || keyHeld(mc.options);
        if (!Autopilot.isEngaged()                                   // the autopilot moving the view isn't you
            && (Math.abs(p.getXRot() - lastXRot) > LOOK_EPS
                || Math.abs(Mth.wrapDegrees(p.getYRot() - lastYRot)) > LOOK_EPS)) {
            input = true;
        }
        lastXRot = p.getXRot();
        lastYRot = p.getYRot();
        idleTicks = input ? 0 : idleTicks + 1;

        // damage: a fresh hit (hurtTime jumps) or any health loss this tick
        float hp = p.getHealth();
        boolean tookDamage = primed && (p.hurtTime > lastHurtTime || hp < lastHealth - 0.01f);
        lastHurtTime = p.hurtTime;
        lastHealth = hp;
        primed = true;

        if (tookDamage && idleTicks >= IDLE_TICKS) kick(mc, p);
    }

    private static boolean keyHeld(Options o) {
        return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
            || o.keyJump.isDown() || o.keyShift.isDown() || o.keySprint.isDown()
            || o.keyAttack.isDown() || o.keyUse.isDown() || o.keyPickItem.isDown()
            || o.keyDrop.isDown() || o.keySwapOffhand.isDown();
    }

    /** Snapshot current state without arming the trigger (used when disabled or re-entering a world). */
    private static void baseline(LocalPlayer p) {
        lastXRot = p.getXRot(); lastYRot = p.getYRot();
        lastHealth = p.getHealth(); lastHurtTime = p.hurtTime; primed = true;
    }

    private static void reset() {
        idleTicks = 0; primed = false; lastHealth = Float.NaN; lastHurtTime = 0;
    }

    private static void kick(Minecraft mc, LocalPlayer p) {
        reset();
        DamageSource src = p.getLastDamageSource();
        // The attacker's name if there is one, otherwise the damage type's short id ("lava", "fall").
        // Not getLocalizedDeathMessage, which reads like an obituary.
        Component source = src == null
            ? Component.literal("an unknown source")
            : (src.getEntity() != null ? src.getEntity().getName() : Component.literal(src.getMsgId()));

        MutableComponent reason = Component.literal("Kine: AFK damage protection\n\n")
            .append("You were logged out because you took damage (from ")
            .append(source)
            .append(") after " + (IDLE_TICKS / 20) + " seconds with no input. You did not die.");

        Kine.LOGGER.warn("Kine: AFK damage protection, disconnecting (idle {} s, source {})",
            IDLE_TICKS / 20, src != null ? src.getMsgId() : "unknown");
        Disconnect.withMessage(mc, reason);
    }
}
