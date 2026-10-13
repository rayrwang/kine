package ai.rrw.kine;

import ai.rrw.kine.Settings.Show;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Reading and writing kine.json, and the reading of files older versions saved. */
class SettingsTest {
    @TempDir static Path gameDir;
    static Path file;

    @BeforeAll
    static void pointTheLoaderAtATemporaryGameDirectory() throws Exception {
        // Settings keeps kine.json in the loader's config directory, which only a launched game sets; set it
        // here (before Settings loads) to a temporary one
        Method setGameDir = FabricLoaderImpl.class.getDeclaredMethod("setGameDir", Path.class);
        setGameDir.setAccessible(true);
        setGameDir.invoke(FabricLoaderImpl.INSTANCE, gameDir);
        file = FabricLoader.getInstance().getConfigDir().resolve("kine.json");
    }

    @BeforeEach
    void defaults() {
        Settings.resetDefaults();
    }

    static void load(String json) throws Exception {
        Files.writeString(file, json);
        Settings.load();
    }

    @Test
    void aMissingFileIsWrittenWithTheDefaults() throws Exception {
        Files.delete(file);
        Settings.load();
        assertTrue(Files.exists(file));
        String saved = Files.readString(file);
        assertTrue(saved.contains("\"terrainAvoidance\": true"), saved);
        assertTrue(saved.contains("\"displaySpeed\": \"flight\""), saved);
        assertTrue(saved.contains("\"autoAim\": false"), saved);
    }

    @Test
    void inFlightMode() throws Exception {
        // every display defaults to in flight but the firework count and range and endurance, elytra only (RW); the
        // flight path vector stays off; a saved "flight" reads as in flight, an old true as the display's default
        load("""
            {"displayRangeEndurance": "flight", "displayFlightDirectors": true, "displayAltitude": "elytra"}
            """);
        assertEquals(Show.FLIGHT.ordinal(), Settings.DISPLAY_RANGE_ENDURANCE.value());
        assertEquals(Show.FLIGHT.ordinal(), Settings.DISPLAY_FLIGHT_DIRECTORS.value());
        assertEquals(Show.ELYTRA.ordinal(), Settings.DISPLAY_ALTITUDE.value());
        assertEquals(Show.FLIGHT.ordinal(), Settings.DISPLAY_HEADING_ARC.value());
        assertEquals(Show.FLIGHT.ordinal(), Settings.FLIGHT_PATH_RAILS.value());
        assertEquals(Show.OFF.ordinal(), Settings.DISPLAY_FLIGHT_PATH_VECTOR.value());
        assertEquals(Show.FLIGHT.ordinal(), Settings.DISPLAY_SPEED.value());
        assertEquals(Show.ELYTRA.ordinal(), Settings.DISPLAY_FIREWORK_FUEL.value());
        assertEquals(Show.FLIGHT, Settings.displayRangeEndurance);
    }

    @Test
    void displaysSavedAsBooleansReadAsModes() throws Exception {
        // an older version saved every display as on or off: on reads as its default, or elytra only if its
        // default is off; off reads as off
        load("""
            {"displaySpeed": true, "displayFlightPathVector": true, "displayAltitude": false, "displayHeadingArc": "always"}
            """);
        assertEquals(Show.FLIGHT.ordinal(), Settings.DISPLAY_SPEED.value());               // default in flight
        assertEquals(Show.ELYTRA.ordinal(), Settings.DISPLAY_FLIGHT_PATH_VECTOR.value());  // default off
        assertEquals(Show.OFF.ordinal(), Settings.DISPLAY_ALTITUDE.value());
        assertEquals(Show.ALWAYS.ordinal(), Settings.DISPLAY_HEADING_ARC.value());         // today's format
        assertEquals(Show.ALWAYS, Settings.displayHeadingArc);
        // keys the file doesn't have keep their defaults
        assertEquals(Show.FLIGHT.ordinal(), Settings.DISPLAY_GROUND_SPEED.value());
        assertEquals(1, Settings.TERRAIN_AVOIDANCE.value());
    }

    @Test
    void togglesAndSwitches() throws Exception {
        load("""
            {"enabled": true, "sectionCombat": false, "autopilot": false, "autoAim": true}
            """);
        assertEquals(0, Settings.AUTOPILOT.value());
        assertFalse(Settings.autopilot);
        // a feature in a section switched off is off, its own setting kept
        assertEquals(1, Settings.AUTO_AIM.value());
        assertFalse(Settings.autoAim);
        assertFalse(Settings.projectileReticle);
        assertFalse(Settings.sectionOn(Settings.Section.COMBAT));
        assertTrue(Settings.takeoff);
        load("""
            {"enabled": false}
            """);
        assertFalse(Settings.takeoff);
        assertEquals(1, Settings.TAKEOFF.value());
    }

