package ai.rrw.kine.ui;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class KineMenu {
    public static void register() {
        KeyMapping open = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.kine.settings", GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (open.consumeClick()) {
                mc.setScreen(new KineSettingsScreen(mc.screen));
            }
        });
    }
}
