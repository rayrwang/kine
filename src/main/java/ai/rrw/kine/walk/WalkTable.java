package ai.rrw.kine.walk;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The live walkability table: a {@link ChunkFloors} for every loaded chunk of the client's own world. A chunk is
 * (re)built when it loads and when a block in it changes: the client thread copies the chunk's block sections (a
 * memory copy) and a worker thread reads the copies into floors, so the game's thread does almost nothing; the result
 * replaces the chunk's old entry. Columns depend only on their own blocks, so a change rebuilds its own chunk only.
 * Unloaded chunks are dropped; a new world or dimension clears it all. Built only while the pathfinder is on.
 */
public final class WalkTable {
    private WalkTable() {}

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kine-walk-table");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final ConcurrentHashMap<Long, ChunkFloors> CHUNKS = new ConcurrentHashMap<>();
    /** Entries of chunks that unloaded, kept (up to a few thousand) so a chunk that loads again keeps its columns'
     *  stamps when nothing in it changed. The start of a saved copy of chunks seen (planning beyond what's loaded). */
    private static final ConcurrentHashMap<Long, ChunkFloors> SEEN = new ConcurrentHashMap<>();
    private static final int SEEN_MAX = 4096;
    /** Chunks loaded in the client's world (client thread only). */
    private static final LongOpenHashSet LOADED = new LongOpenHashSet();
    /** Chunks to (re)build, nearest the player first each tick (client thread only). */
    private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
    /** Bumped on a world change: builds started before it are dropped. */
    private static final AtomicLong EPOCH = new AtomicLong();
    private static ClientLevel level = null;

    // measured, for tests and the report
    public static volatile long builds = 0, buildNanos = 0, copyNanos = 0, maxCopyNanos = 0;
    public static volatile int pending = 0;
    /** Chunk loads and block changes seen, and the last few changes (x y z), for tests. */
    public static long loads = 0, blockChanges = 0;
    public static final java.util.ArrayDeque<String> recentChanges = new java.util.ArrayDeque<>();
    /** Chunks handed to the worker and not yet built. */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Boolean> IN_FLIGHT = new java.util.concurrent.ConcurrentHashMap<>();

