package com.jan1a234.counterstrike.game;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.DynamicOps;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

class PlayerStateStoreItemCodecTest {
    static DynamicOps<Tag> ops;

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(lookup)
                .forEach(pending -> pending.apply());
        ops = lookup.createSerializationContext(NbtOps.INSTANCE);
    }

    @Test
    void damagedNamedEquipmentWithCustomDataRoundTrips() throws IOException {
        ItemStack original = new ItemStack(Items.DIAMOND_CHESTPLATE);
        original.setDamageValue(37);
        original.set(DataComponents.CUSTOM_NAME, Component.literal("Survival armour"));
        CompoundTag data = new CompoundTag();
        data.putString("owner", "survival player");
        data.putIntArray("values", new int[] {17, 31, 42});
        original.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        ItemStack restored =
                PlayerStateStore.decodeStack(PlayerStateStore.encodeStack(original, ops), ops);
        assertTrue(ItemStack.matches(original, restored));
        assertNotSame(original, restored);
        assertEquals(37, restored.getDamageValue());
        assertEquals(data, restored.get(DataComponents.CUSTOM_DATA).copyTag());
    }

    @Test
    void emptyAndStackCountsRoundTripExactly() throws IOException {
        assertTrue(
                PlayerStateStore.decodeStack(
                                PlayerStateStore.encodeStack(ItemStack.EMPTY, ops), ops)
                        .isEmpty());
        ItemStack original = new ItemStack(Items.DIAMOND, 47);
        assertTrue(
                ItemStack.matches(
                        original,
                        PlayerStateStore.decodeStack(
                                PlayerStateStore.encodeStack(original, ops), ops)));
    }

    @Test
    void unknownRegistryItemFailsInsteadOfBecomingAnEmptySlot() {
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "counterstrike:missing_survival_item");
        stack.putInt("count", 1);
        CompoundTag slot = new CompoundTag();
        slot.putBoolean("empty", false);
        slot.put("stack", stack);
        assertThrows(IOException.class, () -> PlayerStateStore.decodeStack(slot, ops));
    }

    @Test
    void missingAndAmbiguousStackFieldsFailClosed() {
        assertThrows(IOException.class, () -> PlayerStateStore.decodeStack(new CompoundTag(), ops));
        CompoundTag slot = new CompoundTag();
        slot.putBoolean("empty", false);
        assertThrows(IOException.class, () -> PlayerStateStore.decodeStack(slot, ops));
        slot.putBoolean("empty", true);
        slot.put("stack", new CompoundTag());
        assertThrows(IOException.class, () -> PlayerStateStore.decodeStack(slot, ops));
    }
}
