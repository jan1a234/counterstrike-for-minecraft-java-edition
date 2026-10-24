package com.jan1a234.counterstrike.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Synchronize the carried slot through vanilla, including its cached slot index. */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeInvoker {
    @Invoker("ensureHasSentCarriedItem")
    void counterstrike$ensureHasSentCarriedItem();
}
