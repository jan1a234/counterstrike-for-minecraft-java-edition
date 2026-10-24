package com.jan1a234.counterstrike.client;

import com.jan1a234.counterstrike.weapon.Weapons;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import org.lwjgl.glfw.GLFW;

public class CounterStrikeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyMapping.Category category =
                KeyMapping.Category.register(
                        Identifier.fromNamespaceAndPath("counterstrike", "controls"));
        KeyMapping reload =
                KeyMappingHelper.registerKeyMapping(
                        new KeyMapping("key.counterstrike.reload", GLFW.GLFW_KEY_R, category));
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    while (reload.consumeClick()) {
                        if (client.player != null
                                && client.getConnection() != null
                                && Weapons.typeOf(client.player.getMainHandItem()) != null) {
                            client.getConnection().sendCommand("cs reload");
                        }
                    }
                });
    }
}
