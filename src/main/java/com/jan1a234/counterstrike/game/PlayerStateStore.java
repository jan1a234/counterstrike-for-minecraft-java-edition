package com.jan1a234.counterstrike.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Survival-state transaction owned by one server. Call capture BEFORE any arena/team mutation, and
 * restore on reconnect BEFORE admitting the player to a new match. All public operations except has
 * must run on the server thread. Never call Entity.load on a connected player.
 *
 * <p>A backup from an earlier store/session blocks capture until restored. Repeated capture on this
 * instance is idempotent only while the exact original backup is still on disk. Any restore error
 * retains the backup; callers must keep that player out of the minigame.
 */
public final class PlayerStateStore {
    private static final int FORMAT = 1;
    private final MinecraftServer server;
    private final PlayerStateStoreFiles files;
    private final Map<UUID, byte[]> capturedHere = new HashMap<>();

    public PlayerStateStore(MinecraftServer server) {
        this.server = server;
        this.files = new PlayerStateStoreFiles(server.getWorldPath(LevelResource.ROOT));
    }

    public boolean has(UUID id) {
        return files.has(id);
    }

    public void capture(ServerPlayer player) throws IOException {
        requireServerThread();
        UUID id = player.getUUID();
        byte[] previous = capturedHere.get(id);
        if (previous != null) {
            if (!Arrays.equals(previous, files.read(id))) {
                throw new IOException(
                        "Recovery snapshot changed on disk; refusing arena entry: " + id);
            }
            return;
        }
        if (files.has(id))
            throw new IOException("Restore the existing recovery snapshot before joining: " + id);
        if (!player.isAlive() || player.isRemoved())
            throw new IOException("Cannot capture a dead/removed player");
        // Cursor/crafting stacks are not saved by vanilla PlayerDataStorage. Do not silently lose
        // them or close a container (which can drop items) before the durable snapshot exists.
        if (player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()
                || !player.inventoryMenu.getCraftSlots().isEmpty()) {
            throw new IOException(
                    "Close containers and empty the cursor/crafting grid before joining");
        }
        try {
            CompoundTag state = snapshot(player);
            byte[] bytes = pack(id, state);
            // Exercise the exact bounded disk decoder, not merely the in-memory tag.
            decode(unpack(id, bytes), player);
            files.capture(id, bytes);
            capturedHere.put(id, bytes);
        } catch (RuntimeException exception) {
            throw new IOException(
                    "Could not serialize survival state; player was not changed", exception);
        }
    }

