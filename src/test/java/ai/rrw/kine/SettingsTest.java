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
        assertTrue(saved.contains("\"displaySpeed\": \"elytra\""), saved);
        assertTrue(saved.contains("\"autoAim\": false"), saved);
    }

    @Test
    void displaysSavedAsBooleansReadAsModes() throws Exception {
        // an older version saved every display as on or off: on reads as its default, or elytra only if its
        // default is off; off reads as off
        load("""
            {"displaySpeed": true, "displayFlightPathVector": true, "displayAltitude": false, "displayHeadingArc": "always"}
            """);
        assertEquals(Show.ELYTRA.ordinal(), Settings.DISPLAY_SPEED.value());               // default elytra only
        assertEquals(Show.ELYTRA.ordinal(), Settings.DISPLAY_FLIGHT_PATH_VECTOR.value());  // default off
        assertEquals(Show.OFF.ordinal(), Settings.DISPLAY_ALTITUDE.value());
        assertEquals(Show.ALWAYS.ordinal(), Settings.DISPLAY_HEADING_ARC.value());         // today's format
        assertEquals(Show.ALWAYS, Settings.displayHeadingArc);
        // keys the file doesn't have keep their defaults
        assertEquals(Show.ELYTRA.ordinal(), Settings.DISPLAY_GROUND_SPEED.value());
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
}
