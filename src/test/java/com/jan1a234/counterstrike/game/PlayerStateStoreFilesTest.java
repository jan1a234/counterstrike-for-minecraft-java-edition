package com.jan1a234.counterstrike.game;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

class PlayerStateStoreFilesTest {
    @TempDir Path world;
    final UUID id = UUID.fromString("808993a8-bfbe-4bd6-801d-132ca12b8642");

    @Test
    void captureSurvivesNewStoreAndCannotOverwriteRecovery() throws IOException {
        var store = new PlayerStateStoreFiles(world);
        byte[] original = {1, 2, 3, 4};
        store.capture(id, original);
        var reopened = new PlayerStateStoreFiles(world);
        assertTrue(reopened.has(id));
        assertArrayEquals(original, reopened.read(id));
        assertThrows(IOException.class, () -> reopened.capture(id, new byte[] {9}));
        assertArrayEquals(original, reopened.read(id));
    }

    @Test
    void failedPlayerDataCommitRetainsOriginalBackup() throws IOException {
        var store = new PlayerStateStoreFiles(world);
        byte[] original = {1, 2, 3};
        store.capture(id, original);
        Files.createDirectories(
                world.resolve(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR.id())
                        .resolve(id + ".dat"));
        assertThrows(IOException.class, () -> store.complete(id, new byte[] {4, 5}));
        assertTrue(store.has(id));
        assertArrayEquals(original, store.read(id));
    }

    @Test
    void successfulCommitPersistsPlayerBeforeRemovingSnapshot() throws IOException {
        var store = new PlayerStateStoreFiles(world);
        store.capture(id, new byte[] {1, 2, 3});
        byte[] restored = {4, 5, 6, 7};
        store.complete(id, restored);
        assertArrayEquals(
                restored,
                Files.readAllBytes(
                        world.resolve(
                                        net.minecraft.world.level.storage.LevelResource
                                                .PLAYER_DATA_DIR
                                                .id())
                                .resolve(id + ".dat")));
        assertFalse(new PlayerStateStoreFiles(world).has(id));
    }

    @Test
    void abandonedTemporaryWriteIsNotARecoverySnapshot() throws IOException {
        Path directory = world.resolve("counterstrike-player-backups");
        Files.createDirectories(directory);
        Files.write(directory.resolve(id + ".nbt-incomplete.tmp"), new byte[] {1});
        var store = new PlayerStateStoreFiles(world);
        assertFalse(store.has(id));
        store.capture(id, new byte[] {2});
        assertArrayEquals(new byte[] {2}, store.read(id));
    }

    @Test
    void unreadableBackupEntryFailsClosedInsteadOfBeingOverwritten() throws IOException {
        Path entry = world.resolve("counterstrike-player-backups").resolve(id + ".nbt");
        Files.createDirectories(entry);
        var store = new PlayerStateStoreFiles(world);
        assertTrue(store.has(id));
        assertThrows(IOException.class, () -> store.read(id));
        assertThrows(IOException.class, () -> store.capture(id, new byte[] {1}));
        assertTrue(Files.isDirectory(entry));
    }

    @Test
    void concurrentStoresCannotReplaceTheWinningSnapshot() throws Exception {
        var first = new PlayerStateStoreFiles(world);
        var second = new PlayerStateStoreFiles(world);
        var start = new java.util.concurrent.CountDownLatch(1);
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    first.capture(id, new byte[] {1});
                                    successes.incrementAndGet();
                                } catch (IOException expected) {
                                    failures.incrementAndGet();
                                }
                                return null;
                            });
            var two =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    second.capture(id, new byte[] {2});
                                    successes.incrementAndGet();
                                } catch (IOException expected) {
                                    failures.incrementAndGet();
                                }
                                return null;
                            });
            start.countDown();
            one.get();
            two.get();
        }
        assertEquals(1, successes.get());
        assertEquals(1, failures.get());
        byte[] snapshot = first.read(id);
        assertEquals(1, snapshot.length);
        assertTrue(snapshot[0] == 1 || snapshot[0] == 2);
    }

    @Test
    void successfulCommitReplacesExistingArenaData() throws IOException {
        Path destination =
                world.resolve(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR.id())
                        .resolve(id + ".dat");
        Files.createDirectories(destination.getParent());
        Files.write(destination, new byte[] {99});
        var store = new PlayerStateStoreFiles(world);
        store.capture(id, new byte[] {1});
        store.complete(id, new byte[] {2, 3});
        assertArrayEquals(new byte[] {2, 3}, Files.readAllBytes(destination));
        assertFalse(store.has(id));
        try (var files = Files.list(destination.getParent())) {
            assertEquals(1, files.count(), "No abandoned temporary writes after success");
        }
    }

    @Test
    void missingBackupCannotCommitAndReplacePlayerData() throws IOException {
        var store = new PlayerStateStoreFiles(world);
        assertThrows(IOException.class, () -> store.complete(id, new byte[] {9}));
        assertFalse(
                Files.exists(
                        world.resolve(
                                        net.minecraft.world.level.storage.LevelResource
                                                .PLAYER_DATA_DIR
                                                .id())
                                .resolve(id + ".dat")));
    }
}
