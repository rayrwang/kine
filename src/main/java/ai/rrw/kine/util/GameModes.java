package ai.rrw.kine.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;

/** The local player's game mode, as the client knows it (MultiPlayerGameMode.getPlayerMode). */
public final class GameModes {
    private GameModes() {}

    /** Survival or adventure. In creative and spectator hostile mobs leave you alone and nothing hurts you, so the
     *  features that fight or keep you safe have nothing to do there. */
    public static boolean survivalLike(Minecraft mc) {
        if (mc.gameMode == null) return false;
        GameType mode = mc.gameMode.getPlayerMode();
        return mode == GameType.SURVIVAL || mode == GameType.ADVENTURE;
    }
}
