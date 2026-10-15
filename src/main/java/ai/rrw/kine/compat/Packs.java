package ai.rrw.kine.compat;

import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Which folder of the jar a built-in resource pack comes from on the running release (Epoch.builtinPack). A pack is
 * resourcepacks/&lt;name&gt;/ in the jar; a folder resourcepacks/&lt;name&gt;_&lt;from&gt;-&lt;to&gt;/ (or &lt;name&gt;_&lt;from&gt;-,
 * open-ended) replaces it on the releases from..to, when they need other files (the thin outlines' shader is written
 * three ways on 1.21.x and two on 26.x). Whole folders rather than the pack format's overlays: Fabric API enables a
 * default-on built-in pack only when the game opens it as one plain pack, and a pack with an overlay in use opens
 * wrapped (seen on 26.3, whose overlay kept the pack off on a fresh start; Fabric's DefaultResourcePackStorage, and
 * GameOptionsMixin before it, test the opened pack's class).
 */
public final class Packs {
    private Packs() {}

    /** The folder name under resourcepacks/ (also the pack id's path) for this release. */
    public static String folder(ModContainer mod, String name) {
        Optional<Path> dir = mod.findPath("resourcepacks");
        if (dir.isEmpty()) return name;
        String release = GameVersion.minecraft();
        try (Stream<Path> folders = Files.list(dir.get())) {
            return folders.map(p -> p.getFileName().toString().replaceAll("/$", ""))
                .filter(f -> f.startsWith(name + "_") && covers(f.substring(name.length() + 1), release))
                .findFirst().orElse(name);
        } catch (IOException e) {
            return name;
        }
    }

    /** "from-to" or "from-" holds this release. */
    private static boolean covers(String range, String release) {
        int dash = range.indexOf('-');
        if (dash <= 0) return false;
        String from = range.substring(0, dash), to = range.substring(dash + 1);
        if (!from.matches("[0-9.]+") || !to.matches("[0-9.]*")) return false;
        return GameVersion.compare(release, from) >= 0 && (to.isEmpty() || GameVersion.compare(release, to) <= 0);
    }
}
