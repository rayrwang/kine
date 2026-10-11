package ai.rrw.kine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The on/off settings, saved in config/kine.json. Each setting is a field (read directly by the
 * features) and one line in the table below (its key in kine.json, the field's name, and its default).
 * Adding a setting: a field, a table line, and a row in KineSettingsScreen.
 */
public final class Settings {
    private Settings() {}

    public static boolean displaySpeed, displayGroundSpeed, displayFlightDirectors, elytraDuraFailsafe,
        fallPrevention, displayMobHealths, displayMobNames, projectileReticle, projectileGlow,
        autoAim, projectileDodge, displayRangeEndurance, afkDamageProtection, waterBucketClutch,
        displayFlightPathVector, terrainAvoidance, flightRibbon, displayFlightProfile, killAura, autoWeapon;

    /** One saved setting: its key in kine.json, its default, and access to its field. */
    public record Toggle(String key, boolean defaultValue, BooleanSupplier get, Consumer<Boolean> set) {}

    private static final List<Toggle> TABLE = new ArrayList<>();
    /** Every setting, in the order kine.json lists them. */
    public static final List<Toggle> ALL = Collections.unmodifiableList(TABLE);

    private static Toggle add(String key, boolean defaultValue, BooleanSupplier get, Consumer<Boolean> set) {
        Toggle t = new Toggle(key, defaultValue, get, set);
        TABLE.add(t);
        set.accept(defaultValue);
        return t;
    }

    public static final Toggle DISPLAY_SPEED              = add("displaySpeed", true, () -> displaySpeed, v -> displaySpeed = v);
    public static final Toggle DISPLAY_GROUND_SPEED       = add("displayGroundSpeed", true, () -> displayGroundSpeed, v -> displayGroundSpeed = v);
    public static final Toggle DISPLAY_FLIGHT_DIRECTORS   = add("displayFlightDirectors", true, () -> displayFlightDirectors, v -> displayFlightDirectors = v);
    public static final Toggle ELYTRA_DURA_FAILSAFE       = add("elytraDuraFailsafe", true, () -> elytraDuraFailsafe, v -> elytraDuraFailsafe = v);
    public static final Toggle FALL_PREVENTION            = add("fallPrevention", true, () -> fallPrevention, v -> fallPrevention = v);
    public static final Toggle DISPLAY_MOB_HEALTHS        = add("displayMobHealths", true, () -> displayMobHealths, v -> displayMobHealths = v);
    public static final Toggle DISPLAY_MOB_NAMES          = add("displayMobNames", false, () -> displayMobNames, v -> displayMobNames = v);
    public static final Toggle PROJECTILE_RETICLE         = add("projectileReticle", true, () -> projectileReticle, v -> projectileReticle = v);
    public static final Toggle PROJECTILE_GLOW            = add("projectileGlow", true, () -> projectileGlow, v -> projectileGlow = v);
    public static final Toggle AUTO_AIM                   = add("autoAim", false, () -> autoAim, v -> autoAim = v);
    public static final Toggle PROJECTILE_DODGE           = add("projectileDodge", false, () -> projectileDodge, v -> projectileDodge = v);
    public static final Toggle DISPLAY_RANGE_ENDURANCE    = add("displayRangeEndurance", true, () -> displayRangeEndurance, v -> displayRangeEndurance = v);
    public static final Toggle AFK_DAMAGE_PROTECTION      = add("afkDamageProtection", true, () -> afkDamageProtection, v -> afkDamageProtection = v);
    public static final Toggle WATER_BUCKET_CLUTCH        = add("waterBucketClutch", true, () -> waterBucketClutch, v -> waterBucketClutch = v);
    public static final Toggle DISPLAY_FLIGHT_PATH_VECTOR = add("displayFlightPathVector", false, () -> displayFlightPathVector, v -> displayFlightPathVector = v);
    public static final Toggle TERRAIN_AVOIDANCE          = add("terrainAvoidance", true, () -> terrainAvoidance, v -> terrainAvoidance = v);
    public static final Toggle FLIGHT_PATH_RAILS          = add("flightRibbon", true, () -> flightRibbon, v -> flightRibbon = v);
    public static final Toggle DISPLAY_FLIGHT_PROFILE     = add("displayFlightProfile", true, () -> displayFlightProfile, v -> displayFlightProfile = v);
    public static final Toggle KILL_AURA                  = add("killAura", false, () -> killAura, v -> killAura = v);
    public static final Toggle AUTO_WEAPON                = add("autoWeapon", false, () -> autoWeapon, v -> autoWeapon = v);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("kine.json");

    /** Read kine.json; a missing key keeps its default, and a malformed file changes nothing. */
    public static void load() {
        try {
            if (!Files.exists(FILE)) { save(); return; }
            JsonObject o = GSON.fromJson(Files.readString(FILE), JsonObject.class);
            if (o == null) return;
            boolean[] values = new boolean[ALL.size()];
            for (int i = 0; i < values.length; i++) {
                Toggle t = ALL.get(i);
                JsonElement e = o.get(t.key());
                values[i] = (e == null || e.isJsonNull()) ? t.defaultValue() : GSON.fromJson(e, boolean.class);
            }
            for (int i = 0; i < values.length; i++) ALL.get(i).set().accept(values[i]);
        } catch (Exception e) {
            Kine.LOGGER.warn("Kine: could not load config, using defaults", e);
        }
    }

    /** Restore every setting to its built-in default and persist. */
    public static void resetDefaults() {
        for (Toggle t : ALL) t.set().accept(t.defaultValue());
        save();
    }

    public static void save() {
        JsonObject o = new JsonObject();
        for (Toggle t : ALL) o.addProperty(t.key(), t.get().getAsBoolean());
        try {
            Files.writeString(FILE, GSON.toJson(o));
        } catch (IOException e) {
            Kine.LOGGER.warn("Kine: could not save config", e);
        }
    }
}
