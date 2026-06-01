package ai.rrw.kine;

import net.fabricmc.api.ClientModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.rrw.kine.hud.VelocityHud;
import ai.rrw.kine.hud.MobHealthHud;
import ai.rrw.kine.hud.MiningHud;
import ai.rrw.kine.hud.FlightDirector;
import ai.rrw.kine.hud.RadioAltimeter;

public class Kine implements ClientModInitializer {
	public static final String MOD_ID = "kine";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		VelocityHud.register();
		MobHealthHud.register();
		MiningHud.register();
		FlightDirector.register();
		RadioAltimeter.register();
	}
}
