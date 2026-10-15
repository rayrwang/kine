package ai.rrw.kine.combat;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.util.Disconnect;
import ai.rrw.kine.util.InventoryClicks;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
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
 * Input is a movement or action key held (a sneak, sprint, attack or use key set to Toggle in the controls counts
 * when pressed, not while latched on), the mouse turning the view (not counted while the autopilot
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
    private static final boolean[] toggleWasDown = new boolean[4];   // sneak, sprint, attack and use last tick

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AfkGuard::tick);
    }

    /** Ticks since the pilot's last input, as this guard counts it, whether or not the protection is on: the
     *  durability failsafe reads it for its no-input disconnect, so there is one definition of input. Counted at the
     *  end of each tick. */
    public static int idleTicks() { return idleTicks; }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { reset(); return; }      // not in a world; never carry state across

        // idle tracking: any sign of a person at the controls resets the clock
        // An open screen counts only while the window has focus: when it loses focus the game opens the pause
        // screen (Minecraft.pauseIfInactive), which would otherwise always count as someone being there.
        boolean input = (Compat.screen(mc) != null && mc.isWindowActive()) || keyHeld(mc.options)
            || ai.rrw.kine.view.Freecam.inputThisTick();              // flying the freecam is you being there
        if (!Autopilot.isEngaged()                                   // the autopilot moving the view isn't you
            && (Math.abs(p.getXRot() - lastXRot) > LOOK_EPS
                || Math.abs(Mth.wrapDegrees(p.getYRot() - lastYRot)) > LOOK_EPS)) {
            input = true;
        }
        lastXRot = p.getXRot();
        lastYRot = p.getYRot();
        idleTicks = input ? 0 : idleTicks + 1;
        if (!Settings.afkDamageProtection) { baseline(p); return; }   // the idle count goes on; no kick

        // damage: a fresh hit (hurtTime jumps) or any health loss this tick
        float hp = p.getHealth();
        boolean tookDamage = primed && (p.hurtTime > lastHurtTime || hp < lastHealth - 0.01f);
        lastHurtTime = p.hurtTime;
        lastHealth = hp;
        primed = true;

        // not after a lethal hit: logging out saves nothing then, and the message would say you did not die
        if (tookDamage && idleTicks >= IDLE_TICKS && !p.isDeadOrDying()) kick(mc, p);
    }

    private static boolean keyHeld(Options o) {
        boolean toggles = toggleInput(0, o.keyShift, o.toggleCrouch().get())
            | toggleInput(1, o.keySprint, o.toggleSprint().get())
            | toggleInput(2, o.keyAttack, Compat.toggleAttack(o))
            | toggleInput(3, o.keyUse, Compat.toggleUse(o));
        return toggles || o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
            || o.keyJump.isDown() || o.keyPickItem.isDown() || o.keyDrop.isDown() || o.keySwapOffhand.isDown();
    }

    // Sneak, sprint, attack and use can be set to Toggle, and then isDown() is a state latched on by one press
    // until the next, which would count as input forever. In the tick Kine plays an inventory opening
    // (InventoryClicks), Kine let go of every key itself: that is neither input nor a state to compare with.
    private static boolean toggleInput(int i, KeyMapping key, boolean toggled) {
        if (InventoryClicks.isOpenTick()) return false;
        boolean down = key.isDown();
        boolean input = keyInput(toggled, down, toggleWasDown[i]);
        toggleWasDown[i] = down;
        return input;
    }

    /** Whether a key counts as input: held down, or for a key set to Toggle, pressed since last tick (each press
     *  flips its latched state). */
    static boolean keyInput(boolean toggled, boolean down, boolean wasDown) {
        return toggled ? down != wasDown : down;
    }

    /** Snapshot the health without arming the trigger (while the protection is off). */
    private static void baseline(LocalPlayer p) {
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
