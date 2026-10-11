package ai.rrw.kine.hud;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.PackRepository;

/**
 * The thin projectile outlines: an override of the game's outline blur shader (entity_outline_box_blur.fsh)
 * that keeps the red projectile outline thin and at full colour. It changes every outline in the game while
 * on, so it ships as a built-in resource pack, on by default, that its setting switches; it also shows in the
 * game's resource pack screen as "Kine thin outlines".
 */
public final class ThinOutlines {
    private ThinOutlines() {}

    private static final Identifier ID = Identifier.fromNamespaceAndPath(Kine.MOD_ID, "thin_outlines");

    public static void register() {
        FabricLoader.getInstance().getModContainer(Kine.MOD_ID).ifPresent(c ->
            ResourceLoader.registerBuiltinPack(ID, c, Component.literal("Kine thin outlines"), PackActivationType.DEFAULT_ENABLED));
    }

    /** Whether the pack is on now. */
    public static boolean selected(Minecraft mc) {
        return mc.getResourcePackRepository().getSelectedIds().contains(ID.toString());
    }

    /** Switch the pack to match the setting, reloading resources only when that changes it. */
    public static void sync(Minecraft mc) {
        PackRepository repo = mc.getResourcePackRepository();
        String id = ID.toString();
        if (!repo.getAvailableIds().contains(id)) return;
        boolean want = Settings.thinOutlines;
        if (want == repo.getSelectedIds().contains(id)) return;
        if (want) repo.addPack(id);
        else repo.removePack(id);
        mc.options.updateResourcePacks(repo);
    }
}
