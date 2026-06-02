package ai.rrw.kine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Settings {
    public static boolean displaySpeed           = true;
    public static boolean displayGroundSpeed     = true;
    public static boolean displayFlightDirectors = true;
    public static boolean elytraDuraFailsafe     = true;
    public static boolean crashProtection        = true;
    public static boolean fallPrevention         = true;
    public static boolean displayMobHealths      = true;
    public static boolean displayMobNames        = false;
    public static boolean projectileReticle      = true;
    public static boolean projectileGlow         = true;
    public static boolean autoAim                = false;
    public static boolean projectileDodge        = false;
    public static boolean displayRangeEndurance  = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("kine.json");

    private static class Data {
        boolean displaySpeed = true, displayGroundSpeed = true, displayFlightDirectors = true,
                elytraDuraFailsafe = true, crashProtection = true, fallPrevention = true,
                displayMobHealths = true, displayMobNames = false, projectileReticle = true,
                projectileGlow = true, autoAim = false, projectileDodge = false, displayRangeEndurance = true;
    }

    public static void load() {
        try {
            if (!Files.exists(FILE)) { save(); return; }
            Data d = GSON.fromJson(Files.readString(FILE), Data.class);
            if (d == null) return;
            displaySpeed = d.displaySpeed;
            displayGroundSpeed = d.displayGroundSpeed;
            displayFlightDirectors = d.displayFlightDirectors;
            elytraDuraFailsafe = d.elytraDuraFailsafe;
            crashProtection = d.crashProtection;
            fallPrevention = d.fallPrevention;
            displayMobHealths = d.displayMobHealths;
            displayMobNames = d.displayMobNames;
            projectileReticle = d.projectileReticle;
            projectileGlow = d.projectileGlow;
            autoAim = d.autoAim;
            projectileDodge = d.projectileDodge;
            displayRangeEndurance = d.displayRangeEndurance;
        } catch (Exception e) {
            Kine.LOGGER.warn("kine: could not load config, using defaults", e);
        }
    }

    public static void save() {
        Data d = new Data();
        d.displaySpeed = displaySpeed;
        d.displayGroundSpeed = displayGroundSpeed;
        d.displayFlightDirectors = displayFlightDirectors;
        d.elytraDuraFailsafe = elytraDuraFailsafe;
        d.crashProtection = crashProtection;
        d.fallPrevention = fallPrevention;
        d.displayMobHealths = displayMobHealths;
        d.displayMobNames = displayMobNames;
        d.projectileReticle = projectileReticle;
        d.projectileGlow = projectileGlow;
        d.autoAim = autoAim;
        d.projectileDodge = projectileDodge;
        d.displayRangeEndurance = displayRangeEndurance;
        try {
            Files.writeString(FILE, GSON.toJson(d));
        } catch (IOException e) {
            Kine.LOGGER.warn("kine: could not save config", e);
        }
    }
}
