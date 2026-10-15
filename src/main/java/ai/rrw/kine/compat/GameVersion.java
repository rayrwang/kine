package ai.rrw.kine.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The running Minecraft release and the epoch whose game code runs on it (versions/README.md). The jar's table,
 * kine-epochs.txt (written by the build from versions/versions.txt), lists each release of the jar's family with its
 * epoch; a release newer than all of them (a patch the table doesn't name) takes the newest epoch at or below it.
 *
 * <p>No Minecraft class is touched here: the mixin plugin reads it before the game's classes load. In unit tests (no
 * Fabric Loader) the release is the system property kine.minecraft, set by the build to the development version.
 */
public final class GameVersion {
    private GameVersion() {}

    private static String minecraft, epoch;

    /** The running release, e.g. "26.1.2". */
    public static String minecraft() {
        if (minecraft == null) load();
        return minecraft;
    }

    /** The epoch folder whose code runs on it, e.g. "v26_1". */
    public static String epoch() {
        if (epoch == null) load();
        return epoch;
    }

    /** Whether the running release is at least this one (numeric, part by part: 26.1 < 26.1.1 < 26.2). */
    public static boolean atLeast(String release) {
        return compare(minecraft(), release) >= 0;
    }

    private static synchronized void load() {
        if (epoch != null) return;
        String mc = System.getProperty("kine.minecraft");
        if (mc == null) {
            try {
                mc = FabricLoader.getInstance().getModContainer("minecraft")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
            } catch (Throwable t) {
                mc = null;   // no loader (a unit test without the property)
            }
        }
        List<String[]> table = table();
        if (table.isEmpty()) throw new IllegalStateException("kine: no kine-epochs.txt in the jar");
        if (mc == null) mc = table.get(table.size() - 1)[0];
        String best = null, bestRelease = null;
        for (String[] row : table) {
            if (compare(row[0], mc) <= 0 && (bestRelease == null || compare(row[0], bestRelease) >= 0)) {
                best = row[1];
                bestRelease = row[0];
            }
        }
        if (best == null) throw new IllegalStateException("kine: Minecraft " + mc + " is older than this jar supports");
        minecraft = mc;
        epoch = best;
    }

    private static List<String[]> table() {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = GameVersion.class.getClassLoader().getResourceAsStream("kine-epochs.txt")) {
            if (in == null) return rows;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\\s+");
                if (f.length == 2) rows.add(f);
            }
        } catch (IOException e) {
            throw new IllegalStateException("kine: can't read kine-epochs.txt", e);
        }
        return rows;
    }

    /** Releases compared numerically, part by part; anything after a '-' or a non-digit (a snapshot) ignored. */
    public static int compare(String a, String b) {
        int[] x = parts(a), y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int[] parts(String v) {
        int cut = v.indexOf('-');
        if (cut >= 0) v = v.substring(0, cut);
        String[] s = v.split("\\.");
        int[] out = new int[s.length];
        for (int i = 0; i < s.length; i++) {
            int n = 0;
            for (char c : s[i].toCharArray()) {
                if (c < '0' || c > '9') break;
                n = n * 10 + (c - '0');
            }
            out[i] = n;
        }
        return out;
    }
}