    /**
     * @return false only if no backup exists; true only after restored player data is committed.
     */
    public boolean restore(ServerPlayer player) throws IOException {
        requireServerThread();
        UUID id = player.getUUID();
        if (!files.has(id)) return false;
        // Once recovery begins, a failed attempt must never permit idempotent arena re-entry.
        // Only a successful restore followed by a fresh capture can authorize another match.
        capturedHere.remove(id);
        try {
            // Every item/effect and mandatory scalar is decoded BEFORE clearing anything.
            Decoded saved = decode(unpack(id, files.read(id)), player);
            if (!player.isAlive() || player.isRemoved()) {
                throw new IOException(
                        "Recovery requires the live respawned player; snapshot retained");
            }
            CompoundTag state = saved.state();
            player.stopRiding();
            player.stopUsingItem();
            // Teleport first: a failed teleport must not replace the current inventory.
            if (!player.teleportTo(
                    saved.level(),
                    number(state, "x"),
                    number(state, "y"),
                    number(state, "z"),
                    Set.of(),
                    decimal(state, "yaw"),
                    decimal(state, "pitch"),
                    true)) {
                throw new IOException("Recovery teleport failed; snapshot retained");
            }
            player.containerMenu.setCarried(ItemStack.EMPTY);
            player.inventoryMenu.getCraftSlots().clearContent();
            player.closeContainer();
            Loadout.reset(
                    player); // Remove arena attribute modifiers before restoring health/effects.
            player.removeAllEffects();
            player.getInventory().clearContent();
            for (int slot = 0; slot < saved.inventory().size(); slot++) {
                player.getInventory().setItem(slot, saved.inventory().get(slot).copy());
            }
            for (var entry : saved.equipment().entrySet())
                player.setItemSlot(entry.getKey(), entry.getValue().copy());
            player.getInventory().setSelectedSlot(integer(state, "selected"));
            player.setGameMode(saved.mode());
            player.getAbilities().apply(saved.abilities());
            player.onUpdateAbilities();
            for (MobEffectInstance effect : saved.effects())
                player.addEffect(new MobEffectInstance(effect));
            var problems = new ProblemReporter.Collector();
            player.getFoodData()
                    .readAdditionalSaveData(
                            TagValueInput.create(
                                    problems, player.registryAccess(), compound(state, "food")));
            checkProblems(problems);
            player.experienceLevel = integer(state, "xpLevel");
            player.totalExperience = integer(state, "xpTotal");
            player.experienceProgress = decimal(state, "xpProgress");
            player.setHealth(decimal(state, "health"));
            player.setAbsorptionAmount(decimal(state, "absorption"));
            player.setAirSupply(integer(state, "air"));
            player.setRemainingFireTicks(integer(state, "fire"));
            player.setTicksFrozen(integer(state, "frozen"));
            player.resetFallDistance();
            player.setDeltaMovement(Vec3.ZERO);
            player.invulnerableTime = 0;
            player.hurtTime = 0;
            var scoreboard = server.getScoreboard();
            scoreboard.removePlayerFromTeam(player.getScoreboardName());
            if (saved.team() != null)
                scoreboard.addPlayerToTeam(player.getScoreboardName(), saved.team());
            player.getInventory().setChanged();
            player.inventoryMenu.sendAllDataToRemote();
            player.resetSentInfo();
            // Unusual equipment-driven max-health updates can be delayed until the next tick.
            // Retain recovery rather than silently commit a clamped health/absorption value.
            if (Float.compare(player.getHealth(), decimal(state, "health")) != 0
                    || Float.compare(player.getAbsorptionAmount(), decimal(state, "absorption"))
                            != 0) {
                throw new IOException(
                        "Health attributes have not settled; retry recovery after a tick");
            }
            // Vanilla PlayerDataStorage.save catches/logs failures and returns void. It cannot
            // authorize deletion of our recovery snapshot. Write the same full player NBT using
            // a checked atomic transaction instead; this SAVES but never LOADS entity internals.
            var output = TagValueOutput.createWithContext(problems, player.registryAccess());
            player.saveWithoutId(output);
            checkProblems(problems);
            files.complete(id, compressed(output.buildResult()));
            capturedHere.remove(id);
            return true;
        } catch (RuntimeException exception) {
            throw new IOException("Player recovery failed; original snapshot retained", exception);
        }
    }

