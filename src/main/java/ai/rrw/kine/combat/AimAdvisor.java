package ai.rrw.kine.combat;

import ai.rrw.kine.combat.HitChance.Box;
import ai.rrw.kine.combat.HitChance.Shot;
import ai.rrw.kine.combat.HitChance.Target;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs HitChance off the game thread for the projectile reticle: the chance that the shot as aimed now hits the
 * target, and, while auto aim holds a target, the point on it with the best chance. Each tick the game thread
 * gathers the inputs (the shot, the target, the block boxes near the arrow's path) and hands them to whichever
 * worker is free; the reticle and the aim read the latest result. The search aims each candidate point with
 * Ballistics.solveAim, the auto aim's own solve. Arrow and trident physics only.
 */
public final class AimAdvisor {
    private AimAdvisor() {}

    private static final int ROLLS = 4096;
    private static final int STALE_TICKS = 20;          // older results are neither shown nor aimed at
    private static final int MAX_FLIGHT_TICKS = 200;
    private static final int MAX_BLOCK_READS = 60_000;  // block positions read per hand-over, at most

    private record ChanceResult(int targetId, double chance, long gameTime) {}
    private record BestResult(int targetId, double up, double side, long gameTime) {}

    private static volatile ChanceResult lastChance = null;
    private static volatile BestResult lastBest = null;
    private static final AtomicBoolean chanceBusy = new AtomicBoolean(), bestBusy = new AtomicBoolean();
    private static final ExecutorService chanceWorker = worker("kine-hit-chance");
    private static final ExecutorService bestWorker = worker("kine-best-aim");

    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Called each client tick with the shot as aimed now: dir is the launch direction before the spread, and
     * shooterMotion the shooter's per-tick motion as the aim uses it (zero for a weapon that doesn't pass it
     * on). Starts the chance job, and the best-aim job if wanted, when their workers are free. Every weapon
     * here shoots with uncertainty 1.
     */
    public static void update(ClientLevel level, Vec3 spawn, Vec3 dir, Ballistics.Spec spec, Vec3 shooterMotion, boolean onGround,
                              Entity target, boolean wantBest) {
        boolean doChance = !chanceBusy.get(), doBest = wantBest && !bestBusy.get();
        if (!doChance && !doBest) return;

        Shot shot = new Shot(spawn.x, spawn.y, spawn.z, dir.x, dir.y, dir.z, spec.speed(), 1.0,
                             shooterMotion.x, onGround ? 0.0 : shooterMotion.y, shooterMotion.z);
        AABB bb = target.getBoundingBox();
        Entity mover = target instanceof EnderDragonPart part ? part.parentMob : target;
        Vec3 targetMotion = new Vec3(mover.getX() - mover.xOld, mover.getY() - mover.yOld, mover.getZ() - mover.zOld);
        Target tgt = new Target(new Box(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ),
                                targetMotion.x, targetMotion.y, targetMotion.z);
        HitChance.Aimer aimer = (px, py, pz) -> {
            Vec3 d = Ballistics.solveAim(spawn, new Vec3(px, py, pz), targetMotion, spec, shooterMotion, onGround)
                .subtract(spawn).normalize();
            return new double[]{d.x, d.y, d.z};
        };
        int maxTicks = ticksPast(shot, tgt);
        List<List<Box>> perTick = blocksAlong(level, shot, maxTicks);
        HitChance.Blocks blocks = age -> age < perTick.size() ? perTick.get(age) : List.of();
        int id = target.getId();
        long now = level.getGameTime();

        if (doChance && chanceBusy.compareAndSet(false, true)) {
            chanceWorker.execute(() -> {
                try {
                    lastChance = new ChanceResult(id, HitChance.chance(shot, tgt, blocks, ROLLS, maxTicks), now);
                } catch (RuntimeException e) {
                    lastChance = null;
                } finally {
                    chanceBusy.set(false);
                }
            });
        }
        if (doBest && bestBusy.compareAndSet(false, true)) {
            bestWorker.execute(() -> {
                try {
                    HitChance.Aim a = HitChance.bestAim(shot, tgt, blocks, maxTicks, aimer);
                    lastBest = a == null ? null : new BestResult(id, a.up(), a.side(), now);
                } catch (RuntimeException e) {
                    lastBest = null;
                } finally {
                    bestBusy.set(false);
                }
            });
        }
    }

    /** The latest hit chance for this target, or NaN if there is none from the last second. */
    public static double chance(int targetId, long gameTime) {
        ChanceResult r = lastChance;
        return r != null && r.targetId() == targetId && gameTime - r.gameTime() <= STALE_TICKS ? r.chance() : Double.NaN;
    }

    /**
     * Where to aim on this target, as an offset from its box centre: up, and sideways as seen from 'from'.
     * Zero when there is no result for it from the last second.
     */
    public static Vec3 aimOffset(Entity target, Vec3 from, long gameTime) {
        BestResult r = lastBest;
        if (r == null || r.targetId() != target.getId() || gameTime - r.gameTime() > STALE_TICKS) return Vec3.ZERO;
        Vec3 c = target.getBoundingBox().getCenter();
        double bx = c.x - from.x, bz = c.z - from.z, d = Math.sqrt(bx * bx + bz * bz);
        if (d < 1e-6) return Vec3.ZERO;
        return new Vec3(-bz / d * r.side(), r.up(), bx / d * r.side());
    }

    /** Ticks until the no-spread path is a few blocks past the target's distance (allowing for its motion). */
    private static int ticksPast(Shot s, Target t) {
        double len = Math.sqrt(s.aimX() * s.aimX() + s.aimY() * s.aimY() + s.aimZ() * s.aimZ());
        double vx = s.aimX() / len * s.speed() + s.inheritX(), vz = s.aimZ() / len * s.speed() + s.inheritZ();
        double vh = Math.sqrt(vx * vx + vz * vz), h = 0;
        double tx = t.box().centerX() - s.x(), tz = t.box().centerZ() - s.z();
        double target = Math.sqrt(tx * tx + tz * tz) + 3, drift = Math.hypot(t.vx(), t.vz());
        for (int i = 1; i <= MAX_FLIGHT_TICKS; i++) {
            h += vh;
            vh *= HitChance.DRAG;
            if (h > target + drift * i) return Math.min(MAX_FLIGHT_TICKS, i + 2);
        }
        return MAX_FLIGHT_TICKS;
    }

    /**
     * For each tick of the flight (index = the projectile's age, 1 on its first tick), the collision boxes of the
     * blocks it can meet then: those around that tick's no-spread move, widened by how far the spread can carry
     * the projectile by then (all three axes, so along the path too) plus the hit margin and a little slack.
     */
    private static List<List<Box>> blocksAlong(ClientLevel level, Shot s, int maxTicks) {
        double len = Math.sqrt(s.aimX() * s.aimX() + s.aimY() * s.aimY() + s.aimZ() * s.aimZ());
        double x = s.x(), y = s.y(), z = s.z();
        double vx = s.aimX() / len * s.speed() + s.inheritX();
        double vy = s.aimY() / len * s.speed() + s.inheritY();
        double vz = s.aimZ() / len * s.speed() + s.inheritZ();
        double reach = HitChance.SPREAD * s.uncertainty() * Math.sqrt(3);
        double travelled = 0;
        int reads = 0;

        Map<Long, List<Box>> shapes = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<List<Box>> out = new ArrayList<>(maxTicks + 1);
        out.add(List.of());
        for (int age = 1; age <= maxTicks; age++) {
            double nx = x + vx, ny = y + vy, nz = z + vz;
            travelled += Math.sqrt(vx * vx + vy * vy + vz * vz);
            double r = reach * travelled + 0.8;
            int x0 = (int) Math.floor(Math.min(x, nx) - r), x1 = (int) Math.floor(Math.max(x, nx) + r);
            int y0 = (int) Math.floor(Math.min(y, ny) - r), y1 = (int) Math.floor(Math.max(y, ny) + r);
            int z0 = (int) Math.floor(Math.min(z, nz) - r), z1 = (int) Math.floor(Math.max(z, nz) + r);
            List<Box> near = new ArrayList<>();
            for (int bx = x0; bx <= x1 && reads < MAX_BLOCK_READS; bx++)
                for (int by = y0; by <= y1; by++)
                    for (int bz = z0; bz <= z1; bz++) {
                        long key = BlockPos.asLong(bx, by, bz);
                        List<Box> sh = shapes.get(key);
                        if (sh == null) {
                            reads++;
                            pos.set(bx, by, bz);
                            VoxelShape v = level.getBlockState(pos).getCollisionShape(level, pos);
                            if (v.isEmpty()) sh = List.of();
                            else {
                                sh = new ArrayList<>();
                                for (AABB a : v.toAabbs())
                                    sh.add(new Box(a.minX + bx, a.minY + by, a.minZ + bz, a.maxX + bx, a.maxY + by, a.maxZ + bz));
                            }
                            shapes.put(key, sh);
                        }
                        near.addAll(sh);
                    }
            out.add(near);
            x = nx; y = ny; z = nz;
            vx *= HitChance.DRAG; vy *= HitChance.DRAG; vz *= HitChance.DRAG;
            vy -= HitChance.GRAVITY;
        }
        return out;
    }
}
