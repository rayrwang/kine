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
        String set = System.getenv("KINE_SPIRAL_SET");
        if (set != null) {
            // what-if edits of the map (bench tool): "x,y,z=state;..." (a state as the scan writes it, e.g. minecraft:air),
            // for asking whether a leg has a way once a mechanism's blocks are where it puts them (a piston door open)
            for (String part : set.split(";")) {
                if (part.isBlank()) continue;
                String[] kv = part.split("=", 2), p = kv[0].split(",");
                int x = Integer.parseInt(p[0].trim()), y = Integer.parseInt(p[1].trim()), z = Integer.parseInt(p[2].trim());
                try {
                    m.cells[idx(x, y, z)] = Block.getId(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, kv[1].trim(), false).blockState());
                } catch (Exception e) {
                    throw new IllegalArgumentException("KINE_SPIRAL_SET: " + part, e);
                }
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

    /** Water as the live game has it (KINE_SPIRAL_WATER, a bench option): offline the game's tags aren't bound, so
     *  McKinds (FluidTags.WATER) and WorldSnapshot (the fluid by FluidTags) took every water block for air: no
     *  Kind.WATER in the table (a lake's floor was its bed, with no swimming floor on top) and no fluid in the copy. With
     *  it, a block whose fluid is water (by the fluid's type, which needs no tags) gets the table's water flag and the
     *  copy's fluid, its height and current, as WalkTable and WorldSnapshot.blocks have them live. */
    static final boolean WATER = System.getenv("KINE_SPIRAL_WATER") != null;
    private static final java.util.Map<Integer, Integer> WATER_KINDS = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean water(BlockState st) {
        var fs = st.getFluidState();
        return !fs.isEmpty() && fs.getType().isSame(net.minecraft.world.level.material.Fluids.WATER);
    }

    /** The copy's block info of a water block with its fluid, as WorldSnapshot.info has it where the tags are bound. */
    static BlockInfo withWater(BlockInfo bi, BlockState st) {
        if (bi.fluid != BlockInfo.NO_FLUID) return bi;
        var fs = st.getFluidState();
        return new BlockInfo(bi.shape, bi.friction, bi.speedFactor, bi.jumpFactor, bi.flags | BlockInfo.FLUID | (fs.isSource() ? 0 : BlockInfo.CURRENT),
            BlockInfo.WATER_FLUID, fs.getOwnHeight(), bi.fallFactor, bi.bounce);
    }

    /** The table kind of water block st (McKinds' kind id `id`) with the water flag and the fluid in its info. */
    static int waterKind(int id, BlockState st) {
        return WATER_KINDS.computeIfAbsent(id, i -> {
            Kind k = Palette.get(i);
            if (k.is(Kind.WATER)) return i;
            return Palette.add(k.name + "[water offline]", withWater(k.info, st), java.util.Arrays.asList(k.boxes), k.flags | Kind.WATER,
                k.drawnTop).id;
        });
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
                            if (WATER && water(st)) out[y - Y0] = waterKind(out[y - Y0], st);
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
                    if (WATER && water(st)) bi = withWater(bi, st);
                    // under 26.2's rules (KINE_RULES=26.2) on the development release's game classes, whose
                    // Block.getBounceRestitution doesn't exist (the epoch code gives 0): 26.2's values, slime 1, beds 0.75
                    if (ai.rrw.kine.compat.Rules.BOUNCE_RESTITUTION && bi.bounce == 0.0F
                        && (bi.is(BlockInfo.SLIME) || bi.is(BlockInfo.BED)))
                        bi = new BlockInfo(bi.shape, bi.friction, bi.speedFactor, bi.jumpFactor, bi.flags, bi.fluid, bi.fluidHeight,
                            bi.fallFactor, bi.is(BlockInfo.SLIME) ? 1.0F : 0.75F);
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
