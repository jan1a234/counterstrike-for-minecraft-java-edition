package com.jan1a234.counterstrike.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jan1a234.counterstrike.CounterStrikeMod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Einstellungen der Map: Spawnpunkte pro Team, Laufzeit und Bot-Anzahl.
 * Wird als counterstrike.json im Weltordner gespeichert, damit sie zur Map gehoeren.
 */
public class ArenaConfig {
	public static final int[] ALLOWED_DURATIONS = {15, 30, 120};

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public List<SpawnPoint> ctSpawns = new ArrayList<>();
	public List<SpawnPoint> tSpawns = new ArrayList<>();
	public int durationMinutes = 15;
	public int ctBots = 0;
	public int tBots = 0;

	private transient Path file;

	public List<SpawnPoint> spawns(CsTeam team) {
		return team == CsTeam.CT ? ctSpawns : tSpawns;
	}

	public int bots(CsTeam team) {
		return team == CsTeam.CT ? ctBots : tBots;
	}

	public void setBots(CsTeam team, int count) {
		if (team == CsTeam.CT) {
			ctBots = count;
		} else {
			tBots = count;
		}
	}

	public static ArenaConfig load(Path file) {
		ArenaConfig config = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				config = GSON.fromJson(reader, ArenaConfig.class);
			} catch (IOException | RuntimeException e) {
				CounterStrikeMod.LOGGER.error("Konnte {} nicht lesen, nutze Standardwerte", file, e);
			}
		}
		if (config == null) {
			config = new ArenaConfig();
		}
		if (config.ctSpawns == null) {
			config.ctSpawns = new ArrayList<>();
		}
		if (config.tSpawns == null) {
			config.tSpawns = new ArrayList<>();
		}
		config.file = file;
		return config;
	}

	public void save() {
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(this, writer);
		} catch (IOException e) {
			CounterStrikeMod.LOGGER.error("Konnte {} nicht speichern", file, e);
		}
	}
}
