package ai.rrw.kine.fight;

import ai.rrw.kine.combat.SwingPlan;
import ai.rrw.kine.move.GameMath;
import ai.rrw.kine.move.MoveState;

import java.util.ArrayList;
import java.util.List;

/**
 * The kill aura inside the fight simulation, swinging as the real one does (KillAura with SwingPlan): each tick, before
 * the player moves, it gathers the mobs in reach, asks SwingPlan.decide for the swing (threats first, swings that pay
 * for their charge, the charged swing when due, sweeps), hits if the rotation it sends can be on the mob within
 * MAX_TURN of the last one, and otherwise turns toward where the plan aims, at most MAX_TURN a tick, or back to the
 * view. The hit follows Player.attack: the charge, crits, the sprint knockback, the sweep, the mob's 10-tick window,
 * armour, knockback. Left out: blocks and other mobs in the way of the crosshair (every mob in reach counts as
 * hittable), auto weapon.
 */
public final class AuraSim {
    public boolean on = true;
    public SwingPlan.Weapon weapon = new SwingPlan.Weapon(8.0, 12.500001F, true, 0.0);
    /** The weapon's knockback (Knockback enchantment, attribute) added to a hit's push. */
    public double weaponKnockback = 0.0;
    /** Ticks since the last swing, as the server will count them (attackStrengthTicker). */
    public int k = 20;
    /** The yaw the server last got, and the player's view (camera) yaw. */
    public float sentYaw, viewYaw;
    public double reach = 3.0, eyeHeight = 1.62;
    public double turnBudget = 29.5;
    /** Swings made in the simulation so far. */
    public int swings = 0;

    public AuraSim copy() {
        AuraSim a = new AuraSim();
        a.on = on; a.weapon = weapon; a.weaponKnockback = weaponKnockback; a.k = k; a.sentYaw = sentYaw;
        a.viewYaw = viewYaw; a.reach = reach; a.eyeHeight = eyeHeight; a.turnBudget = turnBudget; a.swings = swings;
        return a;
    }

    /**
     * The aura's move for this tick, before the player moves. Returns the health it took off mobs. Sets sentYaw to the
     * rotation this tick sends (the player walks by it).
     */
    public double tick(MoveState p, double pdx, double pdz, double moveSpeed, MobSim[] mobs) {
        if (!on) { sentYaw = viewYaw; return 0.0; }
        double ex = p.x, ey = p.y + eyeHeight, ez = p.z;
        List<MobSim> found = new ArrayList<>();
        for (MobSim m : mobs) if (m.alive && eyeToBox(ex, ey, ez, m, m.seen()) <= reach) found.add(m);
        if (found.isEmpty()) { turnToward(viewYaw); return 0.0; }
        found.sort((a, b) -> Double.compare(d2(ex, ey, ez, a), d2(ex, ey, ez, b)));
        boolean canSweep = p.onGround && !p.sprinting && pdx * pdx + pdz * pdz < moveSpeed * 2.5 * moveSpeed * 2.5 && weapon.sword();
        List<SwingPlan.Mob> plan = new ArrayList<>(found.size());
        for (MobSim m : found) {
            SwingPlan.Mob s = new SwingPlan.Mob(m.health, m.armour);
            s.toughness = m.toughness;
            s.bonus = m.bonus;
            s.freshIn = m.freshIn;
            s.lastHurt = m.freshIn > 0 ? m.lastHurt : Double.MAX_VALUE;
            s.contact = contact(p, pdx, pdz, m);
            s.inPlay = true;
            double[] at = m.seen();
            double yaw = yawTo(ex, ez, at[0], at[2]);
            s.yaw = GameMath.wrapDegrees(yaw - sentYaw);
            double flat = Math.max(0.3, Math.hypot(at[0] - ex, at[2] - ez));
            s.halfWidth = Math.toDegrees(Math.atan2(m.width / 2, flat));
            double travel = Math.abs(s.yaw);
            s.turnTicks = travel <= turnBudget ? 0 : (int) Math.ceil((travel - turnBudget) / turnBudget);
            s.reachable = travel <= turnBudget;
            plan.add(s);
        }
        for (int i = 0; i < found.size(); i++) {
            List<Integer> sw = new ArrayList<>();
            for (int j = 0; j < found.size(); j++) if (j != i && swept(p, found.get(i), found.get(j))) sw.add(j);
            plan.get(i).swept = sw.stream().mapToInt(Integer::intValue).toArray();
        }
        boolean critable = !p.sprinting;
        boolean canCrit = critable && p.fallDistance > 0 && !p.onGround;
        int critIn = critable && !canCrit && !p.onGround ? critIn(p.vy) : -1;
        SwingPlan.Swing s = SwingPlan.decide(weapon, k, canSweep, canCrit, critIn, plan, turnBudget);
        if (s.hit() >= 0 && plan.get(s.hit()).reachable) {
            MobSim m = found.get(s.hit());
            double[] at = m.seen();
            sentYaw = (float) yawTo(ex, ez, at[0], at[2]);
            double dealt = hit(p, m, found, plan, s.hit(), canSweep, canCrit);
            k = 0;
            swings++;
            return dealt;
        }
        if (s.aim() >= 0) {
            MobSim m = found.get(s.aim());
            double[] at = m.seen();
            turnToward((float) yawTo(ex, ez, at[0], at[2]));
        } else {
            turnToward(viewYaw);
        }
        return 0.0;
    }

