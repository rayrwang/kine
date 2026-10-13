package ai.rrw.kine.fight;

import ai.rrw.kine.combat.SwingPlan;
import ai.rrw.kine.move.GroundPhysics;
import ai.rrw.kine.move.MoveState;

import java.util.SplittableRandom;

/**
 * The simulated fight: the player (its exact movement state, health, armour, its own hurt window), the mobs, the kill
 * aura when it is on, and the server's copy of the player's velocity (a mob's knockback is computed from it). One
 * {@link #tick} plays a client tick and the server tick after it, in the game's order:
 * <ol>
 *   <li>the kill aura swings or turns (before handleKeybinds), setting the rotation sent;</li>
 *   <li>mobs touching the player push it (their client copies tick before or with it);</li>
 *   <li>the player moves under the keys, by the exact copy (GroundPhysics), at the rotation sent;</li>
 *   <li>the server: the player's hurt window counts down; each mob thinks (re-aims its path, attacks if it can; a hit
 *       hurts the player through armour and its window and sets its velocity by knockback, as the server's motion
 *       packet does before the next client tick), then moves; the charge counts on.</li>
 * </ol>
 */
public final class FightWorld {
    public MoveState player;
    public double health = 20, absorption = 0;
    /** The player's max health (the health weight grows as health drops toward 0). */
    public double maxHealth = 20;
    public double armour = 0, toughness = 0;
    /** Damage taken is multiplied by this after armour (Resistance: 1 - 0.2 per level). */
    public double resistance = 1.0;
    /** The player's own hurt window (invulnerableTime; full hits only at 10 and below) and the biggest hit in it. */
    public int invulnerable = 0;
    public double lastHurt = 0;
    /** A knockback on its way: the velocity the server's motion packet will set, and the ticks until the client's
     *  tick starts with it (measured in the lockstep zombie test: the client tick after next). */
    public double kbx, kby, kbz;
    public int kbIn = 0;
    /** The player's last move, as the client sees it (x - xo). */
    public double pdx, pdz;
    public MobSim[] mobs;
    public AuraSim aura;
    /** Health taken off mobs and off the player so far, and ticks played. */
    public double dealt, taken;
    public int ticks;
    /** Ticks since a mob in the fight last lost health, at the snapshot (the idle cost grows with it). */
    public int lull;
    /** The heading a situational action settled on at the start of its segment (yaw degrees). */
    public float segmentYaw;
    /** The worst safety mark so far: a fall, a hazard touched, the edge of the known world. */
    public double unsafe;

    public FightWorld copy() {
        FightWorld w = new FightWorld();
        w.player = player.copy();
        w.health = health; w.maxHealth = maxHealth; w.absorption = absorption; w.armour = armour; w.toughness = toughness; w.resistance = resistance;
        w.invulnerable = invulnerable; w.lastHurt = lastHurt;
        w.kbx = kbx; w.kby = kby; w.kbz = kbz; w.kbIn = kbIn; w.pdx = pdx; w.pdz = pdz;
        w.mobs = new MobSim[mobs.length];
        for (int i = 0; i < mobs.length; i++) w.mobs[i] = mobs[i].copy();
        w.aura = aura.copy();
        w.dealt = dealt; w.taken = taken; w.ticks = ticks; w.lull = lull; w.segmentYaw = segmentYaw; w.unsafe = unsafe;
        return w;
    }

    public int alive() {
        int n = 0;
        for (MobSim m : mobs) if (m.alive) n++;
        return n;
    }

    /** The keys for a tick, chosen once the rotation sent this tick is known (the kill aura sets it first). */
    public interface Keys {
        int at(float sentYaw);

        /** The rotation the player moves by this tick: the one sent (an "ideal" chooser, for measuring what tilting
         *  the rotation toward the heading could gain, moves by its own). */
        default float moveYaw(float sentYaw) { return sentYaw; }
    }

    /** One tick with fixed keys. */
    public double[] tick(GroundPhysics phys, int keys, SplittableRandom rnd) {
        return tick(phys, yaw -> keys, rnd);
    }

