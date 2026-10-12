package ai.rrw.kine;

import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.autoflight.ElytraGuard;
import ai.rrw.kine.autoflight.FlightDirector;
import ai.rrw.kine.autoflight.FlightPathRails;
import ai.rrw.kine.autoflight.Landing;
import ai.rrw.kine.autoflight.Nav;
import ai.rrw.kine.autoflight.PathPrediction;
import ai.rrw.kine.autoflight.Takeoff;
import ai.rrw.kine.combat.AfkGuard;
import ai.rrw.kine.combat.AutoTotem;
import ai.rrw.kine.combat.KillAura;
import ai.rrw.kine.combat.ProjectileTargeting;
import ai.rrw.kine.combat.WaterClutch;
import ai.rrw.kine.util.InventoryClicks;
import ai.rrw.kine.combat.WeaponSelect;
import ai.rrw.kine.fight.CombatMovement;
import ai.rrw.kine.fireworks.FireworkFuel;
import ai.rrw.kine.hud.FlightDirectorHud;
import ai.rrw.kine.hud.FlightPathVector;
import ai.rrw.kine.hud.FlightProfile;
import ai.rrw.kine.hud.MiningHud;
import ai.rrw.kine.hud.MobHealthHud;
import ai.rrw.kine.hud.NavBeam;
import ai.rrw.kine.hud.NavHud;
import ai.rrw.kine.hud.RadioAltimeter;
import ai.rrw.kine.hud.RangeEndurance;
import ai.rrw.kine.hud.ThinOutlines;
import ai.rrw.kine.hud.VelocityHud;
import ai.rrw.kine.ui.KineMenu;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Kine implements ClientModInitializer {
    public static final String MOD_ID = "kine";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Keybind category, so Kine's keys get their own section in the controls screen. Registered at
    // class load, before the features create their keys; the title is "key.category.kine.keybinds" in
    // the lang file.
    public static final KeyMapping.Category KEY_CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "keybinds"));

    @Override
    public void onInitializeClient() {
        Settings.load();
        ThinOutlines.register();
        VelocityHud.register();
        MobHealthHud.register();
        MiningHud.register();
        FlightDirector.register();
        FlightDirectorHud.register();
        Takeoff.register();               // before the rails, which draw its path
        Landing.register();
        PathPrediction.register();
        NavBeam.registerFaint();          // before the rails, which draw over its faint copy
        FlightPathRails.register();
        NavBeam.register();               // after the rails, so they hide it where they are nearer
        FlightPathVector.register();
        FlightProfile.register();
        RadioAltimeter.register();
        RangeEndurance.register();
        FireworkFuel.register();
        Autopilot.register();
        Nav.register();
        NavHud.register();
        ElytraGuard.register();
        InventoryClicks.register();
        WaterClutch.register();
        AutoTotem.register();             // after the clutch: each asks for the inventory at the end of a tick, in this order
        AfkGuard.register();
        ProjectileTargeting.register();
        WeaponSelect.register();
        CombatMovement.register();
        KineMenu.register();
    }
}
