package com.jan1a234.counterstrike.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jan1a234.counterstrike.CounterStrikeMod;
import com.jan1a234.counterstrike.bot.BotDifficulty;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Einstellungen der Map: Spawnpunkte pro Team, Laufzeit und Bot-Anzahl. Wird als counterstrike.json
 * im Weltordner gespeichert, damit sie zur Map gehoeren.
 */
public class ArenaConfig {
    public static final int[] ALLOWED_DURATIONS = {15, 30, 120};

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public List<SpawnPoint> ctSpawns = new ArrayList<>();
    public List<SpawnPoint> tSpawns = new ArrayList<>();
    public String arena = "harbor";
    public int durationMinutes = 15;
    public int ctBots = 0;
    public int tBots = 0;
    public String botDifficulty = "normal";

    public BotDifficulty botDifficulty() {
        BotDifficulty difficulty = BotDifficulty.byId(botDifficulty == null ? "" : botDifficulty);
        return difficulty == null ? BotDifficulty.NORMAL : difficulty;
    }

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
                com.google.gson.JsonObject json =
                        GSON.fromJson(reader, com.google.gson.JsonObject.class);
                config = GSON.fromJson(json, ArenaConfig.class);
                if (config != null
                        && !json.has("arena")
                        && ((config.ctSpawns != null && !config.ctSpawns.isEmpty())
                                || (config.tSpawns != null && !config.tSpawns.isEmpty())))
                    config.arena = "custom";
            } catch (IOException | RuntimeException e) {
                CounterStrikeMod.LOGGER.error(
                        "Konnte {} nicht lesen, nutze Standardwerte", file, e);
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
        config.ctSpawns.removeIf(spawn -> !validSpawn(spawn));
        config.tSpawns.removeIf(spawn -> !validSpawn(spawn));
        int duration = config.durationMinutes;
        if (java.util.Arrays.stream(ALLOWED_DURATIONS).noneMatch(n -> n == duration))
            config.durationMinutes = 15;
        config.ctBots = Math.clamp(config.ctBots, 0, 16);
        config.tBots = Math.clamp(config.tBots, 0, 16);
        if (!"custom".equals(config.arena)) config.arena = "harbor";
        config.file = file;
        return config;
    }

    public static boolean validSpawn(SpawnPoint spawn) {
        return spawn != null
                && spawn.dimension != null
                && !spawn.dimension.isBlank()
                && Double.isFinite(spawn.x)
                && Double.isFinite(spawn.y)
                && Double.isFinite(spawn.z)
                && Math.abs(spawn.x) < 29999984
                && Math.abs(spawn.z) < 29999984
                && Float.isFinite(spawn.yaw)
                && Float.isFinite(spawn.pitch);
    }

    public void save() {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            CounterStrikeMod.LOGGER.error("Konnte {} nicht speichern", file, e);
        }
    }
}
