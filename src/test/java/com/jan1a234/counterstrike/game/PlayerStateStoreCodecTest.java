package com.jan1a234.counterstrike.game;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

class PlayerStateStoreCodecTest {
    @org.junit.jupiter.api.BeforeAll
    static void detectGameVersion() {
        net.minecraft.SharedConstants.tryDetectVersion();
    }

    final UUID id = UUID.fromString("808993a8-bfbe-4bd6-801d-132ca12b8642");

    @Test
    void snapshotPayloadRoundTripsWithoutLoss() throws IOException {
        CompoundTag state = new CompoundTag();
        state.putString("dimension", "minecraft:overworld");
        state.putDouble("x", -131.25);
        state.putInt("selected", 7);
        assertEquals(state, PlayerStateStore.unpack(id, PlayerStateStore.pack(id, state)));
    }

    @Test
    void anotherPlayersSnapshotIsRejected() throws IOException {
        byte[] bytes = PlayerStateStore.pack(id, new CompoundTag());
        assertThrows(IOException.class, () -> PlayerStateStore.unpack(UUID.randomUUID(), bytes));
    }

    @Test
    void validNbtWithAlteredPayloadIsRejectedByChecksum() throws IOException {
        byte[] bytes = PlayerStateStore.pack(id, new CompoundTag());
        CompoundTag envelope =
                NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        byte[] payload = envelope.getByteArray("payload").orElseThrow();
        payload[payload.length - 1] ^= 1;
        envelope.putByteArray("payload", payload);
        var output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(envelope, output);
        assertThrows(IOException.class, () -> PlayerStateStore.unpack(id, output.toByteArray()));
    }

    @Test
    void corruptOrTruncatedBackupIsRejected() throws IOException {
        byte[] bytes = PlayerStateStore.pack(id, new CompoundTag());
        assertThrows(IOException.class, () -> PlayerStateStore.unpack(id, new byte[] {1, 2, 3}));
        assertThrows(
                IOException.class,
                () ->
                        PlayerStateStore.unpack(
                                id, java.util.Arrays.copyOf(bytes, bytes.length / 2)));
    }

    @Test
    void unsupportedSnapshotVersionIsRejected() throws IOException {
        byte[] bytes = PlayerStateStore.pack(id, new CompoundTag());
        CompoundTag envelope =
                NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        envelope.putInt("version", 999);
        var output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(envelope, output);
        assertThrows(IOException.class, () -> PlayerStateStore.unpack(id, output.toByteArray()));
    }
}
