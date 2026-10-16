package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.WorldSnapshot;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The movement copy's blocks for the loose moves' checks (LooseCheck.Grids), read from the live world on the game's
 * thread: a check on its own thread asks for the box round a take-off and waits; the walk's end of tick (Walk.endTick)
 * serves a few each tick (WorldSnapshot.blocks, as layer 2's copy round the player is made), so the world is never read
 * off the game's thread.
 */
final class GridRequests {
    private GridRequests() {}

    private record Req(int x0, int y0, int z0, int nx, int ny, int nz, CompletableFuture<BlockGrid> f) {}

    private static final ConcurrentLinkedQueue<Req> QUEUE = new ConcurrentLinkedQueue<>();
    /** Served a tick at most (each some 16 thousand blocks). */
    static final int PER_TICK = 2;
    /** How long a check waits for its blocks before it gives up (the game paused or closing). */
    static final long WAIT_MS = 5000;

    /** The blocks within r of (x, z) and from down below to up above y, read on the game's thread; null if it can't
     *  (no world, the wait ran out). */
    static BlockGrid around(int x, int y, int z, int r, int down, int up) throws InterruptedException {
        CompletableFuture<BlockGrid> f = new CompletableFuture<>();
        QUEUE.add(new Req(x - r, y - down, z - r, 2 * r + 1, down + up + 1, 2 * r + 1, f));
        try {
            return f.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            f.cancel(false);
            return null;
        }
    }

    /** The game's thread, each tick: serve up to PER_TICK requests (none waiting: nothing). */
    static void serve(Minecraft mc) {
        for (int i = 0; i < PER_TICK; i++) {
            Req q = QUEUE.poll();
            if (q == null) return;
            if (q.f().isDone()) { i--; continue; }
            if (mc.level == null || mc.player == null) { q.f().complete(null); continue; }
            q.f().complete(WorldSnapshot.blocks(mc.level, mc.player, q.x0(), q.y0(), q.z0(), q.nx(), q.ny(), q.nz()));
        }
    }
}