    /** One tick; the keys are asked for at the rotation sent. Returns {health taken off mobs, health the player lost}. */
    public double[] tick(GroundPhysics phys, Keys chooser, SplittableRandom rnd) {
        if (kbIn > 0 && --kbIn == 0) { player.vx = kbx; player.vy = kby; player.vz = kbz; }
        double dealtNow = aura.tick(player, pdx, pdz, phys.body().walkSpeed, mobs);
        int keys = chooser.at(aura.sentYaw);
        // pushes: each mob whose box meets the player's pushes it away (Entity.push, by the mob's client copy)
        for (MobSim m : mobs) {
            if (!m.alive) continue;
            double hw = m.width / 2, pw = phys.body().width / 2;
            if (Math.abs(m.x - player.x) < hw + pw && Math.abs(m.z - player.z) < hw + pw
                && m.y < player.y + phys.body().height && m.y + m.height > player.y) {
                double xa = player.x - m.x, za = player.z - m.z;
                double dd = Math.max(Math.abs(xa), Math.abs(za));
                if (dd >= 0.01F) {
                    dd = Math.sqrt(dd);
                    xa /= dd; za /= dd;
                    double pow = Math.min(1.0, 1.0 / dd);
                    player.vx += xa * pow * 0.05F;
                    player.vz += za * pow * 0.05F;
                }
            }
        }
        double x0 = player.x, z0 = player.z;
        phys.step(player, keys, chooser.moveYaw(aura.sentYaw));
        pdx = player.x - x0; pdz = player.z - z0;
        // the server tick
        if (invulnerable > 0) invulnerable--;
        double[] pb = playerBox(phys);
        double lost = 0;
        for (MobSim m : mobs) {
            if (!m.alive) continue;
            m.tickWindow();
            if (m.think(player.x, player.y, player.z, pb, rnd)) lost += hurtPlayer(m, phys);
            m.move();
        }
        aura.endTick();
        ticks++;
        dealt += dealtNow;
        taken += lost;
        return new double[]{dealtNow, lost};
    }

    /** A mob's hit on the player (Player.hurtServer, LivingEntity.hurtServer and actuallyHurt): the hurt window, armour,
     *  Resistance, absorption, and the knockback, which becomes the player's velocity. Returns the health lost. */
    double hurtPlayer(MobSim m, GroundPhysics phys) {
        double dmg = m.attackDamage;
        double through;
        if (invulnerable > 10) {
            if (dmg <= lastHurt) return 0.0;
            through = dmg - lastHurt;
            lastHurt = dmg;
        } else {
            lastHurt = dmg;
            invulnerable = 20;
            through = dmg;
            // LivingEntity.knockback(0.4, toward the mob) on the server's copy of the player, whose velocity there is
            // that of a player at rest (the server moves it with no keys: (0, -0.0784, 0) on the ground); then the
            // server's own tick of physics on it before the motion packet goes (horizontal x friction, vertical
            // (vy - 0.08) x 0.98). Measured in the lockstep zombie test: (0.2184 x the push's direction, 0.2752).
            double xd = m.x - player.x, zd = m.z - player.z, d = Math.sqrt(xd * xd + zd * zd);
            if (d < 1e-4) { xd = 0.01; zd = 0; d = 0.01; }
            double power = 0.4F * (1.0 - phys.body().knockbackResistance);
            double ky = player.onGround ? Math.min(0.4, -0.0784000015258789 / 2.0 + power) : player.vy;
            float f = player.onGround
                ? phys.grid().get((int) Math.floor(player.x), (int) Math.floor(player.y - 0.5), (int) Math.floor(player.z)).friction * 0.91F
                : 0.91F;
            kbx = -xd / d * power * f;
            kbz = -zd / d * power * f;
            kby = (ky - 0.08) * 0.98F;
            kbIn = 2;
        }
        double after = SwingPlan.afterArmour(through, armour, toughness) * resistance;
        double fromAbsorption = Math.min(absorption, after);
        absorption -= fromAbsorption;
        double lost = after - fromAbsorption;
        health -= lost;
        return after;
    }

    public double[] playerBox(GroundPhysics phys) {
        float w = phys.body().width / 2.0F, h = player.crouchPose ? phys.body().crouchHeight : phys.body().height;
        return new double[]{player.x - w, player.y, player.z - w, player.x + w, player.y + h, player.z + w};
    }
}