    @Test
    void aMalformedFileOrValueChangesNothing() throws Exception {
        load("""
            {"autopilot": false, "displaySpeed": "always"}
            """);
        load("{\"autopilot\": tru");
        assertEquals(0, Settings.AUTOPILOT.value());
        load("""
            {"displaySpeed": "sideways", "autopilot": 3, "takeoff": {"on": false}}
            """);
        assertEquals(Show.ALWAYS.ordinal(), Settings.DISPLAY_SPEED.value());
        assertEquals(0, Settings.AUTOPILOT.value());
        assertEquals(1, Settings.TAKEOFF.value());
    }

    @Test
    void whatIsSavedReadsBackTheSame() throws Exception {
        load("""
            {"displaySpeed": true, "displayFlightPathVector": true, "autopilot": false, "sectionHud": false}
            """);
        int[] before = Settings.ALL.stream().mapToInt(Settings.Option::value).toArray();
        Settings.save();
        String saved = Files.readString(file);
        assertTrue(saved.contains("\"displayFlightPathVector\": \"elytra\""), saved);
        Settings.resetDefaults();   // (saves the defaults over it)
        load(saved);
        assertArrayEquals(before, Settings.ALL.stream().mapToInt(Settings.Option::value).toArray());
        assertFalse(Settings.sectionOn(Settings.Section.HUD));
    }
    @Test
    void combatMovementIsOffByDefaultAndItsSliderSavesAsANumber() throws Exception {
        assertFalse(Settings.combatMovement, "a cheat: off by default");
        assertEquals(25, Settings.COMBAT_BIAS.value(), "leaning to survive");
        assertTrue(Settings.COMBAT_BIAS.isLevel());
        Settings.COMBAT_BIAS.setLevel(70);
        String saved = Files.readString(file);
        assertTrue(saved.contains("\"combatMovementBias\": 70"), saved);
        assertTrue(saved.contains("\"combatMovement\": false"), saved);
        load("""
            {"combatMovement": true, "combatMovementBias": 140}
            """);
        assertTrue(Settings.combatMovement);
        assertEquals(100, Settings.combatBias, "clamped to the slider's range");
        Settings.resetDefaults();
        load("""
            {"combatMovementBias": "lots"}
            """);
        assertEquals(25, Settings.combatBias, "a value that isn't a number keeps the default");
    }

    @Test
    void walkingSectionDefaultsAndSwitches() throws Exception {
        // the pathfinder on (it acts only when started from the Walk tab) and its path display on; both layer 1 search
        // displays off (RW 2026-10-13: "have both eh alyer 1 ones off and layer 2 on")
        assertTrue(Settings.walkPathfinder);
        assertTrue(Settings.walkPathDisplay);
        assertFalse(Settings.walkSearchDisplay);
        assertFalse(Settings.walkSearchDetailed);
        assertEquals(20, Settings.walkMargin, "0.20 blocks");
        // layer 2 (the movement search) and its display on: it presses only the game's own keys
        assertTrue(Settings.walkLayer2);
        assertTrue(Settings.walkLayer2Display);
        // elytra hops off: they wear the elytra (RW's scope ruling: "hops off by default")
        assertFalse(Settings.walkElytraHops);
        assertTrue(Settings.WALK_MARGIN.isLevel());
        for (Settings.Option o : new Settings.Option[]{Settings.WALK_PATHFINDER, Settings.WALK_PATH_DISPLAY,
                Settings.WALK_SEARCH_DISPLAY, Settings.WALK_MARGIN, Settings.WALK_LAYER2, Settings.WALK_LAYER2_DISPLAY,
                Settings.WALK_ELYTRA_HOPS})
            assertEquals(Settings.Section.WALK, o.section);
        // the section switch and the all-features switch reach every one, each keeping its own setting
        Settings.setSectionOn(Settings.Section.WALK, false);
        assertFalse(Settings.walkPathfinder);
        assertFalse(Settings.walkPathDisplay);
        assertFalse(Settings.walkSearchDisplay);
        assertFalse(Settings.walkLayer2);
        assertEquals(0, Settings.walkMargin);
        assertEquals(1, Settings.WALK_PATHFINDER.value());
        Settings.setSectionOn(Settings.Section.WALK, true);
        assertTrue(Settings.walkPathfinder);
        Settings.setEnabled(false);
        assertFalse(Settings.walkPathfinder);
        assertFalse(Settings.walkSearchDisplay);
        Settings.setEnabled(true);
        // saved and read back
        Settings.WALK_MARGIN.setLevel(35);
        Settings.WALK_SEARCH_DISPLAY.cycle();
        String saved = Files.readString(file);
        assertTrue(saved.contains("\"walkSafetyMargin\": 35"), saved);
        assertTrue(saved.contains("\"walkSearchDisplay\": true"), saved);
        assertTrue(saved.contains("\"sectionWalk\": true"), saved);
        Settings.resetDefaults();
        load(saved);
        assertEquals(35, Settings.walkMargin);
        assertTrue(Settings.walkSearchDisplay);
    }
}
