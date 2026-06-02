package ai.rrw.kine.combat;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.Autopilot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;

/**
 * AFK damage protection. If you take damage without having given any input for a while, you're
 * logged out before something can kill you while you're away from the keyboard — the same dead-man
 * idea as the autopilot and durability failsafes, but for any situation. The disconnect screen
 * names what hit you.
 *
 * Input is anything that proves a human is present: a movement/action key held, the mouse actually
 * moving the view (ignored while the autopilot is the one steering, since that isn't you), or a
 * screen being open. Damage is any health drop or fresh hurt this tick; the source is read straight
 * off the client, which the damage-event packet populates on the same tick the hit lands.
 */
public class AfkGuard {

    private static final int   IDLE_TICKS = 15 * 20;   // 15 s with no input counts as AFK
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

        // --- idle tracking: any sign a human is at the controls resets the clock ---
        boolean input = mc.screen != null || keyHeld(mc.options);
        if (!Autopilot.isEngaged()                                   // the autopilot moving the view isn't you
            && (Math.abs(p.getXRot() - lastXRot) > LOOK_EPS
                || Math.abs(Mth.wrapDegrees(p.getYRot() - lastYRot)) > LOOK_EPS)) {
            input = true;
        }
        lastXRot = p.getXRot();
        lastYRot = p.getYRot();
        idleTicks = input ? 0 : idleTicks + 1;

        // --- damage detection: a fresh hit (hurtTime jumps) or any health loss this tick ---
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
        // A concise, neutral source — the attacker's name if there is one, otherwise the damage
        // type's short id (e.g. "lava", "fall", "cactus"). Deliberately NOT getLocalizedDeathMessage,
        // which reads like an obituary and would make a precautionary logout look like a death.
        Component source = src == null
            ? Component.literal("an unknown source")
            : (src.getEntity() != null ? src.getEntity().getName() : Component.literal(src.getMsgId()));

        MutableComponent reason = Component.literal("Kine \u2014 AFK damage protection\n\n")
            .append("You were logged out safely because you took damage (from ")
            .append(source)
            .append(") after " + (IDLE_TICKS / 20) + " seconds with no input.\n\n")
            .append("You did not die \u2014 this is a precaution so nothing finishes you off while you're "
                + "away. Just reconnect to carry on.");

        Kine.LOGGER.warn("kine: AFK damage protection \u2014 disconnecting (idle {}s, source {})",
            IDLE_TICKS / 20, src != null ? src.getMsgId() : "unknown");
        ClientPacketListener cpl = mc.getConnection();
        if (cpl != null) cpl.getConnection().disconnect(reason);
    }
}
