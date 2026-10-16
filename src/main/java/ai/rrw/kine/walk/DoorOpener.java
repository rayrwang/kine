package ai.rrw.kine.walk;

import ai.rrw.kine.Settings;
import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.mixin.MinecraftInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Opening a closed wooden door on the walk's route (Settings.walkOpenDoors; the spiral agent, 2026-10-15: the Parkour
 * Spiral's level through a house, whose two oak doors are the only way on). The route goes through a door by an OPEN move
 * (Graph). Within reach of a door still closed on the route ahead, the walk stops, the rotation sent turns to the door's
 * middle (through the kill aura's turn, at most 30 degrees a tick, the camera yours), and once the game's own crosshair
 * pick with that rotation is on the door, the game's own right click (Minecraft.startUseItem, just before handleKeybinds,
 * as a vanilla client's use) opens it: a use packet a precisely aimed vanilla client sends. The door's opening is a block
 * change, the route is planned again through the open door. A door that doesn't open after a few clicks (powered shut,
 * a server that refuses) has its move blocked for the walk and the route planned round it.
 */
public final class DoorOpener {
    private DoorOpener() {}

    /** The door being opened (its lower half), or null. */
    private static BlockPos door = null;
    /** The move through it (Loose.edge), the ticks spent on it, the clicks made, the tick of the last click. */
    private static long move = 0;
    private static int ticks = 0, clicks = 0, lastClick = -100, tick = 0;
    /** How near the door (eyes to its middle, blocks) the walk stops to open it: a vanilla reach (4.5) with room. */
    static final double REACH = 3.5;
    /** Ticks at a door before it is given up; clicks at most. */
    static final int GIVE_UP = 80, MAX_CLICKS = 3;
    public static int opened = 0, givenUp = 0;

    /** Whether a door is being opened this tick (the walk stands and the rotation goes to the door). */
    static boolean active() {
        return door != null;
    }

    static void reset() {
        door = null;
        move = 0;
        ticks = clicks = 0;
        lastClick = -100;
    }

    /** The move given up on (a door that didn't open), once; 0 if none. */
    private static long gaveUp = 0;

    static long takeGivenUp() {
        long m = gaveUp;
        gaveUp = 0;
        return m;
    }

    /**
     * At the walk's end of tick: the door to open now, if the route's next OPEN move is near and its door still closed;
     * the door's state read from the world. Called while walking a route.
     */
    static void update(Minecraft mc, LocalPlayer p, Planner.Plan plan) {
        tick++;
        if (!Settings.walkOpenDoors || plan == null || plan.direct || mc.level == null) { reset(); return; }
        if (door != null) {
            BlockState st = mc.level.getBlockState(door);
            if (!isClosedWoodenDoor(st)) { opened++; reset(); return; }   // open now (or gone): on through it
            if (++ticks > GIVE_UP || clicks >= MAX_CLICKS && tick - lastClick > 20) {
                gaveUp = move;
                givenUp++;
                reset();
            }
            return;
        }
        PathSearch.Result r = plan.result;
        for (int i = 1; i < r.path.length; i++) {
            if (r.moves[i] != Graph.OPEN) continue;
            for (int k = i - 1; k <= i; k++) {
                int x = View.nodeX(r.path[k]), z = View.nodeZ(r.path[k]);
                int y = (int) Math.floor(r.pathY[k] + 1e-3);
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = new BlockPos(x, y + dy, z);
                    BlockState st = mc.level.getBlockState(pos);
                    if (!isClosedWoodenDoor(st)) continue;
                    BlockPos lower = st.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
                    double d = p.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(lower).add(0, 0.5, 0));
                    if (d > REACH) continue;
                    door = lower;
                    move = Loose.edge(r.path[i - 1], r.path[i]);
                    ticks = clicks = 0;
                    return;
                }
            }
        }
    }

    static boolean isClosedWoodenDoor(BlockState st) {
        return st.getBlock() instanceof DoorBlock && st.is(BlockTags.WOODEN_DOORS) && !st.getValue(DoorBlock.OPEN);
    }

    /** The rotation to send while opening: toward the middle of the door's height (its two halves' join), {yaw, pitch}. */
    static float[] aim(LocalPlayer p) {
        double tx = door.getX() + 0.5, ty = door.getY() + 1.0, tz = door.getZ() + 0.5;
        var eye = p.getEyePosition();
        double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return new float[]{yaw, pitch};
    }

    /** Just before handleKeybinds (after the kill aura's turn and the crosshair pick): the right click, when the game's
     *  pick with the rotation being sent is on the door. */
    public static void beforeKeybinds(Minecraft mc) {
        if (door == null || mc.player == null || mc.gameMode == null || Compat.screen(mc) != null) return;
        if (mc.player.isShiftKeyDown()) return;   // sneaking, a click would use the held item, not the door
        if (tick - lastClick < 5 || clicks >= MAX_CLICKS) return;
        // the game's own pick with the rotation as it is now (the kill aura re-picks only on a tick it turns; a pick left
        // from a tick before missed the door while the use went out: check.py R6 in the first map run with doors)
        Compat.pick(mc, 1.0F);
        HitResult h = mc.hitResult;
        if (!(h instanceof BlockHitResult b) || h.getType() != HitResult.Type.BLOCK) return;
        BlockPos hit = b.getBlockPos();
        if (!hit.equals(door) && !hit.equals(door.above())) return;
        MinecraftInvoker mi = (MinecraftInvoker) mc;
        if (mi.kine$getRightClickDelay() > 0) return;
        mi.invokeStartUseItem();
        clicks++;
        lastClick = tick;
    }
}
