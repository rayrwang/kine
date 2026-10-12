package ai.rrw.kine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The settings, saved in config/kine.json. Every feature has one: an on/off toggle, or for the elytra, speed
 * and altitude displays a three-way choice of off, only while wearing an elytra, or always, or a level (a slider,
 * 0 to its maximum, saved as a number). Above them sit a
 * switch for each section and one for the whole mod. A feature reads only its field below, which holds the
 * effective value: its own setting when its section and the mod are on, else off. Adding a setting: a field,
 * a table line, and a row in KineSettingsScreen.
 */
public final class Settings {
    private Settings() {}

    /** The settings screen's sections; each has an on/off switch over its features. */
    public enum Section {
        HUD("Heads-up display"), VISIBILITY("Visibility"), FLIGHT("Elytra flight"), WALK("Walking"), SAFETY("Safety"), COMBAT("Combat");
        public final String title;
        Section(String title) { this.title = title; }
    }

    /** When a display shows: never, only in flight (gliding, or during a rocket takeoff), only while wearing an elytra
     *  (or gliding), or always. Saved by key, so adding a mode doesn't change what a saved file means. */
    public enum Show {
        OFF("OFF", "off"), FLIGHT("IN FLIGHT", "flight"), ELYTRA("ELYTRA ONLY", "elytra"), ALWAYS("ALWAYS", "always");
        public final String label;
        final String key;
        Show(String label, String key) { this.label = label; this.key = key; }
    }

    // Effective values, read by the features.
    public static boolean displayMobHealths, displayMobNames, displayMiningProgress,
        fullbright, freecam, noWaterFog, noLavaFog, noPowderSnowFog, noNetherFog, noRainFog, noBossFog, noDistanceFog,
        noDarkness, noBlindness, noFireOverlay, noUnderwaterOverlay, noPumpkinOverlay, noFrostOverlay, noPortalOverlay,
        autopilot, takeoff, landing, navigation, terrainAvoidance,
        elytraDuraFailsafe, fallPrevention, waterBucketClutch, autoTotem, afkDamageProtection,
        projectileReticle, projectileGlow, thinOutlines, projectileDodge, autoWeapon, autoAim, killAura, combatMovement;
    /** Combat movement's balance, 0 (survive) to 100 (kill fast). */
    public static int combatBias;
    public static boolean walkPathfinder, walkPathDisplay, walkSearchDisplay;
    /** The walking route's safety margin, hundredths of a block. */
    public static int walkMargin;
    public static Show displaySpeed, displayGroundSpeed,
        displayHeadingArc, displayAltitude, displayHeightAboveGround, displayNavReadouts, displayFlightDirectors, displayFlightPathVector,
        displayFlightProfile, displayRangeEndurance, displayFireworkFuel, flightRibbon;

    /** Whether a display set to this choice shows now. */
    public static boolean shows(Show s) {
        return s == Show.ALWAYS || s == Show.ELYTRA && wearingElytra() || s == Show.FLIGHT && inFlight();
    }

