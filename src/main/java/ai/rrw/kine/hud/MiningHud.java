package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class MiningHud {

    private static boolean active = false;    // currently breaking?
    private static double progress = 0.0;     // our tracked 0..1
    private static double ratePerTick = 0.0;  // current progress added per tick
    private static BlockPos target = null;    // block being broken

    public static void register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(Kine.MOD_ID, "mining"),
            MiningHud::render
        );
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

        if (!pos.equals(target)) {   // a new block — start over
            target = pos;
            progress = 0.0;
        }
        progress = Math.min(1.0, progress + d);
        ratePerTick = d;
        active = true;
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();

        double secondsLeft = ratePerTick > 0 ? (1.0 - progress) / ratePerTick / 20.0 : 0.0;
        String text = String.format("%.0f%%   %.1fs left", progress * 100.0, secondsLeft);

        int w = mc.font.width(text);
        int x = mc.getWindow().getGuiScaledWidth() / 2 - w / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2 + 12;   // just below the crosshair
        graphics.text(mc.font, text, x, y, 0xFFFFFFFF, true);
    }
}
