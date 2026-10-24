package com.jan1a234.counterstrike.game;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

/**
 * Checked disk transactions, independent of Minecraft so failure paths can be exercised in tests.
 */
final class PlayerStateStoreFiles {
    static final int MAX_BYTES = 64 * 1024 * 1024;
    // The server owns the world's session lock. This also serializes separate stores in the same
    // JVM.
    private static final Object WRITE_LOCK = new Object();
    private final Path backups;
    private final Path players;

    PlayerStateStoreFiles(Path world) {
        backups = world.resolve("counterstrike-player-backups");
        players =
                world.resolve(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR.id());
    }

    boolean has(UUID id) {
        // Unknown/inaccessible is NOT permission to overwrite an existing recovery snapshot.
        return !Files.notExists(backup(id), LinkOption.NOFOLLOW_LINKS);
    }

    byte[] read(UUID id) throws IOException {
        return readBounded(backup(id));
    }

    void capture(UUID id, byte[] bytes) throws IOException {
        synchronized (WRITE_LOCK) {
            if (has(id)) throw new IOException("Unrecovered player snapshot already exists: " + id);
            atomicWrite(backup(id), bytes);
        }
    }

    void complete(UUID id, byte[] playerData) throws IOException {
        synchronized (WRITE_LOCK) {
            // Do not turn a missing/inaccessible backup into an unchecked player-data replacement.
            read(id);
            Path destination = players.resolve(id + ".dat");
            atomicWrite(destination, playerData);
            if (!Arrays.equals(playerData, readBounded(destination))) {
                throw new IOException(
                        "Player data verification failed; recovery snapshot retained: " + id);
            }
            Files.delete(backup(id));
            forceDirectory(backups);
        }
    }

    private Path backup(UUID id) {
        return backups.resolve(id + ".nbt");
    }

    private static byte[] readBounded(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a regular state file: " + path);
        }
        if (Files.size(path) > MAX_BYTES)
            throw new IOException("Player state file too large: " + path);
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES)
                throw new IOException("Player state file too large: " + path);
            return bytes;
        }
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IOException("Invalid player state size");
        Files.createDirectories(target.getParent());
        Path temporary =
                Files.createTempFile(target.getParent(), target.getFileName() + "-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No unsafe copy fallback: failure to support atomic replacement blocks the operation.
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(target.getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void forceDirectory(Path directory) {
        // Unix supports directory fsync; Windows commonly denies opening directories as channels.
        // Atomic rename + forced contents protect JVM crashes. Absolute power-loss guarantees are
        // filesystem/platform dependent and are deliberately NOT claimed on unsupported platforms.
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // The atomic move remains mandatory; only the extra directory flush is best effort.
        }
    }
}
