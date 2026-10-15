package ai.rrw.kine.hud;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.Gui2D;
import ai.rrw.kine.Settings;
import ai.rrw.kine.util.KineTime;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class MiningHud {

    private static boolean active = false;    // currently breaking?
    private static double progress = 0.0;     // our tracked 0..1
    private static double ratePerTick = 0.0;  // current progress added per tick
    private static BlockPos target = null;    // block being broken

    public static void register() {
        Compat.hud("mining", MiningHud::render);
        ClientTickEvents.END_CLIENT_TICK.register(MiningHud::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null
                || !mc.gameMode.isDestroying()
                || mc.hitResult == null
                || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            active = false;
            progress = 0.0;
            target = null;
            return;
        }

        BlockPos pos = ((BlockHitResult) mc.hitResult).getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        double d = state.getDestroyProgress(mc.player, mc.level, pos);

        if (!pos.equals(target)) {   // a new block: start over
            target = pos;
            progress = 0.0;
        }
        progress = Math.min(1.0, progress + d);
        ratePerTick = d;
        active = true;
    }

    private static void render(Gui2D graphics, DeltaTracker delta) {
        if (!active || !Settings.displayMiningProgress) return;
        Minecraft mc = Minecraft.getInstance();

        String text = text(progress, ratePerTick);

        int w = mc.font.width(text);
        int x = mc.getWindow().getGuiScaledWidth() / 2 - w / 2;
        int y = HudLayout.miningRow(mc.getWindow().getGuiScaledHeight());
        graphics.text(mc.font, text, x, y, 0xFFFFFFFF, true);
    }

    /** The readout: progress, and the time left at the current rate; "--" for a block that can't be broken
     *  (bedrock and the like, where the game's progress rate is 0). */
    static String text(double progress, double ratePerTick) {
        double secondsLeft = ratePerTick > 0 ? (1.0 - progress) / ratePerTick / 20.0 : Double.POSITIVE_INFINITY;
        // sub-second precision for normal breaks; roll up to m/h when mining fatigue makes it long
        String left = secondsLeft >= 60 ? KineTime.format(secondsLeft) : String.format("%.1fs", secondsLeft);
        return String.format("%.0f%%   %s left", progress * 100.0, left);
    }
}
