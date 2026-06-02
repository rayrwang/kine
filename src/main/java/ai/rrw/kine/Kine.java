package ai.rrw.kine;

import net.fabricmc.api.ClientModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.rrw.kine.hud.VelocityHud;
import ai.rrw.kine.hud.MobHealthHud;
import ai.rrw.kine.hud.MiningHud;
import ai.rrw.kine.hud.RadioAltimeter;
import ai.rrw.kine.hud.RangeEndurance;
import ai.rrw.kine.autoflight.FlightDirector;
import ai.rrw.kine.autoflight.Autopilot;
import ai.rrw.kine.autoflight.Nav;
import ai.rrw.kine.autoflight.ElytraGuard;
import ai.rrw.kine.combat.ProjectileTargeting;
import ai.rrw.kine.combat.ProjectileDodge;
import ai.rrw.kine.combat.WaterClutch;

public class Kine implements ClientModInitializer {
	public static final String MOD_ID = "kine";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		Settings.load();
		VelocityHud.register();
		MobHealthHud.register();
		MiningHud.register();
		FlightDirector.register();
		RadioAltimeter.register();
		RangeEndurance.register();
		Autopilot.register();
		Nav.register();
		ElytraGuard.register();
		WaterClutch.register();
		ai.rrw.kine.combat.AfkGuard.register();
		ProjectileTargeting.register();
		ProjectileDodge.register();
		ai.rrw.kine.ui.KineMenu.register();
	}
}
