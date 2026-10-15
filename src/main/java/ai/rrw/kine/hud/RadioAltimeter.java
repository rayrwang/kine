package ai.rrw.kine.hud;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.Gui2D;
import ai.rrw.kine.Settings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.IntToDoubleFunction;

public class RadioAltimeter {

    private static int agl = -1;                  // blocks above terrain; -1 = unknown

    public static int agl() { return agl; }

    public static void register() {
        Compat.hud("radalt", RadioAltimeter::render);
        ClientTickEvents.END_CLIENT_TICK.register(RadioAltimeter::tick);
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { agl = -1; return; }

        int x = p.getBlockX(), z = p.getBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        agl = agl(p.getY(), Compat.minY(mc.level), y -> {
            pos.set(x, y, z);
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir()) return Double.NaN;
            VoxelShape shape = state.getCollisionShape(mc.level, pos);
            return shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
        });
    }

    /** Whole blocks from the feet (at height y) down to the ground: the first block that isn't air, scanning from
     *  the block the feet are in (a slab or farmland underfoot is that block) down to the world floor minY. The
     *  ground's surface is the top of that block's collision shape (half way up a slab), or the whole block for
     *  one without (water, grass). topAt gives a block's top above its base, NaN for air. -1 when the column is
     *  empty (the void): unknown, hidden. */
    static int agl(double y, int minY, IntToDoubleFunction topAt) {
        for (int by = (int) Math.floor(y); by >= minY; by--) {
            double top = topAt.applyAsDouble(by);
            if (!Double.isNaN(top)) return Math.max(0, (int) Math.round(y - (by + top)));
        }
        return -1;
    }

    private static void render(Gui2D g, DeltaTracker delta) {
        if (agl < 0 || !Settings.shows(Settings.displayHeightAboveGround)) return;
        Minecraft mc = Minecraft.getInstance();

        String text = "" + agl;
        int w = mc.font.width(text);
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int x = W / 2 - w / 2;
        int y = HudLayout.radioAltimeterRow(H);
        g.text(mc.font, text, x, y, HudColors.AMBER, true);
    }
}
