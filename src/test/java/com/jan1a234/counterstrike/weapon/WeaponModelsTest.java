package com.jan1a234.counterstrike.weapon;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WeaponModelsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(lookup)
                .forEach(pending -> pending.apply());
    }

    @Test
    void everyWeaponSelectsItsOriginalBundledModel() {
        for (WeaponType type : WeaponType.values()) {
            var stack = Weapons.create(type);
            assertEquals(
                    Identifier.fromNamespaceAndPath("counterstrike", type.id),
                    stack.get(DataComponents.ITEM_MODEL),
                    type.id);
            assertNotNull(
                    getClass().getResource("/assets/counterstrike/items/" + type.id + ".json"));
        }
    }
}