    private CompoundTag snapshot(ServerPlayer player) throws IOException {
        DynamicOps<Tag> ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        CompoundTag state = new CompoundTag();
        var inventory = player.getInventory().getNonEquipmentItems();
        CompoundTag items = new CompoundTag();
        items.putInt("size", inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++)
            items.put("slot" + slot, encodeStack(inventory.get(slot), ops));
        state.put("inventory", items);
        CompoundTag equipment = new CompoundTag();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot != EquipmentSlot.MAINHAND)
                equipment.put(slot.getName(), encodeStack(player.getItemBySlot(slot), ops));
        }
        state.put("equipment", equipment);
        state.putInt("selected", player.getInventory().getSelectedSlot());
        state.putString("dimension", player.level().dimension().identifier().toString());
        state.putDouble("x", player.getX());
        state.putDouble("y", player.getY());
        state.putDouble("z", player.getZ());
        state.putFloat("yaw", player.getYRot());
        state.putFloat("pitch", player.getXRot());
        state.putInt("mode", player.gameMode().getId());
        state.put("abilities", encode(Abilities.Packed.CODEC, ops, player.getAbilities().pack()));
        state.putFloat("health", player.getHealth());
        state.putFloat("absorption", player.getAbsorptionAmount());
        state.putInt("air", player.getAirSupply());
        state.putInt("fire", player.getRemainingFireTicks());
        state.putInt("frozen", player.getTicksFrozen());
        state.putInt("xpLevel", player.experienceLevel);
        state.putInt("xpTotal", player.totalExperience);
        state.putFloat("xpProgress", player.experienceProgress);
        state.put(
                "effects",
                encode(
                        MobEffectInstance.CODEC.listOf(),
                        ops,
                        List.copyOf(player.getActiveEffects())));
        var problems = new ProblemReporter.Collector();
        var food = TagValueOutput.createWithContext(problems, player.registryAccess());
        player.getFoodData().addAdditionalSaveData(food);
        checkProblems(problems);
        state.put("food", food.buildResult());
        PlayerTeam team = server.getScoreboard().getPlayersTeam(player.getScoreboardName());
        state.putString("team", team == null ? "" : team.getName());
        return state;
    }

    private Decoded decode(CompoundTag state, ServerPlayer player) throws IOException {
        DynamicOps<Tag> ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        CompoundTag items = compound(state, "inventory");
        int size = integer(items, "size");
        if (size != player.getInventory().getNonEquipmentItems().size())
            throw new IOException("Inventory size mismatch");
        List<ItemStack> inventory = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++)
            inventory.add(decodeStack(compound(items, "slot" + slot), ops));
        CompoundTag equipmentTag = compound(state, "equipment");
        var equipment = new EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot != EquipmentSlot.MAINHAND)
                equipment.put(slot, decodeStack(compound(equipmentTag, slot.getName()), ops));
        }
        int selected = integer(state, "selected");
        if (selected < 0 || selected >= 9) throw new IOException("Invalid selected inventory slot");
        int mode = integer(state, "mode");
        if (!GameType.isValidId(mode)) throw new IOException("Invalid game mode");
        ResourceKey<Level> dimension =
                ResourceKey.create(
                        Registries.DIMENSION, Identifier.parse(text(state, "dimension")));
        ServerLevel level = server.getLevel(dimension);
        if (level == null)
            throw new IOException("Original dimension is unavailable: " + dimension.identifier());
        for (String field : List.of("x", "y", "z")) number(state, field);
        for (String field : List.of("yaw", "pitch", "health", "absorption", "xpProgress"))
            decimal(state, field);
        if (decimal(state, "health") <= 0
                || decimal(state, "absorption") < 0
                || decimal(state, "xpProgress") < 0
                || decimal(state, "xpProgress") > 1
                || integer(state, "xpLevel") < 0
                || integer(state, "xpTotal") < 0
                || integer(state, "frozen") < 0)
            throw new IOException("Invalid vital/experience state");
        integer(state, "air");
        integer(state, "fire");
        CompoundTag food = compound(state, "food");
        int foodLevel = integer(food, "foodLevel");
        if (foodLevel < 0
                || foodLevel > 20
                || integer(food, "foodTickTimer") < 0
                || decimal(food, "foodSaturationLevel") < 0
                || decimal(food, "foodExhaustionLevel") < 0) {
            throw new IOException("Invalid food state");
        }
        var problems = new ProblemReporter.Collector();
        new FoodData()
                .readAdditionalSaveData(
                        TagValueInput.create(problems, player.registryAccess(), food));
        checkProblems(problems);
        var abilities = decode(Abilities.Packed.CODEC, ops, required(state, "abilities"));
        var effects = decode(MobEffectInstance.CODEC.listOf(), ops, required(state, "effects"));
        String name = text(state, "team");
        PlayerTeam team = name.isEmpty() ? null : server.getScoreboard().getPlayerTeam(name);
        if (!name.isEmpty() && team == null)
            throw new IOException("Original scoreboard team is unavailable: " + name);
        return new Decoded(
                state, inventory, equipment, level, GameType.byId(mode), abilities, effects, team);
    }

    private record Decoded(
            CompoundTag state,
            List<ItemStack> inventory,
            EnumMap<EquipmentSlot, ItemStack> equipment,
            ServerLevel level,
            GameType mode,
            Abilities.Packed abilities,
            List<MobEffectInstance> effects,
            PlayerTeam team) {}

    private void requireServerThread() throws IOException {
        if (!server.isSameThread())
            throw new IOException("Player state operations require the server thread");
    }

    static CompoundTag encodeStack(ItemStack stack, DynamicOps<Tag> ops) throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("empty", stack.isEmpty());
        if (!stack.isEmpty()) tag.put("stack", encode(ItemStack.CODEC, ops, stack));
        return tag;
    }

    static ItemStack decodeStack(CompoundTag tag, DynamicOps<Tag> ops) throws IOException {
        boolean empty = tag.getBoolean("empty").orElseThrow(() -> missing("empty"));
        if (empty) {
            if (tag.contains("stack")) throw new IOException("Ambiguous empty item stack");
            return ItemStack.EMPTY;
        }
        ItemStack stack = decode(ItemStack.CODEC, ops, required(tag, "stack"));
        if (stack.isEmpty()) throw new IOException("Nonempty item decoded as empty");
        return stack;
    }

    private static <T> Tag encode(Codec<T> codec, DynamicOps<Tag> ops, T value) throws IOException {
        var result = codec.encodeStart(ops, value);
        if (result.error().isPresent())
            throw new IOException("State encode failed: " + result.error().get().message());
        return result.result().orElseThrow(() -> new IOException("Missing encoded state"));
    }

    private static <T> T decode(Codec<T> codec, DynamicOps<Tag> ops, Tag value) throws IOException {
        var result = codec.parse(ops, value);
        if (result.error().isPresent())
            throw new IOException("State decode failed: " + result.error().get().message());
        return result.result().orElseThrow(() -> new IOException("Missing decoded state"));
    }

    // Envelope checks also detect syntactically-valid NBT with accidentally altered/missing data.
    static byte[] pack(UUID id, CompoundTag state) throws IOException {
        byte[] payload = compressed(state);
        CompoundTag envelope = new CompoundTag();
        envelope.putInt("version", FORMAT);
        envelope.putString("uuid", id.toString());
        envelope.putByteArray("sha256", digest(payload));
        envelope.putByteArray("payload", payload);
        return compressed(envelope);
    }

    static CompoundTag unpack(UUID id, byte[] bytes) throws IOException {
        try {
            CompoundTag envelope = uncompressed(bytes);
            if (integer(envelope, "version") != FORMAT)
                throw new IOException("Unsupported player snapshot version");
            if (!text(envelope, "uuid").equals(id.toString()))
                throw new IOException("Player snapshot UUID mismatch");
            byte[] payload = envelope.getByteArray("payload").orElseThrow(() -> missing("payload"));
            byte[] checksum = envelope.getByteArray("sha256").orElseThrow(() -> missing("sha256"));
            if (!MessageDigest.isEqual(checksum, digest(payload)))
                throw new IOException("Player snapshot checksum mismatch");
            return uncompressed(payload);
        } catch (RuntimeException exception) {
            throw new IOException("Malformed player snapshot", exception);
        }
    }

    private static byte[] compressed(CompoundTag tag) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, output);
        byte[] result = output.toByteArray();
        if (result.length > PlayerStateStoreFiles.MAX_BYTES)
            throw new IOException("Player snapshot too large");
        return result;
    }

    private static CompoundTag uncompressed(byte[] bytes) throws IOException {
        if (bytes.length > PlayerStateStoreFiles.MAX_BYTES)
            throw new IOException("Player snapshot too large");
        return NbtIo.readCompressed(
                new ByteArrayInputStream(bytes),
                NbtAccounter.create(PlayerStateStoreFiles.MAX_BYTES));
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java requires SHA-256", impossible);
        }
    }

    private static void checkProblems(ProblemReporter.Collector problems) throws IOException {
        if (!problems.isEmpty())
            throw new IOException("Invalid player state: " + problems.getReport());
    }

    private static IOException missing(String key) {
        return new IOException("Missing/invalid player state field: " + key);
    }

    private static Tag required(CompoundTag tag, String key) throws IOException {
        Tag value = tag.get(key);
        if (value == null) throw missing(key);
        return value;
    }

    private static CompoundTag compound(CompoundTag tag, String key) throws IOException {
        return tag.getCompound(key).orElseThrow(() -> missing(key));
    }

    private static String text(CompoundTag tag, String key) throws IOException {
        return tag.getString(key).orElseThrow(() -> missing(key));
    }

    private static int integer(CompoundTag tag, String key) throws IOException {
        return tag.getInt(key).orElseThrow(() -> missing(key));
    }

    private static double number(CompoundTag tag, String key) throws IOException {
        double value = tag.getDouble(key).orElseThrow(() -> missing(key));
        if (!Double.isFinite(value)) throw missing(key);
        return value;
    }

    private static float decimal(CompoundTag tag, String key) throws IOException {
        float value = tag.getFloat(key).orElseThrow(() -> missing(key));
        if (!Float.isFinite(value)) throw missing(key);
        return value;
    }
}