    /** After the player's tick: the charge counts on (Player.tick). */
    public void endTick() {
        k++;
    }

    private void turnToward(float yaw) {
        float d = GameMath.wrapDegrees(yaw - sentYaw);
        if (Math.abs(d) <= turnBudget) sentYaw = sentYaw + d;
        else sentYaw = sentYaw + (float) Math.copySign(turnBudget, d);
    }

    private double hit(MoveState p, MobSim m, List<MobSim> found, List<SwingPlan.Mob> plan, int idx, boolean canSweep, boolean canCrit) {
        float c = SwingPlan.charge(Math.max(1, k), weapon.delay());
        boolean full = c > 0.9F;
        boolean crit = canCrit && full;
        boolean sprintHit = p.sprinting && full;
        double raw = SwingPlan.hitDamage(weapon, m.bonus, c, crit);
        double dealt = hurt(p, m, raw, true);
        if (dealt >= 0 && (sprintHit || weaponKnockback > 0)) {   // Player.causeExtraKnockback along the player's facing
            double r = Math.toRadians(sentYaw);
            m.knockback((sprintHit ? 0.5 : 0.0) + weaponKnockback, Math.sin(r), -Math.cos(r));
        }
        double total = Math.max(0.0, dealt);
        if (weapon.sword() && canSweep && full && !crit && !sprintHit && dealt > 0) {   // Player.doSweepAttack
            for (int j : plan.get(idx).swept) {
                MobSim o = found.get(j);
                double sw = SwingPlan.sweepDamage(weapon, o.bonus, c);
                double d = hurt(p, o, sw, false);
                if (d >= 0) {
                    double r = Math.toRadians(sentYaw);
                    o.knockback(0.4, Math.sin(r), -Math.cos(r));
                }
                total += Math.max(0.0, d);
            }
        }
        return total;
    }

    /** LivingEntity.hurtServer on a mob: the window, armour, knockback 0.4 away from the player on a full hit.
     *  Returns the health taken, or -1 if the hit did nothing. */
    private static double hurt(MoveState p, MobSim m, double raw, boolean knock) {
        double through;
        boolean full = m.freshIn <= 0;
        if (!full) {
            if (raw <= m.lastHurt) return -1;
            through = raw - m.lastHurt;
            m.lastHurt = raw;
        } else {
            through = raw;
            m.lastHurt = raw;
            m.freshIn = SwingPlan.WINDOW;
        }
        double taken = Math.min(m.health, SwingPlan.afterArmour(through, m.armour, m.toughness));
        m.health -= taken;
        if (m.health <= 1e-9) { m.alive = false; m.health = 0; }
        if (full && knock) m.knockback(0.4, p.x - m.x, p.z - m.z);
        return taken;
    }

    static int critIn(double vy) {
        for (int j = 1; j <= 4; j++) {
            if (vy < 0) return j;
            vy = (vy - 0.08) * 0.98;
        }
        return -1;
    }

    /** Player.doSweepAttack's test, on the server's (true) positions. */
    static boolean swept(MoveState p, MobSim hit, MobSim o) {
        double hw = hit.width / 2 + 1.0, ow = o.width / 2;
        boolean meets = hit.x - hw < o.x + ow && hit.x + hw > o.x - ow && hit.z - hw < o.z + ow && hit.z + hw > o.z - ow
            && hit.y - 0.25 < o.y + o.height && hit.y + hit.height + 0.25 > o.y;
        double dx = o.x - p.x, dy = o.y - p.y, dz = o.z - p.z;
        return meets && dx * dx + dy * dy + dz * dz < 9.0;
    }

    /** Ticks until the mob's attack reaches the player, from its distance and how fast the two closed in the last
     *  tick (KillAura.contact; pdx, pdz the player's last move). */
    static double contact(MoveState p, double pdx, double pdz, MobSim m) {
        double[] at = m.seen(), step = m.seenStep();
        if (at[1] >= p.y + 1.8 || at[1] + m.height <= p.y) return Double.POSITIVE_INFINITY;
        double half = (0.6 + m.width) / 2 + MobSim.REACH;
        double gap = Math.max(Math.abs(at[0] - p.x), Math.abs(at[2] - p.z)) - half;
        if (gap <= 0) return 0.0;
        double dx = at[0] - p.x, dz = at[2] - p.z, d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-6) return 0.0;
        double vx = step[0] - pdx, vz = step[1] - pdz;
        double closing = -(vx * dx + vz * dz) / d;
        return closing > 1e-3 ? gap / closing : Double.POSITIVE_INFINITY;
    }

    static double eyeToBox(double ex, double ey, double ez, MobSim m, double[] at) {
        double hw = m.width / 2;
        double dx = Math.max(Math.max(at[0] - hw - ex, 0.0), ex - (at[0] + hw));
        double dy = Math.max(Math.max(at[1] - ey, 0.0), ey - (at[1] + m.height));
        double dz = Math.max(Math.max(at[2] - hw - ez, 0.0), ez - (at[2] + hw));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double d2(double ex, double ey, double ez, MobSim m) {
        double[] at = m.seen();
        double dx = at[0] - ex, dy = at[1] + m.height / 2 - ey, dz = at[2] - ez;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Minecraft yaw of the direction from (x0, z0) to (x1, z1): 0 = +z, 90 = -x. */
    public static double yawTo(double x0, double z0, double x1, double z1) {
        return Math.toDegrees(Math.atan2(-(x1 - x0), z1 - z0));
    }
}
