package com.jan1a234.counterstrike.game;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class ArenaConfigTest {
    @TempDir Path directory;

    @Test
    void malformedSettingsCannotOverflowTimerOrFloodServerWithBots() throws Exception {
        Path file = directory.resolve("counterstrike.json");
        Files.writeString(file, "{\"durationMinutes\":2147483647,\"ctBots\":-4,\"tBots\":1000000}");
        ArenaConfig config = ArenaConfig.load(file);
        assertEquals(15, config.durationMinutes);
        assertEquals(0, config.ctBots);
        assertEquals(16, config.tBots);
    }

    @Test
    void brokenSpawnEntriesAreRejectedInsteadOfCrashingMatchStart() throws Exception {
        Path file = directory.resolve("counterstrike.json");
        Files.writeString(
                file,
                "{\"ctSpawns\":[null,{\"dimension\":null},{\"dimension\":\"minecraft:overworld\",\"x\":1e309}]}");
        assertTrue(ArenaConfig.load(file).ctSpawns.isEmpty());
    }

    @Test
    void existingValidConfigurationRoundTrips() throws Exception {
        Path file = directory.resolve("counterstrike.json");
        Files.writeString(file, "{\"durationMinutes\":30,\"ctBots\":2,\"tBots\":5}");
        ArenaConfig config = ArenaConfig.load(file);
        config.save();
        ArenaConfig restored = ArenaConfig.load(file);
        assertEquals(30, restored.durationMinutes);
        assertEquals(2, restored.ctBots);
        assertEquals(5, restored.tBots);
    }
}