    /** Gliding, or a rocket takeoff under way (its ground part included, so its bars show from the click). */
    public static boolean inFlight() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && (p.isFallFlying() || ai.rrw.kine.autoflight.Takeoff.active());
    }

    /** Wearing something to glide with (an elytra in the chest slot), or gliding now. */
    public static boolean wearingElytra() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && (p.isFallFlying() || p.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER));
    }

    /** One setting: its key in kine.json, its section, the choices it cycles through, its default and its
     *  saved value (an index into the choices: 0 off, 1 on, or the Show order). */
    public static final class Option {
        public final String key;
        public final Section section;
        private final String[] labels;   // null for a level
        private final int max;           // a level's maximum
        private final int defaultValue;
        private final Consumer<Integer> field;   // writes the effective value
        private int value;

        Option(String key, Section section, String[] labels, int defaultValue, Consumer<Integer> field) {
            this(key, section, labels, labels.length - 1, defaultValue, field);
        }

        Option(String key, Section section, String[] labels, int max, int defaultValue, Consumer<Integer> field) {
            this.key = key; this.section = section; this.labels = labels; this.max = max;
            this.defaultValue = defaultValue; this.field = field; this.value = defaultValue;
        }

        public int value() { return value; }
        public String label() { return labels == null ? String.valueOf(value) : labels[value]; }
        public boolean isMode() { return labels == MODES; }   // the display modes (not any 3 or 4 choices)
        public boolean isLevel() { return labels == null; }
        public int max() { return max; }
        /** Step to the next choice, apply and save. */
        public void cycle() { value = (value + 1) % (max + 1); apply(); save(); }
        /** Set a level, apply and save. */
        public void setLevel(int v) { set(v); apply(); save(); }
        void set(int v) { value = Math.max(0, Math.min(max, v)); }
    }

    private static final String[] ON_OFF = {"OFF", "ON"};
    private static final String[] MODES = {Show.OFF.label, Show.FLIGHT.label, Show.ELYTRA.label, Show.ALWAYS.label};

    private static final List<Option> TABLE = new ArrayList<>();
    /** Every setting, in the order kine.json lists them. */
    public static final List<Option> ALL = Collections.unmodifiableList(TABLE);

    private static Option toggle(String key, Section s, boolean def, Consumer<Boolean> field) {
        Option o = new Option(key, s, ON_OFF, def ? 1 : 0, v -> field.accept(v != 0));
        TABLE.add(o);
        return o;
    }

    private static Option mode(String key, Section s, Show def, Consumer<Show> field) {
        Option o = new Option(key, s, MODES, def.ordinal(), v -> field.accept(Show.values()[v]));
        TABLE.add(o);
        return o;
    }

    private static Option level(String key, Section s, int def, int max, Consumer<Integer> field) {
        Option o = new Option(key, s, null, max, def, field);
        TABLE.add(o);
        return o;
    }

    private static final Section HUD = Section.HUD, VISIBILITY = Section.VISIBILITY, FLIGHT = Section.FLIGHT, WALK = Section.WALK,
        SAFETY = Section.SAFETY, COMBAT = Section.COMBAT;
    private static final Show ELYTRA = Show.ELYTRA;

    public static final Option DISPLAY_MOB_HEALTHS        = toggle("displayMobHealths", HUD, true, v -> displayMobHealths = v);
    public static final Option DISPLAY_MOB_NAMES          = toggle("displayMobNames", HUD, false, v -> displayMobNames = v);
    public static final Option DISPLAY_MINING_PROGRESS    = toggle("displayMiningProgress", HUD, true, v -> displayMiningProgress = v);
    public static final Option DISPLAY_SPEED              = mode("displaySpeed", HUD, Show.FLIGHT, v -> displaySpeed = v);
    public static final Option DISPLAY_GROUND_SPEED       = mode("displayGroundSpeed", HUD, Show.FLIGHT, v -> displayGroundSpeed = v);

    public static final Option FULLBRIGHT                 = toggle("fullbright", VISIBILITY, false, v -> fullbright = v);
    public static final Option FREECAM                    = toggle("freecam", VISIBILITY, false, v -> freecam = v);
    public static final Option NO_WATER_FOG               = toggle("noWaterFog", VISIBILITY, false, v -> noWaterFog = v);
    public static final Option NO_LAVA_FOG                = toggle("noLavaFog", VISIBILITY, false, v -> noLavaFog = v);
    public static final Option NO_POWDER_SNOW_FOG         = toggle("noPowderSnowFog", VISIBILITY, false, v -> noPowderSnowFog = v);
    public static final Option NO_NETHER_FOG              = toggle("noNetherFog", VISIBILITY, false, v -> noNetherFog = v);
    public static final Option NO_RAIN_FOG                = toggle("noRainFog", VISIBILITY, false, v -> noRainFog = v);
    public static final Option NO_BOSS_FOG                = toggle("noBossFog", VISIBILITY, false, v -> noBossFog = v);
    public static final Option NO_DISTANCE_FOG            = toggle("noDistanceFog", VISIBILITY, false, v -> noDistanceFog = v);
    public static final Option NO_DARKNESS                = toggle("noDarkness", VISIBILITY, false, v -> noDarkness = v);
    public static final Option NO_BLINDNESS               = toggle("noBlindness", VISIBILITY, false, v -> noBlindness = v);
    public static final Option NO_FIRE_OVERLAY            = toggle("noFireOverlay", VISIBILITY, false, v -> noFireOverlay = v);
    public static final Option NO_UNDERWATER_OVERLAY      = toggle("noUnderwaterOverlay", VISIBILITY, false, v -> noUnderwaterOverlay = v);
    public static final Option NO_PUMPKIN_OVERLAY         = toggle("noPumpkinOverlay", VISIBILITY, false, v -> noPumpkinOverlay = v);
    public static final Option NO_FROST_OVERLAY           = toggle("noFrostOverlay", VISIBILITY, false, v -> noFrostOverlay = v);
    public static final Option NO_PORTAL_OVERLAY          = toggle("noPortalOverlay", VISIBILITY, false, v -> noPortalOverlay = v);

    public static final Option DISPLAY_HEADING_ARC        = mode("displayHeadingArc", FLIGHT, Show.FLIGHT, v -> displayHeadingArc = v);
    public static final Option DISPLAY_ALTITUDE           = mode("displayAltitude", FLIGHT, Show.FLIGHT, v -> displayAltitude = v);
    public static final Option DISPLAY_HEIGHT_ABOVE_GROUND = mode("displayHeightAboveGround", FLIGHT, Show.FLIGHT, v -> displayHeightAboveGround = v);
    public static final Option DISPLAY_NAV_READOUTS       = mode("displayNavReadouts", FLIGHT, Show.FLIGHT, v -> displayNavReadouts = v);
    public static final Option DISPLAY_FLIGHT_DIRECTORS   = mode("displayFlightDirectors", FLIGHT, Show.FLIGHT, v -> displayFlightDirectors = v);
    public static final Option DISPLAY_FLIGHT_PATH_VECTOR = mode("displayFlightPathVector", FLIGHT, Show.OFF, v -> displayFlightPathVector = v);
    public static final Option DISPLAY_FLIGHT_PROFILE     = mode("displayFlightProfile", FLIGHT, Show.FLIGHT, v -> displayFlightProfile = v);
    public static final Option DISPLAY_RANGE_ENDURANCE    = mode("displayRangeEndurance", FLIGHT, ELYTRA, v -> displayRangeEndurance = v);
    public static final Option DISPLAY_FIREWORK_FUEL      = mode("displayFireworkFuel", FLIGHT, ELYTRA, v -> displayFireworkFuel = v);
    public static final Option FLIGHT_PATH_RAILS          = mode("flightRibbon", FLIGHT, Show.FLIGHT, v -> flightRibbon = v);
    public static final Option AUTOPILOT                  = toggle("autopilot", FLIGHT, true, v -> autopilot = v);
    public static final Option TAKEOFF                    = toggle("takeoff", FLIGHT, true, v -> takeoff = v);
    public static final Option LANDING                    = toggle("landing", FLIGHT, true, v -> landing = v);
    public static final Option NAVIGATION                 = toggle("navigation", FLIGHT, true, v -> navigation = v);
    public static final Option TERRAIN_AVOIDANCE          = toggle("terrainAvoidance", FLIGHT, true, v -> terrainAvoidance = v);

    public static final Option WALK_PATHFINDER            = toggle("walkPathfinder", WALK, true, v -> walkPathfinder = v);
    public static final Option WALK_PATH_DISPLAY          = toggle("walkPathDisplay", WALK, true, v -> walkPathDisplay = v);
    public static final Option WALK_SEARCH_DISPLAY        = toggle("walkSearchDisplay", WALK, true, v -> walkSearchDisplay = v);
    public static final Option WALK_MARGIN                = level("walkSafetyMargin", WALK, 20, 50, v -> walkMargin = v);

    public static final Option ELYTRA_DURA_FAILSAFE       = toggle("elytraDuraFailsafe", SAFETY, true, v -> elytraDuraFailsafe = v);
    public static final Option FALL_PREVENTION            = toggle("fallPrevention", SAFETY, true, v -> fallPrevention = v);
    public static final Option WATER_BUCKET_CLUTCH        = toggle("waterBucketClutch", SAFETY, true, v -> waterBucketClutch = v);
    public static final Option AUTO_TOTEM                 = toggle("autoTotem", SAFETY, false, v -> autoTotem = v);
    public static final Option AFK_DAMAGE_PROTECTION      = toggle("afkDamageProtection", SAFETY, true, v -> afkDamageProtection = v);

    public static final Option PROJECTILE_RETICLE         = toggle("projectileReticle", COMBAT, true, v -> projectileReticle = v);
    public static final Option PROJECTILE_GLOW            = toggle("projectileGlow", COMBAT, true, v -> projectileGlow = v);
    public static final Option THIN_OUTLINES              = toggle("thinOutlines", COMBAT, true, v -> thinOutlines = v);
    public static final Option PROJECTILE_DODGE           = toggle("projectileDodge", COMBAT, false, v -> projectileDodge = v);
    public static final Option AUTO_WEAPON                = toggle("autoWeapon", COMBAT, false, v -> autoWeapon = v);
    public static final Option AUTO_AIM                   = toggle("autoAim", COMBAT, false, v -> autoAim = v);
    public static final Option KILL_AURA                  = toggle("killAura", COMBAT, false, v -> killAura = v);
    public static final Option COMBAT_MOVEMENT            = toggle("combatMovement", COMBAT, false, v -> combatMovement = v);
    public static final Option COMBAT_BIAS                = level("combatMovementBias", COMBAT, 25, 100, v -> combatBias = v);

    /** The nav screen's At destination choice (Nav.Arrival's key), kept here so it is saved with the rest. */
    public static String navArrival = "holdTillDay";

    // the switches above the options
    private static boolean enabled = true;
    private static final Map<Section, Boolean> SECTION_ON = new EnumMap<>(Section.class);
    static { for (Section s : Section.values()) SECTION_ON.put(s, true); apply(); }

    public static boolean enabled() { return enabled; }
    public static boolean sectionOn(Section s) { return SECTION_ON.get(s); }
    public static void setEnabled(boolean v) { enabled = v; apply(); save(); }
    public static void setSectionOn(Section s, boolean v) { SECTION_ON.put(s, v); apply(); save(); }

    private static String sectionKey(Section s) { return "section" + s.name().charAt(0) + s.name().substring(1).toLowerCase(); }

    /** Write every feature's effective value: its own setting while its section and the mod are on, else off. */
    public static void apply() {
        for (Option o : ALL) o.field.accept(enabled && SECTION_ON.get(o.section) ? o.value : 0);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("kine.json");

    /** Read kine.json; a missing key keeps its default, and a malformed file changes nothing. A display saved
     *  by an older version as true reads as its default, or elytra only if its default is off; false as off. Modes
     *  are read by key ("off", "flight", "elytra", "always"), so a mode added later leaves saved files' meaning. */
    public static void load() {
        try {
            if (!Files.exists(FILE)) { save(); return; }
            JsonObject o = GSON.fromJson(Files.readString(FILE), JsonObject.class);
            if (o == null) return;
            for (Option opt : ALL) {
                JsonElement e = o.get(opt.key);
                if (e == null || !e.isJsonPrimitive()) continue;
                JsonPrimitive v = e.getAsJsonPrimitive();
                if (opt.isLevel()) {
                    if (v.isNumber()) opt.set(v.getAsInt());
                } else if (opt.isMode()) {
                    if (v.isBoolean()) opt.set(!v.getAsBoolean() ? 0 : opt.defaultValue == 0 ? Show.ELYTRA.ordinal() : opt.defaultValue);
                    else for (Show s : Show.values()) if (s.key.equals(v.getAsString())) opt.set(s.ordinal());
                } else if (v.isBoolean()) {
                    opt.set(v.getAsBoolean() ? 1 : 0);
                }
            }
            if (o.has("enabled") && o.get("enabled").isJsonPrimitive()) enabled = o.get("enabled").getAsBoolean();
            if (o.has("navArrival") && o.get("navArrival").isJsonPrimitive()) navArrival = o.get("navArrival").getAsString();
            for (Section s : Section.values()) {
                JsonElement e = o.get(sectionKey(s));
                if (e != null && e.isJsonPrimitive()) SECTION_ON.put(s, e.getAsBoolean());
            }
        } catch (Exception e) {
            Kine.LOGGER.warn("Kine: could not load config, using defaults", e);
        }
        apply();
    }

    /** Restore every setting and switch to its default and persist. */
    public static void resetDefaults() {
        for (Option o : ALL) o.set(o.defaultValue);
        enabled = true;
        for (Section s : Section.values()) SECTION_ON.put(s, true);
        apply();
        save();
    }

    public static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        for (Section s : Section.values()) o.addProperty(sectionKey(s), SECTION_ON.get(s));
        o.addProperty("navArrival", navArrival);
        for (Option opt : ALL) {
            if (opt.isLevel()) o.addProperty(opt.key, opt.value);
            else if (opt.isMode()) o.addProperty(opt.key, Show.values()[opt.value].key);
            else o.addProperty(opt.key, opt.value != 0);
        }
        try {
            Files.writeString(FILE, GSON.toJson(o));
        } catch (IOException e) {
            Kine.LOGGER.warn("Kine: could not save config", e);
        }
    }
}
