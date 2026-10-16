package ai.rrw.kine;

import ai.rrw.kine.autoflight.Landing;
import ai.rrw.kine.autoflight.Takeoff;
import ai.rrw.kine.combat.BowAura;
import ai.rrw.kine.combat.FallPrevention;
import ai.rrw.kine.combat.KillAura;
import ai.rrw.kine.combat.ProjectileDodge;
import ai.rrw.kine.combat.WaterClutch;
import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.Input;
import ai.rrw.kine.fight.CombatMovement;
import ai.rrw.kine.util.InventoryClicks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Where the features act on the game, so the server only ever receives what a precisely controlled vanilla
 * client could send (RW's server-side rule, 2026-10-11). Within a client tick a vanilla client picks what its
 * crosshair is on, attacks or uses in handleKeybinds, then reads its keys, moves, and sends its input and its
 * one rotation; the tick ends with a tick-end packet, and inventory clicks come from screens between ticks.
 * <ul>
 *   <li>The bow aura draws and looses by holding and letting go of the use key before handleKeybinds, so the game
 *       starts the use (with this tick's rotation) and sends the release itself.</li>
 *   <li>Attacks and item uses go through the game's own startAttack and startUseItem just before
 *       handleKeybinds ({@link #beforeKeybinds}), after a turn by whole mouse counts, so they hit what that
 *       rotation's crosshair is on and this tick's move packet carries that rotation.</li>
 *   <li>Movement is pressed keys ({@link #adjustInput}), so the player moves by the game's own physics
 *       and the input packet shows the keys.</li>
 *   <li>The kill aura's rotation is its own: turned to at the start of each tick and undone once the rotation has
 *       been sent ({@link #afterEntities}), so the camera never shows it; it turns at most 30 degrees a tick.</li>
 *   <li>Inventory clicks run at the end of a tick, between ticks, each batch followed by the close packet
 *       a vanilla client sends when it closes its inventory. Where a feature asks for it, the tick before the
 *       clicks plays the inventory's opening (InventoryClicks.requestOpen): every key up, no action.</li>
 * </ul>
 */
public final class VanillaActions {
    private VanillaActions() {}

    /** Just before handleKeybinds, with no screen or overlay open. One action a tick. */
    public static void beforeKeybinds(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        BowAura.guard(mc);   // first: a stand-down lets go of the bow before anything else acts
        if (InventoryClicks.isOpenTick()) { InventoryClicks.playOpen(); return; }   // the inventory "opens": no action this tick
        if (Takeoff.beforeKeybinds(mc)) return;
        if (Landing.beforeKeybinds(mc)) return;
        if (WaterClutch.beforeKeybinds(mc)) return;
        KillAura.beforeKeybinds(mc);   // asks the bow aura where to turn when it has nothing to swing at
        BowAura.act(mc);   // after the aura's turn and pick: hold use to draw, let go to loose
        ai.rrw.kine.combat.AutoEat.beforeKeybinds(mc);   // after the aura's turn and pick: hold use to eat
        ai.rrw.kine.walk.DoorOpener.beforeKeybinds(mc);   // after the aura's turn and pick: the walk's click on a door
    }

    /** Once this tick's rotation has gone to the server, before the tick-end packet: each epoch's AfterRotationSentMixin
     *  (after the entities tick up to 26.2, where the player sends from its own tick; after LocalPlayer.sendChanges from
     *  26.3, where Minecraft.tick sends it after the entities). */
    public static void afterEntities(Minecraft mc) {
        KillAura.afterEntities(mc);
    }

    /** The keys the player holds this tick, as the features want them pressed. None with a screen open: a vanilla
     *  client sends no game keys behind one (opening it lets go of every key, and a key press reaches the game only
     *  with no screen up). */
    public static Input adjustInput(LocalPlayer p, Input keys) {
        if (Compat.screen(Minecraft.getInstance()) != null) { WaterClutch.sneakNotSent(); return keys; }
        keys = KillAura.adjustInput(p, keys);   // first: your keys, for the rotation the aura sends; the rest press theirs for it
        keys = CombatMovement.adjustInput(p, keys);   // while you press no movement key, near zombies
        keys = ai.rrw.kine.walk.Walk.adjustInput(p, keys);   // the pathfinder's walk, and its hand-back to you
        keys = Takeoff.adjustInput(p, keys);
        keys = ProjectileDodge.adjustInput(p, keys);
        keys = WaterClutch.adjustInput(p, keys);
        keys = ai.rrw.kine.combat.AutoEat.adjustInput(p, keys);   // sneak as the eating starts, so no block is used
        keys = FallPrevention.adjustInput(p, keys);   // last, so a dodge never steps off a cliff
        if (InventoryClicks.isOpenTick()) keys = Input.EMPTY;   // the tick the inventory "opens" in: every key up
        return keys;
    }
}
