package ai.rrw.kine.autoflight;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.WorldSnapshot;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The elytra guardian's copy of the blocks ahead: every loaded chunk within {@link #RADIUS} blocks of the player, as
 * copies of its block sections (a memory copy each, made on the game's thread, as the walk table makes them), kept
 * while the player wears or flies an elytra. A chunk is copied when it comes in range and again when a block in it
 * changes; the nearest first, within a time budget a tick. {@link #snapshot} gives the worker a {@link BlockGrid} over
 * the copies: the movement copy reads real block shapes (the collision shape for no one in particular; a block whose
 * shape moves with its position, bamboo or dripstone, counts as a full block), and a chunk not loaded or not copied
 * yet reads as air (unseen is not a collision, as terrain avoidance treats it).
 */
public final class GuardWorld {
    private GuardWorld() {}

    /** Blocks around the player kept copied (the do-nothing path looks about 160 ticks ahead). */
    public static final int RADIUS = 160;
    private static final int CHUNKS = RADIUS / 16 + 1;           // chunks either side of the player's
    private static final long BUDGET_NANOS = 1_500_000L;         // copying per tick at most

    private record Chunk(PalettedContainer<BlockState>[] sections) {}

    private static final Long2ObjectOpenHashMap<Chunk> COPIES = new Long2ObjectOpenHashMap<>();
    private static final LongOpenHashSet DIRTY = new LongOpenHashSet();
    private static ClientLevel level = null;

    // measured
    public static long copied = 0, copyNanos = 0, maxTickNanos = 0;

    static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    /** A block changed in the client's world (ClientLevelBlockMixin): its chunk is copied again. */
    public static void blockChanged(ClientLevel lvl, int x, int y, int z) {
        if (lvl != level) return;
        long k = key(x >> 4, z >> 4);
        if (COPIES.containsKey(k)) DIRTY.add(k);
    }

    /** A chunk unloaded: its copy goes (ClientChunkEvents). */
    public static void chunkUnloaded(ClientLevel lvl, int cx, int cz) {
        if (lvl != level) return;
        COPIES.remove(key(cx, cz));
        DIRTY.remove(key(cx, cz));
    }

    public static void clear() {
        COPIES.clear(); DIRTY.clear(); level = null;
    }

    public static int size() { return COPIES.size(); }

    /** End of a tick while the guardian may be needed: copy what is missing or changed near the player, nearest first,
     *  and drop what is far. */
    public static void tick(Minecraft mc, LocalPlayer p) {
        tick(mc, p, BUDGET_NANOS);
    }

    /** The same with another time budget (Long.MAX_VALUE: everything in range now, for Guardian.probe). */
    static void tick(Minecraft mc, LocalPlayer p, long budgetNanos) {
        long t0 = System.nanoTime();
        if (mc.level != level) { COPIES.clear(); DIRTY.clear(); level = mc.level; }
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
        // drop the far ones
        COPIES.keySet().removeIf((long k) -> {
            int cx = (int) (k >> 32), cz = (int) k;
            return Math.abs(cx - pcx) > CHUNKS + 2 || Math.abs(cz - pcz) > CHUNKS + 2;
        });
        // ring by ring outward: the missing and the changed
        for (int r = 0; r <= CHUNKS; r++) {
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    if (System.nanoTime() - t0 > budgetNanos) { note(t0); return; }
                    int cx = pcx + dx, cz = pcz + dz;
                    long k = key(cx, cz);
                    if (COPIES.containsKey(k) && !DIRTY.contains(k)) continue;
                    LevelChunk c = level.getChunkSource().getChunk(cx, cz, false);
                    if (c == null) continue;
                    COPIES.put(k, copy(c));
                    DIRTY.remove(k);
                    copied++;
                }
        }
        note(t0);
    }

    private static void note(long t0) {
        long took = System.nanoTime() - t0;
        copyNanos += took;
        maxTickNanos = Math.max(maxTickNanos, took);
    }

    @SuppressWarnings("unchecked")
    private static Chunk copy(LevelChunk c) {
        LevelChunkSection[] sections = c.getSections();
        PalettedContainer<BlockState>[] copies = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++)
            if (sections[i] != null && !sections[i].hasOnlyAir()) copies[i] = sections[i].getStates().copy();
        return new Chunk(copies);
    }

    /** The copies now, as a grid the worker can read while the game goes on (the copies are never written again). */
    public static Grid snapshot(LocalPlayer p, int minY) {
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4, n = 2 * CHUNKS + 1;
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[][] w = new PalettedContainer[n * n][];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                Chunk c = COPIES.get(key(pcx - CHUNKS + i, pcz - CHUNKS + j));
                if (c != null) w[i * n + j] = c.sections();
            }
        return new Grid(pcx - CHUNKS, pcz - CHUNKS, n, minY, w);
    }

    /** Block kinds seen, shared by all snapshots (BlockInfo per state: its shape for no one in particular). */
    private static final ConcurrentHashMap<BlockState, BlockInfo> INFOS = new ConcurrentHashMap<>();

    static BlockInfo info(BlockState st) {
        BlockInfo b = INFOS.get(st);
        if (b != null) return b;
        VoxelShape vs;
        try {
            vs = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
            if (st.hasOffsetFunction() && !vs.isEmpty()) vs = Shapes.block();
        } catch (RuntimeException e) {
            vs = Shapes.block();
        }
        b = WorldSnapshot.info(st, vs);
        INFOS.put(st, b);
        return b;
    }

    /** The movement copy's view of the copies. */
    public static final class Grid extends BlockGrid {
        private final int cx0, cz0, n, minY;
        private final PalettedContainer<BlockState>[][] w;
        private final Map<BlockState, BlockInfo> local = new IdentityHashMap<>();

        Grid(int cx0, int cz0, int n, int minY, PalettedContainer<BlockState>[][] w) {
            super(0, 0, 0, 0, 0, 0);
            this.cx0 = cx0; this.cz0 = cz0; this.n = n; this.minY = minY; this.w = w;
        }

        public int minY() { return minY; }

        @Override
        public BlockInfo get(int x, int y, int z) {
            int i = (x >> 4) - cx0, j = (z >> 4) - cz0;
            if (i < 0 || j < 0 || i >= n || j >= n) return BlockInfo.AIR;
            PalettedContainer<BlockState>[] secs = w[i * n + j];
            if (secs == null) return BlockInfo.AIR;
            int s = (y - minY) >> 4;
            if (s < 0 || s >= secs.length || secs[s] == null) return BlockInfo.AIR;
            BlockState st = secs[s].get(x & 15, y & 15, z & 15);
            if (st.isAir()) return BlockInfo.AIR;
            BlockInfo b = local.get(st);
            if (b == null) { b = info(st); local.put(st, b); }
            return b;
        }
    }
}