    /** How many chunks' sections the client thread copies per tick at most, and for how long (the rest wait). */
    private static final int COPIES_PER_TICK = 24;
    private static final long COPY_BUDGET_NANOS = 2_000_000L;

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register((lvl, chunk) -> {
            if (lvl != level) reset(lvl);
            long k = View.key(chunk.getPos().x(), chunk.getPos().z());
            LOADED.add(k);
            DIRTY.add(k);
            loads++;
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((lvl, chunk) -> {
            if (lvl != level) return;
            long k = View.key(chunk.getPos().x(), chunk.getPos().z());
            LOADED.remove(k);
            DIRTY.remove(k);
            ChunkFloors gone = CHUNKS.remove(k);
            if (gone != null) {
                if (SEEN.size() >= SEEN_MAX) SEEN.clear();
                SEEN.put(k, gone);
            }
        });
    }

    /** A block in the client's world changed (ClientLevelBlockMixin, from Level.setBlock). */
    public static void blockChanged(ClientLevel lvl, int x, int y, int z) {
        if (lvl != level) return;
        blockChanges++;
        if (recentChanges.size() >= 8) recentChanges.pollFirst();
        recentChanges.addLast(x + " " + y + " " + z);
        long k = View.key(x >> 4, z >> 4);
        if (LOADED.contains(k)) DIRTY.add(k);
    }

    private static void reset(ClientLevel lvl) {
        level = lvl;
        EPOCH.incrementAndGet();
        IN_FLIGHT.clear();
        SEEN.clear();
        LOADED.clear();
        DIRTY.clear();
        CHUNKS.clear();
    }

    /** End of a client tick: copy the sections of the dirty chunks nearest the player and hand them to the worker. */
    public static void tick(Minecraft mc) {
        if (mc.level != level) { if (mc.level == null) { reset(null); return; } reset(mc.level); }
        if (!Settings.walkPathfinder || mc.player == null || DIRTY.isEmpty()) { pending = DIRTY.size() + IN_FLIGHT.size(); return; }
        int px = mc.player.getBlockX() >> 4, pz = mc.player.getBlockZ() >> 4;
        long t0 = System.nanoTime();
        for (int n = 0; n < COPIES_PER_TICK && !DIRTY.isEmpty() && System.nanoTime() - t0 < COPY_BUDGET_NANOS; n++) {
            // nearest first: a full scan of the set is cheap next to the copies
            long best = 0;
            long bestD = Long.MAX_VALUE;
            for (long k : DIRTY) {
                long dx = (int) (k >> 32) - px, dz = (int) k - pz;
                long d = dx * dx + dz * dz;
                if (d < bestD) { bestD = d; best = k; }
            }
            DIRTY.remove(best);
            submit(level, best);
        }
        pending = DIRTY.size() + IN_FLIGHT.size();
    }

    private static void submit(ClientLevel lvl, long k) {
        int cx = (int) (k >> 32), cz = (int) k;
        LevelChunk chunk = lvl.getChunkSource().getChunk(cx, cz, false);
        if (chunk == null) return;
        long t0 = System.nanoTime();
        LevelChunkSection[] sections = chunk.getSections();
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] copies = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++)
            if (sections[i] != null && !sections[i].hasOnlyAir()) copies[i] = sections[i].getStates().copy();
        int minY = lvl.getMinY();
        long took = System.nanoTime() - t0;
        copyNanos += took;
        maxCopyNanos = Math.max(maxCopyNanos, took);
        long epoch = EPOCH.get();
        IN_FLIGHT.put(k, Boolean.TRUE);
        WORKER.submit(() -> {
            try {
                long b0 = System.nanoTime();
                ChunkFloors cf = ColumnScan.chunk(cx, cz, new Sections(copies, minY));
                ChunkFloors before = CHUNKS.get(k);
                cf.stampColumnsFrom(before != null ? before : SEEN.get(k));
                if (EPOCH.get() == epoch) CHUNKS.put(k, cf);
                builds++;
                buildNanos += System.nanoTime() - b0;
            } catch (Exception e) {
                Kine.LOGGER.warn("Kine walk table: chunk " + cx + "," + cz + " failed", e);
            } finally {
                IN_FLIGHT.remove(k);
            }
        });
    }

    /** A snapshot for one search (client thread or any). */
    public static View snapshot() {
        Long2ObjectOpenHashMap<ChunkFloors> m = new Long2ObjectOpenHashMap<>(CHUNKS.size() * 2);
        CHUNKS.forEach(m::put);
        return new View(m);
    }

    /** Whether the chunks within r chunks of (x, z) are all built. */
    public static boolean builtAround(int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                long k = View.key((x >> 4) + dx, (z >> 4) + dz);
                if (LOADED.contains(k) && !CHUNKS.containsKey(k)) return false;
            }
        return true;
    }

    /** Whether the table is up to date within r chunks of (x, z): built, and nothing changed there waiting to be rebuilt. */
    public static boolean settled(int x, int z, int r) {
        if (!builtAround(x, z, r)) return false;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                long k = View.key((x >> 4) + dx, (z >> 4) + dz);
                if (DIRTY.contains(k) || IN_FLIGHT.containsKey(k)) return false;
            }
        return true;
    }

    /** The stamp of column (x, z)'s last change, or -1 if its chunk has no entry. */
    public static long columnStamp(int x, int z) {
        ChunkFloors c = CHUNKS.get(View.key(x >> 4, z >> 4));
        return c == null ? -1 : c.columnStamp(x & 15, z & 15);
    }

    public static int chunkCount() {
        return CHUNKS.size();
    }

    public static long bytes() {
        long b = 0;
        for (ChunkFloors c : CHUNKS.values()) b += c.bytes();
        return b;
    }

    /** The kinds of copied sections, bottom up, for ColumnScan. */
    private record Sections(PalettedContainer<BlockState>[] copies, int minY) implements ColumnScan.Source {
        public int minY() { return minY; }
        public int maxY() { return minY + copies.length * 16; }
        public void column(int lx, int lz, int[] ids) {
            int air = Palette.AIR.id;
            for (int s = 0; s < copies.length; s++) {
                PalettedContainer<BlockState> c = copies[s];
                int base = s * 16;
                if (c == null) { java.util.Arrays.fill(ids, base, base + 16, air); continue; }
                for (int y = 0; y < 16; y++) ids[base + y] = McKinds.id(c.get(lx, y, lz));
            }
        }
    }
}
