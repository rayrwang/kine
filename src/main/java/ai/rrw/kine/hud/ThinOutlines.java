package ai.rrw.kine.hud;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

/**
 * The thin projectile outlines: an override of the game's outline blur shader (entity_outline_box_blur.fsh)
 * that keeps the red projectile outline thin and at full colour. It changes every outline in the game while
 * on, so it ships as a built-in resource pack, on by default, that its setting switches; it also shows in the
 * game's resource pack screen as "Kine thin outlines".
 */
public final class ThinOutlines {
    private ThinOutlines() {}

    /** The pack's id: kine:thin_outlines, or the release's own copy of it (compat.Packs). */
    private static String id = Kine.MOD_ID + ":thin_outlines";

    public static void register() {
        id = Compat.builtinPack("thin_outlines", "Kine thin outlines", true);
    }

    /** Whether the pack is on now. */
    public static boolean selected(Minecraft mc) {
        return mc.getResourcePackRepository().getSelectedIds().contains(id);
    }

    /** Switch the pack to match the setting, reloading resources only when that changes it. */
    public static void sync(Minecraft mc) {
        PackRepository repo = mc.getResourcePackRepository();
        if (!repo.getAvailableIds().contains(id)) return;
        boolean want = Settings.thinOutlines;
        if (want == repo.getSelectedIds().contains(id)) return;
        if (want) repo.addPack(id);
        else repo.removePack(id);
        mc.options.updateResourcePacks(repo);
    }
}
