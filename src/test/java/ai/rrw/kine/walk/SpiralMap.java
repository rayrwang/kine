package ai.rrw.kine.walk;

import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.WorldSnapshot;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A real map's blocks loaded offline for the walk's planners and the movement copy (the spiral agent, 2026-10-15):
 * Hielke's Parkour Spiral as scanned from its region files (the scan's tower.tsv: "x y z state" a line, the tower's
 * box from y 40), made into the walk table's chunks (ColumnScan over McKinds' kinds, as WalkTable builds them from the
 * live world) and into BlockGrids for the movement copy (WorldSnapshot.info, as WorldSnapshot.blocks reads them).
 * Local test data only (the map is not redistributed): the tests that use it are skipped without KINE_SPIRAL_TSV.
 */
final class SpiralMap {
    static final int X0 = -64, X1 = 63, Z0 = -128, Z1 = 63, Y0 = 40, Y1 = 272;
    static final int NX = X1 - X0 + 1, NY = Y1 - Y0, NZ = Z1 - Z0 + 1;

    /** Global block state id per cell (0: air). */
    final int[] cells = new int[NX * NY * NZ];
    int unparsed = 0;
    final List<String> unparsedNames = new ArrayList<>();

    private static boolean booted = false;

    static synchronized void boot() {
        if (booted) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        booted = true;
    }

    static int idx(int x, int y, int z) {
        return ((x - X0) * NY + (y - Y0)) * NZ + (z - Z0);
    }

    static boolean inside(int x, int y, int z) {
        return x >= X0 && x <= X1 && y >= Y0 && y < Y1 && z >= Z0 && z <= Z1;
    }

    BlockState state(int x, int y, int z) {
        if (!inside(x, y, z)) return Blocks.AIR.defaultBlockState();
        return Block.stateById(cells[idx(x, y, z)]);
    }

    static SpiralMap load(Path tsv) throws IOException {
        boot();
        SpiralMap m = new SpiralMap();
        Map<String, Integer> ids = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(tsv)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
                if (!inside(x, y, z)) continue;
                Integer id = ids.get(p[3]);
                if (id == null) {
                    int sid;
                    try {
                        sid = Block.getId(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, p[3], false).blockState());
                    } catch (Exception e) {
                        // a block or property this release doesn't have (the map is 26.2's): a full block in its place
                        sid = Block.getId(Blocks.STONE.defaultBlockState());
                        m.unparsed++;
                        if (m.unparsedNames.size() < 40) m.unparsedNames.add(p[3]);
                    }
                    ids.put(p[3], id = sid);
                }
                m.cells[idx(x, y, z)] = id;
            }
        }
        if (System.getenv("KINE_SPIRAL_SEA_LAVA") != null) {
            // the sea round the tower kills on this map (its datapack's drown rule): for exploring, lava instead, so the
            // searches don't count on swimming round to another level
            int lava = Block.getId(Blocks.LAVA.defaultBlockState());
            for (int x = X0; x <= X1; x++)
                for (int z = Z0; z <= Z1; z++)
                    for (int y = Y0; y <= 51; y++) {
                        BlockState st = Block.stateById(m.cells[idx(x, y, z)]);
                        if (st.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) m.cells[idx(x, y, z)] = lava;
                    }
        }
        return m;
    }

    /** The blocks of the game's climbable tag but scaffolding (offline the tags aren't bound, so st.is(BlockTags.CLIMBABLE)
     *  is false and the table had no ladders or vines: the bench's reach sets left them out). */
    private static final java.util.Set<String> CLIMBABLE = java.util.Set.of("ladder", "vine", "weeping_vines",
        "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "cave_vines", "cave_vines_plant");

    static boolean climbable(BlockState st) {
        return CLIMBABLE.contains(BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath());
    }

    /** The walk table's chunks for the whole box, as WalkTable builds them. */
    View view() {
        Long2ObjectOpenHashMap<ChunkFloors> chunks = new Long2ObjectOpenHashMap<>();
        for (int cx = X0 >> 4; cx <= X1 >> 4; cx++)
            for (int cz = Z0 >> 4; cz <= Z1 >> 4; cz++) {
                final int fcx = cx, fcz = cz;
                chunks.put(View.key(cx, cz), ColumnScan.chunk(cx, cz, new ColumnScan.Source() {
                    public int minY() { return Y0; }
                    public int maxY() { return Y1; }
                    public void column(int lx, int lz, int[] out) {
                        int x = fcx * 16 + lx, z = fcz * 16 + lz;
                        for (int y = Y0; y < Y1; y++) {
                            BlockState st = state(x, y, z);
                            out[y - Y0] = st.isAir() ? Palette.AIR.id : climbable(st) ? McKinds.climbableId(st)
                                : st.hasOffsetFunction() ? McKinds.idAt(st, x, y, z) : McKinds.id(st);
                        }
                    }
                }));
            }
        return new View(chunks);
    }

    /** The movement copy's blocks in a box (as WorldSnapshot.blocks reads the live world, for no one in particular). */
    BlockGrid grid(int x0, int y0, int z0, int nx, int ny, int nz) {
        BlockGrid g = new BlockGrid(x0, y0, z0, nx, ny, nz);
        for (int x = x0; x < x0 + nx; x++)
            for (int y = y0; y < y0 + ny; y++)
                for (int z = z0; z < z0 + nz; z++) {
                    BlockState st = state(x, y, z);
                    if (st.isAir()) continue;
                    // at its own position (bamboo's and pointed dripstone's shapes are offset by it, as in the live copy)
                    BlockInfo bi = WorldSnapshot.info(st, st.getCollisionShape(EmptyBlockGetter.INSTANCE, new BlockPos(x, y, z),
                        CollisionContext.empty()));
                    if (WorldSnapshot.trapdoorLadder(st, state(x, y - 1, z)) || climbable(st)) bi = WorldSnapshot.climbable(bi);
                    g.set(x, y, z, bi);
                }
        return g;
    }

    /** A checkpoint of the map's checkpoints.json: its number and where its plate's player stands. */
    record Cp(int n, int x, int y, int z) {}

    static List<Cp> checkpoints(Path json) throws IOException {
        List<Cp> out = new ArrayList<>();
        var a = com.google.gson.JsonParser.parseString(Files.readString(json)).getAsJsonArray();
        for (var e : a) {
            var o = e.getAsJsonObject();
            if (o.has("secret") && o.get("secret").getAsBoolean()) continue;   // off the spiral (an easter egg's)
            out.add(new Cp(o.get("n").getAsInt(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
        }
        return out;
    }
}
