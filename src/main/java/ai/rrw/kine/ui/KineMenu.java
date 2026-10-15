package ai.rrw.kine.ui;

import ai.rrw.kine.compat.Compat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;

public class KineMenu {
    public static void register() {
        KeyMapping open = Compat.key("key.kine.settings", "k");
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (open.consumeClick()) {
                Compat.setScreen(mc, Compat.settingsScreen(Compat.screen(mc)));
            }
        });
    }
}
