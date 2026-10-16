package ai.rrw.kine;

import ai.rrw.kine.compat.Compat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
        autopilot, takeoff, landing, navigation, terrainAvoidance, collisionAvoidance,
        elytraDuraFailsafe, fallPrevention, waterBucketClutch, autoTotem, autoEat, afkDamageProtection,
        projectileReticle, projectileGlow, thinOutlines, projectileDodge, autoWeapon, autoAim, killAura, bowAura, combatMovement;
    /** Combat movement's balance, 0 (survive) to 100 (kill fast). */
    public static int combatBias;
    /** The bow aura's mode (RW 2026-10-14): BOW_OFF, BOW_HELD (only the ranged weapon in your selected slot, no
     *  switching), BOW_ON (picks the strongest one it can and switches to it). bowAura is true in either of the last two. */
    public static int bowAuraMode;
    public static final int BOW_OFF = 0, BOW_HELD = 1, BOW_ON = 2;
    /** The bow aura's range, blocks (RW 2026-10-14: "make the 48 blocks a slider"), from BOW_RANGE_MIN: below it a mob
     *  is about to be the kill aura's anyway (its reach and a couple of blocks' walk). Up to 128: the client is sent
     *  monsters only within 8 chunks of you (EntityType clientTrackingRange 8), and a bow's arc on level ground ends at
     *  about 117 blocks (a crossbow's a little further), so nothing beyond can be seen or reached. */
    public static int bowAuraRange;
    public static final int BOW_RANGE_MIN = 8, BOW_RANGE_MAX = 128;
    /** Collision avoidance's quiet time after it lets go, tenths of a second. */
    public static int collisionCooldown;
    public static boolean walkPathfinder, walkPathDisplay, walkSearchDisplay, walkLayer2, walkLayer2Display, walkElytraHops,
        walkReadouts, walkSearchDetailed, walkParkour, walkDirect, walkFreeJumps, walkLoose, walkOpenDoors;
    /** The walk's gait (RW 2026-10-14, "add teh gaint"): the most the walk may use, as an Airbus thrust lever sets the
     *  most the autothrust may command; it still jumps where the ground needs it. GAIT_WALK: no sprint key at all;
     *  GAIT_SPRINT: sprinting, a jump only where the ground needs one; GAIT_SPRINT_JUMP: sprint-jumping as before (the
     *  default); GAIT_HOPS: that and elytra hops (was the Elytra hops switch; walkElytraHops is this level). */
    public static int walkGait;
    public static final int GAIT_WALK = 0, GAIT_SPRINT = 1, GAIT_SPRINT_JUMP = 2, GAIT_HOPS = 3;
    /** The walking route's safety margin, hundredths of a block. */
    public static int walkMargin;
    /** The most health (half hearts) the walk may lose to a drop (RW 2026-10-14: "a todo seting how big jumps the
     *  pathifnder is allwod to take damange"); 0: no drop that hurts. */
    public static int walkFallDamage;
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
        return p != null && (p.isFallFlying() || Compat.isGlider(p.getItemBySlot(EquipmentSlot.CHEST)));
    }

    /** One setting: its key in kine.json, its section, the choices it cycles through, its default and its
     *  saved value (an index into the choices: 0 off, 1 on, or the Show order). */
    public static final class Option {
        public final String key;
        public final Section section;
        private final String[] labels;   // null for a level
        private String[] keys = null;    // a choice saved by key (else by index as a toggle, a mode or a level)
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
        /** A choice of more than on and off, saved by key ("off", "held", "on"). */
        public boolean isChoice() { return keys != null; }
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

    /** A choice among labels, saved by its key; a saved true (the setting when it was a toggle) reads as the last
     *  choice, false as the first. */
    private static Option choice(String key, Section s, String[] labels, String[] keys, int def, Consumer<Integer> field) {
        Option o = new Option(key, s, labels, def, field);
        o.keys = keys;
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
    public static final Option COLLISION_AVOIDANCE        = toggle("collisionAvoidance", FLIGHT, true, v -> collisionAvoidance = v);
    // RW 2026-10-14: "add a coolwodnw ig defualt to like 1s or somethgin with a slider"
    public static final Option COLLISION_COOLDOWN         = level("collisionAvoidanceCooldown", FLIGHT, 10, 100, v -> collisionCooldown = v);

    public static final Option WALK_PATHFINDER            = toggle("walkPathfinder", WALK, true, v -> walkPathfinder = v);
    public static final Option WALK_PATH_DISPLAY          = toggle("walkPathDisplay", WALK, true, v -> walkPathDisplay = v);
    public static final Option WALK_SEARCH_DISPLAY        = toggle("walkSearchDisplay", WALK, false, v -> walkSearchDisplay = v);
    public static final Option WALK_SEARCH_DETAILED       = toggle("walkSearchDetailed", WALK, false, v -> walkSearchDetailed = v);
    public static final Option WALK_MARGIN                = level("walkSafetyMargin", WALK, 20, 50, v -> walkMargin = v);
    public static final Option WALK_LAYER2                = toggle("walkLayer2", WALK, true, v -> walkLayer2 = v);
    public static final Option WALK_LAYER2_DISPLAY        = toggle("walkLayer2Display", WALK, true, v -> walkLayer2Display = v);
    public static final Option WALK_GAIT                  = choice("walkGait", WALK,
        new String[]{"WALK", "SPRINT", "SPRINT-JUMP", "ELYTRA HOPS"}, new String[]{"walk", "sprint", "sprintJump", "elytraHops"},
        GAIT_SPRINT_JUMP, v -> { walkGait = v; walkElytraHops = v == GAIT_HOPS; });
    public static final Option WALK_PARKOUR               = toggle("walkParkour", WALK, false, v -> walkParkour = v);
    public static final Option WALK_DIRECT                = toggle("walkDirect", WALK, true, v -> walkDirect = v);
    public static final Option WALK_FREE_JUMPS            = toggle("walkFreeJumps", WALK, true, v -> walkFreeJumps = v);
    // the loosened route planner, the fallback when the proven moves find no way (Loose; the spiral agent, 2026-10-15)
    public static final Option WALK_LOOSE                 = toggle("walkLoose", WALK, true, v -> walkLoose = v);
    // walking through closed wooden doors, opened on the way (DoorOpener; the spiral agent, 2026-10-15)
    public static final Option WALK_OPEN_DOORS            = toggle("walkOpenDoors", WALK, true, v -> walkOpenDoors = v);
    // the most a drop may hurt, half hearts (the spiral agent, 2026-10-15: a parkour map's drops of 3.5 and 4)
    public static final Option WALK_FALL_DAMAGE           = level("walkFallDamage", WALK, 0, 20, v -> walkFallDamage = v);
    public static final Option WALK_READOUTS              = toggle("walkReadouts", WALK, true, v -> walkReadouts = v);

    public static final Option ELYTRA_DURA_FAILSAFE       = toggle("elytraDuraFailsafe", SAFETY, true, v -> elytraDuraFailsafe = v);
    public static final Option FALL_PREVENTION            = toggle("fallPrevention", SAFETY, true, v -> fallPrevention = v);
    public static final Option WATER_BUCKET_CLUTCH        = toggle("waterBucketClutch", SAFETY, true, v -> waterBucketClutch = v);
    public static final Option AUTO_TOTEM                 = toggle("autoTotem", SAFETY, false, v -> autoTotem = v);
    public static final Option AUTO_EAT                   = toggle("autoEat", SAFETY, false, v -> autoEat = v);
    public static final Option AFK_DAMAGE_PROTECTION      = toggle("afkDamageProtection", SAFETY, true, v -> afkDamageProtection = v);

    public static final Option PROJECTILE_RETICLE         = toggle("projectileReticle", COMBAT, true, v -> projectileReticle = v);
    public static final Option PROJECTILE_GLOW            = toggle("projectileGlow", COMBAT, true, v -> projectileGlow = v);
    public static final Option THIN_OUTLINES              = toggle("thinOutlines", COMBAT, true, v -> thinOutlines = v);
    public static final Option PROJECTILE_DODGE           = toggle("projectileDodge", COMBAT, false, v -> projectileDodge = v);
    public static final Option AUTO_WEAPON                = toggle("autoWeapon", COMBAT, false, v -> autoWeapon = v);
    public static final Option AUTO_AIM                   = toggle("autoAim", COMBAT, false, v -> autoAim = v);
    public static final Option KILL_AURA                  = toggle("killAura", COMBAT, false, v -> killAura = v);
    // RW 2026-10-14: "add a third oton, so bow aura can be off, only when bow slot slected, and on"
    public static final Option BOW_AURA                   = choice("bowAura", COMBAT, new String[]{"OFF", "HELD ONLY", "ON"},
        new String[]{"off", "held", "on"}, BOW_OFF, v -> { bowAuraMode = v; bowAura = v != BOW_OFF; });
    public static final Option BOW_AURA_RANGE             = level("bowAuraRange", COMBAT, 48, BOW_RANGE_MAX,
        v -> bowAuraRange = Math.max(BOW_RANGE_MIN, v));
    public static final Option COMBAT_MOVEMENT            = toggle("combatMovement", COMBAT, false, v -> combatMovement = v);
    public static final Option COMBAT_BIAS                = level("combatMovementBias", COMBAT, 0, 100, v -> combatBias = v);

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
        if (!parkourAllowed()) walkParkour = false;
    }

    /** Parkour needs sprint-jumping (RW 2026-10-14 22:46, "gray out hte parkor lock to off if the gaint lowr htan sprint
     *  jump"): below the Sprint-jump gait it is off and its row greyed, its own setting kept for when the gait goes back
     *  up. */
    public static boolean parkourAllowed() {
        return walkGait >= GAIT_SPRINT_JUMP;
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
                } else if (opt.isChoice()) {
                    if (v.isBoolean()) opt.set(v.getAsBoolean() ? opt.max : 0);
                    else for (int i = 0; i < opt.keys.length; i++) if (opt.keys[i].equals(v.getAsString())) opt.set(i);
                } else if (opt.isMode()) {
                    if (v.isBoolean()) opt.set(!v.getAsBoolean() ? 0 : opt.defaultValue == 0 ? Show.ELYTRA.ordinal() : opt.defaultValue);
                    else for (Show s : Show.values()) if (s.key.equals(v.getAsString())) opt.set(s.ordinal());
                } else if (v.isBoolean()) {
                    opt.set(v.getAsBoolean() ? 1 : 0);
                }
            }
            // the gait's top level was the Elytra hops switch: a file from before the gait reads its true as that level
            JsonElement hops = o.get("walkElytraHops");
            if (o.get(WALK_GAIT.key) == null && hops != null && hops.isJsonPrimitive() && hops.getAsJsonPrimitive().isBoolean()
                && hops.getAsBoolean()) WALK_GAIT.set(GAIT_HOPS);
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
            else if (opt.isChoice()) o.addProperty(opt.key, opt.keys[opt.value]);
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
